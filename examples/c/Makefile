# Build hello.wasm from hello.c using Homebrew LLVM's clang + wasm-ld.
# No wasi-sdk needed — the program declares `fd_write` directly as a wasi
# import rather than going through wasi-libc's wrappers.
#
#   make            # builds hello.wasm
#   make clean      # removes hello.wasm
#
# Override CLANG if your llvm install is elsewhere; the default is the
# Homebrew path on macOS arm64.

CLANG ?= /opt/homebrew/opt/llvm/bin/clang

CFLAGS = \
  --target=wasm32 \
  -O2 \
  -nostdlib \
  -Wl,--no-entry \
  -Wl,--export=_start \
  -Wl,--allow-undefined

hello.wasm: hello.c
	$(CLANG) $(CFLAGS) -o $@ $<

clean:
	rm -f hello.wasm

.PHONY: clean
