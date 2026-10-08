(ns resolver-sim.research.accounting-arithmetic-sp1-host-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.research.accounting-arithmetic-sp1-host :as host]
            [resolver-sim.research.accounting-proof-statement :as statement]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.native-accounting-work-test :as fixture]))

(deftest proof-witness-is-derived-only-from-validated-work-and-execution
  (let [left-work (:work (fixture/work-fixture 100 301))
        right-work (:work (fixture/work-fixture 100 302))
        left-execution (native/execute-work! left-work)
        right-execution (native/execute-work! right-work)
        left (host/derive-authoritative-proof-input left-work left-execution)
        right (host/derive-authoritative-proof-input right-work right-execution)]
    (is (= (:witness left) (:witness right)))
    (is (= (:statement left) (:statement right)))
    (is (= (:canonical-accounting-partial/root (:statement left))
           (:canonical-accounting-partial/root (:statement right))))
    (is (not= (native/execution-root left-execution)
              (native/execution-root right-execution)))
    (testing "a run from another native work item cannot be substituted"
      (is (thrown? clojure.lang.ExceptionInfo
                   (host/derive-authoritative-proof-input left-work right-execution))))
    (testing "the host API has no slot for caller-supplied roots"
      (is (thrown? clojure.lang.ArityException
                   (apply host/derive-authoritative-proof-input
                          [left-work left-execution
                           {:canonical-accounting-partial/root "substitute"}]))))))

(deftest structural-lineage-is-not-arithmetic-validation
  (let [{:keys [work]} (fixture/work-fixture 100 301)
        good (native/execute-work! work)
        false-values (assoc (:accounting/values (:accounting-partial good))
                            :accounting/bond 999)
        runtime (select-keys (:work/execution-environment work)
                             [:runtime/clojure-version :runtime/jvm-version
                              :environment/arithmetic])
        forged (#'native/derive-evidence work false-values runtime)]
    (is (= forged (native/validate-execution-lineage work forged)))
    (is (thrown? clojure.lang.ExceptionInfo (native/validate-execution work forged)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (statement/proof-input
                  (:accounting/request work) (accounting/build-reduction-contract)
                  (:accounting/partition-plan work) (:accounting-partial forged))))
    (is (= "research-accounting-arithmetic-statement.v1"
           (get (statement/proof-input-for-verification
                 (:accounting/request work)
                 (accounting/build-reduction-contract)
                 (:accounting/partition-plan work) (:accounting-partial forged))
                "schema")))))

(deftest proof-verifier-rejects-cross-assignment-execution-before-reading-proof
  (let [left-work (:work (fixture/work-fixture 100 301))
        right-work (:work (fixture/work-fixture 100 302))
        right-execution (native/execute-work! right-work)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (host/verify-existing-proof!
                  {:work left-work :execution right-execution
                   :proof-file "/does/not/exist/proof.bin"
                   :metadata-file "/does/not/exist/metadata.json"})))))
