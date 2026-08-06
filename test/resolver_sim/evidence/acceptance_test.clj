(ns resolver-sim.evidence.acceptance-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.acceptance :as acceptance]
            [resolver-sim.evidence.artifact :as artifact]))

(def ^:private schema-version "test.artifact.v1")
(def ^:private kind :test/artifact)
(def ^:private verifier "test.artifact.verifier.v1")

(defn- finalize-valid [body]
  (artifact/finalize-artifact
   (assoc body :schema-version schema-version
          :artifact/kind kind
          :artifact/verifier verifier)))

(defn- all-ok-stages [a]
  {:content-integrity     (artifact/verify-artifact a schema-version kind verifier)
   :registry-membership   {:valid? true :reason :registered :details {:id :x}}
   :required-chain        {:valid? true :reason :in-required-chain :details {}}
   :publisher-commitment  {:valid? true :reason :publisher-signature-valid :details {}}
   :file-integrity        {:valid? true :reason :hash-match :details {}}})

(deftest acceptance-report-composition
  (let [a (finalize-valid {:id 1})
        stages (all-ok-stages a)
        report (acceptance/acceptance-report stages)]
    (testing "all stages present and accepted"
      (is (true? (acceptance/accepted? report)))
      (is (true? (:accepted? report)))
      (is (every? #(contains? report %) acceptance/acceptance-stages)))
    (testing "content-integrity is derived from verify-artifact"
      (is (true? (:valid? (:content-integrity report)))))))

(deftest acceptance-report-fail-closed-on-missing-stage
  (let [a (finalize-valid {:id 1})
        stages (dissoc (all-ok-stages a) :publisher-commitment)
        report (acceptance/acceptance-report stages)]
    (is (false? (:accepted? report)))
    (is (= :stage-missing (:reason (:publisher-commitment report))))
    (is (false? (:valid? (:publisher-commitment report))))))

(deftest acceptance-report-distinguishes-stages
  (testing "content validity never implies publisher authenticity"
    (let [a (finalize-valid {:id 1})
          stages (assoc (all-ok-stages a)
                        :publisher-commitment
                        {:valid? false :reason :signature-invalid
                         :details {:key "k"}})
          report (acceptance/acceptance-report stages)]
      (is (true? (:valid? (:content-integrity report))))
      (is (false? (:valid? (:publisher-commitment report))))
      (is (false? (:accepted? report)))
      (is (= :signature-invalid (:reason (:publisher-commitment report))))))
  (testing "a tampered artifact fails content-integrity while other stages may hold"
    (let [tampered (assoc (finalize-valid {:id 1}) :artifact/hash "sha256:forged")
          stages (assoc (all-ok-stages tampered)
                        :content-integrity
                        (artifact/verify-artifact tampered schema-version kind verifier))
          report (acceptance/acceptance-report stages)]
      (is (= :content-hash-mismatch (:reason (:content-integrity report))))
      (is (false? (:accepted? report))))))

(deftest acceptance-report-normalizes-nil-stage
  (let [report (acceptance/acceptance-report {:content-integrity nil})]
    (is (false? (:accepted? report)))
    (is (= :stage-missing (:reason (:content-integrity report))))))
