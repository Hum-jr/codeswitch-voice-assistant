# Native build: whisper.cpp + a small JNI bridge (whisper_jni.cpp).
cmake_minimum_required(VERSION 3.22.1)

# Always build optimized: whisper.cpp in a Debug build is ~10x slower, which would
# invalidate on-device latency measurements (NFR-02). Overrides AGP's Debug default.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(whisper_jni C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp sources not found in ${WHISPER_DIR}.\n"
        "From the repo root run:\n"
        "  git submodule add https://github.com/ggml-org/whisper.cpp android/app/src/main/cpp/whisper.cpp")
endif()

# Build whisper/ggml as static libraries linked into libwhisper_jni.so (must be PIC)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # cross-compiling: never use -march=native
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # use ggml's own thread pool
# Phones from ~2018 on (ARMv8.2+) have dot-product and fp16 instructions that make the
# quantized matrix maths much faster. Generic armv8-a would leave them unused.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(${WHISPER_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper android log)
