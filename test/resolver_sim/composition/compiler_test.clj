(ns resolver-sim.composition.compiler-test
  "Combination representation and the sequential composition compiler:
   valid pipelines, structured rejections, determinism, and root mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.combination :as combo]
            [resolver-sim.composition.compiler :as comp]
            [resolver-sim.composition.fixtures :as fx]))

(defn- compile-it
  [emap combination]
  (comp/compile-combination emap combination))

(defn- rejects
  [emap combination violation-id]
  (let [{:keys [status violations]} (compile-it emap combination)]
    (and (= :invalid status)
         (some #(= violation-id (:violation/id %)) violations))))

(defn- two-stage
  ([_emap]
   (fx/seq-combination
    (fx/node :n1 [:economics/award-amount :prf/rate-of-gross] :spec {:parameter-key :p})
    (fx/node :n2 [:economics/award-amount :prf/rate-of-gross] :spec {:parameter-key :q})))
  ([_emap n1]
   (two-stage nil n1 (fx/node :n2 [:economics/award-amount :prf/rate-of-gross])))
  ([_emap n1 n2]
   (fx/seq-combination n1 n2)))

;; ── combination validation ────────────────────────────────────────────────

(deftest combination-validation
  (is (:valid? (combo/validate-combination (two-stage (fx/ext-map-with)))))
  (is (some #(= :violation/empty-combination (:violation/id %))
            (:violations (combo/validate-combination {:combination/id :x :combination/version 1
                                                      :combination/nodes []}))))
  (is (some #(= :violation/duplicate-node-id (:violation/id %))
            (:violations (combo/validate-combination
                          {:combination/id :x :combination/version 1
                           :combination/nodes [(fx/node :n1 [:a :b])
                                               (fx/node :n1 [:a :c])]}))))
  (is (some #(= :violation/invalid-combination-edges (:violation/id %))
            (:violations (combo/validate-combination
                          (assoc (two-stage (fx/ext-map-with))
                                 :combination/edges [{:edge/id :x :from :n2 :to :n1}]))))))

(deftest caller-edge-ids-accepted
  (testing "edges with caller-supplied ids are accepted when the structure is
            the canonical consecutive chain"
    (is (:valid? (combo/validate-combination
                  (assoc (two-stage (fx/ext-map-with))
                         :combination/edges
                         [{:edge/id :e1 :from :n1 :to :n2}]))))))

(deftest missing-input-output-rejected
  (let [base (two-stage (fx/ext-map-with))]
    (is (some #(= :violation/missing-combination-input (:violation/id %))
              (:violations (combo/validate-combination (dissoc base :combination/input)))))
    (is (some #(= :violation/missing-combination-expected-output (:violation/id %))
              (:violations (combo/validate-combination
                            (dissoc base :combination/expected-output)))))))

;; ── held-custody addresses ────────────────────────────────────────────────

(def addresses
  {:owner/address "0xowner"
   :parameter/address "0xparameter"
   :parameter/context "sew:governance-snapshot"})

(deftest combination-addresses-validation
  (let [base (two-stage (fx/ext-map-with))]
    (is (:valid? (combo/validate-combination (assoc base :combination/addresses addresses))))
    (is (some #(= :violation/missing-or-invalid-combination-address (:violation/id %))
              (:violations (combo/validate-combination
                            (assoc base :combination/addresses {:owner/address "0xowner"})))))
    (is (some #(= :violation/unknown-combination-address-key (:violation/id %))
              (:violations (combo/validate-combination
                            (assoc base :combination/addresses
                                   (assoc addresses :bogus/address "0x"))))))
    (is (some #(= :violation/invalid-combination-addresses (:violation/id %))
              (:violations (combo/validate-combination
                            (assoc base :combination/addresses [1 2])))))))

(deftest combination-addresses-bound-in-plan
  (let [emap (fx/ext-map-with)
        base (two-stage emap)
        {:keys [status plan]} (compile-it emap (assoc base :combination/addresses addresses))]
    (is (= :valid status))
    (is (= addresses (:plan/addresses plan)))
    (is (not= (:plan/root (:plan (compile-it emap base)))
              (:plan/root plan))
        "addresses are a semantic plan input")
    (is (not= (combo/combination-root base)
              (combo/combination-root (assoc base :combination/addresses addresses)))
        "addresses change the combination root")))

(deftest per-node-addresses-override-combination-level
  (let [emap (fx/ext-map-with)
        node-addresses {:owner/address "0xnode1" :parameter/address "0xnode1-param"}
        base (assoc (two-stage emap
                               (fx/node :n1 [:economics/award-amount :prf/rate-of-gross]
                                        :addresses node-addresses)
                               (fx/node :n2 [:economics/award-amount :prf/rate-of-gross]))
                    :combination/addresses addresses)
        {:keys [status plan]} (compile-it emap base)]
    (is (= :valid status))
    (is (= node-addresses (-> plan :plan/nodes first :addresses))
        "a node's own addresses win")
    (is (= addresses (-> plan :plan/nodes second :addresses))
        "a node without addresses falls back to the combination-level default")
    (is (not= (-> plan :plan/nodes first :addresses)
              (-> plan :plan/nodes second :addresses)))))

(deftest invalid-node-addresses-rejected
  (let [base (assoc (two-stage (fx/ext-map-with)
                               (fx/node :n1 [:economics/award-amount :prf/rate-of-gross]
                                        :addresses {:owner/address "0x"}))
                    :combination/addresses addresses)
        {:keys [valid? violations]} (combo/validate-combination base)]
    (is (not valid?))
    (is (some #(= :violation/missing-or-invalid-combination-address (:violation/id %))
              violations))))

(deftest explicit-edges-equal-derived-combination-root
  (let [base (two-stage (fx/ext-map-with))
        with-edges (assoc base :combination/edges (combo/effective-edges base))]
    (is (= (combo/combination-root base)
           (combo/combination-root with-edges)))))

(deftest combination-root-mutation
  (testing "every committed semantic compiler input changes the combination root"
    (let [base (two-stage (fx/ext-map-with))]
      (doseq [[label mutate] [["input-semantic" #(assoc-in % [:combination/input :semantic-type] :gross)]
                              ["expected-output" #(assoc-in % [:combination/expected-output :semantic-type] :gross)]
                              ["verification" #(assoc % :combination/verification
                                                      {:intermediate-output-committed? false})]
                              ["node-capability" #(assoc-in % [:combination/nodes 1 :capability/ref 1]
                                                            :prf/resolved-amount)]]]
        (is (not= (combo/combination-root base)
                  (combo/combination-root (mutate base)))
            (str label " must change the combination root")))))
  (testing "adapters are not committed in v1"
    (let [base (two-stage (fx/ext-map-with))
          with-adapters (assoc base :combination/adapters [:fixture/adapter])]
      (is (= (combo/combination-root base)
             (combo/combination-root with-adapters))
          "v1 categorically forbids adapters, so the field is not part of the
          combination root")))
  (testing "irrelevant node metadata does not change the combination root"
    (let [base (two-stage (fx/ext-map-with))
          noisy (update base :combination/nodes
                        (fn [ns] (mapv #(assoc % :source-metadata {:x 1}) ns)))]
      (is (= (combo/combination-root base)
             (combo/combination-root noisy))))))

;; ── valid pipelines ───────────────────────────────────────────────────────

(deftest valid-two-stage
  (let [emap (fx/ext-map-with)
        {:keys [status plan]} (compile-it emap (two-stage emap))]
    (is (= :valid status))
    (is (= 2 (count (:plan/nodes plan))))
    (is (every? #(= 64 (count %)) (map :capability-root (:plan/nodes plan))))
    (is (every? #(= 64 (count %)) (map :contract-root (:plan/nodes plan))))
    (is (= 1 (count (:plan/edges plan))))
    (is (= comp/compiler-id (:plan/compiler-id plan)))
    (is (= comp/compiler-version (:plan/compiler-version plan)))
    (is (= 64 (count (:plan/root plan))))
    (is (string? (:plan/combination-root plan)))))

(deftest valid-three-stage
  (let [emap (fx/ext-map-with)
        combo {:combination/id :test.combination/three
               :combination/version 1
               :combination/nodes [(fx/node :n1 [:economics/award-amount :prf/rate-of-gross])
                                   (fx/node :n2 [:economics/award-amount :prf/rate-of-gross])
                                   (fx/node :n3 [:economics/award-amount :prf/rate-of-gross])]
               :combination/input {:schema-ref :prf/award-amount-context.v1 :semantic-type :amount}
               :combination/expected-output {:schema-ref :prf/calculation-result.v1 :semantic-type :amount}}
        {:keys [status plan]} (compile-it emap combo)]
    (is (= :valid status))
    (is (= 3 (count (:plan/nodes plan))))
    (is (= 2 (count (:plan/edges plan))))))

;; ── structured rejections ─────────────────────────────────────────────────

(deftest unresolved-capability-rejected
  (let [emap (fx/ext-map-with)]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/nope]))
                 :violation/unresolved-capability))))

(deftest version-mismatch-rejected
  (let [emap (fx/ext-map-with)]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :prf/rate-of-gross]
                                          :version 99))
                 :violation/capability-version-mismatch))))

(deftest missing-contract-rejected
  (let [emap (fx/ext-map-with (dissoc (fx/cap :fixture/nocontract) :composition-contract))]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/nocontract]))
                 :violation/missing-composition-contract))))

