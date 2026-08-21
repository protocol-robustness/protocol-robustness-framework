(ns resolver-sim.assurance.governed-authority-consumer
  "Reusable consumer boundary for production users of governed researcher
   authority. It resolves only context-owned inputs and binds roots from a
   freshly recomputed report; event payloads are deliberately absent."
  (:require [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]))

(defn- resolved-artifact [resolver reference]
  (when (and (fn? resolver) (some? reference))
    (try (resolver reference) (catch Exception _ nil))))

(defn verify-governed-authority
  "Return canonical governed authority bindings, or {:valid? false}. `context`
   must provide :researcher-force-authorisation-governed-authority-context-resolver.
   Its result contains only trusted control-plane values: :review-round,
   :review-governance, :position-time-resolver, and :governance-current?."
  [context authorisation round-hash]
  (let [resolved (resolved-artifact
                  (:researcher-force-authorisation-governed-authority-context-resolver context)
                  round-hash)
        review-round (:review-round resolved)
        governance (:review-governance resolved)
        signature-valid? (fn [position]
                           (true? (:valid?
                                   (rfa/verify-decision-signatures
                                    (:researcher-public-key-resolver context)
                                    (assoc authorisation :authorisation/decision-references [position])))))]
    (try
      (if (and (:resolved? resolved)
               (= round-hash (:review-round/hash review-round))
               governance
               (fn? (:position-time-resolver resolved))
               (fn? (:governance-current? resolved)))
        (let [report (authority/evaluate-governed-authority
                      :authorisation authorisation
                      :review-round review-round
                      :governance governance
                      :position-time-resolver (:position-time-resolver resolved)
                      :governance-current? (:governance-current? resolved)
                      :signature-valid? signature-valid?)]
          {:valid? (= :authorised (:authority-status report))
           :authority-report report
           :authority-report-root (authority/authority-report-root report)
           :governance-root (:governance-root report)
           :governed-review-round-hash (:review-round/hash review-round)})
        {:valid? false})
      (catch Exception _ {:valid? false}))))
