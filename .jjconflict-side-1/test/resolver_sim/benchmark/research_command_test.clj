(ns resolver-sim.benchmark.research-command-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-command :as rcmd]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.sequence :as seq]))

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

(deftest combinations-are-recognized-not-counted-as-commands
  (testing "a combination is a distinct first-class trace entity: it is
            counted as a combination, never as a command, and never as
            skipped debris"
    (let [cmd (built-command :trace/held)
          add-held-combination {:combination/id :test.combination/add-held
                                :combination/version 1
                                :combination/nodes []}
          metrics (rcmd/command-trace-metrics [cmd add-held-combination])]
      (is (= 1 (:command-count metrics))
          "a combination is not a command declaration")
      (is (= 1 (:command-valid-count metrics)))
      (is (= 1 (:combination-count metrics))
          "the combination is recognized and counted separately")
      (is (= 0 (:trace/skipped metrics))
          "a combination is not skipped debris")
      (is (seq (filter #(contains? % :combination/id)
                       [cmd add-held-combination]))
          "the combination entry is a :combination/id artifact"))))

(deftest combinations-do-not-affect-command-count
  (testing "non-command entries alongside commands never change the command
            counts; a combination is counted separately and only unrecognized
            entries are skipped"
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
      (is (= 1 (:combination-count metrics))
          "the combination is recognized, not skipped")
      (is (= 1 (:trace/skipped metrics))
          "only genuinely unrecognized entries are skipped")
      (is (= (rcmd/command-trace-root [cmd]) (:trace/root metrics))
          "the trace root binds exactly the counted command declarations"))))

(deftest combination-with-command-id-counts-as-command
  (testing "the :command/id discriminator is authoritative: a combination map
            that also carries :command/id is a command declaration (discovered,
            then judged invalid), never a combination"
    (let [cmd (built-command :trace/held)
          annotated-combination (assoc {:combination/id :test.combination/add-held
                                        :combination/version 1
                                        :combination/nodes []}
                                       :command/id :trace/annotated)
          metrics (rcmd/command-trace-metrics [cmd annotated-combination])]
      (is (= 2 (:command-count metrics)))
      (is (= 0 (:combination-count metrics)))
      (is (= 0 (:trace/skipped metrics))))))

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

;; ── Gate 0: Semantic boundary preservation ──────────────────────────────────
;;
;; These tests enforce the five-part boundary:
;;   1. command-built-with-includes     — v1 compat oracle (kept unchanged above)
;;   2. combination / add-held          — composition semantics, not command scope
;;   3. semantic / semantic-golden      — command identity determinism
;;   4. build-certificate               — researcher projection (integration test)
;;   5. pro-rata-fairness-end-to-end    — end-to-end promise (strategic claim)
;;
;; The following tests assert separations that must hold across migrations.

(deftest trace-sequencing-is-distinct-from-concatenate-bound
  (let [cmd-1 (built-command :trace/sep-a)
        cmd-2 (built-command :trace/sep-b)
        trace (rcmd/command-trace-root [cmd-1 cmd-2])]
    (is (string? trace)
        "trace root is a sha256 reference")
    (is (not= "CONFIDENCE_COMPOSITION_V1" rcmd/trace-domain-tag)
        "command trace domain tag is distinct from concatenate-bound domain tag")))

(deftest command-scope-is-distinct-from-add-held
  (let [cmd (built-command :scope/sep)]
    (is (not (contains? (set (:command/include cmd)) :add-held))
        "add-held is not a valid command include keyword — it is a combination/effect concept")
    (is (thrown? clojure.lang.ExceptionInfo
                 (rcmd/build-command (assoc minimal-command
                                            :command/include [:add-held])))
        "add-held is rejected as an unsupported include value")))

;; ── Phase 1: research-command-trace.v2 ───────────────────────────────────────

(deftest trace-v2-order-sensitive
  (let [c1 (built-command :trace/v2-a)
        c2 (built-command :trace/v2-b)]
    (is (not= (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
              (:trace/root (rcmd/build-command-trace-v2 {:commands [c2 c1]})))
        "different order → different root")))

(deftest trace-v2-same-sequence-same-root
  (let [c1 (built-command :trace/v2-x)
        c2 (built-command :trace/v2-y)
        r1 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        r2 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))]
    (is (= r1 r2)
        "same ordered sequence → same root (construction-detail independent)")))

