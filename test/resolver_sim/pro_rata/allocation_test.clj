(ns resolver-sim.pro-rata.allocation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]
            [resolver-sim.pro-rata.evidence :as mechanism-evidence]
            [resolver-sim.pro-rata.claims :as claims]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.payoffs :as payoffs]))

(def rows
  [{:row/id :row/alice :obligation/id :withdrawal/alice :requested 8 :weight 8 :cap 8}
   {:row/id :row/bob :obligation/id :withdrawal/bob :requested 7 :weight 7 :cap 7}])

(defn- legacy-redistribution
  [request]
  ((ns-resolve 'resolver-sim.economics.payoffs
               'allocate-pro-rata-with-redistribution-legacy)
   request))

(deftest corrected-active-set-redistribution-intentionally-diverges-from-legacy
  (let [request {:amount 8
                 :items [{:id :a :weight 8 :cap 1}
                         {:id :b :weight 1 :cap 10}
                         {:id :c :weight 1 :cap 10}
                         {:id :d :weight 1 :cap 10}]
                 :cap-fn :cap
                 :rounding :floor-with-largest-remainder
                 :ordering-policy :canonical-id}
        legacy (legacy-redistribution request)
        corrected (payoffs/allocate-pro-rata-with-redistribution request)
        mechanism (allocation/allocate
                   {:allocation/id :semantic-divergence
                    :available 8
                    :redistribution-policy :redistribute-cap-excess
                    :rows [{:row/id :a :obligation/id :a :requested 10 :weight 8 :cap 1}
                           {:row/id :b :obligation/id :b :requested 10 :weight 1 :cap 10}
                           {:row/id :c :obligation/id :c :requested 10 :weight 1 :cap 10}
                           {:row/id :d :obligation/id :d :requested 10 :weight 1 :cap 10}]})]
    (is (= [[:a 1N] [:b 3N] [:c 3N] [:d 1N]]
           (mapv (juxt :id :allocated) (:allocations legacy))))
    (is (= [[:a 1N] [:b 3N] [:c 2N] [:d 2N]]
           (mapv (juxt :id :allocated) (:allocations corrected))))
    (is (not= (:allocations legacy) (:allocations corrected)))
    (is (empty? (invariants/result-violations mechanism)))))

(deftest allocation-is-permutation-invariant
  (testing "canonical row identity controls tie-breaking and evidence order"
    (let [forward (allocation/allocate {:allocation/id :example :available 10 :rows rows})
          reverse (allocation/allocate {:allocation/id :example :available 10 :rows (vec (reverse rows))})]
      (is (= forward reverse))
      (is (= 10N (:allocated-total forward)))
      (is (= 0N (:unallocated-residual forward)))
      (is (= [:row/alice :row/bob] (mapv :row/id (:rows forward)))))))

(deftest capped-redistribution-uses-final-active-set-quota
  (let [result (allocation/allocate
                {:allocation/id :cap-only :available 10
                 :redistribution-policy :redistribute-cap-excess
                 :rows [{:row/id :a :obligation/id :a :requested 10 :weight 8 :cap 2}
                        {:row/id :b :obligation/id :b :requested 10 :weight 1 :cap 10}
                        {:row/id :c :obligation/id :c :requested 10 :weight 1 :cap 10}]})]
    (is (= [[:a 2N] [:b 4N] [:c 4N]]
           (mapv (juxt :row/id :allocated) (:rows result))))
    (is (= 2 (count (:allocation/rounds result))))
    (is (empty? (invariants/result-violations result)))))

(deftest capped-redistribution-rounds-final-active-set-canonically
  (let [result (allocation/allocate
                {:allocation/id :cap-rounding :available 8
                 :redistribution-policy :redistribute-cap-excess
                 :rows [{:row/id :a :obligation/id :a :requested 10 :weight 8 :cap 1}
                        {:row/id :b :obligation/id :b :requested 10 :weight 1 :cap 10}
                        {:row/id :c :obligation/id :c :requested 10 :weight 1 :cap 10}
                        {:row/id :d :obligation/id :d :requested 10 :weight 1 :cap 10}]})]
    (is (= [[:a 1N] [:b 3N] [:c 2N] [:d 2N]]
           (mapv (juxt :row/id :allocated) (:rows result))))
    (is (= [:b] (mapv :row/id (filter :remainder-unit-awarded? (:rows result)))))
    (is (empty? (invariants/result-violations result)))))

(deftest round-witness-tampering-is-detected
  (let [result (allocation/allocate
                {:allocation/id :tamper :available 8
                 :redistribution-policy :redistribute-cap-excess
                 :rows [{:row/id :a :obligation/id :a :requested 10 :weight 8 :cap 1}
                        {:row/id :b :obligation/id :b :requested 10 :weight 1 :cap 10}
                        {:row/id :c :obligation/id :c :requested 10 :weight 1 :cap 10}
                        {:row/id :d :obligation/id :d :requested 10 :weight 1 :cap 10}]})
        cap-tampered (assoc-in result [:rows 0 :allocated] 2)
        rank-tampered (assoc-in result [:rows 2 :remainder-rank] 0)
        continuity-tampered (assoc-in result [:allocation/rounds 1 :available-at-start] 99)]
    (is (some #(= :pro-rata/cap-exceeded (:reason %))
              (invariants/result-violations cap-tampered)))
    (is (some #(= :pro-rata/remainder-rank-mismatch (:reason %))
              (invariants/result-violations rank-tampered)))
    (is (some #(= :pro-rata/round-continuity-failed (:reason %))
              (invariants/result-violations continuity-tampered)))
    (is (some #(= :pro-rata/allocation-hash-mismatch (:reason %))
              (invariants/result-violations cap-tampered)))))

(deftest mechanism-claims-consume-persisted-witnesses
  (let [result (allocation/allocate
                {:allocation/id :claims :available 8
                 :redistribution-policy :redistribute-cap-excess
                 :rows [{:row/id :a :obligation/id :a :requested 10 :weight 8 :cap 1}
                        {:row/id :b :obligation/id :b :requested 10 :weight 1 :cap 10}
                        {:row/id :c :obligation/id :c :requested 10 :weight 1 :cap 10}
                        {:row/id :d :obligation/id :d :requested 10 :weight 1 :cap 10}]})
        context {:evidence-nodes [{:result {:claims/mechanism-result result}}]}]
    (is (true? (:holds? (claims/check-cap-respecting context))))
    (is (true? (:holds? (claims/check-quota-bounded context))))
    (is (true? (:holds? (claims/check-canonical-remainder-assignment context))))))

(deftest mechanism-evidence-envelope-binds-complete-result
  (let [result (allocation/allocate {:allocation/id :envelope :available 10 :rows rows})
        envelope (mechanism-evidence/mechanism-evidence-artifact result)
        reference (mechanism-evidence/evidence-reference envelope)]
    (is (empty? (mechanism-evidence/evidence-violations envelope)))
    (is (= (:allocation/hash result) (:allocation/hash reference)))
    (is (= (:mechanism result) (:mechanism reference)))
    (is (some #(= :pro-rata/mechanism-evidence-hash-mismatch (:reason %))
              (mechanism-evidence/evidence-violations
               (assoc-in envelope [:mechanism/result :rows 0 :allocated] 99))))
    (is (some #(= :pro-rata/mechanism-evidence-result-hash-mismatch (:reason %))
              (mechanism-evidence/evidence-violations
               (assoc envelope :mechanism/result-hash "other"))))
    (let [forged-result (assoc-in result [:rows 0 :allocated] 9)
          forged-result (assoc forged-result :allocation/hash
                               (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                    (dissoc forged-result :allocation/hash)))
          forged-envelope (mechanism-evidence/mechanism-evidence-artifact forged-result)]
      ;; All self-hashes and envelope hashes are internally consistent, but the
      ;; verifier still rejects a witness that cannot be reconstructed.
      (is (some #(= :pro-rata/allocation-reconstruction-mismatch (:reason %))
                (mechanism-evidence/evidence-violations forged-envelope))))))

(deftest allocation-rejects-duplicate-row-identity
  (testing "duplicate evidence is rejected rather than silently collapsed"
    (let [error (try
                  (allocation/allocate {:allocation/id :duplicate
                                        :available 1
                                        :rows [(first rows) (first rows)]})
                  nil
                  (catch clojure.lang.ExceptionInfo exception exception))]
      (is error)
      (is (= :duplicate-allocation-row-id (:reason (ex-data error)))))))

(deftest integer-representations-produce-the-same-canonical-result
  (let [base {:allocation/id :integer-normalization
              :available 3
              :rows [{:row/id :a :obligation/id :a :requested 3 :weight 1 :cap 3}
                     {:row/id :b :obligation/id :b :requested 3 :weight 1 :cap 3}]}
        long-result (allocation/allocate base)
        bigint-result (allocation/allocate
                       {:allocation/id :integer-normalization
                        :available 3N
                        :rows [{:row/id :a :obligation/id :a :requested 3N :weight 1N :cap 3N}
                               {:row/id :b :obligation/id :b :requested 3N :weight 1N :cap 3N}]})]
    (is (= long-result bigint-result))
    (is (= (:request/hash long-result) (:request/hash bigint-result)))
    (is (= (:allocation/hash long-result) (:allocation/hash bigint-result)))
    (is (empty? (invariants/result-violations bigint-result)))))

(deftest allocation-rejects-non-canonical-identity
  (let [error (try
                (allocation/allocate {:allocation/id :invalid-id
                                      :available 1
                                      :rows [{:row/id #{:not :canonical}
                                              :obligation/id :a
                                              :requested 1 :weight 1}]})
                nil
                (catch clojure.lang.ExceptionInfo exception exception))]
    (is error)
    (is (= :unsupported-allocation-row-id (:reason (ex-data error))))))

(deftest residual-reasons-are-machine-verifiable
  (let [no-participants (allocation/allocate {:allocation/id :no-participants
                                              :available 5 :rows []})
        no-weight (allocation/allocate {:allocation/id :no-weight
                                        :available 5
                                        :rows [{:row/id :a :obligation/id :a
                                                :requested 5 :weight 0}]})
        floor-dust (allocation/allocate {:allocation/id :floor-dust
                                         :available 5 :rounding-policy :floor
                                         :rows [{:row/id :a :obligation/id :a :requested 5 :weight 1}
                                                {:row/id :b :obligation/id :b :requested 5 :weight 1}]})]
    (is (= [:no-remaining-capacity :no-active-weight :floor-rounding]
           (mapv :residual-reason [no-participants no-weight floor-dust])))
    (is (every? #(empty? (invariants/residual-violations %))
                [no-participants no-weight floor-dust]))
    (is (some #(= :pro-rata/residual-reason-not-established (:reason %))
              (invariants/residual-violations
               (assoc no-weight :residual-reason :no-remaining-capacity))))
    (is (some #(= :pro-rata/unexpected-residual-reason (:reason %))
              (invariants/residual-violations
               (assoc no-weight :unallocated-residual 0))))))
