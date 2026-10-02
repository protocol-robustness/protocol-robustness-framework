(ns resolver-sim.research.remote-runner-fixture
  "Opt-in second-host fixture for the rooted reproduction transport test."
  (:require [resolver-sim.research.remote-execution-test :as fixture]
            [resolver-sim.research.runner-transport :as transport]))

(defn -main [auth-secret coordinator-port state-path]
  (let [coordinator-port (Integer/parseInt coordinator-port)
        participant (fixture/runner \d :identified :site/a)
        endpoint {:transport/base-url (str "http://127.0.0.1:" coordinator-port)
                  :transport/auth-secret auth-secret
                  :transport/request-timeout-ms 30000}
        runner (transport/start-runner!
                {:runner-participation participant :auth-secret auth-secret
                 :bind-address "127.0.0.1" :port 39105 :state-path state-path
                 :coordinator-root (fixture/fake-root \c)
                 :coordinator-endpoint endpoint})]
    (println :remote-runner-ready (:transport/base-url runner)
             (:runner-participation/root participant))
    (flush)
    (loop [] (Thread/sleep 10000) (recur))))
