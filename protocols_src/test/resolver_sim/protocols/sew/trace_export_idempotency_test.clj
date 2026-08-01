(ns resolver-sim.protocols.sew.trace-export-idempotency-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export]
            [resolver-sim.io.scenarios :as scen-io]))

(deftest s64-export-surfaces-idempotency-on-deduped-steps
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario)
        fixture  (trace-export/export-trace-fixture result scenario)
        dup-exec (some #(when (= 3 (:seq %)) %) (:steps fixture))
        dup-set  (some #(when (= 5 (:seq %)) %) (:steps fixture))]
    (is (= :pass (:outcome result)))
    (is (= 2 (get-in fixture [:metadata "idempotency" :dedupe_step_count])))
    (is (= [3 5] (get-in fixture [:metadata "idempotency" :dedupe_steps])))
    (is (= "no-op-duplicate" (get-in dup-exec [:attributes :idempotency])))
    (is (= "evt-exec-res-1" (get-in dup-exec [:attributes :event_id])))
    (is (= "no-op-duplicate" (get-in dup-set [:attributes :idempotency])))
    (is (= "evt-settle-1" (get-in dup-set [:attributes :event_id])))))

(deftest export-includes-transfer-trace-decision-for-final-resolution
  (let [;; S11 resolves at appeal-window-duration 0; a zero window now creates a
        ;; pending settlement, so append an immediate keeper execution to reach
        ;; the terminal state (pending lifecycle, zero waiting period).
        scenario (-> (scen-io/load-scenario-file "scenarios/edn/S11_zero-fee-edge-case.edn")
                     (update :events conj
                             {:seq 3 :time 1180 :agent "resolver"
                              :action "execute_pending_settlement" :params {:workflow-id 0}}))
        result (replay/replay-with-protocol sew/protocol scenario)
        fixture (trace-export/export-trace-fixture result scenario)
        decision (get-in fixture [:expected_semantics :resolution :trace_decision])]
    (is (= :pass (:outcome result)))
    (is (= {:decision-id "resolve-0-0"
            :step 1120
            :alternatives [:release :refund]
            :selected :release
            :reasoning "Resolver 0xresolver releases escrow 0"
            :caller "0xresolver"
            :decision-evidence-hash (get-in decision [:decision-evidence-hash])}
           decision))
    (is (string? (:decision-evidence-hash decision)))))
