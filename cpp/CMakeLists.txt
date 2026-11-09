cmake_minimum_required(VERSION 3.16)

project(zksnark-java-sdk LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

find_package(Threads REQUIRED)
find_package(Java REQUIRED COMPONENTS Development)
find_program(CARGO_EXECUTABLE cargo)
find_program(NATIVE_MAKE_EXECUTABLE NAMES gmake make)
if(NOT CARGO_EXECUTABLE OR NOT NATIVE_MAKE_EXECUTABLE)
    message(FATAL_ERROR "cargo and make are required")
endif()
include(ExternalProject)

set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "JDK used to compile JNI")
if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "JAVA_HOME must point to a JDK containing include/jni.h")
endif()

string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" NATIVE_PROCESSOR)
if(NATIVE_PROCESSOR MATCHES "^(arm64|aarch64)$")
    set(NATIVE_ARCH aarch64)
elseif(NATIVE_PROCESSOR MATCHES "^(x86_64|amd64)$")
    set(NATIVE_ARCH x86_64)
else()
    message(FATAL_ERROR "Unsupported architecture: ${CMAKE_SYSTEM_PROCESSOR}")
endif()

set(RUST_BUILD_ENV "CARGO_TARGET_DIR=${CMAKE_BINARY_DIR}/cargo")
set(SODIUM_BUILD_ENV "CC=${CMAKE_C_COMPILER}" "CFLAGS=-O2 -fPIC")
set(SODIUM_HOST_ARGS)
if(APPLE)
    set(JNI_OS darwin)
    set(NATIVE_RESOURCE_DIR osx64)
    if(NOT CMAKE_OSX_DEPLOYMENT_TARGET)
        set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0")
    endif()
    list(APPEND RUST_BUILD_ENV "MACOSX_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    list(APPEND SODIUM_BUILD_ENV "MACOSX_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    # The old release tarball's config.guess does not recognize Apple Silicon.
    set(SODIUM_HOST_ARGS "--build=${NATIVE_ARCH}-apple-darwin" "--host=${NATIVE_ARCH}-apple-darwin")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(JNI_OS linux)
    set(NATIVE_RESOURCE_DIR linux64)
else()
    message(FATAL_ERROR "Only Linux and macOS are supported")
endif()
if(NATIVE_ARCH STREQUAL "aarch64")
    string(APPEND NATIVE_RESOURCE_DIR "/aarch64")
endif()

set(RUST_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../rust/librustzcash")
set(RUST_LIBRARY "${CMAKE_BINARY_DIR}/cargo/release/librustzcash.a")
# Let Cargo check source changes on every build; ExternalProject stamps alone
# do not notice edits inside a local Rust submodule.
add_custom_target(rust_build
    COMMAND "${CMAKE_COMMAND}" -E env ${RUST_BUILD_ENV}
        "${CARGO_EXECUTABLE}" build --locked --release --package librustzcash
        --manifest-path "${RUST_SOURCE_DIR}/Cargo.toml"
    BYPRODUCTS "${RUST_LIBRARY}"
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
    VERBATIM)

set(LIBSODIUM_ARCHIVE "" CACHE FILEPATH "Optional local libsodium 1.0.15 release archive")
if(LIBSODIUM_ARCHIVE)
    set(SODIUM_URL "${LIBSODIUM_ARCHIVE}")
else()
    set(SODIUM_URL "https://github.com/jedisct1/libsodium/releases/download/1.0.15/libsodium-1.0.15.tar.gz")
endif()
set(SODIUM_SOURCE_DIR "${CMAKE_BINARY_DIR}/libsodium/src/libsodium")
set(SODIUM_LIBRARY "${SODIUM_SOURCE_DIR}/src/libsodium/.libs/libsodium.a")
ExternalProject_Add(libsodium
    PREFIX "${CMAKE_BINARY_DIR}/libsodium"
    SOURCE_DIR "${SODIUM_SOURCE_DIR}"
    URL "${SODIUM_URL}"
    URL_HASH SHA256=fb6a9e879a2f674592e4328c5d9f79f082405ee4bb05cb6e679b90afe9e178f4
    BUILD_IN_SOURCE 1
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env ${SODIUM_BUILD_ENV}
        <SOURCE_DIR>/configure --disable-shared --enable-static --with-pic
        --disable-pie ${SODIUM_HOST_ARGS}
    BUILD_COMMAND "${NATIVE_MAKE_EXECUTABLE}" -j2
    BUILD_BYPRODUCTS "${SODIUM_LIBRARY}"
    INSTALL_COMMAND "")

set(GENERATED_INCLUDE_DIR "${CMAKE_BINARY_DIR}/include")
file(MAKE_DIRECTORY "${GENERATED_INCLUDE_DIR}" "${CMAKE_BINARY_DIR}/classes")
foreach(JNI_CLASS Librustzcash Libsodium)
    set(JAVA_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/java/org/tron/common/zksnark/${JNI_CLASS}.java")
    set(JNI_HEADER "${GENERATED_INCLUDE_DIR}/org_tron_common_zksnark_${JNI_CLASS}_${JNI_CLASS}JNI.h")
    add_custom_command(
        OUTPUT "${JNI_HEADER}"
        COMMAND "${Java_JAVAC_EXECUTABLE}" -h "${GENERATED_INCLUDE_DIR}"
            -d "${CMAKE_BINARY_DIR}/classes" "${JAVA_SOURCE}"
        DEPENDS "${JAVA_SOURCE}"
        VERBATIM)
    list(APPEND JNI_HEADERS "${JNI_HEADER}")
endforeach()

add_library(zksnarkjni SHARED
    src/LibrustzcashJNIImpl.cpp src/LibsodiumJNIImpl.cpp src/memcpy.c ${JNI_HEADERS})
target_include_directories(zksnarkjni PRIVATE
    "${GENERATED_INCLUDE_DIR}"
    "${JAVA_HOME}/include" "${JAVA_HOME}/include/${JNI_OS}"
    "${RUST_SOURCE_DIR}/librustzcash/include"
    "${SODIUM_SOURCE_DIR}/src/libsodium/include")
target_compile_options(zksnarkjni PRIVATE -Wall)
target_link_libraries(zksnarkjni PRIVATE
    "${RUST_LIBRARY}" "${SODIUM_LIBRARY}" Threads::Threads ${CMAKE_DL_LIBS} m)
add_dependencies(zksnarkjni rust_build libsodium)

if(APPLE)
    execute_process(COMMAND xcrun --show-sdk-path
        OUTPUT_VARIABLE NATIVE_MACOS_SDK OUTPUT_STRIP_TRAILING_WHITESPACE)
    target_include_directories(zksnarkjni PRIVATE "${NATIVE_MACOS_SDK}/usr/include/c++/v1")
    target_link_libraries(zksnarkjni PRIVATE "-framework Security" "-framework CoreFoundation" resolv)
    set_target_properties(zksnarkjni PROPERTIES SUFFIX ".jnilib" INSTALL_NAME_DIR "@rpath")
elseif(NATIVE_ARCH STREQUAL "x86_64")
    target_link_options(zksnarkjni PRIVATE "-Wl,--wrap=memcpy")
endif()

set(NATIVE_INSTALL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/resources/META-INF/native"
    CACHE PATH "Root directory for packaged native libraries")
install(TARGETS zksnarkjni LIBRARY DESTINATION "${NATIVE_INSTALL_ROOT}/${NATIVE_RESOURCE_DIR}")
