# Coprocessor source moved

The Rust witness kernel, SP1 guests/hosts, and proof scripts are owned by
[`protocol-robustness/prf-coprocessor`](https://github.com/protocol-robustness/prf-coprocessor/tree/e2e4e9b25622c52eb028fc373a66e81b3982e933).
This directory contains no implementation copy.

Prepare the immutable checkout explicitly with `make setup-coprocessor`; the
pin is recorded in `data/prf/external-repositories.json`. Runtime resolvers only
validate the prepared checkout and never perform Git or network operations.

The extracted repository's `PROVENANCE.md` records the original PRF source
commit, source tree, and golden vector provenance. Historical evidence may
retain references to the former `coprocessor/` source snapshot.
