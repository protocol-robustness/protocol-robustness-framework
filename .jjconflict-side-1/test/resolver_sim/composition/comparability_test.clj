(ns resolver-sim.composition.comparability-test
  "Comparability: distinct from composability; derived from committed
   contracts; exact/compatible-normalized/compatible-direct/partial/
   incomparable/unknown classes."
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest compatible-normalized-alternatives
  (let [a (side (fx/cap :fixture/a))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b {:normalization-root "norm"})]
    (is (= :compatible-normalized (:comparability/class res)))
    (is (= "norm" (:normalization-root res)))))

(deftest compatible-direct-when-no-normalization
  (let [a (side (fx/cap :fixture/a))
        b (side (fx/cap :fixture/b))
        res (cp/compare-capabilities a b)]
    (is (= :compatible-direct (:comparability/class res)))
    (is (some? (:shared-contract-root res)))
    (is (nil? (:normalization-root res)))))

(deftest normalization-not-ceremonial
  (testing "an explicit normalization-root selects :compatible-normalized;
            its absence selects :compatible-direct — never the same class"
    (let [a (side (fx/cap :fixture/a))
          b (side (fx/cap :fixture/b))]
      (is (= :compatible-direct
             (:comparability/class (cp/compare-capabilities a b))))
      (is (= :compatible-normalized
             (:comparability/class
              (cp/compare-capabilities a b {:normalization-root "norm"})))))))

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
    (is (= :unknown (:comparability/status res))
        "malformed comparison evidence is non-passing")))

(defn- with-roles
  [cap roles]
  (update cap :composition-contract
          assoc-in [:composition/roles] roles))

(deftest role-conflict-is-incomparable
  (testing "roles are a gating dimension: a declared role mismatch is a
            demonstrated conflict, not a compatible class"
    (let [a (side (fx/cap :fixture/a))
          b (side (with-roles (fx/cap :fixture/b) #{:writer}))
          res (cp/compare-capabilities a b)]
      (is (= :incomparable (:comparability/class res)))
      (is (= "role dimension conflict" (:reason res))))))

(deftest role-unevaluable-is-unknown
  (testing "non-exact comparison with roles not evaluable on both sides
            (empty role sets) is :unknown, not a successful class"
    (let [a (side (with-roles (fx/cap :fixture/a) #{}))
          b (side (with-roles (fx/cap :fixture/b) #{}))
          res (cp/compare-capabilities a b)]
      (is (= :unknown (:comparability/class res)))
      (is (= :unknown (:comparability/status res))))))

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
    (is (= :compatible-direct (:comparability/class (cp/compare-capabilities a b))))
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
    (is (= :compatible-direct (:comparability/class (cp/compare-plans p other))))
    (is (= :incomparable (:comparability/class (cp/compare-plans p diff))))))

(deftest compare-plans-fails-closed-on-missing-left-field
  (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
           :plan/effect-merge-strategy :accumulate}
        no-contract (-> (dissoc p :plan/output-contract)
                        (assoc :plan/root "s"))
        no-merge (-> (dissoc p :plan/effect-merge-strategy)
                     (assoc :plan/root "s"))
        res (cp/compare-plans p no-contract)
        res2 (cp/compare-plans no-merge p)]
    (is (= :unknown (:comparability/class res)))
    (is (= :unknown (:comparability/status res)))
    (is (= :unknown (:comparability/class res2)))))

(deftest compare-plans-fails-closed-on-missing-right-field
  (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
           :plan/effect-merge-strategy :accumulate}
        no-merge (-> (dissoc p :plan/effect-merge-strategy)
                     (assoc :plan/root "s"))
        res (cp/compare-plans p no-merge)]
    (is (= :unknown (:comparability/class res)))
    (is (= :unknown (:comparability/status res)))))

(deftest compare-plans-fails-closed-on-malformed-contract
  (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
           :plan/effect-merge-strategy :accumulate}
        bad-contract {:plan/root "s" :plan/output-contract "not-a-map"
                      :plan/effect-merge-strategy :accumulate}
        empty-contract {:plan/root "t" :plan/output-contract {}
                        :plan/effect-merge-strategy :accumulate}
        res (cp/compare-plans p bad-contract)
        res2 (cp/compare-plans p empty-contract)]
    (is (= :unknown (:comparability/class res)))
    (is (= :unknown (:comparability/class res2)))))

(deftest compare-plans-exact-wins-over-missing-supplied-fields
  (testing "identical committed plan roots are :exact even when the supplied
            map omits evidence fields — the root is the committed identity"
    (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
             :plan/effect-merge-strategy :accumulate}
          stripped (dissoc p :plan/output-contract :plan/effect-merge-strategy)]
      (is (= :exact (:comparability/class (cp/compare-plans p stripped)))))))

(deftest compare-plans-fails-closed-on-malformed-plan
  (let [p {:plan/root "r" :plan/output-contract {:semantic-type :amount}
           :plan/effect-merge-strategy :accumulate}
        res (cp/compare-plans p "not-a-plan")]
    (is (= :unknown (:comparability/class res)))
    (is (= :unknown (:comparability/status res)))))

;; ── registry bridge (register / register-capability) ───────────────────────

(deftest compare-entries-committed-sides
  (testing "entries built through register-capability compare on committed roots"
    (let [emap (fx/ext-map-with (fx/cap :fixture/a) (fx/cap :fixture/b))
          entry-a (get emap [:economics/award-amount :fixture/a])
          entry-b (get emap [:economics/award-amount :fixture/b])]
      (is (= :exact (:comparability/class (cp/compare-entries entry-a entry-a)))
          "the same committed entry is exactly comparable to itself")
      (is (= :compatible-direct
             (:comparability/class (cp/compare-entries entry-a entry-b)))
          "distinct registered capabilities compare on their committed surfaces")
      (is (= (:left/root (cp/compare-entries entry-a entry-a))
             (:descriptor-root entry-a))
          "the side root is the registry-committed descriptor root"))))

(deftest compare-registered-by-key
  (let [emap (fx/ext-map-with (fx/cap :fixture/a) (fx/cap :fixture/b))
        res (cp/compare-registered emap
                                   [:economics/award-amount :fixture/a]
                                   [:economics/award-amount :fixture/b])]
    (is (= :compatible-direct (:comparability/class res)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cp/compare-registered emap
                                        [:economics/award-amount :fixture/missing]
                                        [:economics/award-amount :fixture/b]))
        "an unresolvable key fails closed")))

(deftest side-from-entry-fails-closed-on-forged-root
  (testing "a registered entry with a mismatched committed root is rejected"
    (let [forged {:capability (fx/cap :fixture/a)
                  :descriptor-root "sha256:forged"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"inconsistent"
           (cp/side-from-entry forged)))
      (let [e (try (cp/side-from-entry forged)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :comparability/error-inconsistent-entry (:error (ex-data e))))))))
