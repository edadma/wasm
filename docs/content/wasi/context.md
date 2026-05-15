---
title: WasiContext
summary: The configuration passed to `Wasi.preview1` — args, envs, stdio sinks, clock, random source, preopens.
weight: 10
---

`WasiContext` is the configuration record `Wasi.preview1(ctx)` accepts. It's a plain case class with seven fields, all of which have sensible defaults:

```scala
final case class WasiContext(
    args:     Seq[String]                  = Seq.empty,                   // argv
    envs:     Seq[(String, String)]        = Seq.empty,                   // KEY=VALUE pairs
    stdout:   Int => Unit                  = WasiContext.defaultStdout,   // per-byte sink for fd 1
    stderr:   Int => Unit                  = WasiContext.defaultStderr,   // per-byte sink for fd 2
    clock:    WasiContext.Clock            = WasiContext.systemClock,     // realtime + monotonic
    random:   Int => Array[Byte]           = WasiContext.defaultRandom,   // random_get source
    preopens: Seq[WasiContext.Preopen]     = Seq.empty,                   // fd 3, 4, … flavours
)
```

Every wasi syscall that needs host state reads from this record — `args_get` walks `args`, `clock_time_get` calls `clock.realtimeNanos()`, `random_get` calls `random(n)`, and so on. Copy-and-modify (`ctx.copy(args = …)`) is the only way to construct one; there is no mutation.

## Factories

### `WasiContext.default`

```scala
val ctx = WasiContext.default
```

Equivalent to `WasiContext()` — every field at its default. stdout / stderr go to `System.out` / `System.err`, the clock walks `System.currentTimeMillis` + `System.nanoTime`, random uses `scala.util.Random`, no args, no envs, no preopens.

Use it when running a real binary outside tests. Pair it with `.copy(...)` to override individual fields:

```scala
val ctx = WasiContext.default.copy(
  args     = Seq("myprog", "input.txt"),
  preopens = Seq(HostPreopen.fromDir("/var/data", "/data")),
)
```

### `WasiContext.collecting(...)`

```scala
val collecting = WasiContext.collecting(
  args     = Seq("myprog", "--flag"),
  envs     = Seq("LANG" -> "C"),
)
val ctx = collecting.context
```

Same shape as `WasiContext.default` but redirects stdout and stderr into `ArrayBuffer[Byte]`s you can read back after the guest runs:

```scala
collecting.stdoutBytes   // Array[Byte]
collecting.stderrBytes   // Array[Byte]
collecting.stdoutString  // String (UTF-8 decoded)
collecting.stderrString  // String (UTF-8 decoded)
```

This is what the WASI test suite uses to assert against program output without touching the real stdout/stderr. The `clock` / `random` / `preopens` overrides flow through identically, so the same factory works for any test that wants deterministic clock or random reads as well.

`Collecting` is single-threaded — the interpreter is single-threaded, and the underlying buffers aren't synchronized. Don't share a `Collecting` across threads.

### Constructing from scratch

Both factories produce a `WasiContext`. If neither fits, build one directly:

```scala
val ctx = WasiContext(
  args     = Seq("prog"),
  envs     = Seq("PATH" -> "/usr/bin"),
  stdout   = b => myLogger.write(b),
  stderr   = b => myLogger.write(b),
  clock    = myClock,
  random   = myRandom,
  preopens = Seq(myPreopen),
)
```

## The `Clock` trait

```scala
trait Clock:
  def realtimeNanos():  Long       // wall clock, nanoseconds since Unix epoch
  def monotonicNanos(): Long       // arbitrary anchor, nanoseconds; only deltas matter
```

`clock_time_get(id, ...)` dispatches against this. Four wasi `clockid_t` values map onto these two methods:

| `clockid_t`              | Backing method        |
|--------------------------|-----------------------|
| `CLOCK_REALTIME`         | `realtimeNanos()`     |
| `CLOCK_MONOTONIC`        | `monotonicNanos()`    |
| `CLOCK_PROCESS_CPUTIME`  | `monotonicNanos()` (folded) |
| `CLOCK_THREAD_CPUTIME`   | `monotonicNanos()` (folded) |

`WasiContext.systemClock` is the default — `System.currentTimeMillis() * 1_000_000L` for realtime, `System.nanoTime()` for monotonic. Cross-platform: JVM, Scala.js (via `Date.now` / `performance.now`), and Scala Native all support both calls without conditional code.

For deterministic tests, plug in your own:

```scala
val frozenClock = new WasiContext.Clock:
  def realtimeNanos():  Long = 1_700_000_000_000_000_000L
  def monotonicNanos(): Long = 42L

val ctx = WasiContext.default.copy(clock = frozenClock)
```

## The `random` closure

```scala
random: Int => Array[Byte]
```

`random_get(buf, len)` calls `ctx.random(len)` and copies the result into linear memory at `buf`. The closure must return an array of *exactly* `len` bytes — shorter returns are not detected and would surface as silent zero-fill in the guest. The default implementation (`WasiContext.defaultRandom`) uses `scala.util.Random.nextBytes`; not cryptographic, but adequate for `getrandom`-style entropy in most cases.

Override for deterministic tests:

```scala
val ctx = WasiContext.default.copy(
  random = (n: Int) => Array.fill(n)(0x42.toByte),
)
```

Or for a real CSPRNG, wire `java.security.SecureRandom` (JVM/Native) or `crypto.randomBytes` (JS) in here.

## stdout / stderr sinks

`stdout` and `stderr` are per-byte callbacks. Every `fd_write(1, …)` byte goes through `stdout`; every `fd_write(2, …)` byte goes through `stderr`. They're called exactly once per byte the guest writes, in order — they're not aware of newlines or line buffering.

If you want line-buffered output, do the buffering in your callback:

```scala
val lineBuf = new StringBuilder
val stdout: Int => Unit = b =>
  if b == '\n' then
    println(lineBuf.toString)
    lineBuf.clear()
  else
    lineBuf += b.toChar

val ctx = WasiContext.default.copy(stdout = stdout)
```

## Where to go next

- [Preopens](/wasi/preopens/) — the `preopens` field in depth.
- [Syscalls](/wasi/syscalls/) — which fields each syscall reads.
- [Concepts → ModuleInstance](/concepts/module-instance/) — what `Wasi.preview1(ctx)` returns and how to drive it.