(deftest invalid-contract-rejected
  (let [bad {:capability (assoc (fx/cap :fixture/bad)
                                :composition-contract {:no/version true})
             :descriptor-root "x" :builtin? false :providers []}
        emap (assoc (fx/ext-map-with) [:economics/award-amount :fixture/bad] bad)]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/bad]))
                 :violation/invalid-composition-contract))))

(deftest unsupported-mode-rejected
  (let [emap (fx/ext-map-with (fx/cap :fixture/parallel :modes #{:parallel}))]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/parallel]))
                 :violation/unsupported-composition-mode))))

(deftest nondeterministic-rejected
  (let [emap (fx/ext-map-with (fx/cap :fixture/nd :determinism false))]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/nd]))
                 :violation/nondeterministic-capability-forbidden))))

(deftest input-output-semantic-mismatch
  (let [emap (fx/ext-map-with (fx/cap :fixture/in2 :input-semantic :amount-with-effects))]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :prf/rate-of-gross])
                            (fx/node :n2 [:economics/award-amount :fixture/in2]))
                 :violation/input-output-semantic-mismatch))))

(deftest graph-input-not-satisfied
  (let [emap (fx/ext-map-with)
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :prf/rate-of-gross])
               (fx/node :n2 [:economics/award-amount :prf/rate-of-gross])
               :input :gross)]
    (is (rejects emap combo :violation/graph-input-not-satisfied))))

