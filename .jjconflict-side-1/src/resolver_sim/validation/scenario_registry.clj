(ns resolver-sim.validation.scenario-registry
  "Validation entrypoint for the canonical deterministic scenario registries.

   Covers:
   - in-process invariant registry (`resolver-sim.protocols.sew.invariant-scenarios`)
   - file-backed scenario suite registry (`resolver-sim.scenario.suites`)

   The file-backed validation path intentionally reuses
   `resolver-sim.io.scenario-runner/resolve-path-run-request` so registry
   validation exercises the same protocol inference, dispatcher selection,
   scenario-id checks, and metadata normalization used during actual scenario
   execution."
  (:require [clojure.string :as str]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.io.scenario-runner :as sr]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.suites :as suites]))

(defn- duplicate-values
  [xs]
  (->> xs
       frequencies
       (filter (fn [[_ n]] (> n 1)))
       (map first)
       sort
       vec))

(defn- known-protocol-id?
  [protocol-id]
  (or ((set (preg/known-protocol-ids)) protocol-id)
      (some? (preg/get-protocol protocol-id))))

(defn- sew-invariant-registry []
  (try
    {:validate-all! (requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/validate-all-scenarios!)
     :scenario-type-registry @(requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/scenario-type-registry)}
    (catch java.io.FileNotFoundException _ nil)))

(defn- resolve-suite-paths
  "Resolve :scenario-ids via sc/scenario-path, or return :paths as-is."
  [{:keys [scenario-ids paths]}]
  (if scenario-ids
    (mapv sc/scenario-path scenario-ids)
    paths))

(defn- validate-suite-definition!
  [suite-key {:keys [scenario-ids paths protocol-id kind] :as suite-def}]
  (when-not (map? suite-def)
    (throw (ex-info "Malformed file-backed suite registry entry"
                    {:suite/key suite-key
                     :suite/definition suite-def
                     :reason :suite-definition-not-a-map})))
  (when-not (= :file-path-suite kind)
    (throw (ex-info "File-backed suite registry entry must declare :kind :file-path-suite"
                    {:suite/key suite-key
                     :suite/definition suite-def
                     :reason :unexpected-suite-kind
                     :expected :file-path-suite
                     :actual kind})))
  (let [effective-paths (or scenario-ids paths)]
    (when-not (vector? effective-paths)
      (throw (ex-info "File-backed suite registry entry must provide :scenario-ids or :paths as a vector"
                      {:suite/key suite-key
                       :suite/definition suite-def
                       :reason :suite-paths-not-a-vector
                       :actual effective-paths})))
    (when-not (seq effective-paths)
      (throw (ex-info "File-backed suite registry entry must provide at least one scenario-id or path"
                      {:suite/key suite-key
                       :suite/definition suite-def
                       :reason :suite-paths-empty})))
    (when-not (every? string? effective-paths)
      (throw (ex-info "File-backed suite registry scenario-ids/paths must all be strings"
                      {:suite/key suite-key
                       :suite/definition suite-def
                       :reason :suite-path-not-a-string
                       :paths effective-paths}))))
  (when-not (string? protocol-id)
    (throw (ex-info "File-backed suite registry entry must provide :protocol-id as a string"
                    {:suite/key suite-key
                     :suite/definition suite-def
                     :reason :suite-protocol-not-a-string
                     :actual protocol-id})))
  (when-not (known-protocol-id? protocol-id)
    (throw (ex-info "File-backed suite registry entry references an unknown protocol"
                    {:suite/key suite-key
                     :suite/definition suite-def
                     :reason :unknown-suite-protocol
                     :actual protocol-id
                     :known-protocols (vec (preg/known-protocol-ids))})))
  (let [duplicate-paths (duplicate-values paths)]
    (when (seq duplicate-paths)
      (throw (ex-info "File-backed suite registry entry contains duplicate scenario paths"
                      {:suite/key suite-key
                       :suite/definition suite-def
                       :reason :duplicate-suite-paths
                       :duplicates duplicate-paths})))))

