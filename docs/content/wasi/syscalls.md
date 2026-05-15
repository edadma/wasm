---
title: Syscalls
summary: The 24 wasi_snapshot_preview1 host functions implemented, grouped by purpose.
weight: 10
---

Twenty-four host functions are exposed under the module name `wasi_snapshot_preview1`. That's enough to run rustc-built `wasm32-wasip1` binaries that exercise stdin/stdout, command-line args, environment variables, the clock, randomness, and the filesystem (read, write, create, unlink, stat, readdir).

**Return shape:** every wasi-preview1 syscall returns a single `i32` errno (`0` for success; nonzero values from the wasi-preview1 errno list — `Wasi.ENOENT`, `Wasi.EBADF`, `Wasi.EFAULT`, …). Output data is delivered through pointers passed by the guest into its own linear memory; the host writes the bytes there, the guest reads them back. The "Seq(I32(errno))" shape you'd see from `inst.invoke` reflects that single-result calling convention — the data isn't *in* that Seq, it's in memory.

## Process

| Syscall              | Behaviour |
|----------------------|-----------|
| `proc_exit(code)`    | Signals the host to terminate with `code`. `Wasi.run` catches it and returns `Right(code)` instead of `Left(...)`. |
| `args_get(argv, buf)`     | Writes `WasiContext.args` into guest memory, NUL-terminated. |
| `args_sizes_get(*c, *sz)` | Returns argc and total byte size. |
| `environ_get(envp, buf)`         | Same shape as `args_get`, but `KEY=VALUE` pairs from `WasiContext.envs`. |
| `environ_sizes_get(*c, *sz)`     | Returns environ count and total byte size. |

## Clock + entropy

| Syscall                              | Behaviour |
|--------------------------------------|-----------|
| `clock_time_get(id, prec, *out)`     | `id=0` → realtime nanos, `id=1` → monotonic nanos. Backed by `WasiContext.clock` (override the trait for deterministic tests). Other clock ids return `ENOTSUP`. |
| `random_get(buf, len)`               | Writes `len` bytes of randomness to `buf`. Backed by `WasiContext.random: Int => Array[Byte]` — `SecureRandom` by default, injectable for tests. |

## Stdio + preopens

| Syscall                            | Behaviour |
|------------------------------------|-----------|
| `fd_write(fd, iovs, iovs_len, *n)` | Writes one or more iovecs. fd 1 routes to `WasiContext.stdout` (`System.out` by default), fd 2 to `WasiContext.stderr`, fd 3+ to a preopen-backed file handle. |
| `fd_prestat_get(fd, *out)`         | wasi-libc's startup walk asks for each preopen at fd 3, 4, … until the host returns `EBADF`. The host fills in the directory-name length here. |
| `fd_prestat_dir_name(fd, buf, len)`| Writes the preopen name (as UTF-8 bytes) into guest memory. |

## File I/O

| Syscall | Behaviour |
|---|---|
| `path_open(dirfd, …, path_ptr, path_len, oflags, …, *out_fd)`  | Resolves `path` against the preopen at `dirfd`, opens it according to `oflags` (`CREAT` / `EXCL` / `TRUNC` / `DIRECTORY`), and returns a new fd. Sandboxed against `..`-escape and absolute paths. |
| `fd_read(fd, iovs, iovs_len, *n)`            | Reads into iovecs, advancing the file position. |
| `fd_seek(fd, offset, whence, *new_pos)`      | `SEEK_SET=0`, `SEEK_CUR=1`, `SEEK_END=2`. |
| `fd_close(fd)`                               | Releases the slot in the fd table. |
| `fd_filestat_get(fd, *stat)`                 | Fills the wasi-preview1 `filestat` struct (filetype, inode placeholder, size, atim/mtim/ctim placeholders). |
| `fd_fdstat_get(fd, *stat)`                   | Filetype + fd-flags + rights. |
| `fd_fdstat_set_flags(fd, fdflags)`           | Adjusts `APPEND`, `NONBLOCK`, `SYNC`, etc. on the fd. |
| `fd_sync(fd)`                                | Flushes any buffered writes to the host filesystem (no-op for in-memory preopens). |
| `fd_datasync(fd)`                            | Like `fd_sync` but data-only, for callers that don't care about metadata. |

## Filesystem

| Syscall | Behaviour |
|---|---|
| `path_filestat_get(dirfd, …, path_ptr, path_len, *stat)` | Stats `path` relative to the preopen at `dirfd` without opening it. |
| `path_unlink_file(dirfd, path_ptr, path_len)`            | Removes a path. Already-open handles keep their cell reference (POSIX unlink-while-open). |
| `path_create_directory(dirfd, path_ptr, path_len)`       | Creates a directory entry. Returns `EEXIST` if anything is there already. |
| `fd_readdir(fd, buf, buf_len, cookie, *bytes_written)`   | Enumerates directory entries. Resumable via the `cookie` for buffers smaller than the listing. |

## What isn't here yet

| Syscall | Status | Why |
|---|---|---|
| `poll_oneoff`                    | not implemented | No subscription handling yet — anything that polls falls back to ENOTSUP. |
| `sock_*`                         | not implemented | wasi-preview1 sockets are a thin shim; the project is library-scoped, not server-scoped. |
| `path_link` / `path_symlink` / `path_readlink` | not implemented | Adds complexity without unblocking the rustc smoke tests. |
| `path_rename`                    | not implemented | Same. |
| `fd_advise` / `fd_allocate`      | not implemented | rustc binaries probe these but don't require them. |

Programs that issue an unimplemented syscall get back `Wasi.ENOTSUP` (52), which is the spec-conformant "host doesn't support this".
