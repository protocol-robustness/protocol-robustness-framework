# Generic Economics Layering

`resolver-sim.economics/*` contains protocol-agnostic economic primitives: allocation, accounting, fee math, and other reusable calculations expressed in abstract terms.

Protocol-specific namespaces adapt protocol state and policy into those primitives. For Sew, that adapter layer lives under `resolver-sim.protocols.sew/*`; Sew-specific concepts such as resolvers, workflow ids, slashable stake, slash ids, appeals, escrows, junior bonds, senior pools, fraud slashes, reversal slashes, timeout slashes, and governance must stay in the Sew layer.

The dependency direction is one-way:

```text
resolver-sim.protocols.sew/* -> resolver-sim.economics/*
resolver-sim.economics/*     -> no protocol namespaces
```

Generic economics code must never depend on `resolver-sim.protocols.sew` or any other protocol namespace. If a protocol needs domain-shaped output for compatibility, create or extend an adapter in that protocol namespace and keep only a temporary deprecated wrapper in generic code when migration requires it.
