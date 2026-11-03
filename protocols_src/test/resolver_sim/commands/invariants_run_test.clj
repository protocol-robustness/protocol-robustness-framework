(ns resolver-sim.commands.invariants-run-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.io.scenario-runner :as sr]
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

(deftest normalizes-passed-results-before-counting-and-writing
  (let [root (Files/createTempDirectory "prf-invariants-outcome-alias"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [sr/run-registry-suite
                  (fn [_]
                    {:results [{:scenario-id "alias-pass"
                                :outcome :passed
                                :pass? true}
                               {:scenario-id "boolean-pass"
                                :pass? true}]})]
      (let [result (invariants-run/run {:run-root (.toString root)})
            completion (json/read-str (slurp (io/file (.toFile root) "completion.json"))
                                      :key-fn keyword)
            index (json/read-str (slurp (io/file (.toFile root) "scenario-results.json"))
                                 :key-fn keyword)]
        (is (= 0 (:exit-code result)))
        (is (= 2 (:passed-count completion)))
        (is (zero? (:unknown-count completion)))
        (is (= ["pass" "pass"] (mapv :status (:results index))))))))
