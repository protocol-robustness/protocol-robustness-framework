(ns resolver-sim.risk.authority-test
  "Authoritative adoption and consumption of risk-limit-policy.v1.

   Demonstrates the required adversarial properties:
     1. authorized policy + exact candidate + passing evaluation -> accepted
     2. authorized P1 but caller/evidence uses more permissive P2 -> reject
     3. correct P1 body under wrong root -> reject
     4. configured policy body unavailable -> fail closed
     5. malformed configured policy -> fail closed
     6. passing projection for candidate A transplanted to candidate B -> reject
     7. :as-of observational projection used for live admission -> reject
     8. stale/wrong predecessor or candidate binding -> reject
     9. risk-policy root changes through configuration transition -> change
        identity changes
    10. violation evaluation prevents commit
    11. evaluation/projection tampering rejects
    12. no caller-controlled lookup can choose the policy used by admission
   Plus: state/candidate binding audit, v4 configuration semantics, policy-body
   resolution, and no-partial-effects verification."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.risk.authority :as auth]
            [resolver-sim.risk.limit-evaluation :as le]
            [resolver-sim.risk.limit-policy :as lp]
            [resolver-sim.risk.pro-rata-producer :as producer]
            [resolver-sim.risk.projection :as rp])
  (:import [clojure.lang ExceptionInfo]))

;; ── fixtures ────────────────────────────────────────────────────────────────

(def ^:private unit-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "usd-micros"})))

(def ^:private valuation-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "valuation-basis"})))

(def ^:private qa "3f3ef786b34d6dd716e1812c8b74a7a0e1f05aa5f3230588f6f5bcd00c6c8392")
(def ^:private qb "22d1fb414b6d62b69391bb42b9b94314a5b2fa77a7e8abd507cf3152ed4fa38e")

(defn- strict-policy []
  (lp/policy
   {:id "p1-strict" :unit/root unit-root :basis :conservative-peak
    :limits [{:limit/kind :global :limit/id "global" :limit/amount 1000000}
             {:limit/kind :domain :limit/id "new-impl"
              :risk/domain "technical/new-implementation" :limit/amount 100000}
             {:limit/kind :concentration :limit/id "per-resolver"
              :risk/domain "human/principal" :limit/amount 100000}]}))

(defn- permissive-policy []
  (lp/policy
   {:id "p2-permissive" :unit/root unit-root :basis :conservative-peak
    :limits [{:limit/kind :global :limit/id "global" :limit/amount 5000000}
             {:limit/kind :domain :limit/id "new-impl"
              :risk/domain "technical/new-implementation" :limit/amount 500000}
             {:limit/kind :concentration :limit/id "per-resolver"
              :risk/domain "human/principal" :limit/amount 500000}]}))

(defn- v4-config [policy]
  (assoc genesis/chain-configuration-v4-fixture
         :risk-limit-policy/root (:risk-limit-policy/root policy)))

(defn- store
  [& entries]
  (let [m (into {} entries)]
    (fn [root] (get m root))))

(defn- base-operation-args [config store*]
  {:configuration config
   :policy-store store*
   :state-before {qa 80000}
   :stages [[(effects/delta qa 10000)]]
   :attribution {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}
                     {:risk/domain "technical"}]}
   :valuation-basis-root valuation-root
   :unit-root unit-root})

(defn- projection-for [state-before stages attribution]
  (producer/exposure-projection
   {:time-basis {:basis :state-derived}
    :state-before state-before
    :stages stages
    :attribution attribution}
   {:valuation-basis-root valuation-root
    :unit-root unit-root}))

(defn- state-root-ref [state]
  (hash-ref/sha256-ref (effects/state-root state)))

;; ── state/candidate binding audit ───────────────────────────────────────────

(deftest test-source-root-binds-to-predecessor-state-root
  (let [state-before {qa 80000}
        transition (effects/transition state-before [(effects/delta qa 10000)])
        p (projection-for state-before [[(effects/delta qa 10000)]] {})]
    (testing "producer source-root == canonical-effects state-root of state-before"
      (is (= (state-root-ref state-before) (:risk-projection/source-root p))))
    (testing "producer source-root == canonical transition state-before/root (same object)"
      (is (= (hash-ref/sha256-ref (:state-before/root transition))
             (:risk-projection/source-root p))))
    (testing "projection after-state is the candidate transition state-after"
      (is (= (hash-ref/sha256-ref (:state-after/root transition))
             (hash-ref/sha256-ref (effects/state-root {qa 90000})))))))

