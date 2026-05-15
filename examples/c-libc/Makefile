# Build hexdump.wasm from hexdump.c using the wasi-sdk clang toolchain.
#
# wasi-sdk ships a clang preconfigured with the wasm32-wasi target and a
# wasi-libc sysroot, so a normal libc-using C program builds with no extra
# flags. Without wasi-sdk you can still build the freestanding example in
# `../c/`, but anything that touches <stdio.h>, <string.h>, malloc, or
# similar needs this.
#
#   make            # builds hexdump.wasm
#   make clean      # removes hexdump.wasm
#
# Install wasi-sdk: https://github.com/WebAssembly/wasi-sdk/releases
# (either grab a tarball and extract to /opt/wasi-sdk, or
# `brew install wasi-sdk` on macOS).

WASI_SDK_PATH ?= /opt/wasi-sdk
CLANG          = $(WASI_SDK_PATH)/bin/clang

CFLAGS = \
  --target=wasm32-wasi \
  --sysroot=$(WASI_SDK_PATH)/share/wasi-sysroot \
  -O2

hexdump.wasm: hexdump.c
	$(CLANG) $(CFLAGS) -o $@ $<

clean:
	rm -f hexdump.wasm

.PHONY: clean
