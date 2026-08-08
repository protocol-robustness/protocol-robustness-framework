(ns resolver-sim.composition.compiler-test
  "Combination representation and the sequential composition compiler:
   valid pipelines, structured rejections, determinism, and root mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.combination :as combo]
            [resolver-sim.composition.compiler :as comp]
            [resolver-sim.composition.fixtures :as fx]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- compile-it
  ([emap combination]
   (comp/compile-combination {:extensions emap} combination))
  ([emap combination evidence-contracts]
   (comp/compile-combination {:extensions emap
                              :evidence-contracts evidence-contracts}
                             combination))
  ([emap combination evidence-contracts obligation-definitions]
   (comp/compile-combination {:extensions emap
                              :evidence-contracts evidence-contracts
                              :obligations obligation-definitions}
                             combination)))

(defn- hex64 [c] (apply str (repeat 64 c)))

(def ref-a
  "Committed root of evidence.contract/a."
  (hash-ref/sha256-ref (hex64 \a)))

(def ref-a-v2
  "A later registry mutation re-pointing evidence.contract/a."
  (hash-ref/sha256-ref (hex64 \d)))

(def ref-b
  "Committed root of evidence.contract/b."
  (hash-ref/sha256-ref (hex64 \b)))

(def ref-invariant
  "Committed root of a non-evidence-contract kind entry."
  (hash-ref/sha256-ref (hex64 \c)))

(def evidence-contracts
  "Explicit evidence-contract registry resolved by the compiler."
  {:evidence.contract/a {:evidence-contract/id :evidence.contract/a
                         :evidence-contract/kind :evidence-contract
                         :evidence-contract/root ref-a}
   :evidence.contract/b {:evidence-contract/id :evidence.contract/b
                         :evidence-contract/kind :evidence-contract
                         :evidence-contract/root ref-b}
   :yield.invariant/ledger-balanced {:evidence-contract/id :yield.invariant/ledger-balanced
                                     :evidence-contract/kind :invariant
                                     :evidence-contract/root ref-invariant}})

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

(defn- verification-combo
  [verification]
  (assoc (two-stage (fx/ext-map-with)) :combination/verification verification))

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
                                                :evidence-contract-ref :evidence.contract/a})]
    (is (:valid? (combo/validate-combination combo)))
    (is (= :valid (:status (compile-it emap combo evidence-contracts))))
    (is (= {:id :evidence.contract/a :root ref-a}
           (:evidence-contract (:plan/verification (:plan (compile-it emap combo evidence-contracts))))))))

;; ── evidence-contract resolution (compile-time trust boundary) ─────────────

(deftest evidence-contract-resolved-and-committed
  (let [emap (fx/ext-map-with)
        combo (verification-combo {:evidence-contract-ref :evidence.contract/a})
        {:keys [status plan]} (compile-it emap combo evidence-contracts)]
    (is (= :valid status))
    (is (= {:id :evidence.contract/a :root ref-a}
           (:evidence-contract (:plan/verification plan)))
        "the resolved identity, not merely the symbolic ref, is committed")
    (is (not (contains? (:plan/verification plan) :evidence-contract-ref))
        "the symbolic ref is not committed raw")))

(deftest evidence-contract-absent-remains-valid
  (testing "no evidence-contract-ref declares no evidence obligation and is valid"
    (let [{:keys [status plan]} (compile-it (fx/ext-map-with) (two-stage (fx/ext-map-with)))]
      (is (= :valid status))
      (is (nil? (:evidence-contract (:plan/verification plan)))))))