(defn- validate-suite-scenario-entry!
  [suite-key protocol-id path]
  (when-not (rp/path-exists? path)
    (throw (ex-info "File-backed suite registry references a missing scenario file"
                    {:suite/key         suite-key
                     :suite/protocol-id protocol-id
                     :scenario/path     path
                     :reason            :missing-scenario-file})))
  (let [{request :scenario-run/request}
        (sr/resolve-path-run-request [path] {:suite-id suite-key
                                             :protocol protocol-id})
        entry                  (first (:entries request))
        explicit-scenario-id   (get-in entry [:scenario :scenario-id])
        expected-dispatcher-id (keyword "protocol" protocol-id)]
    (when-not explicit-scenario-id
      (throw (ex-info "File-backed scenario is missing an explicit :scenario-id"
                      {:suite/key         suite-key
                       :suite/protocol-id protocol-id
                       :scenario/path     path
                       :reason            :missing-explicit-scenario-id})))
    (when-not (= protocol-id (:protocol entry))
      (throw (ex-info "File-backed suite protocol does not match the scenario protocol"
                      {:suite/key         suite-key
                       :suite/protocol-id protocol-id
                       :scenario/path     path
                       :scenario/id       explicit-scenario-id
                       :reason            :scenario-protocol-mismatch
                       :expected          protocol-id
                       :actual            (:protocol entry)})))
    (when-not (= expected-dispatcher-id (:dispatcher-id entry))
      (throw (ex-info "File-backed scenario dispatcher does not match the resolved protocol"
                      {:suite/key         suite-key
                       :suite/protocol-id protocol-id
                       :scenario/path     path
                       :scenario/id       explicit-scenario-id
                       :reason            :scenario-dispatcher-mismatch
                       :expected          expected-dispatcher-id
                       :actual            (:dispatcher-id entry)})))
    {:suite/key       suite-key
     :scenario/path   path
     :scenario/id     explicit-scenario-id
     :protocol/id     (:protocol entry)
     :dispatcher/id   (:dispatcher-id entry)
     :source          (:source entry)
     :scenario-format (cond
                        (str/ends-with? path ".edn") :edn
                        (str/ends-with? path ".json") :json
                        :else :unknown)}))

(defn validate-file-backed-suite-registry!
  "Validate the canonical file-backed scenario suite registry.

   Checks:
   - every suite entry is well formed
   - every suite protocol is known
   - every suite path exists
   - every file-backed scenario has an explicit stable :scenario-id
   - every file-backed scenario resolves through the expected protocol/dispatcher
   - scenario ids are unique across all registered file-backed suites"
  ([]
   (validate-file-backed-suite-registry! (suites/known-suite-definitions)))
  ([suite-registry]
   (let [suite-keys   (->> (keys suite-registry) sort vec)
         entries      (mapcat (fn [suite-key]
                                (let [suite-def (get suite-registry suite-key)
                                      protocol-id (:protocol-id suite-def)]
                                  (validate-suite-definition! suite-key suite-def)
                                  (mapv #(validate-suite-scenario-entry! suite-key protocol-id %)
                                        (resolve-suite-paths suite-def))))
                              suite-keys)
         scenario-ids (mapv :scenario/id entries)
         duplicates   (duplicate-values scenario-ids)]
     (when (seq duplicates)
       (throw (ex-info "Duplicate file-backed scenario-id(s) detected"
                       {:reason     :duplicate-file-backed-scenario-ids
                        :duplicates duplicates
                        :entries    (->> entries
                                         (filter (fn [{scenario-id :scenario/id}]
                                                   ((set duplicates) scenario-id)))
                                         vec)})))
     {:ok?                            true
      :suite-count                    (count suite-keys)
      :scenario-count                 (count entries)
      :suite-keys                     suite-keys
      :scenario-entries               (vec entries)
      :deprecated-json-scenario-count (count (filter #(= :json (:scenario-format %)) entries))})))

(defn validate-all!
  "Validate all canonical deterministic scenario registries used by the runner."
  ([]
   (validate-all! (suites/known-suite-definitions)))
  ([suite-registry]
   (let [sew-registry (sew-invariant-registry)
         _ (when-let [validate! (:validate-all! sew-registry)] (validate!))
         file-backed-summary (validate-file-backed-suite-registry! suite-registry)]
     {:ok?                  true
      :registry/invariants  (if sew-registry
                              {:ok? true
                               :status :available
                               :scenario-count (count (set (keys (:scenario-type-registry sew-registry))))}
                              {:ok? true
                               :status :unavailable
                               :reason :sew-extension-not-on-classpath
                               :scenario-count 0})
      :registry/file-backed file-backed-summary})))

(defn -main
  [& _args]
  (try
    (let [{:registry/keys [invariants file-backed]} (validate-all!)]
      (println "Scenario registry validation passed.")
      (println (format "  Invariant scenarios: %d" (:scenario-count invariants)))
      (println (format "  File-backed suites: %d" (:suite-count file-backed)))
      (println (format "  File-backed scenarios: %d" (:scenario-count file-backed)))
      (println (format "  Deprecated JSON executable scenarios: %d" (:deprecated-json-scenario-count file-backed))))
    (catch Exception e
      (binding [*out* *err*]
        (println "Scenario registry validation failed:")
        (println (str "  " (.getMessage e)))
        (when-let [data (ex-data e)]
          (println (pr-str data))))
      (System/exit 1))))
