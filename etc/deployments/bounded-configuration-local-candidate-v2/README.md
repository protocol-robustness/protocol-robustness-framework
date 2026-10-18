# Bounded configuration local candidate v2 with authority policy v3

These four package artifacts replace the legacy demo authority-state input with
`authority-state.v1` and the explicitly versioned
`local-authority-policy.v3`, while retaining the
existing candidate protocol-genesis and six initial policy materials. The
initial configuration contains the canonical empty verifier registry, so its
configuration artifact has the six policy roots plus the registry root.

`candidate-derivation.edn` recomputes the protocol, authority, local-policy and
initial-configuration roots. `review-manifest.v3.edn` binds those artifacts to
the PRF source parent and scoped source-tree digest. The package, derivation
and source files are pinned in the bounded release baseline. All package
statuses remain `candidate-owner-approval-required`; the manifest is review
material, not an approval.

The local policy proposes the conventional Anvil governor address and the
candidate secp256k1 transition-validator address
`0xc78884593859e84524e41ce94cce38fbcb58c5f8` under key ID
`bounded-local-transition-validator-v1`. The validator address was generated
for candidate review; its private key is not retained. The generic immutable
ECDSA validator implementation and its test vector now exist, but no owner has
authenticated the governor, candidate validator address, policy, or package.
The candidate cannot authorize a live transition until those semantics and
identities are reviewed and approved. Its roots differ from the previous v2
policy candidate and are recomputed in `candidate-derivation.edn`.

## Review identity

The current source-bound manifest binds source parent
`d282a5fa6f2224c71f510353ca6758cedcb635e4` and the independently locked source
tree `d1b819e37633db8af181bf6b637ba5c615cc69f96ab51bad69003eb599218c68`.
Its canonical manifest root is
`sha256:7662a1a217f2077fb63554c46d28d799eb9c0c8f37aeaf6507aaf66d54721d79`;
the raw manifest file digest is
`sha256:7e9109321b2c58926c8ba1f41fdd945cc3ae37088973f5de6060875cd6073be1`.
The scope is chain ID 31337 and `local-only`; the required initial-input
approval action is `approve-initial-inputs`. The manifest's
`approval-authority` field is a proposed EVM principal for review, not an
independently trusted identity. The approver must be authenticated by the
separate trust policy used by the verifier.

C0 has an empty verifier registry at
`sha256:2099fabf2cca563f8d183749d1a842946286e8704a96c51ec801eff4e834a240`.
Its seven component roots are recorded in `review-manifest.v3.edn` and
`candidate-derivation.edn`; approval covers the complete manifest, not only
the aggregate C0 root. The raw manifest digest and source-tree identity are
recorded in `baselines/bounded-configuration-release-baseline.v1.json`.

Before signing, the input owner should review all four package artifacts, the
derived roots, policy-v3 validator address and source identities. The signer
must use the owner's independently held key and trust role. The dedicated
`sign-initial-inputs` command checks manifest content, the source lock, fixed
action, and trusted-key binding before signing; it writes a detached envelope
without overwriting an existing file. The shared EIP-191
test vector and the proposed Anvil governor are not owner approval material.
Successor C0-to-C1 approval is a separate later action and is not authorized by
this manifest.
