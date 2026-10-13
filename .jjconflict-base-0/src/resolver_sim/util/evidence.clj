(ns resolver-sim.util.evidence
  "Hardened evidence boundary for producing tamper-evident, attribution-bound
   evidence records. This is the single entry point all evidence-producing code
   should pass through.

   Key principle: keep the world semantically pure; make transition evidence
   richly attributed and content-hashed.

   See `make-evidence-record` for the canonical evidence record shape, and
   `emit-evidence!` for the primary emission API."
  (:require [resolver-sim.evidence.config :as evcfg])
  (:require [resolver-sim.hash.canonical :as hc])
  (:require [resolver-sim.util.attribution :as attr])
  (:require [resolver-sim.evidence.capture :as evcapture]))

;; ── Attribution Context ──────────────────────────────────────────────────────

(defn current-attribution
  "Return the current dynamic attribution context."
  []
  (attr/current-attribution))

;; ── Required Attribution Schema ──────────────────────────────────────────────

(def required-attribution
  "Map from evidence artifact-kind to the set of attribution keys that must
   be present for evidence of that kind to be valid."
  {:transition [:ctx/run-id :ctx/scenario-id :ctx/step :ctx/event-id]
   :invariant [:ctx/run-id :ctx/scenario-id :ctx/step :ctx/invariant-id]
   :scenario [:ctx/run-id :ctx/scenario-id]
   :sweep [:ctx/run-id :ctx/sweep-id :ctx/scenario-id]})

