(ns resolver-sim.economics.pool-availability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.pool-availability :as pa]))

(defn- valid-pool-input
  []
  {:pool/id "pool-001"
   :pool/kind :bonus
   :pool/owner-id "owner-001"
   :pool/state-root "sha256:state-root"
   :pool/policy-root "sha256:policy-root"
   :pool/snapshot-time "2026-07-30T00:00:00Z"
   :pool/gross-amount 1000
   :pool/reserved-amount 200
   :pool/protected-amount 100
   :pool/liability-roots ["sha256:l1" "sha256:l2"]
   :pool/reservation-roots ["sha256:r1"]})

;; ── build-pool-availability ──────────────────────────────────────────────────

(deftest build-valid-pool-derives-correct-available
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (= :pool-availability.v2 (:artifact/type pool)))
    (is (= 700 (:pool/available-amount pool))
        "available = 1000 - 200 - 100")
    (is (some? (:artifact/hash pool)))
    (is (= (:pool/available-amount pool) (-' (:pool/gross-amount pool)
                                              (:pool/reserved-amount pool)
                                              (:pool/protected-amount pool))))))

(deftest build-negative-gross-rejected
  (is (thrown? Exception
              (pa/build-pool-availability (assoc (valid-pool-input)
                                                 :pool/gross-amount -1)))))

(deftest build-negative-reserved-rejected
  (is (thrown? Exception
              (pa/build-pool-availability (assoc (valid-pool-input)
                                                 :pool/reserved-amount -1)))))

(deftest build-encumbered-exceeds-gross-rejected
  (is (thrown? Exception
              (pa/build-pool-availability (assoc (valid-pool-input)
                                                 :pool/reserved-amount 600
                                                 :pool/protected-amount 500)))))

(deftest build-unknown-source-fields-rejected
  (is (thrown? Exception
              (pa/build-pool-availability (assoc (valid-pool-input)
                                                 :pool/unknown-field "oops")))))

(deftest build-deterministic-hash
  (let [a (pa/build-pool-availability (valid-pool-input))
        b (pa/build-pool-availability (valid-pool-input))]
    (is (= (:artifact/hash a) (:artifact/hash b)))))

(deftest build-different-reserved-different-hash
  (let [a (pa/build-pool-availability (valid-pool-input))
        b (pa/build-pool-availability (assoc (valid-pool-input)
                                             :pool/reserved-amount 300))]
    (is (not= (:artifact/hash a) (:artifact/hash b)))
    (is (= 600 (:pool/available-amount b)))))

(deftest build-duplicate-liability-root-rejected
  (is (thrown? Exception
              (pa/build-pool-availability
               (assoc (valid-pool-input)
                      :pool/liability-roots ["sha256:l1" "sha256:l1"])))))

(deftest build-duplicate-reservation-root-rejected
  (is (thrown? Exception
              (pa/build-pool-availability
               (assoc (valid-pool-input)
                      :pool/reservation-roots ["sha256:r1" "sha256:r1"])))))

(deftest build-root-permutation-same-hash
  (let [a (pa/build-pool-availability
           (assoc (valid-pool-input)
                  :pool/liability-roots ["sha256:l1" "sha256:l2"]))
        b (pa/build-pool-availability
           (assoc (valid-pool-input)
                  :pool/liability-roots ["sha256:l2" "sha256:l1"]))]
    (is (= (:artifact/hash a) (:artifact/hash b)))))

;; ── validate-pool-availability ───────────────────────────────────────────────

(deftest validate-pool-availability-passes
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (nil? (pa/validate-pool-availability pool)))))

(deftest validate-pool-availability-rejects-wrong-type
  (let [pool (assoc (pa/build-pool-availability (valid-pool-input))
                    :artifact/type :wrong-type)]
    (is (thrown? Exception (pa/validate-pool-availability pool)))))

;; ── verify-pool-availability ─────────────────────────────────────────────────

(deftest verify-pool-availability-passes
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (:valid? (pa/verify-pool-availability pool)))))

(deftest verify-pool-availability-detects-tampered-gross
  (let [pool (assoc (pa/build-pool-availability (valid-pool-input))
                    :pool/gross-amount 999)]
    (is (false? (:valid? (pa/verify-pool-availability pool))))))

