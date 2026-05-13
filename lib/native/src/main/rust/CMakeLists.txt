cmake_minimum_required(VERSION 3.22)

project(florisboard C CXX)

if(CMAKE_HOST_WIN32)
    set(USER_HOME "$ENV{USERPROFILE}")
else()
    set(USER_HOME "$ENV{HOME}")
endif()

find_program(RUSTUP_EXECUTABLE NAMES rustup HINTS "${USER_HOME}/.cargo/bin")
find_program(CARGO_EXECUTABLE NAMES cargo HINTS "${USER_HOME}/.cargo/bin")
if(NOT RUSTUP_EXECUTABLE OR NOT CARGO_EXECUTABLE)
    message(FATAL_ERROR "Rust toolchain not found on this system. Please install Rust toolchain, then try again.")
else()
    message(STATUS "Using Rust toolchain: ${RUSTUP_EXECUTABLE}")
endif()

### llama.cpp — on-device AI inference backend ###

# Static linking — we bundle everything into libfl_native.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs")

# Disable all GPU backends and optional features
set(GGML_CUDA  OFF CACHE BOOL "")
set(GGML_METAL OFF CACHE BOOL "")
set(GGML_VULKAN OFF CACHE BOOL "")
set(GGML_SYCL  OFF CACHE BOOL "")
set(GGML_CANN  OFF CACHE BOOL "")
set(GGML_RPC   OFF CACHE BOOL "")
set(GGML_BLAS  OFF CACHE BOOL "")
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "")
set(GGML_CPU_HBM      OFF CACHE BOOL "")
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "")
set(GGML_NATIVE OFF CACHE BOOL "Cross-compiling for Android")
set(GGML_BACKEND_DL OFF CACHE BOOL "No dynamic backend loading")

# Disable llama.cpp tools, tests, examples, server
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "")
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "")

add_subdirectory(../llama.cpp llama_build)

### FlorisBoard ###

add_library(fl_native SHARED src/lib.c src/llama_jni.cpp)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(ANDROID_TARGET "armv7a-linux-androideabi")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(ANDROID_TARGET "aarch64-linux-android")
    set(RUST_TARGET "aarch64-linux-android")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
    set(ANDROID_TARGET "i686-linux-android")
    set(RUST_TARGET "i686-linux-android")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(ANDROID_TARGET "x86_64-linux-android")
    set(RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported ABI: ${CMAKE_ANDROID_ARCH_ABI}")
endif()
get_filename_component(LLVM_TOOLCHAIN ${CMAKE_C_COMPILER} DIRECTORY)

set(FL_NATIVE_RUST_PATH "${CMAKE_CURRENT_SOURCE_DIR}/target/${RUST_TARGET}/release/libfl_native_rust.a")

add_custom_target(
    setup_rust_target ALL
    COMMAND ${RUSTUP_EXECUTABLE} target add ${RUST_TARGET}
)
add_custom_target(
    fl_native_rust_build ALL
    COMMAND ${CARGO_EXECUTABLE} rustc --release --locked --target ${RUST_TARGET} --
        -C linker="${LLVM_TOOLCHAIN}/${ANDROID_TARGET}${CMAKE_ANDROID_API}-clang"
    DEPENDS setup_rust_target
    BYPRODUCTS ${FL_NATIVE_RUST_PATH}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
)

add_dependencies(fl_native fl_native_rust_build)

# Link llama.cpp static lib + Rust native lib into the final .so
# --whole-archive and --allow-multiple-definition are for the Rust static lib only
target_link_libraries(fl_native
    android log llama
    -Wl,--whole-archive -Wl,--allow-multiple-definition -Wl,--build-id=none ${FL_NATIVE_RUST_PATH}
)
