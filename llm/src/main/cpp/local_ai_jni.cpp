#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <codecvt>
#include <exception>
#include <locale>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "jinja/value.h"
#include "llama.h"

namespace {

constexpr const char * TAG = "TranscriptLocalAI";
constexpr int PROMPT_BATCH_SIZE = 1024;
constexpr const char * FREE_TEST_MARKER = "[[FREE_TEST]]";
constexpr int CHAT_SUFFIX_TOKEN_RESERVE = 16;
using SteadyClock = std::chrono::steady_clock;
constexpr const char * CORRECTION_GRAMMAR = R"GBNF(
root ::= "{" ws "\"result\"" ws ":" ws string "}" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
ws ::= [ \t\n]*
)GBNF";

const std::string CORRECTION_SYSTEM_PROMPT =
    "Du korrigierst genau ein Zielsegment eines lokal erzeugten Whisper-Rohtranskripts. "
    "Korrigiere ausschließlich eindeutige Rechtschreib-, Grammatik- und "
    "Zeichensetzungsfehler sowie anhand des bereitgestellten Gesprächskontexts "
    "eindeutig falsch erkannte Wörter. Verändere weder Bedeutung noch "
    "Informationsgehalt oder Sprechstil. Lasse nichts weg, füge nichts hinzu und "
    "formuliere nicht unnötig um. Ist keine eindeutige Korrektur erforderlich, "
    "übernimm den Zieltext unverändert. Der Gesprächskontext ist schreibgeschützt. "
    "Antworte ausschließlich als JSON-Objekt mit genau einem Feld result. result "
    "enthält immer den vollständigen korrigierten oder unveränderten Zieltext. "
    "Gib keine Erklärung, Analyse, Segmentnummer, Markdown-Formatierung, weiteren "
    "Felder oder sonstigen Text aus.";

const std::string FREE_SYSTEM_PROMPT =
    "Du bist ein vollständig lokaler KI-Assistent. Bearbeite die Benutzeranfrage "
    "direkt und antworte in der Sprache der Anfrage. Behalte den Inhalt der "
    "laufenden Unterhaltung im Gedächtnis. Es gibt für diese freie Unterhaltung "
    "keine besondere Transkript-Korrekturstruktur.";

std::string token_piece(const llama_vocab * vocab, llama_token token);

struct LocalChatTemplate {
    std::string source;
    jinja::program program;
    std::string bos_token;
    std::string eos_token;

    LocalChatTemplate(
        std::string source_value,
        std::string bos_token_value,
        std::string eos_token_value)
        : source(std::move(source_value)),
          program(jinja::parse_from_tokens(jinja::lexer().tokenize(source))),
          bos_token(std::move(bos_token_value)),
          eos_token(std::move(eos_token_value)) {}
};

struct LocalEngine {
    llama_model * model = nullptr;
    std::unique_ptr<LocalChatTemplate> chat_template;
    int context_size = 4096;
    int thread_count = 4;
    llama_context * free_context = nullptr;
    std::vector<std::pair<std::string, std::string>> free_messages;
    std::string free_rendered_history;
    std::string last_error;
    llama_context * correction_context = nullptr;
    int correction_base_tokens = 0;
    std::string correction_common_prompt;
    std::string correction_base_rendered;
};

struct RenderedChat {
    std::string prompt;
    bool thinking_disabled = true;
};

