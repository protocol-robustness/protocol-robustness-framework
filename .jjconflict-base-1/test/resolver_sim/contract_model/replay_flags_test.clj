(ns resolver-sim.contract-model.replay-flags-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.protocols.dummy :as dummy]))

(def minimal-scenario
  {:scenario-id "flags-minimal"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]
   :options {:minimal true}})

(deftest resolve-minimal-flags
  (let [f (flags/resolve-replay-flags minimal-scenario)]
    (is (false? (:evaluate-theory? f)))
    (is (false? (:temporal-enabled? f)))
    (is (false? (:strict-validation? f)))
    (is (= :yield-provider (:metrics-profile f)))))

(deftest simple-replay-completes
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-opts-override
  (testing "replay-opts override :minimal true defaults in simple-replay path"
    (let [scenario (assoc minimal-scenario :theory {:falsifies-if []})
          ;; Force evaluate-theory? to true even if :minimal true would disable it
          result (replay/simple-replay dummy/protocol scenario {:flags {:evaluate-theory? true}})]
      ;; If evaluate-theory? was true, theory evaluation should have produced output
      (is (= :not-falsified (:status result))))))

(deftest explicit-flags-override-minimal
  (let [scenario (assoc-in minimal-scenario [:options :flags]
                           {:evaluate-theory? true :strict-validation? true})
        f (flags/resolve-replay-flags scenario {:minimal true})]
    (is (true? (:evaluate-theory? f)))
    (is (true? (:strict-validation? f)))))
