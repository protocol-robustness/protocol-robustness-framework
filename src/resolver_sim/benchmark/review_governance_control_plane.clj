(ns resolver-sim.benchmark.review-governance-control-plane
  "Authenticated control-plane resolution for governed review authority.
   Production callers construct this context; authority evaluation consumes only
   the derived answers, never independently supplied governance/time callbacks."
  (:require [resolver-sim.genesis :as genesis]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as round]))

(defn resolve-context
  "Resolve one direct P0 graph: canonical configuration -> direct governance
   root -> unique policy -> governed round. `control-plane-evidence` is the
   the authenticated finalization/head event and must carry the authoritative
   configuration root. P0 eligibility is governance-root scoped, so this
   resolver intentionally has no position-time input or callback. Returns a
   data-only, fail-closed context with specific reasons."
  [{:keys [chain-configuration review-governance review-round
           control-plane-evidence]}]
  (let [config-root (try (genesis/chain-configuration-root chain-configuration)
                         (catch Exception _ nil))
        governance-root (when review-governance (governance/governance-root review-governance))
        round-config (:review-round/chain-configuration-root review-round)
        round-governance (:review-round/governance-root review-round)
        authoritative-config (:authoritative-chain-configuration/root control-plane-evidence)
        authoritative-governance (:authoritative-review-governance/root control-plane-evidence)
        policy (governance/policy-by-id review-governance (:review-round/policy-id review-round))
        reasons (cond-> []
                  (nil? config-root) (conj :configuration-root-invalid)
                  (nil? governance-root) (conj :governance-root-unresolvable)
                  (not= config-root round-config) (conj :round-configuration-mismatch)
                  (not= governance-root round-governance) (conj :round-governance-mismatch)
                  (not= (:governance-policy/root chain-configuration) governance-root) (conj :configuration-governance-mismatch)
                  (nil? policy) (conj :policy-unresolvable)
                  (not= config-root authoritative-config) (conj :governance-stale-reconstitution-required)
                  (not= governance-root authoritative-governance) (conj :governance-stale-reconstitution-required))]
    {:resolved? (empty? reasons)
     :reasons (vec (distinct reasons))
     :chain-configuration/root config-root
     :review-governance/root governance-root
     ;; The snapshot itself is returned only by this trusted control-plane
     ;; resolver; consumers must never take governance from an event payload.
     :review-governance review-governance
     :review-policy policy
     :review-round review-round
     :control-plane-evidence control-plane-evidence
     ;; Compatibility adapter over the already-resolved finalization result.
     ;; It must be replaced by a verifier of configuration-head activation
     ;; evidence before this namespace is used by production admission.
     :governance-current? (fn [_ _] (empty? reasons))}))
