(ns resolver-sim.validation.runtime-report-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.runtime-report :as report])
  (:import [java.nio.file Files]))

(def valid-checks
  [{:check/id :balance :status :passed :severity :info
    :evidence-root (str "sha256:" (apply str (repeat 64 "a")))}
   {:check/id :coverage :status :warning :severity :warning
    :source/severity "medium" :message "Recovery interleaving not exercised"}])

(defn sample-report []
  (report/build-report {:run/id "run-17"
                        :source/revision (apply str (repeat 40 "a"))
                        :validator/id "partial-fill-runtime-v0"
                        :execution/roots [(str "sha256:" (apply str (repeat 64 "b")))]
                        :checks valid-checks
                        :generated-at "2026-10-31T10:00:00Z"}))

(deftest report-preserves-check-provenance-and-distinct-severity-fields
  (let [value (sample-report)]
    (is (= :prf/runtime-validation-report.v1 (:report/schema value)))
    (is (= :warning (:status value)))
    (is (= "medium" (:source/severity (second (:checks value)))))
    (is (= :warning (:severity (second (:checks value)))))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:report/root value)))
    (is (:valid? (report/validate-report value)))))

(deftest report-root-detects-content-modification
  (let [value (sample-report)
        altered (assoc value :run/id "substituted-run")
        extended (assoc value :unexpected-field true)
        extended (assoc extended :report/root (report/report-root extended))]
    (is (= [:report-root-mismatch] (:errors (report/validate-report altered))))
    (is (= [:invalid-report-fields]
           (:errors (report/validate-report extended))))))

(deftest report-rejects-unsupported-severity-and-duplicate-checks
  (testing "generic validation severity is closed"
    (is (= :invalid-checks
           (:reason (ex-data (try
                               (report/build-report
                                {:run/id "r" :source/revision (apply str (repeat 40 "a")) :validator/id "v"
                                 :checks [{:check/id :x :status :failed :severity :high}]})
                               (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "check identifiers are unique"
    (is (= :duplicate-check-id
           (:reason (ex-data (try
                               (report/build-report
                                {:run/id "r" :source/revision (apply str (repeat 40 "a")) :validator/id "v"
                                 :checks [{:check/id :x :status :passed}
                                          {:check/id :x :status :failed}]})
                               (catch clojure.lang.ExceptionInfo e e))))))))

(deftest atomic-report-file-roundtrip
  (let [dir (Files/createTempDirectory "runtime-report-test" (make-array java.nio.file.attribute.FileAttribute 0))
        target (.resolve dir "findings/report.edn")
        value (sample-report)]
    (try
      (is (= (str target) (report/write-report! target value)))
      (is (= value (report/read-report target)))
      (is (thrown? java.nio.file.FileAlreadyExistsException
                   (report/write-report! target value)))
      (is (= value (report/read-report target)))
      (spit (.toFile target) (str (pr-str value) " {}\n"))
      (is (= :trailing-report-data
             (:reason (ex-data (try
                                 (report/read-report target)
                                 (catch clojure.lang.ExceptionInfo error error))))))
      (finally
        (doseq [path (reverse (file-seq (.toFile dir)))]
          (Files/deleteIfExists (.toPath path)))))))