(deftest graph-output-not-satisfied
  (let [emap (fx/ext-map-with)
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :prf/rate-of-gross])
               (fx/node :n2 [:economics/award-amount :prf/rate-of-gross])
               :output :gross)]
    (is (rejects emap combo :violation/graph-output-not-satisfied))))

(deftest illegal-terminal-placement
  (let [emap (fx/ext-map-with (fx/cap :fixture/term :terminal? true))]
    (is (rejects emap
                 (two-stage emap (fx/node :n1 [:economics/award-amount :fixture/term]))
                 :violation/illegal-terminal-placement))))

(deftest undeclared-dependency-rejected
  (let [emap (fx/ext-map-with)]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :prf/rate-of-gross]
                                     :basis {:source :step/output :step-id :n99 :field :remaining}))
                 :violation/undeclared-dependency))))

(deftest effect-conflict-rejected
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/e1 :effects #{:prf.effect/x.v1} :exclusive #{:prf.effect/x.v1})
              (fx/cap :fixture/e2 :effects #{:prf.effect/x.v1} :exclusive #{:prf.effect/x.v1}))]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :fixture/e1])
                            (fx/node :n2 [:economics/award-amount :fixture/e2]))
                 :violation/effect-conflict))))

(deftest merge-strategy-conflict-rejected
  (testing "a combination-level merge strategy conflicting with a node contract
            is rejected (the plan must not bind a strategy a node contradicts)"
    (let [emap (fx/ext-map-with (fx/cap :fixture/ms :merge :replace))
          combo (assoc (two-stage emap
                                  (fx/node :n1 [:economics/award-amount :fixture/ms])
                                  (fx/node :n2 [:economics/award-amount :prf/rate-of-gross]))
                       :combination/effect-merge-strategy :accumulate)]
      (is (rejects emap combo :violation/effect-conflict)))))

;; ── custody-affecting effect conflicts ─────────────────────────────────────

(deftest exclusive-custody-account-conflict
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/c1 :custody {:direction :add :accounts #{:escrow}
                                            :exclusive-accounts #{:escrow}})
              (fx/cap :fixture/c2 :custody {:direction :add :accounts #{:escrow}}))]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :fixture/c1])
                            (fx/node :n2 [:economics/award-amount :fixture/c2]))
                 :violation/custody-effect-conflict))))

(deftest custody-direction-conflict
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/c1 :custody {:direction :add :accounts #{:escrow}})
              (fx/cap :fixture/c2 :custody {:direction :sub :accounts #{:escrow}}))]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :fixture/c1])
                            (fx/node :n2 [:economics/award-amount :fixture/c2]))
                 :violation/custody-effect-conflict))))

(deftest same-account-same-direction-compiles
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/c1 :custody {:direction :add :accounts #{:escrow}})
              (fx/cap :fixture/c2 :custody {:direction :add :accounts #{:escrow}}))
        {:keys [status]} (compile-it emap
                                     (two-stage emap
                                                (fx/node :n1 [:economics/award-amount :fixture/c1])
                                                (fx/node :n2 [:economics/award-amount :fixture/c2])))]
    (is (= :valid status))))

(deftest failure-mode-conflict-rejected
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/f1 :failure-mode :abort)
              (fx/cap :fixture/f2 :failure-mode :continue))]
    (is (rejects emap
                 (two-stage emap
                            (fx/node :n1 [:economics/award-amount :fixture/f1])
                            (fx/node :n2 [:economics/award-amount :fixture/f2]))
                 :violation/failure-mode-conflict))))

