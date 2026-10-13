(ns resolver-sim.validation.scenario-registry-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.invariant-scenarios :as inv-sc]
            [resolver-sim.validation.scenario-registry :as registry]))

(defn- first-invariant-scenario
  []
  (some (fn [[_ entry]]
          (cond
            (map? entry) entry
            (vector? entry) (first entry)
            :else nil))
        inv-sc/all-scenarios))

(deftest canonical-scenario-registry-validates
  (let [summary (registry/validate-all!)]
    (is (:ok? summary))
    (is (pos? (get-in summary [:registry/invariants :scenario-count])))
    (is (pos? (get-in summary [:registry/file-backed :suite-count])))
    (is (pos? (get-in summary [:registry/file-backed :scenario-count])))))

(deftest file-backed-registry-rejects-duplicate-scenario-ids
  (let [suite-registry {:suite/a {:paths ["scenarios/edn/Y01_vault-shared-liquidity.edn"]
                                  :protocol-id "yield-v1"
                                  :kind :file-path-suite}
                        :suite/b {:paths ["scenarios/edn/Y01_vault-shared-liquidity.edn"]
                                  :protocol-id "yield-v1"
                                  :kind :file-path-suite}}]
    (try
      (registry/validate-file-backed-suite-registry! suite-registry)
      (is false "expected duplicate file-backed scenario-id validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :duplicate-file-backed-scenario-ids (:reason (ex-data e))))
        (is (= ["Y01_vault-shared-liquidity"] (:duplicates (ex-data e))))))))

(deftest file-backed-registry-rejects-missing-scenario-file
  (let [suite-registry {:suite/missing {:paths ["scenarios/DOES_NOT_EXIST.edn"]
                                        :protocol-id "sew-v1"
                                        :kind :file-path-suite}}]
    (try
      (registry/validate-file-backed-suite-registry! suite-registry)
      (is false "expected missing scenario file validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :missing-scenario-file (:reason (ex-data e))))
        (is (= "scenarios/DOES_NOT_EXIST.edn" (:scenario/path (ex-data e))))))))

(deftest file-backed-registry-rejects-protocol-dispatch-mismatch
  (let [suite-registry {:suite/wrong-protocol {:paths ["scenarios/edn/Y01_vault-shared-liquidity.edn"]
                                               :protocol-id "sew-v1"
                                               :kind :file-path-suite}}]
    (try
      (registry/validate-file-backed-suite-registry! suite-registry)
      (is false "expected protocol mismatch validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :scenario-protocol-mismatch (:reason (ex-data e))))
        (is (= "sew-v1" (:expected (ex-data e))))
        (is (= "yield-v1" (:actual (ex-data e))))))))

(deftest file-backed-registry-rejects-malformed-suite-definition
  (let [suite-registry {:suite/bad {:paths '("scenarios/edn/Y01_vault-shared-liquidity.edn")
                                    :protocol-id "yield-v1"
                                    :kind :file-path-suite}}]
    (try
      (registry/validate-file-backed-suite-registry! suite-registry)
      (is false "expected malformed suite definition validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :suite-paths-not-a-vector (:reason (ex-data e))))))))

(deftest invariant-registry-rejects-duplicate-scenario-ids
  (let [scenario-a (assoc (first-invariant-scenario) :scenario-id "dup-scenario")
        scenario-b (assoc (first-invariant-scenario) :scenario-id "dup-scenario")]
    (with-redefs [inv-sc/all-scenarios [["dup-a" scenario-a]
                                        ["dup-b" scenario-b]]
                  inv-sc/scenario-type-registry {"dup-scenario" {:type :test}}]
      (try
        (inv-sc/validate-all-scenarios!)
        (is false "expected duplicate invariant scenario-id validation failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= ["dup-scenario"] (:duplicates (ex-data e)))))))))

(deftest invariant-registry-rejects-malformed-entry
  (with-redefs [inv-sc/all-scenarios [["broken" 42]]
                inv-sc/scenario-type-registry {}]
    (try
      (inv-sc/validate-all-scenarios!)
      (is false "expected malformed invariant registry entry failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :entry-not-a-scenario-map (:reason (ex-data e))))))))

(deftest invariant-registry-rejects-missing-scenario-type-coverage
  (let [scenario (assoc (first-invariant-scenario) :scenario-id "missing-type-id")]
    (with-redefs [inv-sc/all-scenarios [["missing-type" scenario]]
                  inv-sc/scenario-type-registry {}]
      (try
        (inv-sc/validate-all-scenarios!)
        (is false "expected missing scenario type registry failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= ["missing-type-id"] (:missing (ex-data e)))))))))
