(ns resolver-sim.protocols.sew.debug-escalation-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol       :as proto]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.contract-model.replay     :as replay]
            [resolver-sim.time.context :as time-ctx]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def usdc     "0xUSDC")
(def senior-resolver "0xSenior")

(defn make-escalation-fn [addr]
  (fn [_world _wf _caller _level] {:ok true :new-resolver addr}))

(defn base-world [appeal-window-duration]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps        50
                                        :max-dispute-duration  3600
                                        :appeal-window-duration appeal-window-duration})
        w0   (time-ctx/ensure-temporal-context (t/empty-world 1000))
        r    (lc/create-escrow w0 alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state]     :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status]    :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        (assoc-in [:dispute-timestamps 0] 1000))))

(defn with-pending [world workflow-id is-release appeal-deadline]
  (assoc-in world [:pending-settlements workflow-id]
            (t/make-pending-settlement {:exists true :is-release is-release
                                        :appeal-deadline appeal-deadline
                                        :resolution-hash "0xhash"})))

(deftest debug-replay-escalate-dispute-action
  (let [agents  [{:id "alice"    :address alice    :type "honest"}
                 {:id "bob"      :address bob       :type "honest"}
                 {:id "resolver" :address resolver  :type "resolver"}]
        esc-fn  (make-escalation-fn senior-resolver)
        context (proto/build-execution-context sew/protocol agents
                                               {:resolver-fee-bps 50
                                                :escalation-resolvers {:1 senior-resolver}})
        world   (-> (base-world 0)
                    (with-pending 0 true 5000)
                    (assoc-in [:dispute-timestamps 0] 1000)
                    (assoc-in [:escrow-transfers 0 :resolution]
                              {:resolved-by resolver
                               :is-release true
                               :resolution-hash "0xhash"}))
        event   {:seq 0 :time 1000 :agent "alice" :action "escalate_dispute"
                 :params {:workflow-id 0}}
        step    (replay/process-step sew/protocol context world event)]
    (println "=== VIOLATIONS:" (pr-str (get-in step [:trace-entry :violations])))
    (println "=== RESULT:" (pr-str (get-in step [:trace-entry :result])))
    (is (= :ok (get-in step [:trace-entry :result]))
        (pr-str (get-in step [:trace-entry :violations])))
    (is (= 1   (t/dispute-level (:world step) 0)))
    (is (= senior-resolver
           (get-in (:world step) [:escrow-transfers 0 :dispute-resolver])))))
