---
title: CLI
summary: The command-line runner — `wasm <file>` with `--preopen`, `--invoke`, `--args`, `--list-exports`.
weight: 40
---

The `cli/` sub-project builds a small wrapper around the interpreter and the WASI shim. Use it when you want to run a `.wasm` module from a shell — for quick experiments, for CI smoke tests against rustc-built binaries, or as a starting point for a longer-lived launcher.

## First run

The committed `examples/hello.wasm` is a hand-written WAT fixture that calls `env.putchar` to print `Hello, world!`. From a checkout of this repo:

```bash
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

For a real rustc-built WASI binary, you'll need to give it a host directory to read from via `--preopen`:

```bash
mkdir -p /tmp/sandbox
echo "Hello from the host" > /tmp/sandbox/hello.txt

sbt 'cliJVM/run --preopen /tmp/sandbox:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm'
# Hello from the host
```

## Help output

```text
$ wasm --help
wasm 0.1.1
Usage: wasm [options] <file> [<wasi-args>...]

  <file>                                       path to a .wasm module
  <wasi-args>...                               arguments passed to a WASI program's `_start` as argv[1..]; use `--` to separate from CLI flags
  -i, --invoke <export>                        name of the export to invoke (default: _start if exported, else main)
  -a, --args n1,n2,...                         comma-separated decimal i32 arguments to the export
  --list-exports                               print exported function names and exit (no invocation)
  -p, --preopen <host-path>:<virtual-name>     mount a host directory as a wasi preopen (repeatable)
  -e, --env <key>=<value>                      environment variable for the WASI program (repeatable)
  --help                                       print this help message
  --version                                    print version and exit
```

The CLI cross-builds on JVM, Scala.js (Node), and Scala Native — all three are wired up in `build.sbt`.

- [Flags](/cli/flags/) — every option, what it does, when to reach for it.
- [Recipes](/cli/recipes/) — hand-written WAT, rustc-built WASI binaries, native binaries.
