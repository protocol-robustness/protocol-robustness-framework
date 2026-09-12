(ns resolver-sim.scenario.theory
  "Theory evaluator for schema-profile-driven scenarios.

   Determines whether a theoretical claim is falsified by replay metrics.
   This namespace is pure — no I/O, no DB, no side effects.

   ## Falsification semantics

   `falsifies-if` declares observable conditions that, if satisfied by the replay
   trace, contradict the scenario's theory claim. A satisfied condition means the
   theory is *falsified*, not validated.

   - Any falsification condition true → `:falsified`
   - All false and evidence complete → `:not-falsified`
   - Required metric evidence missing → `:inconclusive` (policy-dependent; strict never → `:falsified`)
   - Empty `falsifies-if` → `:not-falsified` when only mechanism/equilibrium apply

   Legacy vectors of flat metric predicates use OR semantics. Structured predicates
   preserve explicit logical meaning.

   Equilibrium validators are trace-consistency checks, not full game-theoretic proofs.

   ## Options

   See `resolver-sim.scenario.theory-eval/theory-eval-profiles` and
   `evaluate-theory` optional third argument.

   ## Full Schema Documentation

    See: docs/CDRS-v1.1-THEORY-SCHEMA.md"
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.scenario.equilibrium :as equilibrium]
            [resolver-sim.scenario.theory-eval :as theory-eval]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.scenario.theory-validation :as tv]))

;; ---------------------------------------------------------------------------
;; Shared value helpers (also used by scenario.expectations)
;; ---------------------------------------------------------------------------

