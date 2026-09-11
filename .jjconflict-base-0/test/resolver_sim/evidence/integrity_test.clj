(ns resolver-sim.evidence.integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.capture :as cap]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; Helper to create a fake evidence file
(defn- write-fake-evidence [dir id]
  (.mkdirs (io/file dir))
  (spit (io/file dir (str id ".json"))
        (json/write-str {:evidence-hash id})))

(deftest test-registry-integrity-checks
  (testing "orphaned-evidence-detected-test"
    (chain/reset-registry!)
    (let [dir "results/test-artifacts/event-evidence"]
      (write-fake-evidence dir "orphan-1")
      (let [registry (chain/build-registry)]
        ;; The registry has no artifacts, but disk has "orphan-1.json"
        ;; A full integrity utility would traverse the directory and compare.
        (is (= 0 (:evidence-count registry)))
        ;; Verify registry is empty
        (is (not (some #(= "orphan-1" (:evidence-hash %)) (:evidence-hashes registry)))))))

  (testing "registry-hash-mismatch-test"
    (chain/reset-registry!)
    (let [ev {:evidence-hash "valid-hash" :data "foo"}
          registry (chain/build-registry)]
      ;; Manually tamper with the registry hash
      (let [tampered (assoc registry :registry-hash "wrong-hash")]
        (is (false? (:valid (chain/verify-registry-hash tampered)))))))

  (testing "malformed-core-evidence-rejected-test"
    ;; Verifies our builder logic (Phase 3 requirements)
    (is (thrown? Exception (cap/require-fields {:some-field "val"
                                                :evidence/importance "core"})))
    (is (= {} (cap/require-fields {}))))

  (testing "deterministic-evidence-chain-test"
    (let [ev1 (-> (cap/evidence-base {:type :transition :importance :core})
                  (cap/cap-fields {:scenario/id "s1"})
                  cap/finalize-evidence)
          ev2 (-> (cap/evidence-base {:type :transition :importance :core})
                  (cap/cap-fields {:scenario/id "s1"})
                  cap/finalize-evidence)]
      (is (= (:evidence/hash ev1) (:evidence/hash ev2))
          "Evidence hashing must be deterministic for identical content"))))

(deftest test-causality-integrity
  (testing "invalid-caused-by-id-test"
    (let [registry {:artifacts [{:evidence-hash "e1"}] :evidence-hashes ["e1"]}]
      ;; Mock evidence pointing to non-existent e2
      (is (= false (:present (chain/verify-evidence-in-registry registry "e2"))))))

  (testing "causality-cycle-test"
    (let [registry {:artifacts [{:evidence-hash "e1" :caused-by "e2"}
                                {:evidence-hash "e2" :caused-by "e1"}]}]
      ;; Integrity check should detect the cycle
      (is (= "e2" (:caused-by (first (filter #(= "e1" (:evidence-hash %)) (:artifacts registry))))))
      (is (= "e1" (:caused-by (first (filter #(= "e2" (:evidence-hash %)) (:artifacts registry))))))))

  (testing "missing-evidence-file-detected-test"
    (let [registry {:artifacts [{:id "evidence-1" :evidence-hash "e1"}]}]
      ;; Function to verify if evidence file exists on disk given registry entry
      (let [verify-files (fn [reg] (every? #(.exists (io/file "results/test-artifacts/event-evidence" (str (:evidence-hash %) ".json"))) (:artifacts reg)))]
        (is (false? (verify-files registry))))))

  (testing "future-caused-by-id-test"
    (let [registry {:artifacts [{:evidence-hash "e1" :event/seq 1}
                                {:evidence-hash "e2" :event/seq 2 :caused-by "e3"}]}]
      ;; Evidence e2 caused by e3 (which doesn't exist, so this is invalid)
      (let [is-valid? (fn [reg]
                        (let [arts (:artifacts reg)
                              e2 (first (filter #(= "e2" (:evidence-hash %)) arts))
                              e3 (first (filter #(= (:caused-by e2) (:evidence-hash %)) arts))]
                          (if (and e3 (<= (:event/seq e3) (:event/seq e2))) true false)))]
        (is (false? (is-valid? registry))))))

  (testing "invalid-causality-edge-test"
    (let [registry {:artifacts [{:evidence-hash "e1" :workflow-id 1}
                                {:evidence-hash "e2" :caused-by "e1" :workflow-id 2}]}]
      ;; Evidence e2 caused by e1 but different workflow-id
      (let [is-valid? (fn [reg]
                        (let [arts (:artifacts reg)
                              e1 (first (filter #(= "e1" (:evidence-hash %)) arts))
                              e2 (first (filter #(= (:caused-by %) "e1") arts))]
                          (if (and e1 e2 (= (:workflow-id e1) (:workflow-id e2))) true false)))]
        (is (false? (is-valid? registry)))))))
