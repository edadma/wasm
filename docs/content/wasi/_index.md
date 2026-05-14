---
title: WASI
summary: The WASI Preview 1 host shim — 24 syscalls, three preopen flavours, no thrown exceptions.
weight: 30
---

The `wasi/` sub-project (`io.github.edadma.wasm.wasi`) implements `wasi_snapshot_preview1`, the standard host-call surface that rustc, zig, and Clang's WASI sysroot all target when you compile to `wasm32-wasip1`. Everything runs as a `HostModule` exposed to `Runtime.instantiate`, so it composes with your own host imports cleanly.

`Wasi.preview1(ctx)` returns a `HostModule` named `"wasi_snapshot_preview1"`. `Wasi.run(inst, "_start")` invokes the WASI command-mode entry point and translates `proc_exit(N)` into `Right(N)` — see [Quickstart](/getting-started/quickstart/) for the calling shape.

- [Syscalls](/wasi/syscalls/) — the 24 syscalls in five groups.
- [Preopens](/wasi/preopens/) — `WasiContext.Preopen`, `HostPreopen.fromDir`, and the sandboxing model.
