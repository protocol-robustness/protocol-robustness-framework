use sp1_build::build_program_with_args;

fn main() {
    build_program_with_args("../sp1-program", Default::default());
    build_program_with_args("../realized-statement-sp1-program", Default::default());
    build_program_with_args("../realized-execution-sp1-program", Default::default());
    build_program_with_args("../source-bound-sp1-program", Default::default());
}
