(ns scripts.scenarios.gen-yield-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [scripts.scenarios.gen-yield]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.io.scenarios :as scenarios]))

(defn- invoke-private
  [symbol & args]
  (apply (ns-resolve 'scripts.scenarios.gen-yield symbol) args))

(defn- temp-output
  []
  (let [base (.toFile (java.nio.file.Files/createTempDirectory
                       "prf-gen-yield-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (io/file base "out")))

(defn- generate-output!
  [out & args]
  (let [options (concat ["--seed" "701" "--count" "2" "--max-events" "5"
                         "--out" (.getPath out)] args)]
    (binding [*out* (java.io.StringWriter.)]
      (invoke-private 'generate! options))))

(deftest seeded-output-is-repeatable-and-independently-verifiable
  (let [first-out (temp-output)
        second-out (temp-output)]
    (generate-output! first-out "--profile" "aave-v3")
    (generate-output! second-out "--profile" "aave-v3")
    (let [first-manifest (edn/read-string (slurp (io/file first-out "manifest.edn")))
          second-manifest (edn/read-string (slurp (io/file second-out "manifest.edn")))]
      (is (= (mapv #(select-keys % [:yield-scenario/root :yield-generation/root])
                   (:scenarios first-manifest))
             (mapv #(select-keys % [:yield-scenario/root :yield-generation/root])
                   (:scenarios second-manifest))))
      (is (= (slurp (io/file first-out "scenario-0001.edn"))
             (slurp (io/file second-out "scenario-0001.edn"))))
      (let [loaded (scenarios/load-scenario-file
                    (.getPath (io/file first-out "scenario-0001.edn")))]
        (is (= "yield-v1" (:protocol loaded)))
        (is (= :aave-v3 (get-in loaded [:protocol-params :yield-profile])))
        (is (:ok (yield-replay/validate-yield-scenario loaded))))
      (is (= :verified (:status (invoke-private 'verify! ["--input" (.getPath first-out)])))))))

(deftest profile-selection-produces-a-loadable-yield-scenario
  (let [out (temp-output)]
    (generate-output! out "--profile" "fixed-rate" "--token" "USDT")
    (let [scenario (scenarios/load-scenario-file
                    (.getPath (io/file out "scenario-0001.edn")))]
      (is (= :fixed-rate (get-in scenario [:protocol-params :yield-profile])))
      (is (= "USDT" (get-in scenario [:events 0 :params :token])))
      (is (:ok (yield-replay/validate-yield-scenario scenario))))))

(deftest generation-options-produce-replay-valid-materializations
  (let [out (temp-output)]
    (generate-output! out "--preset" "shortfall-partial"
                      "--token" "DAI"
                      "--failure-mode" "oracle-stale"
                      "--liquidity-mode" "shortfall"
                      "--max-events" "3")
    (let [manifest (edn/read-string (slurp (io/file out "manifest.edn")))
          scenario (edn/read-string (slurp (io/file out "scenario-0001.edn")))]
      (is (= 2 (count (:scenarios manifest))))
      (is (= "DAI" (get-in scenario [:events 0 :params :token])))
      (is (contains? (get-in scenario [:yield-config :modules :aave-v3 :tokens "DAI"
                                       :failure-modes])
                     :oracle-stale))
      (is (= :verified (:status (invoke-private 'verify! ["--input" (.getPath out)])))))))

(deftest verifier-is-read-only-and-rejects-tampered-materializations
  (let [out (temp-output)]
    (generate-output! out)
    (let [scenario-file (io/file out "scenario-0001.edn")
          before (slurp scenario-file)]
      (is (= :verified (:status (invoke-private 'verify! ["--input" (.getPath out)]))))
      (is (= before (slurp scenario-file)))
      (let [scenario (edn/read-string before)
            tampered (assoc-in scenario [:events 0 :params :amount] 999999)]
        (spit scenario-file (pr-str tampered))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"commitment"
                              (invoke-private 'verify! ["--input" (.getPath out)])))))))

(deftest generation-fails-closed-on-unsupported-or-conflicting-options
  (testing "profile and preset cannot silently override one another"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                          (invoke-private 'parse-options!
                                          {:options {:seed 1 :count 1 :max-events 5 :out "x"
                                                     :profile "aave-v3" :preset "shortfall-partial"
                                                     :failure-mode []}
                                           :arguments [] :errors []}))))
  (testing "out-of-range sequence sizes are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"between 1 and 5"
                          (invoke-private 'parse-options!
                                          {:options {:seed 1 :count 1 :max-events 6 :out "x"
                                                     :failure-mode []}
                                           :arguments [] :errors []}))))
  (testing "existing output is never overwritten"
    (let [out (temp-output)]
      (generate-output! out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refusing to overwrite"
                            (invoke-private 'generate! ["--seed" "702" "--out" (.getPath out)]))))))
