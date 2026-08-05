(ns resolver-sim.economics.with-bounty.transition-evidence
  "Content-addressed transition evidence binding a with-bounty application
   plan to the resulting protocol transition (ADR-0006 D3/D5).

   The evidence binds the generic effect root, the combined effect-set root,
   the application-plan root, the world before/after roots, and the custody /
   payable / backing artifacts produced by the protocol adapter. It is generic:
   the protocol adapter supplies the world and artifact roots; this namespace
   owns the committed projection and hash."
  (:require [resolver-sim.hash.canonical :as hc]))

(def transition-domain-tag
  :with-bounty-transition-evidence-v1)

(def transition-projection-fields
  [:transition/type
   :plan/root
   :effect-root
   :combined-effect-root
   :world-before-root
   :world-after-root
   :payable/roots
   :backing/roots
   :custody/adjustment-roots
   :idempotent?
   :context])

(defn transition-hash
  [evidence]
  (hc/domain-hash transition-domain-tag
                  (select-keys evidence transition-projection-fields)))

(defn build-transition-evidence
  "Assemble a with-bounty transition evidence record from a plan and the
   adapter-produced roots."
  [{:keys [plan effect-root world-before-root world-after-root
           payable-roots backing-roots custody-adjustment-roots
           idempotent? context]}]
  (let [base {:transition/type :with-bounty/apply
              :plan/root (:plan/hash plan)
              :effect-root effect-root
              :combined-effect-root (:plan/combined-effect-root plan)
              :world-before-root world-before-root
              :world-after-root world-after-root
              :payable/roots (vec payable-roots)
              :backing/roots (vec backing-roots)
              :custody/adjustment-roots (vec custody-adjustment-roots)
              :idempotent? (boolean idempotent?)
              :context (or context {})}
        h (transition-hash base)]
    (assoc base :transition/hash h)))

(defn validate-transition-evidence
  [evidence]
  (let [custody-roots (:custody/adjustment-roots evidence [])
        bad-custody (into []
                          (keep (fn [c]
                                  (when-not (and (map? c)
                                                 (string? (:held-adjustment/id c))
                                                 (string? (:artifact/hash c)))
                                    c)))
                          custody-roots)
        errors (cond-> []
                 (not= :with-bounty/apply (:transition/type evidence))
                 (conj :invalid-transition-type)
                 (not (string? (:plan/root evidence)))
                 (conj :missing-plan-root)
                 (not (string? (:effect-root evidence)))
                 (conj :missing-effect-root)
                 (not (string? (:world-before-root evidence)))
                 (conj :missing-world-before-root)
                 (not (string? (:world-after-root evidence)))
                 (conj :missing-world-after-root)
                 (seq bad-custody)
                 (conj [:unbound-custody-artifacts bad-custody]))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-transition-evidence
  [evidence]
  (let [v (validate-transition-evidence evidence)]
    (if-not (:valid? v)
      v
      (let [computed (transition-hash evidence)
            stored (:transition/hash evidence)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false :errors [:hash-mismatch]
           :computed computed :stored stored})))))
