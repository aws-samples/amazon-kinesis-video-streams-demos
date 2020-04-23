cmake_minimum_required(VERSION 2.8)
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake;${CMAKE_MODULE_PATH}")
include(Utilities)
project(KinesisVideoProducerC LANGUAGES C)

# User Flags
option(ADD_MUCLIBC "Add -muclibc c flag" OFF)
option(BUILD_DEPENDENCIES "Whether or not to build depending libraries from source" ON)
option(USE_OPENSSL "Use openssl as crypto library" ON)
option(USE_MBEDTLS "Use openssl as crypto library" OFF)
option(BUILD_COMMON_LWS "Whether or not to build ProducerC libwebsockets common library" OFF)
option(BUILD_COMMON_CURL "Whether or not to build ProducerC curl common library" ON)
option(BUILD_OPENSSL_PLATFORM "If buildng OpenSSL what is the target platform" OFF)

# Developer Flags
option(BUILD_TEST "Build the testing tree." OFF)
option(CODE_COVERAGE "Enable coverage reporting" OFF)
option(COMPILER_WARNINGS "Enable all compiler warnings." OFF)
option(ADDRESS_SANITIZER "Build with AddressSanitizer." OFF)
option(MEMORY_SANITIZER "Build with MemorySanitizer." OFF)
option(THREAD_SANITIZER "Build with ThreadSanitizer." OFF)
option(UNDEFINED_BEHAVIOR_SANITIZER "Build with UndefinedBehaviorSanitizer." OFF)
option(ALIGNED_MEMORY_MODEL "Aligned memory model ONLY." OFF)

set(CMAKE_MACOSX_RPATH TRUE)

get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)

if(NOT KINESIS_VIDEO_PIC_SRC)
  if(DEFINED ENV{KINESIS_VIDEO_PIC_SRC})
    set(KINESIS_VIDEO_PIC_SRC $ENV{KINESIS_VIDEO_PIC_SRC})
  else()
    set(KINESIS_VIDEO_PIC_SRC "${CMAKE_CURRENT_SOURCE_DIR}/open-source/kinesis-video-streams-pic/")
  endif()
endif()

if(NOT KINESIS_VIDEO_PRODUCER_C_SRC)
    if(DEFINED ENV{KINESIS_VIDEO_PRODUCER_C_SRC})
        set(KINESIS_VIDEO_PRODUCER_C_SRC $ENV{KINESIS_VIDEO_PRODUCER_C_SRC})
    else()
        set(KINESIS_VIDEO_PRODUCER_C_SRC "${CMAKE_CURRENT_SOURCE_DIR}/open-source/kinesis-video-streams-producer-c")
    endif()
endif()

if(NOT KINESIS_VIDEO_OPEN_SOURCE_SRC)
  set(KINESIS_VIDEO_OPEN_SOURCE_SRC ${CMAKE_SOURCE_DIR}/open-source)
endif()

message(STATUS "Kinesis Video PIC path is ${KINESIS_VIDEO_PIC_SRC}")
message(STATUS "Kinesis Video Producer path is ${KINESIS_VIDEO_PRODUCER_C_SRC}")
message(STATUS "Kinesis Video Open Source path is ${KINESIS_VIDEO_OPEN_SOURCE_SRC}")

