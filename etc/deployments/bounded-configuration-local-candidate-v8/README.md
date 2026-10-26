# Bounded configuration local candidate v8

Candidate v8 has signed initial inputs and a locally verified C0 deployment.
The genesis seal and empty registry seal are independently verified on Anvil;
the deployment intentionally stops before the separate C0-to-C1 transition.
See `APPROVAL_REVIEW_HANDOFF.md` for retained evidence, the exact successor
manifest, and the two signatures still required to resume.

## Reproduce the canonical package roots

Run this from the root of a PRF checkout with Java and the Clojure CLI
installed. It derives the protocol-genesis, authority-state,
local-authority-policy, and initial-configuration roots from the package
artifacts, checks them against the v8 recorded values, and prints the complete
JSON derivation result. A mismatch exits nonzero.

```sh
set -euo pipefail

candidate_dir="etc/deployments/bounded-configuration-local-candidate-v8"
result_file="$(mktemp)"

PRF_DEPLOY_CHAIN_ID=31337 \
  clojure -M:test -m scripts.deployment.genesis-roots \
  generic-candidate-package-set \
  "$candidate_dir/candidate-package-set.edn" > "$result_file"

jq -e '
  .schema == "prf-deployment-generic-candidate-package-set.v1" and
  .qualification == "derived-only-not-approved" and
  .roots == {
    "protocol-genesis": "sha256:668bc1d132b8745a3f6db7fa0b2dad2b85a11246e00c39e7b24d266af4724775",
    "authority-state": "sha256:062aac742a56de1f3d51e1debc4fd8670b1dda8fc1acb68c0bbcd10ae1b8337e",
    "local-authority-policy": "sha256:0e124c35e9707f03ddb7f0b2972c22715098a23b58804175df02acdd25ded55c",
    "initial-configuration": "sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95"
  }
' "$result_file" > /dev/null

cat "$result_file"
printf '\nFull result saved at: %s\n' "$result_file"
```

This derivation needs no RPC endpoint, wallet, private key, or network access;
it only reads the checked-out package and runs the local PRF derivation code.

The result is a deterministic derivation, not an approval. It does not prove
that the semantic inputs were approved, that a deployment used these roots,
or that a successor transition is authorized. Reproducing the signed v8
initial-input approval additionally requires the exact source parent, source
lock, trusted context, and detached signature recorded in
`APPROVAL_REVIEW_HANDOFF.md`. A later checkout does not inherit that approval.
