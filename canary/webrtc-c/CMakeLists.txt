cmake_minimum_required(VERSION 3.11)
project(KVSWebRTCCanary LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 11)

include(FetchContent)

option(CANARY_USE_OPENSSL "OPenssl" ON)
option(CANARY_USE_MBEDTLS "MBedtls" OFF)

FetchContent_Declare(
  webrtc
  GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c
  GIT_TAG        main
)

FetchContent_Declare(
  cloudwatch
  GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
  GIT_TAG        1.11.217
)

FetchContent_GetProperties(webrtc)
if(NOT webrtc_POPULATED)
  FetchContent_Populate(webrtc)
  set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
  if(CANARY_USE_OPENSSL)
    message(STATUS "Setting USE_OPENSSL to ON for webrtc")
    set(USE_OPENSSL ON CACHE BOOL "Openssl" FORCE)
  elseif(CANARY_USE_MBEDTLS)
    message(STATUS "Setting USE_OPENSSL to OFF and USE_MBEDTLS to ON for webrtc")
    set(USE_OPENSSL OFF CACHE BOOL "Openssl" FORCE)
    set(USE_MBEDTLS ON CACHE BOOL "Openssl" FORCE)
  endif()
  set(BUILD_SAMPLE OFF)
  add_subdirectory(${webrtc_SOURCE_DIR} ${webrtc_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

FetchContent_GetProperties(cloudwatch)
if(NOT cloudwatch_POPULATED)
  FetchContent_Populate(cloudwatch)
  # AWS SDK options
  set(CUSTOM_MEMORY_MANAGEMENT OFF)
  set(ENABLE_TESTING OFF)
  set(BUILD_ONLY "monitoring;logs")
  set(USE_OPENSSL ON)
  set(CMAKE_CXX_FLAGS "-Wno-error=deprecated-declarations")
  add_subdirectory(${cloudwatch_SOURCE_DIR} ${cloudwatch_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# pass ca cert location to sdk
add_definitions(-DKVS_CA_CERT_PATH="${CMAKE_SOURCE_DIR}/certs/cert.pem")
add_definitions(-DCMAKE_DETECTED_CACERT_PATH)

include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-core/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-monitoring/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-logs/include)
include_directories(${webrtc_SOURCE_DIR}/src/include)
include_directories(${webrtc_SOURCE_DIR}/open-source/include)
link_directories(${webrtc_SOURCE_DIR}/open-source/lib)
add_library(
  kvsWebrtcCanary
  src/Config.cpp
  src/CloudwatchLogs.cpp
  src/CloudwatchMonitoring.cpp
  src/Cloudwatch.cpp
  src/Peer.cpp
  src/media-server-storage/Common.cpp)
target_link_libraries(
  kvsWebrtcCanary
  kvsWebrtcClient
  kvsWebrtcSignalingClient
  kvspicUtils
  aws-cpp-sdk-core
  aws-cpp-sdk-monitoring
  aws-cpp-sdk-logs)

add_executable(
  kvsWebrtcCanaryWebrtc
  src/CanaryWebrtc.cpp)
target_link_libraries(
  kvsWebrtcCanaryWebrtc
  kvsWebrtcCanary)

add_executable(
  kvsWebrtcCanarySignaling
  src/CanarySignaling.cpp)
target_link_libraries(
  kvsWebrtcCanarySignaling
  kvsWebrtcCanary)

add_executable(
  kvsWebrtcStorageSample
  src/media-server-storage/kvsWebRTCClientMaster.cpp)
target_link_libraries(
  kvsWebrtcStorageSample
  kvsWebrtcCanary)

file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/assets" DESTINATION .)
