(ns resolver-sim.commands.backstop-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.cli.registry :as registry]
            [resolver-sim.commands.backstop :as backstop])
  (:import [java.io StringWriter]))

(deftest tier-returns-failure-when-a-check-fails
  (with-redefs [registry/list-commands
                (fn [] [{:id :failing-check :tier :fast :jar-avail :available}])
                dispatch/command-available? (constantly true)
                dispatch/dispatch-command (fn [_ _] {:exit-code 1 :message "expected failure"})]
    (let [result (binding [*out* (StringWriter.)]
                   (backstop/run-fast {}))]
      (is (= 1 (:exit-code result)))
      (is (= :completed-with-findings (:status result))))))
