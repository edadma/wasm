# C example — freestanding `hello.wasm`

A WASI binary built from C **without** wasi-sdk. The program declares `fd_write` directly as an import from `wasi_snapshot_preview1` and defines `_start` as its entry point; no libc, no sysroot. The point isn't to show off — it's to demystify what a WASI binary actually is.

## Build

```bash
cd examples/c
make
```

This requires `clang` from a recent LLVM (the default `Makefile` points at `/opt/homebrew/opt/llvm/bin/clang` from Homebrew, which ships `wasm-ld` alongside clang). Apple's bundled clang doesn't include `wasm-ld`, so the system `clang` at `/usr/bin/clang` won't work — install Homebrew LLVM or set `CLANG=` to another LLVM that does.

If your LLVM lives elsewhere:

```bash
make CLANG=/path/to/your/clang
```

## Run

```bash
sbt 'cliJVM/run examples/c/hello.wasm'
# Hello from freestanding C!
```

No preopens needed — the program only touches fd 1 (stdout), which the CLI routes to `System.out`.

## What this demonstrates

- `__attribute__((import_module(...), import_name(...)))` declares a wasi import from C without any wrapper library.
- `_start` is the WASI command-mode entry point. `Wasi.run` invokes it; a clean return is exit code 0.
- A wasi binary is just wasm: imports + functions + linear memory + an exported entry. Everything else (libc, the rust stdlib, ...) is a convenience layer on top.
- The build uses `clang --target=wasm32 -nostdlib`, which works without a wasi-sysroot. The `-Wl,--allow-undefined` flag tells `wasm-ld` not to fail on the unresolved `fd_write` reference — the runtime resolves it via the wasi shim.

For a realistic C program (one that uses `<stdio.h>`, `<string.h>`, malloc, etc.), you want wasi-sdk and `clang --target=wasm32-wasi --sysroot=...`. Install instructions: <https://github.com/WebAssembly/wasi-sdk>.
