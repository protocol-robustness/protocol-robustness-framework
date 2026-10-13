(ns resolver-sim.protocols.sew.dispute-ruling-finality
  "Derived V1 finality for authenticated SEW rulings.

   This is not a context-free proof: validation independently reproduces the
   artifact against the authoritative replayable SEW world."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.adjudication-authentication-verification :as authentication]
            [resolver-sim.protocols.sew.adjudication-instance :as instance]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]
            [resolver-sim.protocols.sew.types :as t]))

(def ^:const schema :prf/dispute-ruling-finality.v1)
(def ^:const domain :prf-dispute-ruling-finality-v1)
(def ^:const reason :authoritative-final-round)

(def ^:private root-key :finality/root)
(def ^:private required-fields
  #{:artifact/schema
    :finality/ruling-root
    :finality/authentication-verification-root
    :finality/case-root
    :finality/adjudication-instance-root
    :finality/workflow-id
    :finality/round-id
    :finality/reason})
(def ^:private reference-fields
  #{:finality/ruling-root
    :finality/authentication-verification-root
    :finality/case-root
    :finality/adjudication-instance-root})

(defn finality-payload [finality]
  (dissoc finality root-key))

(defn finality-errors [finality]
  (if-not (map? finality)
    [:invalid-dispute-ruling-finality]
    (let [have (set (keys finality))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-dispute-ruling-finality-field)
        (not= schema (:artifact/schema finality)) (conj :invalid-schema)
        (not= reason (:finality/reason finality)) (conj :invalid-finality-reason)
        (not (and (integer? (:finality/workflow-id finality))
                  (not (neg? (:finality/workflow-id finality)))))
        (conj :invalid-finality-workflow-id)
        (not (and (integer? (:finality/round-id finality))
                  (not (neg? (:finality/round-id finality)))))
        (conj :invalid-finality-round-id)
        (not (every? #(ref/valid-sha256-ref? (get finality %)) reference-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get finality root-key)))
        (conj :invalid-finality-root)
        (and (empty? missing)
             (empty? extra)
             (every? #(ref/valid-sha256-ref? (get finality %)) reference-fields)
             (ref/valid-sha256-ref? (get finality root-key))
             (not= (get finality root-key)
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (finality-payload finality))))))
        (conj :finality-root-mismatch)))))

(defn validate-finality [finality]
  (let [errors (finality-errors finality)]
    {:valid? (empty? errors) :errors errors}))

(defn- authoritative-lineage [world workflow-id]
  (let [round-id (t/dispute-level world workflow-id)
        instance-root (get-in world [:adjudication-current-instance-root workflow-id])
        adjudication-instance (get-in world [:adjudication-instances workflow-id instance-root])
        published-case (get-in world [:adjudication-published-cases workflow-id round-id])]
    {:round-id round-id
     :instance-root instance-root
     :instance adjudication-instance
     :case-root (:case/root published-case)}))

(defn derive-finality
  "Derive V1 finality from the authoritative current SEW execution context.
   Returns a finality artifact or nil when the ruling is not terminally eligible."
  [world workflow-id dispute-ruling authentication-verification]
  (let [{:keys [round-id instance-root instance case-root]}
        (authoritative-lineage world workflow-id)]
    (when (and (t/valid-workflow-id? world workflow-id)
               (:valid? (ruling/validate-dispute-ruling dispute-ruling))
               (:valid? (authentication/validate-authentication-verification
                          authentication-verification))
               (:valid? (instance/validate-instance instance))
               (= workflow-id (:workflow/id instance))
               (= round-id (:adjudication/round-id instance))
               (= case-root (:case/root instance))
               (= instance-root (:ruling/adjudication-instance-root dispute-ruling))
               (= case-root (:ruling/case-root dispute-ruling))
               (= round-id (:ruling/round dispute-ruling))
               (= (:ruling/root dispute-ruling)
                  (:authentication-verification/ruling-root authentication-verification))
               (= (:ruling/authentication-root dispute-ruling)
                  (:authentication-verification/authentication-root authentication-verification))
               (= instance-root
                  (:authentication-verification/adjudication-instance-root
                   authentication-verification))
               (= :authenticated (:authentication-verification/result
                                  authentication-verification))
               (contains? #{:release :refund} (:ruling/outcome dispute-ruling))
               (t/final-round? world workflow-id))
      (let [base {:artifact/schema schema
                  :finality/ruling-root (:ruling/root dispute-ruling)
                  :finality/authentication-verification-root
                  (:authentication-verification/root authentication-verification)
                  :finality/case-root case-root
                  :finality/adjudication-instance-root instance-root
                  :finality/workflow-id workflow-id
                  :finality/round-id round-id
                  :finality/reason reason}]
        (assoc base root-key
               (ref/sha256-ref
                (hc/domain-hash domain
                                (hc/project-canonical-safe base))))))))

(defn validate-finality-against-context
  "Validate canonical integrity, then independently derive the expected artifact
   from authoritative SEW state and require exact equality."
  [finality world workflow-id dispute-ruling authentication-verification]
  (let [own (validate-finality finality)
        expected (derive-finality world workflow-id dispute-ruling authentication-verification)
        lineage (authoritative-lineage world workflow-id)
        errors (cond-> (vec (:errors own))
                 (nil? finality) (conj :finality-missing)
                 (and finality
                      (not= (:finality/ruling-root finality) (:ruling/root dispute-ruling)))
                 (conj :finality-ruling-binding-mismatch)
                 (and finality
                      (not= (:finality/authentication-verification-root finality)
                            (:authentication-verification/root authentication-verification)))
                 (conj :finality-authentication-binding-mismatch)
                 (and finality
                      (or (not= (:finality/case-root finality) (:case-root lineage))
                          (not= (:finality/adjudication-instance-root finality)
                                (:instance-root lineage))))
                 (conj :finality-adjudication-lineage-mismatch)
                 (and finality
                      (or (not= (:finality/workflow-id finality) workflow-id)
                          (not= (:finality/round-id finality) (:round-id lineage))))
                 (conj :finality-workflow-round-mismatch)
                 (not (t/final-round? world workflow-id))
                 (conj :authoritative-final-round-not-satisfied)
                 (= :refuse (:ruling/outcome dispute-ruling))
                 (conj :unsupported-finality-outcome)
                 (nil? expected)
                 (conj :finality-not-derivable)
                 (and expected (not= finality expected))
                 (conj :finality-reconstruction-mismatch))]
    {:valid? (empty? errors) :errors errors :expected expected}))