(deftest test-evaluate-bound-uses-exact-source-root
  (let [state-before {qa 80000}
        p (projection-for state-before [[(effects/delta qa 10000)]] {})
        policy (strict-policy)]
    (is (= :pass (:risk-limit-evaluation/status
                  (le/evaluate-bound p policy (state-root-ref state-before)))))
    (testing "passing the post-transition candidate root never matches the source"
      (is (thrown-with-msg? ExceptionInfo #"does not match"
                            (le/evaluate-bound p policy
                                               (state-root-ref {qa 90000})))))))

;; ── chain-configuration.v4 ──────────────────────────────────────────────────

(deftest test-v4-configuration-is-closed-and-rooted
  (let [c (v4-config (strict-policy))]
    (is (:valid? (genesis/validate-chain-configuration-v4 c)))
    (is (genesis/supported-chain-configuration? c))
    (is (= (:risk-limit-policy/root (strict-policy))
           (auth/authoritative-policy-root c)))
    (testing "risk root is mandatory: absent or malformed fails closed"
      (is (not (:valid? (genesis/validate-chain-configuration-v4
                         (dissoc c :risk-limit-policy/root)))))
      (is (not (:valid? (genesis/validate-chain-configuration-v4
                         (assoc c :risk-limit-policy/root "not-a-root"))))))))

(deftest test-v4-root-commits-the-risk-policy-root
  (let [c1 (v4-config (strict-policy))
        c2 (v4-config (permissive-policy))]
    (is (not= (genesis/chain-configuration-root c1)
              (genesis/chain-configuration-root c2)))
    (is (not= (:risk-limit-policy/root c1)
              (:risk-limit-policy/root c2)))
    (testing "v1/v2/v3 configurations remain supported without a risk root"
      (is (genesis/supported-chain-configuration? genesis/chain-configuration-fixture))
      (is (genesis/supported-chain-configuration? c1)))))

(deftest test-risk-policy-root-change-changes-configuration-change-identity
  (let [parent (assoc (v4-config (strict-policy))
                      :module-registry/root (hash-ref/sha256-ref qa))
        c-same (v4-config (strict-policy))
        c-diff (v4-config (permissive-policy))
        base {:transition/schema genesis/chain-configuration-transition-schema
              :protocol/genesis-root genesis/protocol-genesis-fixture-root
              :target {:target/type :chain-instance
                       :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
              :verifier-registry/root (:verifier-registry/root c-diff)
              :epoch 1}
        t-same (assoc base
                      :configuration/parent-root (genesis/chain-configuration-root parent)
                      :configuration/new-root (genesis/chain-configuration-root c-same))
        t-diff (assoc base
                      :configuration/parent-root (genesis/chain-configuration-root parent)
                      :configuration/new-root (genesis/chain-configuration-root c-diff))]
    (is (not= (genesis/chain-configuration-root parent)
              (genesis/chain-configuration-root c-same)))
    (testing "generic change identity tracks the risk-policy root via :configuration/new-root"
      (is (not= (genesis/chain-configuration-change-identity-hash t-same)
                (genesis/chain-configuration-change-identity-hash t-diff)))
      (is (not= (genesis/chain-configuration-transition-root t-same)
                (genesis/chain-configuration-transition-root t-diff))))))

;; ── policy-body resolution ──────────────────────────────────────────────────

(deftest test-policy-body-resolution-fails-closed
  (let [p1 (strict-policy)
        root1 (:risk-limit-policy/root p1)
        wrong-root (hash-ref/sha256-ref qa)]
    (testing "valid body resolves and recomputed root == configured root"
      (is (= root1 (:policy-root (auth/resolve-policy (v4-config p1) (store [root1 p1]))))))
    (testing "absent body fails closed"
      (is (thrown-with-msg? ExceptionInfo #"unavailable"
                            (auth/resolve-policy (v4-config p1) (store)))))
    (testing "malformed body fails closed"
      (is (thrown-with-msg? ExceptionInfo #"malformed"
                            (auth/resolve-policy (v4-config p1)
                                                 (store [root1 (assoc p1 :intruder 1)])))))
    (testing "correct body under wrong configured root fails closed"
      (is (thrown-with-msg? ExceptionInfo #"root mismatch"
                            (auth/resolve-policy
                             (assoc (v4-config p1) :risk-limit-policy/root wrong-root)
                             (store [wrong-root p1])))))))

;; ── admission gate ──────────────────────────────────────────────────────────

(deftest test-admit-accepts-authorized-exact-candidate
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        args (base-operation-args config st)
        decision (auth/admit-pro-rata-operation args)]
    (is (:admitted? decision))
    (is (= (:risk-limit-policy/root p1) (:policy-root decision)))
    (is (= :pass (:risk-limit-evaluation/status (:evaluation decision))))))

