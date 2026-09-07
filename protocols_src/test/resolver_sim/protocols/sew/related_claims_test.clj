(ns resolver-sim.protocols.sew.related-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.accounting :as acct]
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

(defn- rc-apply [ctx world ev]
  (sew/apply-action ctx world ev))
(def test-creator {:actor/type :test :actor/address "0xGov"})

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
      (is (= rc/related-claims-version-v3 (:related-claims/version rel)))
      (is (some? (:relationship/hash rel)))
      (is (= 1 (get world' :next-related-claim-id 0))))))

(deftest create-related-claims-members-exist-validation
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 99}]
               :created-by test-creator})]
    (is (false? (:ok result)))
    (is (= :invalid-related-claims (:error result)))))

(deftest create-related-claims-no-duplicate-within
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 0}]
               :created-by test-creator})]
    (is (false? (:ok result)))))

(deftest create-related-claims-no-duplicate-across-relationships
  (let [w (world-with-escrows 3)
        r1 (rc/create-related-claims! w
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]
               :created-by test-creator})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 1}
                         {:claim/kind :sew/workflow :workflow/id 2}]
               :created-by test-creator})]
    (is (false? (:ok r2)))))

(deftest create-related-claims-no-duplicate-across-types
  (testing "a workflow cannot enter a second active relationship even of a different type"
    (let [w (world-with-escrows 3)
          r1 (rc/create-related-claims! w
                {:type :same-incident
                 :members [{:claim/kind :sew/workflow :workflow/id 0}
                           {:claim/kind :sew/workflow :workflow/id 1}]
               :created-by test-creator})
          world' (:world r1)
          r2 (rc/create-related-claims! world'
                {:type :same-counterparty
                 :members [{:claim/kind :sew/workflow :workflow/id 1}
                           {:claim/kind :sew/workflow :workflow/id 2}]
               :created-by test-creator})]
      (is (false? (:ok r2))
          "workflow 1 already belongs to an active relationship"))))

(deftest create-related-claims-rejects-empty-members
  (testing "empty membership is rejected"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members []
                    :reason "test"
               :created-by test-creator})]
      (is (false? (:ok result)))
      (is (= :invalid-related-claims (:error result))))))

(deftest create-related-claims-rejects-unknown-semantics
  (testing "an unknown semantics keyword is rejected"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :semantics #{:batch-force-authorisation}
                    :reason "test"
               :created-by test-creator})]
      (is (false? (:ok result)))
      (is (= :invalid-related-claims (:error result))))))

(deftest create-related-claims-rejects-non-audit-only-semantics
  (testing "any semantics other than exactly #{:audit-only} is rejected in v1"
    (let [w (world-with-escrows 1)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :semantics #{:audit-only :cross-claim-guarantee}
                    :reason "test"
               :created-by test-creator})]
      (is (false? (:ok result))))))

(deftest find-related-claims-for-workflow
  (let [w (world-with-escrows 3)]
    (is (empty? (rc/find-related-claims-for-workflow w 0)))
    (let [r1 (rc/create-related-claims! w
                {:type :same-incident
                 :members [{:claim/kind :sew/workflow :workflow/id 0}
                           {:claim/kind :sew/workflow :workflow/id 1}]
                 :reason "test"
               :created-by test-creator})
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
               :reason "test"
               :created-by test-creator})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-counterparty
               :members [{:claim/kind :sew/workflow :workflow/id 2}
                         {:claim/kind :sew/workflow :workflow/id 3}]
               :reason "test2"
               :created-by test-creator})
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
                  :reason "test"
                  :created-by test-creator})
        world' (:world result)
        rel (rc/get-related-claims world' (:relationship-id result))]
    (is (some? (:relationship/hash rel)))
    (is (= (:relationship/hash rel)
           (rc/related-claims-hash (:relationship/members rel)
                                   (:relationship/creator-provenance rel)))
        "V3 hash commits members, creator provenance, and default semantics")
    (let [hash1 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}]
                 {:actor/address "0xGov"})
          hash2 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}]
                 {:actor/address "0xGov"})]
      (is (= hash1 hash2) "V3 hash is deterministic"))
    (let [hash3 (rc/related-claims-hash
                 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                  {:claim/kind :sew/workflow :workflow/id 2 :claim/scope-hash "b"}]
                 {:actor/address "0xGov"})]
      (is (not= (:relationship/hash rel) hash3) "V3 hash changes when members change"))))

