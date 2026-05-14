// Phase 7.F smoke-test source: write a single file under a wasi preopen
// and exit 0. The shim's `Preopen.inMemory("/sandbox")` keeps the file
// in a mutable in-memory map; the integration test inspects the post-
// state via `bytesOf("out.txt")` to confirm the bytes landed verbatim.
//
// `std::fs::write` is sugar for `OpenOptions::new().write(true)
// .create(true).truncate(true).open(p)` + `write_all` + drop — so this
// exercises path_open with OFLAGS_CREAT | OFLAGS_TRUNC, fd_write to a
// non-stdio fd, and fd_close on a real (non-preopen) descriptor.
fn main() {
    let path     = "/sandbox/out.txt";
    let contents = "Hello from rustc-wasm32-wasip1 file write!\n";
    std::fs::write(path, contents)
        .expect("failed to write /sandbox/out.txt");
}
