# Ecommerce use-case example bundle

This is illustrative PRF-repository-authored content, not a framework-owned
use-case. It demonstrates the exact external contract that an application uses:

```clojure
(resolver-sim.use-cases.registry/load-use-case-registry
 "examples/use-cases/ecommerce/registry.edn")
```

`registry.edn` follows `:prf/use-case-registry.v1`. Each definition is resolved
only relative to that registry, must remain inside this bundle, and is committed
into `:use-case-registry/root`. No runtime component implicitly loads this
bundle or contains ecommerce-specific projection logic.

The `src/` tree retains the optional ecommerce presentation example. It is not
on the framework classpath and must be added by an application that chooses to
render that vocabulary.
