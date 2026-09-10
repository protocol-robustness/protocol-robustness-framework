(ns resolver-sim.notebook-support.assurance-test
  "Anti-vacuity guarantees for assurance workbench cells: displayed assurance
   rows must derive their status from the computed value (never author it
   independently), and root-comparison invariants must reject missing operands
   instead of passing through nil = nil."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.notebook-support.assurance :as assurance]))

(deftest cells-derive-status-from-verified
  (testing "status is never authored independently of the computed value"
    (is (= :verified (:status (assurance/cell true "evidence")))
        "truthy value -> :verified")
    (is (= :failed (:status (assurance/cell false "evidence")))
        "falsey value -> :failed")
    (is (= :failed (:status (assurance/cell nil "evidence")))
        "nil value -> :failed"))
  (testing "verified? is always a boolean and status is always a keyword"
    (doseq [v [true false nil "string" 0 1]]
      (let [c (assurance/cell v "evidence")]
        (is (boolean? (:verified? c)))
        (is (contains? #{:verified :failed} (:status c)))
        (is (= (= :verified (:status c)) (:verified? c))
            "status and verified? are mutually consistent")))))

(deftest root-compare-is-non-vacuous
  (testing "missing operands never satisfy equality"
    (is (false? (assurance/root-compare nil nil))
        "nil = nil is never a successful assurance")
    (is (false? (assurance/root-compare "sha256:abc" nil)))
    (is (false? (assurance/root-compare nil "sha256:abc"))))
  (testing "present equal operands satisfy equality"
    (is (true? (assurance/root-compare "sha256:abc" "sha256:abc")))
    (is (false? (assurance/root-compare "sha256:abc" "sha256:def")))))

(deftest three-level-status-is-derived-from-the-value
  (testing "status is a pure function of verified?/reason, never authored independently"
    (is (= :verified (assurance/status true :transition/unimplemented))
        "verified always wins")
    (is (= :verified (assurance/status true nil)))
    (is (= :unimplemented (assurance/status false :transition/unimplemented))
        "explicitly unimplemented mechanism is distinguished from a failure")
    (is (= :not-established (assurance/status false :not-established))
        "not-established authority is distinguished from a failure")
    (is (= :failed (assurance/status false :some-other-reason)))
    (is (= :failed (assurance/status false nil))))
  (testing "a non-verified three-level status still exposes a boolean verified?"
    (is (false? (:verified? (assurance/cell false "x")))
        "the cell's verified? reflects the computed boolean, not the status label")))

(deftest rows-always-carry-status-verified-and-evidence
  (testing "every assurance row exposes status, verified?, and evidence"
    (doseq [row (assurance/rows [["integrity" true "ok"]
                                 ["derivation" false "not derived"]
                                 ["authority" nil "missing"]])]
      (is (= 4 (count row)))
      (let [[_label status verified? evidence] row]
        (is (string? status))
        (is (boolean? verified?))
        (is (string? evidence))
        (is (= (= ":verified" status) verified?)
            "displayed status agrees with the verified? boolean")))))