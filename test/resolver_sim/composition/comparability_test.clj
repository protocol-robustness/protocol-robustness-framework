(ns resolver-sim.composition.comparability-test
  "Comparability: distinct from composability; derived from committed
   contracts; exact/compatible/partial/incomparable/unknown classes."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.composition.comparability :as cp]
            [resolver-sim.composition.fixtures :as fx]))

(defn- side
  [cap & [root]]
  {:capability cap :capability-root (or root (str "root-" (name (:capability/id cap))))})

(deftest exact-alternatives
  (let [a (side (fx/cap :fixture/a) "r1")
        b (side (fx/cap :fixture/a) "r1")
        res (cp/compare-capabilities a b)]
    (is (= :exact (:comparability/class res)))
    (is (= "r1" (:shared-contract-root res)))))

(deftest compatible-alternatives
  (let [a (side (fx/cap :fixture/a))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b {:normalization-root "norm"})]
    (is (= :compatible (:comparability/class res)))
    (is (= "norm" (:normalization-root res)))))

(deftest partial-over-committed-projection
  (let [a (side (fx/cap :fixture/a :effects #{:prf.effect/x.v1}))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b)]
    (is (= :partial (:comparability/class res)))
    (is (some? (:shared-contract-root res))
        "partial comparability identifies the shared projection")))

(deftest incomparable-domains
  (let [a (side (fx/cap :fixture/a :output-semantic :gross))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b)]
    (is (= :incomparable (:comparability/class res)))))

(deftest unknown-when-contract-missing
  (let [a (side (dissoc (fx/cap :fixture/a) :composition-contract))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b)]
    (is (= :unknown (:comparability/class res)))
    (is (not= :evaluated (:comparability/status res))
        "malformed comparison evidence is non-passing")))

(deftest symmetric-classification
  (let [a (side (fx/cap :fixture/a :effects #{:x}))
        b (side (fx/cap :fixture/b))
        fwd (cp/compare-capabilities a b)
        rev (cp/compare-capabilities b a)]
    (is (= (:comparability/class fwd) (:comparability/class rev)))))

(deftest exact-comparability-is-transitive
  (let [a (side (fx/cap :fixture/a) "r")
        b (side (fx/cap :fixture/a) "r")
        c (side (fx/cap :fixture/a) "r")]
    (is (= :exact (:comparability/class (cp/compare-capabilities a b))))
    (is (= :exact (:comparability/class (cp/compare-capabilities b c))))
    (is (= :exact (:comparability/class (cp/compare-capabilities a c))))))

(deftest partial-never-upgrades-to-exact
  (let [a (side (fx/cap :fixture/a :effects #{:x}))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b)]
    (is (= :partial (:comparability/class res)))
    (is (not= :exact (:comparability/class res)))))

(deftest classification-changes-when-commitment-changes
  (let [a (side (fx/cap :fixture/a))
        b (side (fx/cap :fixture/b))]
    (is (= :compatible (:comparability/class (cp/compare-capabilities a b))))
    (is (= :incomparable
           (:comparability/class
            (cp/compare-capabilities
             (side (fx/cap :fixture/a :output-semantic :gross))
             b))))
    (is (= :partial
           (:comparability/class
            (cp/compare-capabilities
             (side (fx/cap :fixture/a :effects #{:x}))
             b))))))

(deftest compare-plans
  (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
           :plan/effect-merge-strategy :accumulate}
        same {:plan/root "r" :plan/output-contract {:semantic-type :amount}
              :plan/effect-merge-strategy :accumulate}
        other {:plan/root "s" :plan/output-contract {:semantic-type :amount}
               :plan/effect-merge-strategy :accumulate}
        diff {:plan/root "t" :plan/output-contract {:semantic-type :gross}
              :plan/effect-merge-strategy :accumulate}]
    (is (= :exact (:comparability/class (cp/compare-plans p same))))
    (is (= :compatible (:comparability/class (cp/compare-plans p other))))
    (is (= :incomparable (:comparability/class (cp/compare-plans p diff))))))
