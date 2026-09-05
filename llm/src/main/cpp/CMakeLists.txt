cmake_minimum_required(VERSION 3.22.1)

project(transcript_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

# Android's cross-compilation search root hides the host-side SPIR-V package
# metadata even though glslc and the headers are installed on the CI host.
# llama.cpp only uses find_package here as an availability check, so expose a
# tiny config package whose include target points at the host headers used by
# its host shader generator.
list(PREPEND CMAKE_PREFIX_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
set(SPIRV-Headers_DIR
    "${CMAKE_CURRENT_LIST_DIR}/cmake/SPIRV-Headers"
    CACHE PATH "Host SPIR-V header package for Vulkan shader generation" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Build the Android CPU variants as loadable backends. llama.cpp scores the
    # variants against HWCAP/HWCAP2 at runtime, so an older ARM64 device keeps
    # the baseline path while newer devices receive real DotProd/I8MM/SVE
    # KleidiAI kernels without making the whole JNI library require them.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # whisper.cpp ships its own ggml shared objects in the app module. Keep the
    # independently pinned llama.cpp core under unique SONAMEs; the loadable
    # CPU variant names remain upstream-compatible so ggml can discover them.
    set_target_properties(ggml-base PROPERTIES OUTPUT_NAME transcript-llm-ggml-base)
    set_target_properties(ggml PROPERTIES OUTPUT_NAME transcript-llm-ggml)
    set_target_properties(llama PROPERTIES OUTPUT_NAME transcript-llm-llama)
endif()

# The Android NDK exposes Vulkan's C API and loader but omits Khronos'
# header-only C++ bindings required by ggml-vulkan. CI stages the distro's
# matching Vulkan headers here before Gradle configures this target. The CI
# step also routes Vulkan 1.1 feature queries through Vulkan-Hpp's dynamic
# dispatcher because Android API 26 does not link that symbol directly.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND TARGET ggml-vulkan)
    # Keep llama.cpp's loadable Vulkan backend discoverable by ggml's
    # libggml-vulkan-*.so variant scan while avoiding the generic
    # libggml-vulkan.so SONAME used by the independently pinned CrispASR runtime.
    set_target_properties(ggml-vulkan PROPERTIES OUTPUT_NAME ggml-vulkan-transcript-llm)
    target_include_directories(ggml-vulkan PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/host-vulkan-headers")
endif()

# The diagnostics translation unit includes the established JNI implementation
# and replaces only the transcript-correction entry points with instrumented
# variants. Free chat/model loading therefore keep the exact existing runtime.
add_library(transcript_llm SHARED local_ai_diagnostics.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(transcript_llm PRIVATE
        TRANSCRIPT_DYNAMIC_BACKENDS=1
        TRANSCRIPT_VULKAN_PACKAGED=1)

    # Keep the APK bounded while covering the relevant Android generations:
    # portable ARMv8, DotProd, I8MM and SVE2. The runtime loader selects only a
    # variant whose feature score is valid for the current processor.
    add_dependencies(transcript_llm
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-vulkan)
endif()

target_sources(transcript_llm PRIVATE
    ${LLAMA_SRC}/common/jinja/lexer.cpp
    ${LLAMA_SRC}/common/jinja/parser.cpp
    ${LLAMA_SRC}/common/jinja/runtime.cpp
    ${LLAMA_SRC}/common/jinja/string.cpp
    ${LLAMA_SRC}/common/jinja/value.cpp
    ${LLAMA_SRC}/common/unicode.cpp
)

target_include_directories(transcript_llm PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/vendor
)

target_compile_options(transcript_llm PRIVATE
    -O3
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(transcript_llm PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

find_library(LOG_LIB log)
target_link_libraries(transcript_llm PRIVATE llama ${LOG_LIB} android)
