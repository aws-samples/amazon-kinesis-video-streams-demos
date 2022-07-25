cmake_minimum_required(VERSION 3.16)
# set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake;${CMAKE_MODULE_PATH}")

include(FetchContent)


if(APPLE AND NOT DEFINED ENV{MACOSX_DEPLOYMENT_TARGET} AND NOT DEFINED ENV{SDKROOT})
  set(CMAKE_OSX_DEPLOYMENT_TARGET ${CMAKE_SYSTEM_VERSION})
endif()

# include(Utilities)
project(KinesisVideoProducerCloudwatch LANGUAGES C CXX) # what to do here, take out C? why is cpp called CXX in this case?

# set(CMAKE_MACOSX_RPATH TRUE)
# get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)


set(BUILD_GSTREAMER_PLUGIN ON CACHE BOOL "Tell SDK to ..." FORCE)

set(CUSTOM_MEMORY_MANAGEMENT OFF CACHE BOOL "Tell AWS SDK to not use custom memory management" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "Disable building tests for AWS SDK" FORCE)
set(BUILD_ONLY "monitoring;logs" CACHE STRING "Tell AWS SDK to only build monitoring and logs" FORCE)

FetchContent_Declare(
  producercpp
  GIT_REPOSITORY https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
  GIT_TAG        f6e705de169da69a6c99a18a603f26b52695e11e
  # GIT_TAG        70f74f14cf27b09f71dc1889f36eb6e04cdd90a8
)

FetchContent_Declare(
  cloudwatch
  GIT_REPOSITORY https://github.com/aws/aws-sdk-cpp
  GIT_TAG        1.9.67
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
add_executable(kvsProducerSampleCloudwatch
            ${CANARY_SOURCE_FILES})



target_link_libraries(kvsProducerSampleCloudwatch ${GST_APP_LIBRARIES} KinesisVideoProducer
                        aws-cpp-sdk-core
                        aws-cpp-sdk-monitoring
                        aws-cpp-sdk-logs)

file(COPY "${producercpp_SOURCE_DIR}/kvs_log_configuration" DESTINATION ${CMAKE_CURRENT_SOURCE_DIR})

# if (NOT OPEN_SRC_INSTALL_PREFIX)
#   message("PrintMessage: Not  ${OPEN_SRC_INSTALL_PREFIX}" )
#   set(OPEN_SRC_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/open-source)
#   set(ENV{PKG_CONFIG_PATH}
#         "$ENV{PKG_CONFIG_PATH}:${OPEN_SRC_INSTALL_PREFIX}/lib/pkgconfig")
#   set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} ${OPEN_SRC_INSTALL_PREFIX})
# endif()


# if(NOT EXISTS ${OPEN_SRC_INSTALL_PREFIX})
#   message("PrintMessage: Not Exists   ${OPEN_SRC_INSTALL_PREFIX}")
#   file(MAKE_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX})
# endif()
# if(NOT EXISTS ${DEPENDENCY_DOWNLOAD_PATH})
#   file(MAKE_DIRECTORY ${DEPENDENCY_DOWNLOAD_PATH})
# endif()


# # add_subdirectory(${DEPENDENCY_DOWNLOAD_PATH}/libcppproducer/cppproducer-src EXCLUDE_FROM_ALL)


# message(STATUS "Begin building dependencies.")

# message("PrintMessage: About to build deps  ${OPEN_SRC_INSTALL_PREFIX}")
# message("PrintMessage: ${CMAKE_INSTALL_LIBDIR}")

# build_dependency(kvsProducerCPP)
# # build_dependency(cppsdk)

# message("PrintMessage: Finished building deps")

# message(STATUS "Kinesis Video Producer path is ${KINESIS_VIDEO_PRODUCER_CPP_SRC}")


# message(STATUS "Finished building dependencies.")

# ############# find dependent libraries ############

# find_package(Threads)
# find_package(PkgConfig REQUIRED)
# # find_package(AWSSDK REQUIRED COMPONENTS monitoring logs)

# # if (WIN32)
# #   find_library(LIBKVSPRODUCERCPP libKinesisVideoProducer REQUIRED)
# # else()
# #   pkg_check_modules(LIBKVSPRODUCERCPP REQUIRED libKinesisVideoProducer)
# # endif()


# set(LIBKVSPIC_INCLUDE_DIRS ${OPEN_SRC_INSTALL_PREFIX}/include)
# set(LIBKVSPIC_LIBRARY_DIRS ${OPEN_SRC_INSTALL_PREFIX}/lib)
# set(OPEN_SRC_INCLUDE_DIRS ${OPEN_SRC_INSTALL_PREFIX}/include)
# set(OPEN_SRC_LIBRARY_DIRS ${OPEN_SRC_INSTALL_PREFIX}/lib)

# link_directories(${LIBKVSPIC_LIBRARY_DIRS})

# include_directories(${OPEN_SRC_INCLUDE_DIRS})
# # include_directories(${KinesisVideoProducerCPP_SOURCE_DIR}/src)
# link_directories(${OPEN_SRC_LIBRARY_DIRS})
# message("the cppp directory: ${KinesisVideoProducerCPP_SOURCE_DIR}")

# add_library(KinesisVideoProducer ${LINKAGE} ${PRODUCER_CPP_SOURCE_FILES})
# target_link_libraries(
#   KinesisVideoProducer
#   PUBLIC cppproducer)


# set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
# add_executable(kvsProducerSampleCloudwatch
#             ${CMAKE_CURRENT_SOURCE_DIR}/src/CanaryCallbackProvider.cpp
#             ${CMAKE_CURRENT_SOURCE_DIR}/src/CanaryLogsUtils.cpp
#             ${CMAKE_CURRENT_SOURCE_DIR}/src/kvs_gstreamer_sample.cpp)

# # target_link_libraries(kvsProducerSampleCloudwatch cppproducer ${AWSSDK_LINK_LIBRARIES})
# target_link_libraries(kvsProducerSampleCloudwatch cppproducer)