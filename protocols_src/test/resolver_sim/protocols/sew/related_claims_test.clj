(ns resolver-sim.protocols.sew.related-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.related-claims :as rc]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.workflow-group :as wg]))

(def usdc :0xUSDC)
(def alice "0xAlice")
(def bob "0xBob")
(def gov "0xGovernance")

(def snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50}))

(defn- world-with-escrows
  [n]
  (reduce (fn [w _]
            (let [r (lc/create-escrow w alice usdc bob 1000
                                      (t/make-escrow-settings {}) snap)]
              (:world r)))
          (t/empty-world 1000)
          (range n)))

(deftest create-related-claims-happy
  (let [w (world-with-escrows 3)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test-related-claims"
                  :created-by {:actor/type :test :actor/address gov}
                  :created-at-step 5})]
    (is (true? (:ok result)))
    (is (some? (:relationship-id result)))
    (let [world' (:world result)
          rel (rc/get-related-claims world' (:relationship-id result))]
      (is (some? rel))
      (is (= :active (:relationship/status rel)))
      (is (= :same-incident (:relationship/type rel)))
      (is (= 2 (count (:relationship/members rel))))
      (is (= #{:audit-only} (:relationship/semantics rel)))
      (is (some? (:relationship/hash rel)))
      (is (= 1 (get world' :next-related-claim-id 0))))))

(deftest create-related-claims-members-exist-validation
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 99}]})]
    (is (false? (:ok result)))
    (is (= :invalid-related-claims (:error result)))))

(deftest create-related-claims-no-duplicate-within
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 0}]})]
    (is (false? (:ok result)))))

(deftest create-related-claims-no-duplicate-across-relationships
  (let [w (world-with-escrows 3)
        r1 (rc/create-related-claims! w
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 1}
                         {:claim/kind :sew/workflow :workflow/id 2}]})]
    (is (false? (:ok r2)))))

(deftest create-related-claims-no-duplicate-across-types
  (testing "a workflow cannot enter a second active relationship even of a different type"
    (let [w (world-with-escrows 3)
          r1 (rc/create-related-claims! w
                {:type :same-incident
                 :members [{:claim/kind :sew/workflow :workflow/id 0}
                           {:claim/kind :sew/workflow :workflow/id 1}]})
          world' (:world r1)
          r2 (rc/create-related-claims! world'
                {:type :same-counterparty
                 :members [{:claim/kind :sew/workflow :workflow/id 1}
                           {:claim/kind :sew/workflow :workflow/id 2}]})]
      (is (false? (:ok r2))
          "workflow 1 already belongs to an active relationship"))))

(deftest create-related-claims-rejects-empty-members
  (testing "empty membership is rejected"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members []
                    :reason "test"})]
      (is (false? (:ok result)))
      (is (= :invalid-related-claims (:error result))))))

(deftest create-related-claims-rejects-unknown-semantics
  (testing "an unknown semantics keyword is rejected"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :semantics #{:batch-force-authorisation}
                    :reason "test"})]
      (is (false? (:ok result)))
      (is (= :invalid-related-claims (:error result))))))

(deftest create-related-claims-rejects-non-audit-only-semantics
  (testing "any semantics other than exactly #{:audit-only} is rejected in v1"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :semantics #{:audit-only :cross-claim-guarantee}
                    :reason "test"})]
      (is (false? (:ok result))))))

(deftest find-related-claims-for-workflow
  (let [w (world-with-escrows 3)]
    (is (empty? (rc/find-related-claims-for-workflow w 0)))
    (let [r1 (rc/create-related-claims! w
                {:type :same-incident
                 :members [{:claim/kind :sew/workflow :workflow/id 0}
                           {:claim/kind :sew/workflow :workflow/id 1}]
                 :reason "test"})
          world' (:world r1)]
      (is (= #{(:relationship-id r1)}
             (set (rc/find-related-claims-for-workflow world' 0))))
      (is (= #{(:relationship-id r1)}
             (set (rc/find-related-claims-for-workflow world' 1))))
      (is (empty? (rc/find-related-claims-for-workflow world' 2))))))

(deftest find-related-claims-for-workflows
  (let [w (world-with-escrows 4)
        r1 (rc/create-related-claims! w
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]
               :reason "test"})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-counterparty
               :members [{:claim/kind :sew/workflow :workflow/id 2}
                         {:claim/kind :sew/workflow :workflow/id 3}]
               :reason "test2"})
        world'' (:world r2)]
    (is (= #{(:relationship-id r1) (:relationship-id r2)}
           (rc/find-related-claims-for-workflows world'' [0 2])))
    (is (= #{(:relationship-id r1)}
           (rc/find-related-claims-for-workflows world'' [0 1])))))

(deftest related-claims-hash-integrity
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)
        rel (rc/get-related-claims world' (:relationship-id result))]
    (is (some? (:relationship/hash rel)))
    (is (= (:relationship/hash rel)
           (rc/related-claims-hash (:relationship/members rel))))
    (let [hash1 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}])
          hash2 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}])]
      (is (= hash1 hash2)))
    (let [hash3 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 2 :claim/scope-hash "b"}])]
      (is (not= (:relationship/hash rel) hash3)))))

(deftest related-claims-members-exist-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-members-exist? world'))))))

(deftest related-claims-no-duplicate-members-invariant
  (let [w (world-with-escrows 4)
        r1 (rc/create-related-claims! w
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]
               :reason "test"})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-counterparty
               :members [{:claim/kind :sew/workflow :workflow/id 2}
                         {:claim/kind :sew/workflow :workflow/id 3}]
               :reason "test2"})
        world'' (:world r2)]
    (is (true? (:holds? (inv/related-claims-no-duplicate-members? world''))))))

