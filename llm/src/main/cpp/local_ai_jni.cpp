#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <codecvt>
#include <cstdlib>
#include <exception>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "jinja/value.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"

namespace {

constexpr const char * TAG = "TranscriptLocalAI";
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
void log_error(const std::string & message);

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
    int generation_threads = 4;
    int prompt_threads = 4;
    int batch_size = 1024;
    int micro_batch_size = 512;
    int flash_attention = 0;
    int requested_backend = 0;
    int requested_cpu_backend = 0;
    int requested_gpu_layers = 0;
    int load_mode = 0;
    bool offload_kqv = true;
    bool offload_operations = true;
    bool fallback_used = false;
    std::string gpu_device;
    ggml_threadpool * generation_threadpool = nullptr;
    ggml_threadpool * prompt_threadpool = nullptr;
    std::vector<std::pair<std::string, std::string>> free_messages;
    std::string last_error;
    llama_context * correction_context = nullptr;
    int correction_base_tokens = 0;
    std::string correction_common_prompt;
    std::string correction_base_rendered;
};

std::once_flag backend_init_once;

void ensure_backend_initialized() {
    std::call_once(backend_init_once, []() { llama_backend_init(); });
}

const char * backend_name(int backend) {
    switch (backend) {
        case 1: return "CPU";
        case 2: return "Vulkan";
        case 3: return "CPU/Vulkan";
        default: return "Automatisch";
    }
}

const char * load_mode_name(int mode) {
    switch (mode) {
        case 2: return "RAM/Lesen";
        case 3: return "MLock";
        case 4: return "Memory Mapping + MLock";
        case 1: return "Memory Mapping";
        default: return "Automatisch";
    }
}

bool kleidi_compiled() {
#ifdef GGML_USE_CPU_KLEIDIAI
    return true;
#else
    return false;
#endif
}

bool vulkan_compiled() {
#ifdef GGML_USE_VULKAN
    return true;
#else
    return false;
#endif
}

std::vector<ggml_backend_dev_t> gpu_devices() {
    std::vector<ggml_backend_dev_t> result;
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(device);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU ||
            type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            result.push_back(device);
        }
    }
    return result;
}

llama_load_mode to_load_mode(int mode) {
    switch (mode) {
        case 2: return LLAMA_LOAD_MODE_NONE;
        case 3: return LLAMA_LOAD_MODE_MLOCK;
        case 4: return LLAMA_LOAD_MODE_MMAP_MLOCK;
        case 1: return LLAMA_LOAD_MODE_MMAP;
        default: return LLAMA_LOAD_MODE_MMAP;
    }
}

ggml_sched_priority to_thread_priority(int priority) {
    switch (priority) {
        case 0: return GGML_SCHED_PRIO_LOW;
        case 2: return GGML_SCHED_PRIO_MEDIUM;
        case 3: return GGML_SCHED_PRIO_HIGH;
        default: return GGML_SCHED_PRIO_NORMAL;
    }
}

void apply_cpu_mask(ggml_threadpool_params & params, const std::string & value) {
    if (value.empty()) return;
    std::stringstream stream(value);
    std::string item;
    while (std::getline(stream, item, ',')) {
        try {
            const int core = std::stoi(item);
            if (core >= 0 && core < GGML_MAX_N_THREADS) params.cpumask[core] = true;
        } catch (...) {
            log_error("Ignoring invalid CPU core entry: " + item);
        }
    }
}

void configure_threadpools(
    LocalEngine * engine,
    const std::string & cpu_mask,
    bool strict_cpu,
    int priority,
    int polling) {
    ggml_threadpool_params generation =
        ggml_threadpool_params_default(engine->generation_threads);
    generation.prio = to_thread_priority(priority);
    generation.poll = static_cast<uint32_t>(std::clamp(polling, 0, 100));
    generation.strict_cpu = strict_cpu;
    apply_cpu_mask(generation, cpu_mask);
    engine->generation_threadpool = ggml_threadpool_new(&generation);

    ggml_threadpool_params prompt = ggml_threadpool_params_default(engine->prompt_threads);
    prompt.prio = to_thread_priority(priority);
    prompt.poll = static_cast<uint32_t>(std::clamp(polling, 0, 100));
    prompt.strict_cpu = strict_cpu;
    apply_cpu_mask(prompt, cpu_mask);
    engine->prompt_threadpool = ggml_threadpool_new(&prompt);
}

