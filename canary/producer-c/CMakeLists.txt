cmake_minimum_required(VERSION 3.11)

project(KinesisVideoProducerCloudwatch LANGUAGES C CXX)
include(FetchContent)

set(CMAKE_CXX_STANDARD 11)

# AWS SDK options
set(CUSTOM_MEMORY_MANAGEMENT OFF CACHE BOOL "Tell AWS SDK to not use custom memory management" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "Disable building tests for AWS SDK" FORCE)
set(BUILD_ONLY "monitoring;logs" CACHE STRING "Tell AWS SDK to only build monitoring and logs" FORCE)
set(BUILD_COMMON_CURL ON CACHE BOOL "Curl" FORCE)
set(BUILD_DEPENDENCIES OFF CACHE BOOL "Deps in kvs" FORCE)

# -DKVS_SDK_TRACK=develop builds PIC and Producer C from their develop branches. See ../cmake/KvsSdkSource.cmake.
include(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/KvsSdkSource.cmake)
kvs_sdk_top_level_ref(${KVS_PRODUCER_C_REF} PRODUCER_C_GIT_TAG)
kvs_sdk_prepare_layers(PIC)

FetchContent_Declare(
        producerc
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-c.git
        GIT_TAG        ${PRODUCER_C_GIT_TAG}
)

FetchContent_Declare(
        cloudwatch
        GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
        GIT_TAG        1.11.92
)

FetchContent_GetProperties(producerc)
if(NOT producerc_POPULATED)
  FetchContent_Populate(producerc)
  add_subdirectory(${producerc_SOURCE_DIR} ${producerc_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()
kvs_sdk_verify_layers()

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

