cmake_minimum_required(VERSION 3.10.2)
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake;${CMAKE_MODULE_PATH}")

if(APPLE AND NOT DEFINED ENV{MACOSX_DEPLOYMENT_TARGET} AND NOT DEFINED ENV{SDKROOT})
  set(CMAKE_OSX_DEPLOYMENT_TARGET ${CMAKE_SYSTEM_VERSION})
endif()

include(Utilities)
project(KinesisVideoProducerCloudwatch LANGUAGES C CXX)

set(CMAKE_MACOSX_RPATH TRUE)
get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)

message(STATUS "Kinesis Video Producer path is ${KINESIS_VIDEO_PRODUCER_C_SRC}")

if (NOT OPEN_SRC_INSTALL_PREFIX)
  set(OPEN_SRC_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/open-source)
  set(ENV{PKG_CONFIG_PATH}
        "$ENV{PKG_CONFIG_PATH}:${OPEN_SRC_INSTALL_PREFIX}/lib/pkgconfig")
  set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})
endif()

if(NOT EXISTS ${OPEN_SRC_INSTALL_PREFIX})
  file(MAKE_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX})
endif()

message(STATUS "Begin building dependencies.")

build_dependency(kvsProducerC)
build_dependency(cppsdk)

message(STATUS "Finished building dependencies.")

############# find dependent libraries ############

find_package(Threads)
find_package(PkgConfig REQUIRED)
find_package(AWSSDK REQUIRED COMPONENTS monitoring logs)

if (WIN32)
  find_library(LIBKVSPIC_LIBRARIES kvspic REQUIRED)
  find_library(LIBKVSPRODUCERC cproducer REQUIRED)
else()
  pkg_check_modules(LIBKVSPIC REQUIRED libkvspic)
  pkg_check_modules(LIBKVSPRODUCERC REQUIRED libcproducer)
endif()

set(OPEN_SRC_INCLUDE_DIRS ${LIBKVSPIC_INCLUDE_DIRS})
link_directories(${LIBKVSPIC_LIBRARY_DIRS})

include_directories(${OPEN_SRC_INCLUDE_DIRS})

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
add_executable(kvsProducerSampleCloudwatch
            ${CMAKE_CURRENT_SOURCE_DIR}/canary/KvsProducerSampleCloudwatch.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/canary/CanaryStreamUtils.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/canary/CanaryLogsUtils.cpp)

target_link_libraries(kvsProducerSampleCloudwatch cproducer kvspicUtils ${AWSSDK_LINK_LIBRARIES})
