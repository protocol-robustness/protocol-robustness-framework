(ns resolver-sim.commands.pre-application-checks
  "Pre-application checks command.
   Validates that an outcome manifest is ready for benchmark execution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(defn- build-fixture-manifest
  "Build a synthetic manifest with all required fields for pre-application
   validation. Used as the canonical default when no --manifest is given."
  []
  (om/build-manifest
   {:benchmark/content-root (str "sha256:"
                                 (apply str (take 64 (cycle "c0de"))))
    :benchmark/model-root (str "sha256:"
                               (apply str (take 64 (cycle "a0de"))))
    :benchmark/evaluation-policy-root (str "sha256:"
                                            (apply str (take 64 (cycle "e5ca"))))
    :execution/status :completed
    :execution/parameter-domain-root (str "sha256:"
                                          (apply str (take 64 (cycle "d0"))))
    :execution/sampling-policy-root (str "sha256:"
                                         (apply str (take 64 (cycle "c0"))))
    :execution/generated-case-set-root (str "sha256:"
                                            (apply str (take 64 (cycle "c0de"))))
    :execution/command-root (str "sha256:"
                                 (apply str (take 64 (cycle "c0d"))))
    :outcomes/operational-root (str "sha256:"
                                    (apply str (take 64 (cycle "0ead"))))
    :outcomes/incentive-root (str "sha256:"
                                  (apply str (take 64 (cycle "1c"))))
    :outcomes/incentive-compatibility-root (str "sha256:"
                                                (apply str (take 64 (cycle "1c1"))))
    :results/operational {:conservation :pass}}))

(defn checks
  "Pre-application checks command handler.
   Validates an outcome manifest for benchmark readiness.
   Reads from --manifest path, or uses a canonical fixture."
  [{:keys [manifest] :as opts}]
  (let [manifest-data (if manifest
                        (edn/read-string (slurp (io/file manifest)))
                        (do (println "  No --manifest provided, using canonical fixture")
                            (build-fixture-manifest)))
        result (om/pre-application-checks manifest-data)]
    (println (str "  pre-application-valid?: " (:pre-application-valid? result)))
    (doseq [e (:errors result)]
      (println (str "  ✗ " e)))
    (if (:pre-application-valid? result)
      {:exit-code 0 :message "Pre-application checks passed" :result result}
      {:exit-code 1 :message "Pre-application checks failed" :errors (:errors result) :result result})))
