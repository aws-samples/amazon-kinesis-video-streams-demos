test: build
	# TODO: Add tests. Probably, run the canary for a short time

install-system-dependencies:
	@echo "Installing system dependencies"

build: install-system-dependencies
	@echo "Build webrtc canary"
	mkdir -p build && \
		cd build && \
		cmake .. -DCMAKE_INSTALL_PREFIX=$(shell pwd)/build -DUSE_LIBSRTP3=ON -DUSE_OPENSSL3=ON && \
		make -j
