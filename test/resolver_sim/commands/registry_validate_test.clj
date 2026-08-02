(ns resolver-sim.commands.registry-validate-test
  (:require [clojure.test :refer [deftest is testing]]
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
   :command/jar-availability :native
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
