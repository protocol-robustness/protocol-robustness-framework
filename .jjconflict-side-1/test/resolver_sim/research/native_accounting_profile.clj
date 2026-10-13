(ns resolver-sim.research.native-accounting-profile
  "Opt-in current-JVM profiling. Var wrappers exist only during a measurement;
   no observer or timing enters canonical work/results. Inclusive stage totals
   overlap and are not additive or elapsed critical-path time."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.runner-transport :as transport]))

(def ^:private measurement-lock (Object.))
(def ^:dynamic *role* :caller)
(def ^:dynamic *validation?* false)

(defn- instrument [stats original stage role validation? enabled?]
  (fn [& args]
    (if (and enabled? (not (enabled? args)))
      (apply original args)
      (binding [*role* (or role *role*)
                *validation?* (or validation? *validation?*)]
        (let [started (System/nanoTime)
              key [*role* (if *validation?*
                            (case stage
                              :partition-arithmetic :partition-arithmetic-recomputation
                              :selected-implementation-arithmetic :selected-implementation-recomputation
                              stage)
                            stage)]]
          (try
            (apply original args)
            (finally
              (let [duration (- (System/nanoTime) started)]
                (swap! stats update key
                       (fn [old]
                         {:calls (inc (or (:calls old) 0))
                          :inclusive-ns (+ (or (:inclusive-ns old) 0) duration)}))))))))))

(defn profile-call
  "Measure one isolated harness invocation, including its local HTTP threads.
   Remote-process stages are explicitly unobserved. This uses temporary global
   Var wrappers; run without unrelated workload activity in this JVM."
  [f]
  (locking measurement-lock
    (let [stats (atom {})
          transport-var #(ns-resolve 'resolver-sim.research.runner-transport %)
          journal? #(some? (:transport/state-path (first %)))
          definitions [[#'native/execute-work! :execution :executor false nil]
                       [(ns-resolve 'resolver-sim.research.native-accounting-work
                                    'validate-execution-after-work-validation)
                        :execution-validation nil true nil]
                       [#'native/computed-partial-values :selected-implementation-arithmetic nil false nil]
                       [#'accounting/partial-values :partition-arithmetic nil false nil]
                       [#'accounting/valid-partial-result? :partial-validation nil true nil]
                       [#'accounting/valid-request? :accounting-request-validation nil true nil]
                       [#'accounting/valid-partition-plan? :partition-plan-validation nil true nil]
                       [#'accounting/build-partial-result :partial-construction nil true nil]
                       [#'accounting/serial-values :full-serial-fold nil false nil]
                       [#'accounting/reconcile :reconciliation :reconciliation true nil]
                       [#'native/valid-work? :work-binding-validation nil false nil]
                       [#'dependency/validate-manifest :dependency-manifest-validation nil true nil]
                       [#'dependency/validate-policy :dependency-policy-validation nil true nil]
                       [#'dependency/resolve-dependencies :dependency-resolution nil false nil]
                       [#'native/build-work :work-construction :construction false nil]
                       [(ns-resolve 'resolver-sim.research.native-accounting-work
                                    'evidence-nodes-after-execution-validation)
                        :forensic-evidence :executor-evidence false nil]
                       [#'canonical/domain-hash :canonical-hashing nil false nil]
                       [#'transport/dispatch-native-accounting! :dispatch-round-trip nil false nil]
                       [(transport-var 'deliver-computation-result!)
                        :result-delivery-round-trip :executor-delivery false nil]
                       [(transport-var 'valid-native-accounting-delivery?)
                        :result-admission :coordinator true nil]
                       [(transport-var 'post-edn!) :http-round-trip nil false nil]
                       [(transport-var 'request-body) :http-request-body-read :transport false nil]
                       [(transport-var 'response!) :http-response-write :transport false nil]
                       [(transport-var 'write-runtime-state!) :runner-journal-write nil false journal?]
                       [(transport-var 'write-coordinator-runtime-state!)
                        :coordinator-journal-write nil false journal?]]
          replacements (into {} (map (fn [[v stage role validation? enabled?]]
                                       [v (instrument stats @v stage role validation? enabled?)]))
                             definitions)
          started (System/nanoTime)
          value (with-redefs-fn replacements f)]
      {:value value
       :runtime/profile {:coverage :current-jvm-only
                         :remote-stages :not-observed
                         :wire-transfer-only :not-isolated
                         :timing :inclusive-nonadditive
                         :journal-writes :only-when-state-path-configured
                         :elapsed-ns (- (System/nanoTime) started)
                         :stages (into (sorted-map) @stats)}})))
