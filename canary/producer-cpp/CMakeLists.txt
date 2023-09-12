cmake_minimum_required(VERSION 3.15)

include(FetchContent)
project(KinesisVideoProducerCloudwatch)

set(CMAKE_CXX_STANDARD 17)

FetchContent_Declare(
        producercpp
        GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
        GIT_TAG        8e59b313950d4dbb1ecfb88e2108df87e3331c4e
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
  set(CUSTOM_MEMORY_MANAGEMENT OFF)
  set(ENABLE_TESTING OFF)
  set(BUILD_ONLY "monitoring;logs")
  set(USE_OPENSSL OFF)
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
