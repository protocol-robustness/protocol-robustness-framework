use sp1_build::build_program_with_args;

fn main() {
    println!("cargo:rerun-if-changed=../exact-verifier-sp1-program/src");
    build_program_with_args("../exact-verifier-sp1-program", Default::default());
}