(deftest trace-v2-component-count-verified
  (let [c1 (built-command :trace/v2-cnt)
        t  (rcmd/build-command-trace-v2 {:commands [c1]})]
    (is (= 1 (:trace/component-count t)))
    (is (some? (:trace/root t)))
    (is (some? (:trace/commitment t)))
    (is (bytes? (:trace/commitment t))
        "commitment is a byte array")))

(deftest trace-v2-roundtrip
  (let [c1    (built-command :trace/v2-rt-a)
        c2    (built-command :trace/v2-rt-b)
        trace (rcmd/build-command-trace-v2 {:commands [c1 c2]})
        vr    (rcmd/verify-command-trace-v2 (:trace/commitment trace))]
    (is (:valid? vr))
    (is (= 2 (:component-count (:trace vr))))
    (is (= (:trace/components trace) (:components (:trace vr))))
    (is (= rcmd/command-trace-v2-purpose (:purpose (:trace vr))))))

(deftest trace-v2-rejects-empty-trace
  (is (thrown? clojure.lang.ExceptionInfo
               (rcmd/build-command-trace-v2 {:commands []}))))

(deftest trace-v2-rejects-invalid-component
  (let [c1 (built-command :trace/v2-valid)
        c2 (assoc (built-command :trace/v2-tampered)
                  :command/hash "sha256:not-a-real-hash")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        "invalid :command/hash → rejected")))

(deftest trace-v2-mutation-deletion
  (let [c1  (built-command :trace/v2-del-a)
        c2  (built-command :trace/v2-del-b)
        r12 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        r1  (:trace/root (rcmd/build-command-trace-v2 {:commands [c1]}))]
    (is (not= r12 r1) "removing a command changes root")))

(deftest trace-v2-mutation-insertion
  (let [c1  (built-command :trace/v2-ins-a)
        c2  (built-command :trace/v2-ins-b)
        c3  (built-command :trace/v2-ins-c)
        r12 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        r123 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2 c3]}))]
    (is (not= r12 r123) "adding a command changes root")))

(deftest trace-v2-mutation-duplication
  (let [c1  (built-command :trace/v2-dup-a)
        r1  (:trace/root (rcmd/build-command-trace-v2 {:commands [c1]}))
        r11 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c1]}))]
    (is (not= r1 r11) "duplicating a command changes root")))

(deftest trace-v2-mutation-reorder
  (let [c1 (built-command :trace/v2-reo-a)
        c2 (built-command :trace/v2-reo-b)
        r-forward  (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        r-reversed (:trace/root (rcmd/build-command-trace-v2 {:commands [c2 c1]}))]
    (is (not= r-forward r-reversed) "reordering commands changes root")))

(deftest trace-v2-mutation-substitution
  (let [c1 (built-command :trace/v2-sub-a)
        c2 (built-command :trace/v2-sub-b)
        c3 (built-command :trace/v2-sub-c)
        r12 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c2]}))
        r13 (:trace/root (rcmd/build-command-trace-v2 {:commands [c1 c3]}))]
    (is (not= r12 r13) "substituting a different valid command changes root")))

(deftest trace-v2-domain-separated-from-concatenate-bound
  (is (not= "CONFIDENCE_COMPOSITION_V1" (name rcmd/command-trace-v2-domain))
      "v2 trace domain tag is independent of concatenate-bound"))

(deftest trace-v2-domain-separated-from-combination
  (is (contains? hc/domain-tags rcmd/command-trace-v2-domain)
      "v2 trace domain tag is a registered canonical domain tag")
  (is (not= "COMPOSITION_COMBINATION_V1" (name rcmd/command-trace-v2-domain))
      "v2 trace domain tag is independent of combination"))

(deftest trace-v2-metrics-distinguishes-valid-from-invalid
  (let [c1 (built-command :trace/v2-metric-a)
        c2 (assoc (built-command :trace/v2-metric-b)
                  :command/hash "sha256:tampered-trace")
        metrics (rcmd/command-trace-metrics-v2 [c1 c2])]
    (is (= 2 (:command-count metrics)))
    (is (= 1 (:command-valid-count metrics)))
    (is (not (:trace/valid? metrics)))
    (is (some? (:trace/v2-root metrics))
        "trace root commits only the valid command")))

