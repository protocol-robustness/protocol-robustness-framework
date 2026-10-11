# Challenge 001 approval preparation

This is an unsigned, inactive approval payload. It is not the frozen challenge
configuration, a publication, a reward promise, an obligation, or payment
authorization. The payload is
[`challenge-001-approval-payload.v1.edn`](../../examples/researcher-challenges/shortfall-stress/challenge-001-approval-payload.v1.edn)
and its root is
`sha256:6895b7bc2d413c92aa02c3e54188e9d85954d012edf2d2937bb026e5fcb6171b`.

The payload binds the approved scope/method/source/policy roots, the bounded
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

Repository searches found no dedicated Challenge 001 operator/reviewer keys.
Checked-in signing keys are fixtures or unrelated release keys and must not be
reused. The local SSH agent was unavailable for inspection. Each role owner
must provision a distinct Ed25519 key on their own machine; the owners should
keep private material there and provide only public-key content and the
detached signature. Distinct keys establish key distinction, not distinct
people or organizations.

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
            "sha256:6895b7bc2d413c92aa02c3e54188e9d85954d012edf2d2937bb026e5fcb6171b"
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

The source manifest was refreshed for the current local checkout and
independently rechecked: 219 entries, zero digest mismatches; manifest file root
`sha256:790402f2ecd202dcdf2d7b509f34c31d3a09b2ee06d7df4ee2e0b1201ab3bffb`.
This is a new unsigned local approval candidate. Approval of the prior source
content-set root does not transfer to it.
It is a content-set identity, not a captured `jj` revision or hermetic build
environment. The V2 reward-policy root for the payload's chain-qualified
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
