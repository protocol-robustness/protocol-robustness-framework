(ns resolver-sim.allocation.realized-statement-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.context :as ctx]
            [resolver-sim.allocation.realized-statement :as rs]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.benchmark.packs.partial-fill.evidence :as pfev]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const golden-context-hash
  "PRF/native golden allocation-context-hash for the a-vs-b-plus-c scenario."
  "aec90fc6a813d8b3f28ca2c27573a70b5daf0c81964ae4121b12d6fa89555dd3")

(def raw-input
  {"allocation-id" "a-vs-b-plus-c"
   "kernel-version" "allocation-kernel.v1"
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "policy-a-vs-b-plus-c"
             "policy-hash" "0xabababababababababababababababababababababababababababababababab"
             "forbid-duplicate-owners" false}
   "claimants" [{"claim-id" "A" "economic-owner-id" "owner-A" "amount" "50" "weight" "50"}
                {"claim-id" "B" "economic-owner-id" "owner-B" "amount" "30" "weight" "30"}
                {"claim-id" "C" "economic-owner-id" "owner-C" "amount" "20" "weight" "20"}]
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

(def ^:private ctx (delay (ctx/build-context raw-input)))
(def ^:private lifecycle (delay (round-state/round-lifecycle {} :result-accepted)))

(def all-active-decision
  {:requested {:A 50 :B 30 :C 20}
   :filled {:A 50 :B 30 :C 20}
   :deferred {}
   :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder
            :fail-action-policy {:mode :pro-rata-treatment
                                 :deferred-policy :same-ratio
                                 :haircut-policy :same-ratio}}})

(def shortfall-decision
  {:requested {:A 50 :B 30 :C 20}
   :filled {:A 25 :B 15 :C 10}
   :deferred {:A 25 :B 15 :C 10}
   :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder
            :fail-action-policy {:mode :pro-rata-treatment
                                 :deferred-policy :same-ratio
                                 :haircut-policy :same-ratio}}})

