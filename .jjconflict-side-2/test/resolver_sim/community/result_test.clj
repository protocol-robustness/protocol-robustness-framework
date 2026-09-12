(ns resolver-sim.community.result-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.community.result :as result]))

(def sample-evidence
  {:benchmark {:benchmark/id :benchmark/prf-deterministic-replay-v1
               :benchmark/status :active}
   :repo {:git/sha "abc123" :dirty? false}
   :environment {:os-name "Linux" :os-version "6.1" :java-version "17"}
   :results [{:file "/tmp/run-1/scenario-1.trace.json"
              :scenario/id "scenario-a"
              :simulator/scenario-path "/tmp/run-1/scenario-1.trace.json"
              :outcome :pass
              :halt-reason nil
              :benchmark/run-index 1
              :benchmark/run-count 2
              :invariant-results [{:id :conservation-of-funds :result :pass}]
              :scenario/evidence-root "a1b2c3"}]
   :metrics {:total 1 :passed 1}
   :claim-results [{:claim/id :claim/replay-identical-results :claim/outcome :pass}]
   :invariant-summary {:total-checks 1 :passed-checks 1 :all-pass? true}
   :evidence/hash "original-bundle-hash"
   :run/manifest {:manifest/version "run-manifest.v1" :scenario-hashes [{:file "/tmp/run-1/scenario-1.trace.json"}]}})

(def sample-evidence-identical
  (-> sample-evidence
      (assoc-in [:environment :java-version] "21")
      (assoc-in [:repo :git/sha] "def456")
      (assoc :evidence/hash "different-hash")
      (assoc-in [:run/manifest :scenario-hashes] [{:file "/tmp/different-path/scenario-1.trace.json"}])))

(def sample-evidence-different-outcome
  (-> sample-evidence-identical
      (assoc-in [:results 0 :outcome] :fail)
      (assoc-in [:results 0 :scenario/evidence-root] "different-root")))

(deftest projection-strips-volatile-fields
  (let [proj (result/project-stable-result sample-evidence)
        p (:stable/projection proj)]
    (is (nil? (:repo p)) ":repo must be stripped")
    (is (nil? (:environment p)) ":environment must be stripped")
    (is (nil? (:evidence/hash p)) ":evidence/hash must be stripped")
    (is (nil? (:run/manifest p)) ":run/manifest must be stripped")
    (is (:benchmark p) ":benchmark must be preserved")
    (is (:metrics p) ":metrics must be preserved")
    (is (:claim-results p) ":claim-results must be preserved")))

(deftest projection-strips-scenario-file-paths
  (let [proj (result/project-stable-result sample-evidence)
        p (:stable/projection proj)]
    (is (nil? (get-in p [:results 0 :file])) ":file must be stripped")
    (is (nil? (get-in p [:results 0 :simulator/scenario-path])) ":simulator/scenario-path must be stripped")
    (is (= "scenario-a" (get-in p [:results 0 :scenario/id])) ":scenario/id must be preserved")
    (is (= :pass (get-in p [:results 0 :outcome])) ":outcome must be preserved")))

(deftest projection-is-deterministic
  (let [h1 (:stable/hash (result/project-stable-result sample-evidence))
        h2 (:stable/hash (result/project-stable-result sample-evidence))]
    (is (= h1 h2) "Same input must produce same hash")))

(deftest identical-bundles-match-after-projection
  (let [{:keys [comparison-status matched?]}
        (result/compare-stable-results sample-evidence sample-evidence-identical)]
    (is matched? "Bundles differing only in volatile fields must match")
    (is (= :matched comparison-status))))

(deftest different-outcomes-detect-mismatch
  (let [{:keys [comparison-status matched?]}
        (result/compare-stable-results sample-evidence sample-evidence-different-outcome)]
    (is (not matched?) "Bundles with different outcomes must not match")
    (is (= :mismatched comparison-status))))
