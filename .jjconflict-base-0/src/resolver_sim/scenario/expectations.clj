(ns resolver-sim.scenario.expectations
  "Expectation evaluator for CDRS v1.1 scenarios.

   Checks execution-level pass/fail criteria: named invariants, terminal world
   state, and metric assertions. This namespace is pure — no I/O, no DB.

   Depends on resolver-sim.scenario.theory for shared value helpers and
   evaluate-metric-op. The split is intentional: theory evaluation (claim
   falsification) and expectation evaluation (execution correctness) are
   separate concerns that may have different failure semantics."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.theory :as theory]
            [resolver-sim.yield.invariants :as yield-inv]))

;; ---------------------------------------------------------------------------
;; Key-relaxed world lookup
;; ---------------------------------------------------------------------------

(defn- try-parse-int [k]
  (if (string? k)
    (try (Integer/parseInt k) (catch Exception _ nil))
    k))

(defn- get-relaxed
  "Get key k from map m, tolerating integer/string key type mismatch.
   Tries: exact key → integer parse → keyword parse → string of keyword."
  [m k]
  (let [int-k (try-parse-int k)
        kw-k  (when (string? k) (keyword k))]
    (cond
      (contains? m k)                          (get m k)
      (and int-k (contains? m int-k))          (get m int-k)
      (and kw-k (contains? m kw-k))            (get m kw-k)
      (and (keyword? k) (contains? m (name k))) (get m (name k))
      :else                                    (get m k))))

(defn- get-in-relaxed [m path]
  (reduce get-relaxed m path))

