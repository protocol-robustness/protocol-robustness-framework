# User-owned research ratification demonstration

This document describes a draft HTTP contract for a demonstration frontend in
a separate repository controlled and deployed by its author. The frontend is
not part of PRF, and its hosting, identity provider, storage, branding, and
configuration are controlled by that repository's owner.

The demonstration label must remain visible on every workspace and result:
**demonstration only**. A research ratification is not a SEW ruling, legal
decision, governance authorization, reward, or permission to execute an
operation. The current PRF ratification result sets
`operationalAuthority` to `false`.

## What the frontend consumes

The user-owned frontend should generate its HTTP client and TypeScript types
from [`../../schemas/research-ratification-demo-api.v1.json`](../../schemas/research-ratification-demo-api.v1.json).
It should not import PRF's internal Clojure namespaces or reimplement PRF's
canonical hashing. The API serves the roots computed by its verifier.

A backend adapter implements the contract and calls the existing PRF
ratification functions. In this repository that means
`resolver-sim.research.ratification` for target, policy, contribution, and
result reconstruction, plus
`resolver-sim.benchmark.research-ratification-artifact` when creating or
verifying retained research artifacts. The backend also owns authentication,
the principal-to-key registry, evidence retrieval, and immutable storage.
Those adapter services do not exist yet; the OpenAPI file is a draft contract,
not a claim that the routes are currently available.

## Interaction sequence

1. Load a workspace by its immutable target root. Display its subject,
   evidence, round, policy, predecessor, and root before enabling a response.
2. Request a contribution intent. The service binds the authenticated
   principal, researcher ID, registered signing-key ID, contribution kind,
   evidence root, target root, and round, and returns the exact
   `researcher-decision.v2` fields to sign. In this demo contract,
   `authorisationId` and `requestRoot` are both the immutable target root. The
   service checks every returned identity and scope field against the
   submitted decision. This step does not record a contribution.
3. Sign the returned payload using a supported user-controlled signer. Submit
   the signed decision. The service checks eligibility, reconstructs the
   contribution subject root, verifies the Ed25519 signature and target/round
   binding, and rejects a second contribution from the same researcher or
   principal for that target.
4. Reload the workspace and independently reconstruct the ratification from
   the retained target, policy, contributions, and trusted key bindings.
5. If evidence, question, round, or policy changes, publish a successor
   target with a predecessor root. Earlier contributions remain attached to
   their original target and do not authorize the successor.

`challenge` and `reproduction` contributions require an evidence root. Under
the current PRF model they are signed as dissent decisions whose contribution
kind and evidence root are included in the signed outcome root. A challenge
can keep the result `contested-unresolved` when policy says challenges block.
The UI should render dissent and challenge evidence as retained positions,
not discard them when the threshold is reached.

## User-owned repository boundary

The separate frontend repository should import only a generated client and
types from this contract (or a released package generated from it). It may
include a demo gateway that calls a PRF verifier, but must pin the PRF source
revision or released library version and keep its own identity and storage
configuration. The current PRF repository is not published as a standalone
ratification library, so an initial gateway can pin this repository revision
and call the existing Clojure namespaces. Do not make the browser depend on
the PRF source tree.

The current researcher CLI signs with local Ed25519 key files. The browser
signing method is not specified by the existing implementation. The demo must
choose an explicit signer adapter, keep private-key custody outside ordinary
page JavaScript, and show the exact target root and decision fields at signing
time. Test keys and fixture identities must be visibly marked as test-only.

## Draft limitations

The OpenAPI document defines read, contribution-intent, contribution-submit,
and reconstruction operations. It intentionally leaves account creation,
membership administration, policy publication, target publication, evidence
upload, notifications, and production key custody to the user-owned service.
The current ratification library receives eligible principals and key
bindings from its caller; it does not establish an identity provider or
membership registry.

The contract needs a golden JSON fixture and cross-language root tests before
it is treated as a stable integration surface. Until then, version it as a
draft and expect the API adapter to reject unknown schema versions and
unrecognized fields.
