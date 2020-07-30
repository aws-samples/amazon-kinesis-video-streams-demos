cmake_minimum_required(VERSION 3.14)
project(KVSWebRTCCanary LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 11)

include(FetchContent)

# AWS SDK options
set(CUSTOM_MEMORY_MANAGEMENT OFF)
set(ENABLE_TESTING OFF)
set(BUILD_ONLY "monitoring;logs")

FetchContent_Declare(
  webrtc
  GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c
  GIT_TAG        master
)

FetchContent_Declare(
  cloudwatch
  GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
  GIT_TAG        1.8.17
)

FetchContent_MakeAvailable(webrtc cloudwatch)

# pass ca cert location to sdk
add_definitions(-DKVS_CA_CERT_PATH="${CMAKE_SOURCE_DIR}/certs/cert.pem")
add_definitions(-DCMAKE_DETECTED_CACERT_PATH)

include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-core/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-monitoring/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-logs/include)
include_directories(${webrtc_SOURCE_DIR}/src/include)
include_directories(${webrtc_SOURCE_DIR}/open-source/include)
link_directories(${webrtc_SOURCE_DIR}/open-source/lib)
add_executable(
  kvsWebrtcCanary
  src/Config.cpp
  src/CloudwatchLogs.cpp
  src/CloudwatchMonitoring.cpp
  src/Cloudwatch.cpp
  src/Peer.cpp
  src/Main.cpp)
target_link_libraries(
  kvsWebrtcCanary
  kvsWebrtcClient
  kvsWebrtcSignalingClient
  kvspicUtils
  aws-cpp-sdk-core
  aws-cpp-sdk-monitoring
  aws-cpp-sdk-logs)

file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/assets" DESTINATION .)