(deftest related-claims-hash-matches-members-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-hash-matches-members? world'))))))

(deftest related-claims-do-not-block-finality-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-do-not-block-finality? world'))))))

(deftest all-related-claims-invariants-pass-in-check-all
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)
        check (inv/check-all world')]
    (is (true? (get-in check [:results :related-claims-members-exist :holds?])))
    (is (true? (get-in check [:results :related-claims-no-duplicate-members :holds?])))
    (is (true? (get-in check [:results :related-claims-hash-matches-members :holds?])))
    (is (true? (get-in check [:results :related-claims-do-not-block-finality :holds?])))
    (is (true? (get-in check [:results :related-claims-authorisation-scope-closed :holds?])))))

(deftest related-claims-active-after-creation
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)
        rel-id (:relationship-id result)]
    (is (true? (rc/related-claims-active? world' rel-id)))
    (is (not (rc/related-claims-active? world' 99)))))

(deftest related-claims-allowed-types
  (let [w (world-with-escrows 1)
        result (rc/create-related-claims! w
                 {:type :nonexistent-type
                  :members [{:claim/kind :sew/workflow :workflow/id 0}]
                  :reason "test"})]
    (is (false? (:ok result)))))

(deftest related-claims-authorisation-scope-closed-invariant-vacuous
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-authorisation-scope-closed? world'))))))

(deftest related-claims-authorisation-scope-closed-invariant-with-auth
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"})
        world' (:world result)
        rel-id (:relationship-id result)
        rel (rc/get-related-claims world' rel-id)
        auth-id "fa-rel-test"
        world'' (assoc-in world' [:force-authorisations/consumed auth-id]
                          {:consumed? true
                           :authorization/id auth-id
                           :authorization/type :force-authorisation
                           :authorization/scope-kind :related-claims
                           :authorization/scope-hash "test-scope-hash"
                           :relationship/id rel-id
                           :relationship/hash (:relationship/hash rel)
                           :member-scope-hashes ["hash1" "hash2"]
                           :member-count 1
                           :consumed-members #{"hash1"}})]
    (is (true? (:holds? (inv/related-claims-authorisation-scope-closed? world''))))))

;; ---------------------------------------------------------------------------
;; Workflow-group delegation / equivalence
;; ---------------------------------------------------------------------------

(deftest related-claims-member-hash-delegates-to-workflow-group
  (testing "related-claims-member-hash is the workflow-group member hash of the projected member"
    (let [member {:claim/kind :sew/workflow :workflow/id 5}]
      (is (= (rc/related-claims-member-hash member)
             (wg/workflow-group-member-hash (wg/workflow-group-member :sew/workflow 5)))))))

(deftest relationship-member-delegates-to-canonical-predicate
  (testing "relationship-member? matches the canonical workflow-group predicate"
    (let [relationship {:relationship/members
                        [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]}
          present {:claim/kind :sew/workflow :workflow/id 1}
          absent  {:claim/kind :sew/workflow :workflow/id 9}
          projected (map (fn [m] (wg/workflow-group-member (:claim/kind m) (:workflow/id m)))
                         (:relationship/members relationship))]
      (is (= (rc/relationship-member? relationship present)
             (wg/workflow-group-member? projected (wg/workflow-group-member :sew/workflow 1))))
      (is (= (rc/relationship-member? relationship absent)
             (wg/workflow-group-member? projected (wg/workflow-group-member :sew/workflow 9))))
      (is (true? (rc/relationship-member? relationship present)))
      (is (false? (rc/relationship-member? relationship absent))))))

(deftest related-claims-member-hash-domain-is-workflow-group-domain
  (testing "related-claims member hashes now use the WORKFLOW_GROUP_MEMBER_V1 domain, not the legacy related-claims-member domain"
    (let [member {:claim/kind :sew/workflow :workflow/id 0}
          wg-hash (wg/workflow-group-member-hash (wg/workflow-group-member :sew/workflow 0))
          legacy-hash (hash/domain-hash "related-claims-member" {:claim/kind :sew/workflow :workflow/id 0})]
      (is (= (rc/related-claims-member-hash member) wg-hash))
      (is (not= (rc/related-claims-member-hash member) legacy-hash)
          "domain separation from the legacy related-claims-member domain is intentional"))))