(deftest same-failure-mode-compiles
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/f1 :failure-mode :abort)
              (fx/cap :fixture/f2 :failure-mode :abort))]
    (is (= :valid (:status (compile-it emap
                                       (two-stage emap
                                                  (fx/node :n1 [:economics/award-amount :fixture/f1])
                                                  (fx/node :n2 [:economics/award-amount :fixture/f2]))))))))

(deftest unsupported-adapters-rejected
  (let [emap (fx/ext-map-with)
        combo (assoc (two-stage emap) :combination/adapters [:fixture/adapter])]
    (is (rejects emap combo :violation/unsupported-adapters))))

(deftest verification-contract-not-preserved
  (let [emap (fx/ext-map-with)
        combo (assoc (two-stage emap)
                     :combination/verification {:intermediate-output-committed? false})]
    (is (rejects emap combo :violation/verification-contract-not-preserved))))

(deftest malformed-combination-verification-rejected
  (let [emap (fx/ext-map-with)]
    (doseq [[label verification] [["non-map" "garbage"]
                                  ["vector" [:x]]
                                  ["non-boolean-flag" {:intermediate-output-committed? "yes"}]
                                  ["bad-evidence-ref" {:evidence-contract-ref 42}]
                                  ["unknown-key" {:intermediate-output-committed? true :typo/key 1}]]]
      (let [combo (assoc (two-stage emap) :combination/verification verification)
            {:keys [valid? violations]} (combo/validate-combination combo)]
        (is (not valid?) (str label " must be rejected by validate-combination"))
        (is (seq violations)))
      (is (= :invalid (:status (compile-it emap
                                           (assoc (two-stage emap)
                                                  :combination/verification verification))))
          (str label " must not compile")))))

(deftest valid-combination-verification-compiles
  (let [emap (fx/ext-map-with)
        combo (assoc (two-stage emap)
                     :combination/verification {:intermediate-output-committed? true
                                                :evidence-contract-ref :prf/evidence.v1})]
    (is (:valid? (combo/validate-combination combo)))
    (is (= :valid (:status (compile-it emap combo))))))

;; ── graph helpers ─────────────────────────────────────────────────────────

(deftest graph-cycle-and-unreachable-helpers
  (is (comp/graph-has-cycle? [:a :b] [{:from :a :to :b} {:from :b :to :a}]))
  (is (not (comp/graph-has-cycle? [:a :b :c] [{:from :a :to :b} {:from :b :to :c}])))
  (is (= [:b] (vec (comp/unreachable-node-ids [:a :b :c] [{:from :a :to :c}]))))
  (is (nil? (comp/unreachable-node-ids [:a :b :c] [{:from :a :to :b} {:from :b :to :c}]))))

;; ── determinism, idempotence, root mutation ───────────────────────────────

(deftest deterministic-and-idempotent
  (let [emap (fx/ext-map-with)
        combo (two-stage emap)]
    (is (= (:plan/root (:plan (compile-it emap combo)))
           (:plan/root (:plan (compile-it emap combo))))
        "compilation is deterministic")
    (is (= (compile-it emap combo) (compile-it emap combo))
        "compilation is idempotent")))

(deftest plan-root-mutation
  (testing "the plan root changes when any semantically relevant input changes"
    (let [emap (fx/ext-map-with)
          base (two-stage emap)]
      (doseq [[label mutate] [["node-spec" #(assoc-in % [:combination/nodes 0 :spec :parameter-key] :z)]
                              ["capability-ref" #(assoc-in % [:combination/nodes 1 :capability/ref]
                                                           [:economics/award-amount :prf/resolved-amount])]
                              ["input-semantic" #(assoc-in % [:combination/input :semantic-type] :gross)]
                              ["expected-output" #(assoc-in % [:combination/expected-output :semantic-type] :gross)]]]
        (is (not= (:plan/root (:plan (compile-it emap base)))
                  (:plan/root (:plan (compile-it emap (mutate base)))))
            (str label " must change the plan root")))))
  (testing "irrelevant source metadata does not change the plan root"
    (let [emap (fx/ext-map-with)
          base (two-stage emap)
          noisy (update base :combination/nodes
                        (fn [ns] (mapv #(assoc % :source-metadata {:foo 1}) ns)))]
      (is (= (:plan/root (:plan (compile-it emap base)))
             (:plan/root (:plan (compile-it emap noisy))))))))
