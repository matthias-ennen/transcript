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
constexpr const char * FREE_TEST_MARKER = "[[FREE_TEST]]";
constexpr const char * CORRECTION_GRAMMAR = R"GBNF(
root ::= "{" ws "\"result\"" ws ":" ws string "}" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
ws ::= [ \t\n]*
)GBNF";

const std::string CORRECTION_SYSTEM_PROMPT =
    "Du korrigierst ein lokal erzeugtes Whisper-Rohtranskript. Der gemeinsame "
    "Gesprächskontext wird einmal bereitgestellt und ist schreibgeschützt. Danach "
    "erhältst du jeweils genau ein Zielsegment. Korrigiere ausschließlich dieses "
    "Zielsegment anhand des Gesprächskontexts. Erlaubt sind Rechtschreibung, "
    "Zeichensetzung, Groß- und Kleinschreibung sowie im Kontext eindeutig falsch "
    "erkannte Wörter. Erhalte Bedeutung, Sprechstil, Wiederholungen und "
    "Informationsgehalt. Ergänze keine neuen Informationen. Bei Unsicherheit "
    "behalte den Whisper-Rohtext bei. Antworte ausschließlich als JSON-Objekt mit "
    "genau einem Feld result; result enthält immer den vollständigen Zieltext. "
    "Keine Erklärungen, keine Segmentnummer und kein Markdown.";

struct LocalEngine {
    llama_model * model = nullptr;
    int context_size = 4096;
    int thread_count = 4;
    llama_context * correction_context = nullptr;
    int correction_base_tokens = 0;
    std::string correction_common_prompt;
    std::string correction_base_rendered;
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

std::string render_chat(
    llama_model * model,
    const std::vector<llama_chat_message> & messages,
    bool add_assistant) {
    const char * chat_template = llama_model_chat_template(model, nullptr);
    const int32_t required = llama_chat_apply_template(
        chat_template,
        messages.data(),
        messages.size(),
        add_assistant,
        nullptr,
        0);
    if (required <= 0) return {};
    std::vector<char> buffer(static_cast<size_t>(required) + 1U);
    const int32_t written = llama_chat_apply_template(
        chat_template,
        messages.data(),
        messages.size(),
        add_assistant,
        buffer.data(),
        static_cast<int32_t>(buffer.size()));
    if (written <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::string format_free_prompt(llama_model * model, const std::string & marked_prompt) {
    const bool marked = marked_prompt.rfind(FREE_TEST_MARKER, 0) == 0;
    const std::string user_prompt = marked
        ? marked_prompt.substr(std::string(FREE_TEST_MARKER).size())
        : marked_prompt;
    const std::string system_prompt =
        "Du bist ein vollständig lokaler KI-Assistent. Bearbeite die Benutzeranfrage "
        "direkt. Antworte in der Sprache der Anfrage. Es gibt für diesen freien "
        "KI-Test keine besondere Transkript-Korrekturstruktur.";
    const std::vector<llama_chat_message> messages = {
        {"system", system_prompt.c_str()},
        {"user", user_prompt.c_str()},
    };
    std::string rendered = render_chat(model, messages, true);
    if (!rendered.empty()) return rendered;
    return system_prompt + "\n\n" + user_prompt + "\n\nAntwort:\n";
}

std::string format_correction_base(llama_model * model, const std::string & common_prompt) {
    const std::string acknowledgement = "Gesprächskontext gelesen.";
    const std::vector<llama_chat_message> messages = {
        {"system", CORRECTION_SYSTEM_PROMPT.c_str()},
        {"user", common_prompt.c_str()},
        {"assistant", acknowledgement.c_str()},
    };
    return render_chat(model, messages, false);
}

std::string format_correction_with_target(
    llama_model * model,
    const std::string & common_prompt,
    const std::string & target_prompt) {
    const std::string acknowledgement = "Gesprächskontext gelesen.";
    const std::vector<llama_chat_message> messages = {
        {"system", CORRECTION_SYSTEM_PROMPT.c_str()},
        {"user", common_prompt.c_str()},
        {"assistant", acknowledgement.c_str()},
        {"user", target_prompt.c_str()},
    };
    return render_chat(model, messages, true);
}

std::vector<llama_token> tokenize(
    const llama_vocab * vocab,
    const std::string & prompt,
    bool add_special) {
    const int32_t required = -llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        add_special,
        true);
    if (required <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(required));
    const int32_t count = llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        add_special,
        true);
    if (count <= 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t written = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<int32_t>(buffer.size()),
        0,
        true);
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true);
    }
    return written > 0
        ? std::string(buffer.data(), static_cast<size_t>(written))
        : std::string();
}

llama_context * create_context(const LocalEngine * engine) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = engine->context_size;
    params.n_batch = std::min(engine->context_size, PROMPT_BATCH_SIZE);
    params.n_ubatch = std::min(engine->context_size, 512);
    params.n_threads = engine->thread_count;
    params.n_threads_batch = engine->thread_count;
    params.no_perf = true;
    return llama_init_from_model(engine->model, params);
}

