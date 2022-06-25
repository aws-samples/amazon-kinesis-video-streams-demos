cmake_minimum_required(VERSION 2.8)

project(libkvsProducerCPP-download NONE)

include(ExternalProject)

ExternalProject_Add(libkvsProducerCPP-download
    GIT_REPOSITORY    https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp
    GIT_TAG           v3.3.1
    PREFIX            ${CMAKE_CURRENT_BINARY_DIR}/build
    CMAKE_ARGS        -DBUILD_GSTREAMER_PLUGIN=ON -DCMAKE_INSTALL_PREFIX=${OPEN_SRC_INSTALL_PREFIX} -DOPEN_SRC_INSTALL_PREFIX=${OPEN_SRC_INSTALL_PREFIX}
    BUILD_ALWAYS      TRUE
    TEST_COMMAND      ""
)
