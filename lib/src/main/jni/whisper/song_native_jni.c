#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>

#define TAG "TranscriptSongNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define CRISPASR_LIBRARY "libcrispasr-transcript.so"

typedef void crispasr_session;

typedef struct {
    int32_t abi_version;
    int32_t n_threads;
    int32_t use_gpu;
    int32_t verbosity;
    int32_t flash_attn;
    int32_t n_gpu_layers;
    int32_t reserved[6];
} crispasr_open_params_v1;

typedef crispasr_session *(*open_explicit_fn)(const char *, const char *, int);
typedef crispasr_session *(*open_with_params_fn)(const char *, const char *, const crispasr_open_params_v1 *);
typedef void (*close_fn)(crispasr_session *);
typedef int (*separate_fn)(crispasr_session *, const float *, int);
typedef const char *(*stem_name_fn)(crispasr_session *, int);
typedef const float *(*stem_fn)(crispasr_session *, int, int *);
typedef int (*sample_rate_fn)(crispasr_session *);

static void *g_crispasr = NULL;
static open_explicit_fn g_open_explicit = NULL;
static open_with_params_fn g_open_with_params = NULL;
static close_fn g_close = NULL;
static separate_fn g_separate = NULL;
static stem_name_fn g_stem_name = NULL;
static stem_fn g_stem = NULL;
static sample_rate_fn g_sample_rate = NULL;
static pthread_mutex_t g_runtime_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_error_mutex = PTHREAD_MUTEX_INITIALIZER;
static char g_last_error[512] = "";

static void set_error(const char *message) {
    pthread_mutex_lock(&g_error_mutex);
    snprintf(g_last_error, sizeof(g_last_error), "%s", message != NULL ? message : "Unbekannter nativer Fehler.");
    pthread_mutex_unlock(&g_error_mutex);
    LOGE("%s", g_last_error);
}

static void clear_error(void) {
    pthread_mutex_lock(&g_error_mutex);
    g_last_error[0] = '\0';
    pthread_mutex_unlock(&g_error_mutex);
}

static void *load_symbol(const char *name) {
    dlerror();
    void *symbol = dlsym(g_crispasr, name);
    const char *error = dlerror();
    if (symbol == NULL || error != NULL) {
        char buffer[512];
        snprintf(buffer, sizeof(buffer), "CrispASR-Symbol fehlt: %s (%s)", name, error != NULL ? error : "unbekannt");
        set_error(buffer);
        return NULL;
    }
    return symbol;
}

static int ensure_runtime_loaded(void) {
    pthread_mutex_lock(&g_runtime_mutex);
    if (g_crispasr != NULL && g_open_explicit != NULL && g_open_with_params != NULL && g_close != NULL &&
        g_separate != NULL && g_stem_name != NULL && g_stem != NULL &&
        g_sample_rate != NULL) {
        pthread_mutex_unlock(&g_runtime_mutex);
        return 1;
    }

    clear_error();
    dlerror();
    g_crispasr = dlopen(CRISPASR_LIBRARY, RTLD_NOW | RTLD_LOCAL);
    if (g_crispasr == NULL) {
        const char *error = dlerror();
        char buffer[512];
        snprintf(buffer, sizeof(buffer), "CrispASR konnte nicht geladen werden: %s", error != NULL ? error : "unbekannter Loader-Fehler");
        set_error(buffer);
        pthread_mutex_unlock(&g_runtime_mutex);
        return 0;
    }

    g_open_explicit = (open_explicit_fn) load_symbol("crispasr_session_open_explicit");
    g_open_with_params = (open_with_params_fn) load_symbol("crispasr_session_open_with_params");
    g_close = (close_fn) load_symbol("crispasr_session_close");
    g_separate = (separate_fn) load_symbol("crispasr_session_separate");
    g_stem_name = (stem_name_fn) load_symbol("crispasr_session_separate_stem_name");
    g_stem = (stem_fn) load_symbol("crispasr_session_separate_stem");
    g_sample_rate = (sample_rate_fn) load_symbol("crispasr_session_separate_sample_rate");

    const int ok = g_open_explicit != NULL && g_open_with_params != NULL && g_close != NULL &&
                   g_separate != NULL && g_stem_name != NULL && g_stem != NULL && g_sample_rate != NULL;
    if (!ok) {
        dlclose(g_crispasr);
        g_crispasr = NULL;
    }
    pthread_mutex_unlock(&g_runtime_mutex);
    return ok;
}

