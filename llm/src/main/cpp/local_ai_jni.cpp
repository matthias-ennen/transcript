#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstring>
#include <cstdlib>
#include <exception>
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
#include "utf8_to_utf16.h"

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
    bool offload_kqv = false;
    bool offload_operations = false;
    bool cpu_fallback_used = false;
    bool gpu_fallback_used = false;
    bool kleidi_enabled = false;
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

using ThreadpoolNewFn = ggml_threadpool * (*)(ggml_threadpool_params *);
using ThreadpoolFreeFn = void (*)(ggml_threadpool *);

struct CpuRuntimeInfo {
    ggml_backend_dev_t device = nullptr;
    ggml_backend_reg_t registry = nullptr;
    std::string variant;
    bool neon = false;
    bool fp16_vector = false;
    bool dot_product = false;
    bool int8_matrix = false;
    bool sve = false;
    int sve_bytes = 0;
    bool sme = false;
    bool sme2 = false;
    bool kleidi_compiled = false;
    bool kleidi_buffer = false;
    bool kleidi_usable = false;
    ThreadpoolNewFn threadpool_new = nullptr;
    ThreadpoolFreeFn threadpool_free = nullptr;
};

std::mutex backend_init_mutex;
bool backend_core_initialized = false;
std::vector<std::string> loaded_backend_paths;

std::vector<std::string> split_search_paths(const std::string & value) {
    std::vector<std::string> paths;
    std::stringstream stream(value);
    std::string path;
    while (std::getline(stream, path, ':')) {
        if (!path.empty() &&
            std::find(paths.begin(), paths.end(), path) == paths.end()) {
            paths.push_back(path);
        }
    }
    return paths;
}