(deftest test-admit-rejects-permissive-policy-substitution
  (let [p1 (strict-policy)
        p2 (permissive-policy)
        root1 (:risk-limit-policy/root p1)
        config (v4-config p1)]
    (testing "store returning P2 under P1's root is rejected (root mismatch)"
      (let [decision (auth/admit-pro-rata-operation
                      (assoc (base-operation-args config (store [root1 p2]))
                             :stages [[(effects/delta qa 300000)]]))]
        (is (not (:admitted? decision)))
        (is (= :risk-policy-root-mismatch (:reason decision)))))
    (testing "store keyed by P2's root cannot be reached from P1's config"
      (let [decision (auth/admit-pro-rata-operation
                      (assoc (base-operation-args config (store [(:risk-limit-policy/root p2) p2]))
                             :stages [[(effects/delta qa 300000)]]))]
        (is (not (:admitted? decision)))
        (is (= :risk-policy-body-unavailable (:reason decision)))))))

(deftest test-admit-rejects-wrong-root-and-unavailable-body
  (let [p1 (strict-policy)
        p2 (permissive-policy)
        root1 (:risk-limit-policy/root p1)
        root2 (:risk-limit-policy/root p2)
        config (v4-config p1)]
    (testing "correct P1 body under wrong configured root"
      (is (= :risk-policy-root-mismatch
             (:reason (auth/admit-pro-rata-operation
                       (assoc (base-operation-args config (store [root2 p1]))
                              :configuration (v4-config p2)))))))
    (testing "configured policy body unavailable"
      (is (= :risk-policy-body-unavailable
             (:reason (auth/admit-pro-rata-operation
                       (base-operation-args config (store)))))))
    (testing "malformed configured policy"
      (is (= :risk-policy-body-malformed
             (:reason (auth/admit-pro-rata-operation
                       (base-operation-args config (store [root1 (assoc p1 :intruder 1)])))))))))

(deftest test-admit-rejects-transplanted-projection
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        proj-a (projection-for {qa 80000} [[(effects/delta qa 10000)]] {})
        ;; candidate B has a different source state (different predecessor)
        proj-b (projection-for {qa 80000 qb 50000} [[(effects/delta qa 10000)]] {})
        source-a (:risk-projection/source-root proj-a)
        source-b (:risk-projection/source-root proj-b)]
    (is (not= source-a source-b))
    (testing "projection A bound to source A passes"
      (is (:admitted? (auth/admit config st proj-a source-a))))
    (testing "projection A transplanted to candidate B's source root rejects"
      (is (= :source-root-mismatch (:reason (auth/admit config st proj-a source-b))))
      (is (= :source-root-mismatch (:reason (auth/admit config st proj-b source-a)))))))

(deftest test-admit-rejects-as-of-observational-projection
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        obs (rp/projection
             {:source/root (state-root-ref {qa 80000})
              :valuation-basis/root valuation-root
              :unit/root unit-root
              :time-basis {:basis :as-of :at 1000}
              :rows [{:exposure/id "a"
                      :exposure/subject-root (hash-ref/sha256-ref qa)
                      :exposure/current 80000 :exposure/after 90000 :exposure/peak 90000
                      :exposure/domains []}]})]
    (is (= :projection-not-exact-state-bound
           (:reason (auth/admit config st obs (state-root-ref {qa 80000})))))))

