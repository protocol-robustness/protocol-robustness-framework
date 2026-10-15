(ns resolver-sim.pro-rata.exact-verifier-test
  "SP-B: independent exact pro-rata verification.

   Core invariant under test: the exact verifier must NOT replay the allocator.
   It reconstructs the mathematically expected allocation from the canonical
   request via its own decomposition (single-pass -> redistribution chain ->
   fixed point) and only then compares the claimed result."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.exact-verifier-conformance :as conf]
            [resolver-sim.pro-rata.exact-verifier :as v]
            [resolver-sim.pro-rata.redistribution :as redistribution]))

(def ^:const locked-corpus-identity
  "Frozen identity over corpus + spec version. Update deliberately and only
   alongside a reviewed math change."
  "94200977d363128af4cb7d5cc86c54c8936cbbba71914a79a5ee5e6bb3505324")

;; ---------------------------------------------------------------------------
;; independence
;; ---------------------------------------------------------------------------

(deftest verifier-is-independent-of-the-allocator
  (testing "exact-verifier depends only on the generic verification layer"
    (let [aliases (set (keys (ns-aliases (the-ns 'resolver-sim.pro-rata.exact-verifier))))]
      (is (= #{'verification} aliases))
      (is (not (contains? aliases 'payoffs)))
      (is (not (contains? aliases 'allocation)))
      (is (not (contains? aliases 'partial-fill)))))
  (testing "no fully-qualified reference to a forbidden namespace appears in source"
    (let [source (slurp (io/resource "resolver_sim/pro_rata/exact_verifier.clj"))
          forbidden ["resolver-sim.pro-rata.allocation"
                     "resolver-sim.economics.payoffs"
                     "resolver-sim.yield.partial-fill"]]
      (doseq [ns-name forbidden]
        (is (not (re-find (re-pattern (str ns-name "/")) source))
            (str "must not reference " ns-name " even fully-qualified"))))))

(deftest reconstruction-is-independent-of-the-claimed-result
  (testing "the claimed result never decides the expected allocation"
    (let [verdict (v/verify-weighted-proportionality
                   {:amount 7 :items [{:id :a :weight 4}
                                      {:id :b :weight 4}
                                      {:id :c :weight 2}]
                    :rounding :floor-with-largest-remainder
                    :ordering-policy :input-order
                    :cap-treatment :unallocated}
                   {})]
      (is (= :failed (:status verdict)))
      (is (= [:a :b :c] (:expected-rows (:details verdict)))
          "reconstruction proceeds even when the claimed result is empty")
      (is (every? nil? (vals (:claimed-totals (:details verdict))))
          "the empty claimed result contributed no totals")
      (is (every? some? (vals (:expected-totals (:details verdict))))
          "expected totals are derived independently of the claimed result"))))

;; ---------------------------------------------------------------------------
;; frozen corpus + identity
;; ---------------------------------------------------------------------------

(deftest frozen-corpus-reconstructs-hand-derived-targets
  (doseq [{:keys [id request expected chain]} v/frozen-corpus
          :let [r (v/reconstruct request)
                rows (:rows r)]]
    (testing (str id)
      (is (= expected
             (into {} (map (fn [[k _]] [k (:allocated (get rows k))])) expected)))
      (when chain
        (let [reconstructed-chain (->> (get-in r [:redistribution :passes])
                                       (map :newly-capped-ids)
                                       (remove empty?)
                                       (mapv #(mapv identity %)))]
          (is (= (mapv (fn [g] (mapv identity g)) chain) reconstructed-chain)))))))

(deftest frozen-corpus-identity-is-locked
  (is (= locked-corpus-identity (v/corpus-identity))))

;; ---------------------------------------------------------------------------
;; supported policy domain (coverage :complete is only truthful over this)
;; ---------------------------------------------------------------------------

(def evaluator-admitted-canonical-policy-domain
  "Policies admitted by the evaluator's canonical path (payoffs
   canonical-pro-rata-request guards): rounding #{floor,largest-remainder},
   cap-treatment #{unallocated,redistribute}, tie-break #{input-order}."
  {:rounding #{:floor :floor-with-largest-remainder}
   :cap-treatment #{:unallocated :redistribute}
   :ordering #{:input-order}})

(deftest verifier-supported-domain-covers-the-evaluator-path
  (testing "every policy the evaluator path admits is implemented by the verifier"
    (doseq [rounding (:rounding evaluator-admitted-canonical-policy-domain)
            cap-treatment (:cap-treatment evaluator-admitted-canonical-policy-domain)
            ordering (:ordering evaluator-admitted-canonical-policy-domain)]
      (is (contains? (:rounding v/supported-policies) rounding) (str "rounding " rounding))
      (is (contains? (:cap-treatment v/supported-policies) cap-treatment) (str "cap-treatment " cap-treatment))
      (is (contains? (:ordering v/supported-policies) ordering) (str "ordering " ordering)))))

(deftest verifier-returns-unsupported-never-silently-narrower
  (testing "an out-of-domain request yields :unsupported, not a silent pass/fail"
    (doseq [anything-else [:floor-and-carry :by-mode :sequence :not-a-policy]]
      (doseq [policy {:rounding anything-else}
              :let [verdict (v/verify-weighted-proportionality
                             (merge {:amount 3
                                     :items [{:id :a :weight 1} {:id :b :weight 1}]
                                     :rounding :floor-with-largest-remainder
                                     :ordering-policy :input-order
                                     :cap-treatment :unallocated}
                                    policy)
                             {})]]
        (is (= :unsupported (:status verdict)) (str policy))
        (is (get-in verdict [:details :unsupported-dims]) (str policy))))
    (let [verdict (v/verify-weighted-proportionality
                   {:amount 3
                    :items [{:id :a :weight 1} {:id :b :weight 1}]
                    :rounding :floor-with-largest-remainder
                    :ordering-policy :djb-ht
                    :cap-treatment :unallocated}
                   {})]
      (is (= :unsupported (:status verdict)))
      (is (= {:ordering :djb-ht} (:unsupported-dims (:details verdict)))))))

;; ---------------------------------------------------------------------------
;; verification decision
;; ---------------------------------------------------------------------------

(defn- claimed-from-reconstruction
  "Assemble a claimed result map that matches the independent reconstruction."
  [request]
  (let [{:keys [rows total-allocated total-unmet remainder]} (v/reconstruct request)]
    {:allocations (mapv (fn [r] (select-keys r [:id :allocated :unmet])) (vals rows))
     :total-allocated total-allocated
     :total-unmet total-unmet
     :remainder remainder}))

(deftest verify-accepts-consistent-claimed-and-rejects-tampering
  (doseq [{:keys [id request]} v/frozen-corpus]
    (testing (str id)
      (let [good (claimed-from-reconstruction request)
            verdict (v/verify-weighted-proportionality request good)]
        (is (= :passed (:status verdict)))
        (let [tampered (update-in good [:allocations 0 :allocated] + 1)
              tv (v/verify-weighted-proportionality request tampered)]
          (is (= :failed (:status tv)) "a shifted allocated amount fails verification"))
        (let [totals-tampered (assoc good :total-allocated (inc (:total-allocated good)))
              t2 (v/verify-weighted-proportionality request totals-tampered)]
          (is (= :failed (:status t2)) "a totals mismatch fails verification"))))))

(deftest agrees-with-the-allocator-and-detects-deviation
  (let [cases [{:amount 100 :items [{:id :a :weight 40} {:id :b :weight 60}]
                :rounding :floor-with-largest-remainder :ordering-policy :input-order
                :cap-treatment :unallocated}
               {:amount 10 :items [{:id :a :weight 5 :cap 2} {:id :b :weight 5}]
                :rounding :floor-with-largest-remainder :ordering-policy :input-order
                :cap-treatment :redistribute}
               {:amount 10 :items [{:id :a :weight 5 :cap 4} {:id :b :weight 5 :cap 4}]
                :rounding :floor-with-largest-remainder :ordering-policy :input-order
                :cap-treatment :redistribute}
               {:amount 7 :items [{:id :a :weight 4} {:id :b :weight 4} {:id :c :weight 2}]
                :rounding :floor :ordering-policy :canonical-id :cap-treatment :unallocated}]]
    (doseq [{:keys [amount items rounding ordering-policy cap-treatment]} cases]
      (testing (str "amount=" amount " " cap-treatment)
        (let [claim (if (= :redistribute cap-treatment)
                      (redistribution/allocate-pro-rata-with-redistribution
                       {:amount amount :items items :rounding rounding
                        :ordering-policy ordering-policy :cap-fn :cap})
                      (allocation/allocate-pro-rata
                       {:amount amount :items items :rounding rounding
                        :ordering-policy ordering-policy :cap-fn :cap}))
              request {:amount amount :items items :rounding rounding
                       :ordering-policy ordering-policy :cap-treatment cap-treatment}]
          (is (= :passed (:status (v/verify-weighted-proportionality request claim)))
              "verifier independently reaches the allocator's result")
          (is (= :failed (:status (v/verify-weighted-proportionality
                                   request
                                   (update-in claim [:allocations 0 :allocated] inc))))
              "verifier rejects a deviating claimed result"))))))

(deftest verifier-covers-the-entire-admitted-policy-domain
  (testing "verifier matches the allocator across every admitted rounding x cap-treatment"
    (let [scenarios (for [rounding [:floor :floor-with-largest-remainder]
                          cap-treatment [:unallocated :redistribute]]
                      {:amount 7
                       :rounding rounding
                       :cap-treatment cap-treatment
                       :ordering-policy :input-order
                       :items [{:id :a :weight 4} {:id :b :weight 4} {:id :c :weight 2}]})]
      (doseq [{:keys [amount items rounding ordering-policy cap-treatment]} scenarios]
        (testing (str rounding " / " cap-treatment)
          (let [claim (if (= :redistribute cap-treatment)
                        (redistribution/allocate-pro-rata-with-redistribution
                         {:amount amount :items items :rounding rounding
                          :ordering-policy ordering-policy :cap-fn :cap})
                        (allocation/allocate-pro-rata
                         {:amount amount :items items :rounding rounding
                          :ordering-policy ordering-policy :cap-fn :cap}))
                request {:amount amount :items items :rounding rounding
                         :ordering-policy ordering-policy :cap-treatment cap-treatment}]
            (is (= :passed (:status (v/verify-weighted-proportionality request claim)))
                "verifier independently reaches the allocator for every admitted combination")))))))

(deftest redistribute-chain-is-reconstructed-independently
  (testing "independent cap-commitment chain reproduces the allocator's rounds"
    (let [request {:amount 14
                   :items [{:id :a :weight 4 :cap 3}
                           {:id :b :weight 3 :cap 3}
                           {:id :c :weight 3}]
                   :rounding :floor-with-largest-remainder
                   :ordering-policy :input-order
                   :cap-treatment :redistribute}
          chain (->> (get-in (v/reconstruct request) [:redistribution :passes])
                     (mapv :newly-capped-ids))
          claim (redistribution/allocate-pro-rata-with-redistribution
                 {:amount (:amount request) :items (:items request)
                  :rounding :floor-with-largest-remainder
                  :ordering-policy :input-order :cap-fn :cap})]
      (is (seq chain) "redistribution actually occurred")
      (is (= :passed (:status (v/verify-weighted-proportionality request claim)))
          "independent chain agrees with the allocator"))))

(deftest independent-adversarial-edge-cases
  (let [huge (reduce *' 1N (repeat 100 2))
        cases [{:id :zero-weights
                :request {:amount 11 :items [{:id :a :weight 0} {:id :b :weight 0}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :input-order :cap-treatment :unallocated}
                :allocated {:a 0 :b 0} :unmet {:a 0 :b 0} :remainder 11}
               {:id :canonical-equal-remainder-one-unit
                :request {:amount 1 :items [{:id :z :weight 1} {:id :a :weight 1}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :canonical-id :cap-treatment :unallocated}
                :allocated {:a 1 :z 0} :unmet {:a 0 :z 0} :remainder 0}
               {:id :zero-cap-redistributed
                :request {:amount 10 :items [{:id :a :weight 1 :cap 0} {:id :b :weight 1}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :input-order :cap-treatment :redistribute}
                :allocated {:a 0 :b 10} :unmet {:a 0 :b 0} :remainder 0
                :chain [[:a]]}
               {:id :all-capped-residual
                :request {:amount 10 :items [{:id :a :weight 1 :cap 2} {:id :b :weight 1 :cap 3}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :input-order :cap-treatment :redistribute}
                :allocated {:a 2 :b 3} :unmet {:a 3 :b 2} :remainder 0
                :chain [[:a :b]]}
               {:id :three-cap-commitment-rounds
                :request {:amount 100 :items [{:id :a :weight 80 :cap 70}
                                              {:id :b :weight 15 :cap 20}
                                              {:id :c :weight 4 :cap 7}
                                              {:id :d :weight 1}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :input-order :cap-treatment :redistribute}
                :allocated {:a 70 :b 20 :c 7 :d 3}
                :unmet {:a 0 :b 0 :c 0 :d 0} :remainder 0
                :chain [[:a] [:b] [:c]]}
               {:id :arbitrary-precision-amount
                :request {:amount huge :items [{:id :a :weight 1} {:id :b :weight 1}]
                          :rounding :floor-with-largest-remainder
                          :ordering-policy :input-order :cap-treatment :unallocated}
                :allocated {:a (quot huge 2) :b (quot huge 2)}
                :unmet {:a 0 :b 0} :remainder 0}]]
    (doseq [{:keys [id request allocated unmet remainder chain]} cases]
      (testing (name id)
        (let [actual (v/reconstruct request)
              actual-chain (->> (get-in actual [:redistribution :passes])
                                (map :newly-capped-ids)
                                (remove empty?)
                                vec)
              claim (claimed-from-reconstruction request)]
          (is (= allocated (into {} (map (fn [[id _]] [id (:allocated (get-in actual [:rows id]))]) allocated))))
          (is (= unmet (into {} (map (fn [[id _]] [id (:unmet (get-in actual [:rows id]))]) unmet))))
          (is (= remainder (:remainder actual)))
          (when chain (is (= chain actual-chain)))
          (is (= :passed (:status (v/verify-weighted-proportionality request claim)))))))))

(deftest normative-spec-and-edge-corpus-roots-are-stable
  (is (= "e0650cf27f9ba13f2c1799ad0ee09ba79214edd1b80a625b5e63f75a7bc5c7f4"
         (conf/fairness-spec-root)))
  (is (= "a6d723b27f154d6981cc41a7837ef7a9278ed3265eb98e4a480871845c5b97c1"
         (conf/conformance-corpus-root)))
  (doseq [{:keys [case/id request expected]} conf/adversarial-corpus]
    (testing (name id)
      (let [result (v/reconstruct request)
            rows (:rows result)]
        (is (= (:allocated expected)
               (into {} (map (fn [[k _]] [k (:allocated (get rows k))])
                             (:allocated expected)))))
        (is (= (:unmet expected)
               (into {} (map (fn [[k _]] [k (:unmet (get rows k))])
                             (:unmet expected)))))
        (is (= (:remainder expected) (:remainder result)))))))

(deftest exact-verification-input-root-matches-canonical-proof-profile-vector
  (let [request {:amount 7
                 :items [{:id :a :weight 4 :cap nil}
                         {:id :b :weight 4 :cap nil}
                         {:id :c :weight 2 :cap nil}]
                 :rounding :floor-with-largest-remainder
                 :ordering-policy :input-order
                 :cap-treatment :unallocated}
        result (v/reconstruct request)
        claim {:allocations (mapv (fn [row] (select-keys row [:id :allocated]))
                                  (vals (:rows result)))
               :total-allocated (:total-allocated result)
               :total-unmet (:total-unmet result)
               :remainder (:remainder result)}]
    (is (= "ee2401b91c694d66990b96114555485b5d8efc9944351c8a9339b64806b4339f"
           (conf/exact-verification-input-root request claim)))
    (is (= (conf/exact-verification-input-root request claim)
           (conf/exact-verification-input-root request
                                               (update claim :allocations reverse))))))
