// hexdump.c — a small `xxd`-style hex dump that demonstrates a libc-using
// wasi program. Unlike `examples/c/hello.c` (which imports fd_write
// directly), this binary links against wasi-libc and gets <stdio.h>,
// argv, and exit codes through normal C abstractions. The build needs
// wasi-sdk (clang + the wasi-libc sysroot); plain Homebrew clang is not
// enough.
//
// What the program does:
//   - Reads argv[1] as a path. The wasi shim resolves it against
//     whatever preopened directory(ies) the host passed in via
//     `--preopen <name>:<host-path>` (or `WasiContext.preopens`
//     when called from Scala code).
//   - Walks the file 16 bytes at a time, printing one line per chunk:
//       `<offset>: <16 hex bytes>  |<16 ASCII or .>|`
//   - Returns 0 on success, 1 on argv error, 2 on open failure, 3 on
//     read failure. Exit codes are visible to the host via proc_exit.

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <ctype.h>

#define BYTES_PER_LINE 16

static void dump_line(uint64_t offset, const uint8_t *buf, size_t n) {
    // Offset column: 8 hex digits is enough for any file under 4 GiB.
    // wasi-libc supports %lx for uint32_t / unsigned long; the cast to
    // unsigned long keeps the format-warning quiet on every wasi-sdk.
    printf("%08lx: ", (unsigned long)offset);

    // Hex column: each byte as two-digit lowercase hex, space-separated.
    // For short final lines we still pad to BYTES_PER_LINE so the ASCII
    // gutter lines up — same trick `xxd` uses.
    for (size_t i = 0; i < BYTES_PER_LINE; i++) {
        if (i < n) printf("%02x ", buf[i]);
        else       printf("   ");
    }

    // ASCII gutter: printable bytes verbatim, control bytes as `.`.
    // Bracketed so the boundary is unambiguous even for files of
    // entirely-printable ASCII (which would otherwise blur into the
    // hex column).
    printf(" |");
    for (size_t i = 0; i < n; i++) {
        int c = buf[i];
        putchar(isprint(c) ? c : '.');
    }
    printf("|\n");
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: hexdump <path>\n");
        return 1;
    }

    FILE *f = fopen(argv[1], "rb");
    if (!f) {
        // Don't use perror — wasi-libc maps wasi errno to a textual
        // form via strerror, but the output is less informative than
        // just naming the failed step plus the path the user gave us.
        fprintf(stderr, "hexdump: cannot open %s\n", argv[1]);
        return 2;
    }

    uint8_t  buf[BYTES_PER_LINE];
    uint64_t offset = 0;
    for (;;) {
        size_t n = fread(buf, 1, BYTES_PER_LINE, f);
        if (n == 0) {
            if (ferror(f)) {
                fprintf(stderr, "hexdump: read error on %s\n", argv[1]);
                fclose(f);
                return 3;
            }
            break;   // clean EOF
        }
        dump_line(offset, buf, n);
        offset += n;
    }

    fclose(f);
    return 0;
}