if(BUILD_DEPENDENCIES)
  if(NOT EXISTS ${KINESIS_VIDEO_OPEN_SOURCE_SRC})
    file(MAKE_DIRECTORY ${KINESIS_VIDEO_OPEN_SOURCE_SRC}/local)
  endif()

  if (NOT OPEN_SRC_INSTALL_PREFIX)
    set(OPEN_SRC_INSTALL_PREFIX ${KINESIS_VIDEO_OPEN_SOURCE_SRC}/local)
    set(ENV{PKG_CONFIG_PATH}
          "$ENV{PKG_CONFIG_PATH}:${OPEN_SRC_INSTALL_PREFIX}/lib/pkgconfig")
    set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})
  endif()

  message(STATUS "Begin building dependencies.")

  set(SSL_OPTIONS USE_OPENSSL USE_MBEDTLS)
  count_true(ENABLED_SSL_OPTION_COUNT
    ${SSL_OPTIONS})

  if(ENABLED_SSL_OPTION_COUNT GREATER "1")
    message(FATAL_ERROR "Only one of ${SSL_OPTIONS} can be enabled")
  endif()

  if(USE_OPENSSL)
    set(BUILD_ARGS -DBUILD_STATIC=${BUILD_STATIC}
                   -DBUILD_OPENSSL_PLATFORM=${BUILD_OPENSSL_PLATFORM})
    build_dependency(openssl ${BUILD_ARGS})
  elseif(USE_MBEDTLS)
    set(BUILD_ARGS -DBUILD_STATIC=${BUILD_STATIC})
    build_dependency(mbedtls ${BUILD_ARGS})
  else()
    message(FATAL_ERROR "No crypto library selected.")
  endif()

  if (BUILD_COMMON_LWS)
    set(BUILD_ARGS  -DBUILD_STATIC=${BUILD_STATIC}
                    -DOPENSSL_DIR=${OPEN_SRC_INSTALL_PREFIX})
    build_dependency(websockets ${BUILD_ARGS})
  endif()

  if (BUILD_COMMON_CURL)
    set(BUILD_ARGS  -DBUILD_STATIC=${BUILD_STATIC}
                    -DUSE_OPENSSL=${USE_OPENSSL}
                    -DUSE_MBEDTLS=${USE_MBEDTLS})
    build_dependency(curl ${BUILD_ARGS})
  endif()

  build_dependency(jsmn)

  if(BUILD_TEST)
    build_dependency(gtest)
  endif()

  message(STATUS "Finished building dependencies.")
endif()

############# find dependent libraries ############

find_package(Threads)
find_package(PkgConfig REQUIRED)

set(OPEN_SRC_INCLUDE_DIRS )

if(USE_OPENSSL)
  find_package(OpenSSL REQUIRED)
  set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INCLUDE_DIRS} ${OPENSSL_INCLUDE_DIR})
elseif(USE_MBEDTLS)
  find_package(MbedTLS REQUIRED)
  set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INCLUDE_DIRS} ${MBEDTLS_INCLUDE_DIRS})
else()
  message(FATAL_ERROR "No crypto library selected.")
endif()

if(BUILD_COMMON_CURL)
    pkg_check_modules(LIBCURL REQUIRED libcurl)

    set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INCLUDE_DIRS} ${LIBCURL_INCLUDE_DIRS})
    link_directories(${LIBCURL_LIBRARY_DIRS})
endif()

if (BUILD_COMMON_LWS)
    pkg_check_modules(LIBWEBSOCKET REQUIRED libwebsockets)

    set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INCLUDE_DIRS} ${LIBWEBSOCKET_INCLUDE_DIRS})
    link_directories(${LIBWEBSOCKET_LIBRARY_DIRS})
endif()

find_package(Jsmn REQUIRED)

############# find dependent libraries end ############

if(CMAKE_SIZEOF_VOID_P STREQUAL 4)
    message(STATUS "Bitness 32 bits")
    set(KINESIS_VIDEO_BUILD_BITNESS "x86")
    set(KINESIS_VIDEO_BUILD_BITNESS_TYPE "Win32")
    set(KINESIS_VIDEO_BUILD_BITNESS_NAME "x86")
elseif(CMAKE_SIZEOF_VOID_P STREQUAL 8)
    message(STATUS "Bitness 64 bits")
    set(KINESIS_VIDEO_BUILD_BITNESS "x64")
    set(KINESIS_VIDEO_BUILD_BITNESS_TYPE "x64")
    set(KINESIS_VIDEO_BUILD_BITNESS_NAME "x86_64")
else()
    message(FATAL_ERROR "Unknown bitness")
endif()

if("${CMAKE_C_COMPILER_ID}" MATCHES "GNU|Clang")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")

    if(CODE_COVERAGE)
      set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O0 -g -fprofile-arcs -ftest-coverage")
      set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} --coverage")
    endif()
    function(enableSanitizer SANITIZER)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O0 -g -fsanitize=${SANITIZER} -fno-omit-frame-pointer" PARENT_SCOPE)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O0 -g -fsanitize=${SANITIZER} -fno-omit-frame-pointer -fno-optimize-sibling-calls" PARENT_SCOPE)
        set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fsanitize=${SANITIZER}" PARENT_SCOPE)
    endfunction()
    if(ADDRESS_SANITIZER)
        enableSanitizer("address")
    endif()
    if(MEMORY_SANITIZER)
        enableSanitizer("memory")
    endif()
    if(THREAD_SANITIZER)
        enableSanitizer("thread")
    endif()
    if(UNDEFINED_BEHAVIOR_SANITIZER)
        enableSanitizer("undefined")
    endif()
