(ns resolver-sim.research.runner-process-fixture
  "Child-JVM fixture for real process-boundary runner recovery tests."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.runner-transport :as transport]))

(defn- block-at! [configuration stage]
  (when (= stage (:block-at configuration))
    (spit (:entered-path configuration) (pr-str {:stage stage}))
    (let [release (java.io.File. (:release-path configuration))]
      (loop []
        (when-not (.exists release)
          (Thread/sleep 10)
          (recur))))))

(defn- run-fixture [configuration ready-path]
  (let [block-hook (fn [stage] (block-at! configuration stage))
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

(defn -main [configuration-path ready-path]
  (let [configuration (edn/read-string (slurp configuration-path))
        compute accounting/partial-values]
    (if (= :during-native-accounting-compute (:block-at configuration))
      ;; A literal kill after arithmetic has begun, before the full partition
      ;; or any completion evidence exists. This hook is child-fixture-only.
      (with-redefs [accounting/partial-values
                    (fn [request unit]
                      (compute request (assoc unit :partition/source-roots
                                              [(first (:partition/source-roots unit))]))
                      (block-at! configuration :during-native-accounting-compute)
                      (compute request unit))]
        (run-fixture configuration ready-path))
      (run-fixture configuration ready-path))))
