(ns resolver-sim.validation.strategic-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.validator-descriptor :as vd]
            [resolver-sim.validation.strategic-registry :as sr]))

(def valid-descriptor
  {:validator/schema vd/validator-schema
   :validator/id :test/validator
   :validator/version 1
   :validator/kind :deviation-resistance
   :validator/validation-class :validation.class/deviation-resistance
   :validator/epistemic-contract {:claim-strength :bounded-falsification
                                  :universal-claim? false
                                  :falsification? true
                                  :limitations [:bounded-domain]}})

(deftest validator-registry-composes-sources-and-roots
  (let [reg (sr/build-validator-registry
             :sources [{:origin :framework :entries [valid-descriptor]}
                       {:origin :protocol
                        :entries [(assoc valid-descriptor
                                         :validator/id :protocol/validator
                                         :validator/execution-model {:horizon :multi-epoch})]}]
             :executables {:test/validator (fn [x] x)})]
    (testing "registry carries committed entries, id index, executables, root"
      (is (= 2 (count (:entries reg))))
      (is (= :test/validator (:validator/id (sr/resolve-validator reg :test/validator))))
      (is (= :protocol/validator (:validator/id (sr/resolve-validator reg :protocol/validator))))
      (is (fn? (sr/resolve-validator-executable reg :test/validator)))
      (is (re-matches #"[0-9a-f]{64}" (:root reg)))
      (is (= [:protocol/validator :test/validator] (sr/validator-ids reg))))))

(deftest validator-registry-is-order-and-source-independent
  (let [reg-a (sr/build-validator-registry
               :sources [{:origin :framework :entries [valid-descriptor]}
                         {:origin :protocol :entries [(assoc valid-descriptor
                                                             :validator/id :protocol/validator)]}])
        reg-b (sr/build-validator-registry
               :sources [{:origin :protocol :entries [(assoc valid-descriptor
                                                             :validator/id :protocol/validator)]}
                         {:origin :framework :entries [valid-descriptor]}])]
    (is (= (:root reg-a) (:root reg-b)))))

(deftest duplicate-validator-id-with-different-identity-fails-closed
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Duplicate validator id with different identity"
       (sr/build-validator-registry
        :sources [{:origin :framework :entries [valid-descriptor]}
                  {:origin :protocol :entries [(assoc valid-descriptor :validator/version 2)]}]))))

(deftest identical-re-registration-is-idempotent
  (let [reg (sr/build-validator-registry
             :sources [{:origin :framework :entries [valid-descriptor]}
                       {:origin :protocol :entries [valid-descriptor]}])]
    (is (= 1 (count (:entries reg)))
        "identical committed identity across sources is a single registry entry")))

(deftest invalid-descriptor-is-rejected-at-build-time
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Invalid game-theoretic validator descriptor"
       (sr/build-validator-registry
        :sources [{:origin :framework :entries [(dissoc valid-descriptor
                                                        :validator/epistemic-contract)]}]))))

(deftest entry-registry-supports-claims-contracts-generators
  (testing "generic entry registry keys by the declared id-key and roots"
    (let [claim-reg (sr/build-entry-registry :claim/id "PRF_STRATEGIC_CLAIM_REGISTRY_V1"
                                             :sources [{:origin :framework :entries [{:claim/id :claim/a
                                                                                      :mechanism-levels [:allocation/partial-fill]}]}])
          contract-reg (sr/build-entry-registry :contract/id "PRF_DEVIATION_CONTRACT_REGISTRY_V1"
                                                :sources [{:origin :framework :entries [{:contract/id :contract/a
                                                                                         :deviation-generators [:split]}]}])]
      (is (= :claim/a (:claim/id (sr/resolve-entry claim-reg :claim/a))))
      (is (= :contract/a (:contract/id (sr/resolve-entry contract-reg :contract/a))))
      (is (re-matches #"[0-9a-f]{64}" (:root claim-reg)))
      (is (re-matches #"[0-9a-f]{64}" (:root contract-reg)))
      (is (= (:root claim-reg)
             (:root (sr/build-entry-registry :claim/id "PRF_STRATEGIC_CLAIM_REGISTRY_V1"
                                             :sources [{:origin :framework :entries [{:claim/id :claim/a
                                                                                      :mechanism-levels [:allocation/partial-fill]}]}])))))))

(deftest duplicate-entry-id-with-different-identity-fails-closed
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Duplicate registry entry id with different identity"
       (sr/build-entry-registry :claim/id "PRF_STRATEGIC_CLAIM_REGISTRY_V1"
                                :sources [{:origin :framework :entries [{:claim/id :claim/a} {:claim/id :claim/a :extra 1}]}]))))

(deftest entry-registry-requires-id-key
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"missing id key"
       (sr/build-entry-registry :claim/id "PRF_STRATEGIC_CLAIM_REGISTRY_V1"
                                :sources [{:origin :framework :entries [{:not-an-id true}]}]))))

(deftest entry-registry-committed-fn-projects-root-but-keeps-raw-resolution
  (testing ":committed-fn projects entries for rooting while :by-id keeps the
            raw entries, so resolution returns the original committed data"
    (let [raw {:claim/id :claim/a
               :manifest (fn [] "resolved/path.edn")
               :tags #{:x :y}}
          reg (sr/build-entry-registry :claim/id "PRF_TEST"
                                       :sources [{:origin :framework :entries [raw]}]
                                       :committed-fn (fn [e]
                                                       (-> e
                                                           (update :manifest (fn [f] (str (f))))
                                                           (update :tags #(vec (sort-by str %))))))]
      (testing "resolution returns the raw entry (with fn and set intact)"
        (let [resolved (sr/resolve-entry reg :claim/a)]
          (is (= raw resolved))))
      (testing "the root is stable and commits the projected form"
        (is (re-matches #"[0-9a-f]{64}" (:root reg)))
        (is (= (:root reg)
               (:root (sr/build-entry-registry :claim/id "PRF_TEST"
                                               :sources [{:origin :framework :entries [raw]}]
                                               :committed-fn (fn [e]
                                                               (-> e
                                                                   (update :manifest (fn [f] (str (f))))
                                                                   (update :tags #(vec (sort-by str %)))))))))))))