(deftest statement-binds-genuine-allocation-context-root
  (testing "the statement's allocation-context-root matches the PRF/native golden hash"
    (let [s (rs/build-statement {:ctx @ctx :decision all-active-decision
                                 :round-lifecycle @lifecycle})]
      (is (= golden-context-hash (:allocation-context-root s)))
      (is (re-matches #"[0-9a-f]{64}" (:statement/root s))))))

(deftest statement-is-deterministic
  (testing "identical inputs produce identical statement roots"
    (let [s1 (rs/build-statement {:ctx @ctx :decision all-active-decision
                                  :round-lifecycle @lifecycle})
          s2 (rs/build-statement {:ctx @ctx :decision all-active-decision
                                  :round-lifecycle @lifecycle})]
      (is (= (:statement/root s1) (:statement/root s2)))
      (is (= s1 s2)))))

(deftest all-active-no-churn
  (testing "all-active allocation: every requested participant has :full-fill disposition and no fail-action rows"
    (let [s (rs/build-statement {:ctx @ctx :decision all-active-decision
                                 :round-lifecycle @lifecycle})
          rr (rs/realized-results-root all-active-decision)]
      (is (true? (:statement/all-active? s)))
      (is (= true (:all-active-all-full-fill (:statement/verification-equalities s))))
      (is (re-matches #"[0-9a-f]{64}" rr))))
  (testing "zero-valued deferred and haircut bookkeeping preserves all-active status"
    (let [decision (assoc all-active-decision
                          :deferred {:A 0 :B 0 :C 0}
                          :haircut {:A 0 :B 0 :C 0})
          s (rs/build-statement {:ctx @ctx :decision decision
                                 :round-lifecycle @lifecycle})]
      (is (true? (:statement/all-active? s)))
      (is (true? (get-in s [:statement/verification-equalities
                             :all-active-all-full-fill]))))))

(deftest realized-results-commits-explicit-dispositions
  (testing "realized-results-root commits a per-participant disposition vector;
            zero-filled participants are present, not silently dropped"
    (let [decision (assoc all-active-decision
                          :requested {:A 50 :B 30 :C 20}
                          :filled {:A 50 :B 30 :C 0}
                          :deferred {} :haircut {})
          rows (->> (keys (:requested decision)) sort)
          dispositions (map (fn [k]
                              (rs/disposition-of
                               {:requested (long (get-in decision [:requested k] 0))
                                :filled (long (get-in decision [:filled k] 0))
                                :deferred (long (get-in decision [:deferred k] 0))
                                :haircut (long (get-in decision [:haircut k] 0))
                                :unrealized (long (get-in decision [:unrealized k] 0))}))
                            rows)]
      (is (= [:full-fill :full-fill :zero-filled] (mapv #(if (= :zero-filled %) :zero-filled :full-fill) dispositions))
          "C is present with an explicit :zero-filled disposition, not omitted")
      (is (re-matches #"[0-9a-f]{64}" (rs/realized-results-root decision))))))

(deftest realized-results-preserves-large-integers-and-rejects-floats
  (let [large (bigint "922337203685477580812345")
        decision {:requested {:A large}
                  :filled {:A large}
                  :deferred {} :haircut {}}]
    (is (= :full-fill (rs/disposition-of {:requested large :filled large
                                          :deferred 0 :haircut 0})))
    (is (string? (rs/realized-results-root decision)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact integer"
                          (rs/realized-results-root
                           (assoc decision :filled {:A 1.5}))))))

(deftest shortfall-statement-distinct
  (testing "a shortfall decision with deferred fail-action produces a distinct statement"
    (let [sa (rs/build-statement {:ctx @ctx :decision all-active-decision
                                  :round-lifecycle @lifecycle})
          ss (rs/build-statement {:ctx @ctx :decision shortfall-decision
                                  :round-lifecycle @lifecycle})]
      (is (false? (:statement/all-active? ss)))
      (is (not= (:statement/root sa) (:statement/root ss)))
      (is (not= (:realized-results-root sa) (:realized-results-root ss))))))

(deftest fail-action-policy-root-committed
  (testing "the declared fail-action policy root is committed and stable"
    (let [s (rs/build-statement {:ctx @ctx :decision all-active-decision
                                 :round-lifecycle @lifecycle})]
      (is (re-matches #"[0-9a-f]{64}" (:fail-action-policy-root s)))
      (is (= (hc/hash-with-intent
              {:hash/intent :fail-action-policy}
              {:mode :pro-rata-treatment
               :deferred-policy :same-ratio
               :haircut-policy :same-ratio})
             (:fail-action-policy-root s))))))

(deftest default-fail-action-policy-root-stable
  (testing "a decision without a declared fail-action policy commits the conservative default"
    (let [decision (assoc-in all-active-decision [:policy :fail-action-policy] nil)
          s (rs/build-statement {:ctx @ctx :decision decision :round-lifecycle @lifecycle})
          default-root (hc/hash-with-intent
                        {:hash/intent :fail-action-policy}
                        {:mode :pro-rata-treatment
                         :deferred-policy :same-ratio
                         :haircut-policy :same-ratio})]
      (is (= default-root (:fail-action-policy-root s))))))

(deftest scenario-evidence-binding-links-statement
  (testing "scenario evidence binding commits the statement root and is deterministic"
    (let [s (rs/build-statement {:ctx @ctx :decision all-active-decision
                                 :round-lifecycle @lifecycle})
          e1 (rs/scenario-evidence-root s {:outcome :pass :evidence "x"})
          e2 (rs/scenario-evidence-root s {:outcome :pass :evidence "x"})]
      (is (= e1 e2))
      (is (re-matches #"[0-9a-f]{64}" e1)))))

;; ── Mutation locality: each mutation changes the expected dimension only ──

(defn- statement-for [& {:keys [ctx-override decision lifecycle-override]}]
  (rs/build-statement {:ctx (or ctx-override @ctx)
                       :decision (or decision all-active-decision)
                       :round-lifecycle (or lifecycle-override @lifecycle)}))

(defn- changed? [a b root-key]
  (not= (get a root-key) (get b root-key)))

(defn- unchanged? [a b root-key]
  (= (get a root-key) (get b root-key)))

(deftest mutation-fail-action-policy-is-localized
  (testing "fail-action policy mutation changes only fail-action-policy-root + statement-root"
    (let [base (statement-for)
          mutated (statement-for :decision
                                 (update-in all-active-decision
                                            [:policy :fail-action-policy :deferred-policy]
                                            (constantly :contractual)))]
      (is (changed? base mutated :fail-action-policy-root))
      (is (changed? base mutated :statement/root))
      (is (unchanged? base mutated :allocation-context-root))
      (is (unchanged? base mutated :request-set-root))
      (is (unchanged? base mutated :allocation-policy-root))
      (is (unchanged? base mutated :realized-results-root))
      (is (unchanged? base mutated :round-lifecycle-root)))))

(deftest mutation-realized-results-is-localized
  (testing "realized fill mutation changes only realized-results-root + statement-root"
    (let [base (statement-for)
          mutated (statement-for :decision
                                 (assoc all-active-decision
                                        :filled {:A 25 :B 15 :C 10}
                                        :deferred {:A 25 :B 15 :C 10}))]
      (is (changed? base mutated :realized-results-root))
      (is (changed? base mutated :statement/root))
      (is (unchanged? base mutated :allocation-context-root))
      (is (unchanged? base mutated :request-set-root))
      (is (unchanged? base mutated :allocation-policy-root))
      (is (unchanged? base mutated :fail-action-policy-root))
      (is (unchanged? base mutated :round-lifecycle-root)))))

(deftest mutation-lifecycle-is-localized
  (testing "lifecycle mutation changes only round-lifecycle-root + statement-root"
    (let [base (statement-for)
          mutated (statement-for :lifecycle-override
                                 (round-state/round-lifecycle {} :result-proposed))]
      (is (changed? base mutated :round-lifecycle-root))
      (is (changed? base mutated :statement/root))
      (is (unchanged? base mutated :allocation-context-root))
      (is (unchanged? base mutated :request-set-root))
      (is (unchanged? base mutated :realized-results-root))
      (is (unchanged? base mutated :fail-action-policy-root)))))

(deftest mutation-allocation-context-is-localized
  (testing "allocation-context mutation changes only allocation-context-root + statement-root"
    (let [base (statement-for)
          other-ctx (assoc @ctx :allocation/id "a-vs-b-plus-c-2")
          mutated (statement-for :ctx-override other-ctx)]
      (is (changed? base mutated :allocation-context-root))
      (is (changed? base mutated :statement/root))
      (is (unchanged? base mutated :request-set-root))
      (is (unchanged? base mutated :realized-results-root))
      (is (unchanged? base mutated :fail-action-policy-root))
      (is (unchanged? base mutated :round-lifecycle-root)))))

(deftest mutation-request-set-is-localized
  (testing "request-set membership mutation changes request-set-root + realized-results-root
            (the new participant gains an explicit disposition) and statement-root,
            but not the context/policy/lifecycle roots"
    (let [base (statement-for)
          mutated (statement-for :decision
                                 (update all-active-decision :requested
                                         (fn [r] (assoc r :D 10))))]
      (is (changed? base mutated :request-set-root))
      (is (changed? base mutated :realized-results-root)
          "a new requested participant must appear with an explicit disposition")
      (is (changed? base mutated :statement/root))
      (is (unchanged? base mutated :allocation-context-root))
      (is (unchanged? base mutated :allocation-policy-root))
      (is (unchanged? base mutated :fail-action-policy-root))
      (is (unchanged? base mutated :round-lifecycle-root)))))

(deftest schema-version-is-realized-allocation-statement-v1
  (is (= "realized-allocation-statement.v1" rs/schema-version)))

(deftest producer-is-reachable-with-context-and-decision
  (testing "the benchmark producer emits one committed statement per decision
            when context + lifecycle + decisions coexist"
    (let [world {:allocation/context @ctx
                 :allocation/round-lifecycle @lifecycle
                 :yield/partial-fill-decisions
                 {(:decision/id all-active-decision) all-active-decision}}
          produced (pfev/realized-allocation-statements world)]
      (is (= 1 (count (:statements produced))))
      (is (re-matches #"[0-9a-f]{64}" (:statements-root produced)))
      (is (every? #(re-matches #"[0-9a-f]{64}" %)
                  (map :statement/root (:statements produced)))))))

(deftest producer-is-fail-closed-without-context
  (testing "the producer returns nil when no allocation context is present, so
            absence can never be mistaken for a proven statement"
    (let [world {:yield/partial-fill-decisions
                 {(:decision/id all-active-decision) all-active-decision}}]
      (is (nil? (pfev/realized-allocation-statements world))))))

(deftest producer-is-fail-closed-without-decisions
  (testing "the producer returns nil when no partial-fill decisions are present"
    (let [world {:allocation/context @ctx
                 :allocation/round-lifecycle @lifecycle}]
      (is (nil? (pfev/realized-allocation-statements world))))))
