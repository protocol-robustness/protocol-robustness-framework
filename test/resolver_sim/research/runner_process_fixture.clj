(ns resolver-sim.research.runner-process-fixture
  "Child-JVM fixture for real process-boundary runner recovery tests."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.runner-transport :as transport]))

(defn -main [configuration-path ready-path]
  (let [configuration (edn/read-string (slurp configuration-path))
        block-hook (fn [stage]
                     (when (= stage (:block-at configuration))
                       (spit (:entered-path configuration) (pr-str {:stage stage}))
                       (let [release (java.io.File. (:release-path configuration))]
                         (loop []
                           (when-not (.exists release)
                             (Thread/sleep 10)
                             (recur))))))
        runner (transport/start-runner!
                (cond-> configuration
                  (:block-at configuration)
                  (assoc :before-safe-cutpoint (fn [_ _ _] (block-hook :before-safe-cutpoint))
                         :before-reproduction-compute
                         (fn [_ _ _] (block-hook :before-reproduction-compute))
                         :before-result-delivery
                         (fn [_ _ _] (block-hook :before-result-delivery)))))]
    (spit ready-path
          (pr-str {:runner/ready? true
                   :runner/base-url (:transport/base-url runner)}))
    (loop []
      (Thread/sleep 1000)
      (recur))))
