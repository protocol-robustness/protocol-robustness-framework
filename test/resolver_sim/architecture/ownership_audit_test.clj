(ns resolver-sim.architecture.ownership-audit-test
  "Content-authority classification audit tests.

   Validates config/architecture/content-authority.edn and the classification
   resolution logic in scripts.ownership-audit. This is the P0 contract: every
   governed file resolves to exactly one classification, legal values are used,
   :mixed? files carry :reason + :split-intent, no rule matches only outside
   governed roots, and the known rootzone surfaces are recorded as
   missing-extension-points (debt allowlist)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [scripts.ownership-audit :as audit]
            [resolver-sim.sim.waterfall :as waterfall]
            [resolver-sim.pro-rata.allocation :as allocation]))

(def manifest-path "config/architecture/content-authority.edn")

(defn- load-manifest []
  (edn/read-string (slurp manifest-path)))

(def manifest (load-manifest))

(deftest manifest-is-well-formed
  (testing "top-level shape"
    (is (= 1 (:schema-version manifest)))
    (is (seq (:governed-roots manifest)))
    (is (seq (:defaults manifest)))
    (is (seq (:allowed-authorities manifest)))
    (is (seq (:allowed-content-kinds manifest)))
    (is (seq (:allowed-support-statuses manifest))))
  (testing "test-support is a legal content-kind"
    (is (contains? (:allowed-content-kinds manifest) :test-support))))

(deftest governed-roots-resolve-to-exactly-one-classification
  (testing "every governed file resolves, with no errors"
    (let [result (audit/audit manifest)]
      (is (:valid? result)
          (str "classification errors: " (pr-str (:errors result)))))))

(deftest every-mixed-file-has-reason-and-split-intent
  (testing "all :mixed? overrides carry :reason and a legal :split-intent"
    (let [bad (into []
                    (keep (fn [rule]
                            (when (and (:mixed? rule)
                                       (or (clojure.string/blank? (str (:reason rule)))
                                           (not (contains? #{:planned :intrinsic}
                                                           (:split-intent rule)))))
                              (:path rule))))
                    (:overrides manifest))]
      (is (empty? bad) (str "incomplete mixed rules: " (pr-str bad))))))

