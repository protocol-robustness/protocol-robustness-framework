(ns resolver-sim.economics.with-bounty.replay-test
  "C1 — implementation replay (design note §12.2, ADR-0006 C1): re-running the
   exact sealed eligibility and amount implementations against the committed
   inputs reproduces the committed artifacts. Classified :implementation-replay,
   never independent verification."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.economics.with-bounty.replay :as replay]))

(defn- applied-result
  []
  (proof/evaluate-bounty {:event/context {:review/finalised? true
                                          :event/actor :researcher/alice}
                          :base/result {:resolved-amount 10000}}))

(defn- skipped-result
  []
  (proof/evaluate-bounty {:event/context {:review/finalised? false}
                          :base/result {:resolved-amount 10000}}))

(deftest applied-result-replays-identically
  (let [original (applied-result)
        result (replay/replay-with-bounty original)]
    (is (= :implementation-replay (:verification/profile result)))
    (is (:valid? result))
    (is (empty? (:mismatches result)))
    (is (= :applied (:replayed/status result)))
    (is (some? (replay/replay-inputs original)))
    (is (= (policy/normalize-with-bounty-policy proof/review-policy)
           (get-in original [:replay/inputs :policy])))))

(deftest skipped-result-replays-identically
  (let [original (skipped-result)
        result (replay/replay-with-bounty original)]
    (is (= :implementation-replay (:verification/profile result)))
    (is (:valid? result))
    (is (= :skipped (:replayed/status result)))))

(deftest changed-committed-input-produces-mismatch
  (let [original (applied-result)
        tampered (assoc-in original [:replay/inputs :base-result :resolved-amount] 20000)
        result (replay/replay-with-bounty tampered)]
    (is (not (:valid? result)))
    (is (some #(= :effect (:field %)) (:mismatches result)))))

(deftest replay-classified-not-independent
  (let [original (applied-result)
        result (replay/replay-with-bounty original)]
    (is (= :implementation-replay (:verification/profile result)))
    (is (not= :independent (:verification/profile result)))))
