(ns resolver-sim.research.runner-transport.lifecycle
  "Runner/coordinator listener lifecycle and bootstrap ordering."
  (:require [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.runner-transport.handlers :as handlers])
  (:import [com.sun.net.httpserver HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors ExecutorService]))

(def ^:private service-keys
  #{:read-runner-journal :runner-state :accounting-cache-from-state :handler-runtime
    :recover-interrupted-work! :retry-undelivered-results!
    :retry-undelivered-reproduction-results! :retry-undelivered-accounting-proofs!
    :write-runtime-state! :read-coordinator-runtime-state :coordinator-state
    :write-coordinator-runtime-state! :persist-dispatch-intent!})

(defn- validate-services! [services]
  (when-not (and (map? services) (= service-keys (set (keys services)))
                 (every? fn? (vals services)))
    (throw (ex-info "Invalid runner transport lifecycle services" {}))))

(defn start-runner!
  [services {:keys [runner-participation auth-secret bind-address port advertise-url
                    coordinator-root coordinator-endpoint state-path recovery-logical-cutpoint
                    before-safe-cutpoint before-result-delivery before-reproduction-compute test-event]
             :or {bind-address "127.0.0.1" port 0 recovery-logical-cutpoint 0}}]
  (validate-services! services)
  (when-not (and (:valid? (participation/validate-participation runner-participation))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (nil? state-path) (string? state-path))
                 (integer? recovery-logical-cutpoint) (not (neg? recovery-logical-cutpoint))
                 (or (and (nil? coordinator-root) (nil? coordinator-endpoint))
                     (and (hash-ref/valid-sha256-ref? coordinator-root)
                          (map? coordinator-endpoint))))
    (throw (ex-info "Invalid research runner transport configuration" {})))
  (let [bootstrap {:runner-participation runner-participation
                   :transport/auth-secret auth-secret
                   :transport/coordinator-root coordinator-root
                   :transport/coordinator-endpoint coordinator-endpoint
                   :transport/before-safe-cutpoint before-safe-cutpoint
                   :transport/before-result-delivery before-result-delivery
                   :transport/before-reproduction-compute before-reproduction-compute
                   :transport/test-event test-event
                   :transport/state-path state-path}
        recovered ((:read-runner-journal services) state-path bootstrap)
        state (atom (:state recovered))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        executor (Executors/newCachedThreadPool)
        runner (assoc bootstrap :transport/state state :transport/server server
                      :transport/journal (atom (select-keys recovered [:sequence :format]))
                      :transport/accounting-input-cache
                      (atom ((:accounting-cache-from-state services) @state))
                      :transport/executor executor)
        runtime ((:handler-runtime services))]
    (.createContext server "/v1/jobs" (handlers/job-handler runtime runner))
    (.createContext server "/v1/claim-evidence"
                    (handlers/claim-evidence-handler runtime runner))
    (.createContext server "/v1/cancellations"
                    (handlers/cancellation-handler runtime runner))
    (.setExecutor server executor)
    (.start server)
    (let [runner (assoc runner :transport/base-url
                        (or advertise-url
                            (str "http://" bind-address ":" (.getPort (.getAddress server)))))]
      ((:recover-interrupted-work! services) runner recovery-logical-cutpoint)
      (when coordinator-endpoint
        ((:retry-undelivered-results! services)
         runner coordinator-endpoint recovery-logical-cutpoint)
        ((:retry-undelivered-reproduction-results! services)
         runner coordinator-endpoint recovery-logical-cutpoint)
        ((:retry-undelivered-accounting-proofs! services)
         runner coordinator-endpoint recovery-logical-cutpoint))
      ((:write-runtime-state! services) runner
                                        ((:runner-state services) runner) ((:runner-state services) runner))
      runner)))

(defn stop-runner! [runner]
  (when-let [server (:transport/server runner)] (.stop ^HttpServer server 0))
  (when-let [executor (:transport/executor runner)]
    (.shutdownNow ^ExecutorService executor))
  nil)

(defn runner-state [runner]
  @(:transport/state runner))

(defn start-coordinator!
  [services {:keys [coordinator-root assignments auth-secret bind-address port advertise-url state-path
                    after-reproduction-persisted test-event initial-dispatches
                    accounting-proof-verification]
             :or {bind-address "127.0.0.1" port 0}}]
  (validate-services! services)
  (when-not (and (hash-ref/valid-sha256-ref? coordinator-root)
                 (vector? assignments) (every? remote/valid-assignment? assignments)
                 (= (count assignments) (count (set (map :research-run-assignment/root assignments))))
                 (string? auth-secret) (not (empty? auth-secret))
                 (string? bind-address) (integer? port) (<= 0 port 65535)
                 (or (nil? advertise-url) (string? advertise-url))
                 (or (nil? state-path) (string? state-path))
                 (or (nil? initial-dispatches) (vector? initial-dispatches)))
    (throw (ex-info "Invalid research coordinator transport configuration" {})))
  (let [bootstrap {:coordinator/root coordinator-root :transport/state-path state-path}
        recovered ((:read-coordinator-runtime-state services) state-path bootstrap assignments)
        state (atom (:state recovered))
        server (HttpServer/create (InetSocketAddress. bind-address (int port)) 0)
        coordinator {:coordinator/root coordinator-root
                     :transport/auth-secret auth-secret
                     :transport/state-path state-path
                     :transport/state state
                     :transport/journal (atom (:journal recovered))
                     :transport/accounting-input-known (atom #{})
                     :transport/accounting-proof-verification accounting-proof-verification
                     :transport/after-reproduction-persisted after-reproduction-persisted
                     :transport/test-event test-event
                     :transport/server server}
        runtime ((:handler-runtime services))]
    ((:write-coordinator-runtime-state! services) coordinator
                                                  (:persisted-state recovered) ((:coordinator-state services) coordinator))
    (doseq [dispatch initial-dispatches]
      ((:persist-dispatch-intent! services) coordinator dispatch))
    (.createContext server "/v1/results"
                    (handlers/coordinator-result-handler runtime coordinator))
    (.createContext server "/v1/reproductions"
                    (handlers/coordinator-reproduction-handler runtime coordinator))
    (.createContext server "/v1/accounting"
                    (handlers/coordinator-reproduction-handler runtime coordinator))
    (.createContext server "/v1/accounting-proof"
                    (handlers/coordinator-accounting-proof-handler runtime coordinator))
    (.setExecutor server nil)
    (.start server)
    (let [coordinator (assoc coordinator :transport/base-url
                             (or advertise-url
                                 (str "http://" bind-address ":" (.getPort (.getAddress server)))))]
      ((:write-coordinator-runtime-state! services) coordinator
                                                    ((:coordinator-state services) coordinator)
                                                    ((:coordinator-state services) coordinator))
      coordinator)))

(defn stop-coordinator! [coordinator]
  (when-let [server (:transport/server coordinator)] (.stop ^HttpServer server 0))
  nil)

(defn coordinator-state [coordinator]
  @(:transport/state coordinator))