(defn- try-number [v]
  (cond
    (number? v) v
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

(defn- normalize-path-segment [seg]
  (cond
    (and (vector? seg) (= 2 (count seg)))
    [(if (keyword? (first seg)) (first seg) (keyword (str (first seg))))
     (try-number (second seg))]

    (and (string? seg) (.contains ^String seg "/"))
    (let [parts (str/split seg #"/")]
      (if (and (= 3 (count parts))
               (= "sew" (first parts))
               (= "escrow" (second parts))
               (re-matches #"\d+" (nth parts 2)))
        [(keyword "sew/escrow") (Long/parseLong (nth parts 2))]
        (mapv keyword parts)))

    (string? seg) (case (keyword seg)
                    :yield-positions :yield/positions
                    :yield-indices   :yield/indices
                    :yield-held      :yield/held-balances
                    (keyword seg))

    (= seg :yield-positions) :yield/positions
    (= seg :yield-indices)   :yield/indices
    (= seg :yield-held)      :yield/held-balances

    :else seg))

(defn- normalize-path [path]
  (mapv normalize-path-segment path))

(defn- metric-within-slack? [actual target slack]
  (let [a (try-number actual)
        t (try-number target)
        s (long (or slack 0))]
    (and a t (<= (Math/abs (- a t)) s))))

(defn- terminal-check-ok? [actual spec]
  (let [op (or (:op spec) :=)]
    (if (:equals spec)
      (if (= op :=)
        (= (theory/normalize-val actual) (theory/normalize-val (:equals spec)))
        (theory/evaluate-metric-op op actual (:equals spec)))
      (theory/evaluate-metric-op op actual (:value spec)))))

;; ---------------------------------------------------------------------------
;; Invariant evaluation
;; ---------------------------------------------------------------------------

(defn- yield-invariant-registered? [inv-kw]
  (contains? (set (yield-inv/registered-ids)) inv-kw))

(defn evaluate-invariants
  "Check named invariants from :expectations/:invariants against the replay result.

   Lookup strategy:
   1. Per-step map: result[:metrics :invariant-results] {inv-kw :fail}.
   2. Final world: for registered yield invariants, re-run on trace end world.
   3. Aggregate fallback when other invariants fired but this one is unconfirmed.

   Returns {:ok? bool :violations [v-map]}"
  [result named-invariants]
  (if (empty? named-invariants)
    {:ok? true :violations []}
    (let [inv-map        (get-in result [:metrics :invariant-results] {})
          agg-violations (get-in result [:metrics :invariant-violations] 0)
          last-world     (get (last (:trace result)) :world)
          violations     (atom [])]
      (doseq [inv-name named-invariants]
        (let [inv-kw     (if (keyword? inv-name) inv-name (keyword (str inv-name)))
              step-fail? (and (contains? inv-map inv-kw)
                              (= :fail (get inv-map inv-kw)))
              world-ok?  (if (and last-world (yield-invariant-registered? inv-kw))
                           (yield-inv/holds? inv-kw last-world)
                           true)
              fail?      (or step-fail?
                             (not world-ok?)
                             (and (pos? agg-violations)
                                  (not (contains? inv-map inv-kw))
                                  (not (yield-invariant-registered? inv-kw))))]
          (when fail?
            (swap! violations conj
                   {:type      :invariant-failed
                    :invariant inv-kw
                    :note      (cond
                                 step-fail?
                                 "per-invariant result: fail"

                                 (not world-ok?)
                                 "final world check: fail"

                                 :else
                                 (str "aggregate fallback: "
                                      agg-violations " violation(s) in run"))}))))
      {:ok? (empty? @violations)
       :violations @violations})))

;; ---------------------------------------------------------------------------
;; Expectations evaluation
;; ---------------------------------------------------------------------------

(defn analyze-expected-outcomes
  "Validate per-step :expected-outcomes against the replay trace.

   Each entry: {:seq n :action \"...\" :expect \"ok\"|\"rejected\"}."
  [scenario trace]
  (let [expected (vec (or (:expected-outcomes scenario) []))
        by-seq   (into {} (map (juxt :seq identity) trace))]
    (if (empty? expected)
      {:ok? true :violations []}
      (let [violations
            (for [{:keys [seq action expect]} expected
                  :let [entry (get-relaxed by-seq seq)
                        want  (keyword (or expect "ok"))
                        got   (:result entry)]
                  :when (or (nil? entry)
                            (not= (name got) (name want)))]
              {:type     :expected-outcome-mismatch
               :seq      seq
               :action   action
               :expected want
               :actual   got
               :error    (:error entry)})]
        {:ok? (empty? violations) :violations (vec violations)}))))

(defn evaluate-expectations
  "Evaluate execution-level pass/fail criteria from an :expectations block.

   Checks in order:
     0. :invariants — named invariant checks (aggregate fallback if no per-invariant map)
     1. :terminal   — world state at end of trace (path + :equals or :op/:value)
     2. :step-terminal — world snapshot after a given :seq
     3. :metrics    — named metric assertions (op + value, optional :slack)

   Returns {:ok? bool
            :violations [v-map]
            :summary {:expectations/total n
                      :expectations/passed m
                      :expectations/failed k
                      :expectations/not-evaluated j}}

   The summary is derived from evaluated results, never from declarations or
   caller-supplied counts: every declared expectation is accounted as passed,
   failed, or not-evaluated. A declared metric whose key is absent from the
   result metrics is reported as a :metric-not-evaluated violation (fail-closed)
   rather than silently omitted, so a missing metric can never masquerade as a
   pass."
  [result expectations]
  (let [metrics    (:metrics result)
        trace      (:trace result)
        last-world (get (last trace) :world)
        summary    (atom {:expectations/total 0
                          :expectations/passed 0
                          :expectations/failed 0
                          :expectations/not-evaluated 0})
        violations (atom [])
        pass!      (fn [] (swap! summary update :expectations/passed inc))
        fail!      (fn [v]
                     (swap! summary update :expectations/failed inc)
                     (swap! violations conj v))
        note!      (fn [v]
                     (swap! summary update :expectations/not-evaluated inc)
                     (swap! violations conj v))]

    ;; 0. Named invariant checks
    (let [declared (count (:invariants expectations []))
          inv-res  (evaluate-invariants result (:invariants expectations []))
          inv-fails (count (:violations inv-res))]
      (swap! summary update :expectations/total + declared)
      (swap! summary update :expectations/passed + (- declared inv-fails))
      (swap! summary update :expectations/failed + inv-fails)
      (doseq [v (:violations inv-res)]
        (swap! violations conj v)))

    ;; 1. Terminal state checks
    (doseq [t (:terminal expectations)]
      (swap! summary update :expectations/total inc)
      (let [path   (normalize-path (:path t))
            actual (get-in-relaxed last-world path)]
        (if (terminal-check-ok? actual t)
          (pass!)
          (fail! {:type     :terminal-mismatch
                  :path     path
                  :expected (or (:equals t) (:value t))
                  :op       (:op t :=)
                  :actual   actual}))))

    ;; 2. Step terminal checks
    (doseq [t (:step-terminal expectations)]
      (swap! summary update :expectations/total inc)
      (let [seq-t  (if (string? (:seq t)) (Integer/parseInt (:seq t)) (:seq t))
            entry  (first (filter #(= seq-t (:seq %)) trace))
            world  (:world entry)
            path   (normalize-path (:path t))
            actual (get-in-relaxed world path)]
        (if (terminal-check-ok? actual t)
          (pass!)
          (fail! {:type     :step-terminal-mismatch
                  :seq      (:seq t)
                  :path     path
                  :expected (or (:equals t) (:value t))
                  :op       (:op t :=)
                  :actual   actual}))))

    ;; 3. Metric expectations
    (doseq [m (:metrics expectations)]
      (swap! summary update :expectations/total inc)
      (let [actual (get metrics (theory/metric-key (:name m)))
            target (:value m)
            slack  (:slack m)
            ok?    (if slack
                     (metric-within-slack? actual target slack)
                     (theory/evaluate-metric-op (:op m) actual target))]
        (cond
          (nil? actual)
          (note! {:type :metric-not-evaluated
                  :name (:name m)
                  :op   (:op m)
                  :expected target
                  :slack slack
                  :note "declared metric absent from result metrics"})

          ok?
          (pass!)

          :else
          (fail! {:type     :metric-violation
                  :name     (:name m)
                  :op       (:op m)
                  :expected target
                  :slack    slack
                  :actual   actual}))))

    {:ok? (empty? @violations)
     :violations @violations
     :summary @summary}))
