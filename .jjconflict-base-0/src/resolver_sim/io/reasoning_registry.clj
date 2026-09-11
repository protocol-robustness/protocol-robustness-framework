(ns resolver-sim.io.reasoning-registry
  "Registry for protocol-specific semantic classifiers.
   Used by the reasoning-capsule to inject state-aware conceptual metadata.")

(def ^:private registry (atom {}))

(defn register-classifier!
  "Register a classification function for a protocol.
   `classifier-fn` must accept (protocol-id world entry) and return a map."
  [protocol-id classifier-fn]
  (swap! registry assoc protocol-id classifier-fn))

(defn classify-semantic-impact
  "Invoke the registered classifier for the given protocol."
  [protocol-id world entry]
  (if-let [classifier (get @registry protocol-id)]
    (classifier world entry)
    {}))