void configure_kleidi_environment(int sme_units, int chunk_multiplier, int threads) {
    if (sme_units >= 0) {
        setenv("GGML_KLEIDIAI_SME", std::to_string(sme_units).c_str(), 1);
    } else {
        unsetenv("GGML_KLEIDIAI_SME");
    }
    if (chunk_multiplier > 0) {
        setenv("GGML_KLEIDIAI_CHUNK_MULTIPLIER", std::to_string(chunk_multiplier).c_str(), 1);
    } else {
        unsetenv("GGML_KLEIDIAI_CHUNK_MULTIPLIER");
    }
    setenv("GGML_TOTAL_THREADS", std::to_string(threads).c_str(), 1);
}

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

llama_context * create_context(LocalEngine * engine) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = engine->context_size;
    params.n_batch = std::min(engine->context_size, engine->batch_size);
    params.n_ubatch = std::min(params.n_batch, static_cast<uint32_t>(engine->micro_batch_size));
    params.n_threads = engine->generation_threads;
    params.n_threads_batch = engine->prompt_threads;
    params.flash_attn_type = engine->flash_attention == 1
        ? LLAMA_FLASH_ATTN_TYPE_ENABLED
        : engine->flash_attention == 2
            ? LLAMA_FLASH_ATTN_TYPE_DISABLED
            : LLAMA_FLASH_ATTN_TYPE_AUTO;
    params.offload_kqv = engine->offload_kqv;
    params.op_offload = engine->offload_operations;
    params.no_perf = true;
    llama_context * context = llama_init_from_model(engine->model, params);
    if (context && engine->generation_threadpool && engine->prompt_threadpool) {
        llama_attach_threadpool(
            context,
            engine->generation_threadpool,
            engine->prompt_threadpool);
    }
    return context;
}

