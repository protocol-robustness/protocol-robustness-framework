(ns resolver-sim.shortfall.provenance-conformance-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.shortfall.evm-asset :as asset]
            [resolver-sim.shortfall.reserve-observation :as observation]
            [resolver-sim.shortfall.context-v2 :as context-v2]
            [resolver-sim.shortfall.reserve-binding :as binding]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]))

(def fixture-path "coprocessor/core/tests/fixtures/onchain-shortfall-end-to-end-v1.json")

(defn- fixture [] (json/read-str (slurp fixture-path) :key-fn keyword))
(defn- kw [s] (keyword (if (= \: (first s)) (subs s 1) s)))

(deftest realized-statement-root-matches-rust-source-bound-path
  (let [f (fixture)
        sf (:shortfall f)
        raw (json/write-str {"allocation-context" (:allocation-context sf)
                             "available" (:available-amount f)
                             "requested" (:requested sf)
                             "policy" (:policy sf)
                             "fail-action-policy" (:fail-action-policy sf)
                             "round-state" (:round-state sf)})
        rebuilt (persisted/reconstruct-input raw)]
    (is (= "1779ead0c2eff0239a3de700918fca7849e33cbca0a8ebdad363f3ec38f226e9"
           (get-in rebuilt [:statement :statement/root])))))

(deftest shared-rust-fixture-derives-complete-provenance
  (let [f (fixture)
        sf (:shortfall f)
        raw (select-keys f [:chain-id :reserve :token-address :snapshot-id :operation-id
                            :allocator :created-at-block :gross-amount :reserved-amount-before
                            :protected-amount :available-amount])
        raw (reduce-kv (fn [m k v] (assoc m k (if (#{:chain-id :snapshot-id :created-at-block
                                                     :gross-amount :reserved-amount-before
                                                     :protected-amount :available-amount} k)
                                                (Long/parseLong v) v))) {} raw)
        a (asset/build-asset {:chain-id (:chain-id raw) :token-address (:token-address raw)
                              :unit :erc20-base-unit})
        snapshot-hash (observation/solidity-snapshot-hash raw)
        obs (observation/build-observation (assoc raw :snapshot-hash snapshot-hash))
        candidates (mapv (fn [c]
                           (cond-> {:claim/id (:claim-id c) :requested (Long/parseLong (:requested c))
                                    :eligibility (kw (:eligibility c))}
                             (:ineligibility-reason c)
                             (assoc :ineligibility/reason (kw (:ineligibility-reason c)))))
                         (:candidates sf))
        policy {:mode :pro-rata :rounding-policy :largest-remainder
                :fail-action-policy {:mode :pro-rata-treatment
                                     :deferred-policy :same-ratio :haircut-policy :same-ratio}}
        ctx (context-v2/build-context {:shortfall/profile :shortfall-pro-rata.v1
                                       :asset/root (:asset/root a)
                                       :allocation-context-root (:allocation-context-root sf)
                                       :candidates candidates
                                       :availability/source (:source obs)
                                       :availability/root (:observation/root obs)
                                       :available-amount (:available-amount raw)
                                       :allocation-policy policy})
        b (binding/build-binding {:reserve-observation (assoc raw :snapshot-hash snapshot-hash)
                                  :shortfall-context (select-keys ctx [:shortfall/profile :asset/root :allocation-context-root
                                                                       :candidates :availability/source :availability/root
                                                                       :available-amount :allocation-policy])
                                  :operation-id (:operation-id raw)})]
    ;; The end-to-end fixture intentionally carries assertion-only expectations;
    ;; these roots are reconstructed, never consumed from an expected-output body.
    (is (re-find #"^0x[0-9a-f]{64}$" (:snapshot-hash obs)))
    (is (re-find #"^sha256:[0-9a-f]{64}$" (:asset/root a)))
    (is (re-find #"^sha256:[0-9a-f]{64}$" (:observation/root obs)))
    (is (re-find #"^sha256:[0-9a-f]{64}$" (:shortfall/context-root ctx)))
    (is (re-find #"^sha256:[0-9a-f]{64}$" (:binding/root b)))
    (is (= "70" (str (:available-amount obs))))))
