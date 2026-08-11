(ns resolver-sim.pro-rata.refinement
  "Bijective refinement from generic pro-rata effects to protocol effects."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.evidence :as evidence]))

(def schema-version "pro-rata-effect-refinement.v1")
(def sew-held-credit-profile :sew/pro-rata-held-credit.v1)

(defn- source-root [source]
  (hc/domain-hash "PRO_RATA_GENERIC_EFFECT_V1" source))

(defn- effect-root [effect]
  (hc/domain-hash "PROTOCOL_EFFECT_V1" effect))

(defn sew-add-held-refinement
  "Refines each generic proposed effect exactly once into a Sew add-held effect.
   `effect-fields` is keyed by generic :effect/id and supplies protocol-specific
   token/account/reason/attribution; amount and source identity are derived."
  [allocation proposal effect-fields]
  (when-not (evidence/proposed-effects-valid? allocation proposal)
    (throw (ex-info "invalid generic proposed-effects witness" {})))
  (let [effects (mapv (fn [source]
                        (let [extra (get effect-fields (:effect/id source))
                              effect (merge {:effect/type :custody/held-adjustment
                                             :effect/contract :prf.effect/custody-held-adjustment.v2
                                             :effect/action "add-held"
                                             :effect/amount (:amount source)} extra)]
                          {:source/effect-id (:effect/id source)
                           :source/effect-root (source-root source)
                           :effect/root (effect-root effect)
                           :effect effect}))
                      (:effects proposal))
        base {:schema-version schema-version :protocol/id :sew
              :profile/id sew-held-credit-profile
              :allocation/hash (:allocation/hash proposal)
              :proposed-effects/root (:proposed-effects/root proposal)
              :effects effects}]
    (assoc base :protocol-effect-set/root (hc/domain-hash :pro-rata-effect-refinement base))))

(defn refinement-violations
  "Proves total, unique one-to-one refinement and the Sew add-held profile."
  [allocation proposal refinement]
  (let [sources (:effects proposal) rs (:effects refinement)
        expected-ids (set (map :effect/id sources))
        ids (map :source/effect-id rs)
        base (dissoc refinement :protocol-effect-set/root)]
    (vec (concat
          (when-not (evidence/proposed-effects-valid? allocation proposal) [{:reason :pro-rata/invalid-generic-proposal}])
          (when-not (= schema-version (:schema-version refinement)) [{:reason :pro-rata/unsupported-refinement-schema}])
          (when-not (= sew-held-credit-profile (:profile/id refinement)) [{:reason :pro-rata/wrong-refinement-profile}])
          (when-not (= expected-ids (set ids)) [{:reason :pro-rata/refinement-not-total}])
          (when-not (= (count ids) (count (distinct ids))) [{:reason :pro-rata/refinement-not-unique}])
          (mapcat (fn [entry]
                    (let [effect-id (:source/effect-id entry)
                          source (first (filter #(= (:effect/id %) effect-id) sources))
                          protocol-effect (:effect entry)]
                      (concat
                       (when-not (= (source-root source) (:source/effect-root entry)) [{:reason :pro-rata/source-root-mismatch :effect-id effect-id}])
                       (when-not (= (:amount source) (:effect/amount protocol-effect)) [{:reason :pro-rata/refinement-amount-mismatch :effect-id effect-id}])
                       (when-not (and (= :custody/held-adjustment (:effect/type protocol-effect))
                                      (= :prf.effect/custody-held-adjustment.v2 (:effect/contract protocol-effect))
                                      (= "add-held" (:effect/action protocol-effect))) [{:reason :pro-rata/not-sew-add-held :effect-id effect-id}])
                       (when-not (= (effect-root protocol-effect) (:effect/root entry)) [{:reason :pro-rata/effect-root-mismatch :effect-id effect-id}])) ) rs)
          (when-not (= (:protocol-effect-set/root refinement) (hc/domain-hash :pro-rata-effect-refinement base)) [{:reason :pro-rata/refinement-root-mismatch}]))))))

(defn refinement-valid? [allocation proposal refinement]
  (empty? (refinement-violations allocation proposal refinement)))
