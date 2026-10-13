(ns resolver-sim.scripts.runner
  "Robust runner for test suites with automated registry emission."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [resolver-sim.evidence.config :as evcfg]))

(defn- run-suite [suite-cmd]
  (let [result (apply shell/sh suite-cmd)]
    (println (:out result))
    (when-not (= 0 (:exit result))
      (println (:err result)))
    (= 0 (:exit result))))

(defn emit-registry! [suite status]
  (let [artifact-dir (evcfg/artifact-dir)
        ;; Pass diagnostic files if they exist (mimicking test.sh logic)
        risk-file (first (filter #(.exists %) [(io/file artifact-dir "risk-invariants.lines")]))
        cmd ["python3" "scripts/evidence/write_scenario_run_manifest.py"
             "--scenario" (str "suite-" suite)
             "--suite" suite
             "--status" status
             "--artifact-dir" artifact-dir
             "--registry-level" "DIAGNOSTIC"]]
    (apply shell/sh (cond-> cmd
                      risk-file (concat ["--risk-digest-file" (.getPath risk-file)])))))

(defn -main [suite & cmd]
  (let [success (run-suite cmd)]
    (emit-registry! suite (if success "pass" "fail"))
    (System/exit (if success 0 1))))
