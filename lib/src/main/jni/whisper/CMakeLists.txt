cmake_minimum_required(VERSION 3.22.1)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

# Path to external GGML, otherwise uses the copy in whisper.cpp.
option(GGML_HOME "whisper: Path to external GGML source" OFF)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
    )

find_library(LOG_LIB log)
find_library(DL_LIB dl)

# Native source-separation shim. It deliberately does not link CrispASR at build
# time: the pinned Android runtime is staged into jniLibs by CI and dlopen'd only
# when the Native/GGUF Kim variant is selected.
add_library(
    transcript_song_native
    SHARED
    ${CMAKE_SOURCE_DIR}/song_native_jni.c
)
target_compile_options(transcript_song_native PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(transcript_song_native PRIVATE -Wl,--gc-sections)
target_link_libraries(transcript_song_native ${LOG_LIB} ${DL_LIB})

include(FetchContent)

# Android's cross-compilation root hides the host SPIR-V package used only by
# ggml-vulkan's host shader generator. Reuse the checked-in package shim and
# staged Khronos headers already used by the local llama.cpp module.
list(PREPEND CMAKE_PREFIX_PATH
    "${CMAKE_SOURCE_DIR}/../../../../../llm/src/main/cpp/cmake")
set(SPIRV-Headers_DIR
    "${CMAKE_SOURCE_DIR}/../../../../../llm/src/main/cpp/cmake/SPIRV-Headers"
    CACHE PATH "Host SPIR-V header package for Vulkan shader generation" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Build the real Whisper Vulkan backend" FORCE)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS                      -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    if (GGML_HOME)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()
    FetchContent_MakeAvailable(ggml)
    if (TARGET ggml-vulkan)
        # llama.cpp packages its independently pinned Vulkan backend under the
        # upstream name. Give Whisper's copy a unique SONAME so both runtimes
        # can coexist in one APK without Android's native-library merge clash.
        set_target_properties(ggml-vulkan PROPERTIES OUTPUT_NAME whisper-ggml-vulkan)
        target_include_directories(ggml-vulkan PRIVATE
            "${CMAKE_SOURCE_DIR}/../../../../../llm/src/main/cpp/host-vulkan-headers")
    endif()
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)


endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # Default target

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
