(ns resolver-sim.protocols.sew.pro-rata-application
  "Batch application boundary for a fully authorized Sew pro-rata held credit."
  (:require [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.evm :as evm]
            [resolver-sim.pro-rata.refinement :as refinement]
            [resolver-sim.economics.effects :as effects]))

(defn application-roots
  "Canonical roots used by the batch receipt. Exposed so proof-chain tests and
   independent verifiers use the production projection rather than reimplement it."
  [before after adjustments]
  {:state-before/root (hash/domain-hash :world-state (hash/project-world-to-structure-view before :world-structure))
   :state-after/root (hash/domain-hash :world-state (hash/project-world-to-structure-view after :world-structure))
   :ledger-before/root (hash/domain-hash "SEW_HELD_LEDGER_V1" (or (:held-ledger/index before) {}))
   :ledger-after/root (hash/domain-hash "SEW_HELD_LEDGER_V1" (or (:held-ledger/index after) {}))
   :applied-adjustments/root (held-adjustment/settlement-held-adjustment-set-root adjustments)})

(declare apply-effects-to-world)

(defn derive-pro-rata-state-transition
  "Derive the only canonical post-state for a Sew held-credit refinement.
   The returned transition is a first-class object: its application rows explain
   the affected set while its state-after root commits the complete post-state.
   No supplied state-after root is accepted by this function."
  [world allocation refinement-artifact application-policy-root]
  (let [protocol-effects (:effects refinement-artifact)
        before-count (count (:held-adjustments world))
        world' (apply-effects-to-world world protocol-effects)
        adjustments (vec (drop before-count (:held-adjustments world')))
        roots (application-roots world world' adjustments)
        applications (mapv (fn [{:keys [effect] :as entry} adjustment]
                             {:effect/root (:effect/root entry)
                              :account (:effect/account effect)
                              :direction :credit
                              :allocated (:effect/amount effect)
                              :disposition :held-credit
                              :adjustment/root (effects/held-adjustment-root adjustment)})
                           protocol-effects adjustments)
        application (evm/build-application
                     {:state-before-root (:state-before/root roots)
                      :allocation-root (:allocation/hash allocation)
                      :application-policy-root application-policy-root
                      :state-after-root (:state-after/root roots)
                      :applications applications})]
    {:world world'
     :adjustments adjustments
     :roots roots
     :application application
     :transition (evm/build-transition
                  {:state-before-root (:state-before/root roots)
                   :allocation-root (:allocation/hash allocation)
                   :application-policy-root application-policy-root
                   :application-root (:application/root application)
                   :state-after-root (:state-after/root roots)})}))

(defn apply-pro-rata-held-credit
  "Applies a verified Sew add-held refinement exactly once per effect. `roots`
   are committed run-layer projections; this pure protocol layer does not invent
   world/ledger identities. Returns {:world ... :receipt ... :adjustments [...]}."
  [world allocation proposal refinement-artifact authorization roots]
  (when-not (refinement/refinement-valid? allocation proposal refinement-artifact)
    (throw (ex-info "invalid pro-rata effect refinement" {})))
  (let [before-count (count (:held-adjustments world))
        protocol-effects (:effects refinement-artifact)
        world' (reduce (fn [w {:keys [effect]}]
                         (accounting/add-held w (:effect/token effect) (:effect/amount effect)
                                              {:action "add-held" :reason (:held/kind effect)
                                               :account (:effect/account effect)
                                               :extra (select-keys effect [:owner/address :parameter/context :parameter/address])}))
                       world protocol-effects)
        adjustments (vec (drop before-count (:held-adjustments world')))
        actual-roots (application-roots world world' adjustments)
        _ (when-not (= actual-roots (select-keys roots (keys actual-roots)))
            (throw (ex-info "committed pro-rata application roots differ from observed state"
                            {:expected actual-roots :committed (select-keys roots (keys actual-roots))})))
        pairs (mapv (fn [entry adjustment]
                      {:effect/root (:effect/root entry)
                       :adjustment/root (effects/held-adjustment-root adjustment)})
                    protocol-effects adjustments)
        adjustment-refinement (application/applied-adjustment-refinement
                               (:protocol-effect-set/root refinement-artifact)
                               (:applied-adjustments/root roots) pairs)
        receipt (application/applied-receipt
                 {:authorization authorization
                  :state-before-root (:state-before/root roots)
                  :state-after-root (:state-after/root roots)
                  :executed-effect-set-root (:protocol-effect-set/root refinement-artifact)
                  :protocol-effects protocol-effects
                  :applied-adjustments adjustments
                  :applied-adjustment-refinement adjustment-refinement
                  :ledger-before-root (:ledger-before/root roots)
                  :ledger-after-root (:ledger-after/root roots)})]
    {:world world' :adjustments adjustments :receipt receipt}))

(defn apply-effects-to-world
  "Apply a vector of protocol effects to `world` via accounting/add-held.
   Returns the resulting world-state map. Pure: does not mutate anything."
  [world protocol-effects]
  (reduce (fn [w {:keys [effect]}]
            (accounting/add-held w (:effect/token effect) (:effect/amount effect)
                                 {:action "add-held" :reason (:held/kind effect)
                                  :account (:effect/account effect)
                                  :extra (select-keys effect [:owner/address :parameter/context :parameter/address])}))
          world protocol-effects))

(defn application-transition-valid?
  "Re-derive state/ledger roots from world-before + protocol-effects + adjustments
   and compare to the roots committed in the receipt. Does NOT mutate
   `receipt-valid?` — that remains the self-integrity check. Returns true only
   when all four committed roots match the re-derived set.

   `committed-roots` is the map of committed roots (e.g. the `roots` argument to
   `apply-pro-rata-held-credit`, or the receipt's `/root` fields)."
  [world protocol-effects adjustments committed-roots]
  (let [world' (apply-effects-to-world world protocol-effects)
        actual-roots (application-roots world world' adjustments)
        committed-state-before-root (:state-before/root committed-roots)
        committed-state-after-root (:state-after/root committed-roots)
        committed-ledger-before-root (:ledger-before/root committed-roots)
        committed-ledger-after-root (:ledger-after/root committed-roots)
        derived-state-before-root (:state-before/root actual-roots)
        derived-state-after-root (:state-after/root actual-roots)
        derived-ledger-before-root (:ledger-before/root actual-roots)
        derived-ledger-after-root (:ledger-after/root actual-roots)]
    (and (= committed-state-before-root derived-state-before-root)
         (= committed-state-after-root derived-state-after-root)
         (= committed-ledger-before-root derived-ledger-before-root)
         (= committed-ledger-after-root derived-ledger-after-root))))

(defn verify-applied-application
  "Compose the three assurance propositions into one authoritative verdict:

        receipt integrity  (self-integrity of the applied-effect-receipt)
        + transition derivation  (re-derived state + ledger roots match committed)
        + authorization evidence  (authorization artifact self-validates)
        = authoritative state-after

  Does NOT expand `receipt-valid?` — that remains the self-integrity check.
  Returns {:valid? bool :receipt-valid? ... :transition-valid? ... :authorization-valid? ...}."
  [receipt authorization world protocol-effects adjustments]
  (let [receipt-ok (application/receipt-valid? receipt)
        auth-ok (application/authorization-valid? authorization)
        committed-roots (select-keys receipt [:state-before/root :state-after/root
                                              :ledger-before/root :ledger-after/root])
        transition-ok (application-transition-valid? world protocol-effects adjustments committed-roots)]
    {:valid? (and receipt-ok auth-ok transition-ok)
     :receipt-valid? receipt-ok
     :authorization-valid? auth-ok
     :transition-valid? transition-ok}))