static int is_vocals_name(const char *name) {
    if (name == NULL) return 0;
    return strcasecmp(name, "vocals") == 0 || strcasecmp(name, "vocal") == 0;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_CrispSongSeparatorNative_open(
        JNIEnv *env, jobject thiz, jstring model_path, jint threads, jboolean prefer_gpu) {
    (void) thiz;
    if (model_path == NULL) {
        set_error("GGUF-Modellpfad fehlt.");
        return 0;
    }
    if (!ensure_runtime_loaded()) return 0;

    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) {
        set_error("GGUF-Modellpfad konnte nicht gelesen werden.");
        return 0;
    }
    clear_error();

    crispasr_open_params_v1 params;
    memset(&params, 0, sizeof(params));
    params.abi_version = 2;
    params.n_threads = threads > 0 ? threads : 1;
    params.use_gpu = prefer_gpu == JNI_TRUE ? 1 : 0;
    params.verbosity = 0;
    params.flash_attn = 0;
    params.n_gpu_layers = -1;

    crispasr_session *session = g_open_with_params(
        path,
        "mel-band-roformer",
        &params
    );
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (session == NULL) {
        set_error("Kim Vocal 2 Native/GGUF konnte nicht geöffnet werden.");
        return 0;
    }
    return (jlong) (intptr_t) session;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_CrispSongSeparatorNative_sampleRate(
        JNIEnv *env, jobject thiz, jlong session_ptr) {
    (void) env;
    (void) thiz;
    crispasr_session *session = (crispasr_session *) (intptr_t) session_ptr;
    if (session == NULL || !ensure_runtime_loaded()) {
        set_error("Native Kim-Session ist nicht verfügbar.");
        return -1;
    }
    return (jint) g_sample_rate(session);
}

JNIEXPORT jfloatArray JNICALL
Java_com_whispercpp_whisper_CrispSongSeparatorNative_separateVocals(
        JNIEnv *env, jobject thiz, jlong session_ptr, jfloatArray audio) {
    (void) thiz;
    crispasr_session *session = (crispasr_session *) (intptr_t) session_ptr;
    if (session == NULL || audio == NULL || !ensure_runtime_loaded()) {
        set_error("Native Kim-Inferenz hat ungültige Eingabedaten erhalten.");
        return NULL;
    }

    const jsize sample_count = (*env)->GetArrayLength(env, audio);
    if (sample_count <= 0 || (sample_count % 2) != 0) {
        set_error("Native Kim-Inferenz benötigt interleaved Stereo-PCM.");
        return NULL;
    }
    const int frames = (int) (sample_count / 2);
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (samples == NULL) {
        set_error("Native Kim-Eingabedaten konnten nicht gebunden werden.");
        return NULL;
    }

    clear_error();
    const int stem_count = g_separate(session, samples, frames);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (stem_count <= 0) {
        set_error("CrispASR konnte den Audioabschnitt nicht trennen.");
        return NULL;
    }

    int vocal_index = -1;
    for (int index = 0; index < stem_count; ++index) {
        const char *name = g_stem_name(session, index);
        if (is_vocals_name(name)) {
            vocal_index = index;
            break;
        }
    }
    if (vocal_index < 0) {
        set_error("CrispASR lieferte keinen Vocals-Stem.");
        return NULL;
    }

    int output_frames = 0;
    const float *vocals = g_stem(session, vocal_index, &output_frames);
    if (vocals == NULL || output_frames <= 0 || output_frames > INT32_MAX / 2) {
        set_error("CrispASR lieferte ungültige Vocals-Daten.");
        return NULL;
    }

    const jsize output_samples = (jsize) (output_frames * 2);
    jfloatArray result = (*env)->NewFloatArray(env, output_samples);
    if (result == NULL) {
        set_error("Vocals-Ausgabe konnte nicht im Java-Speicher angelegt werden.");
        return NULL;
    }
    (*env)->SetFloatArrayRegion(env, result, 0, output_samples, vocals);
    if ((*env)->ExceptionCheck(env)) {
        set_error("Vocals-Ausgabe konnte nicht nach Java kopiert werden.");
        return NULL;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_CrispSongSeparatorNative_close(
        JNIEnv *env, jobject thiz, jlong session_ptr) {
    (void) env;
    (void) thiz;
    crispasr_session *session = (crispasr_session *) (intptr_t) session_ptr;
    if (session != NULL && ensure_runtime_loaded()) {
        g_close(session);
    }
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_CrispSongSeparatorNative_lastError(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    char copy[512];
    pthread_mutex_lock(&g_error_mutex);
    snprintf(copy, sizeof(copy), "%s", g_last_error);
    pthread_mutex_unlock(&g_error_mutex);
    return (*env)->NewStringUTF(env, copy);
}
