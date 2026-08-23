#include <mutex>
#include <unordered_map>
#include <vector>

#define Java_de_matthiasennen_transcript_ai_LocalAiNative_prepareCorrectionContext \
    Java_de_matthiasennen_transcript_ai_LocalAiNative_prepareCorrectionContext_legacy
#define Java_de_matthiasennen_transcript_ai_LocalAiNative_generateCorrection \
    Java_de_matthiasennen_transcript_ai_LocalAiNative_generateCorrection_legacy
#define Java_de_matthiasennen_transcript_ai_LocalAiNative_release \
    Java_de_matthiasennen_transcript_ai_LocalAiNative_release_legacy
#include "local_ai_jni.cpp"
#undef Java_de_matthiasennen_transcript_ai_LocalAiNative_prepareCorrectionContext
#undef Java_de_matthiasennen_transcript_ai_LocalAiNative_generateCorrection
#undef Java_de_matthiasennen_transcript_ai_LocalAiNative_release

namespace {

struct CorrectionDiagnosticState {
    std::vector<std::string> pending;
};

std::mutex correction_diagnostics_mutex;
std::unordered_map<jlong, CorrectionDiagnosticState> correction_diagnostics;

void clear_correction_diagnostics(jlong handle) {
    std::lock_guard<std::mutex> lock(correction_diagnostics_mutex);
    correction_diagnostics[handle].pending.clear();
}

void add_correction_diagnostic(jlong handle, const std::string & message) {
    std::lock_guard<std::mutex> lock(correction_diagnostics_mutex);
    correction_diagnostics[handle].pending.push_back(message);
}

void erase_correction_diagnostics(jlong handle) {
    std::lock_guard<std::mutex> lock(correction_diagnostics_mutex);
    correction_diagnostics.erase(handle);
}

void set_correction_error(
    LocalEngine * engine,
    jlong handle,
    const std::string & code,
    const std::string & detail) {
    const std::string message = code + ": " + detail;
    engine->last_error = message;
    log_error(message);
    add_correction_diagnostic(handle, "FEHLER " + message);
}

std::vector<std::string> take_correction_diagnostics(jlong handle) {
    std::lock_guard<std::mutex> lock(correction_diagnostics_mutex);
    auto iterator = correction_diagnostics.find(handle);
    if (iterator == correction_diagnostics.end()) return {};
    std::vector<std::string> result = std::move(iterator->second.pending);
    iterator->second.pending.clear();
    return result;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_prepareCorrectionContext(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring common_prompt_value) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return JNI_FALSE;

    clear_correction_diagnostics(handle);
    engine->last_error.clear();

    try {
        clear_correction_session(engine);
        engine->correction_common_prompt = from_java(env, common_prompt_value);
        if (engine->correction_common_prompt.empty()) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_INPUT_EMPTY",
                "Der gemeinsame Gesprächskontext ist leer.");
            return JNI_FALSE;
        }
        add_correction_diagnostic(
            handle,
            "CONTEXT_INPUT · " + std::to_string(engine->correction_common_prompt.size()) +
                " Zeichen");