bool decode_tokens(llama_context * context, std::vector<llama_token> & tokens) {
    size_t offset = 0;
    while (offset < tokens.size()) {
        const size_t tokens_left = tokens.size() - offset;
        const int32_t current_batch_size = static_cast<int32_t>(
            std::min(tokens_left, static_cast<size_t>(llama_n_batch(context))));
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
    engine->free_messages.clear();
}

jobjectArray fail_free_generation(
    JNIEnv * env,
    LocalEngine * engine,
    const std::string & message) {
    (void) env;
    log_error(message);
    engine->last_error = message;
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_create(
    JNIEnv * env,
    jobject,
    jstring model_path_value,
    jint context_size,
    jint generation_threads,
    jint prompt_threads,
    jint batch_size,
    jint micro_batch_size,
    jint flash_attention,
    jint load_mode,
    jint backend,
    jint cpu_backend,
    jint gpu_device_index,
    jint gpu_layers,
    jboolean offload_kqv,
    jboolean offload_operations,
    jboolean automatic_cpu_fallback,
    jstring cpu_core_mask_value,
    jboolean strict_cpu_placement,
    jint thread_priority,
    jint thread_polling_percent,
    jint kleidi_sme_units,
    jint kleidi_chunk_multiplier) {
    const std::string model_path = from_java(env, model_path_value);
    if (model_path.empty()) return 0;

    ensure_backend_initialized();
    auto * engine = new LocalEngine();
    engine->context_size = std::clamp(static_cast<int>(context_size), 1024, 32768);
    engine->generation_threads = std::clamp(static_cast<int>(generation_threads), 1, 64);
    engine->prompt_threads = std::clamp(static_cast<int>(prompt_threads), 1, 64);
    engine->batch_size = std::clamp(
        static_cast<int>(batch_size),
        32,
        engine->context_size);
    engine->micro_batch_size = std::clamp(
        static_cast<int>(micro_batch_size),
        16,
        engine->batch_size);
    engine->flash_attention = std::clamp(static_cast<int>(flash_attention), 0, 2);
    engine->load_mode = std::clamp(static_cast<int>(load_mode), 0, 4);
    engine->requested_backend = std::clamp(static_cast<int>(backend), 0, 3);
    engine->requested_cpu_backend = std::clamp(static_cast<int>(cpu_backend), 0, 2);
    engine->requested_gpu_layers = static_cast<int>(gpu_layers);
    engine->offload_kqv = offload_kqv == JNI_TRUE;
    engine->offload_operations = offload_operations == JNI_TRUE;

    configure_kleidi_environment(
        static_cast<int>(kleidi_sme_units),
        static_cast<int>(kleidi_chunk_multiplier),
        std::max(engine->generation_threads, engine->prompt_threads));

    const bool wants_gpu = engine->requested_backend == 2 ||
        engine->requested_backend == 3;
    const std::vector<ggml_backend_dev_t> available_gpus = gpu_devices();
    std::vector<ggml_backend_dev_t> selected_devices;
    if (wants_gpu && !available_gpus.empty()) {
        const size_t selected_index = std::min(
            static_cast<size_t>(std::max(0, static_cast<int>(gpu_device_index))),
            available_gpus.size() - 1);
        selected_devices.push_back(available_gpus[selected_index]);
        selected_devices.push_back(nullptr);
        const char * description = ggml_backend_dev_description(available_gpus[selected_index]);
        engine->gpu_device = description ? description : ggml_backend_dev_name(available_gpus[selected_index]);
    }

    const auto load_model = [&](bool with_gpu) -> llama_model * {
        llama_model_params params = llama_model_default_params();
        params.load_mode = to_load_mode(engine->load_mode);
        params.use_extra_bufts = engine->requested_cpu_backend != 1;
        if (with_gpu && selected_devices.size() > 1) {
            params.devices = selected_devices.data();
            params.n_gpu_layers = engine->requested_backend == 2
                ? -1
                : std::max(1, engine->requested_gpu_layers);
        } else {
            params.n_gpu_layers = 0;
        }
        return llama_model_load_from_file(model_path.c_str(), params);
    };

    if (wants_gpu && selected_devices.empty()) {
        if (automatic_cpu_fallback != JNI_TRUE) {
            log_error("Vulkan was requested, but no Vulkan GPU device is available.");
            delete engine;
            return 0;
        }
        engine->fallback_used = true;
    }
    engine->model = load_model(wants_gpu && !selected_devices.empty());
    if (!engine->model && wants_gpu && automatic_cpu_fallback == JNI_TRUE) {
        log_error("Vulkan model load failed; retrying with CPU.");
        engine->fallback_used = true;
        engine->gpu_device.clear();
        engine->model = load_model(false);
    }
    if (!engine->model) {
        log_error("Model load failed: " + model_path);
        delete engine;
        return 0;
    }

    configure_threadpools(
        engine,
        from_java(env, cpu_core_mask_value),
        strict_cpu_placement == JNI_TRUE,
        static_cast<int>(thread_priority),
        static_cast<int>(thread_polling_percent));
    try {
        const char * source = llama_model_chat_template(engine->model, nullptr);
        if (!source || source[0] == '\0') {
            throw std::runtime_error("Das GGUF-Modell enthält keine Chatvorlage.");
        }
        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        const llama_token bos = llama_vocab_bos(vocab);
        const llama_token eos = llama_vocab_eos(vocab);
        engine->chat_template = std::make_unique<LocalChatTemplate>(
            source,
            bos == LLAMA_TOKEN_NULL ? std::string() : token_piece(vocab, bos),
            eos == LLAMA_TOKEN_NULL ? std::string() : token_piece(vocab, eos));
    } catch (const std::exception & exception) {
        log_error(std::string("Chat template initialization failed: ") + exception.what());
        if (engine->generation_threadpool) ggml_threadpool_free(engine->generation_threadpool);
        if (engine->prompt_threadpool) ggml_threadpool_free(engine->prompt_threadpool);
        llama_model_free(engine->model);
        delete engine;
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

    const bool conversation_continued = engine->free_messages.size() >= 3 &&
        engine->free_messages.back().first == "assistant";
    std::vector<std::pair<std::string, std::string>> request_messages;
    if (conversation_continued) {
        request_messages = engine->free_messages;
        request_messages.push_back({"user", user_prompt});
    } else {
        request_messages = {
            {"system", FREE_SYSTEM_PROMPT},
            {"user", user_prompt},
        };
    }

    const RenderedChat rendered = render_chat(engine, request_messages, true);
    if (rendered.prompt.empty()) {
        return fail_free_generation(
            env,
            engine,
            "Die Chatvorlage konnte die KI-Unterhaltung nicht fortsetzen.");
    }
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    const std::vector<llama_token> prompt_tokens = tokenize(
        vocab,
        rendered.prompt,
        true);
    if (prompt_tokens.empty()) {
        return fail_free_generation(
            env,
            engine,
            "Die neue Anfrage konnte nicht in KI-Tokens umgewandelt werden.");
    }

    const int remaining_context = engine->context_size -
        static_cast<int>(prompt_tokens.size()) - CHAT_SUFFIX_TOKEN_RESERVE;
    if (remaining_context < 64) {
        return fail_free_generation(
            env,
            engine,
            "Das Kontextfenster der Unterhaltung ist voll. Bitte die Unterhaltung zurücksetzen.");
    }
    const int output_limit = std::min(
        std::clamp(static_cast<int>(maximum_output_tokens), 64, 4096),
        remaining_context);

    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        create_context(engine), llama_free);
    if (!context) {
        return fail_free_generation(
            env,
            engine,
            "Der Gesprächskontext konnte nicht angelegt werden.");
    }
    std::vector<llama_token> mutable_prompt_tokens = prompt_tokens;
    if (!decode_tokens(context.get(), mutable_prompt_tokens)) {
        return fail_free_generation(
            env,
            engine,
            "Die KI-Unterhaltung konnte nicht in den Gesprächskontext eingelesen werden.");
    }

    const SteadyClock::time_point prompt_processed_at = SteadyClock::now();
    const SampleResult output = sample_response(
        context.get(),
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

    request_messages.push_back({"assistant", output.text});
    engine->free_messages = std::move(request_messages);

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
    return engine && engine->free_messages.size() >= 3 &&
        engine->free_messages.back().first == "assistant"
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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_runtimeReport(
    JNIEnv * env,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return nullptr;
    const bool gpu_active = !engine->fallback_used && !engine->gpu_device.empty() &&
        (engine->requested_backend == 2 || engine->requested_backend == 3);
    const std::string active_backend = gpu_active
        ? (engine->requested_backend == 2 ? "Vulkan" : "CPU/Vulkan")
        : "CPU";
    const std::string active_cpu = engine->requested_cpu_backend == 1 || !kleidi_compiled()
        ? "Standard-CPU"
        : "KleidiAI freigegeben";
    return to_java_generation_result(env, {
        backend_name(engine->requested_backend),
        active_backend,
        active_cpu,
        engine->gpu_device.empty() ? "Kein Vulkan-Gerät aktiv" : engine->gpu_device,
        std::to_string(llama_model_n_layer(engine->model)),
        std::to_string(engine->requested_gpu_layers),
        engine->fallback_used ? "true" : "false",
        load_mode_name(engine->load_mode),
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_runtimeCapabilities(
    JNIEnv * env,
    jobject) {
    ensure_backend_initialized();
    nlohmann::ordered_json result = {
        {"kleidiAiCompiled", kleidi_compiled()},
        {"vulkanCompiled", vulkan_compiled()},
        {"cpu", {
            {"neon", ggml_cpu_has_neon() != 0},
            {"fp16Vector", ggml_cpu_has_fp16_va() != 0},
            {"dotProduct", ggml_cpu_has_dotprod() != 0},
            {"int8Matrix", ggml_cpu_has_matmul_int8() != 0},
            {"sve", ggml_cpu_has_sve() != 0},
            {"sveBytes", ggml_cpu_get_sve_cnt()},
            {"sme", ggml_cpu_has_sme() != 0},
            {"sme2", ggml_cpu_has_sme2() != 0},
        }},
    };
    result["devices"] = nlohmann::ordered_json::array();
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        size_t free_bytes = 0;
        size_t total_bytes = 0;
        ggml_backend_dev_memory(device, &free_bytes, &total_bytes);
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(device);
        const char * type_name = type == GGML_BACKEND_DEVICE_TYPE_CPU ? "CPU" :
            type == GGML_BACKEND_DEVICE_TYPE_GPU ? "GPU" :
            type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "Integrierte GPU" :
            type == GGML_BACKEND_DEVICE_TYPE_ACCEL ? "Beschleuniger" : "Meta";
        const char * name = ggml_backend_dev_name(device);
        const char * description = ggml_backend_dev_description(device);
        result["devices"].push_back({
            {"index", index},
            {"name", name ? name : "Unbekannt"},
            {"description", description ? description : "Unbekannt"},
            {"type", type_name},
            {"freeBytes", free_bytes},
            {"totalBytes", total_bytes},
        });
    }
    return to_java(env, result.dump());
}

extern "C" JNIEXPORT jint JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_inspectModelLayerCount(
    JNIEnv * env,
    jobject,
    jstring model_path_value) {
    const std::string model_path = from_java(env, model_path_value);
    if (model_path.empty()) return 0;
    ensure_backend_initialized();
    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    params.n_gpu_layers = 0;
    params.use_extra_bufts = false;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
    if (!model) return 0;
    const int layers = llama_model_n_layer(model);
    llama_model_free(model);
    return layers;
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
    if (engine->generation_threadpool) ggml_threadpool_free(engine->generation_threadpool);
    if (engine->prompt_threadpool) ggml_threadpool_free(engine->prompt_threadpool);
    delete engine;
}
