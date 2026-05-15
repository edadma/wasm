---
title: Preopens
summary: The `WasiContext.Preopen` trait, its three factories, and the path-sandboxing model.
weight: 20
---

A WASI program can't open files by absolute path. wasi-libc walks the fds 3, 4, … at program startup, asks the host for the name of each preopened directory, and builds an internal `name → fd` map; everything later that calls `open("/sandbox/hello.txt", …)` is rewritten by libc into `path_open(dirfd=3, "hello.txt", …)`. The host never sees an absolute path from the guest.

`WasiContext.preopens: Seq[Preopen]` is the list the host advertises. The i-th entry shows up at fd `3 + i`. Three factories:

| Factory                                | Backing | What it does |
|----------------------------------------|---------|-------|
| `Preopen.named(name)`                  | none    | Advertises the name; every `path_open` against it returns `ENOTCAPABLE`. |
| `Preopen.inMemory(name, files)`        | `Map[String, Array[Byte]]` | Full read/write semantics — `CREAT`, `EXCL`, `TRUNC`, unlink, mkdir, readdir. What the test suite uses. |
| `HostPreopen.fromDir(hostPath, virtualName)` | real on-disk directory | Sandboxed access to an actual host directory. JVM and Scala Native use `java.nio.file` + `FileChannel`; Scala.js uses Node's `fs.*Sync`. |

Programs see the same wasi-preview1 surface no matter which kind is in play — only the host distinguishes them.

## Named preopens

```scala
val ctx = WasiContext.default.copy(
  preopens = Seq(Preopen.named("/etc"), Preopen.named("/var")),
)
```

`Preopen.named` is the minimum surface: it makes `fd_prestat_get(3)` succeed and report the name, and that's it. `path_open` against fd 3 returns `ENOTCAPABLE` (not `ENOENT` — the distinction matters: ENOENT says "no such path", ENOTCAPABLE says "you can't even ask through this preopen"). It's the right choice when you want to expose a directory's *existence* to libc startup probing without giving the guest the capability to read it.

## In-memory preopens

```scala
val files = Map(
  "hello.txt"        -> "Hello from memory\n".getBytes,
  "config/site.toml" -> "title = \"test\"\n".getBytes,
)

val sandbox = Preopen.inMemory("/sandbox", files)

val ctx = WasiContext.default.copy(preopens = Seq(sandbox))
```

The backing `Map[String, Array[Byte]]` is mutable behind the scenes — `path_open(..., OFLAGS_CREAT, ...)` adds new entries, `path_unlink_file` drops them, `OFLAGS_TRUNC` zeroes the bytes. After the guest runs, the concrete `InMemoryPreopen` returned by `Preopen.inMemory` exposes `bytesOf(path)` so tests can inspect what was written:

```scala
val sandbox = Preopen.inMemory("/sandbox")              // empty to start
// ... run the guest, which writes "output.bin" ...
sandbox.bytesOf("output.bin")  // → Some(Array[Byte](...))
```

In-memory preopens never touch the host filesystem and never block on I/O — they're the right choice for unit tests, fuzzing, and any scenario where guest writes shouldn't escape the process.

## Host-directory preopens

```scala
import io.github.edadma.wasm.wasi.HostPreopen

val ctx = WasiContext.default.copy(
  preopens = Seq(HostPreopen.fromDir("/var/data", "/data")),
)
```

`HostPreopen.fromDir(hostPath, virtualName)` resolves `hostPath` to a real `Path` and uses it as the root. Every guest path is resolved against it through the wasi-preview1 path-sandbox rules:

- Absolute paths from the guest are rejected outright (would-be roots like `/etc/passwd` never reach the host filesystem).
- NUL bytes in the guest-supplied path are rejected (defense against malformed strings).
- `..` segments that would escape the root after normalization are rejected with `ENOTCAPABLE`.
- Symlinks inside the directory are followed by the host filesystem; following one *out* of the root re-triggers the path-escape check on the resolved target.

The three backends (JVM, Native, JS) differ only in how they implement `path_open` and the read/write/stat/readdir syscalls under the hood. The trait surface is identical; the path-sandbox rules are enforced before any platform-specific code runs.

## Picking a preopen kind

- **`HostPreopen.fromDir`** — production use. The guest gets real persistent storage in a controlled subtree of the host filesystem.
- **`Preopen.inMemory`** — tests and any case where the guest's writes need to be inspectable from Scala without crossing the filesystem.
- **`Preopen.named`** — when a guest probes preopens for capability discovery and you want to expose the *name* but not the contents.

You can mix them freely. The order of `preopens` in `WasiContext` determines the fd ordering — preopen `[0]` is fd 3, preopen `[1]` is fd 4, and so on.

## The `Preopen` trait surface

If the three factories don't fit and you want to implement a custom preopen — say, an S3-backed read-only mount or an HTTP-fetching probe — you can subclass `WasiContext.Preopen` directly:

```scala
trait Preopen:
  /** wasi-visible directory name (what userspace sees through `fd_prestat_dir_name`). */
  def name: String

  /** Open a path relative to this preopen. Called from `path_open`.
    * Default returns Left(ENOTCAPABLE). */
  def open(path: String, oflags: Int, fdflags: Int): Either[Int, Wasi.FsFile]

  /** Stat a path WITHOUT opening it. Called from `path_filestat_get`.
    * Right(size) for an existing regular file; Left(errno) otherwise.
    * Default returns Left(ENOTCAPABLE). */
  def statPath(path: String): Either[Int, Long]
```

Three things to know:

- **`Wasi.FsFile` is the file-handle trait** — `read` / `write` / `seek` / `size` / `tell` / `close`. The shim never sees a Java `File` or a `Path`; it talks to your `FsFile`. The `InMemoryPreopen` and `HostBackedPreopen` impls are reference implementations you can read in the source.
- **Several methods are `private[wasi]`** (`unlinkPath`, `mkdir`, `readdir`, `filetypeOf`). External impls inherit their `ENOTCAPABLE` / empty defaults but can't override them — write/enumerate semantics for custom preopens are intentionally limited to what the trait surface above allows. If you genuinely need to override one of those, the customization belongs upstream as a new factory rather than as a downstream subclass.
- **`oflags` / `fdflags` are passed through verbatim.** The `OFLAGS_CREAT` (1), `OFLAGS_DIRECTORY` (2), `OFLAGS_EXCL` (4), `OFLAGS_TRUNC` (8) bits and the `FDFLAGS_*` set are documented in `wasi-preview1`. Read-only impls ignore them.

For the common case — read/write semantics over an in-memory map, or sandboxed access to a real directory — reach for `Preopen.inMemory` and `HostPreopen.fromDir` instead. They cover what most callers need.