(deftest related-claims-hash-version-boundary
  (let [members [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}]
        provenance {:actor/address "0xGov"}]
    (testing "creator provenance changes both V2 and V3 commitments"
      (is (not= (rc/related-claims-hash-v2 members provenance)
                (rc/related-claims-hash-v2 members {:actor/address "0xOther"})))
      (is (not= (rc/related-claims-hash-v3 members provenance #{:audit-only})
                (rc/related-claims-hash-v3 members {:actor/address "0xOther"}
                                           #{:audit-only}))))
    (testing "V2 deliberately excludes semantics while V3 commits it"
      (let [v2-record {:related-claims/version rc/related-claims-version-v2
                       :relationship/members members
                       :relationship/semantics #{:audit-only}
                       :relationship/creator-provenance provenance
                       :relationship/hash (rc/related-claims-hash-v2 members provenance)}]
        (is (:valid? (rc/verify-related-claims-hash
                      (assoc v2-record :relationship/semantics #{:shared-evidence}))))
        (is (not= (rc/related-claims-hash-v3 members provenance #{:audit-only})
                  (rc/related-claims-hash-v3 members provenance #{:shared-evidence})))))
    (testing "V1, V2, and V3 are domain-separated"
      (let [v1 (rc/related-claims-hash-v1 members)
            v2 (rc/related-claims-hash-v2 members provenance)
            v3 (rc/related-claims-hash-v3 members provenance #{:audit-only})]
        (is (not= v1 v2))
        (is (not= v2 v3))
        (is (= v3 (rc/related-claims-hash members provenance)))))))

(deftest related-claims-historical-hash-migration-and-authentication-boundary
  (let [members [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}]
        provenance {:actor/type :governance :actor/address gov}
        v1 {:related-claims/version rc/related-claims-version
            :relationship/members members
            :relationship/hash (rc/related-claims-hash-v1 members)}
        v2 {:related-claims/version rc/related-claims-version-v2
            :relationship/members members
            :relationship/semantics #{:shared-evidence}
            :relationship/creator-provenance provenance
            :relationship/hash (rc/related-claims-hash-v2 members provenance)}
        ctx {:governance-identity gov}]
    (testing "historical artifacts remain hash-verifiable under their own contracts"
      (is (:valid? (rc/verify-related-claims-hash v1)))
      (is (:valid? (rc/verify-related-claims-hash v2)))
      (is (:valid? (rc/verify-related-claims-hash
                    (assoc v2 :relationship/semantics #{:audit-only})))
          "V2 semantics are intentionally not part of its historical preimage")
      (is (contains? (:reasons (rc/verify-related-claims-hash
                                (assoc v2 :relationship/hash
                                       (rc/related-claims-hash-v3 members provenance
                                                                  #{:shared-evidence}))))
                     :relationship-hash-mismatch)
          "the accidental V2-with-semantics preimage is not reinterpreted as V2"))
    (testing "historical records cannot be upgraded into strict authentication"
      (doseq [record [v1 (assoc v2 :relationship/authenticated? true
                                    :relationship/assurance :address-bound)]]
        (is (false? (rc/authenticated-related-claims? ctx record)))
        (is (contains? (:reasons (rc/verify-authenticated-related-claims ctx record))
                       :unsupported-relationship-version))))
    (testing "a V3 hash rejects semantic mutation"
      (let [v3 {:related-claims/version rc/related-claims-version-v3
                :relationship/members members
                :relationship/semantics #{:audit-only}
                :relationship/creator-provenance provenance
                :relationship/hash (rc/related-claims-hash-v3 members provenance #{:audit-only})}]
        (is (:valid? (rc/verify-related-claims-hash v3)))
        (is (false? (:valid? (rc/verify-related-claims-hash
                               (assoc v3 :relationship/semantics #{:shared-evidence})))))))))

(deftest related-claims-members-exist-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-members-exist? world'))))))

(deftest related-claims-no-duplicate-members-invariant
  (let [w (world-with-escrows 4)
        r1 (rc/create-related-claims! w
              {:type :same-incident
               :members [{:claim/kind :sew/workflow :workflow/id 0}
                         {:claim/kind :sew/workflow :workflow/id 1}]
               :reason "test"
               :created-by test-creator})
        world' (:world r1)
        r2 (rc/create-related-claims! world'
              {:type :same-counterparty
               :members [{:claim/kind :sew/workflow :workflow/id 2}
                         {:claim/kind :sew/workflow :workflow/id 3}]
               :reason "test2"
               :created-by test-creator})
        world'' (:world r2)]
    (is (true? (:holds? (inv/related-claims-no-duplicate-members? world''))))))

(deftest related-claims-hash-matches-members-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-hash-matches-members? world'))))
    (let [corrupted-world (assoc-in world'
                                    [:related-claims (:relationship-id result) :relationship/hash]
                                    "corrupted-hash")
          violation (first (:violations
                            (inv/related-claims-hash-matches-members? corrupted-world)))]
      (is (= {:relationship/id (:relationship-id result)
              :reasons #{:relationship-hash-mismatch}
              :actual "corrupted-hash"
              :members (get-in world' [:related-claims (:relationship-id result)
                                       :relationship/members])}
             violation)))))

(deftest authenticated-related-claims-builder-is-not-public
  (is (not (contains? (ns-publics 'resolver-sim.protocols.sew.related-claims)
                      'create-related-claims-with-assurance!))
      "only the governance action may invoke the authenticated builder"))

(deftest related-claims-do-not-block-finality-invariant
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-do-not-block-finality? world'))))))

(deftest all-related-claims-invariants-pass-in-check-all
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
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
                  :reason "test"
               :created-by test-creator})
        world' (:world result)
        rel-id (:relationship-id result)]
    (is (true? (rc/related-claims-active? world' rel-id)))
    (is (not (rc/related-claims-active? world' 99)))))

(deftest related-claims-allowed-types
  (let [w (world-with-escrows 1)
        result (rc/create-related-claims! w
                 {:type :nonexistent-type
                  :members [{:claim/kind :sew/workflow :workflow/id 0}]
                  :reason "test"
               :created-by test-creator})]
    (is (false? (:ok result)))))

(deftest related-claims-authorisation-scope-closed-invariant-vacuous
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
        world' (:world result)]
    (is (true? (:holds? (inv/related-claims-authorisation-scope-closed? world'))))))

(deftest related-claims-authorisation-scope-closed-invariant-with-auth
  (let [w (world-with-escrows 2)
        result (rc/create-related-claims! w
                 {:type :same-incident
                  :members [{:claim/kind :sew/workflow :workflow/id 0}
                            {:claim/kind :sew/workflow :workflow/id 1}]
                  :reason "test"
               :created-by test-creator})
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
    (is (false? (:holds? (inv/related-claims-authorisation-scope-closed? world'')))
        "a consumed entry without its grant, linked adjustment, and immutable record is not scope-closed")))

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

;; ---------------------------------------------------------------------------
;; Authenticated related-claims (governance action) / authentication boundary
;; ---------------------------------------------------------------------------

(deftest grant-related-claims-action-derives-authenticated-creator
  (testing "the governance-gated grant-related-claims action derives creator identity from the authenticated actor"
    (let [w (world-with-escrows 2)
          gov-ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
                   :governance-identity "0xGov"}
          ev {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1] :reason "audit"}}
          r (rc-apply gov-ctx w ev)]
      (is (:ok r))
      (let [rec (get-in r [:world :related-claims (:relationship-id r)])]
        (is (true? (:relationship/authenticated? rec)))
        (is (= "0xGov" (get-in rec [:relationship/creator-provenance :actor/address])))
        (is (some? (:relationship/hash rec)))
        (is (rc/authenticated-related-claims? gov-ctx rec))
        (is (false? (rc/authenticated-related-claims? rec))
            "the compatibility arity fails closed without governance context")))))

(deftest grant-related-claims-rejects-non-governance
  (testing "grant-related-claims by a non-governance actor is rejected"
    (let [w (world-with-escrows 2)
          mallory-ctx {:agent-index {"mallory" {:id "mallory" :address "0xMallory" :type "honest"}}
                       :governance-identity "0xGov"}
          ev {:seq 0 :time 1000 :agent "mallory" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1]}}
          r (rc-apply mallory-ctx w ev)]
      (is (= :not-governance (:error r))))))

(deftest grant-related-claims-ignores-caller-created-by
  (testing "caller-supplied :created-by on the action path cannot override the authenticated identity"
    (let [w (world-with-escrows 2)
          gov-ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
                   :governance-identity "0xGov"}
          ev {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1]
                       :created-by {:actor/type :governance :actor/address "0xForged"}}}
          r (rc-apply gov-ctx w ev)]
      (is (:ok r))
      (let [rec (get-in r [:world :related-claims (:relationship-id r)])]
        (is (= "0xGov" (get-in rec [:relationship/creator-provenance :actor/address]))
            "creator provenance is the authenticated governance actor, not the forged address")))))

(deftest create-related-claims-requires-explicit-creator
  (testing "direct builder rejects a missing creator (no hardcoded default)"
    (let [w (world-with-escrows 2)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}
                              {:claim/kind :sew/workflow :workflow/id 1}]
                    :reason "test"})]
      (is (false? (:ok result)))
      (is (= :invalid-related-claims (:error result))))))

(deftest unauthenticated-direct-builder-not-authenticated
  (testing "a direct-builder V3 record is unconditionally unauthenticated"
    (let [w (world-with-escrows 2)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :created-by test-creator})
          rec (get-in result [:world :related-claims (:relationship-id result)])]
      (is (:ok result))
      (is (= :unauthenticated (:relationship/assurance rec)))
      (is (false? (:relationship/authenticated? rec)))
      (is (false? (rc/authenticated-related-claims? rec))
          "unauthenticated record with creator metadata is NOT authenticated"))))

(deftest create-related-claims-rejects-auth-overrides
  (testing "direct construction rejects any caller-supplied authentication/assurance override"
    (let [w (world-with-escrows 2)]
      (is (false? (:ok (rc/create-related-claims! w
                        {:type :same-incident
                         :members [{:claim/kind :sew/workflow :workflow/id 0}]
                         :created-by test-creator
                         :authenticated? true}))))
      (is (false? (:ok (rc/create-related-claims! w
                        {:type :same-incident
                         :members [{:claim/kind :sew/workflow :workflow/id 0}]
                         :created-by test-creator
                         :assurance :address-bound})))))))

(deftest v1-artifact-cannot-be-authenticated-by-attached-metadata
  (testing "a V1-hashed artifact cannot acquire authenticated status via uncommitted creator metadata"
    (let [members [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}]
          v1-hash (rc/related-claims-hash-v1 members)
          v2-hash (rc/related-claims-hash members {:actor/address "0xGov"})
          v1-record {:relationship/authenticated? true
                     :relationship/creator-provenance {:actor/address "0xGov"}
                     :relationship/hash v1-hash}]
      (is (not= v1-hash v2-hash))
      (is (not= (:relationship/hash v1-record)
                (rc/related-claims-hash (:relationship/members
                                          {:relationship/members members})
                                        (:relationship/creator-provenance v1-record)))
          "attaching creator metadata outside the V1 hash cannot make it an authenticated V2 record"))))

;; ---------------------------------------------------------------------------
;; Assurance classification: restricted / legacy / open / direct
;; ---------------------------------------------------------------------------

(deftest related-claims-assurance-by-mode
  (testing "restricted/address-bound action produces the strict authenticated record"
    (let [w (world-with-escrows 2)
          ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
               :governance-identity "0xGov"}
          ev {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1]}}
          r (rc-apply ctx w ev)
          rec (get-in r [:world :related-claims (:relationship-id r)])]
      (is (= :address-bound (:relationship/assurance rec)))
      (is (true? (:relationship/authenticated? rec)))
      (is (rc/authenticated-related-claims? ctx rec))))
  (testing "explicit legacy mode produces a role-declared (weaker) record that does NOT satisfy the strict predicate"
    (let [w (world-with-escrows 2)
          ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
               :governance-identity "0xGov" :governance-mode :legacy}
          ev {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1]}}
          r (rc-apply ctx w ev)
          rec (get-in r [:world :related-claims (:relationship-id r)])]
      (is (= :role-declared (:relationship/assurance rec)))
      (is (false? (:relationship/authenticated? rec)))
      (is (false? (rc/authenticated-related-claims? ctx rec))
          "legacy role-only must NOT satisfy the strict authenticated predicate")))
  (testing "explicit open mode produces an open record that does NOT satisfy the strict predicate"
    (let [w (world-with-escrows 2)
          ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
               :governance-identity "0xGov" :governance-mode :open}
          ev {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
              :params {:type :same-incident :workflow-ids [0 1]}}
          r (rc-apply ctx w ev)
          rec (get-in r [:world :related-claims (:relationship-id r)])]
      (is (= :open (:relationship/assurance rec)))
      (is (false? (rc/authenticated-related-claims? ctx rec))))))

(deftest authenticated-related-claims-verifies-hash-bound-provenance
  (let [w (world-with-escrows 2)
        ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
             :governance-identity "0xGov"}
        event {:seq 0 :time 1000 :agent "gov" :action "grant-related-claims"
               :params {:type :same-incident :workflow-ids [0 1] :reason "audit"}}
        result (rc-apply ctx w event)
        record (get-in result [:world :related-claims (:relationship-id result)])]
    (testing "a restricted governance record verifies with its originating context"
      (is (:valid? (rc/verify-authenticated-related-claims ctx record)))
      (is (rc/authenticated-related-claims? ctx record)))
    (testing "forged top-level and nested assurance labels do not authenticate"
      (let [forged-top (assoc record :relationship/assurance :open)
            forged-nested (assoc-in record
                                    [:relationship/creator-provenance
                                     :authorization/provenance
                                     :authorization/assurance]
                                    :role-declared)]
        (is (false? (rc/authenticated-related-claims? ctx forged-top)))
        (is (contains? (:reasons (rc/verify-authenticated-related-claims ctx forged-top))
                       :relationship-assurance-not-address-bound))
        (is (false? (rc/authenticated-related-claims? ctx forged-nested)))
        (is (contains? (:reasons (rc/verify-authenticated-related-claims ctx forged-nested))
                       :relationship-hash-mismatch))))
    (testing "member, semantics, and provenance edits leave a stale relationship hash"
      (doseq [stale [(update record :relationship/members
                            (fn [members]
                              (assoc (vec members) 1
                                     (assoc (second members) :workflow/id 99))))
                     (assoc record :relationship/semantics #{:batch-force-authorisation})
                     (assoc-in record [:relationship/creator-provenance :actor/address] "0xMallory")]]
        (is (false? (rc/authenticated-related-claims? ctx stale)))
        (is (contains? (:reasons (rc/verify-authenticated-related-claims ctx stale))
                       :relationship-hash-mismatch))))
    (testing "the supplied context must bind the creator to its active configured address"
      (let [wrong-context (assoc ctx :governance-identity "0xOther")]
        (is (false? (rc/authenticated-related-claims? wrong-context record)))
        (is (contains? (:reasons (rc/verify-authenticated-related-claims wrong-context record))
                       :creator-address-mismatch))))))

(deftest attached-auth-flag-cannot-upgrade-uncommitted-record
  (testing "changing/attaching an authentication flag outside the committed provenance cannot upgrade an artifact"
    (let [w (world-with-escrows 2)
          result (rc/create-related-claims! w
                   {:type :same-incident
                    :members [{:claim/kind :sew/workflow :workflow/id 0}]
                    :created-by test-creator})
          rec (get-in result [:world :related-claims (:relationship-id result)])
          upgraded (assoc rec :relationship/assurance :address-bound
                               :relationship/authenticated? true)]
      (is (false? (rc/authenticated-related-claims? upgraded))
          "a manually attached assurance flag cannot satisfy the strict predicate (creator provenance is not address-bound)")
      (is (= (:relationship/hash rec) (:relationship/hash upgraded))
          "attaching the flag does not alter the committed hash — it is outside the committed provenance, so it cannot upgrade the artifact"))))

;; ============================================================================
;; P0 — Related-claims regression tests for historical hash-version invariant
;;
;; These tests protect against later implementation changes silently
;; invalidating historical semantics. They cover:
;;   - V1/V2/V3 positive cases (historical replay)
;;   - Historical body/version replay
;;   - Wrong-version/body/root combinations
;;   - Invariant-verification regressions for the version-awareness bug
;; ============================================================================

(def members-a [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}])
(def members-b [{:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}])
(def provenance-gov {:actor/type :governance :actor/address gov})
(def provenance-other {:actor/address "0xOther"})

;; ── V1/V2/V3 positive cases ──────────────────────────────────────────────

(deftest p0-related-claims-v1-positive-case
  (testing "V1 artifact: members-only hash, no provenance or semantics"
    (let [v1 {:related-claims/version rc/related-claims-version
              :relationship/members members-a
              :relationship/hash (rc/related-claims-hash-v1 members-a)
              :relationship/status :active}]
      (is (:valid? (rc/verify-related-claims-hash v1)))
      (is (true? (:holds? (inv/related-claims-hash-matches-members? {:related-claims {0 v1}})))))))

(deftest p0-related-claims-v2-positive-case
  (testing "V2 artifact: members + creator provenance, no semantics in hash"
    (let [v2 {:related-claims/version rc/related-claims-version-v2
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash (rc/related-claims-hash-v2 members-a provenance-gov)
              :relationship/status :active}]
      (is (:valid? (rc/verify-related-claims-hash v2)))
      (is (true? (:holds? (inv/related-claims-hash-matches-members? {:related-claims {0 v2}})))))))

(deftest p0-related-claims-v3-positive-case
  (testing "V3 artifact: members + creator provenance + semantics"
    (let [v3 {:related-claims/version rc/related-claims-version-v3
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
              :relationship/status :active}]
      (is (:valid? (rc/verify-related-claims-hash v3)))
      (is (true? (:holds? (inv/related-claims-hash-matches-members? {:related-claims {0 v3}})))))))

;; ── Historical body/version replay ────────────────────────────────────────

(deftest p0-related-claims-historical-replay-v1
  (testing "V1 historical replay: same members always produces same hash"
    (let [h1 (rc/related-claims-hash-v1 members-a)
          h2 (rc/related-claims-hash-v1 members-a)]
      (is (= h1 h2) "V1 hash is deterministic"))
    (let [h1 (rc/related-claims-hash-v1 members-a)
          h2 (rc/related-claims-hash-v1 members-b)]
      (is (not= h1 h2) "V1 hash changes when members change"))))

(deftest p0-related-claims-historical-replay-v2
  (testing "V2 historical replay: members + provenance determine hash"
    (let [h1 (rc/related-claims-hash-v2 members-a provenance-gov)
          h2 (rc/related-claims-hash-v2 members-a provenance-gov)]
      (is (= h1 h2) "V2 hash is deterministic"))
    (let [h1 (rc/related-claims-hash-v2 members-a provenance-gov)
          h2 (rc/related-claims-hash-v2 members-a provenance-other)]
      (is (not= h1 h2) "V2 hash changes when creator provenance changes"))
    (let [h1 (rc/related-claims-hash-v2 members-a provenance-gov)
          h2 (rc/related-claims-hash-v2 members-b provenance-gov)]
      (is (not= h1 h2) "V2 hash changes when members change"))))

(deftest p0-related-claims-historical-replay-v3
  (testing "V3 historical replay: members + provenance + semantics determine hash"
    (let [h1 (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
          h2 (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})]
      (is (= h1 h2) "V3 hash is deterministic"))
    (let [h1 (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
          h2 (rc/related-claims-hash-v3 members-a provenance-other #{:audit-only})]
      (is (not= h1 h2) "V3 hash changes when creator provenance changes"))
    (let [h1 (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
          h2 (rc/related-claims-hash-v3 members-b provenance-gov #{:audit-only})]
      (is (not= h1 h2) "V3 hash changes when members change"))
    (let [h1 (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
          h2 (rc/related-claims-hash-v3 members-a provenance-gov #{:shared-evidence})]
      (is (not= h1 h2) "V3 hash changes when semantics change"))))

;; ── Wrong-version/body/root combinations ──────────────────────────────────

(deftest p0-related-claims-wrong-version-combinations
  (testing "V1 hash declared as V2 fails"
    (let [rel {:related-claims/version rc/related-claims-version-v2
               :relationship/members members-a
               :relationship/hash (rc/related-claims-hash-v1 members-a)
               :relationship/status :active}]
      (is (false? (:valid? (rc/verify-related-claims-hash rel))))
      (is (contains? (:reasons (rc/verify-related-claims-hash rel)) :relationship-hash-mismatch))))
  (testing "V2 hash declared as V1 fails"
    (let [rel {:related-claims/version rc/related-claims-version
               :relationship/members members-a
               :relationship/hash (rc/related-claims-hash-v2 members-a provenance-gov)
               :relationship/status :active}]
      (is (false? (:valid? (rc/verify-related-claims-hash rel))))
      (is (contains? (:reasons (rc/verify-related-claims-hash rel)) :relationship-hash-mismatch))))
  (testing "V3 hash declared as V2 fails"
    (let [rel {:related-claims/version rc/related-claims-version-v2
               :relationship/members members-a
               :relationship/semantics #{:audit-only}
               :relationship/creator-provenance provenance-gov
               :relationship/hash (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
               :relationship/status :active}]
      (is (false? (:valid? (rc/verify-related-claims-hash rel))))
      (is (contains? (:reasons (rc/verify-related-claims-hash rel)) :relationship-hash-mismatch)))))

(deftest p0-related-claims-version-mutation-without-commitment-change
  (testing "changing version without changing hash fails"
    (let [v3 {:related-claims/version rc/related-claims-version-v3
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
              :relationship/status :active}
          ;; Change version to V2 but keep V3 hash
          v2-with-v3-hash (assoc v3 :related-claims/version rc/related-claims-version-v2)]
      (is (false? (:valid? (rc/verify-related-claims-hash v2-with-v3-hash))))
      (is (contains? (:reasons (rc/verify-related-claims-hash v2-with-v3-hash)) :relationship-hash-mismatch)))))

(deftest p0-related-claims-unknown-version-fails-closed
  (testing "unknown future version fails closed with :unsupported-relationship-version"
    (let [rel {:related-claims/version 99
               :relationship/members members-a
               :relationship/hash (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
               :relationship/status :active}]
      (is (false? (:valid? (rc/verify-related-claims-hash rel))))
      (is (contains? (:reasons (rc/verify-related-claims-hash rel)) :unsupported-relationship-version)))))

;; ── Invariant-verification regressions ────────────────────────────────────

(deftest p0-related-claims-invariant-version-aware-regression
  (testing "invariant uses version-aware verification, not V3-only reconstruction"
    ;; This is the regression test for the bug fix: the invariant must delegate
    ;; to verify-related-claims-hash rather than reimplementing V3-only hash.
    (let [v1 {:related-claims/version rc/related-claims-version
              :relationship/members members-a
              :relationship/hash (rc/related-claims-hash-v1 members-a)
              :relationship/status :active}
          v2 {:related-claims/version rc/related-claims-version-v2
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash (rc/related-claims-hash-v2 members-a provenance-gov)
              :relationship/status :active}
          v3 {:related-claims/version rc/related-claims-version-v3
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash (rc/related-claims-hash-v3 members-a provenance-gov #{:audit-only})
              :relationship/status :active}
          world {:related-claims {0 v1 1 v2 2 v3}}]
      (is (true? (:holds? (inv/related-claims-hash-matches-members? world)))
          "all versions survive invariant when hashes match their version contract"))))

(deftest p0-related-claims-invariant-detects-all-tamered-versions
  (testing "invariant detects tampering across all versions"
    (let [v1 {:related-claims/version rc/related-claims-version
              :relationship/members members-a
              :relationship/hash "tampered"
              :relationship/status :active}
          v2 {:related-claims/version rc/related-claims-version-v2
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash "tampered"
              :relationship/status :active}
          v3 {:related-claims/version rc/related-claims-version-v3
              :relationship/members members-a
              :relationship/semantics #{:audit-only}
              :relationship/creator-provenance provenance-gov
              :relationship/hash "tampered"
              :relationship/status :active}
          world {:related-claims {0 v1 1 v2 2 v3}}]
      (is (false? (:holds? (inv/related-claims-hash-matches-members? world))))
      (is (= 3 (count (:violations (inv/related-claims-hash-matches-members? world))))))))

;; ── Member identity independence ──────────────────────────────────────────

(deftest p0-related-claims-member-identity-independent-of-semantics
  (testing "member identity is determined by claim/kind + workflow/id, not scope-hash"
    (let [m1 {:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "hash-a"}
          m2 {:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "hash-b"}
          m3 {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "hash-a"}]
      (is (= (rc/related-claims-member-hash m1) (rc/related-claims-member-hash m2))
          "same claim/kind + workflow/id = same member identity regardless of scope-hash")
      (is (not= (rc/related-claims-member-hash m1) (rc/related-claims-member-hash m3))
          "different workflow/id = different member identity"))))

(deftest p0-related-claims-member-identity-reordering-invariant
  (testing "V1 hash is invariant under member reordering"
    (let [members-1 [{:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}
                     {:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}]
          members-2 [{:claim/kind :sew/workflow :workflow/id 1 :claim/scope-hash "b"}
                     {:claim/kind :sew/workflow :workflow/id 0 :claim/scope-hash "a"}]]
      (is (= (rc/related-claims-hash-v1 members-1) (rc/related-claims-hash-v1 members-2))
          "V1 hash is sorted by (workflow/id, claim/kind) — reordering is invariant"))))

;; ============================================================================
;; P1 — Related-claims lifecycle: audit-only + force-auth consumption closure
;;
;; End-to-end example showing:
;;   workflow A ─┐
;;               ├─ related-claims R (:audit-only)
;;   workflow B ─┘
;;
;;   force-authorisation F
;;          ↓
;;   operation on A
;;          ↓
;;   consumption record commits:
;;     adjustment-specific scope, member(A), relationship R
;;          ↓
;;   world invariant proves member(A) ∈ R
;;
;;   Then explicitly show that:
;;   B remains independently settleable/finalizable
;;   because R is :audit-only.
;; ============================================================================

(deftest p1-related-claims-lifecycle-audit-only-force-auth-closure
  (testing "full lifecycle: related-claims → audit-only → force-auth → consumption → closure"
    (let [;; ── Step 1: Create two workflows A and B ──
          w0 (world-with-escrows 2)
          w1 (lc/create-escrow w0 alice usdc bob 2000 (t/make-escrow-settings {}) snap)
          world-after-escrows (:world w1)
          wf-a 0
          wf-b 1

          ;; ── Step 2: Create related-claims R (:audit-only) linking A and B ──
          rc-result (rc/create-related-claims! world-after-escrows
                       {:type :same-incident
                        :members [{:claim/kind :sew/workflow :workflow/id wf-a}
                                  {:claim/kind :sew/workflow :workflow/id wf-b}]
                        :reason "same incident test"
                        :created-by test-creator})
          world-after-rc (:world rc-result)
          rel-id (:relationship-id rc-result)
          rel (rc/get-related-claims world-after-rc rel-id)

          ;; Verify R is :audit-only
          _ (is (= #{:audit-only} (:relationship/semantics rel))
                "relationship R has :audit-only semantics")

          ;; ── Step 3: Compute stable member identities ──
          member-a (rc/related-claims-member-hash {:claim/kind :sew/workflow :workflow/id wf-a})
          member-b (rc/related-claims-member-hash {:claim/kind :sew/workflow :workflow/id wf-b})

          ;; ── Step 4: Verify member(A) ∈ R and member(B) ∈ R (membership proof) ──
          member-idents (set (map rc/related-claims-member-hash (:relationship/members rel)))
          _ (is (contains? member-idents member-a) "member(A) is in relationship R")
          _ (is (contains? member-idents member-b) "member(B) is in relationship R")

          ;; ── Step 5: Create force-authorisation F for operation on A ──
          ;; The grant commits to the relationship and authorizes specific member scopes
          auth-id "fa-related-claims-test"
          w2 (-> world-after-rc
                 (assoc-in [:force-authorisations auth-id]
                           {:authorization/id auth-id
                            :authorization/type :force-authorisation
                            :authorization/status :active
                            :consumed? false
                            :starts-at 0
                            :authorization/scope-kind :related-claims
                            :relationship/id rel-id
                            :relationship/hash (:relationship/hash rel)
                            :member-scope-hashes [member-a member-b]
                            :authorization/scope member-a
                            :authorization/scope-hash member-a})
                 (assoc :next-force-authorisation-id 1))

          ;; ── Step 6: Simulate consumption record ──
          ;; After operation on A, consumption commits: scope, member(A), relationship R
          consumption-record {:consumed? true
                              :authorization/id auth-id
                              :authorization/type :force-authorisation
                              :authorization/scope-kind :related-claims
                              :authorization/scope-hash member-a
                              :relationship/id rel-id
                              :relationship/hash (:relationship/hash rel)
                              :member-scope-hashes [member-a member-b]
                              :member-count 1
                              :consumed-members #{member-a}}
          w3 (assoc-in w2 [:force-authorisations/consumed auth-id] consumption-record)

          ;; ── Step 7: Verify force-auth is now consumed (closure) ──
          consumed-auth (get-in w3 [:force-authorisations/consumed auth-id])
          _ (is (true? (:consumed? consumed-auth))
                "force-authorisation F is consumed after operation")
          _ (is (contains? (:consumed-members consumed-auth) member-a)
                "consumption record tracks member(A)")
          _ (is (= rel-id (:relationship/id consumed-auth))
                "consumption record references relationship R")

          ;; ── Step 8: B remains independently settleable (audit-only doesn't block) ──
          finality-check (inv/related-claims-do-not-block-finality? w3)
          _ (is (true? (:holds? finality-check))
                ":audit-only semantics do not block finality for B")

          ;; ── Step 9: B's escrow state is unaffected by R ──
          escrow-b (get-in w3 [:escrow-transfers wf-b])
          _ (is (not= :released (:escrow-state escrow-b))
                "workflow B remains in non-terminal state (not affected by R)")
          _ (is (not= :refunded (:escrow-state escrow-b))
                "workflow B not refunded (R is audit-only, no settlement coupling)")]

      ;; The full lifecycle: R created → F granted → A operated → F consumed → B unaffected
      (is (true? (:holds? (inv/related-claims-hash-matches-members? w3)))
          "related-claims invariant still holds after consumption"))))

(deftest p1-related-claims-member-identity-distinct-from-relationship
  (testing "related-claims-member identity is independent from relationship semantics"
    (let [;; Member identity is stable, framework-neutral
          m {:claim/kind :sew/workflow :workflow/id 0}
          member-id (rc/related-claims-member-hash m)

          ;; Same member in different relationships with different semantics
          rel-a {:related-claims/version rc/related-claims-version-v3
                 :relationship/members [m]
                 :relationship/semantics #{:audit-only}
                 :relationship/hash "hash-a"
                 :relationship/status :active}
          rel-b {:related-claims/version rc/related-claims-version-v3
                 :relationship/members [m]
                 :relationship/semantics #{:shared-evidence}
                 :relationship/hash "hash-b"
                 :relationship/status :active}

          ;; Member identity is the same regardless of which relationship it's in
          member-in-a (rc/related-claims-member-hash (first (:relationship/members rel-a)))
          member-in-b (rc/related-claims-member-hash (first (:relationship/members rel-b)))]
      (is (= member-id member-in-a member-in-b)
          "member identity is stable across relationships and semantics"))))

(deftest p1-related-claims-relationship-not-authorization
  (testing "related-claims relationship is not an authorization primitive"
    (let [w0 (world-with-escrows 2)
          rc-result (rc/create-related-claims! w0
                       {:type :same-incident
                        :members [{:claim/kind :sew/workflow :workflow/id 0}
                                  {:claim/kind :sew/workflow :workflow/id 1}]
                        :reason "test"
                        :created-by test-creator})
          world' (:world rc-result)
          rel-id (:relationship-id rc-result)
          rel (rc/get-related-claims world' rel-id)]

      ;; Relationship existence does not create force-authorisation
      (is (empty? (get world' :force-authorisations))
          "creating related-claims does not create force-authorisation")

      ;; Relationship is content-addressed, not executable
      (is (some? (:relationship/hash rel))
          "relationship has content-addressed hash")
      (is (not (contains? rel :authorization/id))
          "relationship does not carry authorization identity")

      ;; Relationship semantics are deliberately weak (:audit-only)
      (is (= #{:audit-only} (:relationship/semantics rel))
          "relationship semantics are :audit-only (not settlement/finality coupling)"))))

;; ---------------------------------------------------------------------------
;; Fix 6: Integration — related-claims × dispute × finalization
;; ---------------------------------------------------------------------------

(deftest dispute-on-related-claims-member-does-not-block-resolution
  (testing "raising a dispute on a related-claims member does not prevent resolution"
    (let [w (world-with-escrows 2)
          ;; Create related-claims linking workflows 0 and 1
          rc-full (rc/create-related-claims! w
                     {:type :same-incident
                      :members [{:claim/kind :sew/workflow :workflow/id 0}
                                {:claim/kind :sew/workflow :workflow/id 1}]
                      :reason "test"
                      :created-by test-creator})
          rel-id (:relationship-id rc-full)
          world' (:world rc-full)
          ;; Put workflow 0 into dispute
          w-disputed (-> world'
                         (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                         (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                         (assoc-in [:dispute-timestamps 0] 1000))
          ;; Submit evidence on workflow 0
          w-evidence (assoc-in w-disputed [:evidence-updated? 0] true)
          ;; Execute resolution (release workflow 0)
          w-resolved (-> w-evidence
                         (assoc-in [:escrow-transfers 0 :escrow-state] :released)
                         (update :amount-released assoc 0 950))
          rel (rc/get-related-claims w-resolved rel-id)]
      ;; Relationship is still active (workflow 1 is non-terminal)
      (is (= :active (:relationship/status rel))
          "relationship stays active while workflow 1 is non-terminal")
      ;; The invariant still holds
      (is (true? (:holds? (inv/related-claims-do-not-block-finality? w-resolved)))
          "audit-only semantics do not block finality after dispute resolution"))))

(deftest relationship-archives-after-all-members-finalize
  (testing "relationship transitions to :archived when all members are terminal"
    (let [w (world-with-escrows 2)
          rc-full (rc/create-related-claims! w
                     {:type :same-incident
                      :members [{:claim/kind :sew/workflow :workflow/id 0}
                                {:claim/kind :sew/workflow :workflow/id 1}]
                      :reason "test"
                      :created-by test-creator})
          rel-id (:relationship-id rc-full)
          world' (:world rc-full)
          ;; Finalize both workflows
          w-final (-> world'
                      (assoc-in [:escrow-transfers 0 :escrow-state] :released)
                      (assoc-in [:escrow-transfers 1 :escrow-state] :refunded))
          ;; Archive stale relationships
          w-archived (rc/archive-stale-relationships w-final)
          rel (rc/get-related-claims w-archived rel-id)]
      (is (= :archived (:relationship/status rel))
          "relationship archives when all members are terminal"))))

(deftest relationship-stays-active-while-some-members-non-terminal
  (testing "relationship stays active when at least one member is non-terminal"
    (let [w (world-with-escrows 2)
          rc-full (rc/create-related-claims! w
                     {:type :same-incident
                      :members [{:claim/kind :sew/workflow :workflow/id 0}
                                {:claim/kind :sew/workflow :workflow/id 1}]
                      :reason "test"
                      :created-by test-creator})
          rel-id (:relationship-id rc-full)
          world' (:world rc-full)
          ;; Finalize only workflow 0
          w-partial (-> world'
                        (assoc-in [:escrow-transfers 0 :escrow-state] :released))
          ;; Archive stale relationships
          w-archived (rc/archive-stale-relationships w-partial)
          rel (rc/get-related-claims w-archived rel-id)]
      (is (= :active (:relationship/status rel))
          "relationship stays active when workflow 1 is non-terminal"))))

(deftest finalized-member-can-join-new-relationship
  (testing "a terminal workflow can join a new relationship even if old one is active"
    (let [w (world-with-escrows 3)
          ;; Create R1 linking workflows 0 and 1
          rc1-full (rc/create-related-claims! w
                     {:type :same-incident
                      :members [{:claim/kind :sew/workflow :workflow/id 0}
                                {:claim/kind :sew/workflow :workflow/id 1}]
                      :reason "R1"
                      :created-by test-creator})
          world' (:world rc1-full)
          ;; Finalize workflow 0 (terminal)
          w-final (assoc-in world' [:escrow-transfers 0 :escrow-state] :released)
          ;; Create R2 linking terminal workflow 0 with new workflow 2
          ;; This should succeed because workflow 0 is terminal
          rc2-result (rc/create-related-claims! w-final
                       {:type :same-incident
                        :members [{:claim/kind :sew/workflow :workflow/id 0}
                                  {:claim/kind :sew/workflow :workflow/id 2}]
                        :reason "R2"
                        :created-by test-creator})]
      (is (:ok rc2-result)
          "terminal workflow 0 can join a new relationship R2"))))