(defn require-attribution!
  "Assert that the attribution context contains all required keys
   for the given evidence kind. Returns the sanitized attribution on success.
   Throws with :missing keys on failure.

   If explicit-attr is provided, it uses that; otherwise it falls back to
   the dynamic *attribution* context."
  ([evidence-kind] (require-attribution! evidence-kind nil))
  ([evidence-kind explicit-attr]
   (let [required (get required-attribution evidence-kind [])
         ;; Use the provided attribution, or fallback to dynamic *attribution*
         raw-attr (if explicit-attr
                    (attr/get-attribution explicit-attr)
                    (attr/current-attribution))
         attr (attr/sanitize-attribution raw-attr)
         missing (seq (remove #(contains? attr %) required))]
     (when missing
       (throw (ex-info "Missing required attribution context for evidence"
                       {:evidence-kind evidence-kind
                        :missing (vec missing)
                        :attribution attr
                        :required (vec required)
                        :explicit? (some? explicit-attr)})))
     attr)))

;; ── Hashing ───────────────────────────────────────────────────────────────────
;;
;; All hashing uses resolver-sim.hash.canonical with explicit intent
;; declarations. See hash-intents in canonical.clj for available intents.

;; ── Evidence Record ──────────────────────────────────────────────────────────

(defn- normalize-action
  "Normalize actions to the representation required by the canonical action projection.
   Conflicting or missing action types fail closed rather than allowing ambiguous evidence."
  [action]
  (cond
    (nil? action) nil
    (or (string? action) (keyword? action)) {:action/type action}
    (not (map? action)) action
    (and (contains? action :type)
         (contains? action :action/type)
         (not= (:type action) (:action/type action)))
    (throw (ex-info "Action :type and :action/type disagree"
                    {:action action}))
    (contains? action :type)
    (-> action
        (assoc :action/type (:type action))
        (dissoc :type))
    (contains? action :action/type) action
    :else
    (throw (ex-info "Action must have :action/type"
                    {:action action}))))

(defn make-evidence-record
  "Build a versioned, content-hashed evidence record from a specification map.

   Expected keys in spec:
     :artifact-kind  - keyword identifying the kind of evidence
     :block-time     - optional simulation time of transition; must be paired with :step
     :step           - optional simulation step index; must be paired with :block-time
     :before         - world state before the transition (hashed, not stored raw)
     :after          - world state after the transition (hashed, not stored raw)
     :action         - the action/event that caused the transition (stored raw + hashed)
     :result         - the outcome/result of the action (stored raw + hashed)
     :attribution    - attribution context map

   The returned record includes an :evidence-hash over the canonical committed
   record fields. Non-temporal records omit :action-hash-at; partial temporal
   context is rejected rather than fabricated."
  [{:keys [artifact-kind block-time step before after action result attribution]
    :as spec}]
  (let [project hc/project-committable-content
        action (normalize-action action)
        temporal-values (select-keys spec [:step :block-time])
        temporal-present? (mapv #(contains? spec %) [:step :block-time])
        temporal-complete? (every? true? temporal-present?)
        _ (when (and (some true? temporal-present?)
                     (not temporal-complete?))
            (throw (ex-info "Evidence temporal context must include both :step and :block-time"
                            temporal-values)))
        _ (when (and temporal-complete?
                     (or (nil? step) (nil? block-time)
                         (not (integer? step))
                         (neg? step)))
            (throw (ex-info "Evidence temporal context requires a non-negative integer :step and non-nil :block-time"
                            temporal-values)))
        context-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                          (project attribution))
        before-hash (hc/hash-with-intent {:hash/intent :world-structure} before)
        after-hash (hc/hash-with-intent {:hash/intent :world-structure} after)
        action-hash (hc/hash-with-intent {:hash/intent :action} action)
        action-hash-at (when temporal-complete?
                         (hc/hash-with-intent {:hash/intent :action-at}
                                              {:action-hash action-hash
                                               :step step
                                               :block-time block-time}))
        result-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                         (project result))
        base (cond-> {:schema-version (evcfg/schema :evidence-record)
                      :artifact-kind artifact-kind
                      :temporal-context (cond-> {}
                                          (contains? spec :block-time)
                                          (assoc :block-time block-time)
                                          (contains? spec :step)
                                          (assoc :step step))
                      :attribution attribution
                      :context-hash context-hash
                      :before-hash before-hash
                      :after-hash after-hash
                      :action action
                      :action-hash action-hash
                      :result result
                      :result-hash result-hash}
               temporal-complete? (assoc :action-hash-at action-hash-at)
               (:ctx/evidence-group-id attribution)
               (assoc :evidence/group-id (:ctx/evidence-group-id attribution)
                      :evidence/layer :generic-trace))
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                           (project base))]
    (assoc base :evidence-hash evidence-hash)))

;; ── Evidence Emission ────────────────────────────────────────────────────────

(defn emit-evidence!
  "Primary evidence emission API. Validates attribution context and produces a
   content-hashed evidence record.

   Call shape:
     (emit-evidence!
       {:artifact-kind     :transition
        :block-time        current-block-time
        :step              current-step
        :before            before-world
        :after             after-world
        :action            action-map
        :result            result-map
        :attribution-context (optional) AttributedState or attribution map})

   Returns the evidence record. Throws if required attribution keys are missing
   or if temporal context is absent."
  [{:keys [artifact-kind block-time step attribution-context] :as spec}]
  (when (or (nil? block-time) (nil? step))
    (throw (ex-info "Missing required temporal context (block-time/step) for evidence"
                    {:block-time block-time :step step})))
  (let [attribution (require-attribution! (or artifact-kind :transition) attribution-context)]
    (make-evidence-record (assoc spec :attribution attribution))))

;; ── Async Attribution Preservation ───────────────────────────────────────────

(defn wrap-attribution
  "Higher-order function that captures current attribution and rebinds it
   when the returned function is called. Use at async boundaries."
  [f]
  (let [attr (attr/current-attribution)]
    (fn [& args]
      (binding [attr/*attribution* attr]
        (apply f args)))))

(defmacro contextual-future
  "Execute body in a future that preserves the current attribution context."
  [& body]
  `(let [attr# (attr/current-attribution)]
     (future
       (binding [attr/*attribution* attr#]
         ~@body))))

(defn contextual-pmap
  "Parallel map that preserves the current attribution context across worker threads.

   WARNING: this delegates to ordinary Clojure `pmap`, which uses the JVM-shared
   fork-join pool. It is NOT owned by the benchmark executor lifecycle: it cannot
   be cancelled, quiesced, or proven quiescent before the benchmark returns.

   Do NOT use this in benchmark-reachable canonical execution paths. Use
   resolver-sim.execution.parallel/ordered-bounded-mapv instead, which provides
   an owned executor lifecycle with explicit cancellation and quiescence.

   Retained for non-canonical convenience/presentation paths only."
  [f coll]
  (let [attr (attr/current-attribution)
        capture evcapture/*capture-event-evidence!*
        wrapper (fn [x]
                  (binding [attr/*attribution* attr
                            evcapture/*capture-event-evidence!* capture]
                    (f x)))]
    (pmap wrapper coll)))
