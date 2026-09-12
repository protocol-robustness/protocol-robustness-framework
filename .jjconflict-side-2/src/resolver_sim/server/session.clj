(ns resolver-sim.server.session
  "Stateful session store for the Phase 2 gRPC simulation server.

  Each session owns:
    :world-holder — AtomicWorldHolder; authoritative world + revision + step-count
    :context    — immutable {:agent-index :snapshot} built at session creation
    :protocol   — the tiered Protocol instances in use for this session

  Concurrency control: the AtomicWorldHolder provides compare-and-set (CAS)
  commit semantics on :world/revision. Concurrent step calls read the same
  predecessor snapshot, compute successor worlds (pure dispatch), and the CAS
  ensures only one commits; the other retries with an updated snapshot. All
  dispatch-action side effects (evidence capture, invariant attestation) are
  idempotent by hash or pure, so CAS retries are safe.

  Layering: server/* may import contract_model/*.  Must NOT import db/* or io/*."

  (:require [resolver-sim.protocols.protocol        :as proto]
            [resolver-sim.protocols.registry        :as preg]
            [resolver-sim.contract-model.replay     :as replay]
            [resolver-sim.world.authority           :as auth]))

;; ---------------------------------------------------------------------------
;; Protocol registry
;; ---------------------------------------------------------------------------

(def ^:private protocol-registry
  "Map of protocol-id string -> Protocol instance.
   Sourced from the central protocol registry."
  (into {} (map (fn [pid] [pid (preg/get-protocol pid)])
                (preg/known-protocol-ids))))

;; ---------------------------------------------------------------------------
;; Session store
;; ---------------------------------------------------------------------------

(defonce ^{:dynamic true :private true
           :doc "Atom: {session-id -> {:world-holder :context :protocol}}"}
  sessions
  (atom {}))

(defmacro with-fresh-sessions
  "Execute body with a fresh empty session store.
   The outer store is restored when body exits.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [sessions fresh-atom#]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- keywordize
  [m]
  (cond
    (map? m)    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize v)]) m))
    (sequential? m) (mapv keywordize m)
    :else       m))

(defn- normalise-agents
  [agents]
  (mapv (fn [a]
          (let [m (keywordize a)]
            (cond-> m
              (string? (:id m))       (update :id str)
              (string? (:address m))  (update :address str)
              (or (:type m) (not (:role m)))
              (assoc :role (or (:role m) (:type m) "buyer"))
              (not (:strategy m)) (assoc :strategy "honest"))))
        agents))

(defn- normalise-params
  [params]
  (if (map? params) (keywordize params) {}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn session-exists?
  [session-id]
  (contains? @sessions session-id))

(defn create-session!
  ([session-id agents protocol-params initial-block-time]
   (create-session! session-id agents protocol-params initial-block-time preg/default-protocol-id))
  ([session-id agents protocol-params initial-block-time protocol-id]
   (let [pid        (or protocol-id preg/default-protocol-id)
         protocol   (get protocol-registry pid)]
     (if-not protocol
       {:ok false :error :unknown-protocol :detail {:protocol-id pid
                                                    :known (keys protocol-registry)}}
       (let [agent-list (normalise-agents agents)
             params     (normalise-params protocol-params)
             validation (replay/validate-agents agent-list)]
         (if-not (:ok validation)
           validation
           (let [context (proto/build-execution-context protocol agent-list params)
                 world0  (proto/init-world protocol {:initial-block-time initial-block-time})
                 session {:world-holder (auth/atomic-world-holder world0)
                          :context       context
                          :protocol      protocol}
                 [old _] (swap-vals! sessions (fn [s]
                                                (if (contains? s session-id)
                                                  s
                                                  (assoc s session-id session))))]
             (if (contains? old session-id)
               {:ok false :error :session-already-exists :detail {:session-id session-id}}
               {:ok true :session-id session-id}))))))))

(defn step-session!
  [session-id event]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (let [holder  (:world-holder session)
            context (:context session)
            proto   (:protocol session)
            evt     (keywordize event)
            result  (auth/cas-step!
                     holder
                     (fn [world]
                       (let [step (replay/process-step proto context world evt)]
                         {:ok (:ok? step)
                          :world (:world step)
                          :step step})))]
        (case (:status result)
          :committed  {:ok true :step (:step result) :revision (:revision result)}
          :no-op      {:ok true :step (:step result) :revision (:revision result)
                       :no-op true}
          :rejected   {:ok true :step (:step result)})))))

(defn destroy-session!
  [session-id]
  (if (session-exists? session-id)
    (do (swap! sessions dissoc session-id)
        {:ok true :session-id session-id})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn active-sessions
  []
  (keys @sessions))

(defn get-session-state
  [session-id]
  (if-let [s (get @sessions session-id)]
    {:ok true :world (auth/world-at (:world-holder s))}
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-info
  [session-id]
  (when-let [s (get @sessions session-id)]
    (let [holder (:world-holder s)
          world  (auth/world-at holder)
          wv     (if (satisfies? proto/AnalysisModule (:protocol s))
                   (proto/io-projection (:protocol s) world :world-view)
                   {:block-time (:block-time world) :entity-count 0})
          fv     (when (satisfies? proto/AnalysisModule (:protocol s))
                   (proto/io-projection (:protocol s) world :funds-ledger-view))]
      {:step-count   (auth/step-count-of holder)
       :revision     (auth/revision-of holder)
       :block-time   (:block-time wv)
       :escrow-count (:entity-count wv)
       :funds-conservation-holds? (get-in fv [:conservation :holds?])
       :funds-drift-total         (get-in fv [:conservation :drift-total])})))

(defn suggest-actions
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (let [holder (:world-holder s)]
      (if (satisfies? proto/EconomicModel (:protocol s))
        (let [result (proto/advisory (:protocol s) (auth/world-at holder)
                                     :suggest-actions
                                     {:actor-id    actor-id
                                      :agent-index (get-in s [:context :agent-index] {})})]
          (if (:not-supported result)
            {:ok false :error :not-supported :detail {:session-id session-id}}
            (assoc result :ok true :session-id session-id :actor-id actor-id)))
        {:ok false :error :not-supported :detail {:session-id session-id}}))
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-signals
  [session-id]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (auth/world-at (:world-holder s)) :session-signals {})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-payoff
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (auth/world-at (:world-holder s))
                                   :evaluate-payoff {:actor-id actor-id})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-attack-objective
  [session-id actor-id objective]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (auth/world-at (:world-holder s))
                                   :evaluate-attack-objective
                                   {:actor-id actor-id :objective objective})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))
