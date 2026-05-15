// hello.c — a freestanding WASI binary. No libc, no wasi-sdk needed: this
// file imports `fd_write` directly from the `wasi_snapshot_preview1` module
// and defines `_start` as the entry point. The build is just
// `clang --target=wasm32 -nostdlib ... -fuse-ld=wasm-ld`, which Homebrew's
// LLVM ships out of the box.
//
// What `_start` does:
//   - Builds a single wasi-style iovec (pointer + length) describing the
//     statically-allocated greeting bytes.
//   - Calls `fd_write(1, &iov, 1, &nwritten)` — fd 1 is stdout, 1 iovec,
//     and the host writes the byte count into `nwritten`. We ignore both
//     the return value and `nwritten`; a real program would check them.
//   - Returns normally. Wasi.run translates a clean `_start` return into
//     exit code 0 at the wasm-cli boundary.

// === wasi_snapshot_preview1 import declarations =============================
//
// One iovec is `{ const uint8_t *buf; uint32_t len; }` — 8 bytes total.
// In linear memory the buffer pointer is a wasm i32 (we cast bytes to int).
struct iovec {
    const unsigned char* buf;
    unsigned int         buf_len;
};

// `fd_write(fd, iovs, iovs_len, *nwritten) -> errno`. The function is
// imported from `wasi_snapshot_preview1.fd_write` via the import attribute.
__attribute__((import_module("wasi_snapshot_preview1"),
               import_name("fd_write")))
int fd_write(int fd, const struct iovec* iovs, int iovs_len, int* nwritten);

// === program ================================================================

static const unsigned char greeting[] = "Hello from freestanding C!\n";

void _start(void) {
    struct iovec iov = { greeting, sizeof(greeting) - 1 };  // -1 to drop the NUL
    int nwritten = 0;
    // fd 1 is stdout. Errno return is ignored for this hello program; a
    // real one would check for non-zero and route to fd_write(2, ...) for
    // diagnostics.
    fd_write(1, &iov, 1, &nwritten);
}
