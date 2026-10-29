# Local researcher ratification API demo

PRF now includes a fixture-backed, loopback-only HTTP adapter for trying the
existing researcher ratification functions. It is an integration prototype,
not a production identity, policy, evidence, or storage service. The adapter
keeps `demoOnly: true`, reports policy approval as `not-approved`, and always
reports `operationalAuthority: false`.

## Start the local service

From the repository root, run:

```sh
clojure -M -m resolver-sim.research.ratification-http \
  examples/research-ratification-demo.edn
```

The fixture binds only to `127.0.0.1`. It uses an illustrative target, a
test-only researcher identity and bearer token, the repository's test public
key, and `/tmp/prf-research-ratification-demo.edn` for its append state. The
matching private key is a repository test fixture and must never be used for
real research. The persisted state file can be removed to reset the example.

The fixture intentionally reports its analysis as `not-established` with
`methodVerification: unavailable`. This is a fixture declaration, not a
verification result: the adapter does not run an analytical verifier. A
successful researcher ratification must not upgrade that separate claim
assessment.

## Read and contribute

The OpenAPI draft is
[`../../schemas/research-ratification-demo-api.v1.json`](../../schemas/research-ratification-demo-api.v1.json).
Read endpoints are public on loopback; contribution endpoints require the
fixture bearer token. For example:

The server prints the computed target root at startup. The illustrative roots
in the config are inputs to canonical PRF hashing, not the target root. Use
the printed root in the examples below (shown as `$TARGET_ROOT`):

```sh
curl "http://127.0.0.1:8787/v1/targets/$TARGET_ROOT"
curl -X POST "http://127.0.0.1:8787/v1/targets/$TARGET_ROOT/contribution-intents" \
  -H 'Authorization: Bearer demo-researcher-token' \
  -H 'Content-Type: application/json' \
  -d '{"kind":"approve"}'
```

The interaction sequence is:

1. `GET /v1/targets/{targetRoot}` to inspect the frozen target and policy.
2. `POST /v1/targets/{targetRoot}/contribution-intents` with
   `{"kind":"approve"}` and `Authorization: Bearer demo-researcher-token`.
   The response provides the exact decision fields to sign.
3. Sign those fields with the researcher's own Ed25519 signer, then submit
   `{"kind":"approve","decision":{...signed researcher-decision.v2...}}`
   to `/contributions`. The server verifies the signature and reconstructs
   the contribution and ratification using the existing PRF functions.
4. Read `/ratification` or reload the workspace to inspect the reconstructed
   result. Repeating the identical signed decision returns an idempotent
   receipt; a different second contribution by the same researcher is
   rejected.

The service accepts dissent, challenge, and reproduction contributions too.
They need a signed dissent reason; challenge and reproduction also need an
evidence root. These records remain positions and evidence references. The
adapter does not fetch or scientifically validate evidence.

## Boundaries and remaining work

This adapter persists a local EDN fixture store and trusts the researcher/key
bindings in its config. It is single-process demo storage, has no account
administration, and does not establish that distinct keys represent
independent people. The signature intent and submission use the existing PRF
`researcher-decision.v2` implementation; the adapter adds transport and
receipt persistence, not another ratification engine.

The Research Resolution notebook in the pinned `prf/notebooks` dependency now
has an optional live workspace and intent preview. It displays claim status,
method verification, ratification, policy approval, and operational authority
as separate fields. A stable external integration still needs a golden JSON
fixture, independent client reconstruction, policy approval, and an actual
method-verification path.
