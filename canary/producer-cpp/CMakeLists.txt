cmake_minimum_required(VERSION 3.15)

include(FetchContent)


if(APPLE AND NOT DEFINED ENV{MACOSX_DEPLOYMENT_TARGET} AND NOT DEFINED ENV{SDKROOT})
  set(CMAKE_OSX_DEPLOYMENT_TARGET ${CMAKE_SYSTEM_VERSION})
endif()

project(KinesisVideoProducerCloudwatch LANGUAGES C CXX)

#set(BUILD_GSTREAMER_PLUGIN ON CACHE BOOL "Tell KVS Producer CPP SDK to build with GStreamer Plugin" FORCE)
set(CUSTOM_MEMORY_MANAGEMENT OFF CACHE BOOL "Tell AWS SDK to not use custom memory management" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "Disable building tests for AWS SDK" FORCE)
set(BUILD_ONLY "monitoring;logs" CACHE STRING "Tell AWS SDK to only build monitoring and logs" FORCE)

FetchContent_Declare(
        producercpp
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
        GIT_TAG        99230757f68817551c0cd401950088aabe8b0c1b
)

FetchContent_Declare(
        cloudwatch
        GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
        GIT_TAG        1.10.9
)

FetchContent_GetProperties(producercpp)
if(NOT producercpp_POPULATED)
  FetchContent_Populate(producercpp)
  add_subdirectory(${producercpp_SOURCE_DIR} ${producercpp_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

FetchContent_GetProperties(cloudwatch)
if(NOT cloudwatch_POPULATED)
  FetchContent_Populate(cloudwatch)
  add_subdirectory(${cloudwatch_SOURCE_DIR} ${cloudwatch_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()


message(STATUS "KVS C Source dir: ${KinesisVideoProducerC_SOURCE_DIR}")

file(GLOB producerc_HEADERS "${KinesisVideoProducerC_SOURCE_DIR}/src/include")
file(GLOB CANARY_SOURCE_FILES "src/*.cpp")
file(GLOB PIC_HEADERS "${pic_project_SOURCE_DIR}/src/*/include")

include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-core/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-monitoring/include)
include_directories(${cloudwatch_SOURCE_DIR}/aws-cpp-sdk-logs/include)

include_directories(${PIC_HEADERS})
include_directories(${producerc_HEADERS})
include_directories(${producercpp_SOURCE_DIR}/src)
include_directories(${producercpp_SOURCE_DIR}/src/credential-providers/)
include_directories(${producercpp_SOURCE_DIR}/open-source/include)
link_directories(${producercpp_SOURCE_DIR}/open-source/lib)


find_package(PkgConfig)
pkg_check_modules(GST_APP REQUIRED gstreamer-app-1.0)
include_directories(${GST_APP_INCLUDE_DIRS})
link_directories(${GST_APP_LIBRARY_DIRS})

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
add_executable(producer_cpp_canary
        ${CANARY_SOURCE_FILES})



file(GLOB GST_PLUGIN_SOURCE_FILES "${producercpp_SOURCE_DIR}/src/gstreamer/*.cpp" "${producercpp_SOURCE_DIR}/src/gstreamer/Util/*.cpp")
add_library(gstkvssink MODULE ${GST_PLUGIN_SOURCE_FILES})
target_link_libraries(gstkvssink PRIVATE ${GST_APP_LIBRARIES} KinesisVideoProducer)

target_link_libraries(producer_cpp_canary ${GST_APP_LIBRARIES} KinesisVideoProducer
        aws-cpp-sdk-core
        aws-cpp-sdk-monitoring
        aws-cpp-sdk-logs)
