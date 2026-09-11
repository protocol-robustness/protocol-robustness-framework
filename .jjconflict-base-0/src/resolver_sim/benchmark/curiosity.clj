(ns resolver-sim.benchmark.curiosity
  "Read-only, fail-closed answers to explicitly named operational questions.

   Curiosities are observations: they neither issue an authority fence nor grant
   authority. Currentness is derived from authenticated authority-store state,
   never accepted as a caller assertion.

   This namespace owns curiosity semantics: IDs, closed dispatch, result/status
   semantics, and safety classification. A use case's required curiosities are
   owned by its committed definition (:concept/required-curiosities); the
   in-file bootstrap declaration is transitional compatibility data reconciled
   by resolve-use-case-required-curiosities."
  (:require [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence :as execution-evidence]))

(def currently-authorized-chain-configuration-root
  :currently-authorized-chain-configuration-root)

(def current-write-back-operationally-verified
  :current-write-back-operationally-verified)

(def curiosity-ids
  #{currently-authorized-chain-configuration-root
    current-write-back-operationally-verified})

(def bootstrap-use-case-required-curiosities
  "Transitional compatibility data for use cases that do not yet have committed
   :concept/required-curiosities definitions in an external use-case registry.

   This is NOT the authoritative long-term owner of use-case requirements: a
   committed use-case definition owns its required curiosities. The bootstrap
   declaration exists so `resolve-use-case-required-curiosities` can reconcile
   it with a committed declaration, and it is deleted once a migration-equivalence
   test proves each entry is redundant with the committed definition."
  {:resubmission/new-chain
   #{currently-authorized-chain-configuration-root}
   :governed-authority/current-admission
   #{currently-authorized-chain-configuration-root
     current-write-back-operationally-verified}})

(defn resolve-use-case-required-curiosities
  "Resolve one use case's required curiosities from its bootstrap declaration
   and its committed/external declaration, if any. Both inputs must already be
   validated sets of keyword curiosity IDs, so disagreement is semantic, not
   representational.

   Neither present → no curiosity requirements. Bootstrap only → bootstrap
   requirements. External only → external requirements. Both and equal → the
   committed/external requirements. Both and unequal → fail closed. There is no
   union, no precedence on disagreement, and no silent fallback."
  [bootstrap-declarations external-declarations use-case-id]
  (let [bootstrap (or (get bootstrap-declarations use-case-id) #{})
        external (or (get external-declarations use-case-id) #{})]
    (cond
      (and (empty? bootstrap) (empty? external)) #{}
      (empty? external) bootstrap
      (empty? bootstrap) external
      (= bootstrap external) external
      :else
      (throw (ex-info "curiosity requirements disagree between bootstrap and committed declarations"
                      {:error/code :curiosity-requirements/disagreement
                       :use-case/id use-case-id
                       :bootstrap/required-curiosities bootstrap
                       :declared/required-curiosities external})))))

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