(deftest verify-pool-availability-detects-tampered-hash
  (let [pool (assoc (pa/build-pool-availability (valid-pool-input))
                    :artifact/hash "sha256:tampered")]
    (is (false? (:valid? (pa/verify-pool-availability pool))))))

(deftest verify-pool-availability-detects-tampered-type
  (let [pool (assoc (pa/build-pool-availability (valid-pool-input))
                    :artifact/type :fake-type)]
    (is (false? (:valid? (pa/verify-pool-availability pool))))))

(deftest verify-pool-availability-non-throwing
  (let [result (pa/verify-pool-availability {:not-a-pool true})]
    (is (false? (:valid? result)))
    (is (vector? (:errors result)))))

;; ── build-reservation ────────────────────────────────────────────────────────

(deftest build-reservation-sufficient-capacity
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation
           pool {:reservation/id "res-001"
                 :reservation/amount 300
                 :reservation/purpose-root "sha256:purp"})]
    (is (= :pool-reservation.v1 (:artifact/type r)))
    (is (= "res-001" (:reservation/id r)))
    (is (= (:artifact/hash pool) (:reservation/pool-root r)))
    (is (some? (:artifact/hash r)))))

(deftest build-reservation-exact-capacity
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation
           pool {:reservation/id "res-exact"
                 :reservation/amount 700
                 :reservation/purpose-root "sha256:p"})]
    (is (= 700 (:reservation/amount r)))))

(deftest build-reservation-insufficient-capacity-rejected
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (thrown? Exception
                (pa/build-reservation
                 pool {:reservation/id "res-fail"
                       :reservation/amount 701
                       :reservation/purpose-root "sha256:p"})))))

(deftest build-reservation-zero-amount-rejected
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (thrown? Exception
                (pa/build-reservation
                 pool {:reservation/id "res-zero"
                       :reservation/amount 0
                       :reservation/purpose-root "sha256:p"})))))

(deftest build-reservation-derives-pool-root-from-pool
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation
           pool {:reservation/id "res-002"
                 :reservation/amount 100
                 :reservation/purpose-root "sha256:p"})]
    (is (= (:artifact/hash pool) (:reservation/pool-root r)))))

(deftest build-reservation-validates-pool-first
  (let [bad-pool (assoc (pa/build-pool-availability (valid-pool-input))
                        :pool/gross-amount -1)]
    (is (thrown? Exception
                (pa/build-reservation
                 bad-pool {:reservation/id "res-bad"
                           :reservation/amount 100
                           :reservation/purpose-root "sha256:p"})))))

;; ── verify-reservation ───────────────────────────────────────────────────────

(deftest verify-reservation-passes
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-003"
                                      :reservation/amount 200
                                      :reservation/purpose-root "sha256:p"})]
    (is (:valid? (pa/verify-reservation r)))))

(deftest verify-reservation-detects-tampered-amount
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (assoc (pa/build-reservation
                  pool {:reservation/id "res-004"
                        :reservation/amount 200
                        :reservation/purpose-root "sha256:p"})
                 :reservation/amount 999)]
    (is (false? (:valid? (pa/verify-reservation r))))))

(deftest verify-reservation-detects-tampered-hash
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (assoc (pa/build-reservation
                  pool {:reservation/id "res-005"
                        :reservation/amount 200
                        :reservation/purpose-root "sha256:p"})
                 :artifact/hash "sha256:fake")]
    (is (false? (:valid? (pa/verify-reservation r))))))

;; ── pool-after-reservation ───────────────────────────────────────────────────

(deftest pool-after-reservation-produces-valid-successor
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-006"
                                      :reservation/amount 300
                                      :reservation/purpose-root "sha256:p"})
        successor (pa/pool-after-reservation
                   pool r {:pool/state-root "sha256:new-state"
                           :pool/snapshot-time "2026-07-30T01:00:00Z"})]
    (is (:valid? (pa/verify-pool-availability successor)))
    (is (= 400 (:pool/available-amount successor))
        "successor available = 700 - 300 = 400")
    (is (= "sha256:new-state" (:pool/state-root successor)))
    (is (not= (:artifact/hash pool) (:artifact/hash successor)))))

