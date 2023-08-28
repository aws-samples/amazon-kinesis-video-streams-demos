cmake_minimum_required(VERSION 3.13)
project(KVSWebRTCCanary LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 11)

include(FetchContent)

# AWS SDK options
set(CUSTOM_MEMORY_MANAGEMENT OFF CACHE BOOL "Tell AWS SDK to not use custom memory management" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "Disable building tests for AWS SDK" FORCE)
set(BUILD_ONLY "monitoring;logs" CACHE STRING "Tell AWS SDK to only build monitoring and logs" FORCE)
set(BUILD_SAMPLE OFF CACHE BOOL "Disable sample building for WebRTC library" FORCE)

if (NOT OPEN_SRC_INSTALL_PREFIX)
  set(OPEN_SRC_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/build/_deps)
  set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})
endif()
if(NOT EXISTS ${OPEN_SRC_INSTALL_PREFIX})
  file(MAKE_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX})
endif()

FetchContent_Declare(
  webrtc
  GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c
  GIT_TAG        e9f3cab63756669746cdf79a9254778b6c4cf11e
)

FetchContent_Declare(
  cloudwatch
  GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
  GIT_TAG        1.11.143
  )

FetchContent_GetProperties(webrtc)
if(NOT webrtc_POPULATED)
  FetchContent_Populate(webrtc)
  add_subdirectory(${webrtc_SOURCE_DIR} ${webrtc_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

FetchContent_GetProperties(cloudwatch)
if(NOT cloudwatch_POPULATED)
  FetchContent_Populate(cloudwatch)
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

link_directories(${OPEN_SRC_INSTALL_PREFIX}/lib)

add_library(
  kvsWebrtcCanary
  src/Config.cpp
  src/CloudwatchLogs.cpp
  src/CloudwatchMonitoring.cpp
  src/Cloudwatch.cpp
  src/Peer.cpp)
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

file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/assets" DESTINATION .)
