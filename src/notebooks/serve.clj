(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.logging :as log]))

(defn- show-notebook! [path]
  (if (.exists (io/file path))
    (clerk/show! (str/trim (str path)))
    (log/warn! "notebook/server-skipping-missing" {:path path})))

(defn -main []
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
    (show-notebook! "notebooks/canonical_framing.clj")
    (show-notebook! "notebooks/equilibrium_artifact.clj")
    (show-notebook! "notebooks/dispute_resolution.clj")
    (show-notebook! "notebooks/withdrawal_observatory.clj")
    (show-notebook! "notebooks/game_theory_artifact.clj")
    (show-notebook! "notebooks/appeal_analysis.clj")
    (show-notebook! "notebooks/hardening_artifact.clj")
    (show-notebook! "notebooks/protocol_provenance.clj")
    (show-notebook! "notebooks/security_validation.clj")
    (show-notebook! "notebooks/benchmark_protocol_robustness.clj")
    (show-notebook! "notebooks/demo_not_admitted.clj")
    (show-notebook! "notebooks/demo_reorder_chain.clj")

    ;; Index is shown last — it becomes the default landing page.
    (show-notebook! "notebooks/index.clj")))

