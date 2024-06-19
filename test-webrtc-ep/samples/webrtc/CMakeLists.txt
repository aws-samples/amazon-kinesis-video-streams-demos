cmake_minimum_required(VERSION 3.12)
project(kvswebrtcmaster VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)


message(STATUS "SAMPLES_DIR - ${SAMPLES_DIR}")
message(STATUS "AWS_DEPENDENCIES_DIR - ${AWS_DEPENDENCIES_DIR}")
message(STATUS "BOARD_LIBS_DIR - ${BOARD_LIBS_DIR}")
message(STATUS "BOARD_LIBS_SHARED - ${BOARD_LIBS_SHARED}")
message(STATUS "BOARD_LIBS_STATIC - ${BOARD_LIBS_STATIC}")
message(STATUS "EMBEDDED_MEDIA_INCLUDES_DIR - ${EMBEDDED_MEDIA_INCLUDES_DIR}")
message(STATUS "EMBEDDED_MEDIA_LINK_DIR - ${EMBEDDED_MEDIA_LINK_DIR}")
message(STATUS "USE_MUCLIBC - ${USE_MUCLIBC}")
message(STATUS "BOARD_DESTINATION_PLATFORM - ${BOARD_DESTINATION_PLATFORM}")

set(WEBRTC_CMAKE_C_FLAGS "-fPIC -std=gnu99")

include(ExternalProject)
ExternalProject_Add(kvs-webrtc
        GIT_REPOSITORY    https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c.git
        GIT_TAG           master
        CMAKE_ARGS        -DADD_MUCLIBC=${USE_MUCLIBC}
        -DUSE_OPENSSL=OFF -DUSE_MBEDTLS=ON -DBUILD_STATIC_LIBS=ON -DBUILD_SAMPLE=OFF -DBUILD_LIBSRTP_DESTINATION_PLATFORM=${BOARD_DESTINATION_PLATFORM} -DCMAKE_INSTALL_PREFIX=${AWS_DEPENDENCIES_DIR}/webrtc/
        -DCMAKE_BUILD_TYPE=Release -DOPEN_SRC_INSTALL_PREFIX=${AWS_DEPENDENCIES_DIR}/webrtc/
        -DCMAKE_C_FLAGS=${WEBRTC_CMAKE_C_FLAGS}
        BUILD_ALWAYS      TRUE
        GIT_PROGRESS      TRUE
        TEST_COMMAND      ""
)

set(WEBRTC_SAMPLE_SRCS
        source/kvsWebRTCClientMaster.cpp
        source/Common.c)

set(WEBRTC_SDK_LIBS_SHARED
        kvsWebrtcClient
        kvsWebrtcSignalingClient
        kvsCommonLws
        kvspicUtils
        kvspicState
        websockets
        srtp2
        usrsctp
        mbedtls
        mbedx509
        mbedcrypto
        pthread
        m)

set(WEBRTC_SDK_LIBS_STATIC
        libkvsWebrtcClient.a
        libkvsWebrtcSignalingClient.a
        libkvsCommonLws.a
        libkvspicUtils.a
        libkvspicState.a
        libwebsockets.a
        libsrtp2.a
        libusrsctp.a
        libmbedtls.a
        libmbedx509.a
        libmbedcrypto.a
        pthread
        m)

add_executable(kvswebrtcmaster-shared ${WEBRTC_SAMPLE_SRCS})
add_dependencies(kvswebrtcmaster-shared kvs-webrtc)
target_include_directories(kvswebrtcmaster-shared PRIVATE ${AWS_DEPENDENCIES_DIR}/webrtc/include/ ${EMBEDDED_MEDIA_INCLUDES_DIR})
target_link_directories(kvswebrtcmaster-shared PRIVATE ${AWS_DEPENDENCIES_DIR}/webrtc/lib/ ${EMBEDDED_MEDIA_LINK_DIR})
target_link_libraries(kvswebrtcmaster-shared PRIVATE embedded-media-shared ${WEBRTC_SDK_LIBS_SHARED} ${BOARD_LIBS_SHARED})

add_executable(KvsWebrtcMaster ${WEBRTC_SAMPLE_SRCS})
add_dependencies(KvsWebrtcMaster kvs-webrtc)
target_include_directories(KvsWebrtcMaster PRIVATE ${AWS_DEPENDENCIES_DIR}/webrtc/include/ ${EMBEDDED_MEDIA_INCLUDES_DIR})
target_link_directories(KvsWebrtcMaster PRIVATE ${AWS_DEPENDENCIES_DIR}/webrtc/lib/ ${EMBEDDED_MEDIA_LINK_DIR})
target_link_libraries(KvsWebrtcMaster PRIVATE ${WEBRTC_SDK_LIBS_STATIC} ${BOARD_LIBS_STATIC})

include(GNUInstallDirs)

install(TARGETS kvswebrtcmaster-shared
        RUNTIME DESTINATION ${CMAKE_INSTALL_FULL_BINDIR})

install(TARGETS KvsWebrtcMaster
        RUNTIME DESTINATION ${CMAKE_INSTALL_FULL_BINDIR})