(ns resolver-sim.protocols.common.action-context
  "Protocol-agnostic action context helpers.

   These wrappers intentionally avoid protocol-specific error constructors.
   Callers inject resolver and error/failure functions.

   Error boundary contract:
   - This namespace does NOT translate, normalize, or wrap error maps.
   - Resolver/check/role failures are returned unchanged from injected fns.
   - Success path delegates to caller callback; callback return is propagated as-is.

   In practice, protocol modules own domain error semantics while these helpers
   only orchestrate control-flow ordering.")

(defn with-resolved-actor
  "Resolve actor via (resolve-fn agent-index agent-id) and call (f actor-address).

   Expected resolve-fn contract:
   - success: {:ok true :address <address>}
   - failure: {:ok false ...}

   Error boundary:
   - resolve failure map is returned unchanged
   - callback result is returned unchanged"
  [resolve-fn agent-index event f]
  (let [ar (resolve-fn agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (f (:address ar)))))

(defn with-resolved-actor-and-check
  "Resolve actor, run (check-fn), then call (f actor-address).

   check-fn must return either:
   - {:ok true}
   - {:ok false ...}

   Error boundary:
   - resolve/check failure maps are returned unchanged
   - callback result is returned unchanged"
  [resolve-fn check-fn agent-index event f]
  (with-resolved-actor
    resolve-fn agent-index event
    (fn [addr]
      (let [check-result (check-fn)]
        (if (:ok check-result)
          (f addr)
          check-result)))))

(defn with-role-actor
  "Resolve actor, enforce role predicate on actor-map, then call (f address actor).

   - actor-fetch-fn: (fn [agent-index event] -> actor-map)
   - role-pred?:     (fn [actor-map] -> truthy/falsey)
   - role-fail-fn:   (fn [] -> {:ok false ...})

   Error boundary:
   - resolve failure map is returned unchanged
   - role-fail-fn return is propagated unchanged
   - callback result is returned unchanged"
  [resolve-fn actor-fetch-fn role-pred? role-fail-fn agent-index event f]
  (with-resolved-actor
    resolve-fn agent-index event
    (fn [addr]
      (let [actor (actor-fetch-fn agent-index event)]
        (if (role-pred? actor)
          (f addr actor)
          (role-fail-fn))))))
