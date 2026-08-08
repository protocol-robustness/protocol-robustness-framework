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

;; ── trace metrics: command-count / command-valid-count ─────────────────────

(defn- built-command
  [id]
  (rcmd/build-command (assoc minimal-command :command/id id)))

(deftest command-trace-metrics-counts-and-binds
  (testing "both counts derive from one snapshot and bind the trace root"
    (let [trace [(built-command :trace/a) (built-command :trace/b)]
          metrics (rcmd/command-trace-metrics trace)]
      (is (= 2 (:command-count metrics)))
      (is (= 2 (:command-valid-count metrics)))
      (is (:trace/valid? metrics))
      (is (string? (:trace/root metrics)))
      (is (= (rcmd/command-trace-root trace) (:trace/root metrics))))))

(deftest command-trace-metrics-distinguishes-valid-from-invalid
  (testing "an invalid declaration counts as discovered but not valid"
    (let [valid (built-command :trace/good)
          invalid (assoc (built-command :trace/tampered)
                         :command/hash "sha256:tampered")
          metrics (rcmd/command-trace-metrics [valid invalid])]
      (is (= 2 (:command-count metrics)))
      (is (= 1 (:command-valid-count metrics)))
      (is (not (:trace/valid? metrics))))))

(deftest command-trace-metrics-reproducible
  (testing "the same declarations produce the same root and counts"
    (let [a (rcmd/command-trace-metrics [(built-command :trace/x) (built-command :trace/y)])
          b (rcmd/command-trace-metrics [(built-command :trace/y) (built-command :trace/x)])]
      (is (= (:trace/root a) (:trace/root b)))
      (is (= (:command-count a) (:command-count b)))
      (is (= (:command-valid-count a) (:command-valid-count b))))))

(deftest command-trace-metrics-empty-trace
  (let [metrics (rcmd/command-trace-metrics [])]
    (is (= 0 (:command-count metrics)))
    (is (= 0 (:command-valid-count metrics)))
    (is (:trace/valid? metrics))
    (is (string? (:trace/root metrics)))))

(deftest combinations-do-not-affect-command-count
  (testing "non-command artifacts (composition combinations, add-held evidence
            maps) recorded alongside commands never change the command counts"
    (let [cmd (built-command :trace/held)
          add-held-combination {:combination/id :test.combination/add-held
                                :combination/version 1
                                :combination/nodes []}
          add-held-evidence {:effect/type :custody/held-adjustment
                             :effect/contract :prf.effect/custody-held-adjustment.v2
                             :effect/action "add-held"
                             :effect/account :escrow
                             :effect/amount 100
                             :effect/token :usdc
                             :held/kind :reason}
          metrics (rcmd/command-trace-metrics
                   [cmd add-held-combination add-held-evidence])]
      (is (= 1 (:command-count metrics))
          "a combination is not a command declaration")
      (is (= 1 (:command-valid-count metrics)))
      (is (:trace/valid? metrics))
      (is (= 2 (:trace/skipped metrics))
          "non-command entries are surfaced, not silently dropped")
      (is (= (rcmd/command-trace-root [cmd]) (:trace/root metrics))
          "the trace root binds exactly the counted declarations"))))

(deftest duplicate-command-id-fails-closed
  (testing "two declarations carrying the same :command/id are a malformed
            trace: rejected, never silently deduplicated, never counted twice"
    (let [a (built-command :trace/dup)
          b (assoc (built-command :trace/other) :command/id :trace/dup)
          e (try (rcmd/command-trace-metrics [a b])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :command-trace/duplicate-command-id (:error (ex-data e))))
      (is (= [:trace/dup] (:duplicates (ex-data e)))))))

(deftest duplicate-nil-command-id-fails-closed
  (testing "entries carrying :command/id with a nil value are declarations and
            therefore also fail closed on duplication"
    (let [e (try (rcmd/command-trace-metrics
                  [{:command/id nil :effect/action "add-held"}
                   {:command/id nil :effect/action "sub-held"}])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :command-trace/duplicate-command-id (:error (ex-data e)))))))

(deftest command-looking-malformed-maps-are-discovered-but-invalid
  (testing "an entry that merely acquires the :command/id discriminator is a
            declaration — discovered and counted, then judged invalid, never
            skipped"
    (let [valid (built-command :trace/good)
          command-shaped {:command/id "x" :combination/type :add-held}
          nil-id {:command/id nil :effect/action "add-held"}
          metrics (rcmd/command-trace-metrics [valid command-shaped nil-id])]
      (is (= 3 (:command-count metrics))
          "command-shaped maps are discovered as declarations")
      (is (= 1 (:command-valid-count metrics))
          "only the genuinely valid command passes validation")
      (is (not (:trace/valid? metrics)))
      (is (= 0 (:trace/skipped metrics)))
      (testing "each malformed declaration is individually invalid"
        (is (not (:valid? (rcmd/validate-command command-shaped))))
        (is (not (:valid? (rcmd/validate-command nil-id))))))))

(deftest hashless-declaration-is-discovered-but-invalid
  (testing "a declaration without a committed :command/hash is invalid, so it
            counts toward command-count but never command-valid-count"
    (let [good (built-command :trace/good)
          no-hash (dissoc (built-command :trace/no-hash) :command/hash)
          metrics (rcmd/command-trace-metrics [good no-hash])]
      (is (= 2 (:command-count metrics)))
      (is (= 1 (:command-valid-count metrics)))
      (is (not (:trace/valid? metrics)))
      (is (not (:valid? (rcmd/validate-command no-hash)))))))

(deftest validators-agree-on-malformed-boundary
  (testing "validate-command (the metric's validity basis) and command-valid?
            agree: valid commands pass both, hashless and non-keyword-id
            declarations pass neither"
    (let [valid (built-command :trace/agree)
          no-hash (dissoc valid :command/hash)
          string-id (assoc valid :command/id "not-a-keyword")]
      (is (:valid? (rcmd/validate-command valid)))
      (is (rcmd/command-valid? valid))
      (is (not (:valid? (rcmd/validate-command no-hash))))
      (is (not (rcmd/command-valid? no-hash)))
      (is (not (:valid? (rcmd/validate-command string-id))))
      (is (not (rcmd/command-valid? string-id)))
      (testing "a hashless command-looking map is discovered but invalid in the metric"
        (let [metrics (rcmd/command-trace-metrics [(built-command :trace/valid) no-hash string-id])]
          (is (= 3 (:command-count metrics)))
          (is (= 1 (:command-valid-count metrics))))))))
