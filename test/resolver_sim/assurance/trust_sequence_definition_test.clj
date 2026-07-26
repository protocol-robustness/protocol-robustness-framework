(ns resolver-sim.assurance.trust-sequence-definition-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]))

(def valid-provider
  {:protocol/id :protocol/sew
   :protocol/version "1"})

(def valid-steps
  [{:step/id :prf.step/authorisation-granted
    :step/type :assertion
    :step/policy-requirement
    {:policy/id :sew.policy/force-authorisation
     :policy/version 1}}
   {:step/id :prf.step/authorised-execution
    :step/type :assertion
    :step/policy-requirement
    {:policy/id :sew.policy/force-authorisation
     :policy/version 1}}
   {:step/id :prf.step/authorised-consumption-custody-adjustment
    :step/type :state-transition
    :step/policy-requirement
    {:policy/id :sew.policy/force-authorisation
     :policy/version 1}}])

(deftest build-valid-definition
  (let [defn (tsd/build-definition
              {:id :sew.sequence/force-authorised-custody-adjustment
               :provider valid-provider
               :steps valid-steps})]
    (is (some? (:trust-sequence-definition/root defn)))
    (is (= 1 (:trust-sequence-definition/schema-version defn)))
    (is (= :sew.sequence/force-authorised-custody-adjustment
           (:trust-sequence-definition/id defn)))
    (is (= 3 (count (:trust-sequence-definition/steps defn))))))

(deftest validate-valid-definition
  (let [defn (tsd/build-definition
              {:id :sew.sequence/force-authorised-custody-adjustment
               :provider valid-provider
               :steps valid-steps})
        result (tsd/validate-definition defn)]
    (is (:valid? result))
    (is (= :valid (:status result)))
    (is (nil? (:errors result)))))

(deftest validate-rejects-bare-policy-id
  (let [steps [(assoc (first valid-steps)
                      :step/policy-requirement
                      {:policy/id :force-authorisation
                       :policy/version 1})]
        defn (tsd/build-definition
              {:id :sew.sequence/force-authorised-custody-adjustment
               :provider valid-provider
               :steps steps})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))
    (is (some #(re-find #"must be a qualified keyword" %) (:errors result)))))

(deftest validate-rejects-missing-policy-version
  (let [steps [(assoc (first valid-steps)
                      :step/policy-requirement
                      {:policy/id :sew.policy/force-authorisation
                       :policy/version 0})]
        defn (tsd/build-definition
              {:id :sew.sequence/force-authorised-custody-adjustment
               :provider valid-provider
               :steps steps})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))
    (is (some #(re-find #"positive integer" %) (:errors result)))))

(deftest validate-rejects-duplicate-step-ids
  (let [steps [(first valid-steps) (first valid-steps)]
        defn (tsd/build-definition
              {:id :sew.sequence/test
               :provider valid-provider
               :steps (vec steps)})
        defn (tsd/build-definition
              {:id :sew.sequence/test
               :provider valid-provider
               :steps steps})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))
    (is (some #(re-find #"must be unique" %) (:errors result)))))

(deftest validate-rejects-missing-provider
  (let [defn (tsd/build-definition
              {:id :sew.sequence/test
               :provider {}
               :steps valid-steps})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))))

(deftest validate-rejects-unqualified-protocol-id
  (let [defn (tsd/build-definition
              {:id :sew.sequence/test
               :provider {:protocol/id :sew :protocol/version "1"}
               :steps valid-steps})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))
    (is (some #(re-find #"qualified keyword" %) (:errors result)))))

(deftest validate-rejects-empty-steps
  (let [defn (tsd/build-definition
              {:id :sew.sequence/test
               :provider valid-provider
               :steps []})
        result (tsd/validate-definition defn)]
    (is (not (:valid? result)))
    (is (some #(re-find #"at least one step" %) (:errors result)))))

(deftest root-changes-when-steps-change
  (let [defn-a (tsd/build-definition
                {:id :sew.sequence/test
                 :provider valid-provider
                 :steps [(first valid-steps)]})
        defn-b (tsd/build-definition
                {:id :sew.sequence/test
                 :provider valid-provider
                 :steps (vec (take 2 valid-steps))})]
    (is (not= (:trust-sequence-definition/root defn-a)
              (:trust-sequence-definition/root defn-b)))))

(deftest root-deterministic
  (let [defn-a (tsd/build-definition
                {:id :sew.sequence/test
                 :provider valid-provider
                 :steps valid-steps})
        defn-b (tsd/build-definition
                {:id :sew.sequence/test
                 :provider valid-provider
                 :steps valid-steps})]
    (is (= (:trust-sequence-definition/root defn-a)
           (:trust-sequence-definition/root defn-b)))))

(deftest source-definition-loads-and-validates
  (let [src (clojure.edn/read-string
             (slurp "data/sequences/force-authorised-custody-adjustment.edn"))
        defn (tsd/build-definition
              {:id (:trust-sequence-definition/id src)
               :provider (:trust-sequence-definition/provider src)
               :steps (:trust-sequence-definition/steps src)})
        result (tsd/validate-definition defn)]
    (is (:valid? result))
    (is (= :sew.sequence/force-authorised-custody-adjustment
           (:trust-sequence-definition/id defn)))))