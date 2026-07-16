(ns resolver-sim.commands.benchmark-conclusion-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]))

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
