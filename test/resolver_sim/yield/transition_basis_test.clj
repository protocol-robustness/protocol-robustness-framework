(ns resolver-sim.yield.transition-basis-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.transition-basis :as basis]
            [resolver-sim.yield.invariants-transition :as transitions]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(def before {:yield/indices {:aave-v3 {:USDC 1.0}}
             :yield/positions {"u" {:principal 100}}})
(def after (assoc-in before [:yield/indices :aave-v3 :USDC] 1.1))
(def event {:seq 1 :time 2 :agent "u" :action "yield_accrue" :params {:token "USDC"}})

(deftest basis-authenticates-both-states
  (let [b (basis/build before after event)]
    (is (:holds? (basis/validate before after event b)))
    (is (not (:holds? (basis/validate (assoc-in before [:yield/positions "u" :principal] 101)
                                      after event b))))
    (is (not (:holds? (basis/validate before after (assoc event :seq 2) b))))
    (is (not (:holds? (basis/validate (assoc-in before [:yield/risk :aave-v3 :USDC :failure-modes]
                                                #{:negative-yield}) after event b))))))

(deftest missing-index-is-not-defaulted
  (testing "deletion is an invalid index transition, not a transition to 1.0"
    (let [removed (update-in after [:yield/indices :aave-v3] dissoc :USDC)]
      (is (not (transitions/check-index-monotone-transition before removed))))))

(deftest authoritative-check-requires-valid-basis
  (let [b (basis/build before after event)
        result (transitions/check-transition-authoritative before after event (assoc b :state-after/root "bad"))]
    (is (not (:all-hold? result)))
    (is (some #(= :state-after-root-mismatch (:reason %))
              (get-in result [:results :yield/state-addressed :violations])))))

(deftest authoritative-check-rejects-malformed-committed-amounts
  (doseq [[path value] [[[:yield/positions "u" :principal] 1.5]
                        [[:yield/positions "u" :realized-yield] "bad"]
                        [[:yield/positions "u" :shortfall :basis-amount] 1.5]
                        [[:yield/withdrawal-ledger 0 :ledger/requested] 1.5]
                        [[:yield/withdrawal-ledger 0 :ledger/rows 0 :filled] "bad"]
                        [[:yield/partial-fill-decisions "d" :requested "u"] 1.5]]]
    (let [bad-after (assoc-in after path value)
          result (transitions/check-transition-authoritative
                  before bad-after event (basis/build before bad-after event))]
      (is (not (:all-hold? result)) (str "reject " path))
      (is (some #(= :invalid-state-after (:reason %))
                (get-in result [:results :yield/state-addressed :violations]))
          (str "report " path)))))

(deftest valid-cutpoint-from-another-transition-is-rejected
  (let [other-before (assoc-in before [:yield/positions "u" :principal] 200)
        wrong-cutpoint (partial-fill/ledger-state-cutpoint-root other-before)
        after-with-ledger (assoc after :yield/withdrawal-ledger
                                 [{:ledger/id "l" :ledger/state-cutpoint-root wrong-cutpoint}])
        b (basis/build before after-with-ledger event)
        result (transitions/check-transition-authoritative before after-with-ledger event b)]
    (is (not (:all-hold? result)))
    (is (= :withdrawal-cutpoint-transition-mismatch
           (get-in result [:results :yield/withdrawal-cutpoint :violations 0 :reason])))))
