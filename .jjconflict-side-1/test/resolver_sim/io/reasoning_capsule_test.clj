(ns resolver-sim.io.reasoning-capsule-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.io.reasoning-capsule :as capsule]))

(deftest test-generate-capsule
  (let [capsule (capsule/generate-capsule
                 :scenario-id "S104"
                 :protocol "sew"
                 :risk-domain "slashing"
                 :failure-class "residual-liability-conservation"
                 :critical-path ["fraud-detected" "slash-requested" "junior-pool-debited"]
                 :relevant-invariants ["slash-obligation-conservation"]
                 :metrics-digest {:attack_attempts 1 :slash_requested 1000}
                 :diagnosis "Example diagnosis"
                 :recommended-next-test "Example test")]
    (is (= "reasoning-capsule.v1" (:schema_version capsule)))
    (is (= "S104" (:scenario_id capsule)))))