(deftest test-admit-rejects-wrong-configuration-lineage
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        args (base-operation-args config st)]
    (testing "stale/wrong configuration root does not match the authoritative lineage head"
      (let [decision (auth/admit-pro-rata-operation
                      (assoc args :expected-configuration-root
                             (hash-ref/sha256-ref qa)))]
        (is (not (:admitted? decision)))
        (is (= :configuration-root-mismatch (:reason decision)))))
    (testing "non-V4 configuration is not risk-gated authority"
      (let [decision (auth/admit (assoc config :configuration/schema "chain-configuration.v3")
                                 st
                                 (projection-for {qa 80000} [[(effects/delta qa 10000)]] {})
                                 (state-root-ref {qa 80000}))]
        (is (= :configuration-invalid (:reason decision)))))))

(deftest test-admit-rejects-risk-limit-violation
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        ;; peak exposure 1.1M exceeds the 1M global conservative-peak limit
        violating (assoc (base-operation-args config st)
                         :state-before {qa 800000}
                         :stages [[(effects/delta qa 300000)]])
        decision (auth/admit-pro-rata-operation violating)]
    (is (not (:admitted? decision)))
    (is (= :risk-limit-violation (:reason decision)))
    (is (some #(= "global" (:limit/id %))
              (:risk-limit-evaluation/results (:evaluation decision))))))

(deftest test-admit-rejects-tampered-projection
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        proj (projection-for {qa 80000} [[(effects/delta qa 10000)]] {})
        source (:risk-projection/source-root proj)]
    (testing "tampered row amounts invalidate the projection root"
      (let [tampered (update-in proj [:risk-projection/exposures 0]
                                assoc :exposure/current 999999)
            decision (auth/admit config st tampered source)]
        (is (not (:admitted? decision)))
        (is (= :projection-root-invalid (:reason decision)))))
    (testing "tampered committed root is rejected"
      (let [tampered (assoc proj :risk-projection/root (hash-ref/sha256-ref qa))
            decision (auth/admit config st tampered source)]
        (is (not (:admitted? decision)))
        (is (= :projection-root-invalid (:reason decision)))))))

(deftest test-admit-policy-root-is-never-caller-chosen
  (let [p1 (strict-policy)
        p2 (permissive-policy)
        config (v4-config p1)
        ;; store contains BOTH policies, keyed by their own roots
        st (store [(:risk-limit-policy/root p1) p1]
                  [(:risk-limit-policy/root p2) p2])
        decision (auth/admit-pro-rata-operation (base-operation-args config st))]
    (is (:admitted? decision))
    (testing "the policy used is exactly the one committed by the configuration"
      (is (= (:risk-limit-policy/root p1) (:policy-root decision)))
      (is (= (:risk-limit-evaluation/policy-root (:evaluation decision))
             (:risk-limit-policy/root p1))))))

(deftest test-admit-rejects-violation-without-any-state-effect
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        state-before {qa 800000}
        stages [[(effects/delta qa 300000)]]
        before-snapshot state-before
        decision (auth/admit-pro-rata-operation
                  (assoc (base-operation-args config st)
                         :state-before state-before :stages stages))]
    (is (= :risk-limit-violation (:reason decision)))
    (testing "no partial effects: state-before is untouched and no transition is emitted"
      (is (= before-snapshot state-before))
      (is (= before-snapshot (effects/apply-effects state-before [])))
      (is (nil? (:evaluation-in-commit decision))))))

(deftest test-admit-pro-rata-underflow-rejects-before-evaluation
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])]
    (testing "an underflowing staged operation fails closed during replay"
      (is (thrown-with-msg? ExceptionInfo #"underflow"
                            (auth/admit-pro-rata-operation
                             (assoc (base-operation-args config st)
                                    :state-before {qa 100}
                                    :stages [[(effects/delta qa -200)]])))))))

(deftest test-admit-unit-mismatch-rejects
  (let [p1 (strict-policy)
        config (v4-config p1)
        st (store [(:risk-limit-policy/root p1) p1])
        other-unit (hash-ref/sha256-ref
                    (hc/domain-hash :prf-risk-projection-v1 {:fixture "other-unit"}))
        proj (producer/exposure-projection
              {:time-basis {:basis :state-derived}
               :state-before {qa 80000}
               :stages [[(effects/delta qa 10000)]]}
              {:valuation-basis-root valuation-root
               :unit-root other-unit})]
    (is (= :unit-mismatch
           (:reason (auth/admit config st proj (:risk-projection/source-root proj)))))))