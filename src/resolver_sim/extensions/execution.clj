(ns resolver-sim.extensions.execution
  "Resolve and invoke extension capability entrypoints.

   Entrypoints are declared as symbols in capability descriptors and are never
   stored as closures. Resolution happens at execution time, after the
   registry has been frozen. A capability is invoked with a kind-specific
   input map and returns a kind-specific structured result map; the contract
   is defined by the capability's input/output schemas, not by core.
   Entrypoints that are unavailable resolve to nil so callers can classify
   the result as :not-evaluated / :unsupported-extension rather than silently
   falling back to core behaviour.")

(defn resolve-entrypoint
  "Resolve a resolved registry entry's entrypoint symbol to its Var value
   (a function), or nil when the namespace or Var is unavailable. The
   namespace is required lazily; a missing namespace yields nil."
  [entry]
  (let [cap (:capability entry)
        sym (:entrypoint cap)]
    (when sym
      (try
        (require (symbol (namespace sym)))
        (when-let [v (resolve sym)]
          @v)
        (catch java.io.FileNotFoundException _ nil)))))

(defn invoke-capability
  "Invoke a resolved capability entrypoint with a kind-specific input map.
   Returns the structured result, or nil when the entrypoint is unavailable."
  [entry input]
  (when-let [f (resolve-entrypoint entry)]
    (f input)))