(defn normalize-val
  "Normalize a value to a string for non-numeric equality comparisons."
  [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn to-kw
  "Coerce a value to a keyword, stripping any spurious leading colon."
  [x]
  (let [s  (if (keyword? x) (name x) (str x))
        s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

(defn metric-key
  "Keyword for metrics map lookup; preserves namespaced keys like :coalition/net-profit
   and multi-segment keys like :coalition/net/profit."
  [m]
  (cond
    (keyword? m) m
    (string? m)  (keyword m)
    :else (to-kw m)))

(defn- validate-theory-block
  "Validate a theory block.
   Delegates to resolver-sim.scenario.theory-validation/validate-theory.
   Returns nil or a vector of error strings."
  [theory]
  (let [result (tv/validate-theory theory)]
    (when-not (:valid? result)
      (:errors result))))

(defn try-number
  "Coerce value to a number (via edn parsing). Returns nil if not possible."
  [v]
  (cond
    (number? v) v
    (string? v) (try (let [parsed (clojure.edn/read-string v)]
                       (when (number? parsed) parsed))
                     (catch Exception _ nil))
    :else nil))

;; ---------------------------------------------------------------------------
;; Metric operator evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-metric-op
  "Evaluate a metric operation with robust numeric comparison.
   Uses == for numeric types (handles Long/Integer/Double equivalence).
   Falls back to normalized string equality for non-numeric values."
  [op actual target]
  (let [op-kw      (if (nil? op) := (to-kw op))
        num-actual (try-number actual)
        num-target (try-number target)]
    (case op-kw
      :=    (if (and num-actual num-target)
              (== num-actual num-target)
              (= (normalize-val actual) (normalize-val target)))
      :>    (if (and num-actual num-target) (> num-actual num-target) false)
      :<    (if (and num-actual num-target) (< num-actual num-target) false)
      :>=   (if (and num-actual num-target) (>= num-actual num-target) false)
      :<=   (if (and num-actual num-target) (<= num-actual num-target) false)
      :not= (not (if (and num-actual num-target)
                   (== num-actual num-target)
                   (= (normalize-val actual) (normalize-val target))))
      false)))

;; ---------------------------------------------------------------------------
;; Predicate evaluation
;; ---------------------------------------------------------------------------

(defn- extract-metrics-at-entry [entry]
  (:metrics entry))

(defn- empty-logical-result [operator]
  {:holds? false
   :reason :empty-logical-operator
   :operator operator
   :children []})

(defn- debug-log! [opts msg data]
  (when (and (:debug-theory? opts) (:theory-diagnostics-atom opts))
    (swap! (:theory-diagnostics-atom opts) conj (assoc data :message msg))))

(defn- eval-predicate
  "Recursively evaluate a predicate map against the trace, metrics, and world state.

   `trace-scope` is the slice of events visible to temporal operators
   (`:always`, `:eventually`, `:after`, `:before`).  Non-temporal operators
   pass it through unchanged.  Top-level call from `evaluate-theory` passes
   the full trace as the initial scope."
  [protocol world trace-scope metrics predicate opts]
  (cond
    (:and predicate)
    (let [children (:and predicate)]
      (if (empty? children)
        (empty-logical-result :and)
        (let [results (mapv #(eval-predicate protocol world trace-scope metrics % opts) children)]
          (debug-log! opts "evaluated :and" {:operator :and :child-count (count results)})
          {:holds? (every? :holds? results) :operator :and :children results})))

    (:or predicate)
    (let [children (:or predicate)]
      (if (empty? children)
        (empty-logical-result :or)
        (let [results (mapv #(eval-predicate protocol world trace-scope metrics % opts) children)]
          (debug-log! opts "evaluated :or" {:operator :or :child-count (count results)})
          {:holds? (some :holds? results) :operator :or :children results})))

    (:not predicate)
    (let [result (eval-predicate protocol world trace-scope metrics (:not predicate) opts)]
      {:holds? (not (:holds? result)) :operator :not :children [result]})

    (:implies predicate)
    (let [if-result (eval-predicate protocol world trace-scope metrics (:if predicate) opts)
          then-result (eval-predicate protocol world trace-scope metrics (:then predicate) opts)]
      {:holds? (or (not (:holds? if-result)) (:holds? then-result))
       :operator :implies
       :children [if-result then-result]})

    (:always predicate)
    (let [results (mapv #(eval-predicate protocol world trace-scope
                                         (extract-metrics-at-entry %)
                                         (:always predicate) opts)
                        trace-scope)]
      {:holds? (every? :holds? results) :operator :always :children results})

    (:eventually predicate)
    (let [results (mapv #(eval-predicate protocol world trace-scope
                                         (extract-metrics-at-entry %)
                                         (:eventually predicate) opts)
                        trace-scope)]
      {:holds? (some :holds? results) :operator :eventually :children results})

    (:after predicate)
    (let [{:keys [event predicate]} (:after predicate)
          idx (first (keep-indexed #(when (= (:action %2) event) %1) trace-scope))
          sub-scope (if idx (drop (inc idx) trace-scope) [])]
      (if (empty? sub-scope)
        {:holds? false :reason :event-not-found :event event}
        (let [results (mapv #(eval-predicate protocol world sub-scope
                                             (extract-metrics-at-entry %)
                                             predicate opts)
                            sub-scope)]
          {:holds? (every? :holds? results) :operator :after :children results})))

    (:before predicate)
    (let [{:keys [event predicate]} (:before predicate)
          idx (first (keep-indexed #(when (= (:action %2) event) %1) trace-scope))
          sub-scope (if idx (take idx trace-scope) [])]
      (if (empty? sub-scope)
        {:holds? false :reason :event-not-found :event event}
        (let [results (mapv #(eval-predicate protocol world sub-scope
                                             (extract-metrics-at-entry %)
                                             predicate opts)
                            sub-scope)]
          {:holds? (every? :holds? results) :operator :before :children results})))

    (:state predicate)
    (let [proj-val (proto/project-state protocol world (:query predicate))
          missing? (nil? proj-val)
          holds?   (if missing? false (evaluate-metric-op (:op predicate) proj-val (:value predicate)))]
      {:holds? holds? :kind :state
       :missing-query-result? missing?
       :state (:query predicate) :op (:op predicate) :value (:value predicate) :actual proj-val
       :truth-status (if missing? :unknown (if holds? :true :false))})

    :else
    (let [metric-kw (metric-key (:metric predicate))
          actual    (get metrics metric-kw)
          missing?  (nil? actual)
          holds?    (evaluate-metric-op (:op predicate) actual (:value predicate))]
      {:holds? holds? :kind :metric-leaf
       :metric metric-kw
       :op (:op predicate) :value (:value predicate)
       :actual actual :missing-metric? missing?
       :truth-status (if missing? :unknown (if holds? :true :false))})))

(defn- leaf-conditions [conds]
  (filter map? conds))

(defn- all-metrics-missing? [metrics conds]
  (let [leaves (vec (leaf-conditions conds))]
    (and (seq leaves)
         (every? #(nil? (get metrics (metric-key (:metric %)))) leaves))))

(defn- collect-missing-metrics
  "Walk an eval tree and return metric keywords referenced by leaves with nil actual."
  [tree]
  (cond
    (nil? tree) []
    (:missing-metric? tree) [(:metric tree)]
    (:children tree) (vec (distinct (mapcat collect-missing-metrics (:children tree))))
    :else []))

(defn- collect-evaluated-leaves
  [tree]
  (cond
    (nil? tree) []
    (= :metric-leaf (:kind tree))
    [(select-keys tree [:metric :op :value :actual :holds? :missing-metric? :truth-status])]

    (= :state (:kind tree))
    [(select-keys tree [:state :op :value :actual :holds? :kind :missing-query-result?])]

    (:children tree) (vec (mapcat collect-evaluated-leaves (:children tree)))
    :else []))

(defn normalize-telemetry-evidence
  "Flat, reviewer-oriented evidence rows derived from an eval tree."
  [eval-tree]
  (mapv
   (fn [leaf]
     (if (= :state (:kind leaf))
       {:kind  :state
        :state (:state leaf)
        :actual (:actual leaf)
        :op    (:op leaf)
        :value (:value leaf)
        :threshold (:value leaf)
        :holds? (:holds? leaf)
        :missing? (boolean (:missing-query-result? leaf))
        :truth-status (get leaf :truth-status (if (:holds? leaf) :true :false))
        :source [:terminal-world :projection (:state leaf)]}
       (let [missing? (boolean (:missing-metric? leaf))]
         {:kind  :metric
          :metric (:metric leaf)
          :actual (:actual leaf)
          :op    (:op leaf)
          :value (:value leaf)
          :threshold (:value leaf)
          :holds? (:holds? leaf)
          :missing? missing?
          :truth-status (or (:truth-status leaf)
                            (if missing? :unknown (if (:holds? leaf) :true :false)))
          :source [:metrics (:metric leaf)]})))
   (collect-evaluated-leaves eval-tree)))

(defn- empty-logical-in-tree? [tree]
  (or (= :empty-logical-operator (:reason tree))
      (some empty-logical-in-tree? (:children tree []))))

(defn- apply-empty-logical-policy [policy tree]
  (when (empty-logical-in-tree? tree)
    (case policy
      :fail {:status :falsified :reason :empty-logical-operator}
      :inconclusive {:status :inconclusive :reason :empty-logical-operator}
      nil)))

(defn- apply-missing-metric-policy
  [{:keys [missing-metric-policy]} metrics conds missing-metrics falsified?]
  (cond
    falsified? nil

    (and (all-metrics-missing? metrics conds)
         (not= :ignore-missing-leaves missing-metric-policy))
    {:status :inconclusive :reason :metrics-missing-in-trace}

    (and (seq missing-metrics)
         (= :any-missing-fail missing-metric-policy))
    {:status :inconclusive :reason :strict-missing-metrics}

    (and (seq missing-metrics)
         (= :any-missing-inconclusive missing-metric-policy))
    {:status :inconclusive :reason :partial-metrics-missing}

    :else nil))

(defn- build-diagnostics
  [eval-res missing-metrics]
  (let [missing (vec (distinct missing-metrics))]
    {:missing-metrics missing
     :evaluated-predicates (collect-evaluated-leaves eval-res)
     :warnings (when (seq missing)
                 (mapv (fn [m] {:kind :missing-metric :metric m}) missing))}))

(defn- include-telemetry-evidence? [opts]
  (boolean (or (:include-telemetry-evidence? opts)
               (:notebook-export? opts)
               (:debug-theory? opts))))

(defn- finalize-metric-result
  "Apply derived diagnostics; optional flat :telemetry-evidence when requested."
  [metric-result opts theory]
  (let [opts'       (theory-eval/resolve-theory-eval-opts opts)
        with-metric (assoc metric-result :metric-status (:status metric-result))
        res         (theory-result/attach-three-way-model with-metric opts' :theory theory)
        eval-tree   (first (:evidence res))]
    (cond-> (dissoc res :telemetry-evidence)
      (and (include-telemetry-evidence? opts') eval-tree)
      (assoc :telemetry-evidence (normalize-telemetry-evidence eval-tree)))))

(defn evaluate-theory
  "Evaluate theory falsification and optional mechanism/equilibrium proxies.

   Canonical metric-track contract (`theory-result/canonical-keys`):
     :status — fixture/audit gate (:falsified | :not-falsified | :inconclusive | :not-evaluated)
     :reason — why that status was assigned
     :falsified? — whether a falsification condition fired
     :evidence — machine-readable eval tree
     :diagnostics — missing metrics, evaluated leaves, warnings, derived interpretation

   Derived fields live under :diagnostics. Legacy top-level copies only when
   :include-legacy-derived-top-levels? is true. Flat :telemetry-evidence only
   when :include-telemetry-evidence? (or :notebook-export? / :debug-theory?) is set.

   Optional third argument `opts` (see `theory-eval/resolve-theory-eval-opts`):
   :theory-eval-profile — :regression (default) | :optimistic | :strict | :public-evidence
   (legacy aliases :exploratory / :authoring → :optimistic)
   :missing-metric-policy — override profile
   :include-legacy-derived-top-levels? — default false (deprecated)
   :include-telemetry-evidence? — default false
   :debug-theory? — append to :theory-diagnostics-atom; also enables :telemetry-evidence"
  ([result theory]
   (evaluate-theory result theory {}))
  ([result theory opts]
   (if (nil? theory)
     (finalize-metric-result
      {:status              :not-evaluated
       :reason              :theory-missing
       :falsified?          false
       :evidence            []
       :diagnostics         {}
       :mechanism-results   {}
       :mechanism-status    :not-checked
       :equilibrium-results {}
       :equilibrium-status  :not-checked}
      opts theory)
     (let [opts'     (theory-eval/resolve-theory-eval-opts opts)
           v-errors  (validate-theory-block theory)
           _ (when (and (:debug-theory? opts') (seq v-errors))
               (debug-log! opts' "theory-validation-failed" {:errors v-errors}))]
       (if (seq v-errors)
         (case (:validator-error-policy opts' :inconclusive)
           :throw (throw (ex-info "Theory validation failed" {:errors v-errors}))
           :fail (finalize-metric-result
                  {:status :inconclusive :reason :invalid-theory-block
                   :falsified? false :evidence []
                   :diagnostics {:validation-errors v-errors}
                   :mechanism-results {} :mechanism-status :not-checked
                   :equilibrium-results {} :equilibrium-status :not-checked}
                  opts' theory)
           (finalize-metric-result
            {:status :inconclusive :reason :invalid-theory-block
             :falsified? false :evidence []
             :diagnostics {:validation-errors v-errors}
             :mechanism-results {} :mechanism-status :not-checked
             :equilibrium-results {} :equilibrium-status :not-checked}
            opts' theory))
         (let [protocol  (:protocol result)
               world     (:terminal-world result)
               trace     (:trace result [])
               metrics   (:metrics result)
               conds     (:falsifies-if theory [])

           ;; Cheap predicate check
               predicate (when (seq conds)
                           (if (and (vector? conds) (map? (first conds)))
                             {:or conds}
                             conds))
               eval-res  (when predicate
                           (eval-predicate protocol world trace metrics predicate opts'))
               falsified? (boolean (:holds? eval-res))

           ;; Short-circuit SPE evaluation
               skip-spe? (or falsified? (= :fail (:outcome result)))
               eq-result (when (and (not skip-spe?)
                                    (or (seq (:mechanism-properties theory))
                                        (seq (:equilibrium-concept theory))))
                           (try
                             (equilibrium/evaluate-equilibrium theory result)
                             (catch Exception e
                               (case (:validator-error-policy opts' :inconclusive)
                                 :throw (throw e)
                                 :fail {:mechanism-results {}
                                        :mechanism-status :fail
                                        :equilibrium-results {}
                                        :equilibrium-status :fail
                                        :validator-error {:class (str (type e))
                                                          :message (.getMessage e)}}
                                 {:mechanism-results {}
                                  :mechanism-status :inconclusive
                                  :equilibrium-results {}
                                  :equilibrium-status :inconclusive
                                  :validator-error {:class (str (type e))
                                                    :message (.getMessage e)}}))))
               merge-eq  (fn [base]
                           (merge base
                                  (if eq-result
                                    (select-keys eq-result [:mechanism-results :mechanism-status
                                                            :equilibrium-results :equilibrium-status
                                                            :evidence-schema-version :equilibrium-claim-tier
                                                            :equilibrium-trust-mode :provenance
                                                            :validator-error])
                                    {:mechanism-results   {}
                                     :mechanism-status    (if skip-spe? :skipped :not-checked)
                                     :equilibrium-results {}
                                     :equilibrium-status  (if skip-spe? :skipped :not-checked)})))]
           (cond
             (empty? conds)
             (finalize-metric-result
              (merge-eq {:status     :not-falsified
                         :reason     :no-metric-falsification-claim
                         :falsified? false
                         :evidence   []
                         :diagnostics {}})
              opts' theory)

             :else
             (let [missing   (collect-missing-metrics eval-res)
                   diagnostics (build-diagnostics eval-res missing)
                   empty-logical (apply-empty-logical-policy (:empty-logical-policy opts') eval-res)
                   missing-policy (or empty-logical
                                      (apply-missing-metric-policy opts' metrics conds missing falsified?))
                   base (if missing-policy
                          (merge missing-policy
                                 {:falsified? (= :falsified (:status missing-policy))
                                  :evidence [eval-res]
                                  :diagnostics diagnostics})
                          (if falsified?
                            {:status :falsified :reason :falsification-triggered
                             :falsified? true :evidence [eval-res]
                             :diagnostics diagnostics}
                            {:status :not-falsified :reason :predicate-not-satisfied
                             :falsified? false :evidence [eval-res]
                             :diagnostics diagnostics}))]
               (finalize-metric-result (merge-eq base) opts' theory)))))))))