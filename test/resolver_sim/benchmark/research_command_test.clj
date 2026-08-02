(ns resolver-sim.benchmark.research-command-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-command :as rcmd]))

(def ^:const minimal-command
  {:command/id :command/incentive-compatibility
   :command/type :benchmark-evaluation
   :command/argv ["prf" "benchmark" "run-and-report"
                  "--benchmark-content-root" "sha256:content"
                  "--include" "incentive"
                  "--include" "incentive-compatibility"]
   :command/include [:incentive :incentive-compatibility]
   :command/environment-root "sha256:env"
   :command/runner-root "sha256:runner"
   :command/input-root "sha256:input"
   :command/output-root "sha256:output"})

(deftest build-minimal-command
  (let [c (rcmd/build-command minimal-command)]
    (is (rcmd/command-valid? c))
    (is (some? (:command/hash c)))
    (is (= :command/incentive-compatibility (:command/id c)))
    (is (= :benchmark-evaluation (:command/type c)))
    (is (= [:incentive :incentive-compatibility] (:command/include c)))))

(deftest build-command-normalises-single-include
  (let [c (rcmd/build-command
           (assoc minimal-command :command/include :incentive))]
    (is (= [:incentive] (:command/include c)))))

(deftest build-command-normalises-set-include
  (let [c (rcmd/build-command
           (assoc minimal-command :command/include #{:incentive :incentive-compatibility}))]
    (is (= [:incentive :incentive-compatibility] (:command/include c)))))

(deftest build-command-defaults-empty-include
  (let [c (rcmd/build-command
           (dissoc minimal-command :command/include))]
    (is (= [] (:command/include c)))))

(deftest build-command-rejects-unsupported-or-malformed-includes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported :command/include"
                        (rcmd/build-command
                         (assoc minimal-command :command/include [:yield-lineage]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entries must be keywords"
                        (rcmd/build-command
                         (assoc minimal-command :command/include ["incentive"])))))

(deftest build-command-requires-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :command/id"
                        (rcmd/build-command (dissoc minimal-command :command/id)))))

(deftest build-command-requires-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or invalid :command/type"
                        (rcmd/build-command (dissoc minimal-command :command/type)))))

(deftest build-command-rejects-invalid-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or invalid :command/type"
                        (rcmd/build-command (assoc minimal-command :command/type :bogus)))))

(deftest build-command-requires-non-empty-argv
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":command/argv"
                        (rcmd/build-command (assoc minimal-command :command/argv [])))))

(deftest build-command-rejects-hash-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Declared command/hash"
                        (rcmd/build-command (assoc minimal-command :command/hash "sha256:wrong")))))

(deftest validate-command-valid
  (let [c (rcmd/build-command minimal-command)
        result (rcmd/validate-command c)]
    (is (:valid? result))))

(deftest validate-command-rejects-tampered-hash
  (let [c (rcmd/build-command minimal-command)
        bad (assoc c :command/hash "sha256:fake")
        result (rcmd/validate-command bad)]
    (is (not (:valid? result)))))

(deftest validate-command-rejects-unrecognised-include
  (let [c (rcmd/build-command minimal-command)
        bad (assoc c :command/include [:unknown])
        result (rcmd/validate-command bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"unsupported :command/include" %) (:errors result)))))

(deftest semantic-command-identity
  (let [c1 (rcmd/build-command
            (assoc minimal-command :command/include #{:incentive :incentive-compatibility}))
        c2 (rcmd/build-command
            (assoc minimal-command :command/include [:incentive-compatibility :incentive]))]
    (is (rcmd/same-semantic-command? c1 c2))
    (is (not (rcmd/same-semantic-command?
              c1
              (rcmd/build-command
               (assoc minimal-command :command/include [:incentive])))))))

(deftest valid-command-types-catalog
  (is (rcmd/valid-command-type? :benchmark-evaluation))
  (is (rcmd/valid-command-type? :theorem-evaluation))
  (is (rcmd/valid-command-type? :claim-evaluation))
  (is (rcmd/valid-command-type? :invariant-check))
  (is (rcmd/valid-command-type? :evidence-projection))
  (is (rcmd/valid-command-type? :state-projection))
  (is (not (rcmd/valid-command-type? :bogus))))

(deftest command-hash-stable
  (let [c1 (rcmd/build-command minimal-command)
        c2 (rcmd/build-command minimal-command)]
    (is (= (:command/hash c1) (:command/hash c2))
        "same inputs must produce same hash")))
