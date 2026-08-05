(ns resolver-sim.commands.scenario-value-at-risk-test
  (:require [clojure.test :refer [deftest is testing]] [resolver-sim.commands.scenario-value-at-risk :as v]))
(def scenario {:value-at-risk {:at {:timestamp "2026-04-18T10:15:00Z" :event-index 8 :event-id "settlement-deadline-reached" :phase :post-event} :risk {:id "late-interaction-custody"} :value {:asset "USDC" :amount-encoding "scenario-native-integer"} :scope {:kind :workflow :id 42} :calculation {:selector [:workflows 42 :custody :held]}}})
(def replay {:trace [{:seq 8 :time 1776507300 :params {:event-id "settlement-deadline-reached"} :world {:workflows {42 {:custody {:held 10000}}}}}]})
(def provenance {:input/snapshot-relative "inputs/scenarios/example.edn"})
(def source "scenarios/example/execution/replay-output.json")
(deftest strict-observation
  (let [o (v/build-observation scenario replay provenance source)]
    (is (= "pass" (get o "status")))
    (is (true? (v/valid-amount? 10000 "scenario-native-integer")))
    (is (false? (v/valid-amount? -1 "scenario-native-integer")))
    (is (false? (v/valid-amount? 1.5 "scenario-native-integer")))
    (is (= "pass" (get (v/validate-persisted o scenario replay provenance source) "status")))
    (is (= "fail" (get (v/build-observation (assoc-in scenario [:value-at-risk :calculation :selector] [:workflows 99 :custody :held]) replay provenance source) "status")))
    (is (some #{"valid-amount"} (get-in o ["validation" "checks"])))))

(deftest instant-event-time-accepted
  (let [replay-inst {:trace [{:seq 8 :time (java.time.Instant/ofEpochSecond 1776507300)
                              :params {:event-id "settlement-deadline-reached"}
                              :world {:workflows {42 {:custody {:held 10000}}}}}]}
        o (v/build-observation scenario replay-inst provenance source)]
    (is (= "pass" (get o "status")))
    (is (= "2026-04-18T10:15:00Z" (get o "timestamp")))))

(deftest sub-second-instant-time-is-floored-to-clock-precision
  (testing "a sub-second Instant event time reports the floored epoch second, not precision the clock lacks"
    (let [replay-sub {:trace [{:seq 8 :time (java.time.Instant/ofEpochSecond 1776507300 999999999)
                               :params {:event-id "settlement-deadline-reached"}
                               :world {:workflows {42 {:custody {:held 10000}}}}}]}
          o (v/build-observation scenario replay-sub provenance source)]
      (is (= "pass" (get o "status")))
      (is (= "2026-04-18T10:15:00Z" (get o "timestamp"))
          "reported timestamp must match the second-precision world clock")))

  (testing "a declared sub-second timestamp can no longer match a floored clock"
    (let [sub-declared (assoc-in scenario [:value-at-risk :at :timestamp] "2026-04-18T10:15:00.500Z")
          replay-sub {:trace [{:seq 8 :time (java.time.Instant/ofEpochSecond 1776507300 500000000)
                               :params {:event-id "settlement-deadline-reached"}
                               :world {:workflows {42 {:custody {:held 10000}}}}}]}
          o (v/build-observation sub-declared replay-sub provenance source)]
      (is (= "fail" (get o "status")))
      (is (some #{"declared-timestamp-mismatch"} (get o "reason_codes"))))))

(deftest validate-persisted-tolerates-additive-fields
  (let [o (v/build-observation scenario replay provenance source)
        extra (assoc o "note" "enrichment added later")]
    (is (= "pass" (get (v/validate-persisted o scenario replay provenance source) "status")))
    (is (= "pass" (get (v/validate-persisted extra scenario replay provenance source) "status"))
        "additive fields must not invalidate an otherwise-correct observation")))

;; ---------------------------------------------------------------------------
;; Real-protocol replay compatibility (Sew worlds use :escrow-transfers, events
;; carry no :event-id param; the declared :event-id falls back to the action name)
;; ---------------------------------------------------------------------------

(def sew-replay
  {:trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
            :params {:token "USDC" :to "0xseller" :amount 5000}
            :world {:escrow-transfers {0 {:amount-after-fee 4925}}}}
           {:seq 1 :time 2000 :agent "keeper" :action "automate_timed_actions"
            :params {:workflow-id 0}
            :world {:escrow-transfers {0 {:amount-after-fee 4925}}}}]})

(def sew-scenario
  {:value-at-risk
   {:at {:timestamp "1970-01-01T00:16:40Z" :event-index 0 :event-id "create_escrow" :phase :post-event}
    :risk {:id "late-interaction-custody"}
    :value {:asset "USDC" :amount-encoding "scenario-native-integer"}
    :scope {:kind :workflow :id 0}
    :calculation {:selector [:escrow-transfers 0 :amount-after-fee]}}})

(deftest observation-works-against-sew-shaped-world
  (testing "selector root is data-driven (:escrow-transfers) and :event-id falls back to the action name"
    (let [o (v/build-observation sew-scenario sew-replay provenance source)]
      (is (= "pass" (get o "status")))
      (is (= 4925 (get-in o ["value" "amount"])))
      (is (= "create_escrow" (get-in o ["event" "id"]))))))

(deftest event-id-mismatch-rejects-wrong-action
  (testing "declared :event-id must match the event action when the event has no :event-id param"
    (let [wrong (assoc-in sew-scenario [:value-at-risk :at :event-id] "settlement-deadline-reached")
          o (v/build-observation wrong sew-replay provenance source)]
      (is (= "fail" (get o "status")))
      (is (some #{"event-id-mismatch"} (get o "reason_codes"))))))

(deftest scope-not-found-when-selector-misses-world
  (testing "scope lookup uses the selector root; a missing workflow is scope-not-found"
    (let [sc (-> sew-scenario
                 (assoc-in [:value-at-risk :scope :id] 99)
                 (assoc-in [:value-at-risk :calculation :selector] [:escrow-transfers 99 :amount-after-fee]))
          o (v/build-observation sc sew-replay provenance source)]
      (is (= "fail" (get o "status")))
      (is (some #{"scope-not-found"} (get o "reason_codes"))))))

(deftest phase-accepted-as-string-or-keyword
  (testing "declared :phase normalizes keyword/string to \"post-event\""
    (let [kw (assoc-in sew-scenario [:value-at-risk :at :phase] :post-event)
          str (assoc-in sew-scenario [:value-at-risk :at :phase] "post-event")]
      (is (= "pass" (get (v/build-observation kw sew-replay provenance source) "status")))
      (is (= "pass" (get (v/build-observation str sew-replay provenance source) "status")))
      (is (= "fail" (get (v/build-observation (assoc-in sew-scenario [:value-at-risk :at :phase] "pre-event")
                                              sew-replay provenance source) "status"))))))

(deftest timeline-reads-sew-world-selector
  (let [tl (v/value-at-risk-timeline sew-scenario sew-replay source)]
    (is (= "derived" (get tl "status")))
    (is (= 2 (count (get tl "rows"))))
    (is (= 4925 (get-in (first (get tl "rows")) ["amount"])))))
