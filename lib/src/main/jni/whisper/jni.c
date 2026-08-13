#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdatomic.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

struct progress_callback_context {
    JavaVM *vm;
    jobject listener;
    jmethodID on_progress;
};

struct abort_callback_context {
    atomic_bool requested;
};

static bool on_whisper_abort(void *user_data) {
    struct abort_callback_context *abort_context =
            (struct abort_callback_context *) user_data;
    return abort_context != NULL && atomic_load(&abort_context->requested);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_createAbortToken(
        JNIEnv *env, jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    struct abort_callback_context *abort_context =
            (struct abort_callback_context *) calloc(1, sizeof(struct abort_callback_context));
    if (abort_context == NULL) return 0;
    atomic_init(&abort_context->requested, false);
    return (jlong) abort_context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_requestAbort(
        JNIEnv *env, jobject thiz, jlong abort_token) {
    UNUSED(env);
    UNUSED(thiz);
    struct abort_callback_context *abort_context =
            (struct abort_callback_context *) abort_token;
    if (abort_context != NULL) atomic_store(&abort_context->requested, true);
}

JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_isAbortRequested(
        JNIEnv *env, jobject thiz, jlong abort_token) {
    UNUSED(env);
    UNUSED(thiz);
    struct abort_callback_context *abort_context =
            (struct abort_callback_context *) abort_token;
    return abort_context != NULL && atomic_load(&abort_context->requested) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeAbortToken(
        JNIEnv *env, jobject thiz, jlong abort_token) {
    UNUSED(env);
    UNUSED(thiz);
    free((struct abort_callback_context *) abort_token);
}

static void on_whisper_progress(
        struct whisper_context *ctx,
        struct whisper_state *state,
        int progress,
        void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    struct progress_callback_context *callback =
            (struct progress_callback_context *) user_data;
    if (callback == NULL || callback->listener == NULL || callback->on_progress == NULL) {
        return;
    }

    JNIEnv *callback_env = NULL;
    bool detach_after_callback = false;
    const jint env_status = (*callback->vm)->GetEnv(
            callback->vm,
            (void **) &callback_env,
            JNI_VERSION_1_6
    );
    if (env_status == JNI_EDETACHED) {
        if ((*callback->vm)->AttachCurrentThread(
                callback->vm,
                &callback_env,
                NULL
        ) != JNI_OK) {
            LOGW("Could not attach Whisper progress callback thread");
            return;
        }
        detach_after_callback = true;
    } else if (env_status != JNI_OK) {
        LOGW("Could not access JNI environment for Whisper progress callback");
        return;
    }

    (*callback_env)->CallVoidMethod(
            callback_env,
            callback->listener,
            callback->on_progress,
            (jint) progress
    );
    if ((*callback_env)->ExceptionCheck(callback_env)) {
        (*callback_env)->ExceptionDescribe(callback_env);
        (*callback_env)->ExceptionClear(callback_env);
    }
    if (detach_after_callback) {
        (*callback->vm)->DetachCurrentThread(callback->vm);
    }
}

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = use_gpu;
    context = whisper_init_from_file_with_params(model_path_chars, context_params);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getGpuBackendName(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    const size_t count = ggml_backend_dev_count();
    for (size_t index = 0; index < count; ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        const enum ggml_backend_dev_type type = device != NULL
                ? ggml_backend_dev_type(device)
                : GGML_BACKEND_DEVICE_TYPE_CPU;
        if (device != NULL &&
                (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)) {
            const char *name = ggml_backend_dev_name(device);
            const char *description = ggml_backend_dev_description(device);
            char report[512] = {0};
            snprintf(report, sizeof(report), "%s%s%s",
                    name != NULL ? name : "GPU",
                    description != NULL && description[0] != '\0' ? " · " : "",
                    description != NULL ? description : "");
            return (*env)->NewStringUTF(env, report);
        }
    }
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initVadContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_vad_context_params params = whisper_vad_default_context_params();
    // The small VAD probe deliberately stays on CPU. Whisper's separately
    // selected backend is loaded only after the automatic decision is complete.
    params.use_gpu = false;
    struct whisper_vad_context *context =
            whisper_vad_init_from_file_with_params(model_path, params);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeVadContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_vad_free((struct whisper_vad_context *) context_ptr);
}

JNIEXPORT jfloatArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_detectVadSegmentsCentiseconds(
        JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data,
        jfloat threshold, jint minimum_speech_duration_ms,
        jint minimum_silence_duration_ms, jfloat maximum_speech_duration_seconds,
        jint speech_pad_ms, jfloat overlap_seconds) {
    UNUSED(thiz);
    struct whisper_vad_context *context = (struct whisper_vad_context *) context_ptr;
    if (context == NULL) return NULL;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize sample_count = (*env)->GetArrayLength(env, audio_data);
    struct whisper_vad_params params = whisper_vad_default_params();
    params.threshold = threshold;
    params.min_speech_duration_ms = minimum_speech_duration_ms;
    params.min_silence_duration_ms = minimum_silence_duration_ms;
    params.max_speech_duration_s = maximum_speech_duration_seconds;
    params.speech_pad_ms = speech_pad_ms;
    params.samples_overlap = overlap_seconds;

    struct whisper_vad_segments *segments =
            whisper_vad_segments_from_samples(context, params, samples, sample_count);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    if (segments == NULL) return NULL;

    const int segment_count = whisper_vad_segments_n_segments(segments);
    jfloatArray result = (*env)->NewFloatArray(env, segment_count * 2);
    if (result != NULL && segment_count > 0) {
        jfloat *values = (jfloat *) malloc(sizeof(jfloat) * segment_count * 2);
        if (values == NULL) {
            whisper_vad_free_segments(segments);
            return NULL;
        }
        for (int index = 0; index < segment_count; ++index) {
            // whisper.cpp exposes VAD timestamps in centiseconds, just like
            // transcript segment timestamps. Kotlin performs the explicit
            // centisecond-to-millisecond conversion at the JNI boundary.
            values[index * 2] = whisper_vad_segments_get_segment_t0(segments, index);
            values[index * 2 + 1] = whisper_vad_segments_get_segment_t1(segments, index);
        }
        (*env)->SetFloatArrayRegion(env, result, 0, segment_count * 2, values);
        free(values);
    }
    whisper_vad_free_segments(segments);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str,
        jboolean beam_search, jint beam_size, jint best_of, jfloat temperature,
        jstring initial_prompt_str, jboolean carry_context,
        jint maximum_segment_characters, jboolean split_on_word,
        jboolean token_timestamps, jboolean suppress_blank,
        jboolean suppress_non_speech_tokens, jfloat log_probability_threshold,
        jfloat no_speech_threshold, jfloat entropy_threshold, jstring vad_model_path_str,
        jfloat vad_threshold, jint vad_min_speech_duration_ms,
        jint vad_min_silence_duration_ms, jfloat vad_max_speech_duration_seconds,
        jint vad_speech_pad_ms, jfloat vad_samples_overlap_seconds, jlong abort_token,
        jobject progress_listener) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(
            beam_search ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);
    params.language = language;
    // Passing "auto" already makes whisper_full detect the language and then
    // continue transcribing. detect_language=true is a detection-only mode
    // that deliberately returns before producing any text segments.
    params.detect_language = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = !carry_context;
    params.single_segment = false;
    params.beam_search.beam_size = beam_size;
    params.greedy.best_of = best_of;
    params.temperature = temperature;
    const char *initial_prompt = (*env)->GetStringUTFChars(env, initial_prompt_str, NULL);
    params.initial_prompt = initial_prompt[0] == '\0' ? NULL : initial_prompt;
    params.carry_initial_prompt = carry_context;
    params.max_len = maximum_segment_characters;
    params.split_on_word = split_on_word;
    params.token_timestamps = token_timestamps || maximum_segment_characters > 0;
    params.suppress_blank = suppress_blank;
    params.suppress_nst = suppress_non_speech_tokens;
    params.logprob_thold = log_probability_threshold;
    params.no_speech_thold = no_speech_threshold;
    params.entropy_thold = entropy_threshold;
    const char *vad_model_path = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
    params.vad = vad_model_path[0] != '\0';
    params.vad_model_path = params.vad ? vad_model_path : NULL;
    params.vad_params.threshold = vad_threshold;
    params.vad_params.min_speech_duration_ms = vad_min_speech_duration_ms;
    params.vad_params.min_silence_duration_ms = vad_min_silence_duration_ms;
    params.vad_params.max_speech_duration_s = vad_max_speech_duration_seconds;
    params.vad_params.speech_pad_ms = vad_speech_pad_ms;
    params.vad_params.samples_overlap = vad_samples_overlap_seconds;
    params.abort_callback = on_whisper_abort;
    params.abort_callback_user_data = (void *) abort_token;

    struct progress_callback_context progress_context = {};
    if (progress_listener != NULL) {
        (*env)->GetJavaVM(env, &progress_context.vm);
        progress_context.listener = (*env)->NewGlobalRef(env, progress_listener);
        jclass listener_class = (*env)->GetObjectClass(env, progress_listener);
        progress_context.on_progress = (*env)->GetMethodID(
                env,
                listener_class,
                "onProgress",
                "(I)V"
        );
        (*env)->DeleteLocalRef(env, listener_class);
        if (progress_context.on_progress == NULL || (*env)->ExceptionCheck(env)) {
            LOGW("Whisper progress callback is unavailable; continuing without progress updates");
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            (*env)->DeleteGlobalRef(env, progress_context.listener);
            progress_context.listener = NULL;
        } else {
            params.progress_callback = on_whisper_progress;
            params.progress_callback_user_data = &progress_context;
        }
    }

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    const int result = whisper_full(context, params, audio_data_arr, audio_data_length);
    if (result != 0) {
        LOGI("Failed to run the model: %d", result);
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language_str, language);
    (*env)->ReleaseStringUTFChars(env, initial_prompt_str, initial_prompt);
    (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path);
    if (progress_context.listener != NULL) {
        (*env)->DeleteGlobalRef(env, progress_context.listener);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getDetectedLanguage(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const int language_id = whisper_full_lang_id(context);
    const char *language = language_id >= 0 ? whisper_lang_str(language_id) : NULL;
    return (*env)->NewStringUTF(env, language != NULL ? language : "");
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
