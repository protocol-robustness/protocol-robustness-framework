# Bounded configuration local candidate v9

This candidate changes the policy-v3 transition-validator identity to the
operator-supplied address `0xEa2dD67De25F8046B3BA4483a8DE2330D22e9413` and
uses policy key ID `bounded-local-transition-validator-v2`. An operator
reported a passing read-only keystore check, but its machine-local keystore
identifier and output are intentionally not retained in this shared candidate.
The check was not independently rerun in this workspace and does not attest to
external custody. Rerun it with the intended encrypted keystore before any
authorized deployment activity.

Run it from the PRF repository root, using the new encrypted keystore's actual
file path and UUID from `cast wallet list`. Cast prompts for the password in an
interactive terminal; do not put the password on the command line:

```sh
clojure -M:test -m scripts.deployment.transition-validator-key-check \
  etc/deployments/bounded-configuration-local-candidate-v9/local-authority-policy-package.edn \
  /path/to/new-transition-validator-keystore.json \
  <new-keystore-uuid>
```

Continue only if the report is `PASS` and `derivedAddress` equals the v9
policy-pinned address. A matching keystore UUID alone is not sufficient. No
operator report is retained in this candidate, so the address-binding check
must be rerun with the intended keystore; the approval and deployment gates
below remain open.

## Derived predeployment roots

Run from the PRF repository root:

```sh
PRF_DEPLOY_CHAIN_ID=31337 \
  clojure -M:test -m scripts.deployment.genesis-roots \
  generic-candidate-package-set \
  etc/deployments/bounded-configuration-local-candidate-v9/candidate-package-set.edn
```

The retained derivation is `candidate-derivation.json`. It is derivation-only,
not approval. The protocol-genesis and initial-configuration roots remain the
same as v8. The local-authority-policy and initial authority-state roots
change because the policy address and key ID changed:

On 2026-11-01, the repository derivation command was rerun against the retained
package set. Its full JSON output matched `candidate-derivation.json` and the
operator-supplied derivation result exactly. This confirms deterministic
reconstruction with the existing PRF implementation; it is not an independent
implementation check or an approval.

- Protocol genesis: `sha256:668bc1d132b8745a3f6db7fa0b2dad2b85a11246e00c39e7b24d266af4724775`
- Initial configuration: `sha256:131ff6b74047de747bd9ace93413b6b793d677749095f0dadf5dcc173e120f95`
- Local authority policy: `sha256:2d0028af8aadc60fac7734326d2ec6d187078a6f056cab24d6005f2f9ab474b6`
- Initial authority state: `sha256:3e89f54ec34ef87522c3e6cf60897304e0c1f0bbe30c81fdce467dd84d664e13`

An unsigned review manifest is retained at `review-manifest.v3.edn`; its root
is `sha256:9eb838179d04342b7f11ad1d0d4ed8ddc2322038929566cd1bd529376eed3dd1`.
It reuses the v8 source revision, tree digest and approval principal as
derivation-source metadata. A reviewer must independently confirm those
out-of-band source pins before approving this new manifest. It is not a signed
approval.

The manifest can be deterministically regenerated with the existing v3
manifest command. Its source revision, tree digest and principal are copied
from the v8 review context and must be independently confirmed before approval:

```sh
clojure -M:test -m scripts.deployment.configuration-review manifest-v3 \
  etc/deployments/bounded-configuration-local-candidate-v9/candidate-package-set.edn \
  e6fe09666adf8092b2023ea1ecdd84fbf24fa98a \
  066ee1dcd021ecf8e4d182f17a4ded02095cf3fb51e600ddbe96006f9d87ef09 \
  0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266
```

## Required before an execution commitment exists

The v8 request and commitment cannot be reused. The initial authority-state
root is part of the sealed chain-instance identity, and the successor
transition targets that exact instance. Therefore:

1. Verify the encrypted keystore derives to the declared v9 address.
2. Independently validate the source pins in the retained v9 review manifest,
   then obtain a new detached initial-input approval for its exact root. The v8
   approval does not cover v9.
3. Deploy a fresh local instance from v9 inputs, then collect and independently
   verify its addresses, runtime identities, receipts and block context.
4. Derive and seal the new chain-instance-genesis root from those observations.
5. Recompute the C0-to-C1 transition, successor-review manifest and approval.
6. Build and independently verify a new unsigned authority-transition request.
   Its `:authority-transition/execution-commitment` is the value to sign with
   the verified v9 keystore.

No EIP-191 commitment is recorded here because the fresh deployment identity
and observations required to derive it do not yet exist. No approval, signature
or transaction has been created for v9.

Once the independent reviewer supplies the approved v9 trusted context and
active approver key ID, run this read-only preflight before asking the authorized
signer to approve. It checks the source lock, manifest/package binding, action,
principal and active public-key entry; it does not read a private key or create
an approval:

```sh
clojure -M:test -m scripts.deployment.configuration-review preflight-initial-inputs \
  etc/deployments/bounded-configuration-local-candidate-v9/review-manifest.v3.edn \
  etc/deployments/bounded-configuration-local-candidate-v9/candidate-package-set.edn \
  /path/to/reviewer-approved-v9-trusted-context.edn \
  :exact-active-approver-key-id
```

Proceed only for `valid: true` and qualification
`ready-for-authorized-signature-not-approved`. The final phrase is intentional:
preflight does not authorize anything.

## Current local devnet observation

On 2026-11-01 at 11:02 UTC, the local RPC reported chain ID 31337 and block
347. The Genesis contract at `0x3fdc08d815cc4ed3b7f69ee246716f2c8bcd6b07`
is already sealed, with chain-instance root
`0xcddcc621c4f7f0e0d1952203785de6099e4819b2dbc8268b68b28cd76bf2c154` and
initial authority-state root
`0x062aac742a56de1f3d51e1debc4fd8670b1dda8fc1acb68c0bbcd10ae1b8337e`.
These are the retained v8 roots, not v9. The existing local chain has been
left intact; v9 needs a separately deployed and sealed instance.

After the fresh deployment, construct an EDN input file with exactly these
fields: `:previous-state`, `:successor-state`, `:current-policy`,
`:successor-policy`, `:chain-instance`, and `:decision-preimages`. Then derive
the unsigned request:

```sh
clojure -M:test -m scripts.deployment.authority-transition-review build \
  path/to/v9-authority-transition-inputs.edn \
  etc/deployments/bounded-configuration-local-candidate-v9/authority-transition-request.unsigned.edn
```

The builder independently validates the envelope semantics, creates the file
without overwriting an existing path, and prints the execution commitment with
qualification `unsigned-derived-not-approved`. It does not sign or submit it.