(deftest trace-v2-metrics-empty-collection
  (let [metrics (rcmd/command-trace-metrics-v2 [])]
    (is (= 0 (:command-count metrics)))
    (is (= 0 (:command-valid-count metrics)))
    (is (:trace/valid? metrics))
    (is (nil? (:trace/v2-root metrics))
        "empty trace produces no v2 root (no valid commands to sequence)")))

(deftest trace-v2-metrics-cannot-alter-trace-commitment
  (testing "trace metrics describe the trace; they never become part of trace identity"
    (let [c1 (built-command :trace/v2-metric-indep-a)
          c2 (built-command :trace/v2-metric-indep-b)
          trace (rcmd/build-command-trace-v2 {:commands [c1 c2]})
          metrics (rcmd/command-trace-metrics-v2 [c1 c2])
          ;; derived metrics must not appear in the committed sequence
          components (:trace/components trace)
          keys-present (filter (fn [k] (contains? trace k))
                               [:command-count :command-valid-count
                                :trace/valid? :trace/skipped])
          metric-keys (filter (fn [k] (contains? metrics k))
                              [:trace/components :trace/commitment])]
      (is (= 2 (:command-count metrics)))
      (is (= 2 (:command-valid-count metrics)))
      (is (= 2 (:trace/component-count trace)))
      (is (empty? keys-present)
          "no derived metric key leaks into the trace commitment map")
      (is (empty? metric-keys)
          "no committed-sequence key leaks into the metrics map")
      ;; the trace root is exactly over the two command hashes, in order
      (is (= (mapv :command/hash [c1 c2]) components)
          "trace commits only the ordered command hashes"))))

(deftest trace-v2-verify-recomputes-identical-root
  (testing "verifying the commitment bytes recovers the same components, so a
            verifier recomputes the same root as the builder"
    (let [c1 (built-command :trace/v2-recompute-a)
          c2 (built-command :trace/v2-recompute-b)
          trace (rcmd/build-command-trace-v2 {:commands [c1 c2]})
          vr (rcmd/verify-command-trace-v2 (:trace/commitment trace))
          recomputed-root (str "sha256:"
                               (seq/sequence-hash
                                {:purpose rcmd/command-trace-v2-purpose}
                                (:components (:trace vr))))]
      (is (:valid? vr))
      (is (= (:trace/components trace) (:components (:trace vr))))
      (is (= (:trace/root trace) recomputed-root)
          "root recomputed from recovered components matches the builder root"))))

;; ── Phase 2: research-command.v2 typed scope refs ──────────────────────────

(defn- built-command-v2
  [id & {:keys [includes]}]
  (rcmd/build-command
   (cond-> {:command/id id
            :command/type :benchmark-evaluation
            :command/argv ["prf" "benchmark" "run-and-report"]
            :schema-version rcmd/schema-version-v2}
     includes (assoc :command/includes includes))))

(deftest command-built-with-includes-v2-analysis
  (let [c (built-command-v2 :test/v2-analysis
                            :includes [{:kind :research-scope/analysis
                                        :ref :research-analysis/incentive}
                                       {:kind :research-scope/analysis
                                        :ref :research-analysis/incentive-compatibility}])]
    (is (rcmd/command-valid? c))
    (is (= rcmd/schema-version-v2 (:schema-version c)))
    (is (some? (:command/hash c)))
    (is (= 2 (count (:command/includes c))))
    (is (rcmd/valid-scope-ref? (first (:command/includes c))))))

(deftest command-built-with-includes-v2-dimension
  (let [c (built-command-v2 :test/v2-dim
                            :includes [{:kind :research-scope/dimension
                                        :ref :incentives/strategies}])]
    (is (rcmd/command-valid? c))
    (is (some? (:command/hash c)))))

(deftest command-v2-rejects-unknown-kind
  (is (thrown? clojure.lang.ExceptionInfo
               (built-command-v2 :test/v2-bad-kind
                                 :includes [{:kind :bogus :ref :anything}]))
      "unknown scope kind → rejected"))

(deftest command-v2-rejects-unknown-ref
  (is (thrown? clojure.lang.ExceptionInfo
               (built-command-v2 :test/v2-bad-ref
                                 :includes [{:kind :research-scope/dimension
                                             :ref :bogus}]))
      "unknown ref for valid kind → rejected"))

