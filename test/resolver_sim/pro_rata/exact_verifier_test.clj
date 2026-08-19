(ns resolver-sim.pro-rata.exact-verifier-test
  "SP-B: independent exact pro-rata verification.

   Core invariant under test: the exact verifier must NOT replay the allocator.
   It reconstructs the mathematically expected allocation from the canonical
   request via its own decomposition (single-pass -> redistribution chain ->
   fixed point) and only then compares the claimed result."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.pro-rata.exact-verifier :as v]))

(def ^:const locked-corpus-identity
  "Frozen identity over corpus + spec version. Update deliberately and only
   alongside a reviewed math change."
  "94200977d363128af4cb7d5cc86c54c8936cbbba71914a79a5ee5e6bb3505324")

;; ---------------------------------------------------------------------------
;; independence
;; ---------------------------------------------------------------------------

(deftest verifier-is-independent-of-the-allocator
  (testing "exact-verifier requires no allocator namespace (payoffs/allocation)"
    (let [aliases (set (keys (ns-aliases (the-ns 'resolver-sim.pro-rata.exact-verifier))))]
      (is (empty? aliases))
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
                      (payoffs/allocate-pro-rata-with-redistribution
                       {:amount amount :items items :rounding rounding
                        :ordering-policy ordering-policy :cap-fn :cap})
                      (payoffs/allocate-pro-rata
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
                        (payoffs/allocate-pro-rata-with-redistribution
                         {:amount amount :items items :rounding rounding
                          :ordering-policy ordering-policy :cap-fn :cap})
                        (payoffs/allocate-pro-rata
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
          claim (payoffs/allocate-pro-rata-with-redistribution
                 {:amount (:amount request) :items (:items request)
                  :rounding :floor-with-largest-remainder
                  :ordering-policy :input-order :cap-fn :cap})]
      (is (seq chain) "redistribution actually occurred")
      (is (= :passed (:status (v/verify-weighted-proportionality request claim)))
          "independent chain agrees with the allocator"))))