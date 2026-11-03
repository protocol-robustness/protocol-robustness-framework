(ns resolver-sim.commands.invariants-run-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.invariants-run :as invariants-run])
  (:import [java.nio.file Files]))

(deftest refuses-missing-run-root-before-writing
  (is (= {:exit-code 2 :message "Missing --run-root"}
         (invariants-run/run {}))))

(deftest refuses-non-empty-run-root-without-changing-it
  (let [root (Files/createTempDirectory "prf-invariants-run"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        sentinel (.resolve root "keep.txt")]
    (spit (.toFile sentinel) "preserve")
    (let [result (invariants-run/run {:run-root (.toString root)})]
      (is (= {:exit-code 1 :message "Run root not empty"} result))
      (is (= "preserve" (slurp (.toFile sentinel))))
      (is (false? (Files/exists (.resolve root "run-plan.json")
                                (make-array java.nio.file.LinkOption 0)))))))
