(ns resolver-sim.protocols.sew.with-bounty-test
  "Focused adapter regression tests for with-bounty idempot(ns resolver-sim.protocols.sew.with-bounty-test)ent replay."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.protocols.sew.with-bounty :as sut]))

(defn- applied-plan []
  (:plan (proof/evaluate-bounty
          {:event/context {:review/finalised? true
                           :event/actor :researcher/alice}
           :base/result {:resolved-amount 10000}
           :adapter-support sut/adapter-support})))

(defn- drift-violation [plan world]
  (try
    (sut/apply-with-bounty-plan plan world)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:violation/id (ex-data e)))))

(deftest idempotent-replay-rejects-fabricated-or-mutated-artifacts
  (let [plan (applied-plan)
        first-application (sut/apply-with-bounty-plan plan {})
        world (:world first-application)
        payable-id (:plan/obligation-id plan)
        backing-id (str "backing-" payable-id)]
    (is (:idempotent? (sut/apply-with-bounty-plan plan world)))
    (is (= :violation/with-bounty-state-drift
           (drift-violation plan
                            (assoc-in world [:with-bounty/payables payable-id
                                             :payable/amount] 1))))
    (is (= :violation/with-bounty-state-drift
           (drift-violation plan
                            (assoc-in world [:with-bounty/backings backing-id
                                             :backing/distribution-root]
                                      "sha256:fabricated"))))
    (is (= :violation/with-bounty-state-drift
           (drift-violation plan
                            (assoc-in world [:held-artifacts "held-adjustment-0"
                                             :artifact/hash]
                                      "sha256:fabricated"))))))
