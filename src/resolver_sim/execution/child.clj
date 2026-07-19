(ns resolver-sim.execution.child
  "Scoped replay execution for suite and benchmark children. This namespace
   owns child-local inputs, replay output, and summaries only; callers retain
   all top-level lifecycle, aggregate, registry, and completion ownership."
  (:require [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.protocols.registry :as protocols]))

(defn- child-file [root & parts]
  (apply io/file (str root) parts))

(defn- write-edn! [file value]
  (.mkdirs (.getParentFile (io/file file)))
  (spit file (pr-str value))
  file)

(defn execute!
  "Execute one planned child beneath `:execution/root`.

   Required keys are `:run/root`, `:execution/root`, `:execution/id`,
   `:input/source`, and `:scenario`. The returned references are root-relative
   to the owning run. This function never writes a lifecycle marker, registry,
   manifest, or completion record."
  [request]
  (let [run-root (:run/root request)
        execution-root-value (:execution/root request)
        execution-id (:execution/id request)
        source (:input/source request)
        scenario (:scenario request)]
    (when-not (and run-root execution-root-value execution-id source scenario)
      (throw (ex-info "Child execution requires root, id, input source, and scenario"
                      {:provided (keys request)})))
    (let [run-root-file (io/file (str run-root))
        execution-root (io/file (str execution-root-value))
        execution-path (.toPath execution-root)
        run-path (.toPath run-root-file)
        _ (when-not (.startsWith (.toAbsolutePath (.normalize execution-path))
                                 (.toAbsolutePath (.normalize run-path)))
            (throw (ex-info "Child execution root escapes owning run root"
                            {:run/root (str run-root) :execution/root (str execution-root-value)})))
        input-file (child-file execution-root "input" (:input/display-name source))
        replay-file (child-file execution-root "replay" "result.edn")
        summary-file (child-file execution-root "summary.edn")
        artifact-dir (child-file execution-root "evidence")
        _ (.mkdirs artifact-dir)
        provenance (lifecycle/snapshot-input! run-root source input-file)
        protocol (or (:protocol scenario) protocols/default-protocol-id)
        adapter (protocols/get-protocol protocol)
        _ (when-not adapter
            (throw (ex-info "Scenario protocol extension is unavailable"
                            {:protocol protocol
                             :known-protocols (vec (protocols/known-protocol-ids))})))
        run-replay (fn []
                     (replay/replay-events
                      adapter scenario
                      (cond-> {:allow-dirty? (or chain/*allow-dirty* false)}
                        (= "yield-v1" protocol)
                        (assoc :flags {:yield-dt-validation? true
                                       :metrics-profile :yield-provider}))))
        result (binding [chain/*allow-dirty* (or (:allow-dirty? request) chain/*allow-dirty* false)
                         evidence-config/*artifact-dir* (.getPath artifact-dir)]
                 (run-replay))
        post-check (when (and (= "sew-v1" protocol) (:world result))
                     (binding [chain/*allow-dirty* (or (:allow-dirty? request) chain/*allow-dirty* false)
                               evidence-config/*artifact-dir* (.getPath artifact-dir)]
                       (:results ((requiring-resolve 'resolver-sim.protocols.sew.invariants/check-all)
                                                                                (:world result)))))
                               summary {:execution/id execution-id
                 :scenario/id (or (:scenario/id scenario) (:scenario-id scenario) (:id scenario))
                 :protocol protocol
                 :outcome (:outcome result)
                 :halt-reason (:halt-reason result)
                 :events-processed (:events-processed result)
                 :invariants post-check}
        _ (write-edn! replay-file result)
        _ (write-edn! summary-file summary)
        relative (fn [file] (str (.relativize run-path (.toPath (io/file file)))))]
    {:execution/id execution-id
     :outcome (:outcome result)
     :halt-reason (:halt-reason result)
     :metrics (:metrics result)
     :input provenance
     :artifacts {:execution/root (relative execution-root)
                 :input (relative input-file)
                 :replay (relative replay-file)
                 :summary (relative summary-file)
                 :evidence-root (relative artifact-dir)}})))
