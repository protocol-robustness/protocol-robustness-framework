(ns resolver-sim.contract-model.transition-assurance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.protocols.protocol :as proto]))

(defrecord LegacyProtocol []
  proto/SimulationAdapter
  (protocol-id [_] "legacy-test")
  (init-world [_ _] {:block-time 0 :n 0})
  (build-execution-context [_ _ _] {})
  (dispatch-action [_ _ world _] {:ok true :world (update world :n inc)})
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] world)
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil))

(defrecord AssuredProtocol [pass?]
  proto/SimulationAdapter
  (protocol-id [_] "assured-test")
  (init-world [_ _] {:block-time 0 :n 0})
  (build-execution-context [_ _ _] {})
  (dispatch-action [_ _ world _] {:ok true :world (update world :n inc)})
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] world)
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil)

  proto/TransitionAssurance
  (transition-basis [_ _ _ _] {:root "basis-root" :transition/root "transition-root"})
  (check-transition-assurance [_ _ _ _ _] {:ok? pass? :violations {:test :failed}}))

(def event {:seq 0 :time 1 :agent "a" :action "write" :params {}})
(def flags {:check-invariants? true :temporal-enabled? false :evidence-mode :none})

(deftest optional-legacy-assurance-is-explicitly-unsupported
  (let [result (execution/process-step (->LegacyProtocol) {:replay-flags flags} {:block-time 0 :n 0} event)]
    (is (:ok? result))
    (is (= :unsupported (get-in result [:trace-entry :transition-assurance :status])))))

(deftest required-unsupported-assurance-rejects-candidate-transition
  (let [before {:block-time 0 :n 0}
        result (execution/process-step (->LegacyProtocol)
                                       {:replay-flags flags
                                        :replay/requirements #{:transition-assurance/v1}}
                                       before event)]
    (is (not (:ok? result)))
    (is (:halted? result))
    (is (= before (:world result)))
    (is (= :unsupported (get-in result [:trace-entry :transition-assurance :status])))))

(deftest supported-assurance-must-pass-before-frame-acceptance
  (let [before {:block-time 0 :n 0}
        passed (execution/process-step (->AssuredProtocol true)
                                       {:replay-flags flags} before event)
        failed (execution/process-step (->AssuredProtocol false)
                                       {:replay-flags flags} before event)]
    (is (:ok? passed))
    (is (= :passed (get-in passed [:trace-entry :transition-assurance :status])))
    (is (not (:ok? failed)))
    (is (= before (:world failed)))
    (is (= :failed (get-in failed [:trace-entry :transition-assurance :status])))))
