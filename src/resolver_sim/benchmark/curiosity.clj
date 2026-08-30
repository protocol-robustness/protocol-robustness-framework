(ns resolver-sim.benchmark.curiosity
  "Read-only, fail-closed answers to explicitly named operational questions.

   Curiosities are observations: they neither issue an authority fence nor grant
   authority. Currentness is derived from authenticated authority-store state,
   never accepted as a caller assertion."
  (:require [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as execution-evidence]))

(def currently-authorized-chain-configuration-root
  :currently-authorized-chain-configuration-root)

(def current-write-back-operationally-verified
  :current-write-back-operationally-verified)

(def curiosity-ids
  #{currently-authorized-chain-configuration-root
    current-write-back-operationally-verified})

(def use-case-required-curiosities
  "The declared curiosity requirements of the two currently implemented
   admission use cases. These IDs are also carried by external use-case
   definitions through :concept/required-curiosities."
  {:resubmission/new-chain
   #{currently-authorized-chain-configuration-root}
   :governed-authority/current-admission
   #{currently-authorized-chain-configuration-root
     current-write-back-operationally-verified}})

(defn- result
  [id status & {:as values}]
  (merge {:curiosity/id id
          :curiosity/status status
          :curiosity/value nil
          :curiosity/authority-granted? false}
         values))

(defn resolve-currently-authorized-chain-configuration-root
  "Observe the configuration root retained by the current authenticated authority
   state identified by a current-admission resolution basis. This invokes only
   the read-only context resolver; it does not issue an observation handle,
   fence, or other store capability."
  [{:keys [authority-store resolution-basis]}]
  (let [resolved (authority-state/resolve-governed-authority-context
                  authority-store resolution-basis)]
    (if (:resolved? resolved)
      (result currently-authorized-chain-configuration-root :true
              :curiosity/value (get-in resolved [:context :chain-configuration/root])
              :curiosity/context-root
              (get-in resolved [:context :resolved-review-authority-context/root]))
      (result currently-authorized-chain-configuration-root
              (if (= :state-not-at-required-head (:reason resolved)) :stale :invalid-evidence)
              :curiosity/reason (:reason resolved)))))

(defn resolve-current-write-back-operationally-verified
  "Observe the V2 aggregate write-back operational result. Structural evidence
   must self-validate; an absent result field is distinct from a valid false
   observation. This observation does not perform a write-back."
  [{:keys [execution-evidence-profile]}]
  (let [validation (execution-evidence/validate-pro-rata-execution-evidence-any
                    execution-evidence-profile)]
    (cond
      (not (:valid? validation))
      (result current-write-back-operationally-verified :invalid-evidence
              :curiosity/reason :execution-evidence-invalid
              :curiosity/errors (:errors validation))

      (not= "pro-rata-execution-evidence.v2"
            (:schema-version execution-evidence-profile))
      (result current-write-back-operationally-verified :absent
              :curiosity/reason :operational-write-back-not-defined-by-evidence-version)

      :else
      (let [value (get-in execution-evidence-profile
                          [:evidence-profile/execution-result
                           :current-write-back-operational-pass?])]
        (result current-write-back-operationally-verified
                (if value :true :false)
                :curiosity/value value
                :curiosity/evidence-root
                (:evidence-profile/hash execution-evidence-profile))))))

(defn resolve-curiosity
  "Resolve one supported curiosity. Dispatch is closed: unknown IDs return a
   structured non-authoritative result rather than invoking caller-supplied code."
  [curiosity-id inputs]
  (case curiosity-id
    :currently-authorized-chain-configuration-root
    (resolve-currently-authorized-chain-configuration-root inputs)

    :current-write-back-operationally-verified
    (resolve-current-write-back-operationally-verified inputs)

    (result curiosity-id :unknown-curiosity
            :curiosity/reason :unsupported-curiosity)))
