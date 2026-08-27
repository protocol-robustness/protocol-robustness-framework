(ns resolver-sim.commands.benchmark-conclusion-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]
            [resolver-sim.run.package-index :as package-index]))

(deftest required-not-exercised-conservation-claim-cannot-conclude-pass
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "benchmark-conclusion-" (make-array java.nio.file.attribute.FileAttribute 0)))
        evidence-file (io/file root "benchmark/evidence/evidence.edn")
        conclusion-file (io/file root "benchmark/conclusion.json")
        context {:run/id "run-test" :benchmark/evidence-file evidence-file :benchmark/conclusion-file conclusion-file}
        evidence {:benchmark {:benchmark/id :benchmark/test :benchmark/required-claims [:claim/funds-conserved]}
                  :metrics {:total 1 :passed 1}
                  :invariant-summary {:total-checks 1 :passed-checks 1}
                  :claim-results [{:claim/id :claim/funds-conserved :claim/outcome :not-exercised}]}]
    (try
      (io/make-parents evidence-file)
      (spit evidence-file "{}")
      (let [written (conclusion/write! context evidence)]
        (is (= "inconclusive" (get written "outcome")))
        (is (= "required-claim-not-conclusively-evaluated" (get written "reason"))))
      (finally (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))

(deftest semantic-derivation-reuses-producer-classification
  (let [passing {:benchmark {:benchmark/required-claims [:claim/a :claim/b]}
                 :metrics {:total 1 :passed 1}
                 :invariant-summary {:total-checks 1 :passed-checks 1}
                 :claim-results [{:claim/id :claim/a :claim/outcome :pass}
                                 {:claim/id :claim/b :claim/outcome :pass}]}
        mixed (assoc passing :claim-results [{:claim/id :claim/a :claim/outcome :pass}
                                             {:claim/id :claim/b :claim/outcome :fail}])]
    (is (= "pass" (conclusion/derive-semantic-status passing)))
    (is (= "fail" (conclusion/derive-semantic-status mixed))
        "a failing required claim prevents a passing conclusion")
    (is (= (conclusion/derive-semantic-status passing)
           (conclusion/derive-semantic-status (assoc passing :timestamp "runtime-only")))
        "runtime-only observations do not affect semantic derivation")))

(deftest benchmark-package-semantic-status-reconciles-derived-evidence
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "benchmark-semantic-" (make-array java.nio.file.attribute.FileAttribute 0)))
        evidence-file (io/file root "benchmark/evidence/evidence.edn")
        conclusion-file (io/file root "benchmark/conclusion.json")
        pass-evidence {:benchmark {:benchmark/required-claims [:claim/a]}
                       :metrics {:total 1 :passed 1}
                       :invariant-summary {:total-checks 1 :passed-checks 1}
                       :claim-results [{:claim/id :claim/a :claim/outcome :pass}]}
        fail-evidence (assoc pass-evidence :claim-results [{:claim/id :claim/a :claim/outcome :fail}])
        index {:run/type :benchmark
               :artifacts {:benchmark-evidence {:ref "benchmark/evidence/evidence.edn"}
                           :benchmark-conclusion {:ref "benchmark/conclusion.json"}}}
        validate (fn [evidence conclusion completion]
                   (spit evidence-file (pr-str evidence))
                   (spit conclusion-file (json/write-str {"outcome" conclusion}))
                   (with-redefs [package-index/resolve-validation-context
                                 (constantly {:run-root root :completion completion
                                              :package-index {:index index} :reasons []})]
                     (package-index/validate-semantic-result root)))]
    (try
      (io/make-parents evidence-file)
      (is (false? (:semantic-pass? (validate fail-evidence "fail" {"semantic_status" "pass"})))
          "declared completion pass cannot override derived failure")
      (is (false? (:semantic-pass? (validate pass-evidence "pass" {"semantic_status" "fail"})))
          "declared completion fail cannot override derived pass")
      (is (true? (:semantic-pass? (validate pass-evidence "pass" {})))
          "missing redundant completion semantic status still derives")
      (is (false? (:semantic-pass? (validate fail-evidence "pass" {"semantic_status" "fail"})))
          "a conclusion cannot overclaim sealed evidence")
      (finally (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))
