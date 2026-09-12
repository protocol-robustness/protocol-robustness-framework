(ns resolver-sim.scripts.check-direct-writes-test
  "Regression coverage for the static direct-write gate
   (scripts/scenarios/check_direct_writes.clj).

   Verifies the canonical-mutator / completeness-clearing-escape distinction:
     - a new direct write elsewhere is still flagged (fail-closed);
     - the sole canonical private mutator is recognised and enforced;
     - escape-hatch entries are accepted only when they clear completeness;
     - allowlist entries are exact annotated vars, not loose matches."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [scripts.scenarios.check-direct-writes :as dw]))

(def ^:private source-roots
  "Roots the gate scans for the private-mutator check."
  ["src" "protocols_src"])

(defn- temp-source-file
  "Write a throwaway .clj source file (outside src/protocols_src so the gate's
   own source scan never sees it) and return the File."
  [body]
  (let [f (java.io.File/createTempFile "gate-negative-coverage" ".clj")]
    (.deleteOnExit f)
    (spit f body)
    f))

(deftest negative-new-direct-write-still-fails
  (testing "a brand-new direct write in a non-allowlisted fn is still flagged"
    (let [f (temp-source-file
             "(ns gate-test.core)\n(defn rogue-write [world]\n  (assoc-in world [:total-held :USDC] 5))")
          results (dw/check-file f)]
      (is (seq results))
      (is (= "rogue-write" (:fn (first results))))
      (is (str/includes? (:text (first results)) ":total-held")))))

(deftest canonical-private-mutator-check-passes
  (testing "update-ledger-index is recognised as the sole canonical private mutator"
    (is (nil? (dw/check-canonical-private-mutator source-roots)))
    (is (not (contains? dw/allowlist (:var dw/canonical-private-ledger-mutator)))
        "the private mutator must not be allowlisted")
    (is (str/ends-with? (name (:var dw/canonical-private-ledger-mutator))
                        "update-ledger-index"))))

(deftest canonical-private-mutator-must-be-reachable-only-from-adjust-held
  (testing "a second caller of the private mutator is detected as a failure"
    (let [orig dw/canonical-private-ledger-mutator
          bad  (assoc orig :caller "some-new-entry-point")]
      (try
        (with-redefs [dw/canonical-private-ledger-mutator bad]
          (is (= :private-mutator-not-reachable-from-adjust-held
                 (:reason (dw/check-canonical-private-mutator source-roots)))))
        (finally
          (alter-var-root #'dw/canonical-private-ledger-mutator (constantly orig)))))))

(deftest escape-hatch-entries-clear-completeness
  (testing "completeness-clearing escape hatches are accepted and their flag-clearing is verified"
    (is (nil? (dw/validate-allowlist-entry
               'resolver-sim.yield.modules.adversarial/adversarial-accrue)))
    (is (nil? (dw/validate-allowlist-entry
               'resolver-sim.yield.modules.liquid-lending/apply-pro-rata-propagation)))))

(deftest direct-write-without-clearing-completeness-is-rejected
  (testing "a direct write that does not clear :held-adjustments/complete? is NOT a valid escape hatch"
    (is (not (dw/clears-completeness-flag?
              '(defn rogue [world] (assoc-in world [:total-held :USDC] 5))))
        "plain direct write must not count as completeness-clearing")
    (is (dw/clears-completeness-flag?
         '(defn hatch [world]
            (-> world
                (assoc-in [:total-held :USDC] 5)
                (assoc-in [:params :held-adjustments/complete?] false))))
        "direct write that also clears the flag is a valid escape hatch")))

(deftest allowlist-entries-are-exact-annotated-vars
  (testing "every allowlist entry is an exact qualified var with a valid behaviour and justification"
    (is (seq dw/allowlist))
    (doseq [[sym spec] dw/allowlist]
      (is (symbol? sym))
      (is (pos? (.lastIndexOf (str sym) (int \/)))
          (str sym " must be namespace-qualified (no loose matching)"))
      (is (contains? #{:canonical :replay-reconstruction :completeness-clearing-escape}
                     (:behaviour spec)))
      (is (seq (:justification spec))))))
