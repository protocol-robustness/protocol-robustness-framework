(ns resolver-sim.run.runner-finalization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.run.runner-finalization :as runner-finalization]))

(def input
  {:run-id "run-test"
   :runner-selection {:mode :pinned :runner-id :runner/local-bb}
   :source-provenance {:source/hash "source-implementation-hash"}
   :execution-result {:execution/termination :completed
                      :semantic/outcome :fail
                      :cli/exit-code 1
                      :bundle/root-hash "bundle-hash"}})

(deftest runner-finalization-is-immutable-and-local
  (let [artifact (runner-finalization/build input)
        validation (runner-finalization/valid? artifact)]
    (is (:valid? validation))
    (is (= :runner-local (get-in artifact [:runner/local :runtime/kind])))
    (is (= :runner/local-bb (get-in artifact [:runner/selection :runner-id])))
    (is (= :completed (get-in artifact [:execution/result :execution/termination])))
    (is (= :fail (get-in artifact [:execution/result :semantic/outcome])))
    (is (:runnable? (runner-finalization/runnable? artifact)))))

(deftest runner-finalization-rejects-tampering
  (let [artifact (runner-finalization/build input)]
    (is (not (:valid? (runner-finalization/valid?
                       (assoc-in artifact [:execution/result :cli/exit-code] 0)))))))
