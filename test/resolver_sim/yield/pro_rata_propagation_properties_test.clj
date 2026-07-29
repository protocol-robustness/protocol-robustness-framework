(ns resolver-sim.yield.pro-rata-propagation-properties-test
  "Property-based tests for pro-rata propagation invariants.
   Implemented bottom-up: decisions → propagation artifacts → world reconciliation."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.properties.harness :as pbh]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.pro-rata-propagation-helpers :as h]))

;; ============================================================================
;; Property 4 — calculated decisions satisfy closed-form checks
;; ============================================================================

(defn- closed-form-checks
  "Call closed-form checks, returning results even when checks fail."
  [decision]
  (try
    (pf/partial-fill-closed-form-checks decision)
    (catch clojure.lang.ExceptionInfo e
      (:check-results (ex-data e)))))

(defn- gen-position
  "Generate a yield position with principal and yield buckets."
  []
  (gen/fmap
   (fn [[p ry dy]]
     (pos/make-position {:owner/id :test :module/id :fixed-rate :token :USDC
                         :principal p :realized-yield ry :deferred-yield dy}))
   (gen/tuple (gen/choose 1 500) (gen/choose 0 200) (gen/choose 0 200))))

(defn- check-pass?
  "True if a check result map has :status :pass."
  [check]
  (= :pass (:status check)))

(defn- check-id
  "Extract check id from a check result map."
  [check]
  (:check/id check))

;; ---------------------------------------------------------------------------
;; 4a: universal algebraic checks
;; ---------------------------------------------------------------------------

(deftest algebraic-checks-hold-for-any-decision
  "For any generated position and available liquidity, calculate-fulfillment
   produces a decision whose closed-form checks pass for all universal
   (mode-independent) check categories: conservation, capacity, per-claim
   bounds, non-negative amounts, and artifact format."
  (let [prop (prop/for-all [pos (gen-position)
                            liquidity (gen/choose 0 1000)
                            mode (gen/elements [:pro-rata :principal-first :waterfall])
                            rounding (gen/elements [:floor-and-carry :largest-remainder])]
                           (let [policy {:mode mode :rounding-policy rounding}
                                 decision (pf/calculate-fulfillment liquidity pos policy)
                                 checks (closed-form-checks decision)
                                 algebraic-ids #{:partial-fill/conservation
                                                 :partial-fill/capacity-bound
                                                 :partial-fill/per-claim-bound
                                                 :partial-fill/per-claim-conservation
                                                 :partial-fill/claim-key-consistency
                                                 :partial-fill/non-negative-amounts
                                                 :partial-fill/settlement-mode-consistency
                                                 :partial-fill/mode-valid
                                                 :partial-fill/deferred-haircut-overlap
                                                 :partial-fill/deferred-haircut-sum-bound
                                                 :partial-fill/evidence-self-consistency
                                                 :partial-fill/unrealized-bucket-valid
                                                 :partial-fill/decision-artifact-format}
                                 relevant (filter #(algebraic-ids (check-id %)) checks)]
                             (every? check-pass? relevant)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ---------------------------------------------------------------------------
;; 4b: pro-rata cross-product (divisible amounts, no caps)
;; ---------------------------------------------------------------------------

(deftest pro-rata-cross-product-holds
  "When :pro-rata mode is used without caps and with divisible amounts,
   the cross-product check passes: filled[i] * requested[j] == filled[j] * requested[i]."
  (let [;; Simple 2-bucket case where liquidity divides evenly
        pos (pos/make-position {:owner/id :test :module/id :fixed-rate :token :USDC
                                :principal 100 :realized-yield 60 :deferred-yield 0})
        decision (pf/calculate-fulfillment 80 pos {:mode :pro-rata})
        checks (closed-form-checks decision)
        cross-prod (first (filter #(= :partial-fill/pro-rata-cross-product (check-id %)) checks))]
    (is (check-pass? cross-prod)
        (str "Cross-product check failed: " cross-prod))))

(deftest principal-first-priority-holds
  "Under :principal-first mode, no yield bucket is filled when principal
   remains underfilled."
  (let [pos (pos/make-position {:owner/id :test :module/id :fixed-rate :token :USDC
                                :principal 200 :realized-yield 100 :deferred-yield 0})
        decision (pf/calculate-fulfillment 150 pos {:mode :principal-first})
        checks (closed-form-checks decision)
        priority (first (filter #(= :partial-fill/principal-first-priority (check-id %)) checks))]
    (is (check-pass? priority)
        (str "Principal-first priority failed: " priority))))

(deftest waterfall-priority-holds
  "Under :waterfall mode with strict fill-order, higher-priority buckets are
   filled before lower-priority ones."
  (let [pos (pos/make-position {:owner/id :test :module/id :fixed-rate :token :USDC
                                :principal 300 :realized-yield 100 :deferred-yield 50})
        decision (pf/calculate-fulfillment 200 pos {:mode :waterfall})
        checks (closed-form-checks decision)
        priority (first (filter #(= :partial-fill/waterfall-priority (check-id %)) checks))]
    (is (check-pass? priority)
        (str "Waterfall priority failed: " priority))))

(deftest largest-remainder-residual-zero
  "With :largest-remainder rounding and no caps, the residual is zero
   for partial-fill decisions (liquidity insufficient for all claims)."
  (let [prop (prop/for-all [pos (gen-position)
                            shortfall (gen/choose 1 200)]
                           (let [total (+ (:principal pos 0) (:realized-yield pos 0) (:deferred-yield pos 0))
                                 liquidity (max 0 (- total shortfall))
                                 decision (pf/calculate-fulfillment liquidity pos
                                                                    {:mode :pro-rata :rounding-policy :largest-remainder})
                                 checks (closed-form-checks decision)
                                 residual (first (filter #(= :partial-fill/rounding-residual-bounded (check-id %)) checks))]
                             (and (= :partial-fill (:settlement-mode decision))
                                  (check-pass? residual))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest floor-rounding-residual-bounded
  "With :floor-and-carry rounding, the residual is less than the number of
   positive claims (for partial-fill decisions)."
  (let [prop (prop/for-all [pos (gen-position)
                            shortfall (gen/choose 1 200)]
                           (let [total (+ (:principal pos 0) (:realized-yield pos 0) (:deferred-yield pos 0))
                                 liquidity (max 0 (- total shortfall))
                                 decision (pf/calculate-fulfillment liquidity pos
                                                                    {:mode :pro-rata :rounding-policy :floor-and-carry})
                                 checks (closed-form-checks decision)
                                 residual (first (filter #(= :partial-fill/rounding-residual-bounded (check-id %)) checks))]
                             (and (= :partial-fill (:settlement-mode decision))
                                  (check-pass? residual))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ============================================================================
;; Property 1 — applied propagation reconciles against world
;; ============================================================================

(deftest applied-propagation-reconciles
  "For any generated valid case, building and applying a propagation produces
   a world where check-pro-rata-accounting-reconciles passes.  Also verifies
   that the world contains the expected propagation and application identities,
   and that the independent oracle matches."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [world (h/build-propagations-from-case c)
                                 result (inv/check-pro-rata-accounting-reconciles world)
                                 propagations (:yield/pro-rata-propagations world)
                                 applications (:yield/applied-pro-rata-propagations world)]
                             (and
                    ;; World contains expected identities (not vacuously empty)
                              (seq propagations)
                              (seq applications)
                    ;; Reconciliation holds
                              (:holds? result)
                    ;; No silent failures — every expected check passed
                              (let [failed (remove #(= :pass %) (vals (:checks result)))]
                                (empty? failed))
                    ;; Oracle check: allocated matches
                              (= (h/expected-total-allocated c)
                                 (get-in (first (vals propagations)) [:summary :allocated]))
                    ;; Oracle check: participant IDs match
                              (= (h/expected-participant-ids c)
                                 (set (map :participant-id
                                           (:participants (first (vals propagations)))))))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest applied-propagation-reports-failed-checks-on-failure
  "When a known-invalid world is constructed, check-pro-rata-accounting-reconciles
   returns the specific failed check names.  This verifies that failure reporting
   is actionable and not vacuously empty."
  (let [world (h/build-propagations-from-case
               {:token-id :USDC :source-balance 100
                :participants [{:participant-id "alice" :eligible-obligation 60
                                :fulfilled 60 :deferred 0
                                :unmet 0 :waived 0 :obligation-after 0
                                :origin {:obligation-id :obl-alice
                                         :participant-id "alice" :sequence 1}}]})
        ;; Corrupt the source balance to trigger :source-balance-chain-broken
        world' (assoc-in world [:total-held :USDC] 999)
        result (inv/check-pro-rata-accounting-reconciles world')]
    (is (false? (:holds? result)) "Expected reconciliation to fail")
    (is (seq (:violations result)) "Expected at least one violation")
    (is (some #(= :latest-source-balance-mismatch (:reason %))
              (:violations result))
        "Expected latest-source-balance-mismatch violation")))

;; ============================================================================
;; Property 3 — hash determinism and sensitivity
;; ============================================================================

(defn- mutate-field
  "Replace a field in a map with a clearly different canonical value.
   The replacement is deterministic: strings reversed, keywords suffixed,
   numbers incremented, booleans toggled, etc."
  [m k]
  (update m k
          (fn [v]
            (cond
              (string? v) (str v "-mutated")
              (keyword? v) (keyword (str (name v) "-mutated"))
              (number? v) (+ v 1)
              (boolean? v) (not v)
              (map? v) (assoc v :mutated true)
              :else v))))

(defn- simple-position
  "Construct a minimal position map for hash testing, avoiding ratio values
   that the canonical encoder doesn't support."
  [principal realized-yield deferred-yield]
  {:owner/id :test
   :module/id :fixed-rate
   :token :USDC
   :position/id [:yield/position :test :fixed-rate :USDC]
   :principal (long principal)
   :realized-yield (long realized-yield)
   :deferred-yield (long deferred-yield)
   :unrealized-yield 0
   :principal-impairment 0
   :status :active
   :entry-index 1
   :shares 1})

(defn- gen-simple-position
  "Generate a minimal position with only integer values."
  []
  (gen/fmap
   (fn [[p ry dy]]
     (simple-position p ry dy))
   (gen/tuple (gen/choose 1 500) (gen/choose 0 200) (gen/choose 0 200))))

(deftest decision-hash-deterministic
  "Same position and liquidity always produce the same decision artifact hash."
  (let [prop (prop/for-all [pos (gen-simple-position)
                            liquidity (gen/choose 0 500)]
                           (let [policy {:mode :pro-rata :rounding-policy :largest-remainder}
                                 d1 (pf/decision-artifact pos (pf/calculate-fulfillment liquidity pos policy))
                                 d2 (pf/decision-artifact pos (pf/calculate-fulfillment liquidity pos policy))]
                             (= (:decision/hash d1) (:decision/hash d2))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest decision-hash-unrecomputed-mutation-invalidates
  "Changing any committed field while retaining the original hash
   causes decision-hash-valid? to return false."
  (let [prop (prop/for-all [pos (gen-simple-position)
                            liquidity (gen/choose 0 500)
                            k (gen/elements [:module/id :token :settlement-mode])]
                           (let [policy {:mode :pro-rata :rounding-policy :largest-remainder}
                                 artifact (pf/decision-artifact pos
                                                                (pf/calculate-fulfillment liquidity pos policy))
                                 mutated (mutate-field artifact k)]
                             (not (pf/decision-hash-valid? mutated))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest decision-hash-changes-when-input-changes
  "Changing the input to calculate-fulfillment produces a different
   decision artifact hash."
  (let [prop (prop/for-all [pos (gen-simple-position)
                            pos2 (gen-simple-position)
                            liquidity (gen/choose 0 500)]
                           (let [policy {:mode :pro-rata :rounding-policy :largest-remainder}
                                 h1 (:decision/hash (pf/decision-artifact pos
                                                                          (pf/calculate-fulfillment liquidity pos policy)))
                                 h2 (:decision/hash (pf/decision-artifact pos2
                                                                          (pf/calculate-fulfillment liquidity pos2 policy)))]
                  ;; Only assert different when positions differ
                             (or (= pos pos2) (not= h1 h2))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest application-hash-deterministic
  "Same application always produces the same hash."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [world (h/build-propagations-from-case c)
                                 app (first (vals (:yield/applied-pro-rata-propagations world)))
                                 h1 (pf/application-hash app)
                                 h2 (pf/application-hash app)]
                             (= h1 h2)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest application-hash-sensitive-to-committed-fields
  "Changing a committed field in the application produces a different hash."
  (let [prop (prop/for-all [c h/gen-any-case
                            k (gen/elements [:calculation-id :outcome-hash :policy-hash])]
                           (let [world (h/build-propagations-from-case c)
                                 app (first (vals (:yield/applied-pro-rata-propagations world)))
                                 original-hash (pf/application-hash app)
                                 mutated (mutate-field app k)
                                 new-hash (pf/application-hash mutated)]
                             (not= original-hash new-hash)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ============================================================================
;; Property 5 — propagation artifact structure invariants
;; ============================================================================

(def required-propagation-fields
  #{:propagation/id :calculation-ref :outcome-ref :token
    :summary :residual :participants :accounting-entries
    :accounting-entry-set-hash})

(def required-application-fields
  #{:schema-version :propagation-id :propagation/reference
    :calculation-id :outcome-hash :policy-hash :application-key
    :application-order :source-account :residual :participants
    :application/hash})

(deftest propagation-has-required-structure
  "Every generated propagation artifact has all required identity and
   accounting fields."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [world (h/build-propagations-from-case c)
                                 prop (first (vals (:yield/pro-rata-propagations world)))]
                             (and
                              (every? #(contains? prop %) required-propagation-fields)
                              (string? (:propagation/id prop))
                              (keyword? (:token prop))
                              (map? (:summary prop))
                              (contains? (:summary prop) :allocated)
                              (contains? (:summary prop) :available)
                              (contains? (:summary prop) :unallocated-residual)
                              (vector? (:participants prop))
                              (every? #(contains? % :participant-id) (:participants prop))
                              (every? #(contains? % :fulfilled) (:participants prop))
                              (every? #(contains? % :deferred) (:participants prop))
                              (vector? (:accounting-entries prop))
                              (string? (:accounting-entry-set-hash prop)))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest application-has-required-structure
  "Every generated application artifact has all required fields."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [world (h/build-propagations-from-case c)
                                 app (first (vals (:yield/applied-pro-rata-propagations world)))]
                             (and
                              (every? #(contains? app %) required-application-fields)
                              (= "pro-rata-propagation-application.v3" (:schema-version app))
                              (map? (:source-account app))
                              (contains? (:source-account app) :before)
                              (contains? (:source-account app) :delta)
                              (contains? (:source-account app) :after)
                              (vector? (:participants app))
                              (every? #(contains? % :withdrawn) (:participants app)))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest accounting-entry-set-is-balanced
  "The sum of all accounting entry deltas in a propagation is zero:
   debit (negative) + credits (positive) = 0."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [world (h/build-propagations-from-case c)
                                 entries (get-in (first (vals (:yield/pro-rata-propagations world)))
                                                 [:accounting-entries])]
                             (zero? (reduce + 0 (map :delta entries)))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest summary-conservation-holds
  "For every propagation: summary.available = summary.allocated + summary.unallocated-residual."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [summary (get-in (first (vals (:yield/pro-rata-propagations
                                                               (h/build-propagations-from-case c)))) [:summary])]
                             (= (:available summary 0)
                                (+ (:allocated summary 0) (:unallocated-residual summary 0)))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest source-account-arithmetic-holds
  "For every application: source-account.before + source-account.delta = source-account.after.
   (Delta is negative for debits.)"
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [sa (get-in (first (vals (:yield/applied-pro-rata-propagations
                                                          (h/build-propagations-from-case c)))) [:source-account])]
                             (= (:after sa 0) (+ (:before sa 0) (:delta sa 0)))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest participant-obligation-after-consistent
  "For every propagation participant: obligation-after = deferred.
   (Waived and unmet are always zero in the current policy.)"
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [participants (get-in (first (vals (:yield/pro-rata-propagations
                                                                    (h/build-propagations-from-case c)))) [:participants])]
                             (every? (fn [p]
                                       (and (= (:obligation-after p) (:deferred p 0))
                                            (zero? (:unmet p 0))
                                            (zero? (:waived p 0))))
                                     participants)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ============================================================================
;; Property 6 — permutation invariance
;; ============================================================================

(deftest propagation-is-order-independent
  "Reordering participant inputs produces the same propagation artifact
   (same participants, same accounting entries, same summary)."
  (let [prop (prop/for-all [c h/gen-any-case]
                           (let [reordered (update c :participants (fn [ps] (vec (shuffle ps))))
                                 w1 (h/build-propagations-from-case c)
                                 w2 (h/build-propagations-from-case reordered)
                                 p1 (first (vals (:yield/pro-rata-propagations w1)))
                                 p2 (first (vals (:yield/pro-rata-propagations w2)))]
                             (and
                              (= (set (map :participant-id (:participants p1)))
                                 (set (map :participant-id (:participants p2))))
                              (= (:summary p1) (:summary p2))
                              (= (set (map (juxt :entry/type :account :participant-id :delta)
                                           (:accounting-entries p1)))
                                 (set (map (juxt :entry/type :account :participant-id :delta)
                                           (:accounting-entries p2)))))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ============================================================================
;; Property 7 — multi-propagation chain reconciliation
;; ============================================================================

(deftest two-propagation-chain-reconciles
  "Two sequential propagations where the second draws from the first's
   residual produce a world where check-pro-rata-accounting-reconciles
   passes for both propagation entries."
  (let [prop (prop/for-all [[c1 c2] h/gen-two-case-chain]
                           (if-let [world (h/build-two-propagation-world c1 c2)]
                             (let [result (inv/check-pro-rata-accounting-reconciles world)]
                               (and (:holds? result)
                                    (let [failed (remove #(= :pass %) (vals (:checks result)))]
                                      (empty? failed))))
                             true))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest two-propagation-chain-has-continuous-source-balance
  "In a two-propagation chain, the second's source-account.before equals
   the first's source-account.after, and the world total-held matches
   the second's source-account.after."
  (let [prop (prop/for-all [[c1 c2] h/gen-two-case-chain]
                           (if-let [world (h/build-two-propagation-world c1 c2)]
                             (let [apps (:yield/applied-pro-rata-propagations world)
                                   app1 (get apps "p1")
                                   app2 (get apps "p2")
                                   sa1 (:source-account app1)
                                   sa2 (:source-account app2)]
                               (and (= (:after sa1) (:before sa2))
                                    (= (:after sa2) (get-in world [:total-held (:token sa2)]))))
                             true))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

;; ============================================================================
;; Property 8 — edge cases
;; ============================================================================

(deftest single-participant-propagation-reconciles
  "A propagation with a single participant passes all invariants."
  (let [prop (prop/for-all [c h/gen-case]
                           (let [single-participant (update c :participants #(vec (take 1 %)))
                                 world (h/build-propagations-from-case single-participant)
                                 result (inv/check-pro-rata-accounting-reconciles world)]
                             (:holds? result)))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))

(deftest zero-remaining-participants-rejected
  "A propagation where all participants have zero fulfillment produces
   an empty propagation that the invariant correctly rejects."
  (let [prop (prop/for-all [c h/gen-case]
                           (let [zero-ps (mapv (fn [p] (assoc p :fulfilled 0 :deferred (:eligible-obligation p 0)))
                                               (:participants c))
                                 case (assoc c :participants zero-ps)
                                 world (h/build-propagations-from-case case)
                                 result (inv/check-pro-rata-accounting-reconciles world)]
                             (false? (:holds? result))))
        res (tc/quick-check (pbh/trial-count) prop)]
    (is (:pass? res) (pbh/report-failure res))))
