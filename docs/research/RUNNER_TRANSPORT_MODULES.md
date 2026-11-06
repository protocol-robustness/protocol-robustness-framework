# Research runner transport module boundaries

The authenticated runner/coordinator transport is a research lab adapter. Its
public entry points remain in `resolver-sim.research.runner-transport`; the
implementation is split into internal namespaces so transport, persistence,
dispatch, and work-artifact concerns can be reviewed independently.

| Namespace | Owns | Does not own |
| --- | --- | --- |
| `resolver-sim.research.runner-transport.http` | HTTP EDN wire encoding, shared-secret MAC authentication, and request/response helpers | Work validation, state transitions, or evidence roots |
| `resolver-sim.research.runner-transport.journal` | Runtime checkpoint/delta encoding, append/replacement I/O, and generic replay | Semantic validation of recovered runner/coordinator state |
| `resolver-sim.research.runner-transport.work-types` | Work-schema discrimination, root lookup, and the rebuildable native-accounting input cache | Admission authority or canonical artifact definitions |
| `resolver-sim.research.runner-transport.handlers` | Authenticated HTTP ingress, message validation, and coordinator/runner ingestion handlers | Listener lifecycle, shared transport implementation, or semantic artifact ownership |
| `resolver-sim.research.runner-transport.dispatch` | Coordinator-to-runner delivery, retry, result return, and cancellation protocol behavior | Runner/coordinator persistence and transport credentials; these are supplied by the facade for each call |
| `resolver-sim.research.runner-transport.execution` | Runner-side execution lifecycle for reproduction, native-accounting, observed, and accounting work | Runner state, forensic mutation, or network delivery; these are supplied by the facade for each call |
| `resolver-sim.research.runner-transport.lifecycle` | Runner/coordinator listener startup, handler registration, recovery ordering, and shutdown | Semantic state transitions or evidence validation; those are supplied by the facade and handler modules |
| `resolver-sim.research.runner-transport.proof` | Proof artifact persistence/delivery and coordinator-side proof admission | Proof generation policy, runner/coordinator state ownership, or transport endpoint lifecycle |
| `resolver-sim.research.runner-transport.results` | Completed result delivery, acknowledgement evidence, and at-least-once retry | Runner state ownership or coordinator admission policy |
| `resolver-sim.research.runner-transport` | Compatibility API, state ownership and recovery, and forensic reconstruction | A distributed ordering authority or production payment executor |

This split is internal. Existing artifact schemas, roots, journal formats,
retry semantics, endpoint paths, and public function signatures are retained.
Dispatch and execution adapters are call-scoped and receive runtime services
from the facade; they do not persist a second copy of state.

The transport is not currently a PRF extension. The framework extension
architecture in ADR-0005 is proposed and has no transport-provider capability
contract. A separately distributed transport may later implement a narrow
provider interface if it needs independent release or deployment. Any such
provider must leave work semantics, ordering authority, and evidence contracts
with their existing owners.
