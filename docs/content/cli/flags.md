---
title: Flags
summary: Every CLI option, what it does, and the dispatch rules between them.
weight: 10
---

## `<file>` (positional)

Path to the `.wasm` binary to run. Required. The file is read as raw bytes; nothing about the path matters beyond that — there's no virtual-fs lookup, no env-var substitution.

## `<wasi-args>...` (trailing positional)

Anything positional after `<file>` is captured and passed to the WASI program's `_start` as `argv[1..]`. `argv[0]` is synthesised from the file basename (stripped of any `.wasm` suffix), matching the wasmtime / wasmer convention.

```bash
sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm /data/input.txt'
# argv inside word_count.wasm: ["word_count", "/data/input.txt"]
```

If any value starts with `-` it would otherwise be parsed as a CLI flag — use the standard POSIX `--` separator to stop option parsing:

```bash
sbt 'cliJVM/run examples/rust/word_count.wasm -- -v --quiet /data/input.txt'
# argv: ["word_count", "-v", "--quiet", "/data/input.txt"]
```

Non-WASI modules (`main` or `--invoke`-named exports) don't see these args — `argv` is a WASI concept; non-WASI exports take `i32` operands through [`--args`](#--a---args-n1n2) instead.

## `-i`, `--invoke <export>`

Name of the export to invoke. Overrides the default dispatch. Use this when the module exports neither `_start` nor `main`, or when you want to run a specific function instead of the default entry point:

```bash
sbt 'cliJVM/run --invoke fact -a 5 examples/fact.wasm'
# 120
```

## `-a`, `--args n1,n2,...`

Comma-separated decimal `i32` values to pass as arguments to the invoked export. Each `n` is parsed as `Int.parseInt(_)`, so values must fit in a signed 32-bit int. Negative numbers, hex, and floats are not accepted.

`--args` only applies when the dispatch is not `_start` (the WASI command-mode entry point takes no wasm-level arguments — its argv comes from the WASI context, populated by trailing positional args after `<file>`; see [`<wasi-args>...`](#wasi-args-trailing-positional) above). When dispatch is `main` or an `--invoke`-named export, the `--args` values are pushed onto the operand stack in order before the call.

## `--list-exports`

Prints every exported function name on its own line and exits with status 0. No instantiation traps run, no `_start` fires, no preopens are needed.

```bash
sbt 'cliJVM/run --list-exports interp/shared/src/test/resources/fixtures/fact.wasm'
# fact
# memory
```

## `-p`, `--preopen <host-path>:<virtual-name>`

Mounts a real host directory as a wasi preopen, backed by `HostPreopen.fromDir`. Repeatable; each `--preopen` adds one entry to the WASI context's preopen list in order.

The split is on the **last** colon in the spec, so Windows-style paths like `C:\data:/data` parse correctly (`C:\data` host → `/data` virtual). The virtual name should start with `/` — that's the convention wasi-libc expects, and it's what guest code matches `path_open` calls against.

```bash
sbt 'cliJVM/run -p /tmp/sandbox:/sandbox -p /var/log:/logs my-program.wasm'
```

If a `--preopen`'s host path doesn't exist or isn't a directory, the CLI exits with an error before instantiating the module.

## `--help` / `--version`

Standard. `--help` prints the synopsis above; `--version` prints `wasm 0.1.1` and exits.

## Dispatch rules

The CLI picks what to invoke based on what's exported:

1. If `--invoke` is given, that export is invoked. `_start` and WASI dispatch are bypassed.
2. Otherwise, if the module exports `_start`, it's treated as a WASI command-mode binary. The CLI runs it through `Wasi.run`, so `proc_exit(N)` becomes the process exit code, args/envs/preopens go through `WasiContext`, trailing positional args become `argv[1..]`, and `--args` is ignored.
3. Otherwise, if the module exports `main`, that's invoked. `--args` is passed to it as `i32` operands; trailing positional args are ignored.
4. Otherwise, the CLI exits with `ExportNotFound("_start")` (the default it tried first).

## Exit codes

- `0` — clean exit (WASI `_start` returned, or non-WASI export returned a single non-trapping result).
- `N` (1–255) — `proc_exit(N)` from a WASI binary.
- `1` — any non-`proc_exit` failure (parse error, validator error, instantiate error, trap during execution, missing export).

Non-WASI exports that return a single `i32` print the result to stdout as `result: <decimal>`; multi-result exports print `result: <decimal>, <decimal>, …`. Zero-result exports print nothing.