(deftest evidence-contract-unresolved-rejected
  (testing "a declared ref that does not exist in the registry fails compilation"
    (let [{:keys [status violations]}
          (compile-it (fx/ext-map-with)
                      (verification-combo {:evidence-contract-ref :evidence.contract/nope})
                      evidence-contracts)]
      (is (= :invalid status))
      (is (some #(= :violation/unresolved-evidence-contract (:violation/id %)) violations)))))

(deftest evidence-contract-wrong-kind-rejected
  (testing "a ref that resolves to a non-evidence-contract kind is rejected"
    (let [{:keys [status violations]}
          (compile-it (fx/ext-map-with)
                      (verification-combo {:evidence-contract-ref :yield.invariant/ledger-balanced})
                      evidence-contracts)]
      (is (= :invalid status))
      (is (some #(= :violation/evidence-contract-wrong-kind (:violation/id %)) violations)))))

(deftest evidence-contract-requires-explicit-registry
  (testing "a declared ref with no registry supplied is never silently dropped"
    (let [{:keys [status violations]}
          (compile-it (fx/ext-map-with)
                      (verification-combo {:evidence-contract-ref :evidence.contract/a}))]
      (is (= :invalid status))
      (is (some #(= :violation/unresolved-evidence-contract (:violation/id %)) violations)))))

(deftest evidence-contract-committed-root-is-immutable
  (testing "the compiled plan binds the resolved root, so a later registry
            mutation cannot change what the plan already meant"
    (let [emap (fx/ext-map-with)
          combo (verification-combo {:evidence-contract-ref :evidence.contract/a})
          plan (-> (compile-it emap combo evidence-contracts) :plan)
          ;; a later registry mutation points the same ref at a new contract
          mutated (assoc-in evidence-contracts
                            [:evidence.contract/a :evidence-contract/root]
                            ref-a-v2)
          later (-> (compile-it emap combo mutated) :plan)]
      (is (= ref-a (get-in plan [:plan/verification :evidence-contract :root]))
          "the already-compiled plan keeps its committed root")
      (is (= ref-a-v2 (get-in later [:plan/verification :evidence-contract :root]))
          "a fresh compilation against the mutated registry resolves the new root"))))

(deftest evidence-contract-change-changes-plan-root
  (testing "changing the referenced evidence contract changes the plan root"
    (let [emap (fx/ext-map-with)
          base (verification-combo {:evidence-contract-ref :evidence.contract/a})
          other (verification-combo {:evidence-contract-ref :evidence.contract/b})
          plan-a (-> (compile-it emap base evidence-contracts) :plan)
          plan-b (-> (compile-it emap other evidence-contracts) :plan)]
      (is (not= (:plan/root plan-a) (:plan/root plan-b))
          "a different evidence contract is a different compiled plan"))))

(deftest evidence-contract-identity-is-declared-contract-based
  (testing "identity is the (id, root) pair: two refs resolving to the SAME
            root commit different identities and therefore different plans"
    (let [emap (fx/ext-map-with)
          alias-contracts (assoc evidence-contracts
                                 :evidence.contract/a-alias
                                 {:evidence-contract/id :evidence.contract/a-alias
                                  :evidence-contract/kind :evidence-contract
                                  :evidence-contract/root ref-a})
          plan-a (-> (compile-it emap
                                 (verification-combo {:evidence-contract-ref :evidence.contract/a})
                                 alias-contracts)
                     :plan)
          plan-alias (-> (compile-it emap
                                     (verification-combo {:evidence-contract-ref :evidence.contract/a-alias})
                                     alias-contracts)
                         :plan)]
      (is (= ref-a (get-in plan-a [:plan/verification :evidence-contract :root])))
      (is (= {:id :evidence.contract/a-alias :root ref-a}
             (:evidence-contract (:plan/verification plan-alias)))
          "the ref id names WHICH declared contract the plan binds")
      (is (not= (:plan/root plan-a) (:plan/root plan-alias))
          "same root, different declared id => different plan root"))))

(deftest per-node-evidence-contract-ref-is-not-mistaken-for-combination-obligation
  (testing "a per-node :composition/verification :evidence-contract-ref is NOT
            a combination-level obligation: it is not resolved and does not
            require the registry, even when the registry lacks the ref"
    (let [node-cap (assoc-in (fx/cap :fixture/evidence-node)
                             [:composition-contract :composition/verification :evidence-contract-ref]
                             :prf/calculation-result.v1)
          emap (fx/ext-map-with node-cap)
          combo (two-stage emap
                           (fx/node :n1 [:economics/award-amount :fixture/evidence-node])
                           (fx/node :n2 [:economics/award-amount :prf/rate-of-gross]))
          {:keys [status plan]} (compile-it emap combo evidence-contracts)]
      (is (= :valid status)
          "the per-node ref is legacy/unresolved vocabulary and must not fail
          compilation through the combination-level resolver")
      (is (nil? (:evidence-contract (:plan/verification plan)))
          "no combination-level evidence obligation is committed"))))

;; ── typed assurance obligations (obligation.v1) ────────────────────────────

(def obligation-definitions
  "Explicit kind-aware definitions registry resolved by the compiler."
  {:custody/held-action
   {:obligation/id :custody/held-action
    :obligation/kind :effect
    :obligation/root (hash-ref/sha256-ref (hex64 \a))
    :obligation/input-contract-root (hash-ref/sha256-ref (hex64 \b))
    :obligation/satisfaction-contract-root (hash-ref/sha256-ref (hex64 \c))
    :obligation/scope-contract {:subjects #{:combination/effects :node/output}
                                :phases #{:post-execution}
                                :node-id-required? false}
    :obligation/constraint-contract {:fields #{:action} :required #{:action}}}
   :yield.invariant/ledger-balanced
   {:obligation/id :yield.invariant/ledger-balanced
    :obligation/kind :invariant
    :obligation/root (hash-ref/sha256-ref (hex64 \d))
    :obligation/input-contract-root (hash-ref/sha256-ref (hex64 \e))
    :obligation/satisfaction-contract-root (hash-ref/sha256-ref (hex64 \f))
    :obligation/scope-contract {:subjects #{:combination/output :combination/state :node/output}
                                :phases #{:post-execution}
                                :node-id-required? false}}
   :evidence/valid-artifact
   {:obligation/id :evidence/valid-artifact
    :obligation/kind :evidence
    :obligation/root (hash-ref/sha256-ref (hex64 \1))
    :obligation/input-contract-root (hash-ref/sha256-ref (hex64 \2))
    :obligation/satisfaction-contract-root (hash-ref/sha256-ref (hex64 \3))
    :obligation/scope-contract {:subjects #{:combination/evidence}
                                :phases #{}
                                :node-id-required? false}}})

(def custody-safe-obligations
  "Phase-2 acceptance fixture: custody-safe execution = required sub-held +
   ledger-balanced + valid evidence."
  [{:obligation/kind :effect
    :obligation/ref :custody/held-action
    :obligation/scope {:subject :combination/effects :phase :post-execution}
    :obligation/constraint {:action "sub-held"}}
   {:obligation/kind :invariant
    :obligation/ref :yield.invariant/ledger-balanced
    :obligation/scope {:subject :combination/output :phase :post-execution}}
   {:obligation/kind :evidence
    :obligation/ref :evidence/valid-artifact
    :obligation/scope {:subject :combination/evidence}}])

(defn- custody-safe-combo
  ([] (custody-safe-combo custody-safe-obligations))
  ([obligations]
   (assoc (two-stage (fx/ext-map-with))
          :combination/verification {:intermediate-output-committed? true
                                     :obligations obligations})))

(defn- compile-obligations
  [combo]
  (compile-it (fx/ext-map-with) combo nil obligation-definitions))

(deftest obligations-resolved-and-committed
  (testing "each declared obligation resolves to its committed identity in the plan"
    (let [{:keys [status plan]} (compile-obligations (custody-safe-combo))]
      (is (= :valid status))
      (let [obligations (:obligations (:plan/verification plan))]
        (is (= 3 (count obligations)))
        (is (= [{:obligation/kind :effect
                 :obligation/id :custody/held-action
                 :obligation/root (hash-ref/sha256-ref (hex64 \a))
                 :obligation/input-contract-root (hash-ref/sha256-ref (hex64 \b))
                 :obligation/satisfaction-contract-root (hash-ref/sha256-ref (hex64 \c))
                 :obligation/scope {:subject :combination/effects :phase :post-execution}
                 :obligation/constraint {:action "sub-held"}}]
               (filter #(= :effect (:obligation/kind %)) obligations)))
        (is (= :yield.invariant/ledger-balanced
               (:obligation/id (first (filter #(= :invariant (:obligation/kind %)) obligations)))))
        (is (= :evidence/valid-artifact
               (:obligation/id (first (filter #(= :evidence (:obligation/kind %)) obligations)))))))))

(deftest obligation-fail-closed-compilation
  (testing "unknown ref"
    (let [{:keys [status violations]}
          (compile-obligations
           (custody-safe-combo
            (assoc-in custody-safe-obligations [0 :obligation/ref] :custody/nope)))]
      (is (= :invalid status))
      (is (some #(= :violation/unresolved-obligation (:violation/id %)) violations))))
  (testing "wrong kind for ref"
    (let [{:keys [status violations]}
          (compile-obligations
           (custody-safe-combo
            (assoc-in custody-safe-obligations [0 :obligation/kind] :invariant)))]
      (is (= :invalid status))
      (is (some #(= :violation/obligation-wrong-kind (:violation/id %)) violations))))
  (testing "invalid scope for definition"
    (let [{:keys [status violations]}
          (compile-obligations
           (custody-safe-combo
            (assoc-in custody-safe-obligations [1 :obligation/scope :subject]
                      :combination/effects)))]
      (is (= :invalid status))
      (is (some #(= :violation/invalid-obligation-scope-or-constraint (:violation/id %))
                violations))))
  (testing "invalid constraint for definition"
    (let [{:keys [status violations]}
          (compile-obligations
           (custody-safe-combo
            (assoc-in custody-safe-obligations [0 :obligation/constraint]
                      {:amount 100})))]
      (is (= :invalid status))
      (is (some #(= :violation/invalid-obligation-scope-or-constraint (:violation/id %))
                violations)))))

(deftest each-obligation-is-independently-necessary
  (testing "removing any one obligation changes the plan (each is committed,
            none is decorative); changing a ref or constraint changes the plan"
    (let [base (-> (compile-obligations (custody-safe-combo)) :plan :plan/root)
          without-effect (-> (compile-obligations
                              (custody-safe-combo (subvec custody-safe-obligations 1)))
                             :plan :plan/root)
          without-invariant (-> (compile-obligations
                                 (custody-safe-combo (vec (concat (subvec custody-safe-obligations 0 1)
                                                                  (subvec custody-safe-obligations 2)))))
                                :plan :plan/root)
          without-evidence (-> (compile-obligations
                                (custody-safe-combo (subvec custody-safe-obligations 0 2)))
                               :plan :plan/root)
          other-action (-> (compile-obligations
                            (custody-safe-combo
                             (assoc-in custody-safe-obligations [0 :obligation/constraint :action]
                                       "refund-held")))
                           :plan :plan/root)]
      (is (not= base without-effect) "the :effect obligation is committed")
      (is (not= base without-invariant) "the :invariant obligation is committed")
      (is (not= base without-evidence) "the :evidence obligation is committed")
      (is (not= base other-action) "a changed constraint changes the plan"))))

(deftest obligation-scope-is-first-class
  (testing "scope is required on every obligation; the plan commits it"
    (let [{:keys [status violations]}
          (compile-obligations
           (custody-safe-combo
            (assoc-in custody-safe-obligations [2 :obligation/scope] nil)))
          nested (mapcat :violations (map :details violations))]
      (is (= :invalid status))
      (is (some #(= :violation/missing-obligation-scope (:violation/id %)) nested)))))

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
