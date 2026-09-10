(ns resolver-sim.definitions.solidity-shadow-registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.definitions.solidity-shadow-registry :as sr]
            [resolver-sim.protocol-alignment :as align]))

(defn- valid-entry? [e]
  (and (keyword? (:shadow/id e))
       (string? (:simulation/ns e))
       (keyword? (:simulation/role e))
       (string? (:solidity/contract e))
       (string? (:solidity/function e))
       (contains? align/solidity-statuses (:solidity/status e))
       (contains? align/protocol-statuses (:protocol/status e))
       (string? (:description e))
       (vector? (:differences e))
       (string? (:test/link e))
       (string? (:trace-equivalence e))
       (string? (:last-reviewed e))))

(deftest registry-has-entries
  (testing "solidity-shadow-registry has entries"
    (is (seq sr/solidity-shadow-entries))
    (is (pos? (count sr/solidity-shadow-entries)))))

(deftest semantic-coverage-table-is-closed-and-machine-readable
  (testing "semantic coverage rows use the precise status/reason vocabulary"
    (is (= #{:semantic/solidity-coverage-absent
             :semantic/proof-verified-full-coverage}
           sr/semantic-coverage-statuses))
    (is (= #{:reason/solidity-coverage-absent
             :reason/proof-verified-full-coverage}
           sr/semantic-coverage-reasons))
    (is (every? sr/valid-semantic-coverage-row?
                sr/semantic-coverage-table))
    (is (= :semantic/solidity-coverage-absent
           (:semantic/status (sr/semantic-coverage
                              :solidity-shadow/semantic-coverage))))
    (is (= :semantic/proof-verified-full-coverage
           (:semantic/status (sr/semantic-coverage :pro-rata/allocation))))))

(deftest semantic-coverage-rejects-status-reason-mismatch
  (testing "status and reason cannot be mixed or represented by flags alone"
    (is (not (sr/valid-semantic-coverage-row?
              {:semantic/id :test
               :semantic/status :semantic/solidity-coverage-absent
               :semantic/reason :reason/proof-verified-full-coverage
               :semantic/solidity-covered? false
               :semantic/proof-verified? false})))
    (is (nil? (sr/semantic-coverage :unknown)))))

(deftest semantic-shadow-rows-are-validated
  (testing "protected pro-rata lineage is explicit rather than inferred"
    (is (= :semantic/solidity-coverage-absent
           (:semantic/status (sr/semantic-coverage :pro-rata/protected-lineage))))
    (is (empty? (sr/validate-semantic-shadow-rows)))
    (is (not (sr/valid-semantic-shadow-row?
              (assoc (first sr/semantic-shadow-rows)
                     :semantic/proof-verified? true))))))

(deftest all-entries-valid
  (testing "each entry conforms to the expected schema"
    (doseq [e sr/solidity-shadow-entries]
      (is (valid-entry? e) (str "Invalid entry: " (:shadow/id e))))))

(deftest entries-have-unique-ids
  (testing "shadow/id values are unique"
    (let [ids (map :shadow/id sr/solidity-shadow-entries)]
      (is (= (count ids) (count (distinct ids)))))))

(deftest overlapping-simulation-nses-are-documented
  (testing "some simulation namespaces have multiple entries — that's expected"
    (let [nses (map :simulation/ns sr/solidity-shadow-entries)
          groups (group-by identity nses)
          overlaps (keep (fn [[k v]] (when (< 1 (count v)) [k (count v)])) groups)]
      ;; At least one namespace has multiple entries (replay.execution)
      (is (some #(< 1 (second %)) overlaps))
      ;; But each entry should have a distinct shadow/id
      (is (seq (map :shadow/id sr/solidity-shadow-entries)))
      (is (= (count (map :shadow/id sr/solidity-shadow-entries))
             (count (distinct (map :shadow/id sr/solidity-shadow-entries))))))))

(deftest lookup-by-simulation-test
  (testing "lookup-by-simulation returns entries for known namespaces"
    (let [result (sr/lookup-by-simulation "resolver-sim.sim.adversarial.reorg-check")]
      (is (seq result))
      (is (= :escrow-create (:shadow/id (first result))))))
  (testing "lookup-by-simulation returns empty for unknown namespaces"
    (is (empty? (sr/lookup-by-simulation "nonexistent.namespace"))))
  (testing "lookup-by-simulation prefix matching with wildcard"
    (let [result (sr/lookup-by-simulation "resolver-sim.sim.adversarial*")]
      (is (seq result)))))

(deftest lookup-by-solidity-test
  (testing "lookup-by-solidity returns entries for known contracts"
    (let [result (sr/lookup-by-solidity "BaseEscrow.sol")]
      (is (<= 3 (count result)))))
  (testing "lookup-by-solidity returns empty for unknown contracts"
    (is (empty? (sr/lookup-by-solidity "Nonexistent.sol")))))

(deftest all-differences-test
  (testing "all-differences returns entries with non-empty differences"
    (let [diffs (sr/all-differences)]
      (is (seq diffs))
      (doseq [d diffs]
        (is (keyword? (:shadow/id d)))
        (is (seq (:differences d)))))))

(deftest format-shadow-report-test
  (testing "format-shadow-report returns a non-empty string"
    (let [report (sr/format-shadow-report)]
      (is (string? report))
      (is (not (str/blank? report)))
      (is (re-find #"Solidity Shadow Registry" report)))))
