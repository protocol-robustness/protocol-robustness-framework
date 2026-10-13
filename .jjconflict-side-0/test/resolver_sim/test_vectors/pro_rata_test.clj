(ns resolver-sim.test-vectors.pro-rata-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.protocols.sew.test-vectors.slash-allocation :as sew-slash]
            [resolver-sim.test-vectors.pro-rata :as sut]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(defn- parse-amount
  [x]
  (bigint x))

(defn- get-in-str
  [m ks]
  (get-in m (mapv name ks)))

(defn- liquidity-vector?
  [v]
  (= "liquidity-fulfillment" (:domain v)))

(defn- slash-vector?
  [v]
  (= "slash-allocation" (:domain v)))

(defn- assert-liquidity-invariants
  [v]
  (let [out (:expected-output v)
        total-requested (parse-amount (:total-requested out))
        total-fulfilled (parse-amount (:total-fulfilled out))
        total-unmet (parse-amount (:total-unmet out))
        available (parse-amount (:available-liquidity out))]
    (is (= total-requested (+ total-fulfilled total-unmet)))
    (is (<= total-fulfilled available))
    (doseq [bucket (:bucket-results out)]
      (let [claim (parse-amount (:claim-amount bucket))
            fulfilled (parse-amount (:fulfilled-amount bucket))
            unmet (parse-amount (:unmet-amount bucket))]
        (is (<= 0 fulfilled claim))
        (is (<= 0 unmet))))))

(defn- assert-slash-invariants
  [v]
  (let [out (:expected-output v)
        total-obligation (parse-amount (:total-obligation out))
        total-debited (parse-amount (:total-debited out))
        total-unmet (parse-amount (:total-unmet out))
        remainder (parse-amount (:remainder out))]
    (is (= total-obligation (+ total-debited total-unmet remainder)))
    (doseq [party (:liability-per-party out)]
      (let [weight (parse-amount (:weight party))
            debited (parse-amount (:debited party))
            unmet (parse-amount (:unmet party))
            cap (:cap party)]
        (is (<= 0 debited))
        (is (<= 0 unmet))
        (when cap
          (is (<= debited (parse-amount cap))))
        (when (zero? weight)
          (is (zero? debited))
          (is (zero? unmet)))))))

(deftest emitters-are-pure-wrappers-around-current-implementations
  (testing "liquidity vector expected output reflects calculate-fulfillment-pro-rata"
    (let [spec {:vector-id "wrapper-liquidity"
                :description "wrapper check"
                :available-liquidity 500
                :requested {:principal 700 :realized-yield 300}}
          policy (merge partial-fill/default-partial-fill-policy
                        {:mode :pro-rata :rounding-policy :largest-remainder})
          expected (partial-fill/calculate-fulfillment-pro-rata 500 (:requested spec) policy)
          emitted (sut/emit-liquidity-fulfillment-vector spec)]
      (is (= expected (get-in emitted [:expected-output :reference-output])))))
  (testing "slash vector expected output reflects calculate-sew-slash-allocation"
    (let [spec {:vector-id "wrapper-slash"
                :description "wrapper check"
                :slash-obligation 400
                :liable-parties [{:id :alice :slashable-stake 1000 :available-slashable 1000}
                                 {:id :bob :slashable-stake 500 :available-slashable 60}
                                 {:id :carol :slashable-stake 500 :available-slashable 500}]}
          expected (sew-economics/calculate-sew-slash-allocation
                    {:slash-obligation 400
                     :liable-parties (:liable-parties spec)
                     :basis :slashable-stake
                     :cap-field :available-slashable})
          emitted (sew-slash/emit-slash-allocation-vector spec)]
      (is (= expected (get-in emitted [:expected-output :reference-output]))))))

(deftest emitted-vectors-have-schema-and-satisfy-invariants
  (doseq [v (sut/golden-vectors)]
    (testing (:vector-id v)
      (is (contains? v :schema-version))
      (is (seq (:invariants v)))
      (is (seq (:canonical-input-hash v)))
      (is (seq (:canonical-expected-output-hash v)))
      (is (seq (:full-vector-hash v)))
      (cond
        (liquidity-vector? v)
        (do (is (= sut/liquidity-schema-version (:schema-version v)))
            (assert-liquidity-invariants v))

        (slash-vector? v)
        (do (is (= sew-slash/slash-schema-version (:schema-version v)))
            (assert-slash-invariants v))

        :else
        (is false (str "unknown vector domain " (:domain v)))))))

(deftest deterministic-json-and-hashes
  (let [v (first (sut/golden-vectors))
        json-1 (sut/canonical-json v)
        json-2 (sut/canonical-json v)]
    (is (= json-1 json-2))
    (is (= (:canonical-input-hash v) (sut/canonical-hash (:input v))))
    (is (= (:canonical-expected-output-hash v)
           (sut/canonical-hash (:expected-output v))))
    (is (= (:full-vector-hash v)
           (sut/canonical-hash (dissoc v :full-vector-hash))))))

(deftest json-output-round-trips-cleanly
  (doseq [v (sut/golden-vectors)]
    (let [json (sut/canonical-json v)
          parsed (sut/read-json json)]
      (is (= (:schema-version v) (get parsed "schema-version")))
      (is (= (:vector-id v) (get parsed "vector-id")))
      (is (= (:domain v) (get parsed "domain")))
      (is (= (:full-vector-hash v) (get parsed "full-vector-hash")))
      (is (= "amount-like and weight-like fields are decimal strings; no floats"
             (get-in-str parsed [:units :json-numeric-representation]))))))

(deftest committed-golden-fixtures-match-emitters
  (doseq [v (sut/golden-vectors)]
    (let [path (str "resources/test-vectors/pro-rata/" (sut/vector-filename v))
          file (io/file path)]
      (testing path
        (is (.exists file))
        (when (.exists file)
          (is (= (str (sut/canonical-json v) "\n") (slurp file))))))))
