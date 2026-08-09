#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <codecvt>
#include <locale>
#include <memory>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * TAG = "TranscriptLocalAI";
constexpr int PROMPT_BATCH_SIZE = 1024;

struct LocalEngine {
    llama_model * model = nullptr;
    int context_size = 8192;
    int thread_count = 4;
};

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

std::string from_java(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_java(JNIEnv * env, const std::string & value) {
    try {
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> converter;
        const std::u16string utf16 = converter.from_bytes(value);
        return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                              static_cast<jsize>(utf16.size()));
    } catch (...) {
        return env->NewStringUTF(value.c_str());
    }
}

std::string format_prompt(llama_model * model, const std::string & user_prompt) {
    const bool is_self_test = user_prompt.rfind("[[SELF_TEST]]", 0) == 0;
    const std::string effective_user_prompt = is_self_test
        ? user_prompt.substr(std::string("[[SELF_TEST]]").size())
        : user_prompt;
    const std::string system_prompt = is_self_test
        ? "Du bist ein lokaler KI-Assistent. Antworte direkt, kurz und auf Deutsch. Befolge nur die Benutzerfrage."
        : "Du korrigierst deutsche oder englische Spracherkennungs-Transkripte. "
        "Korrigiere ausschließlich Rechtschreibung, Zeichensetzung, Groß- und "
        "Kleinschreibung sowie eindeutig falsch erkannte Wörter. Erfinde keine "
        "Informationen, formuliere nicht um, fasse nichts zusammen und ändere keine "
        "Namen ohne sicheren Hinweis im Text. Bei jeder Unsicherheit behältst du den "
        "Originaltext exakt bei, insbesondere bei Gesang, Dialekt, Fülllauten, "
        "Gebrabbel oder ungewöhnlichen Formulierungen. Text vor und nach dem Ziel "
        "dient nur als Kontext und darf nicht ausgegeben werden. Jede Zielzeile beginnt mit "
        "einer Marke wie "
        "[[SEGMENT_0001]]. Gib exakt dieselben Marken in derselben Reihenfolge aus, "
        "jeweils gefolgt vom korrigierten Text. Keine Einleitung, keine Erklärung, "
        "kein Markdown und kein Denkprotokoll.";

    llama_chat_message messages[2] = {
        {"system", system_prompt.c_str()},
        {"user", effective_user_prompt.c_str()},
    };
    const char * chat_template = llama_model_chat_template(model, nullptr);
    int32_t required = llama_chat_apply_template(
        chat_template, messages, 2, true, nullptr, 0);
    if (required <= 0) {
        return system_prompt + "\n\n" + effective_user_prompt + "\n\nAntwort:\n";
    }
    std::vector<char> buffer(static_cast<size_t>(required) + 1U);
    const int32_t written = llama_chat_apply_template(
        chat_template, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    const int32_t required = -llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (required <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(required));
    const int32_t count = llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(),
        static_cast<int32_t>(tokens.size()), true, true);
    if (count <= 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t written = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    return written > 0 ? std::string(buffer.data(), static_cast<size_t>(written)) : std::string();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_create(
    JNIEnv * env, jobject, jstring model_path_value, jint context_size, jint thread_count) {
    const std::string model_path = from_java(env, model_path_value);
    if (model_path.empty()) return 0;

    llama_backend_init();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
    if (!model) {
        log_error("Model load failed: " + model_path);
        llama_backend_free();
        return 0;
    }

    auto * engine = new LocalEngine();
    engine->model = model;
    engine->context_size = std::max(2048, static_cast<int>(context_size));
    engine->thread_count = std::clamp(static_cast<int>(thread_count), 2, 8);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_generate(
    JNIEnv * env, jobject, jlong handle, jstring prompt_value, jint maximum_output_tokens) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return nullptr;

    const std::string user_prompt = from_java(env, prompt_value);
    const std::string prompt = format_prompt(engine->model, user_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> prompt_tokens = tokenize(vocab, prompt);
    if (prompt_tokens.empty()) return nullptr;

    const int remaining_context = engine->context_size - static_cast<int>(prompt_tokens.size()) - 1;
    if (remaining_context < 64) {
        log_error("Prompt leaves too little context for a corrected response.");
        return nullptr;
    }
    const int output_limit = std::min(
        std::clamp(static_cast<int>(maximum_output_tokens), 64, 4096),
        remaining_context);

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = engine->context_size;
    context_params.n_batch = std::min(engine->context_size, PROMPT_BATCH_SIZE);
    context_params.n_ubatch = std::min(engine->context_size, 512);
    context_params.n_threads = engine->thread_count;
    context_params.n_threads_batch = engine->thread_count;
    context_params.no_perf = true;

    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(engine->model, context_params), llama_free);
    if (!context) return nullptr;

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(sampler_params), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.10f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(0x51A17U));

    size_t prompt_offset = 0;
    while (prompt_offset < prompt_tokens.size()) {
        const size_t tokens_left = prompt_tokens.size() - prompt_offset;
        const int32_t current_batch_size = static_cast<int32_t>(
            std::min(tokens_left, static_cast<size_t>(PROMPT_BATCH_SIZE)));
        llama_batch batch = llama_batch_get_one(
            prompt_tokens.data() + prompt_offset, current_batch_size);
        if (llama_decode(context.get(), batch) != 0) {
            log_error("Prompt decoding failed at token offset " + std::to_string(prompt_offset));
            return nullptr;
        }
        prompt_offset += static_cast<size_t>(current_batch_size);
    }

    std::string output;
    output.reserve(static_cast<size_t>(output_limit) * 4U);
    for (int generated = 0; generated < output_limit; ++generated) {
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += token_piece(vocab, token);
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context.get(), batch) != 0) return nullptr;
    }
    return output.empty() ? nullptr : to_java(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_release(
    JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine) return;
    if (engine->model) llama_model_free(engine->model);
    delete engine;
    llama_backend_free();
}
