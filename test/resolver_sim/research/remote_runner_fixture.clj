(ns resolver-sim.research.remote-runner-fixture
  "Opt-in second-host runner fixture. Workload-specific test fixtures are
   loaded only by the matching mode, keeping native accounting runner startup
   independent of unrelated reproduction and Anvil fixtures."
  (:require [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.runner-transport :as transport]))

(declare start-reproduction-runner! start-native-accounting-runner!)

(defn -main
  ([auth-secret coordinator-port state-path]
   (start-reproduction-runner! auth-secret coordinator-port state-path))
  ([auth-secret coordinator-port state-path mode]
   (case mode
     "native-accounting" (start-native-accounting-runner! auth-secret coordinator-port state-path)
     (throw (ex-info "Unsupported remote runner fixture mode" {:mode mode}))))
  ([auth-secret coordinator-port state-path mode runner-id partition-index]
   (when-not (= "native-accounting" mode)
     (throw (ex-info "Unsupported remote runner fixture mode" {:mode mode})))
   (start-native-accounting-runner! auth-secret coordinator-port state-path
                                    (Integer/parseInt runner-id)
                                    (Integer/parseInt partition-index)))
  ([auth-secret coordinator-port state-path mode runner-id partition-index listen-port]
   (when-not (= "native-accounting" mode)
     (throw (ex-info "Unsupported remote runner fixture mode" {:mode mode})))
   (start-native-accounting-runner! auth-secret coordinator-port state-path
                                    (Integer/parseInt runner-id)
                                    (Integer/parseInt partition-index)
                                    (Integer/parseInt listen-port))))

(defn- serve! [runner]
  (println :remote-runner-ready (:transport/base-url runner)
           (:runner-participation/root (:runner-participation runner)))
  (flush)
  (loop [] (Thread/sleep 10000) (recur)))

(defn- start-reproduction-runner! [auth-secret coordinator-port state-path]
  (let [fixture-runner (requiring-resolve
                       'resolver-sim.research.remote-execution-test/runner)
        fake-root (requiring-resolve
                   'resolver-sim.research.remote-execution-test/fake-root)
        coordinator-port (Integer/parseInt coordinator-port)
        participant (fixture-runner \d :identified :site/a)
        endpoint {:transport/base-url (str "http://127.0.0.1:" coordinator-port)
                  :transport/auth-secret auth-secret
                  :transport/request-timeout-ms 30000}
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret auth-secret
                 :bind-address "127.0.0.1" :port 39105 :state-path state-path
                 :coordinator-root (fake-root \c)
                 :coordinator-endpoint endpoint})]
    (serve! runner)))

(defn- native-participant [runner-id]
  (participation/build-participation
   {:runner-reference (str "sha256:" (format "%064x" runner-id))
    :disclosure-mode :anonymous :site-id :site/local
    :opted-in? true :available? true
    :capabilities [:capability/accounting]
    :job-classes [:job/research-calculation]
    :environment-kinds [:clojure-jvm]
    :qualification-references []}))

(defn- start-native-accounting-runner! [auth-secret coordinator-port state-path
                                       & [runner-id partition-index listen-port]]
  (let [_ (when (contains? (loaded-libs) 'resolver-sim.research.anvil-executor)
            (throw (ex-info "Native accounting runner loaded the Anvil executor" {})))
        _ (when (and partition-index (not= 1 partition-index))
            (throw (ex-info "Native accounting runner fixture supports the declared partition only"
                            {:partition-index partition-index})))
        participant (native-participant (or runner-id 302))
        endpoint {:transport/base-url (str "http://127.0.0.1:" (Integer/parseInt coordinator-port))
                  :transport/auth-secret auth-secret :transport/request-timeout-ms 30000}
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret auth-secret
                 :bind-address "127.0.0.1" :port (or listen-port 39105) :state-path state-path
                 :coordinator-root (str "sha256:" (format "%064x" 900))
                 :coordinator-endpoint endpoint})]
    (serve! runner)))
