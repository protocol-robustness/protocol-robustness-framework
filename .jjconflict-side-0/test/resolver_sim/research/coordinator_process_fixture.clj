(ns resolver-sim.research.coordinator-process-fixture
  "Child-JVM coordinator used only for process-loss qualification."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.runner-transport :as transport]))

(defn -main [configuration-path ready-path]
  (let [configuration (edn/read-string (slurp configuration-path))
        entered-path (:fixture/entered-path configuration)
        release-path (:fixture/release-path configuration)
        event-path (:fixture/event-path configuration)
        events (atom [])
        record-event!
        (fn [event]
          (swap! events conj event)
          (spit event-path (pr-str @events)))
        block-after-persisted
        (fn [_ _]
          (record-event! :semantic-result-persisted)
          (spit entered-path (pr-str {:fixture/stage :after-reproduction-persisted}))
          (let [release (java.io.File. release-path)]
            (loop []
              (when-not (.exists release)
                (Thread/sleep 10)
                (recur)))))
        coordinator (transport/start-coordinator!
                     (assoc configuration
                            :after-reproduction-persisted block-after-persisted
                            :test-event record-event!))
        address (java.net.URI/create (:transport/base-url coordinator))]
    ;; Do not advertise readiness merely because HttpServer.start returned:
    ;; prove the bound listener accepts a real TCP connection first.
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. (.getHost address) (.getPort address)) 5000))
    (record-event! :listener-accepting)
    (spit ready-path (pr-str {:coordinator/ready? true
                              :coordinator/base-url (:transport/base-url coordinator)}))
    (loop []
      (Thread/sleep 1000)
      (recur))))
