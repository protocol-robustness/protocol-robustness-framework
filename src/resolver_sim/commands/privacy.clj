(ns resolver-sim.commands.privacy
  "Offline inspect, evaluate, and independently verify privacy artifacts."
  (:require [resolver-sim.commands.document :as doc]
            [resolver-sim.privacy.policy :as policy]))

(def ^:private evaluation-fields
  #{:policies :request :classification-report :classification-attestation
    :classification-trust-policy :evidence-attestations :evidence-trust-policy})

(defn- evaluation-input [input]
  (doc/closed! input evaluation-fields #{:sentinel-decision :sentinel-trust-policy})
  (let [policies (:policies input)
        result (policy/evaluate policies (:request input)
                                (select-keys input
                                             [:classification-report
                                              :classification-attestation
                                              :classification-trust-policy
                                              :evidence-attestations
                                              :evidence-trust-policy
                                              :sentinel-decision
                                              :sentinel-trust-policy]))]
    result))

(defn policy-inspect [opts]
  (doc/execute
   opts
   (fn [input]
     (doc/closed! input #{:policy} #{})
     (let [result (policy/validate-policy (:policy input))
           artifact (:policy input)]
       (doc/emit opts
                 {:schema "stablecoin-privacy-policy-inspection.v1"
                  :valid? (:valid? result)
                  :errors (:errors result)
                  :policy/id (:privacy-policy/id artifact)
                  :policy/version (:privacy-policy/version artifact)
                  :policy/root (:privacy-policy/root artifact)
                  :authority-status :unverified
                  :currentness :unknown}
                 (if (:valid? result) 0 1))))))

(defn evaluate [opts]
  (doc/execute
   opts
   (fn [input]
     (let [result (evaluation-input input)
           code (case (:privacy-evaluation/decision result)
                  :allow 0 :deny 1 :indeterminate 2 2)]
       (doc/emit opts result code)))))

(defn verify [opts]
  (doc/execute
   opts
   (fn [input]
     (doc/closed! input (conj evaluation-fields :evaluation) #{:sentinel-decision
                                                               :sentinel-trust-policy})
     (let [supplied (:evaluation input)
           reconstructed (evaluation-input (dissoc input :evaluation))
           valid? (and (policy/verify-evaluation supplied)
                       (= supplied reconstructed))]
       (doc/emit opts
                 {:schema "stablecoin-privacy-evaluation-verification.v1"
                  :valid? (boolean valid?)
                  :evaluation/root (:privacy-evaluation/root supplied)
                  :reason (when-not valid? :evaluation-reconstruction-mismatch)}
                 (if valid? 0 1))))))
