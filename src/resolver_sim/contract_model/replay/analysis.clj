(ns resolver-sim.contract-model.replay.analysis
  "Error analysis and result finalization logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.set                    :as set]
            [clojure.string                 :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.protocols.registry :as protocol-registry]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.scenario.yield-metrics :as yield-metrics]
            [resolver-sim.scenario.yield-provider-metrics :as yield-provider-metrics]
            [resolver-sim.time.context :as time-ctx]))

(defn action->transition-id
  "Map an event action string/keyword to canonical transition semantic id.
   Keeps backward compatibility by tolerating hyphen/underscore forms."
  [action]
  (let [s (-> (if (keyword? action) (name action) (str action))
              str/lower-case
              (str/replace "-" "_"))
        k (keyword s)]
    (if (defs/transition-def k)
      (keyword "scenario.transition" s)
      (keyword "scenario.transition" "unknown"))))

(defn normalize-error-value
  [error]
  (cond
    (keyword? error) error
    (string? error)  (let [s (if (.startsWith ^String error ":")
                               (subs error 1)
                               error)]
                       (keyword s))
    :else error))

(defn expected-error-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn rejected-entry-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn analyze-expected-errors
  "Compare rejected trace entries against scenario :expected-errors.

   Returns {:ok? bool :matched [...] :missing [...] :unexpected [...]}.
   Matching is exact on [:seq :action :error]."
  [scenario trace]
  (let [expected        (vec (:expected-errors scenario []))
        expected-set    (set (map expected-error-key expected))
        rejected        (->> trace
                             (filter #(= :rejected (:result %)))
                             (map #(select-keys % [:seq :action :error]))
                             vec)
        rejected-set    (set (map rejected-entry-key rejected))
        matched-set     (set/intersection expected-set rejected-set)
        missing-set     (set/difference expected-set rejected-set)
        unexpected-set  (set/difference rejected-set expected-set)]
    {:ok?        (and (empty? missing-set) (empty? unexpected-set))
     :matched    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? matched-set (expected-error-key %)) expected)))
     :missing    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? missing-set (expected-error-key %)) expected)))
     :unexpected (vec (sort-by (juxt :seq :action)
                               (filter #(contains? unexpected-set (rejected-entry-key %)) rejected)))}))

(defn- merge-metrics-for-profile
  [result scenario flags]
  (case (:metrics-profile flags :sew-integrated)  ; Default is Sew-integrated
    :base result
    :yield-provider (-> result
                        (yield-metrics/merge-yield-metrics)
                        (yield-provider-metrics/merge-provider-metrics scenario))
    (yield-metrics/merge-yield-metrics result)))

(defn- appeal-bond-adapter-present?
  [result]
  (boolean (some :appeal-bond/transition-receipt (:trace result))))

(defn- finalize-protocol-evidence
  [scenario result]
  (when (appeal-bond-adapter-present? result)
    (when-let [finalize (protocol-registry/get-capability
                         "sew-v1" :replay/finalize-appeal-bond)]
      (finalize scenario result))))

(defn finalize-scenario-result
  "Apply yield metrics, expected-outcomes, and :expectations checks."
  ([scenario result] (finalize-scenario-result scenario result {}))
  ([scenario result flags]
   (let [result'      (merge-metrics-for-profile result scenario flags)
         protocol-evidence (finalize-protocol-evidence scenario result')
         result' (or (:result protocol-evidence) result')
         terminal-world (or (:world result') (:last-valid-world result'))
         prerequisite-violations (vec (:prerequisite-violations protocol-evidence))
         projection-ok? (:projection-ok? protocol-evidence true)
         projection-errors (:projection-errors protocol-evidence)
         resolution-reverted-on-failure?
         (boolean (some #(and (= :rejected (:result %))
                              (= "execute_resolution" (:action %)))
                        (:trace result')))
         result' (cond-> result'
                   (seq (:trace result')) (assoc-in [:metrics :resolution_reverted_on_failure]
                                                    resolution-reverted-on-failure?)
                   (seq (:trace result')) (assoc-in [:metrics :resolution_attempt_reverted]
                                                    resolution-reverted-on-failure?))
         outcomes     (expectations/analyze-expected-outcomes scenario (:trace result'))
         expect       (when (:expectations scenario)
                        (expectations/evaluate-expectations result' (:expectations scenario)))

         ;; BRIDGE: Apply theory evaluation
         result''     (theory-result/attach-three-way-model result'
                                                            (:theory-eval-opts scenario {})
                                                            :theory (:theory scenario))

         ;; Step 7: Add temporal evidence envelope
         time-ctx      (when terminal-world (time-ctx/temporal-context terminal-world))
         result-ev     (cond-> result''
                         time-ctx (assoc :time-evidence {:schema-version (or (:schema-version time-ctx) "temporal-context.v2")
                                                         :terminal-time time-ctx}))

         outcomes-ok? (:ok? outcomes true)
         expect-ok?   (or (nil? expect) (:ok? expect))
         theory-ok?   (or (nil? (:falsified? result-ev)) (not (:falsified? result-ev)))
         checks-ok?   (and projection-ok? outcomes-ok? expect-ok? theory-ok?)
         blocked?     (seq prerequisite-violations)]
     (let [base-result (assoc result-ev
                              :assurance/status (if blocked? :blocked (if checks-ok? :pass :failed))
                              :assurance/prerequisite-violations prerequisite-violations
                              :expected-outcomes outcomes
                              :expectations expect)]
       (cond
         blocked?
         (assoc base-result
                :outcome :blocked
                :halt-reason :assurance-prerequisite-unmet
                :expectation-violations
                (vec (concat prerequisite-violations (:violations outcomes []) (:violations expect []))))

         (and (= (:outcome result-ev) :pass) (not checks-ok?))
         (assoc base-result
                :outcome :fail
                :halt-reason (cond (not projection-ok?) :invalid-appeal-bond-projection
                                   (not outcomes-ok?) :expected-outcome-mismatch
                                   (not expect-ok?) :expectation-mismatch
                                   (not theory-ok?) :theory-falsified)
                :expectation-violations
                (vec (concat (when-not projection-ok? projection-errors)
                             (:violations outcomes [])
                             (:violations expect []))))

         :else base-result)))))
