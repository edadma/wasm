---
title: WASI
summary: The WASI Preview 1 host shim — 29 syscalls, three preopen flavours, no thrown exceptions.
weight: 30
---

## What is WASI?

**WASI** is the **WebAssembly System Interface** — a portable, capability-based replacement for the POSIX surface, designed so that a WebAssembly program compiled once can run on any conforming host (browser, server, CLI, embedded). The guest module imports a fixed set of host functions (`fd_read`, `fd_write`, `path_open`, `clock_time_get`, `random_get`, `proc_exit`, …) under a well-known module name; the host implements them however it likes. There is no implicit ambient authority — a WASI program can only touch the directories the host explicitly hands it as **preopens** at startup.

**Preview 1** (`wasi_snapshot_preview1`) is the legacy-but-ubiquitous ABI: every shipping rustc, every zig `wasm32-wasi` target, every Clang WASI sysroot binary imports this name today. Preview 2 (`wasi:cli@0.2.x`) is the newer, component-model-shaped successor; that ecosystem is still settling, and Preview 1 will remain the lingua franca for the foreseeable future. This project implements Preview 1.

## What's here

The `wasi/` sub-project (`io.github.edadma.wasm.wasi`) implements `wasi_snapshot_preview1` as a host module that plugs into this interpreter. Everything runs as a `HostModule` exposed to `Runtime.instantiate`, so it composes with your own host imports cleanly.

`Wasi.preview1(ctx)` returns a `HostModule` named `"wasi_snapshot_preview1"`. `Wasi.run(inst, "_start")` invokes the WASI command-mode entry point and translates `proc_exit(N)` into `Right(N)` — see [Quickstart](/getting-started/quickstart/) for the calling shape.

- [WasiContext](/wasi/context/) — args, envs, stdio sinks, clock, random source, preopens; `default` and `collecting` factories.
- [Preopens](/wasi/preopens/) — `WasiContext.Preopen`, `HostPreopen.fromDir`, and the sandboxing model.
- [Syscalls](/wasi/syscalls/) — the 29 syscalls grouped by purpose (process, clock + entropy, stdio + preopens, file I/O, filesystem, polling, sockets).
