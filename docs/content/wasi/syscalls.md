---
title: Syscalls
summary: The 29 wasi_snapshot_preview1 host functions implemented, grouped by purpose.
weight: 10
---

Twenty-nine host functions are exposed under the module name `wasi_snapshot_preview1`. That covers everything `wasm32-wasip1` programs typically reach for: stdin/stdout, command-line args, environment variables, the clock, randomness, the filesystem (read, write, create, unlink, stat, readdir, rename, hard- and soft-link), poll-style readiness waits, and the wasi sockets surface (host-provided listening fds, accept / recv / send / shutdown).

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
| `fd_read(fd, iovs, iovs_len, *n)`            | Reads into iovecs, advancing the file position. `fd 0` (stdin) dispatches through `WasiContext.stdin` — the default returns 0 bytes (EOF). `fd 1` / `fd 2` / preopen dirs / unopened fds all reject with `EBADF`. |
| `fd_seek(fd, offset, whence, *new_pos)`      | `SEEK_SET=0`, `SEEK_CUR=1`, `SEEK_END=2`. |
| `fd_close(fd)`                               | Releases the slot in the fd table. |
| `fd_filestat_get(fd, *stat)`                 | Fills the wasi-preview1 `filestat` struct (filetype, inode placeholder, size, atim/mtim/ctim placeholders). |
| `fd_fdstat_get(fd, *stat)`                   | Filetype + fd-flags + rights. |
| `fd_fdstat_set_flags(fd, fdflags)`           | Adjusts `APPEND`, `NONBLOCK`, `SYNC`, etc. on the fd. |
| `fd_sync(fd)`                                | Flushes any buffered writes to the host filesystem (no-op for in-memory preopens). |
| `fd_datasync(fd)`                            | Like `fd_sync` but data-only, for callers that don't care about metadata. |
| `fd_advise(fd, offset, len, advice)`         | Advisory POSIX `posix_fadvise`. Valid advice values 0..5 (NORMAL / SEQUENTIAL / RANDOM / WILLNEED / DONTNEED / NOREUSE) — the shim honours none of them but returns ESUCCESS so callers can ship the hint without branching. EINVAL on out-of-range advice. |
| `fd_allocate(fd, offset, len)`               | POSIX `posix_fallocate` — ensure `[offset, offset+len)` is usable for writes. If the range extends past EOF, the file is grown with zero bytes in the gap. EBADF on stdio / preopen / unknown fd; EINVAL on negative arguments or arithmetic overflow. |

## Filesystem

| Syscall | Behaviour |
|---|---|
| `path_filestat_get(dirfd, …, path_ptr, path_len, *stat)` | Stats `path` relative to the preopen at `dirfd` without opening it. |
| `path_unlink_file(dirfd, path_ptr, path_len)`            | Removes a file or symlink. Already-open handles keep their cell reference (POSIX unlink-while-open). `EISDIR` for directories — use `path_remove_directory` for those (not implemented). |
| `path_create_directory(dirfd, path_ptr, path_len)`       | Creates a directory entry. Returns `EEXIST` if anything is there already. |
| `path_rename(fd, old_path, new_fd, new_path)`            | Moves an entry within a preopen. Cross-preopen renames return `ENOTCAPABLE`. `ENOENT` if source is missing, `EEXIST` if destination is taken. |
| `path_link(old_fd, …, old_path, new_fd, new_path)`       | Hard link. Both paths refer to the same entry; writes through either are visible to both. Cross-preopen returns `ENOTCAPABLE`; hardlinking directories returns `EPERM`. |
| `path_symlink(old_path, fd, new_path)`                   | Creates a symlink at `new_path` whose stored target is the opaque string `old_path`. The shim does not follow symlinks during path resolution; they're visible via `path_readlink` and surface as filetype `SYMBOLIC_LINK` (7). |
| `path_readlink(fd, path, buf, buf_len, *bufused)`        | Reads a symlink's target into `buf`, truncating to `buf_len` bytes. `bufused` reports the actual byte count. `EINVAL` on non-symlinks. |
| `fd_readdir(fd, buf, buf_len, cookie, *bytes_written)`   | Enumerates directory entries. Resumable via the `cookie` for buffers smaller than the listing. |

## Polling

| Syscall | Behaviour |
|---|---|
| `poll_oneoff(in, out, nsubs, *nevents)` | Wait for one of `nsubs` subscriptions to become ready. Subscriptions are 48-byte records (decoded fields `userdata`, `eventtype`, `clockid`/`fd`, `timeout`, `precision`, `flags`); events are 32-byte records (`userdata`, `error`, `eventtype`, `nbytes`). FD-read/FD-write subs on any valid fd report `ready` immediately (the InMemoryFs never blocks). CLOCK subs with `timeout = 0` or an ABSTIME target already in the past fire immediately; otherwise the host actually sleeps until the earliest deadline (`Thread.sleep` on JVM/Native, busy-spin fallback on Scala.js). EINVAL for invalid clock ids or unknown event types (per-event), EBADF for unknown fds (per-event); EFAULT only for bounds-violating pointer args. |

## Sockets

WASI Preview 1 sockets follow the BSD-inetd model: there is no `sock_open` / `sock_bind` / `sock_listen`. The host pre-binds listening sockets and hands them to the wasi program through `WasiContext.sockets`. The i-th listening socket is exposed at fd `3 + preopens.length + i`; `sock_accept` resolves a listening fd and returns a fresh accepted-socket fd from the per-instance fd table. Accepted sockets also work through `fd_read` / `fd_write` (they implement `FsFile` under the hood).

JVM / Scala Native: `HostServerSocket.bind(port)` factory wraps `java.net.ServerSocket` (`port = 0` for an OS-chosen ephemeral). Scala.js: synchronous accept can't be expressed on the event loop, so the factory throws — a Node-backed program needs to supply its own `WasiContext.ServerSocket` implementation.

| Syscall | Behaviour |
|---|---|
| `sock_accept(fd, fdflags, *out_fd)`                                              | Block until a client connects to the listening fd; install the connection in the fd table and write its new fd to `*out_fd`. EBADF if the fd isn't a listening socket. |
| `sock_recv(fd, ri_data, ri_data_len, ri_flags, *ro_datalen, *ro_flags)`           | Walk the iovec table reading from an accepted socket. `ri_flags` accepts `RECV_PEEK (1)` and `RECV_WAITALL (2)` (the shim ignores both today). Returns ENOTSOCK on non-socket fds, EINVAL on unknown ri_flags bits, EFAULT on bounds-violating pointers. |
| `sock_send(fd, si_data, si_data_len, si_flags, *so_datalen)`                      | Walk the iovec table writing to an accepted socket. `si_flags` is reserved in Preview 1 — non-zero values return EINVAL. |
| `sock_shutdown(fd, how)`                                                          | Half-close the read side (`SD_RD = 1`), write side (`SD_WR = 2`), or both (`SD_BOTH = 3`). EINVAL on any other `how` value. Idempotent: repeated shutdowns of the same side are not an error. |

Programs that issue an unimplemented syscall get back `Wasi.ENOTSUP` (52), which is the spec-conformant "host doesn't support this".
