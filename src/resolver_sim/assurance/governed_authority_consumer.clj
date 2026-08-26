(ns resolver-sim.assurance.governed-authority-consumer
  "Reusable consumer boundary for production users of governed researcher
   authority. It resolves only context-owned inputs and binds roots from a
   freshly recomputed report; event payloads are deliberately absent."
  (:require [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]))

(defn- resolved-artifact
  [resolver reference]
  (when (and (fn? resolver) (some? reference))
    (try (resolver reference) (catch Exception _ nil))))

(defn verify-governed-authority-current
  "Authoritative V2-only consumer. Uses store-authenticated B3 material and a
   store-issued fence; it never consults legacy resolver, key, or time callbacks."
  [authority-store basis authorisation]
  (let [resolved (authority-state/resolve-authority-material authority-store basis)]
    (if-not (:resolved? resolved)
      {:valid? false :reason (:reason resolved)}
      (let [material (:authenticated-material resolved)
            report (authority-state/evaluate-authority-with-frozen-material
                    {:authorisation authorisation
                     :review-round (:authority-material/review-round material)
                     :review-governance (:authority-material/review-governance material)
                     :position-time-index (:authority-material/position-time-index material)
                     :signer-key-set (:authority-material/signer-key-set material)})]
        {:valid? (= :authorised (:authority-status report))
         :authority-report report
         :authority-report-root (authority/authority-report-root report)
         :governance-root (:governance-root report)
         :governed-review-round-hash (get-in authorisation [:authorisation/review-round :review-round/hash])
         :resolved-review-authority-context (:context resolved)
         :authority-fence (:fence resolved)}))))

(defn finalise-governed-authority-current!
  "C2 finalization boundary. Only a fence returned by the V2 current consumer is
   accepted; publication, binding, and fence retirement remain store-atomic."
  [authority-store authority-result binding successor-envelope successor-material]
  (if-not (:authority-fence authority-result)
    {:finalised? false :reason :missing-authority-fence}
    (authority-state/finalise-under-authority-fence!
     authority-store (:authority-fence authority-result) binding
     successor-envelope successor-material)))

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
