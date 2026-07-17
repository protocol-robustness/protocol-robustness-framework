(ns resolver-sim.suite.execution-plan
  "Deterministic planning contract for future canonical suite bundles.
   Planning is pure with respect to bundle writes: it resolves registered
   members and freezes each child execution identity before replay begins."
  (:require [resolver-sim.benchmark.execution-identity :as execution-identity]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as scenarios]
            [resolver-sim.scenario.suites :as suites]))

(def plan-schema-version "suite-execution-plan.v1")

(defn suite-definition-hash [suite-key suite-definition]
  (str "sha256:"
       (canonical/domain-hash "SUITE_DEFINITION_V1"
                              {:suite/id suite-key
                               :suite/definition suite-definition})))

(defn validate-plan!
  "Reject duplicate logical child identities or ambiguous directory prefixes
   before a suite executor writes any child artifact."
  [entries]
  (let [ids (mapv :execution/id entries)
        directories (mapv :execution/directory entries)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Suite execution plan contains duplicate execution IDs"
                      {:execution/ids ids})))
    (when-not (= (count directories) (count (set directories)))
      (throw (ex-info "Suite execution plan contains directory collisions"
                      {:execution/directories directories})))
    entries))

(defn build-plan
  "Build a plan from resolved InputSources and parsed scenarios. `members` is
   a sequence of [source scenario] pairs, allowing callers to resolve input
   sources without exposing filesystem paths to later execution phases."
  [suite-key suite-definition members]
  (let [entries (mapv (fn [ordinal [source scenario]]
                        (let [descriptor (execution-identity/descriptor source scenario 0)]
                          {:execution/ordinal (inc ordinal)
                           :execution/id (execution-identity/execution-id descriptor)
                           :execution/directory (execution-identity/directory-name (inc ordinal) descriptor)
                           :execution/descriptor descriptor
                           :scenario/source-ref (:input/ref source)}))
                      (range)
                      members)]
    {:schema_version plan-schema-version
     :suite/id suite-key
     :suite/definition-hash (suite-definition-hash suite-key suite-definition)
     :executions (validate-plan! entries)}))

(defn resolve-plan
  "Resolve a registered file-backed suite to its execution plan. Trace-only
   suites are deliberately rejected: canonical suite execution currently
   requires replayable scenario inputs."
  [suite-key]
  (let [suite-definition (or (suites/suite-definition suite-key)
                             (throw (ex-info "Unknown suite" {:suite/id suite-key
                                                              :known (suites/known-suite-keys)})))
        paths (suites/suite-paths suite-key)]
    (when-not (seq paths)
      (throw (ex-info "Suite has no executable members" {:suite/id suite-key})))
    (build-plan suite-key suite-definition
                (mapv (fn [path]
                        (let [source (input-source/source path)]
                          [source (scenarios/load-scenario-file (input-source/loadable-ref source))]))
                      paths))))