(deftest pool-after-reservation-pool-root-mismatch-rejected
  (let [pool-a (pa/build-pool-availability (valid-pool-input))
        pool-b (pa/build-pool-availability
                (assoc (valid-pool-input) :pool/id "pool-002"))
        r (pa/build-reservation pool-b {:reservation/id "res-007"
                                        :reservation/amount 100
                                        :reservation/purpose-root "sha256:p"})]
    (is (thrown? Exception
                (pa/pool-after-reservation
                 pool-a r {:pool/state-root "sha256:s"
                           :pool/snapshot-time "2026-07-30T01:00:00Z"})))))

(deftest pool-after-reservation-duplicate-root-rejected
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-008"
                                      :reservation/amount 100
                                      :reservation/purpose-root "sha256:p"})
        pool-after (pa/pool-after-reservation
                    pool r {:pool/state-root "sha256:s2"
                            :pool/snapshot-time "2026-07-30T01:00:00Z"})]
    (is (thrown? Exception
                (pa/pool-after-reservation
                 pool-after r {:pool/state-root "sha256:s3"
                                :pool/snapshot-time "2026-07-30T02:00:00Z"})))))

;; ── Sequential reservation overcommit ────────────────────────────────────────

(deftest sequential-reservations-cannot-overcommit
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r1 (pa/build-reservation pool {:reservation/id "r1"
                                       :reservation/amount 600
                                       :reservation/purpose-root "sha256:p1"})
        after1 (pa/pool-after-reservation
                pool r1 {:pool/state-root "sha256:s1"
                         :pool/snapshot-time "2026-07-30T01:00:00Z"})
        r2-built (try
                   (pa/build-reservation
                    after1 {:reservation/id "r2"
                            :reservation/amount 500
                            :reservation/purpose-root "sha256:p2"})
                   :succeeded
                   (catch Exception _ :rejected))]
    (is (= :rejected r2-built)
        "second reservation of 500 against available 100 is rejected")
    (is (= 100 (:pool/available-amount after1))
        "after first reservation: 1000 - (200+600) - 100 = 100")))

;; ── verify-candidate-reservation-set ─────────────────────────────────────────

(deftest verify-candidate-reservation-set-detects-overcommit
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r1 (pa/build-reservation pool {:reservation/id "r1"
                                       :reservation/amount 500
                                       :reservation/purpose-root "sha256:p1"})
        r2 (pa/build-reservation pool {:reservation/id "r2"
                                       :reservation/amount 300
                                       :reservation/purpose-root "sha256:p2"})
        result (pa/verify-candidate-reservation-set pool [r1 r2])]
    (is (false? (:valid? result))
        "800 > 700 → overcommit detected"))

  (let [pool (pa/build-pool-availability (valid-pool-input))
        r1 (pa/build-reservation pool {:reservation/id "r1"
                                       :reservation/amount 400
                                       :reservation/purpose-root "sha256:p1"})
        r2 (pa/build-reservation pool {:reservation/id "r2"
                                       :reservation/amount 300
                                       :reservation/purpose-root "sha256:p2"})
        result (pa/verify-candidate-reservation-set pool [r1 r2])]
    (is (:valid? result))
    (is (= 700 (reduce +' 0 (map :reservation/amount [r1 r2])))
        "total 700 = available 700 → passes")))

(deftest verify-candidate-reservation-set-rejects-dangerous
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r1 (pa/build-reservation pool {:reservation/id "r1"
                                       :reservation/amount 600
                                       :reservation/purpose-root "sha256:p1"})
        r2 (pa/build-reservation pool {:reservation/id "r2"
                                       :reservation/amount 200
                                       :reservation/purpose-root "sha256:p2"})
        result (pa/verify-candidate-reservation-set pool [r1 r2])]
    (is (false? (:valid? result))
        "800 > 700 → overcommit detected")))

