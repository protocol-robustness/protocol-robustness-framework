(ns resolver-sim.allocation.realized-execution-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.realized-execution :as execution]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc]))

(def statement-value
  {:schema-version statement/schema-version
   :statement/root "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"})

(def decision
  {:requested {"A" 50 "B" 30 "C" 20}
   :filled {"A" 50 "B" 30 "C" 20}
   :deferred {}
   :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}
   :evidence {:allocation-rows []}})

(def companion
  {:schema-version execution/schema-version
   :claim-recipient-mapping [{:claim/id "A" :recipient "0x1111111111111111111111111111111111111111"}
                             {:claim/id "B" :recipient "0x2222222222222222222222222222222222222222"}
                             {:claim/id "C" :recipient "0x3333333333333333333333333333333333333333"}]
   :token-address "0x4444444444444444444444444444444444444444"
   :execution-target {:allocator-address "0x5555555555555555555555555555555555555555"
                      :chain-id "1"
                      :operation-id "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      :available-amount "100"}})

(defn- projection
  [overrides]
  (execution/project-execution
   {:statement statement-value
    :decision (merge decision (:decision overrides))
    :companion (merge companion (:companion overrides))}))

(deftest rust-frozen-vector-and-public-values
  (let [result (projection {})]
    (is (= "2af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f"
           (:execution/operation-binding result)))
    (is (= ["f80d653aa6c98215e9d3a574dccb08c7b0772dddfff2d4404becf5f08bd4f30a"
            "23472ad42041455ea0d2ae5aaf7b6cb8f440622e2b53ad010bd0bdf8c566d227"
            "f08fd8ef2d0ea21606cc29d5ccfb387800f0fa09d79858ee4432e903ed98e612"]
           (:execution/claim-leaves result)))
    (is (= "407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3"
           (:execution/claim-root result)))
    (is (= 3 (:execution/allocation-count result)))
    (is (= 100 (:execution/total-allocated result)))
    (is (= 0 (:execution/residual-amount result)))
    (is (= execution/public-values-length (alength (:execution/public-values result))))
    (is (= "d2196a7baaa9f298fe2fc5bc00454f9455487bb55672083690a7c856d55f33ebc22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd812af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3000000000000000000000000444444444444444444444444444444444444444400000000000000000000000055555555555555555555555555555555555555550000000000000000000000000000000000000000000000000000000000000001aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0000000000000000000000000000000000000000000000000000000000000064000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000000640000000000000000000000000000000000000000000000000000000000000000"
           (hc/bytes->hex (:execution/public-values result))))
    (is (not= (:execution/operation-binding result)
              (execution/operation-binding
               (assoc companion :execution-target
                      (assoc (:execution-target companion) :available-amount "101")))))))

(deftest companion-rejects-noncanonical-wire-values
  (doseq [override [{:token-address "0x0000000000000000000000000000000000000000"}
                    {:execution-target (assoc (:execution-target companion) :chain-id "01")}
                    {:execution-target (assoc (:execution-target companion) :chain-id "0")}
                    {:execution-target (assoc (:execution-target companion) :operation-id "operation-42")}
                    {:execution-target (assoc (:execution-target companion) :available-amount "100.0")}]]
    (is (false? (execution/companion-valid? (merge companion override))))))

(deftest mapping-is-complete-unique-and-canonically-ordered
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mapping-not-exact"
                        (projection {:companion {:claim-recipient-mapping
                                                 (pop (:claim-recipient-mapping companion))}})))
  (is (false? (execution/companion-valid?
               (assoc companion :claim-recipient-mapping
                      (vec (reverse (:claim-recipient-mapping companion)))))))
  (is (false? (execution/companion-valid?
               (assoc companion :claim-recipient-mapping
                      [(first (:claim-recipient-mapping companion))
                       (first (:claim-recipient-mapping companion))])))))

(deftest ordered-duplicate-last-merkle-and-empty-root
  (is (= "407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3"
         (execution/execution-claim-root
          ["f80d653aa6c98215e9d3a574dccb08c7b0772dddfff2d4404becf5f08bd4f30a"
           "23472ad42041455ea0d2ae5aaf7b6cb8f440622e2b53ad010bd0bdf8c566d227"
           "f08fd8ef2d0ea21606cc29d5ccfb387800f0fa09d79858ee4432e903ed98e612"])))
  (is (= 64 (count (execution/execution-claim-root [])))))

(deftest projection-rejects-unsupported-profile-and-amount-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported"
                        (projection {:decision {:policy {:mode :pro-rata :rounding-policy :floor}}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"available-amount-mismatch"
                        (projection {:companion {:execution-target
                                                 (assoc (:execution-target companion) :available-amount "99")}}))))
