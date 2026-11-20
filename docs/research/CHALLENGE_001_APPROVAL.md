# Challenge 001 approval preparation

This is a proposed, inactive approval payload with development-only signatures.
It is not owner-approved and is not the frozen challenge configuration, a
publication, a reward promise, an obligation, or payment authorization. The payload is
[`challenge-001-approval-payload.v1.edn`](../../examples/researcher-challenges/shortfall-stress/challenge-001-approval-payload.v1.edn)
and its root is
`sha256:2832a447b01cc04301d082eb544315cfbd2e5a41bb4364065458c591f82aa89d`.

The payload binds the proposed scope/method/source/policy roots, the bounded
domain, calibration exclusion, novelty and acceptance rules, Base USDC asset,
reward schedule, and the challenge-specific pseudonymous network scope. The
subject root is the existing candidate subject map hashed under
`challenge-001-subject.v1`. The network ID is
`:researcher-challenges/challenge-001-shortfall-under-stress`; its root is
derived by the existing `researcher-challenges/network-root` function. This
fresh network must start with empty history and contain only Challenge 001 so
network-scoped experience cannot carry an earlier eight-contribution tier into
the pilot.

## Local approval-key procedure

For the current local demo, the supplied development-only Ed25519 operator and
reviewer private keys are outside this repository at
`/home/user/Code/challenge-001-keys` with mode `600`. The previously requested
`/secure/path/challenge` directory is not present in this environment. The
checked-in bundle contains only the public keys and detached signatures over
this exact root. Their fingerprints are
`sha256:f8f8f25db85a261697cd02b58300c933452ffb1e5659f5bdf3c3ab6749ddf907`
and
`sha256:7d31f496fdfa39a117e694fc1e71815b5d73ae5f7ea2474d6ab350504294a0e7`.
These development keys do not establish owner identity or approval. The
detached signatures and public-key content are in
[`challenge-001-approval-signatures.devtest.v1.edn`](../../examples/researcher-challenges/shortfall-stress/challenge-001-approval-signatures.devtest.v1.edn).
Both signatures verify against the exact payload root above, and the public-key
fingerprints differ. This bundle exercises signing and verification only: it
does not establish owner identity, approve the challenge, authorize funding,
or activate publication. The approval payload remains in proposed/inactive
state.

For any later live authorization, role owners must use separately provisioned
owner-controlled keys and provide fresh signatures over the exact then-current
payload root. Distinct keys establish key distinction, not distinct people or
organizations.

One file-based procedure compatible with the PRF Ed25519 signer is to generate
an encrypted PKCS#8 key in an owner-controlled, encrypted local directory
outside the repository. OpenSSL prompts for the passphrase; do not put it in a
command argument or environment variable:

```sh
umask 077
openssl genpkey -algorithm ED25519 -aes-256-cbc \
  -out /secure/local/path/challenge-001-operator.pem
openssl pkey -in /secure/local/path/challenge-001-operator.pem \
  -pubout -out /secure/local/path/challenge-001-operator.pub.pem
```

The reviewer performs the same steps with a different key and role-specific
filename. Do not place either private key in this repository, a shared staging
directory, logs, chat, or a public publication bundle. Confirm the resulting
public key parses as Ed25519 before signing. If an owner uses a hardware-backed
key instead, the current PRF signer is file-based and that integration is not
qualified here; do not export a hardware key to work around that limitation.

Each owner signs the exact approval root locally with the existing
`resolver-sim.benchmark.signing/sign-hash` function. In a local Clojure REPL,
the owner can prompt for the passphrase and print only the signature:

```clojure
(require '[resolver-sim.benchmark.signing :as signing])
(let [console (System/console)
      password (String. (.readPassword console "Private-key passphrase: " (object-array 0)))]
  (println (signing/sign-hash
            "sha256:2832a447b01cc04301d082eb544315cfbd2e5a41bb4364065458c591f82aa89d"
            "/secure/local/path/challenge-001-operator.pem"
            password)))
```

The reviewer signs the same root with their own private key. The detached
submission must label each signature role and include that role's public-key
content. Verification must use the independently supplied operator key for the
operator signature and reviewer key for the reviewer signature, reject equal
public keys, and call
`verify-signature-with-public-key-content` on the exact root for each. Neither
signature is activation by itself. Do not change the payload after signing;
any change requires a new root and both signatures again.

## Validation and activation boundary

The source manifest was refreshed against source revision
`0b1c907e00c6982ebe7b5b557906effec1dbc498` and contains 245 entries. Its root is
`sha256:b416f5cb715cada609dd5739fde0edd16e4250acd2cc65159ad32772d9d62010`;
the scope candidate root is
`sha256:94a974fd5c2d30c2574cd82b6159b147812fe21788b07ed21f4212a9ac794f73`.
The devtest signatures bind the exact payload root above but do not authorize
the challenge. Prior signatures do not transfer to updated roots. Any future
owner signatures must cover the exact then-current payload root.
The manifest root identifies this content set; the candidate separately records
the source `jj` revision. It is not a hermetic build identity. The V2 reward-policy root for the payload's chain-qualified
currency string is
`sha256:b1e0a7d2c608ccffbc768612a9b21347dfd9e49b52ef4d23a0fb46a823dde119`.
With empty network history, six accepted contributions, two researchers, and
at most four per researcher, the maximum quoted liability is 625,000,000 minor
units (625 USDC), below the 750 USDC ceiling. The 1.25× quote applies only after
three previously accepted eligible contributions at task-claim time; the
eight-contribution tier is unreachable in this pilot.

The approval payload and signatures do not themselves create or publish the
researcher-challenge configuration. After both signatures are verified, the
activation operator must still construct and validate the exact challenge and
configuration, publish the trusted configuration through the existing signed
publication path, and verify activation before opening the closed pilot. No
fund transfer is authorized by these artifacts.