void ensure_backend_initialized(const std::string & search_paths = {}) {
    std::lock_guard<std::mutex> lock(backend_init_mutex);
    if (!backend_core_initialized) {
        llama_backend_init();
        backend_core_initialized = true;
    }
#ifdef TRANSCRIPT_DYNAMIC_BACKENDS
    for (const std::string & path : split_search_paths(search_paths)) {
        if (std::find(loaded_backend_paths.begin(), loaded_backend_paths.end(), path) !=
            loaded_backend_paths.end()) {
            continue;
        }
        ggml_backend_load_all_from_path(path.c_str());
        loaded_backend_paths.push_back(path);
    }
#else
    (void) search_paths;
#endif
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

bool feature_enabled(
    const ggml_backend_feature * features,
    const char * name) {
    if (!features) return false;
    for (const ggml_backend_feature * feature = features; feature->name; ++feature) {
        if (std::strcmp(feature->name, name) == 0) {
            return !feature->value || std::strcmp(feature->value, "0") != 0;
        }
    }
    return false;
}

int feature_integer(
    const ggml_backend_feature * features,
    const char * name) {
    if (!features) return 0;
    for (const ggml_backend_feature * feature = features; feature->name; ++feature) {
        if (std::strcmp(feature->name, name) == 0 && feature->value) {
            try {
                return std::stoi(feature->value);
            } catch (...) {
                return 0;
            }
        }
    }
    return 0;
}

CpuRuntimeInfo cpu_runtime_info() {
    CpuRuntimeInfo result;
    result.device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (!result.device) return result;
    result.registry = ggml_backend_dev_backend_reg(result.device);
    const char * registry_name = result.registry ? ggml_backend_reg_name(result.registry) : nullptr;
    result.variant = registry_name ? registry_name : "CPU";
    if (!result.registry) return result;

    const auto get_features = reinterpret_cast<ggml_backend_get_features_t>(
        ggml_backend_reg_get_proc_address(result.registry, "ggml_backend_get_features"));
    ggml_backend_feature * features = get_features ? get_features(result.registry) : nullptr;
    result.neon = feature_enabled(features, "NEON");
    result.fp16_vector = feature_enabled(features, "FP16_VA");
    result.dot_product = feature_enabled(features, "DOTPROD");
    result.int8_matrix = feature_enabled(features, "MATMUL_INT8");
    result.sve = feature_enabled(features, "SVE");
    result.sve_bytes = feature_integer(features, "SVE_CNT");
    result.sme = feature_enabled(features, "SME");
    result.sme2 = feature_enabled(features, "SME2");
    result.kleidi_compiled = feature_enabled(features, "KLEIDIAI");

    const auto get_extra_bufts = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(
        ggml_backend_reg_get_proc_address(result.registry, "ggml_backend_dev_get_extra_bufts"));
    // Do not call the provider here: constructing the KleidiAI buffer freezes
    // its environment-controlled SME/chunk settings. Model loading calls it
    // only after the selected profile has configured those values.
    result.kleidi_buffer = result.kleidi_compiled && get_extra_bufts != nullptr;
    result.kleidi_usable = result.kleidi_compiled && result.kleidi_buffer &&
        (result.dot_product || result.int8_matrix || result.sve || result.sme);
    result.threadpool_new = reinterpret_cast<ThreadpoolNewFn>(
        ggml_backend_reg_get_proc_address(result.registry, "ggml_threadpool_new"));
    result.threadpool_free = reinterpret_cast<ThreadpoolFreeFn>(
        ggml_backend_reg_get_proc_address(result.registry, "ggml_threadpool_free"));
    return result;
}

bool vulkan_packaged() {
#ifdef TRANSCRIPT_VULKAN_PACKAGED
    return true;
#elif defined(GGML_USE_VULKAN)
    return true;
#else
    return false;
#endif
}

bool vulkan_available() {
    return ggml_backend_reg_by_name("Vulkan") != nullptr;
}

bool model_supports_kleidiai(const std::string & model_path) {
    std::string upper = model_path;
    std::transform(upper.begin(), upper.end(), upper.begin(), [](unsigned char value) {
        return static_cast<char>(std::toupper(value));
    });
    return upper.find("Q4_0") != std::string::npos ||
        upper.find("Q8_0") != std::string::npos;
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
    int polling,
    const CpuRuntimeInfo & cpu) {
    if (!cpu.threadpool_new) {
        log_error("Selected CPU backend does not expose threadpool creation.");
        return;
    }
    ggml_threadpool_params generation =
        ggml_threadpool_params_default(engine->generation_threads);
    generation.prio = to_thread_priority(priority);
    generation.poll = static_cast<uint32_t>(std::clamp(polling, 0, 100));
    generation.strict_cpu = strict_cpu;
    apply_cpu_mask(generation, cpu_mask);
    engine->generation_threadpool = cpu.threadpool_new(&generation);

    ggml_threadpool_params prompt = ggml_threadpool_params_default(engine->prompt_threads);
    prompt.prio = to_thread_priority(priority);
    prompt.poll = static_cast<uint32_t>(std::clamp(polling, 0, 100));
    prompt.strict_cpu = strict_cpu;
    apply_cpu_mask(prompt, cpu_mask);
    engine->prompt_threadpool = cpu.threadpool_new(&prompt);
}

void free_threadpools(LocalEngine * engine) {
    if (!engine) return;
    const CpuRuntimeInfo cpu = cpu_runtime_info();
    if (!cpu.threadpool_free) return;
    if (engine->generation_threadpool) {
        cpu.threadpool_free(engine->generation_threadpool);
        engine->generation_threadpool = nullptr;
    }
    if (engine->prompt_threadpool) {
        cpu.threadpool_free(engine->prompt_threadpool);
        engine->prompt_threadpool = nullptr;
    }
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
    const std::u16string utf16 = transcript::utf8_to_utf16_replacing_invalid(value);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size()));
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
    jint kleidi_chunk_multiplier,
    jstring native_library_search_path_value) {
    const std::string model_path = from_java(env, model_path_value);
    if (model_path.empty()) return 0;

    ensure_backend_initialized(from_java(env, native_library_search_path_value));
    const CpuRuntimeInfo cpu = cpu_runtime_info();
    if (!cpu.device) {
        log_error("No compatible CPU backend could be loaded from the APK.");
        return 0;
    }
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
    const bool gpu_backend_requested = engine->requested_backend == 2 ||
        engine->requested_backend == 3;
    engine->requested_gpu_layers = gpu_backend_requested
        ? static_cast<int>(gpu_layers)
        : 0;
    engine->offload_kqv = gpu_backend_requested && offload_kqv == JNI_TRUE;
    engine->offload_operations = gpu_backend_requested && offload_operations == JNI_TRUE;
    const bool model_kleidi_compatible = model_supports_kleidiai(model_path);
    engine->kleidi_enabled = engine->requested_cpu_backend != 1 &&
        cpu.kleidi_usable && model_kleidi_compatible;
    if (engine->requested_cpu_backend == 2 && !engine->kleidi_enabled) {
        if (automatic_cpu_fallback != JNI_TRUE) {
            log_error(model_kleidi_compatible
                ? "KleidiAI was requested, but the selected CPU backend has no compatible KleidiAI kernels."
                : "KleidiAI supports Q4_0/Q8_0 models; the selected model uses another quantization.");
            delete engine;
            return 0;
        }
        engine->cpu_fallback_used = true;
    }

    configure_kleidi_environment(
        static_cast<int>(kleidi_sme_units),
        static_cast<int>(kleidi_chunk_multiplier),
        std::max(engine->generation_threads, engine->prompt_threads));

    const bool wants_gpu = gpu_backend_requested;
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
        params.use_extra_bufts = engine->kleidi_enabled;
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
        engine->gpu_fallback_used = true;
    }
    engine->model = load_model(wants_gpu && !selected_devices.empty());
    if (!engine->model && wants_gpu && automatic_cpu_fallback == JNI_TRUE) {
        log_error("Vulkan model load failed; retrying with CPU.");
        engine->gpu_fallback_used = true;
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
        static_cast<int>(thread_polling_percent),
        cpu);
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
        free_threadpools(engine);
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

    try {

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
    } catch (const std::exception & exception) {
        const std::string detail = exception.what() ? exception.what() : "Unbekannter nativer Fehler";
        const bool device_lost = detail.find("DeviceLost") != std::string::npos ||
            detail.find("DEVICE_LOST") != std::string::npos ||
            detail.find("device lost") != std::string::npos;
        engine->gpu_fallback_used = engine->gpu_fallback_used || device_lost;
        return fail_free_generation(
            env,
            engine,
            (device_lost ? "VULKAN_DEVICE_LOST: " : "NATIVE_INFERENCE_ERROR: ") + detail);
    } catch (...) {
        return fail_free_generation(
            env,
            engine,
            "NATIVE_INFERENCE_ERROR: Unbekannte native C++-Exception.");
    }
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

    try {

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
    } catch (const std::exception & exception) {
        const std::string detail = exception.what() ? exception.what() : "Unbekannter nativer Fehler";
        const bool device_lost = detail.find("DeviceLost") != std::string::npos ||
            detail.find("DEVICE_LOST") != std::string::npos ||
            detail.find("device lost") != std::string::npos;
        engine->last_error = (device_lost ? "VULKAN_DEVICE_LOST: " :
            "NATIVE_INFERENCE_ERROR: ") + detail;
        engine->gpu_fallback_used = engine->gpu_fallback_used || device_lost;
        log_error(engine->last_error);
        clear_correction_session(engine);
        return JNI_FALSE;
    } catch (...) {
        engine->last_error = "NATIVE_INFERENCE_ERROR: Unbekannte native C++-Exception.";
        log_error(engine->last_error);
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

    try {

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
    } catch (const std::exception & exception) {
        const std::string detail = exception.what() ? exception.what() : "Unbekannter nativer Fehler";
        const bool device_lost = detail.find("DeviceLost") != std::string::npos ||
            detail.find("DEVICE_LOST") != std::string::npos ||
            detail.find("device lost") != std::string::npos;
        engine->last_error = (device_lost ? "VULKAN_DEVICE_LOST: " :
            "NATIVE_INFERENCE_ERROR: ") + detail;
        engine->gpu_fallback_used = engine->gpu_fallback_used || device_lost;
        log_error(engine->last_error);
        return nullptr;
    } catch (...) {
        engine->last_error = "NATIVE_INFERENCE_ERROR: Unbekannte native C++-Exception.";
        log_error(engine->last_error);
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_runtimeReport(
    JNIEnv * env,
    jobject,
    jlong handle) {
    auto * engine = reinterpret_cast<LocalEngine *>(handle);
    if (!engine || !engine->model) return nullptr;
    const bool gpu_active = !engine->gpu_fallback_used && !engine->gpu_device.empty() &&
        (engine->requested_backend == 2 || engine->requested_backend == 3);
    const std::string active_backend = gpu_active
        ? (engine->requested_backend == 2 ? "Vulkan" : "CPU/Vulkan")
        : "CPU";
    const CpuRuntimeInfo cpu = cpu_runtime_info();
    const std::string active_cpu = engine->kleidi_enabled
        ? "KleidiAI aktiviert (" + cpu.variant + ")"
        : "Standard-CPU (" + cpu.variant + ")";
    return to_java_generation_result(env, {
        backend_name(engine->requested_backend),
        active_backend,
        active_cpu,
        engine->gpu_device.empty() ? "Kein Vulkan-Gerät aktiv" : engine->gpu_device,
        std::to_string(llama_model_n_layer(engine->model)),
        std::to_string(engine->requested_gpu_layers),
        (engine->cpu_fallback_used || engine->gpu_fallback_used) ? "true" : "false",
        load_mode_name(engine->load_mode),
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_matthiasennen_transcript_ai_LocalAiNative_runtimeCapabilities(
    JNIEnv * env,
    jobject,
    jstring native_library_search_path_value) {
    ensure_backend_initialized(from_java(env, native_library_search_path_value));
    const CpuRuntimeInfo cpu = cpu_runtime_info();
    const bool cpu_loaded = cpu.device != nullptr;
    nlohmann::ordered_json result = {
        {"nativeRuntimeLoaded", cpu_loaded},
        {"nativeRuntimeError", cpu_loaded ? "" : "Kein kompatibles CPU-Backend aus der APK geladen."},
        {"cpuVariant", cpu.variant},
        {"kleidiAiCompiled", cpu.kleidi_compiled},
        {"kleidiAiBufferAvailable", cpu.kleidi_buffer},
        {"kleidiAiUsable", cpu.kleidi_usable},
        {"vulkanCompiled", vulkan_packaged()},
        {"vulkanAvailable", vulkan_available()},
        {"cpu", {
            {"neon", cpu.neon},
            {"fp16Vector", cpu.fp16_vector},
            {"dotProduct", cpu.dot_product},
            {"int8Matrix", cpu.int8_matrix},
            {"sve", cpu.sve},
            {"sveBytes", cpu.sve_bytes},
            {"sme", cpu.sme},
            {"sme2", cpu.sme2},
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
    jstring model_path_value,
    jstring native_library_search_path_value) {
    const std::string model_path = from_java(env, model_path_value);
    if (model_path.empty()) return 0;
    ensure_backend_initialized(from_java(env, native_library_search_path_value));
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
    free_threadpools(engine);
    delete engine;
}
