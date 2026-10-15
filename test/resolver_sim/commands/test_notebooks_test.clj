(ns resolver-sim.commands.test-notebooks-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [scripts.test-notebooks :as notebooks])
  (:import [java.nio.file Files]
           [java.io File]))

(deftest notebook-loader-reports-hang-at-27-of-51-and-retains-logs
  (testing "all namespaces are accounted for when the 27th load times out"
    (let [namespaces (mapv #(symbol (format "notebooks.synthetic-%02d" (inc %)))
                           (range 51))
          loader (fn [ns-sym]
                   (let [index (Integer/parseInt
                                (last (str/split (str ns-sym) #"-")))]
                     (if (= index 27)
                       (do (Thread/sleep 25)
                           {:ok? false :exit :timeout :duration-ms 25
                            :output "simulated notebook child timeout at 27/51\npartial diagnostic"})
                       {:ok? true :exit 0 :duration-ms 10 :output (str ns-sym " loaded")})))
          results* (atom nil)
          progress (with-out-str
                     (reset! results* (notebooks/run-notebook-loads namespaces loader 3)))
          results @results*
          directory (.toFile (Files/createTempDirectory
                              "prf-notebook-report-test-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
          entries (mapv (fn [ns-sym]
                          {:file (File. "notebooks/synthetic.clj") :namespace ns-sym})
                        namespaces)
          report (notebooks/write-validation-report! directory entries results
                                                     "2026-10-15T00:00:00Z")
          persisted (json/read-str (slurp (File. directory "report.json"))
                                   :key-fn keyword)
          timed-out (nth (:namespaces persisted) 26)
          log (slurp (File. directory (:log timed-out)))]
      (is (= 51 (count results)))
      (is (= 51 (:attempted report)))
      (is (= 50 (:loaded report)))
      (is (= 0 (:failed report)))
      (is (= 1 (:timedOut report)))
      (is (= "failed" (:status persisted)))
      (is (= "timed-out" (:status timed-out)))
      (is (re-find #"27/51" log))
      (is (re-find #"TIMEOUT notebooks.synthetic-27" progress))
      (is (not (re-find #"VALIDATION PASSED" progress))))))
