cmake_minimum_required(VERSION 3.22.1)

project(transcript_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
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
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

add_library(transcript_llm SHARED local_ai_jni.cpp)

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
