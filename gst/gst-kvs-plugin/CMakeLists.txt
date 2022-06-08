cmake_minimum_required(VERSION 3.11)

project(kvsWebrtcPlugin LANGUAGES C)

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake;${CMAKE_MODULE_PATH}")
include(Utilities)
if (WIN32)
  set(PKG_CONFIG_EXECUTABLE "C:\\gstreamer\\1.0\\x86_64\\bin\\pkg-config.exe")
endif()

set(ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:${OPEN_SRC_INSTALL_PREFIX}/lib/pkgconfig")
set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})

include(FetchContent)

FetchContent_Declare(
        webrtc
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c
        GIT_TAG v1.7.3
        #GIT_TAG 8b8b2bdf064f6cb2b6495339d31efc3518b12eb9
)

FetchContent_Declare(
        cproducer
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-c
        GIT_TAG v1.3.0
        #GIT_TAG 58e1c6a1cb191f947d904784503b506e04525682
)

FetchContent_GetProperties(webrtc)
if(NOT webrtc_POPULATED)
  FetchContent_Populate(webrtc)
  add_subdirectory(${webrtc_SOURCE_DIR} ${webrtc_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

FetchContent_GetProperties(cproducer)
if(NOT cproducer_POPULATED)
  FetchContent_Populate(cproducer)
  add_subdirectory(${cproducer_SOURCE_DIR} ${cproducer_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# pass ca cert location to sdk
add_definitions(-DKVS_CA_CERT_PATH="${CMAKE_SOURCE_DIR}/certs/cert.pem")
add_definitions(-DCMAKE_DETECTED_CACERT_PATH)

#pkg_check_modules(GST_APP REQUIRED gstreamer-app-1.0)
include_directories(${GST_APP_INCLUDE_DIRS})
link_directories(${GST_APP_LIBRARY_DIRS})

include_directories(${cproducer_SOURCE_DIR}/src/include)
include_directories(${cproducer_SOURCE_DIR}/open-source/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/client/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/common/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/heap/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/mkvgen/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/state/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/utils/include)
include_directories(${cproducer_SOURCE_DIR}/dependency/libkvspic/kvspic-src/src/view/include)
link_directories(${cproducer_SOURCE_DIR}/open-source/lib)
include_directories(${webrtc_SOURCE_DIR}/src/include)
include_directories(${webrtc_SOURCE_DIR}/open-source/include)
link_directories(${webrtc_SOURCE_DIR}/open-source/lib)

file(GLOB GST_PLUGIN_SOURCE_FILES "src/*.c")

add_library(gstkvsplugin MODULE ${GST_PLUGIN_SOURCE_FILES})

target_link_libraries(gstkvsplugin PRIVATE
        ${GST_APP_LIBRARIES}
        kvsWebrtcClient
        kvsWebrtcSignalingClient
        kvsCommonCurl
        kvspicUtils
        cproducer)
