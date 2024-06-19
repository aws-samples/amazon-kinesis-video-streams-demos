cmake_minimum_required(VERSION 3.12)
project(embedded-media VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(USE_MUCLIBC "Use muclibc" OFF)

option(BUILD_WEBRTC_SAMPLES "Build webrtc samples" ON)

include(GNUInstallDirs)

set(SAMPLES_DIR ${CMAKE_CURRENT_LIST_DIR}/samples)
set(AWS_DEPENDENCIES_DIR ${CMAKE_BINARY_DIR}/aws-dependencies/)

if(BUILD_WEBRTC_SAMPLES)
    add_subdirectory(${SAMPLES_DIR}/webrtc)
endif()