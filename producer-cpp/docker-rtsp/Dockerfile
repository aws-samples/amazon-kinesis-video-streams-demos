FROM ubuntu:18.04

RUN apt-get upgrade && \
    apt-get update && \
    apt-get install -y  \
    byacc \
    cmake \
    curl \
    g++ \
    git \
    gstreamer1.0-plugins-base-apps \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-ugly \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev \
    m4 \
    maven \
    openjdk-8-jdk \
    python2.7 \
    pkg-config \
    vim \
    wget  \
    xz-utils && \
    rm -rf /var/lib/apt/lists/* && \
    cd /opt/

# ===== Setup Kinesis Video Streams Producer SDK (CPP) =======================================
WORKDIR /opt/
RUN git clone https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
WORKDIR /opt/amazon-kinesis-video-streams-producer-sdk-cpp/build/
RUN cmake .. -DBUILD_GSTREAMER_PLUGIN=ON &&\
    make

ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64/
ENV LD_LIBRARY_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/open-source/local/lib:$LD_LIBRARY_PATH
ENV GST_PLUGIN_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/build
