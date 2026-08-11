(ns resolver-sim.protocols.sew.pro-rata-application
  "Batch application boundary for a fully authorized Sew pro-rata held credit."
  (:require [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.pro-rata.application :as application]
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
