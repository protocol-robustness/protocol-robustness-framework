use sp1_build::build_program_with_args;

fn main() {
    // The guests share these local crates. Rebuild their ELF when shared
    // semantics change, even when the guest entrypoint itself is unchanged.
    println!("cargo:rerun-if-changed=../core/src");
    build_program_with_args("../sp1-program", Default::default());
    build_program_with_args("../realized-statement-sp1-program", Default::default());
    build_program_with_args("../realized-execution-sp1-program", Default::default());
    build_program_with_args("../source-bound-sp1-program", Default::default());
    build_program_with_args("../source-bound-v2-sp1-program", Default::default());
    build_program_with_args(
        "../source-bound-entitlement-sp1-program",
        Default::default(),
    );
    build_program_with_args(
        "../controlled-entitlement-source-sp1-program",
        Default::default(),
    );
    build_program_with_args("../observed-run-sp1-program", Default::default());
}