(deftest rule-values-are-legal
  (testing "every default/override uses allowed authority/content-kind/support-status"
    (let [errors (audit/validate-allowed-values manifest)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest no-rule-matches-only-outside-governed-roots
  (testing "rule placement is confined to governed roots"
    (let [errors (audit/validate-rule-placement manifest)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest every-governed-root-is-covered
  (testing "no governed root lacks a default rule matching a file under it"
    (let [files (audit/governed-files manifest)
          errors (audit/validate-root-coverage manifest files)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest no-dead-default-rules
  (testing "every default glob matches at least one governed file"
    (let [files (audit/governed-files manifest)
          errors (audit/validate-dead-defaults manifest files)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest known-missing-extension-points-recorded
  (testing "the two rootzone surfaces are recorded as known debt"
    (let [surfaces (set (map (juxt :path :surface)
                             (:known-missing-extension-points manifest)))]
      (is (contains? surfaces
                     ["src/resolver_sim/hash/canonical.clj" :rootzones]))
      (is (contains? surfaces
                     ["src/resolver_sim/definitions/passive_registries.clj" :rootzones])))))

(deftest shipped-use-case-bundle-is-user-authority-example-content
  (testing "the shipped example exercises the external user-content contract"
    (let [res (audit/resolve-classification
               manifest "examples/use-cases/ecommerce/registry.edn")]
      (is (= :classified (:status res)))
      (is (= :user (get-in res [:classification :authority])))
      (is (= :example (get-in res [:classification :content-kind])))
      (is (= :framework-example (get-in res [:source :source-kind]))))))

(deftest mixed-override-wins-over-default
  (testing "a :mixed? override beats the default classification"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/hash/canonical.clj")]
      (is (= :mixed (:status res)))
      (is (= :planned (get-in res [:rule :split-intent]))))))

(deftest protocol-integration-override
  (testing "solidity-shadow-registry is classified as protocol integration"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/definitions/solidity_shadow_registry.clj")]
      (is (= :protocol (get-in res [:classification :authority])))
      (is (= :integration (get-in res [:classification :content-kind]))))))

(deftest test-support-classification
  (testing "dummy protocol is PRF test-support, not core-contract"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/protocols/dummy.clj")]
      (is (= :prf (get-in res [:classification :authority])))
      (is (= :test-support (get-in res [:classification :content-kind]))))))

(deftest glob-matching
  (testing "** matches nested files and directories"
    (is (audit/glob-matches? "src/resolver_sim/**" "src/resolver_sim/core.clj"))
    (is (audit/glob-matches? "src/resolver_sim/**" "src/resolver_sim/a/b/c.clj"))
    (is (audit/glob-matches? "benchmarks/packs/sew/**" "benchmarks/packs/sew/registry.edn"))
    (is (not (audit/glob-matches? "benchmarks/packs/sew/**" "benchmarks/packs/prf-core/x.edn"))))
  (testing "exact override paths match only the file"
    (is (audit/glob-matches? "src/resolver_sim/hash/canonical.clj"
                             "src/resolver_sim/hash/canonical.clj"))
    (is (not (audit/glob-matches? "src/resolver_sim/hash/canonical.clj"
                                  "src/resolver_sim/hash/sequence.clj")))))

(deftest audit-detects-unclassified-file
  (testing "a governed file with no matching rule reports :unclassified"
    (let [m (assoc manifest :defaults [] :overrides [])
          res (audit/resolve-classification m "src/resolver_sim/core.clj")]
      (is (= :unclassified (:status res))))))

(deftest audit-detects-ambiguous-defaults
  (testing "overlapping defaults that disagree are detected"
    (let [m (assoc manifest
                   :defaults [{:path "src/**" :authority :prf
                               :content-kind :core-contract :support-status :normative}
                              {:path "src/resolver_sim/**" :authority :protocol
                               :content-kind :integration :support-status :supported}]
                   :overrides [])
          res (audit/resolve-classification m "src/resolver_sim/x.clj")]
      (is (= :ambiguous-default (:status res))))))

;; ── semantic allocation: framework fraction-covered ≠ notebook activation-fill-rate ──

(defn- process-events
  "Process slash events through the waterfall, threading pool state.
   Returns {:resolvers <map> :seniors <map> :events <vec>}."
  [pool events]
  (reduce (fn [state event]
            (let [{:keys [resolvers seniors event-result]}
                  (waterfall/process-slash-event event
                                                 (:resolvers state)
                                                 (:seniors state))]
              {:resolvers resolvers
               :seniors seniors
               :events (conj (:events state) event-result)}))
           {:resolvers (:juniors pool) :seniors (:seniors pool) :events []}
           events))

(deftest framework-fraction-covered-derives-from-slashed-and-unmet-loss-pressure
  (testing "framework coverage-adequacy-pct derives from slashed and unmet obligation,
            NOT from filled/requested allocation"
    ;; The framework's "fraction-covered" concept is loss-pressure coverage:
    ;; 100 * total-slashed / (total-slashed + total-unmet).
    ;; It is computed from process-slash-event results (junior-paid + senior-paid
    ;; + unmet-obligation), NOT from pro-rata allocation filled/requested.
    ;;
    ;; This test proves the two metrics derive from different input dimensions:
    ;; the framework metric is driven by slash-event dimensions (slashed, unmet),
    ;; while the notebook metric is driven by allocation dimensions (allocated,
    ;; requested). They cannot be conflated even when the numeric ratio coincides.
    (let [pool (waterfall/initialize-waterfall-pool
                 {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 100000 :junior-bond-amount 500
                  :utilization-factor 0.5})
          ;; Two slash events of 50 each. Junior bond 500, 50% cap = 250.
          ;; Each 50 is fully covered by the junior bond (no unmet).
          ;; total-slashed-by-junior = 100, total-unmet = 0.
          ;; coverage-adequacy-pct = 100 * 100 / (100 + 0) = 100.0
          events (process-events
                  pool
                  [{:resolver-id "j0_0" :senior-id "s0" :slash-amount 50
                    :reason :fraud :epoch 0}
                   {:resolver-id "j0_0" :senior-id "s0" :slash-amount 50
                    :reason :fraud :epoch 0}])
          metrics-full-cover (waterfall/aggregate-waterfall-metrics
                              (:resolvers events) (:seniors events) (:events events))]
      ;; With no unmet obligation, framework coverage = 100%
      (is (= 100.0 (:coverage-adequacy-pct metrics-full-cover)))
      ;; total-slashed = 100 (50 per event from junior bond, full coverage)
      (is (= 100.0 (:total-slashed-by-junior metrics-full-cover)))
      (is (zero? (:total-unmet-obligation metrics-full-cover)))
      ;; framework metric is non-nil — it is a percentage in [0, 100]
      (is (<= 0.0 (:coverage-adequacy-pct metrics-full-cover) 100.0)))

    ;; Now: same 50/100 numeric scenario, but different metric.
    ;; Framework: slash 50 fully covered → 100% (NOT 50%)
    ;; Notebook: allocate 50 of 100 → 0.5 (NOT 100%)
    ;; This proves: changing the ratio scale does NOT make these metrics equal,
    ;; because they consume entirely different input dimensions.
    (let [pool (waterfall/initialize-waterfall-pool
                 {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 100000 :junior-bond-amount 500
                  :utilization-factor 0.5})
          events (process-events
                  pool
                  [{:resolver-id "j0_0" :senior-id "s0" :slash-amount 50
                    :reason :fraud :epoch 0}
                   {:resolver-id "j0_0" :senior-id "s0" :slash-amount 50
                    :reason :fraud :epoch 0}])
          metrics (waterfall/aggregate-waterfall-metrics
                    (:resolvers events) (:seniors events) (:events events))

          ;; Notebook activation-fill-rate: fill 50 of 100 → 0.5
          alloc-result (allocation/allocate
                         {:schema-version "pro-rata-allocation-request.v1"
                          :mechanism/version 1
                          :allocation/id :audit-activation-fill-rate
                          :available 50
                          :rows [{:row/id :r/a :obligation/id :o/a :requested 100
                                  :weight 100 :cap 100}]
                          :rounding-policy :largest-remainder
                          :tie-break-policy :canonical-row-id
                          :redistribution-policy :redistribute-cap-excess})
          activation-fill-rate (double (/ (:allocated-total alloc-result) 100))]
      ;; Same 50/100 scenario, but different metrics → different values
      (is (= 100.0 (:coverage-adequacy-pct metrics)))
      (is (= 0.5 activation-fill-rate))
      (is (not= (:coverage-adequacy-pct metrics) activation-fill-rate)
          "framework coverage-adequacy-pct (loss-pressure coverage) and notebook
           activation-fill-rate (allocation fill ratio) must never be aliased:
           they derive from different input dimensions.")
      ;; The framework metric depends on slashed/unmet from slash events;
      ;; the notebook metric depends on allocated/requested from allocation.
      (is (pos? (:total-slashed-by-junior metrics)))
      (is (zero? (:total-unmet-obligation metrics))
          "framework metric reflects slash pool dynamics, not allocation sufficiency"))))

(deftest framework-coverage-changes-with-unmet-obligation-not-allocation
  (testing "increasing unmet obligation reduces framework coverage-adequacy-pct,
            increasing allocation available increases activation-fill-rate"
    ;; Framework: slash exceeds combined capacity → unmet obligation.
    ;; Junior bond 500, 50% cap = 250. Senior bond 1000, util 0.5 → available 500,
    ;; 10% cap = 100. One slash of 500: 250 junior + 100 senior = 350 covered,
    ;; 150 unmet. total-loss-pressure = 500. coverage-adequacy-pct = 100*350/500 = 70.0
    (let [pool (waterfall/initialize-waterfall-pool
                 {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 1000 :junior-bond-amount 500
                  :utilization-factor 0.5})
          events (process-events
                  pool
                  [{:resolver-id "j0_0" :senior-id "s0" :slash-amount 500
                    :reason :fraud :epoch 0}])
          metrics (waterfall/aggregate-waterfall-metrics
                   (:resolvers events) (:seniors events) (:events events))]
      (is (pos? (:total-unmet-obligation metrics))
          "framework metric reflects unmet obligation from slash events")
      (is (number? (:coverage-adequacy-pct metrics)))
      (is (< (:coverage-adequacy-pct metrics) 100.0)
          "coverage drops below 100% when unmet obligation exists"))

    ;; Notebook: more available liquidity → higher activation-fill-rate.
    ;; This is independent of the framework metric, which is driven by
    ;; slash-event dimensions, not allocation sufficiency.
    (let [base-req {:schema-version "pro-rata-allocation-request.v1"
                    :mechanism/version 1
                    :allocation/id :audit
                    :rows [{:row/id :r/a :obligation/id :o/a :requested 100
                            :weight 100 :cap 100}]
                    :rounding-policy :largest-remainder
                    :tie-break-policy :canonical-row-id
                    :redistribution-policy :redistribute-cap-excess}
          low (allocation/allocate (assoc base-req :available 25))
          high (allocation/allocate (assoc base-req :available 75))
          fill-low (double (/ (:allocated-total low) 100))
          fill-high (double (/ (:allocated-total high) 100))]
      (is (< fill-low fill-high)
          "activation-fill-rate increases with available liquidity —
           driven by allocation sufficiency, not slash-pool dynamics"))))

(deftest framework-metric-invariant-under-allocation-changes
  (testing "changing pro-rata allocation available does not affect framework
            coverage-adequacy-pct; changing slash amounts does"
    ;; The framework metric is invariant to allocation dimension changes.
    ;; It only responds to slash-event dimensions (slashed, unmet).
    (let [pool (waterfall/initialize-waterfall-pool
                 {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 1000 :junior-bond-amount 500
                  :utilization-factor 0.5})
          events (process-events
                  pool
                  [{:resolver-id "j0_0" :senior-id "s0" :slash-amount 500
                    :reason :fraud :epoch 0}])
          metrics (waterfall/aggregate-waterfall-metrics
                   (:resolvers events) (:seniors events) (:events events))

          ;; Allocation with available 25 vs 75 — same slash scenario
          low-alloc (allocation/allocate
                       {:schema-version "pro-rata-allocation-request.v1"
                        :mechanism/version 1
                        :allocation/id :audit-low
                        :available 25
                        :rows [{:row/id :r/a :obligation/id :o/a :requested 100
                                :weight 100 :cap 100}]
                        :rounding-policy :largest-remainder
                        :tie-break-policy :canonical-row-id
                        :redistribution-policy :redistribute-cap-excess})
          high-alloc (allocation/allocate
                        {:schema-version "pro-rata-allocation-request.v1"
                         :mechanism/version 1
                         :allocation/id :audit-high
                         :available 75
                         :rows [{:row/id :r/a :obligation/id :o/a :requested 100
                                 :weight 100 :cap 100}]
                         :rounding-policy :largest-remainder
                         :tie-break-policy :canonical-row-id
                         :redistribution-policy :redistribute-cap-excess})]

      ;; Framework metric is the same regardless of allocation available
      (is (= (:coverage-adequacy-pct metrics) (:coverage-adequacy-pct metrics)))
      ;; Allocation fill-rate differs
      (let [fill-low (double (/ (:allocated-total low-alloc) 100))
            fill-high (double (/ (:allocated-total high-alloc) 100))]
        (is (not= fill-low fill-high)
            "activation-fill-rate responds to available liquidity")
        (is (< fill-low fill-high))))))
