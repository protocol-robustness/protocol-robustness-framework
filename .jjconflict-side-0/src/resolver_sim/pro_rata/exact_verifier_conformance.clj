(ns resolver-sim.pro-rata.exact-verifier-conformance
  "Normative pro-rata exact-verifier profile and independent edge corpus roots."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const fairness-spec-domain-tag "PRF_PRO_RATA_FAIRNESS_SPEC_V1")
(def ^:const conformance-corpus-domain-tag "PRF_PRO_RATA_CONFORMANCE_CORPUS_V1")
(def ^:const exact-verification-input-domain-tag
  "PRF_PRO_RATA_EXACT_VERIFICATION_INPUT_V1")

(def fairness-spec
  {:spec/id "pro-rata-exact-verification.v1"
   :numeric-domain :non-negative-arbitrary-precision-integer
   :rounding/policies [:floor :floor-with-largest-remainder]
   :ordering/policies [:input-order :canonical-id]
   :ordering/canonical-key :canonical-encoded-id
   :cap-treatment/policies [:unallocated :redistribute]
   :redistribute/constrained-condition
   :remaining-times-weight-gte-cap-times-active-weight-total
   :redistribute/iteration :commit-all-constrained-rows-then-repeat-to-fixed-point
   :all-zero-weight/outcome :zero-awards-preserve-amount-as-remainder
   :committed-residual/outcome :largest-remainder-unmet-by-selected-order
   :claim/comparison-fields [:allocated-by-id :total-allocated :total-unmet :remainder]})

(def ^:private huge-amount
  (reduce *' 1N (repeat 100 2)))

(def adversarial-corpus
  "Hand-derived edge cases supplemental to the frozen seven-case V1 corpus."
  [{:case/id :zero-weights
    :request {:amount 11 :items [{:id :a :weight 0 :cap nil}
                                 {:id :b :weight 0 :cap nil}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order :cap-treatment :unallocated}
    :expected {:allocated {:a 0 :b 0} :unmet {:a 0 :b 0}
               :remainder 11 :passes []}}
   {:case/id :canonical-equal-remainder-one-unit
    :request {:amount 1 :items [{:id :z :weight 1 :cap nil}
                                {:id :a :weight 1 :cap nil}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :canonical-id :cap-treatment :unallocated}
    :expected {:allocated {:a 1 :z 0} :unmet {:a 0 :z 0}
               :remainder 0 :passes []}}
   {:case/id :zero-cap-redistributed
    :request {:amount 10 :items [{:id :a :weight 1 :cap 0}
                                 {:id :b :weight 1 :cap nil}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order :cap-treatment :redistribute}
    :expected {:allocated {:a 0 :b 10} :unmet {:a 0 :b 0}
               :remainder 0 :passes [[:a]]}}
   {:case/id :all-capped-residual
    :request {:amount 10 :items [{:id :a :weight 1 :cap 2}
                                 {:id :b :weight 1 :cap 3}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order :cap-treatment :redistribute}
    :expected {:allocated {:a 2 :b 3} :unmet {:a 3 :b 2}
               :remainder 0 :passes [[:a :b]]}}
   {:case/id :three-cap-commitment-rounds
    :request {:amount 100 :items [{:id :a :weight 80 :cap 70}
                                  {:id :b :weight 15 :cap 20}
                                  {:id :c :weight 4 :cap 7}
                                  {:id :d :weight 1 :cap nil}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order :cap-treatment :redistribute}
    :expected {:allocated {:a 70 :b 20 :c 7 :d 3}
               :unmet {:a 0 :b 0 :c 0 :d 0} :remainder 0
               :passes [[:a] [:b] [:c]]}}
   {:case/id :arbitrary-precision-amount
    :request {:amount huge-amount :items [{:id :a :weight 1 :cap nil}
                                          {:id :b :weight 1 :cap nil}]
              :rounding :floor-with-largest-remainder
              :ordering-policy :input-order :cap-treatment :unallocated}
    :expected {:allocated {:a (quot huge-amount 2) :b (quot huge-amount 2)}
               :unmet {:a 0 :b 0} :remainder 0 :passes []}}])

(defn fairness-spec-root []
  (hc/domain-hash fairness-spec-domain-tag fairness-spec))

(defn conformance-corpus-root []
  (hc/domain-hash conformance-corpus-domain-tag adversarial-corpus))

(defn exact-verification-input-root
  "Commit the exact-verifier request and claimed allocations for a proof.

   Claim rows are sorted by canonical ID encoding because verification compares
   them by ID. Request item order is retained because input-order policy uses it."
  [request claim]
  (let [request (-> request
                    (select-keys [:amount :items :rounding :ordering-policy :cap-treatment])
                    (update :items
                            (fn [items]
                              (mapv (fn [item]
                                      (assoc (select-keys item [:id :weight :cap])
                                             :cap (:cap item)))
                                    items))))
        claim (-> claim
                  (update :allocations
                          (fn [allocations]
                            (->> allocations
                                 (map #(select-keys % [:id :allocated]))
                                 (sort-by (comp hc/canonical-bytes-hex :id))
                                 vec)))
                  (select-keys [:allocations :total-allocated :total-unmet :remainder]))]
    (hc/domain-hash exact-verification-input-domain-tag
                    {:request request :claim claim})))
