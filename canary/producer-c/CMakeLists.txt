cmake_minimum_required(VERSION 3.15)

project(KinesisVideoProducerCloudwatch LANGUAGES C CXX)
include(FetchContent)

set(CMAKE_CXX_STANDARD 11)

FetchContent_Declare(
        producerc
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-c.git
        GIT_TAG        178109a5dbfc5288ba5cf7fab1dc1afd5e2e182b
)

FetchContent_Declare(
        cloudwatch
        GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
        GIT_TAG        1.9.67
)

FetchContent_GetProperties(producerc)
if(NOT producerc_POPULATED)
  FetchContent_Populate(producerc)
  set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
  set(USE_OPENSSL ON)
  set(OPEN_SRC_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR})
  add_subdirectory(${producerc_SOURCE_DIR} ${producerc_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

FetchContent_GetProperties(cloudwatch)
if(NOT cloudwatch_POPULATED)
  FetchContent_Populate(cloudwatch)
  set(CMAKE_CXX_FLAGS "-Wno-error=deprecated-declarations")
  add_subdirectory(${cloudwatch_SOURCE_DIR} ${cloudwatch_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

file(GLOB PIC_HEADERS "${pic_project_SOURCE_DIR}/src/*/include")
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-core/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-monitoring/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-logs/include)
include_directories(${producerc_SOURCE_DIR}/src/include)
include_directories(${producerc_SOURCE_DIR}/open-source/include)
include_directories("${PIC_HEADERS}")

link_directories(${producerc_SOURCE_DIR}/open-source/lib)
add_executable(
        kvsProducerSampleCloudwatch
        canary/KvsProducerSampleCloudwatch.cpp
        canary/CanaryStreamUtils.cpp
        canary/CanaryLogsUtils.cpp
        canary/CanaryUtils.h)
target_link_libraries(
        kvsProducerSampleCloudwatch
        cproducer
        kvspicUtils
        aws-cpp-sdk-core
        aws-cpp-sdk-monitoring
        aws-cpp-sdk-logs)

