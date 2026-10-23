# Controlled Entitlement Source Profile V1

This additive SP1 profile is for a controlled source such as the checked-in
Vector A conformance allocation. It requires the operation's authenticated
`prepared-operation.policy-root` to equal the independently reconstructed
`realizedStatementRoot` (the 32-byte SHA-256 digest, represented as lowercase
`0x` hex in the witness).

The profile reuses the existing entitlement derivation and nine-word,
288-byte public-values schema. Its distinct program identity is:

```text
source-bound-entitlement-controlled-sp1-program.v1
```

The host command accepts a raw witness and does not rewrite it. Run from the
`prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/` workspace directory:

```sh
cargo run --release --bin controlled-entitlement-source-prove -- \
  --input <prepared-controlled-witness.json> --execute
```

Use `--prove --fixture <path>` to generate and verify a Groth16 EVM proof. The
prepared operation must already commit to the realized statement root before
the witness is submitted. The separate VKey/program identity prevents this
stricter witness rule from changing the existing entitlement guest identity.

This profile proves consistency with the operation-committed controlled source.
It does not authenticate who is entitled to publish that source, and it is not
a production claims authority. Production source authorisation remains a
separate requirement.
