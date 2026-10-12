(ns resolver-sim.research.shortfall-pilot-cli
  "Offline arithmetic-gated pilot admission using the existing durable writer."
  (:require [clojure.edn :as edn]
            [resolver-sim.io.edn :as pretty]
            [resolver-sim.research.researcher-challenges-cli :as cli]
            [resolver-sim.research.shortfall-pilot :as pilot]
            [resolver-sim.network.shortfall-pilot-adapter :as pilot-adapter]))

(defn- read-file [path] (edn/read-string (slurp path)))

(defn -main [& args]
  (let [[command & paths] args]
    (println
     (pretty/ppr-str
      (case command
        "compare" (do (when-not (= 2 (count paths)) (throw (ex-info "compare LEFT RIGHT" {})))
                      (pilot/execute-comparison (mapv read-file paths)))
        "context" (do (when-not (= 2 (count paths)) (throw (ex-info "context METADATA COMPARISON" {})))
                      (pilot/build-context (read-file (first paths)) (read-file (second paths))))
        "profile" (do (when-not (= 2 (count paths)) (throw (ex-info "profile CONFIG COMPARISON" {})))
                      (pilot-adapter/build-profile (read-file (first paths)) (read-file (second paths))))
        "controlled-profile" (do (when-not (= 4 (count paths))
                                   (throw (ex-info "controlled-profile CONFIG PILOT_PROFILE CONTROL_POLICY ACTIVATION_HISTORY" {})))
                                 (apply pilot-adapter/build-controlled-profile (map read-file paths)))
        "replay" (do (when-not (= 4 (count paths)) (throw (ex-info "replay CONFIG PROFILE HISTORY EVIDENCE_MAP" {})))
                     (apply pilot-adapter/replay (map read-file paths)))
        "controlled-replay" (do (when-not (= 5 (count paths))
                                  (throw (ex-info "controlled-replay CONFIG CONTROLLED_PROFILE CONTROL_POLICY HISTORY EVIDENCE_MAP" {})))
                                (apply pilot-adapter/replay-controlled (map read-file paths)))
        "apply" (do (when-not (<= 5 (count paths) 6)
                      (throw (ex-info "apply CONFIG PROFILE HISTORY ACTION EVIDENCE_MAP [CHECKPOINT]" {})))
                    (let [[config profile history action evidence checkpoint] paths
                          profile (read-file profile) evidence (read-file evidence)]
                      (cli/apply-checked-file! config history action checkpoint
                                               #(pilot-adapter/replay %1 profile %2 evidence))))
        "controlled-apply" (do (when-not (<= 6 (count paths) 7)
                                 (throw (ex-info "controlled-apply CONFIG CONTROLLED_PROFILE CONTROL_POLICY HISTORY ACTION EVIDENCE_MAP [CHECKPOINT]" {})))
                               (let [[config profile-path policy-path history action evidence-path checkpoint] paths
                                     profile (read-file profile-path)
                                     policy (read-file policy-path)
                                     evidence (read-file evidence-path)]
                                 (cli/apply-controlled-checked-file!
                                  config policy-path history action checkpoint
                                  #(pilot-adapter/replay-controlled %1 profile policy %2 evidence))))
        (throw (ex-info "Expected compare, context, profile, controlled-profile, replay, controlled-replay, apply or controlled-apply" {})))))))
