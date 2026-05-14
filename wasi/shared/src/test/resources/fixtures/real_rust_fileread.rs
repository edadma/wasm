fn main() {
    let s = std::fs::read_to_string("/sandbox/hello.txt")
        .expect("failed to read /sandbox/hello.txt");
    print!("{}", s);
}
