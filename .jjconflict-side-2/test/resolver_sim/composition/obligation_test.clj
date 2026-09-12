(ns resolver-sim.composition.obligation-test
  "Typed assurance obligations: source validation, scope/constraint checks, and
   fail-closed resolution against a kind-aware definitions registry."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.obligation :as obl]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- hex64 [c] (apply str (repeat 64 c)))

(defn- entry
  [id kind & {:keys [root input satisfaction scope-contract constraint-contract]}]
  {:obligation/id id
   :obligation/kind kind
   :obligation/root (or root (hash-ref/sha256-ref (hex64 \a)))
   :obligation/input-contract-root (or input (hash-ref/sha256-ref (hex64 \b)))
   :obligation/satisfaction-contract-root (or satisfaction (hash-ref/sha256-ref (hex64 \c)))
   :obligation/scope-contract (or scope-contract {:subjects #{:combination/effects}
                                                  :phases #{:post-execution}
                                                  :node-id-required? false})
   :obligation/constraint-contract constraint-contract})

(def held-action-def
  (entry :custody/held-action :effect
         :constraint-contract {:fields #{:action} :required #{:action}}))

(def invariant-def
  (entry :yield.invariant/ledger-balanced :invariant
         :scope-contract {:subjects #{:combination/output :combination/state :node/output}
                          :phases #{:post-execution}
                          :node-id-required? false}))

(def evidence-def
  (entry :evidence/valid-artifact :evidence
         :scope-contract {:subjects #{:combination/evidence}
                          :phases #{}
                          :node-id-required? false}))

(def definitions
  {:custody/held-action held-action-def
   :yield.invariant/ledger-balanced invariant-def
   :evidence/valid-artifact evidence-def})

(deftest resolve-valid-obligation
  (testing "a valid :effect obligation resolves to its committed identity plus
            instance data (scope, constraint)"
    (let [{:keys [resolved? obligation]}
          (obl/resolve-obligation
           definitions
           {:obligation/kind :effect
            :obligation/ref :custody/held-action
            :obligation/scope {:subject :combination/effects :phase :post-execution}
            :obligation/constraint {:action "sub-held"}})]
      (is resolved?)
      (is (= :effect (:obligation/kind obligation)))
      (is (= :custody/held-action (:obligation/id obligation)))
      (is (= (:obligation/root held-action-def) (:obligation/root obligation)))
      (is (= (:obligation/input-contract-root held-action-def)
             (:obligation/input-contract-root obligation)))
      (is (= (:obligation/satisfaction-contract-root held-action-def)
             (:obligation/satisfaction-contract-root obligation)))
      (is (= {:subject :combination/effects :phase :post-execution}
             (:obligation/scope obligation)))
      (is (= {:action "sub-held"} (:obligation/constraint obligation))))))

(deftest resolve-invariant-and-evidence
  (let [inv (obl/resolve-obligation
             definitions
             {:obligation/kind :invariant
              :obligation/ref :yield.invariant/ledger-balanced
              :obligation/scope {:subject :combination/output :phase :post-execution}})
        ev (obl/resolve-obligation
            definitions
            {:obligation/kind :evidence
             :obligation/ref :evidence/valid-artifact
             :obligation/scope {:subject :combination/evidence}})]
    (is (:resolved? inv))
    (is (= :invariant (get-in inv [:obligation :obligation/kind])))
    (is (:resolved? ev))
    (is (= :combination/evidence (get-in ev [:obligation :obligation/scope :subject])))))

(deftest fail-closed-resolution
  (testing "unknown ref"
    (is (= :violation/unresolved-obligation
           (:violation/id (obl/resolve-obligation
                           definitions
                           {:obligation/kind :effect
                            :obligation/ref :custody/nope
                            :obligation/scope {:subject :combination/effects}})))))
  (testing "wrong kind for ref"
    (is (= :violation/obligation-wrong-kind
           (:violation/id (obl/resolve-obligation
                           definitions
                           {:obligation/kind :invariant
                            :obligation/ref :custody/held-action
                            :obligation/scope {:subject :combination/output :phase :post-execution}})))))
  (testing "definition lacking committed identity"
    (let [broken (dissoc held-action-def :obligation/satisfaction-contract-root)]
      (is (= :violation/malformed-obligation-definition
             (:violation/id (obl/resolve-obligation
                             {:custody/held-action broken}
                             {:obligation/kind :effect
                              :obligation/ref :custody/held-action
                              :obligation/scope {:subject :combination/effects}}))))))
  (testing "invalid scope for definition"
    (is (= :violation/invalid-obligation-scope-or-constraint
           (:violation/id (obl/resolve-obligation
                           definitions
                           {:obligation/kind :invariant
                            :obligation/ref :yield.invariant/ledger-balanced
                            :obligation/scope {:subject :combination/effects :phase :post-execution}})))))
  (testing "invalid constraint for definition"
    (is (= :violation/invalid-obligation-scope-or-constraint
           (:violation/id (obl/resolve-obligation
                           definitions
                           {:obligation/kind :effect
                            :obligation/ref :custody/held-action
                            :obligation/scope {:subject :combination/effects :phase :post-execution}
                            :obligation/constraint {:amount 100}}))))))

(deftest source-obligation-validation
  (testing "unsupported kind"
    (is (some #(= :violation/unsupported-obligation-kind (:violation/id %))
              (:violations (obl/validate-obligation
                            {:obligation/kind :workflow
                             :obligation/ref :x
                             :obligation/scope {}})))))
  (testing "missing scope"
    (is (some #(= :violation/missing-obligation-scope (:violation/id %))
              (:violations (obl/validate-obligation
                            {:obligation/kind :effect
                             :obligation/ref :custody/held-action})))))
  (testing "non-map constraint"
    (is (some #(= :violation/invalid-obligation-constraint (:violation/id %))
              (:violations (obl/validate-obligation
                            {:obligation/kind :effect
                             :obligation/ref :custody/held-action
                             :obligation/scope {:subject :combination/effects}
                             :obligation/constraint "sub-held"}))))))

(deftest scope-validation
  (testing "unknown scope key"
    (is (some #(= :violation/unknown-obligation-scope-key (:violation/id %))
              (obl/validate-scope {:subject :combination/effects :typo/key 1}
                                  {:subjects #{:combination/effects} :phases #{}}))))
  (testing "unsupported phase"
    (is (some #(= :violation/unsupported-obligation-scope-phase (:violation/id %))
              (obl/validate-scope {:subject :combination/output :phase :during-execution}
                                  {:subjects #{:combination/output} :phases #{:post-execution}}))))
  (testing "missing node id when required"
    (is (some #(= :violation/missing-obligation-node-id (:violation/id %))
              (obl/validate-scope {:subject :node/output}
                                  {:subjects #{:node/output} :phases #{} :node-id-required? true})))))
