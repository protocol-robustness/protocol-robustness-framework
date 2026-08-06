# Publisher policy

`publisher-policy.schema.json` is the JSON Schema for the publisher policy that
authorises the keys allowed to sign the test-artifacts publisher commitment
(acceptance stage 3).  `publisher-policy.example.json` is a template — the
`public_key_hex` shown is a placeholder, **not** a real key, and must be
replaced before use.

## How it is used

Stage 3 of acceptance (`scripts/validate/validate_artifact_registry.py` with
`--publisher-manifest` / `--publisher-policy`, or
`scripts/validate/verify_publisher_commitment.py`) reconstructs the commitment
from already-validated registry data, recomputes the policy hash from *this
file's* `schema_version` + `policy_id` + `trusted_keys`, and rejects any signer
that is not a listed, `active`, `ed25519` key bound to the envelope's
`key_id`/`publisher_id`.

A key bound in the policy but later revoked flips `status` to `revoked`; the
policy's hash changes, so any envelope that committed to the old key becomes a
`publisher-policy-mismatch`, and a new envelope signed under the revoked policy
is rejected as `publisher-not-authorised`.

Key provisioning follows `docs/security/PUBLISHER.md`:

- Generate the Ed25519 keypair offline (e.g. `openssl genpkey -algorithm ed25519`).
- Publish **only** the raw 32-byte public key hex (`public_key_hex`) here.
- Give the private key to the signing authority only (env/file, never argv).
- Do not reuse or derive this key from sentinel or release-attestation keys.

This example policy and its schema are documentation/ops scaffolding; the
executable gate is `scripts/evidence/sign_publisher_commitment.py` (signer) and
`scripts/validate/verify_publisher_commitment.py` (verifier), both tested in
`scripts/evidence/test_publisher_commitment.py`.