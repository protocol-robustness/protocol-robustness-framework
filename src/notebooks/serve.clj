(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.logging :as log]))

(defn- show-notebook! [path]
  (clerk/show! (str/trim (str path))))

(defn -main [& args]
  (let [port 7777]
    (log/info! "notebook/server-starting" {:port port})
    (println (str "Starting Clerk notebook server on http://localhost:" port "/notebooks/xtdb_overview"))
    (clerk/serve! {:watch-paths ["src" "notebooks" "data"]
                   :browse true
                   :port port
                   :render-nrepl {:port 7778}})
    ;; Pre-evaluate all notebooks so they are reachable by URL without a file-change trigger.
    ;; show! evaluates the file and registers it; the last call sets the default landing page.
    (show-notebook! "notebooks/xtdb_overview.clj")
    (show-notebook! "notebooks/invariant_failures.clj")
    (show-notebook! "notebooks/telemetry.clj")
    (show-notebook! "notebooks/report.clj")
    (show-notebook! "notebooks/workbench_v2.clj")
    (show-notebook! "notebooks/yield_scenarios_workbench.clj")
    (show-notebook! "notebooks/golden_artifact.clj")
    (show-notebook! "notebooks/atlas_artifact.clj")
    (show-notebook! "notebooks/dispute_artifact.clj")
    (show-notebook! "notebooks/collusion_artifact.clj")
    (show-notebook! "notebooks/game_theory_artifact.clj")
    (show-notebook! "notebooks/economic_artifact.clj")
    (show-notebook! "notebooks/hardening_artifact.clj")
    (show-notebook! "notebooks/protocol_provenance.clj")
    (show-notebook! "notebooks/security_validation.clj")
    (show-notebook! "notebooks/benchmark_protocol_robustness.clj")

    ;; Index is shown last — it becomes the default landing page.
    (show-notebook! "notebooks/index.clj")))

