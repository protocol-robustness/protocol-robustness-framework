# Key Management

This directory stores public keys and the canonical mapping of researcher identities to those keys (`owners.json`).

## Trust Model
- **Git as Authority**: Repository history documents the addition/modification of keys via Pull Requests. 
- **No Global PKI**: Git PR review is the consensus gate for adding new researchers.
- **Identity**: This file maps a `signer_id` to a researcher. It **does not** prove real-world identity in itself; it only states that the repository maintains this mapping for attribution purposes.

## Maintaining Keys
1. Add new `.pub` file to `keys/`.
2. Update `keys/owners.json` with the new key's fingerprint.
3. Run `scripts/lint_keys.sh` to verify consistency.
