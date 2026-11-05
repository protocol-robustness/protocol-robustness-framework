# Repairability report extension proposal

Status: proposal; no report capability is registered or qualified.

## Purpose

Provide a deterministic, read-only report section that helps a reviewer answer
which repository repair options are supported by the available evidence, what
information is missing, and what authority is still required. The report is a
projection over existing PRF and `prf-dev` repair records. It does not create a
repair, approve one, execute one, or authorize publication.

This proposal follows the `:report/section` capability kind described in
[ADR-0005](../architecture/ADR-0005-framework-extension-packages.md). ADR-0005
is still Proposed, and the framework does not yet expose a stable core report
API. An unqualified, in-tree package candidate now exists at
`extensions/repairability-report`; it is not registered by default and does
not claim conformance to a stable report API.

## Existing inputs and authority boundary

`prf-dev` already defines versioned `repair-assessment.v1`,
`repair-authorization.v1`, `repair-execution.v1`, `repair-review.v1`, and
`repair-verification.v1` views. Its offline verifier checks signed records and
evidence against an independently supplied trust policy, then emits
claim-scoped results. PRO-90's current owner contract is still Backlog; it
specifies that the protected-repair workflow must define versioned
authorization, execution-evidence, review, and final-report records. The
current `repair-verification.v1` schema is the verifier's emitted result, not a
signed final-report record or an approved portable receipt contract. PRO-90
remains authoritative for protected repair authorization, evidence semantics,
independent review, and publication authority; PRO-106 owns routine
submission/review/staging. See `prf-dev/docs/repairs.md`,
`prf-dev/docs/ownership.md`, and Linear issue PRO-90.

The present `repair-verification.v1` output is not itself content-rooted or
signed, and it does not identify an independently reproducible verifier
build. Therefore an extension must not treat a supplied verification JSON as
trusted proof. A report can label such data as an unverified producer
assertion, but cannot upgrade it. The next owner-side decision is to define the
verifier receipt as part of PRO-90's final-report contract (or explicitly
specify how that final report binds the verifier result). At minimum, that
contract needs to bind the exact repair bundle root, independently supplied
trust-policy root, verifier identity and version/build, and claim results;
the receipt itself must be independently checkable. Until PRO-90 approves and
implements that contract, trusted report qualification remains blocked.

## Proposed section contents

The section should preserve separate dimensions rather than collapse them into
a `repairable` boolean:

| Dimension | Example values | What it means |
|---|---|---|
| Evidence integrity | verified / failed / unknown | Whether the referenced records and evidence match their committed identities. |
| Repair feasibility | supported / blocked / unknown | Whether the named operation has sufficient inputs and a bounded recovery path under the declared scope. |
| Authorization | authorized / absent / invalid / unknown | Whether the exact repair action has valid prior authority under the independently supplied policy. |
| Execution | verified / failed / absent / unknown | Whether the authorized operation's execution evidence is valid and bound to the same snapshot. |
| Independent review | approved / rejected / incomplete / absent | The status of review for the exact execution record. |
| Publication | verified / absent / not-applicable / unknown | Whether a separate publication receipt exists and is valid. |

Each repair option should identify the exact snapshot root, source and target
revisions/trees when available, proposed operation, preconditions, recoverable
state, missing evidence, expected history impact, reversibility, and remaining
limitations. Unknown information must remain visible. A feasible option is not
an authorization, and a successful replay is not proof of historical
completeness or publication.

## Extension boundary

When the framework has a supported report API, the capability can use
`:report/section`, with a versioned input contract and output schema. It may
depend on the core report API and evidence schemas, as ADR-0005 specifies. It
must not depend on Sew implementation code or become a second owner of
`prf-dev`/PRO-90 semantics.

The initial extension must be pure and read-only: no JJ mutation, filesystem
repair, signing, network access, trust-policy selection, authorization,
publication, or payment action. It should render verified inputs and qualify
each reported claim without inferring authority from consensus or a producer
status field.

## Qualification gates

1. Have the PRO-90 owner define and approve how its required final-report
   record carries a rooted, independently checkable verifier receipt; keep the
   existing `prf-dev` verification output non-authority-bearing until then.
2. Approve the shared report input/output contract and its relationship to the
   existing `prf-dev` schemas. Do not fork their meaning in PRF.
3. Establish and qualify the framework's core report API and capability
   invocation contract.
4. Qualify the in-tree candidate with deterministic fixtures for a repairable
   case, a blocked case, missing evidence, invalid signatures, mismatched
   snapshots, unsigned verification claims, and absent publication authority.
5. Independently verify that the report never upgrades `unknown`, authorizes
   an operation, or implies publication. Only then consider registering it or
   packaging it for external consumers.

## Development workflow

Follow the `prf-dev` JJ process: scope the task, record the candidate and
evidence, obtain independent read-only review, then submit through the staging
queue (`jjq push`). Do not move `agent-c-staging` directly from a coding
workspace, and do not treat staging admission as publication authorization.
