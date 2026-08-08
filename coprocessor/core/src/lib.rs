//! Independent Rust allocation kernel for the IEE-PRF coprocessor demo.
//!
//! Reproduces the PRF reference allocation computation byte-for-byte at the
//! declared public-output boundary (PRF result == native Rust result).

pub mod assertions;
pub mod canonical;
pub mod json_projection;
pub mod kernel;
pub mod lifecycle;
pub mod proportionality;
pub mod roots;
pub mod selection;