endif()

file(GLOB KVS_COMMON_SOURCE_FILES_BASE "${KINESIS_VIDEO_PRODUCER_C_SRC}/src/source/Common/*.c")
file(GLOB KVS_COMMON_SOURCE_FILES_LWS "${KINESIS_VIDEO_PRODUCER_C_SRC}/src/source/Common/Lws/*.c")
file(GLOB KVS_COMMON_SOURCE_FILES_CURL "${KINESIS_VIDEO_PRODUCER_C_SRC}/src/source/Common/Curl/*.c")
file(GLOB PRODUCER_C_SOURCE_FILES "${KINESIS_VIDEO_PRODUCER_C_SRC}/src/source/*.c")
file(GLOB PIC_HEADERS "${KINESIS_VIDEO_PIC_SRC}/src/*/include")

include_directories(${PIC_HEADERS})
include_directories(${KINESIS_VIDEO_PRODUCER_C_SRC}/src/include)
include_directories(${OPEN_SRC_INCLUDE_DIRS})
include_directories(${KINESIS_VIDEO_OPEN_SOURCE_SRC}/include)

add_subdirectory(${KINESIS_VIDEO_PIC_SRC} ./kvspic EXCLUDE_FROM_ALL)

if(ALIGNED_MEMORY_MODEL)
    add_definitions(-DALIGNED_MEMORY_MODEL)
endif()

# use openssl by default
set(CPRODUCER_COMMON_TLS_OPTION KVS_USE_OPENSSL)
set(PRODUCER_CRYPTO_LIBRARY
        OpenSSL::Crypto
        OpenSSL::SSL)
if (USE_MBEDTLS)
    set(CPRODUCER_COMMON_TLS_OPTION KVS_USE_MBEDTLS)
    set(PRODUCER_CRYPTO_LIBRARY
            MbedTLS
            MbedCrypto)
endif()

if(BUILD_COMMON_LWS)
    add_library(kvsCommonLws STATIC ${KVS_COMMON_SOURCE_FILES_BASE} ${KVS_COMMON_SOURCE_FILES_LWS})
    # dont support websocket with mbedtls yet
    target_compile_definitions(kvsCommonLws PRIVATE KVS_BUILD_WITH_LWS KVS_USE_OPENSSL)
    target_link_libraries(kvsCommonLws
            ${JSMN_LIBRARY}
            ${OPENSSL_CRYPTO_LIBRARY}
            ${OPENSSL_SSL_LIBRARY}
            ${LIBWEBSOCKET_LIBRARIES}
            ${LIBCURL_LIBRARIES}
            kvspicUtils)
endif()

# producer only uses curl right now
add_library(kvsCommonCurl STATIC ${KVS_COMMON_SOURCE_FILES_BASE} ${KVS_COMMON_SOURCE_FILES_CURL})
target_compile_definitions(kvsCommonCurl PRIVATE KVS_BUILD_WITH_CURL ${CPRODUCER_COMMON_TLS_OPTION})
target_link_libraries(kvsCommonCurl
        ${JSMN_LIBRARY}
        ${PRODUCER_CRYPTO_LIBRARY}
        ${LIBCURL_LIBRARIES})

add_library(cproducer SHARED ${PRODUCER_C_SOURCE_FILES})

if (MINGW)
# Use PRIVATE otherwise anything linking against cproducer will also link with pic again,
# causing duplicate symbols
target_link_libraries(cproducer PRIVATE
    kvspic
    kvsCommonCurl)
else()
target_link_libraries(cproducer
        kvspic
        kvsCommonCurl)
endif()

add_executable(kvsVideoOnlyStreamingSample ${KINESIS_VIDEO_PRODUCER_C_SRC}/samples/KvsVideoOnlyStreamingSample.c)
target_link_libraries(kvsVideoOnlyStreamingSample
    cproducer)

add_executable(kvsAacAudioVideoStreamingSample ${KINESIS_VIDEO_PRODUCER_C_SRC}/samples/KvsAacAudioVideoStreamingSample.c)
target_link_libraries(kvsAacAudioVideoStreamingSample
        cproducer)

if (BUILD_TEST)
    add_subdirectory(tst)
endif()
