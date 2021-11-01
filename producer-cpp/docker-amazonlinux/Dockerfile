# Build docker with
# docker build -t kinesis-video-producer-sdk-cpp-amazon-linux .
#
FROM amazonlinux:2

RUN yum install -y \
	autoconf \
	automake  \
	bison \
	bzip2 \
	cmake3 \
	curl \
	diffutils \
	flex \
	gcc \
	gcc-c++ \
	git \
	gmp-devel \
	gstreamer1* \
	libcurl-devel \
	libffi \
	libffi-devel \
	libmpc-devel \
	libtool \
	make \
	m4 \
	mpfr-devel \
	pkgconfig \
	vim \
	wget \
	xz && \
    yum clean all

ENV KVS_SDK_VERSION v3.2.0

WORKDIR /opt/
RUN git clone --depth 1 --branch $KVS_SDK_VERSION https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git
WORKDIR /opt/amazon-kinesis-video-streams-producer-sdk-cpp/build/
RUN cmake3 .. -DBUILD_GSTREAMER_PLUGIN=ON -DCMAKE_VERBOSE_MAKEFILE:BOOL=ON && \
    make 

ENV LD_LIBRARY_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/open-source/local/lib
ENV GST_PLUGIN_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/build/:$GST_PLUGIN_PATH
