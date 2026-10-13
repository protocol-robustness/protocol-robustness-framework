(ns resolver-sim.shortfall.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.context :as allocation-context]
            [resolver-sim.shortfall.context :as shortfall]))

(def raw-allocation-context
  {"allocation-id" "shortfall-v1-fixture"
   "kernel-version" "allocation-kernel.v1"
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "shortfall-policy"
             "policy-hash" "0xabababababababababababababababababababababababababababababababab"
             "forbid-duplicate-owners" false}
   "claimants" [{"claim-id" "A" "economic-owner-id" "owner-a" "amount" "50" "weight" "50"}
                {"claim-id" "B" "economic-owner-id" "owner-b" "amount" "30" "weight" "30"}
                {"claim-id" "C" "economic-owner-id" "owner-c" "amount" "20" "weight" "20"}]
   "outcomes" [{"outcome-id" "O1" "allocations" [{"claim-id" "A" "allocated" "50"}
                                                 {"claim-id" "B" "allocated" "0"}
                                                 {"claim-id" "C" "allocated" "0"}]}
               {"outcome-id" "O2" "allocations" [{"claim-id" "A" "allocated" "0"}
                                                 {"claim-id" "B" "allocated" "30"}
                                                 {"claim-id" "C" "allocated" "20"}]}]
   "proposed-rates" [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
                     {"outcome-id" "O2" "numerator" "1" "denominator" "2"}]
   "capacity" "50"
   "total-eligible-weight" "100"
   "exact-pro-rata-denominator" "100"
   "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"})

(def ctx (allocation-context/build-context raw-allocation-context))
(def context-root (str "sha256:" (allocation-context/context-hash ctx)))
(def policy {:mode :pro-rata
             :rounding-policy :largest-remainder
             :fail-action-policy {:mode :pro-rata-treatment
                                  :deferred-policy :same-ratio
                                  :haircut-policy :same-ratio}})

(defn shortfall-input [available]
  {:shortfall/profile :shortfall-pro-rata.v1
   :asset/id "USDC"
   :asset/unit :erc20-base-unit
   :allocation-context-root context-root
   :candidates [{:claim/id "A" :requested 50 :eligibility :eligible}
                {:claim/id "B" :requested 30 :eligibility :ineligible
                 :ineligibility/reason :shortfall/not-eligible}
                {:claim/id "C" :requested 20 :eligibility :eligible}]
   :availability/source :pool-availability.v2
   :availability/root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
   :available-amount available
   :allocation-policy policy})

(deftest scarcity-is-canonical-and-hands-off-to-existing-realization
  (let [context (shortfall/build-context (shortfall-input 49))
        result (shortfall/realize context ctx)
        decision (:allocation/decision result)
        statement (:realized-allocation-statement result)]
    (is (= {"A" 50 "C" 20} (shortfall/eligible-requested context)))
    (is (= 70 (shortfall/total-eligible-requested context)))
    (is (= 21 (shortfall/shortfall-amount context)))
    (is (= 49 (reduce + 0 (vals (:filled decision)))))
    (is (= 49 (reduce + 0 (vals (:filled decision)))) "scarcity fills all available capital")
    (is (= 21 (:shortfall/unfilled-demand result)))
    (is (= 35 (get-in decision [:filled "A"])))
    (is (string? (:statement/root statement)))
    (is (= 64 (count (:statement/root statement))))))

(deftest equality-is-deterministic-and-has-no-unfilled-demand
  (let [context (shortfall/build-context (shortfall-input 70))
        result (shortfall/realize context ctx)]
    (is (zero? (shortfall/shortfall-amount context)))
    (is (= {"A" 50 "C" 20} (get-in result [:allocation/decision :filled])))
    (is (zero? (:shortfall/unfilled-demand result)))
    (is (:statement/all-active? (:realized-allocation-statement result)))))

(deftest excess-capital-is-explicitly-not-an-allocator-v1-handoff
  (let [context (shortfall/build-context (shortfall-input 71))]
    (is (zero? (shortfall/shortfall-amount context)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"excess capital"
                          (shortfall/allocation-input context ctx)))))

(deftest ineligible-request-is-committed-but-excluded
  (let [context (shortfall/build-context (shortfall-input 49))]
    (is (= ["A" "B" "C"] (mapv :claim/id (:candidates context))))
    (is (= :ineligible (get-in context [:candidates 1 :eligibility])))
    (is (= {"A" 50 "C" 20} (shortfall/eligible-requested context)))
    (is (not (contains? (shortfall/eligible-requested context) "B")))))

(deftest candidate-universe-and-availability-tampering-are-visible
  (let [context (shortfall/build-context (shortfall-input 49))
        omitted (assoc context :candidates (pop (:candidates context)))
        changed (shortfall/build-context (shortfall-input 48))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"candidate universe"
                          (shortfall/allocation-input omitted ctx)))
    (is (not= (:shortfall/context-root context) (:shortfall/context-root changed)))
    (is (not= (shortfall/shortfall-amount context) (shortfall/shortfall-amount changed)))))

(deftest context-rejects-noncanonical-or-ambiguous-eligibility
  (testing "missing candidates cannot be represented as silent omissions"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical claim-id order"
                          (shortfall/build-context
                           (assoc (shortfall-input 49)
                                  :candidates (vec (reverse (:candidates (shortfall-input 49)))))))))
  (testing "ineligible candidates require an explicit namespaced reason"
    (is (thrown? clojure.lang.ExceptionInfo
                 (shortfall/build-context
                  (assoc-in (shortfall-input 49) [:candidates 1 :ineligibility/reason] :unqualified))))))