        engine->correction_base_rendered = format_correction_base(
            engine,
            engine->correction_common_prompt);
        if (engine->correction_base_rendered.empty()) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_TEMPLATE_FAILED",
                "Die Qwen-Chatvorlage hat keinen Basisprompt erzeugt.");
            clear_correction_session(engine);
            return JNI_FALSE;
        }
        add_correction_diagnostic(
            handle,
            "CONTEXT_TEMPLATE · " + std::to_string(engine->correction_base_rendered.size()) +
                " Zeichen gerendert");

        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        std::vector<llama_token> base_tokens = tokenize(
            vocab,
            engine->correction_base_rendered,
            true);
        if (base_tokens.empty()) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_TOKENIZE_FAILED",
                "Der gerenderte Basisprompt konnte nicht tokenisiert werden.");
            clear_correction_session(engine);
            return JNI_FALSE;
        }
        add_correction_diagnostic(
            handle,
            "CONTEXT_TOKENS · " + std::to_string(base_tokens.size()) +
                " Tokens · Reserve 320");

        if (static_cast<int>(base_tokens.size()) + 320 >= engine->context_size) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_TOO_LARGE",
                "Basisprompt=" + std::to_string(base_tokens.size()) +
                    " Tokens · n_ctx=" + std::to_string(engine->context_size) +
                    " · Reserve=320");
            clear_correction_session(engine);
            return JNI_FALSE;
        }

        const int effective_batch = std::min(engine->context_size, engine->batch_size);
        const int effective_micro_batch = std::min(effective_batch, engine->micro_batch_size);
        add_correction_diagnostic(
            handle,
            "CONTEXT_RUNTIME · n_ctx=" + std::to_string(engine->context_size) +
                " · n_batch=" + std::to_string(effective_batch) +
                " · n_ubatch=" + std::to_string(effective_micro_batch));

        engine->correction_context = create_context(engine);
        if (!engine->correction_context) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_CREATE_FAILED",
                "llama.cpp konnte den Korrekturkontext nicht anlegen · n_ctx=" +
                    std::to_string(engine->context_size));
            clear_correction_session(engine);
            return JNI_FALSE;
        }
        add_correction_diagnostic(handle, "CONTEXT_CREATE · erfolgreich");

        size_t offset = 0;
        int batch_number = 0;
        while (offset < base_tokens.size()) {
            const size_t tokens_left = base_tokens.size() - offset;
            const int32_t current_batch_size = static_cast<int32_t>(
                std::min(tokens_left, static_cast<size_t>(llama_n_batch(engine->correction_context))));
            llama_batch batch = llama_batch_get_one(
                base_tokens.data() + offset,
                current_batch_size);
            const int decode_code = llama_decode(engine->correction_context, batch);
            ++batch_number;
            if (decode_code != 0) {
                set_correction_error(
                    engine,
                    handle,
                    "CONTEXT_DECODE_FAILED",
                    "Batch=" + std::to_string(batch_number) +
                        " · Offset=" + std::to_string(offset) +
                        " · Batchgröße=" + std::to_string(current_batch_size) +
                        " · Code=" + std::to_string(decode_code) +
                        " · Gesamt=" + std::to_string(base_tokens.size()) + " Tokens");
                clear_correction_session(engine);
                return JNI_FALSE;
            }
            add_correction_diagnostic(
                handle,
                "CONTEXT_DECODE · Batch " + std::to_string(batch_number) +
                    " erfolgreich · Offset " + std::to_string(offset) +
                    " · " + std::to_string(current_batch_size) + " Tokens");
            offset += static_cast<size_t>(current_batch_size);
        }

        engine->correction_base_tokens =
            llama_memory_seq_pos_max(llama_get_memory(engine->correction_context), 0) + 1;
        if (engine->correction_base_tokens <= 0) {
            set_correction_error(
                engine,
                handle,
                "CONTEXT_CACHE_EMPTY",
                "Der KV-Cache enthält nach der Kontextdekodierung keine Basisposition.");
            clear_correction_session(engine);
            return JNI_FALSE;
        }
        add_correction_diagnostic(
            handle,
            "CONTEXT_READY · " + std::to_string(engine->correction_base_tokens) +
                " Basis-Tokens im KV-Cache");
        return JNI_TRUE;
    } catch (const std::exception & exception) {
        const std::string detail = exception.what() ? exception.what() : "Unbekannter nativer Fehler";
        const bool device_lost = detail.find("DeviceLost") != std::string::npos ||
            detail.find("DEVICE_LOST") != std::string::npos ||
            detail.find("device lost") != std::string::npos;
        engine->gpu_fallback_used = engine->gpu_fallback_used || device_lost;
        set_correction_error(
            engine,
            handle,
            device_lost ? "VULKAN_DEVICE_LOST" : "CONTEXT_NATIVE_EXCEPTION",
            detail);
        clear_correction_session(engine);
        return JNI_FALSE;
    } catch (...) {
        set_correction_error(
            engine,
            handle,
            "CONTEXT_NATIVE_EXCEPTION",
            "Unbekannte native C++-Exception.");
        clear_correction_session(engine);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_generateCorrection(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring target_prompt_value,
    jint maximum_output_tokens) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model || !engine->correction_context ||
        engine->correction_base_tokens <= 0) return nullptr;

    engine->last_error.clear();

    try {
        llama_memory_t memory = llama_get_memory(engine->correction_context);
        if (!llama_memory_seq_rm(memory, 0, engine->correction_base_tokens, -1)) {
            set_correction_error(
                engine,
                handle,
                "TARGET_RESET_FAILED",
                "Der KV-Cache konnte nicht auf den gemeinsamen Basiskontext zurückgesetzt werden.");
            return nullptr;
        }

        const std::string target_prompt = from_java(env, target_prompt_value);
        if (target_prompt.empty()) {
            set_correction_error(engine, handle, "TARGET_INPUT_EMPTY", "Der Zielauftrag ist leer.");
            return nullptr;
        }
        add_correction_diagnostic(
            handle,
            "TARGET_INPUT · " + std::to_string(target_prompt.size()) + " Zeichen");

        const std::string full_prompt = format_correction_with_target(
            engine,
            engine->correction_common_prompt,
            target_prompt);
        if (full_prompt.rfind(engine->correction_base_rendered, 0) != 0) {
            set_correction_error(
                engine,
                handle,
                "TARGET_TEMPLATE_PREFIX_FAILED",
                "Die Qwen-Chatvorlage hat keinen wiederverwendbaren Basisprefix erzeugt.");
            return nullptr;
        }
        const std::string target_delta = full_prompt.substr(engine->correction_base_rendered.size());
        add_correction_diagnostic(
            handle,
            "TARGET_TEMPLATE · Delta " + std::to_string(target_delta.size()) + " Zeichen");

        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        std::vector<llama_token> target_tokens = tokenize(vocab, target_delta, false);
        if (target_tokens.empty()) {
            set_correction_error(
                engine,
                handle,
                "TARGET_TOKENIZE_FAILED",
                "Der Zielauftrag konnte nicht tokenisiert werden.");
            return nullptr;
        }

        const int remaining_context = engine->context_size - engine->correction_base_tokens -
            static_cast<int>(target_tokens.size()) - 1;
        if (remaining_context < 32) {
            set_correction_error(
                engine,
                handle,
                "TARGET_CONTEXT_TOO_SMALL",
                "Basis=" + std::to_string(engine->correction_base_tokens) +
                    " · Ziel=" + std::to_string(target_tokens.size()) +
                    " · n_ctx=" + std::to_string(engine->context_size));
            return nullptr;
        }
        const int output_limit = std::min(
            std::clamp(static_cast<int>(maximum_output_tokens), 32, 512),
            remaining_context);
        add_correction_diagnostic(
            handle,
            "TARGET_TOKENS · " + std::to_string(target_tokens.size()) +
                " Tokens · Antwortlimit " + std::to_string(output_limit));

        size_t offset = 0;
        int batch_number = 0;
        while (offset < target_tokens.size()) {
            const size_t tokens_left = target_tokens.size() - offset;
            const int32_t current_batch_size = static_cast<int32_t>(
                std::min(tokens_left, static_cast<size_t>(llama_n_batch(engine->correction_context))));
            llama_batch batch = llama_batch_get_one(
                target_tokens.data() + offset,
                current_batch_size);
            const int decode_code = llama_decode(engine->correction_context, batch);
            ++batch_number;
            if (decode_code != 0) {
                set_correction_error(
                    engine,
                    handle,
                    "TARGET_DECODE_FAILED",
                    "Batch=" + std::to_string(batch_number) +
                        " · Offset=" + std::to_string(offset) +
                        " · Batchgröße=" + std::to_string(current_batch_size) +
                        " · Code=" + std::to_string(decode_code) +
                        " · Gesamt=" + std::to_string(target_tokens.size()) + " Tokens");
                return nullptr;
            }
            add_correction_diagnostic(
                handle,
                "TARGET_DECODE · Batch " + std::to_string(batch_number) +
                    " erfolgreich · " + std::to_string(current_batch_size) + " Tokens");
            offset += static_cast<size_t>(current_batch_size);
        }

        const SteadyClock::time_point generation_started = SteadyClock::now();
        const SampleResult output = sample_response(
            engine->correction_context,
            vocab,
            output_limit,
            CORRECTION_GRAMMAR,
            generation_started);
        add_correction_diagnostic(
            handle,
            "GENERATION · " + std::to_string(output.generated_tokens) +
                " Tokens · Ende " + output.finish_reason +
                " · " + std::to_string(elapsed_ms(generation_started, SteadyClock::now())) + " ms");
        if (output.text.empty()) {
            set_correction_error(
                engine,
                handle,
                "GENERATION_FAILED",
                "Keine verwertbare Antwort · Tokens=" + std::to_string(output.generated_tokens) +
                    " · Ende=" + output.finish_reason);
            return nullptr;
        }
        add_correction_diagnostic(
            handle,
            "GENERATION_RESULT · " + std::to_string(output.text.size()) + " Zeichen empfangen");
        return to_java(env, output.text);
    } catch (const std::exception & exception) {
        const std::string detail = exception.what() ? exception.what() : "Unbekannter nativer Fehler";
        const bool device_lost = detail.find("DeviceLost") != std::string::npos ||
            detail.find("DEVICE_LOST") != std::string::npos ||
            detail.find("device lost") != std::string::npos;
        engine->gpu_fallback_used = engine->gpu_fallback_used || device_lost;
        set_correction_error(
            engine,
            handle,
            device_lost ? "VULKAN_DEVICE_LOST" : "GENERATION_NATIVE_EXCEPTION",
            detail);
        return nullptr;
    } catch (...) {
        set_correction_error(
            engine,
            handle,
            "GENERATION_NATIVE_EXCEPTION",
            "Unbekannte native C++-Exception.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_consumeCorrectionDiagnostics(
    JNIEnv * env,
    jobject,
    jlong handle) {
    const std::vector<std::string> values = take_correction_diagnostics(handle);
    return values.empty() ? nullptr : to_java_generation_result(env, values);
}

extern "C" JNIEXPORT void JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_release(
    JNIEnv * env,
    jobject thiz,
    jlong handle) {
    erase_correction_diagnostics(handle);
    Java_de_matthiasennen_transcript_ai_LocalAiNative_release_legacy(
        env,
        thiz,
        handle);
}
