# C example — libc-using `hexdump.wasm`

A small `xxd`-style hex dumper that demonstrates a C program built against [wasi-sdk](https://github.com/WebAssembly/wasi-sdk). It uses `<stdio.h>` (`fopen`, `fread`, `printf`, `fprintf`), `<ctype.h>`, `argc`/`argv`, and exit codes — i.e. a normal libc-using C program rather than the freestanding "imports declared by hand" style of `../c/hello.c`.

```
00000000: 48 65 6c 6c 6f 2c 20 57 41 53 49 21 0a 00 00 00  |Hello, WASI!....|
00000010: 4c 69 6e 65 20 32 0a                              |Line 2.|
```

## Build

Install [wasi-sdk](https://github.com/WebAssembly/wasi-sdk/releases) — download a release tarball and extract to `/opt/wasi-sdk`, or on macOS `brew install wasi-sdk`. Then:

```bash
cd examples/c-libc
make
# → hexdump.wasm
```

If your wasi-sdk lives elsewhere, override `WASI_SDK_PATH`:

```bash
make WASI_SDK_PATH=$HOME/wasi-sdk-25.0
```

## Run

The program reads its target from `argv[1]`, so the host needs to both pass an argv entry **and** mount a preopen the program can resolve the path against. The wasm-cli's `--preopen <name>:<host-path>` does the latter; a trailing positional after `--` becomes `argv[1]`:

```bash
sbt 'cliJVM/run --preopen /sandbox:./fixtures -- examples/c-libc/hexdump.wasm /sandbox/data.bin'
```

Path conventions follow wasi-libc: the preopen name plus the rest of the path. `/sandbox/data.bin` resolves to `./fixtures/data.bin` on the host.

## What this demonstrates

- A wasi-sdk binary links against **wasi-libc**: `<stdio.h>` works, malloc works, `argv` is real, exit codes propagate through `proc_exit`.
- `fopen(path, "rb")` rides on top of `path_open` + `fd_read` — wasi-libc handles the preopen lookup, capability bits, and fd table management for you.
- `printf` goes through `fd_write(1, ...)`. `fprintf(stderr, ...)` goes through `fd_write(2, ...)`. The host writes stdout to `System.out` and stderr to `System.err` by default; tests would capture them via `WasiContext.collecting`.
- A clean `return` from `main` calls `__wasilibc_exit_proc(code)`, which calls `proc_exit(code)`. `Wasi.run` translates that into the CLI's process exit code.

## Why not just use `examples/c/`?

The freestanding example proves that a wasi binary is just wasm with imports, but it's not how anyone actually writes wasi programs. Real wasi binaries — Rust's `wasm32-wasip1`, Zig's `wasi`, Go's `js/wasm` (similar story), and any C using wasi-sdk — go through a libc-equivalent. This example is the smallest possible "real C, real libc, real argv" target the project's runtime can run end-to-end.