bool decode_tokens(llama_context * context, std::vector<llama_token> & tokens) {
    size_t offset = 0;
    while (offset < tokens.size()) {
        const size_t tokens_left = tokens.size() - offset;
        const int32_t current_batch_size = static_cast<int32_t>(
            std::min(tokens_left, static_cast<size_t>(PROMPT_BATCH_SIZE)));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, current_batch_size);
        if (llama_decode(context, batch) != 0) {
            log_error("Prompt decoding failed at token offset " + std::to_string(offset));
            return false;
        }
        offset += static_cast<size_t>(current_batch_size);
    }
    return true;
}

std::string sample_response(
    llama_context * context,
    const llama_vocab * vocab,
    int output_limit,
    const char * grammar) {
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(sampler_params), llama_sampler_free);
    if (grammar) {
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!grammar_sampler) {
            log_error("Correction grammar could not be initialized.");
            return {};
        }
        llama_sampler_chain_add(sampler.get(), grammar_sampler);
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(grammar ? 0.0f : 0.20f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(0x51A17U));

    std::string output;
    output.reserve(static_cast<size_t>(output_limit) * 4U);
    for (int generated = 0; generated < output_limit; ++generated) {
        const llama_token token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += token_piece(vocab, token);
        llama_token mutable_token = token;
        llama_batch batch = llama_batch_get_one(&mutable_token, 1);
        if (llama_decode(context, batch) != 0) return {};
    }
    return output;
}

void clear_correction_session(LocalEngine * engine) {
    if (engine->correction_context) {
        llama_free(engine->correction_context);
        engine->correction_context = nullptr;
    }
    engine->correction_base_tokens = 0;
    engine->correction_common_prompt.clear();
    engine->correction_base_rendered.clear();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_create(
    JNIEnv * env,
    jobject,
    jstring model_path_value,
    jint context_size,
    jint thread_count) {
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
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring prompt_value,
    jint maximum_output_tokens) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return nullptr;

    const std::string user_prompt = from_java(env, prompt_value);
    const std::string prompt = format_free_prompt(engine->model, user_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> prompt_tokens = tokenize(vocab, prompt, true);
    if (prompt_tokens.empty()) return nullptr;

    const int remaining_context = engine->context_size - static_cast<int>(prompt_tokens.size()) - 1;
    if (remaining_context < 64) {
        log_error("Prompt leaves too little context for a free response.");
        return nullptr;
    }
    const int output_limit = std::min(
        std::clamp(static_cast<int>(maximum_output_tokens), 64, 4096),
        remaining_context);

    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        create_context(engine), llama_free);
    if (!context || !decode_tokens(context.get(), prompt_tokens)) return nullptr;

    const std::string output = sample_response(context.get(), vocab, output_limit, nullptr);
    return output.empty() ? nullptr : to_java(env, output);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_prepareCorrectionContext(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring common_prompt_value) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return JNI_FALSE;

    clear_correction_session(engine);
    engine->correction_common_prompt = from_java(env, common_prompt_value);
    if (engine->correction_common_prompt.empty()) return JNI_FALSE;
    engine->correction_base_rendered = format_correction_base(
        engine->model,
        engine->correction_common_prompt);
    if (engine->correction_base_rendered.empty()) {
        clear_correction_session(engine);
        return JNI_FALSE;
    }

    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> base_tokens = tokenize(
        vocab,
        engine->correction_base_rendered,
        true);
    if (base_tokens.empty() ||
        static_cast<int>(base_tokens.size()) + 320 >= engine->context_size) {
        log_error("Shared correction context is too large.");
        clear_correction_session(engine);
        return JNI_FALSE;
    }

    engine->correction_context = create_context(engine);
    if (!engine->correction_context ||
        !decode_tokens(engine->correction_context, base_tokens)) {
        clear_correction_session(engine);
        return JNI_FALSE;
    }
    engine->correction_base_tokens =
        llama_memory_seq_pos_max(llama_get_memory(engine->correction_context), 0) + 1;
    return engine->correction_base_tokens > 0 ? JNI_TRUE : JNI_FALSE;
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

    llama_memory_t memory = llama_get_memory(engine->correction_context);
    if (!llama_memory_seq_rm(memory, 0, engine->correction_base_tokens, -1)) {
        log_error("Could not reset correction context to its shared base.");
        return nullptr;
    }

    const std::string target_prompt = from_java(env, target_prompt_value);
    const std::string full_prompt = format_correction_with_target(
        engine->model,
        engine->correction_common_prompt,
        target_prompt);
    if (full_prompt.rfind(engine->correction_base_rendered, 0) != 0) {
        log_error("Model chat template did not produce a reusable correction prefix.");
        return nullptr;
    }
    const std::string target_delta = full_prompt.substr(engine->correction_base_rendered.size());
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> target_tokens = tokenize(vocab, target_delta, false);
    if (target_tokens.empty()) return nullptr;

    const int remaining_context = engine->context_size - engine->correction_base_tokens -
        static_cast<int>(target_tokens.size()) - 1;
    if (remaining_context < 32) {
        log_error("Target segment leaves too little correction response context.");
        return nullptr;
    }
    const int output_limit = std::min(
        std::clamp(static_cast<int>(maximum_output_tokens), 32, 512),
        remaining_context);
    if (!decode_tokens(engine->correction_context, target_tokens)) return nullptr;

    const std::string output = sample_response(
        engine->correction_context,
        vocab,
        output_limit,
        CORRECTION_GRAMMAR);
    return output.empty() ? nullptr : to_java(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_release(
    JNIEnv *,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine) return;
    clear_correction_session(engine);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
    llama_backend_free();
}
