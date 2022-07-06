cmake_minimum_required(VERSION 3.10.2)
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake;${CMAKE_MODULE_PATH}")

if(APPLE AND NOT DEFINED ENV{MACOSX_DEPLOYMENT_TARGET} AND NOT DEFINED ENV{SDKROOT})
  set(CMAKE_OSX_DEPLOYMENT_TARGET ${CMAKE_SYSTEM_VERSION})
endif()

include(Utilities)
project(KinesisVideoProducerCloudwatch LANGUAGES C CXX) # what to do here, take out C? why is cpp called CXX in this case?

set(CMAKE_MACOSX_RPATH TRUE)
get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)

# changed below, not sure where KINESIS_VIDEO_PRODUCER_CPP_SRC gets set
message(STATUS "Kinesis Video Producer path is ${KINESIS_VIDEO_PRODUCER_CPP_SRC}")

if (NOT OPEN_SRC_INSTALL_PREFIX)
  set(OPEN_SRC_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/open-source)
  set(ENV{PKG_CONFIG_PATH}
        "$ENV{PKG_CONFIG_PATH}:${OPEN_SRC_INSTALL_PREFIX}/lib/pkgconfig")
  set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})
endif()



if(NOT EXISTS ${OPEN_SRC_INSTALL_PREFIX})
  file(MAKE_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX})
endif()
# if(NOT EXISTS ${DEPENDENCY_DOWNLOAD_PATH})
#   file(MAKE_DIRECTORY ${DEPENDENCY_DOWNLOAD_PATH})
# endif()

# add_subdirectory(${DEPENDENCY_DOWNLOAD_PATH}/libkvscppproducer/kvscppproducer-src EXCLUDE_FROM_ALL)


message(STATUS "Begin building dependencies.")

build_dependency(autoconf)
build_dependency(automake)
# build_dependency(kvsProducerC)
build_dependency(kvsProducerCPP) # changed this
build_dependency(cppsdk)



message(STATUS "Finished building dependencies.")

############# find dependent libraries ############

find_package(Threads)
find_package(PkgConfig REQUIRED)
find_package(AWSSDK REQUIRED COMPONENTS monitoring logs)

if (WIN32)
  find_library(LIBKVSPRODUCERCPP cppproducer REQUIRED) # changed this
else()
  pkg_check_modules(LIBKVSPRODUCERCPP REQUIRED libcppproducer) # changed this
endif()

set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INSTALL_PREFIX}/include)
set(OPEN_SRC_LIBRARY_DIRS ${OPEN_SRC_INSTALL_PREFIX}/lib)
include_directories(${OPEN_SRC_INCLUDE_DIRS})
include_directories(${KinesisVideoProducerCPP_SOURCE_DIR}/src/include)
link_directories(${OPEN_SRC_LIBRARY_DIRS})
message("the cppp directory: " ${KinesisVideoProducerCPP_SOURCE_DIR})
add_library(KinesisVideoProducer ${LINKAGE} ${PRODUCER_CPP_SOURCE_FILES})
target_link_libraries(
  KinesisVideoProducer
  PUBLIC cppproducer)


set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
add_executable(kvsProducerSampleCloudwatch
            ${CMAKE_CURRENT_SOURCE_DIR}/src/CanaryTest.cpp # changed this
            ${CMAKE_CURRENT_SOURCE_DIR}/src/CanaryTestTwo.cpp) # changed this

target_link_libraries(kvsProducerSampleCloudwatch cppproducer ${AWSSDK_LINK_LIBRARIES}) # changed this