struct SampleResult {
    std::string text;
    int generated_tokens = 0;
    long time_to_first_token_ms = 0;
    long answer_generation_ms = 0;
    std::string finish_reason = "token_limit";
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

long elapsed_ms(SteadyClock::time_point start, SteadyClock::time_point end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

jobjectArray to_java_generation_result(
    JNIEnv * env,
    const std::vector<std::string> & values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(values.size()),
        string_class,
        nullptr);
    env->DeleteLocalRef(string_class);
    if (!result) return nullptr;
    for (size_t index = 0; index < values.size(); ++index) {
        jstring value = to_java(env, values[index]);
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

RenderedChat render_chat(
    LocalEngine * engine,
    const std::vector<std::pair<std::string, std::string>> & messages,
    bool add_assistant) {
    if (!engine || !engine->chat_template) return {};
    nlohmann::ordered_json message_values = nlohmann::ordered_json::array();
    for (const auto & message : messages) {
        message_values.push_back({
            {"role", message.first},
            {"content", message.second},
        });
    }
    nlohmann::ordered_json inputs = {
        {"messages", message_values},
        {"bos_token", engine->chat_template->bos_token},
        {"eos_token", engine->chat_template->eos_token},
        {"enable_thinking", false},
    };
    if (add_assistant) inputs["add_generation_prompt"] = true;

    try {
        jinja::context context(engine->chat_template->source);
        jinja::global_from_json(context, inputs, false);
        jinja::runtime runtime(context);
        const jinja::value result = runtime.execute(engine->chat_template->program);
        const auto parts = jinja::runtime::gather_string_parts(result);
        return { parts->as_string().str(), true };
    } catch (const std::exception & exception) {
        log_error(std::string("Chat template rendering failed: ") + exception.what());
        return {};
    }
}

std::string unmark_free_prompt(const std::string & prompt) {
    return prompt.rfind(FREE_TEST_MARKER, 0) == 0
        ? prompt.substr(std::string(FREE_TEST_MARKER).size())
        : prompt;
}

std::string format_correction_base(LocalEngine * engine, const std::string & common_prompt) {
    const std::string acknowledgement = "Gesprächskontext gelesen.";
    const std::vector<std::pair<std::string, std::string>> messages = {
        {"system", CORRECTION_SYSTEM_PROMPT},
        {"user", common_prompt},
        {"assistant", acknowledgement},
    };
    return render_chat(engine, messages, false).prompt;
}

std::string format_correction_with_target(
    LocalEngine * engine,
    const std::string & common_prompt,
    const std::string & target_prompt) {
    const std::string acknowledgement = "Gesprächskontext gelesen.";
    const std::vector<std::pair<std::string, std::string>> messages = {
        {"system", CORRECTION_SYSTEM_PROMPT},
        {"user", common_prompt},
        {"assistant", acknowledgement},
        {"user", target_prompt},
    };
    return render_chat(engine, messages, true).prompt;
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

bool has_complete_json_object(const std::string & value) {
    bool in_string = false;
    bool escaped = false;
    bool started = false;
    int depth = 0;
    for (char character : value) {
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                in_string = false;
            }
            continue;
        }
        if (character == '"') {
            in_string = true;
        } else if (character == '{') {
            started = true;
            ++depth;
        } else if (character == '}' && started) {
            --depth;
            if (depth == 0) return true;
        }
    }
    return false;
}

SampleResult sample_response(
    llama_context * context,
    const llama_vocab * vocab,
    int output_limit,
    const char * grammar,
    SteadyClock::time_point request_started) {
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

    SampleResult result;
    result.text.reserve(static_cast<size_t>(output_limit) * 4U);
    SteadyClock::time_point first_token_at;
    for (int generated = 0; generated < output_limit; ++generated) {
        const llama_token token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.finish_reason = "eog";
            break;
        }
        const SteadyClock::time_point token_at = SteadyClock::now();
        if (result.generated_tokens == 0) {
            first_token_at = token_at;
            result.time_to_first_token_ms = elapsed_ms(request_started, token_at);
        }
        result.text += token_piece(vocab, token);
        ++result.generated_tokens;
        llama_token mutable_token = token;
        llama_batch batch = llama_batch_get_one(&mutable_token, 1);
        if (llama_decode(context, batch) != 0) {
            result.text.clear();
            result.finish_reason = "decode_error";
            return result;
        }
        if (grammar && has_complete_json_object(result.text)) {
            result.finish_reason = "structured_result";
            break;
        }
    }
    const SteadyClock::time_point finished_at = SteadyClock::now();
    if (result.generated_tokens > 0) {
        result.answer_generation_ms = elapsed_ms(first_token_at, finished_at);
    }
    return result;
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

void clear_free_session(LocalEngine * engine) {
    if (engine->free_context) {
        llama_free(engine->free_context);
        engine->free_context = nullptr;
    }
    engine->free_messages.clear();
    engine->free_rendered_history.clear();
}

jobjectArray fail_free_generation(
    JNIEnv * env,
    LocalEngine * engine,
    const std::string & message) {
    (void) env;
    log_error(message);
    engine->last_error = message;
    clear_free_session(engine);
    return nullptr;
}

jobjectArray reject_free_prompt(
    LocalEngine * engine,
    bool conversation_continued,
    const std::string & message) {
    log_error(message);
    engine->last_error = message;
    if (conversation_continued && !engine->free_messages.empty()) {
        engine->free_messages.pop_back();
    } else {
        clear_free_session(engine);
    }
    return nullptr;
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
    try {
        const char * source = llama_model_chat_template(model, nullptr);
        if (!source || source[0] == '\0') {
            throw std::runtime_error("Das GGUF-Modell enthält keine Chatvorlage.");
        }
        const llama_vocab * vocab = llama_model_get_vocab(model);
        const llama_token bos = llama_vocab_bos(vocab);
        const llama_token eos = llama_vocab_eos(vocab);
        engine->chat_template = std::make_unique<LocalChatTemplate>(
            source,
            bos == LLAMA_TOKEN_NULL ? std::string() : token_piece(vocab, bos),
            eos == LLAMA_TOKEN_NULL ? std::string() : token_piece(vocab, eos));
    } catch (const std::exception & exception) {
        log_error(std::string("Chat template initialization failed: ") + exception.what());
        llama_model_free(model);
        delete engine;
        llama_backend_free();
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_generate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring prompt_value,
    jint maximum_output_tokens) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return nullptr;

    engine->last_error.clear();
    const SteadyClock::time_point request_started = SteadyClock::now();
    const std::string user_prompt = unmark_free_prompt(from_java(env, prompt_value));
    if (user_prompt.empty()) {
        return fail_free_generation(env, engine, "Der KI-Auftrag darf nicht leer sein.");
    }

    const bool conversation_continued = engine->free_context != nullptr;
    if (!conversation_continued) {
        clear_free_session(engine);
        engine->free_messages = {
            {"system", FREE_SYSTEM_PROMPT},
            {"user", user_prompt},
        };
    } else {
        engine->free_messages.push_back({"user", user_prompt});
    }

    const RenderedChat rendered = render_chat(engine, engine->free_messages, true);
    if (rendered.prompt.empty()) {
        return fail_free_generation(
            env,
            engine,
            "Die Chatvorlage konnte die KI-Unterhaltung nicht fortsetzen.");
    }
    if (conversation_continued &&
        rendered.prompt.rfind(engine->free_rendered_history, 0) != 0) {
        return fail_free_generation(
            env,
            engine,
            "Die Chatvorlage hat keinen stabilen Gesprächspräfix erzeugt.");
    }

    const std::string prompt_delta = conversation_continued
        ? rendered.prompt.substr(engine->free_rendered_history.size())
        : rendered.prompt;
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> prompt_tokens = tokenize(
        vocab,
        prompt_delta,
        !conversation_continued);
    if (prompt_tokens.empty()) {
        return fail_free_generation(
            env,
            engine,
            "Die neue Anfrage konnte nicht in KI-Tokens umgewandelt werden.");
    }

    if (!conversation_continued) {
        engine->free_context = create_context(engine);
        if (!engine->free_context) {
            return fail_free_generation(
                env,
                engine,
                "Der Gesprächskontext konnte nicht angelegt werden.");
        }
    }

    const int existing_tokens = conversation_continued
        ? llama_memory_seq_pos_max(llama_get_memory(engine->free_context), 0) + 1
        : 0;
    const int remaining_context = engine->context_size - existing_tokens -
        static_cast<int>(prompt_tokens.size()) - CHAT_SUFFIX_TOKEN_RESERVE;
    if (remaining_context < 64) {
        return reject_free_prompt(
            engine,
            conversation_continued,
            "Das Kontextfenster der Unterhaltung ist voll. Bitte die Unterhaltung zurücksetzen.");
    }
    const int output_limit = std::min(
        std::clamp(static_cast<int>(maximum_output_tokens), 64, 4096),
        remaining_context);

    if (!decode_tokens(engine->free_context, prompt_tokens)) {
        return fail_free_generation(
            env,
            engine,
            "Die neue Anfrage konnte nicht an die KI-Unterhaltung angehängt werden.");
    }

    const SteadyClock::time_point prompt_processed_at = SteadyClock::now();
    const SampleResult output = sample_response(
        engine->free_context,
        vocab,
        output_limit,
        nullptr,
        request_started);
    const SteadyClock::time_point finished_at = SteadyClock::now();
    if (output.text.empty()) {
        return fail_free_generation(
            env,
            engine,
            "Das lokale KI-Modell hat keinen verwertbaren Text erzeugt.");
    }

    engine->free_messages.push_back({"assistant", output.text});
    const RenderedChat completed = render_chat(engine, engine->free_messages, false);
    const std::string rendered_response = rendered.prompt + output.text;
    if (completed.prompt.rfind(rendered_response, 0) != 0) {
        return fail_free_generation(
            env,
            engine,
            "Die KI-Antwort konnte nicht sicher im Gesprächskontext abgeschlossen werden.");
    }
    const std::string completion_suffix = completed.prompt.substr(rendered_response.size());
    if (!completion_suffix.empty()) {
        std::vector<llama_token> suffix_tokens = tokenize(vocab, completion_suffix, false);
        if (suffix_tokens.empty() ||
            !decode_tokens(engine->free_context, suffix_tokens)) {
            return fail_free_generation(
                env,
                engine,
                "Das Ende der KI-Antwort konnte nicht im Gesprächskontext gespeichert werden.");
        }
    }
    engine->free_rendered_history = completed.prompt;

    return to_java_generation_result(env, {
        output.text,
        std::to_string(prompt_tokens.size()),
        std::to_string(output.generated_tokens),
        std::to_string(elapsed_ms(request_started, prompt_processed_at)),
        std::to_string(output.time_to_first_token_ms),
        std::to_string(output.answer_generation_ms),
        std::to_string(elapsed_ms(request_started, finished_at)),
        output.finish_reason,
        rendered.thinking_disabled ? "true" : "false",
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_lastError(
    JNIEnv * env,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || engine->last_error.empty()) return nullptr;
    return to_java(env, engine->last_error);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_hasTestConversation(
    JNIEnv *,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    return engine && engine->free_context && !engine->free_rendered_history.empty()
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_resetTestConversation(
    JNIEnv *,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine) return;
    clear_free_session(engine);
    engine->last_error.clear();
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
        engine,
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
        engine,
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

    const SampleResult output = sample_response(
        engine->correction_context,
        vocab,
        output_limit,
        CORRECTION_GRAMMAR,
        SteadyClock::now());
    return output.text.empty() ? nullptr : to_java(env, output.text);
}

extern "C" JNIEXPORT void JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_release(
    JNIEnv *,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine) return;
    clear_free_session(engine);
    clear_correction_session(engine);
    engine->chat_template.reset();
    if (engine->model) llama_model_free(engine->model);
    delete engine;
    llama_backend_free();
}