(deftest command-v2-legacy-migration-preserves-meaning
  (let [c (rcmd/build-command
           {:command/id :test/v2-legacy
            :command/type :benchmark-evaluation
            :command/argv ["prf" "benchmark"]
            :schema-version rcmd/schema-version-v2
            :command/include [:incentive]})]
    (is (rcmd/command-valid? c))
    (let [includes (:command/includes c)]
      (is (= [{:kind :research-scope/analysis
               :ref :research-analysis/incentive}]
             includes)
          ":incentive → :research-scope/analysis :research-analysis/incentive, not :incentives/participants"))))

(deftest command-v2-legacy-migration-both
  (let [c (rcmd/build-command
           {:command/id :test/v2-legacy-both
            :command/type :benchmark-evaluation
            :command/argv ["prf" "benchmark"]
            :schema-version rcmd/schema-version-v2
            :command/include [:incentive :incentive-compatibility]})]
    (is (rcmd/command-valid? c))
    (is (= (set [{:kind :research-scope/analysis :ref :research-analysis/incentive}
                 {:kind :research-scope/analysis :ref :research-analysis/incentive-compatibility}])
           (set (:command/includes c))))))

(deftest command-v2-hash-differs-from-v1
  (let [v1 (assoc minimal-command :command/include [:incentive])
        v2 (rcmd/build-command
            {:command/id :command/incentive-compatibility ;; same id
             :command/type :benchmark-evaluation
             :command/argv ["prf" "benchmark"]
             :schema-version rcmd/schema-version-v2
             :command/includes [{:kind :research-scope/analysis
                                 :ref :research-analysis/incentive}]})]
    (is (not= (:command/hash (rcmd/build-command v1))
              (:command/hash v2))
        "v1 and v2 commands produce different hashes — commitment compatibility is explicit")))

(deftest command-built-with-includes-v1-unchanged
  "The v1 fixture must produce the same :command/hash it always has.
   This is the golden hash assertion — if it changes, the migration
   broke v1 commitment compatibility."
  (let [c1 (rcmd/build-command minimal-command)
        c2 (rcmd/build-command minimal-command)]
    (is (= (:command/hash c1) (:command/hash c2))
        "v1 golden: deterministic command hash across builds")
    (is (= rcmd/schema-version (:schema-version c1))
        "v1 golden: schema version preserved")))

(deftest command-v2-not-v1-valid
  (let [v2 (built-command-v2 :test/v2-not-v1
                             :includes [{:kind :research-scope/analysis :ref :research-analysis/incentive}])]
    (is (rcmd/command-valid? v2))
    (is (not= rcmd/schema-version (:schema-version v2)))
    (is (= rcmd/schema-version-v2 (:schema-version v2)))))

(deftest command-v2-rejects-both-include-and-includes
  (is (thrown? clojure.lang.ExceptionInfo
               (rcmd/build-command
                {:command/id :test/v2-both
                 :command/type :benchmark-evaluation
                 :command/argv ["prf" "benchmark"]
                 :schema-version rcmd/schema-version-v2
                 :command/include [:incentive]
                 :command/includes [{:kind :research-scope/analysis
                                     :ref :research-analysis/incentive}]}))
      "cannot supply both :command/include and :command/includes for v2"))

(deftest command-v2-semantic-identity
  (let [c1 (built-command-v2 :test/v2-sem-a
                             :includes [{:kind :research-scope/analysis :ref :research-analysis/incentive}
                                        {:kind :research-scope/dimension :ref :incentives/strategies}])
        c2 (built-command-v2 :test/v2-sem-b
                             :includes [{:kind :research-scope/dimension :ref :incentives/strategies}
                                        {:kind :research-scope/analysis :ref :research-analysis/incentive}])]
    (is (rcmd/same-semantic-command? c1 c2)
        "ordering of includes does not affect v2 semantic identity")
    (let [c3 (built-command-v2 :test/v2-sem-c
                               :includes [{:kind :research-scope/analysis :ref :research-analysis/incentive-compatibility}])]
      (is (not (rcmd/same-semantic-command? c1 c3))
          "different scope refs → different semantic identity"))))
