(ns resolver-sim.server.grpc
  "gRPC server for the generic Simulation Engine.

   Uses io.grpc with a custom JSON Marshaller — no protoc compilation required.
   All method descriptors are built programmatically at server start.

   Wire format: UTF-8 JSON.
     - Python → Clojure: snake_case keys  (parse: snake_case → kebab-case keywords)
     - Clojure → Python: snake_case keys  (stream: kebab-case keywords → snake_case)

   Service: simulation.proto.SimulationEngine
     rpc StartSession   (StartRequest)   → StartResponse
     rpc Step           (StepRequest)    → StepResponse
     rpc DestroySession (DestroyRequest) → DestroyResponse

   See proto/simulation.proto for the full service contract.

   Layering: server/* may import contract_model/*.  Must NOT import db/* or io/*."
  (:require [clojure.data.json          :as json]
            [clojure.string             :as str]
            [clojure.stacktrace         :as st]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.logging       :as log]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.server.session :as session])
  (:import [io.grpc ServerBuilder MethodDescriptor MethodDescriptor$MethodType
            MethodDescriptor$Marshaller
            ServerServiceDefinition ServiceDescriptor Status]
           [io.grpc.stub ServerCalls]
           [java.io ByteArrayInputStream InputStreamReader]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; JSON key normalisation (wire ↔ Clojure)
;; ---------------------------------------------------------------------------

(defn- snake->kw [s]
  (keyword (str/replace s "_" "-")))

(defn- kw->snake ^String [k]
  (if (keyword? k) (str/replace (name k) "-" "_") (str k)))

(defn- val->wire [_k v]
  (cond
    (keyword? v) (str/replace (name v) "-" "_")
    :else        v))

;; ---------------------------------------------------------------------------
;; JSON Marshaller
;;
;; Implements io.grpc.MethodDescriptor$Marshaller for Clojure map ↔ JSON bytes.
;; parse:  JSON bytes (snake_case) → Clojure map (kebab-case keywords)
;; stream: Clojure map (kebab-case keywords) → JSON bytes (snake_case)
;; ---------------------------------------------------------------------------

(defn- json-marshaller []
  (reify MethodDescriptor$Marshaller
    (stream [_ m]
      (-> (json/write-str m :key-fn kw->snake :value-fn val->wire)
          (.getBytes StandardCharsets/UTF_8)
          ByteArrayInputStream.))
    (parse [_ is]
      (json/read (InputStreamReader. is StandardCharsets/UTF_8)
                 :key-fn snake->kw))))

;; ---------------------------------------------------------------------------
;; Method descriptors
;; ---------------------------------------------------------------------------

(defn- make-method
  "Build a unary MethodDescriptor<map,map> for the given RPC name."
  [service-name rpc-name]
  (let [m (json-marshaller)]
    (-> (MethodDescriptor/newBuilder m m)
        (.setType MethodDescriptor$MethodType/UNARY)
        (.setFullMethodName (str "simulation.proto." service-name "/" rpc-name))
        (.build))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- handle-start
  "StartSession: allocate a new simulation session.
   req: {:session-id :agents [{:id :address :role :strategy}] :protocol-params {:resolver-fee-bps ...} :initial-block-time :protocol-id}"
  [req]
  (let [sid        (:session-id req)
        pid        (get req :protocol-id preg/default-protocol-id)
        agents     (:agents req [])
        params     (get req :protocol-params {})
        init-time  (get req :initial-block-time 1000)
        result     (session/create-session! sid agents params init-time pid)]
    {:session-id sid
     :ok         (boolean (:ok result))
     :error      (some-> (:error result) name)}))

(defn- handle-step
  "Step: execute one event against the session's canonical world state.
   req: {:session-id :event {:seq :time :agent :action :params {...}}}"
  [req]
  (let [sid    (:session-id req)
        event  (:event req)
        result (session/step-session! sid event)]
    (if-not (:ok result)
      {:session-id  sid
       :result      "error"
       :world-view  nil
       :trace-entry nil
       :halted      false
       :error       (some-> (:error result) name)}
      (let [step    (:step result)
            entry   (:trace-entry step)
            wv      (:world entry)]
        {:session-id  sid
         :result      (some-> (:result entry) name)
         :world-view  wv
         :trace-entry (dissoc entry :world)
         :halted      (boolean (:halted? step))
         :error       nil}))))

(defn- handle-destroy
  "DestroySession: free session resources.
   req: {:session-id}"
  [req]
  (let [sid    (:session-id req)
        result (session/destroy-session! sid)]
    {:session-id sid
     :ok         (boolean (:ok result))
     :error      (some-> (:error result) name)}))

(defn- handle-get-session-state
  "GetSessionState: return the full internal world map.
   req: {:session-id}"
  [req]
  (let [sid    (:session-id req)
        result (session/get-session-state sid)]
    (if (:ok result)
      {:session-id sid
       :ok         true
       :world      (:world result)}
      {:session-id sid
       :ok         false
       :error      (some-> (:error result) name)})))

(defn- handle-suggest-actions
  "SuggestActions: return advisory valid-ish actions from Clojure-owned state.
   req: {:session-id :actor-id}"
  [req]
  (session/suggest-actions (:session-id req) (:actor-id req)))

(defn- handle-session-signals
  "SessionSignals: return read-only risk/economic signals.
   req: {:session-id}"
  [req]
  (session/session-signals (:session-id req)))

(defn- handle-evaluate-payoff
  "EvaluatePayoff: return Clojure-side payoff projection for an actor.
   req: {:session-id :actor-id}"
  [req]
  (session/evaluate-payoff (:session-id req) (:actor-id req)))

(defn- handle-evaluate-attack-objective
  "EvaluateAttackObjective: objective score/decomposition from canonical world.
   req: {:session-id :actor-id :objective}"
  [req]
  (session/evaluate-attack-objective (:session-id req)
                                     (:actor-id req)
                                     (:objective req)))

;; ---------------------------------------------------------------------------
;; Unary call handler wrapper
;; ---------------------------------------------------------------------------

(defn- unary-handler
  "Wrap a (req → resp) fn as a gRPC ServerCallHandler.
   Catches all Throwables and converts them to gRPC INTERNAL status with
   a descriptive message and server-side stack trace."
  [f]
  (ServerCalls/asyncUnaryCall
   (reify io.grpc.stub.ServerCalls$UnaryMethod
     (^void invoke [_ req ^io.grpc.stub.StreamObserver observer]
       (try
         (let [resp (f req)]
           (.onNext observer resp)
           (.onCompleted observer))
         (catch Throwable t
           (let [msg (str "Internal Simulation Error: " (.getMessage t))]
             (log/error! "grpc/internal-error" {:error (.getMessage t)})
             (st/print-stack-trace t)
             (.onError observer
                       (-> Status/INTERNAL
                           (.withDescription msg)
                           (.asRuntimeException))))))))))

;; ---------------------------------------------------------------------------
;; Service definition
;; ---------------------------------------------------------------------------

(defn- build-engine-service []
  (let [start-m   (make-method "SimulationEngine" "StartSession")
        step-m    (make-method "SimulationEngine" "Step")
        destroy-m (make-method "SimulationEngine" "DestroySession")
        state-m   (make-method "SimulationEngine" "GetSessionState")
        svc-desc  (-> (ServiceDescriptor/newBuilder "simulation.proto.SimulationEngine")
                      (.addMethod start-m)
                      (.addMethod step-m)
                      (.addMethod destroy-m)
                      (.addMethod state-m)
                      (.build))]
    (-> (ServerServiceDefinition/builder svc-desc)
        (.addMethod start-m   (unary-handler handle-start))
        (.addMethod step-m    (unary-handler handle-step))
        (.addMethod destroy-m (unary-handler handle-destroy))
        (.addMethod state-m   (unary-handler handle-get-session-state))
        (.build))))

(defn- build-advisory-service []
  (let [suggest-m   (make-method "AdvisoryService" "SuggestActions")
        signals-m   (make-method "AdvisoryService" "SessionSignals")
        payoff-m    (make-method "AdvisoryService" "EvaluatePayoff")
        objective-m (make-method "AdvisoryService" "EvaluateAttackObjective")
        svc-desc    (-> (ServiceDescriptor/newBuilder "simulation.proto.AdvisoryService")
                        (.addMethod suggest-m)
                        (.addMethod signals-m)
                        (.addMethod payoff-m)
                        (.addMethod objective-m)
                        (.build))]
    (-> (ServerServiceDefinition/builder svc-desc)
        (.addMethod suggest-m   (unary-handler handle-suggest-actions))
        (.addMethod signals-m   (unary-handler handle-session-signals))
        (.addMethod payoff-m    (unary-handler handle-evaluate-payoff))
        (.addMethod objective-m (unary-handler handle-evaluate-attack-objective))
        (.build))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private ^{:doc "Running gRPC server instance."} server
  (atom nil))

(defn start!
  "Start the gRPC server on the given port (default 7070).
   Returns the started Server instance.
   Throws if a server is already running."
  ([] (start! 7070))
  ([port]
   (ev-node/with-execution-node
     {:execution-id :execution/server
      :inputs {:port port}
      :outputs-fn (fn [srv]
                    {:port (.getPort srv)
                     :state :listening})}
     (fn []
       (when @server
         (throw (ex-info "gRPC server already running" {:port port})))
       (let [srv (-> (ServerBuilder/forPort port)
                     (.addService (build-engine-service))
                     (.addService (build-advisory-service))
                     (.build)
                     (.start))]
         (reset! server srv)
         (log/info! "grpc/listening" {:port port})

         srv)))))

(defn port
  "Return bound port for the running server, or nil if not running."
  []
  (some-> @server .getPort))

(defn stop!
  "Stop the running gRPC server."
  []
  (when-let [srv @server]
    (.shutdown srv)
    (reset! server nil)
    (log/info! :grpc-stopped {})))

(defn await-termination
  "Block until the server shuts down. Useful for CLI entry points."
  []
  (some-> @server .awaitTermination))
