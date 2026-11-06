# Research module boundaries

This note records the current internal boundaries and extraction decisions for
the research execution code. These modules remain inside PRF; source size alone
does not establish an independent repository or extension boundary.

## Current internal splits

| Area | Boundary | Preserved responsibility |
| --- | --- | --- |
| Runner transport | `runner-transport` is the compatibility facade; `runner-transport/*` owns HTTP/authentication, journal mechanics, handlers, dispatch, execution, lifecycle, proof, result delivery, and work types. | State ownership, journal coordination, recovery, forensic reconstruction, and public API remain in the facade. This is still a research lab transport, not a distributed ordering service or extension provider. |
| File-based partial-fill | `partial-fill-file-artifacts` owns pure rooted constructors, validation, and the economic projection; `partial-fill-file-profile` owns the file journal, state transitions, admission/commit order, recovery, and forensic projection. | Artifact schemas, hash domains, file format, and profile API remain unchanged. The profile remains a local single-authority model. |

## Areas assessed and kept cohesive

- `scheduler-harness` owns its rooted workload, deterministic local execution,
  experiment and parameter sweep. `distributed-scheduler` is already a
  separate semantic simulator layered on that workload, with topology, policy,
  transfer and failure concepts in their own namespaces. Keep this layering;
  do not split the harness into a generic scheduler framework until a second
  independent consumer establishes that contract.
- `dependency-assurance` groups declaration, policy, resolution, temporal
  applicability, and observation comparison under one dependency identity
  model. The later comparisons call into the same schemas and validation
  rules. Keep the namespace cohesive unless a consumer needs a separately
  versioned comparison API.

## Repository extraction gate

Do not create a broad `prf-research` repository. Reconsider a focused repository
only when one capability has a stable public API, can execute from a clean
checkout without PRF-private source paths, pins its complete dependency set,
owns its tests and fixtures, and can be qualified independently. Keep research
evidence and demonstrations separate from the implementation boundary.
