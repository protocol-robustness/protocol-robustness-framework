(ns resolver-sim.sim.result-display-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.result-display :as display]
            [resolver-sim.io.fixtures :as fixtures]))

(def ^:private pass-entry
  {:trace-id "s-pass"
   :pass? true
   :outcome :pass
   :purpose :regression
   :threshold-validation {:ok? true}
   :expectations {:ok? true :violations []}
   :theory {:status :not-falsified
            :diagnostics {:theory-eval-profile :regression
                          :grounded? true
                          :evidence-completeness :complete}}
   :metrics {:yield/escrow-principal 9950
             :yield/escrow-realized 100}})

(def ^:private yield-fail-entry
  {:trace-id "S108-fake-yield"
   :pass? false
   :outcome :pass
   :purpose :regression
   :threshold-validation {:ok? true}
   :expectations {:ok? false
                  :violations [{:type :metric-violation
                                :name :yield/escrow-principal
                                :op :=
                                :expected 9950
                                :actual 9800}]}
   :theory {:status :not-falsified
            :diagnostics {:theory-eval-profile :regression}}
   :metrics {:yield/escrow-principal 9800
             :yield/escrow-unrealized 0
             :yield/escrow-realized 100
             :yield/escrow-deferred 0
             :yield/escrow-haircut 0
             :yield/escrow-reclaimed 0
             :yield/escrow-status "active"
             :escrow/amount-after-fee 9850}})

(def ^:private suite-result
  {:suite-id :suites/fake
   :ok? false
   :results [pass-entry yield-fail-entry]})

(def ^:private yield-expectations-decl
  {"S108-fake-yield" {:metrics [{:name :yield/escrow-principal :op := :value 9950}]}})

(defn- lines-at [level & {:keys [elapsed-ms expectations-by-trace-id]
                          :or {elapsed-ms 1200
                               expectations-by-trace-id yield-expectations-decl}}]
  (display/suite-report-lines suite-result
                              {:result-display-level level
                               :elapsed-ms elapsed-ms
                               :expectations-by-trace-id expectations-by-trace-id}))

(deftest display-levels-produce-different-line-counts
  (let [summary  (lines-at :summary)
        failures (lines-at :failures)
        standard (lines-at :standard)
        verbose  (lines-at :verbose)
        audit    (lines-at :audit)]
    (is (< (count summary) (count failures)))
    (is (< (count failures) (count standard)))
    (is (< (count standard) (count verbose)))
    (is (= verbose audit) ":audit mirrors :verbose for now")))

(deftest summary-includes-failed-scenario-ids-when-suite-fails
  (let [lines (lines-at :summary)]
    (is (some #(re-find #"Status: FAIL" %) lines))
    (is (some #(re-find #"Scenarios: 1/2" %) lines))
    (is (some #(re-find #"\(1\.2 s\)" %) lines))
    (is (some #(re-find #"Failed: S108-fake-yield" %) lines)
        "CI summary must name failed scenario IDs")
    (is (not (some #(re-find #"yield/escrow-principal" %) lines)))))

(deftest failures-print-exact-expectation-key-and-value
  (let [lines (lines-at :failures)
        joined (clojure.string/join "\n" lines)]
    (is (re-find #"S108-fake-yield:" joined))
    (is (re-find #"yield/escrow-principal := 9950 \(actual 9800\)" joined))
    (is (not (some #(re-find #"yield/escrow-unrealized" %) lines)))))

(deftest verbose-prints-compact-yield-subset
  (let [lines (lines-at :verbose)
        joined (clojure.string/join "\n" lines)]
    (is (re-find #"yield/escrow-principal=9800" joined))
    (is (re-find #"yield/escrow-realized=100" joined))
    (is (re-find #"yield/escrow-status=active" joined))
    (is (not (re-find #"escrow/amount-after-fee" joined))
        "reporter must not surface non-display yield keys")))

(deftest standard-omits-yield-unless-referenced-or-failed
  (testing "passing scenario without yield expectations"
    (let [lines (lines-at :standard)
          pass-line (first (filter #(re-find #"s-pass" %) lines))]
      (is pass-line)
      (is (not (re-find #"yield/" pass-line))
          "pass row omits yield when scenario does not reference yield keys")))
  (testing "failed yield expectation shows compact yield on row"
    (let [lines (lines-at :standard)
          fail-line (first (filter #(re-find #"\[FAIL\] S108-fake-yield" %) lines))]
      (is fail-line)
      (is (re-find #"yield/escrow-principal=9800" fail-line)))))

(deftest display-does-not-mutate-suite-result
  (let [before (vary-meta suite-result assoc :marker :unchanged)
        _      (display/suite-report-lines before {:result-display-level :verbose})
        after  before]
    (is (= before after))
    (is (= :unchanged (:marker (meta after))))))

(deftest legacy-opts-normalize-to-display-level
  (is (= :verbose (display/resolve-display-level {:verbose? true})))
  (is (= :failures (display/resolve-display-level {:show-failures? true})))
  (is (= :summary (display/resolve-display-level {:show-failures? false})))
  (is (= :verbose (display/resolve-display-level {:result-display-level :verbose
                                                  :verbose? false}))))

(deftest unknown-display-level-throws-with-valid-levels
  (try
    (display/resolve-display-level {:result-display-level :verbsoe})
    (is false "expected ex-info for unknown display level")
    (catch clojure.lang.ExceptionInfo e
      (is (= "Unknown result-display-level" (.getMessage e)))
      (is (= :verbsoe (:level (ex-data e))))
      (is (= (sort display/display-levels) (:valid-levels (ex-data e)))))))

(deftest scenario-entry-ok-reads-canonical-pass-flag
  (is (display/scenario-entry-ok? pass-entry))
  (is (not (display/scenario-entry-ok? yield-fail-entry)))
  (is (not (display/scenario-entry-ok? (dissoc pass-entry :pass?)))
      "missing :pass? is treated as fail for display gating only"))

(deftest run-suite-return-map-excludes-display-only-keys
  (let [result (fixtures/run-suite-from-key :suites/equivalence-escalation-boundaries
                                            nil nil {:silent? true})]
    (is (map? result))
    (is (contains? result :ok?))
    (is (contains? result :results))
    (is (not (contains? result :expectations-by-trace-id)))
    (is (not (contains? result :result-display-level)))))
