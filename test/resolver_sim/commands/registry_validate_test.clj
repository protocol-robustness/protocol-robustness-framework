(ns resolver-sim.commands.registry-validate-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.cli.registry :as registry]
            [resolver-sim.commands.registry-validate :as registry-validate]))

(defn- command
  [id path & [contract]]
  {:command/id id
   :command/path path
   :id id
   :path path
   :command/category :test
   :command/surface :prf
   :command/jar-availability :native
   :command/runtime :jvm
   :jar-avail :native
   :command/description "test command"
   :command/positional-args (or contract {:min 0 :max 0})})

(deftest registry-requires-well-formed-positional-contracts
  (with-redefs [registry/load-registry
                (constantly {:schema-version "prf.commands.registry.v1"
                             :commands [(dissoc (command :missing-contract ["missing"])
                                                :command/positional-args)
                                        (command :invalid-contract ["invalid"] {:min 2 :max 1})]})]
    (is (= ["Command :missing-contract missing :command/positional-args"
            "Command :invalid-contract has invalid :command/positional-args"]
           (:errors (registry/validate-registry))))))

(deftest registry-reports-duplicate-command-paths
  (with-redefs [registry/load-registry
                (constantly {:schema-version "prf.commands.registry.v1"
                             :commands [(command :first ["duplicate"])
                                        (command :second ["duplicate"])]})]
    (is (= ["Duplicate path \"duplicate\" for commands: (:first :second)"]
           (:errors (registry/validate-paths))))))

(deftest registry-rejects-unsupported-surface-jar-availability-combinations
  (with-redefs [registry/load-registry
                (constantly {:schema-version "prf.commands.registry.v1"
                             :commands [(assoc (command :prf-external ["prf-external"])
                                               :command/surface :prf
                                               :command/jar-availability :external)
                                        (assoc (command :bb-native ["bb-native"])
                                               :command/surface :bb
                                               :command/jar-availability :native)]})]
    (let [errors (:errors (registry/validate-registry))]
      (is (= 2 (count errors)))
      (is (some #(re-find #"Command :prf-external has unsupported" %) errors))
      (is (some #(re-find #"Command :bb-native has unsupported" %) errors)))))

(deftest validation-accumulates-structural-path-and-handler-errors
  (let [commands [(command :registered ["registered"])]
        result (with-redefs [registry/list-commands (constantly commands)
                             registry/get-command (constantly nil)
                             registry/validate-registry (constantly {:ok? false :errors ["invalid registry"]})
                             registry/validate-paths (constantly {:ok? false :errors ["duplicate command path"]})
                             registry/validate-bb-tasks (constantly {:ok? true})
                             dispatch/get-command-handlers (constantly {:unregistered identity})
                             dispatch/command-available? (constantly true)
                             dispatch/resolve-command (constantly nil)]
                 (registry-validate/validate {}))]
    (is (= 1 (:exit-code result)))
    (is (= ["No dispatch handler: :registered"
            "Missing registry: unregistered"
            "invalid registry"
            "duplicate command path"
            "Not resolvable: :registered"]
           (:errors result)))))

(deftest dispatcher-rejects-extra-positional-arguments-before-handler-invocation
  (let [called? (atom false)
        cmd (command :backstop ["backstop"])
        handler-for (ns-resolve 'resolver-sim.cli.dispatch 'handler-for)
        {:keys [exit-code output]}
        (with-redefs-fn
          {#'registry/path->command-id-map (constantly {"backstop" :backstop})
           #'registry/get-command (constantly cmd)
           handler-for (fn [_] (fn [_] (reset! called? true) {:exit-code 0}))}
          #(let [output (with-out-str (dispatch/run ["backstop" "unexpected-token"]))]
             {:exit-code 2 :output output}))]
    (is (= 2 exit-code))
    (is (re-find #"Unexpected positional argument: \"unexpected-token\"" output))
    (is (false? @called?))))

;; ── Real-registry invariants (docs-as-code: tie the documented matrix to the
;; ── actual registry) ─────────────────────────────────────────────────────────

(defn- real-registry-commands
  "Read the canonical command registry from the classpath resource."
  []
  (:commands (edn/read-string (slurp "resources/prf/commands/registry.edn"))))

(deftest real-registry-matches-documented-availability-matrix
  (let [cmds (real-registry-commands)
        distribution (frequencies
                      (map (fn [c] [(:command/surface c) (:command/jar-availability c)])
                           cmds))
        ;; Must stay in lockstep with the Fixed-case availability matrix in
        ;; docs/specs/PRF_CLI_ARCHITECTURE_V1.md and validate-registry's
        ;; fixed-jar-availability-cases.
        expected {[:prf :native] 39
                  [:dev :native] 1
                  [:community :native] 9
                  [:bb :external] 9
                  [:bb :none] 12}]
    (is (= 70 (count cmds)))
    (is (= expected distribution))
    (is (= 70 (apply + (vals distribution))))))

(deftest sew-artifact-gated-commands-are-coherent
  (let [cmds (real-registry-commands)
        by-id (into {} (map (fn [c] [(:command/id c) c]) cmds))
        gated-ids (set @#'dispatch/sew-command-ids)]
    ;; Forward: every gated command is a registered, native, JVM command.
    (doseq [id gated-ids]
      (let [c (by-id id)]
        (is (some? c) (str "sew-gated command " id " missing from registry"))
        (when c
          (is (= :native (:command/jar-availability c)) (str id " not :native"))
          (is (= :jvm (:command/runtime c)) (str id " not :jvm"))
          (is (contains? #{:prf :dev} (:command/surface c)) (str id " unexpected surface " (:command/surface c))))))
    ;; Bidirectional: in a PRF-core-only distribution, exactly the gated
    ;; commands are unavailable; no other native command is accidentally gated.
    (with-redefs [dispatch/sew-capable? (constantly false)]
      (let [unavailable (set (keep (fn [c]
                                     (when-not (dispatch/command-available? (:command/id c))
                                       (:command/id c)))
                                   cmds))]
        (is (= gated-ids unavailable))))))

(deftest bb-tasks-are-not-jvm-dispatchable
  (let [cmds (real-registry-commands)
        bb-cmds (filter #(= :bb (:command/surface %)) cmds)
        bb-ids (set (map :command/id bb-cmds))
        handler-ids (set (keys (dispatch/get-command-handlers)))]
    (is (= 21 (count bb-cmds)) "expected 9 :external + 12 pure-bb tasks")
    (is (every? :command/bb-task bb-cmds) "every :bb command must declare :command/bb-task")
    (is (= 9 (count (filter #(= :external (:command/jar-availability %)) bb-cmds))))
    (is (= 12 (count (filter #(= :none (:command/jar-availability %)) bb-cmds))))
    (is (empty? (filter bb-ids handler-ids))
        "pure-bb commands must not dispatch through the JVM command entry point")))