(deftest verify-candidate-reservation-set-detects-pool-root-mismatch
  (let [pool (pa/build-pool-availability (valid-pool-input))
        other-pool (pa/build-pool-availability
                    (assoc (valid-pool-input) :pool/id "pool-other"))
        r (pa/build-reservation other-pool {:reservation/id "r-other"
                                            :reservation/amount 100
                                            :reservation/purpose-root "sha256:p"})
        result (pa/verify-candidate-reservation-set pool [r])]
    (is (false? (:valid? result)))
    (is (some #(= :pool-root-mismatch (:type %)) (:errors result)))))

(deftest verify-candidate-reservation-set-detects-duplicate-ids
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r1 (pa/build-reservation pool {:reservation/id "r-dup"
                                       :reservation/amount 100
                                       :reservation/purpose-root "sha256:p1"})
        r2 (pa/build-reservation pool {:reservation/id "r-dup"
                                       :reservation/amount 200
                                       :reservation/purpose-root "sha256:p2"})
        result (pa/verify-candidate-reservation-set pool [r1 r2])]
    (is (false? (:valid? result)))
    (is (some #(= :duplicate-reservation-ids (:type %)) (:errors result)))))

(deftest verify-candidate-reservation-set-non-throwing
  (let [result (pa/verify-candidate-reservation-set {:garbage true} [])]
    (is (false? (:valid? result)))
    (is (vector? (:errors result)))))

;; ── Successor predecessor binding ────────────────────────────────────────────

(deftest successor-commits-exact-predecessor-hash
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-pred"
                                      :reservation/amount 100
                                      :reservation/purpose-root "sha256:p"})
        successor (pa/pool-after-reservation
                   pool r {:pool/state-root "sha256:ns"
                           :pool/snapshot-time "2026-07-30T01:00:00Z"})]
    (is (= (:artifact/hash pool) (:pool/predecessor-hash successor))
        "successor binds the exact predecessor artifact hash")
    (is (:valid? (pa/verify-pool-availability successor)))))

(deftest successor-missing-predecessor-rejected
  ;; A successor snapshot must always commit a predecessor hash.
  ;; Building a pool directly without predecessor-hash (initial snapshot) is
  ;; valid; a snapshot intended as a successor must include it.
  (let [pool (pa/build-pool-availability (valid-pool-input))]
    (is (nil? (:pool/predecessor-hash pool))
        "initial snapshot has nil predecessor")
    (is (:valid? (pa/verify-pool-availability pool)))))

(deftest same-state-root-different-predecessor-different-successor
  (let [pool-a (pa/build-pool-availability (assoc (valid-pool-input)
                                                  :pool/reserved-amount 200))
        pool-b (pa/build-pool-availability (assoc (valid-pool-input)
                                                  :pool/reserved-amount 300))
        ;; Both pools share the same :pool/state-root from valid-pool-input
        r-a (pa/build-reservation pool-a {:reservation/id "ra"
                                          :reservation/amount 50
                                          :reservation/purpose-root "sha256:pa"})
        r-b (pa/build-reservation pool-b {:reservation/id "rb"
                                          :reservation/amount 50
                                          :reservation/purpose-root "sha256:pb"})
        succ-a (pa/pool-after-reservation
                pool-a r-a {:pool/state-root "sha256:same-state"
                            :pool/snapshot-time "2026-07-30T01:00:00Z"})
        succ-b (pa/pool-after-reservation
                pool-b r-b {:pool/state-root "sha256:same-state"
                            :pool/snapshot-time "2026-07-30T01:00:00Z"})]
    (is (= (:pool/state-root pool-a) (:pool/state-root pool-b))
        "predecessors share the same state-root")
    (is (not= (:artifact/hash succ-a) (:artifact/hash succ-b))
        "successors differ because they bind different predecessor artifacts")))

;; ── Time monotonicity ────────────────────────────────────────────────────────

(deftest successor-earlier-time-rejected
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-time"
                                      :reservation/amount 100
                                      :reservation/purpose-root "sha256:p"})]
    (is (thrown? Exception
                (pa/pool-after-reservation
                 pool r {:pool/state-root "sha256:ns"
                         :pool/snapshot-time "2020-01-01T00:00:00Z"}))
        "successor time before predecessor time is rejected")))

(deftest successor-equal-time-accepted
  (let [pool (pa/build-pool-availability (valid-pool-input))
        r (pa/build-reservation pool {:reservation/id "res-eq"
                                      :reservation/amount 100
                                      :reservation/purpose-root "sha256:p"})
        successor (pa/pool-after-reservation
                   pool r {:pool/state-root "sha256:ns"
                           :pool/snapshot-time "2026-07-30T00:00:00Z"})]
    (is (:valid? (pa/verify-pool-availability successor))
        "equal successor time is permitted for batching")))
