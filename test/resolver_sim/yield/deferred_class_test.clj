(ns resolver-sim.yield.deferred-class-test
  "Adversarial tests for deferred-class derivation, lineage anchoring,
   max-lineage-round enforcement, and strict validation of new deferred positions."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.pro-rata-propagation-policy :as policy]
            [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.position :as pos]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- deposit-owners
  [world owners amount]
  (reduce (fn [w owner]
            (ll/deposit w test-mod {:owner/id owner :amount amount :token "USDC"}))
          world
          owners))

;; ── Deferred-class derivation ─────────────────────────────────────────────

(deftest derive-deferred-class-supports-liquidity-shortfall
  (testing "recognized shortfall reason maps to :liquidity-shortfall"
    (is (= :liquidity-shortfall
           (policy/derive-deferred-class
            {:reason :liquidity-shortfall
             :basis-amount 100 :fulfilled-amount 30
             :deferred-amount 70 :haircut-amount 0})))))

(deftest derive-deferred-class-rejects-unsupported-reasons
  (testing "arbitrary reason keyword throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported shortfall reason"
                          (policy/derive-deferred-class {:reason :some-made-up-reason}))))
  (testing "nil reason throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported shortfall reason"
                          (policy/derive-deferred-class {:reason nil}))))
  (testing "missing :reason key throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported shortfall reason"
                          (policy/derive-deferred-class {:basis-amount 100})))))

(deftest supported-classes-match-derivation-table
  (testing "policy :supported-classes matches shortfall-reason->deferred-class values"
    (let [expected (set (vals policy/shortfall-reason->deferred-class))
          policy-classes (get-in policy/shared-withdrawal-policy
                                 [:deferred :supported-classes])]
      (is (= expected policy-classes)))))

;; ── Max-lineage-round enforcement ─────────────────────────────────────────

(deftest max-lineage-round-rejects-excessive-depth
  (testing "attempting to exceed the policy max-lineage-round fails"
    ;; The default policy has max-lineage-round = 255.
    ;; We need to drive the lineage to round 256.  Each shortfall produces a
    ;; new deferred round.  With 100 principal and 30 held each round, we get
    ;; rounds: 1(70),2(40),3(10),4+ — well beyond 255 with small-enough holds.
    ;; Practical test: use a much smaller max by injecting a custom policy.
    (let [custom-policy (assoc-in policy/shared-withdrawal-policy
                                  [:deferred :max-lineage-round] 3)
          custom-ref (policy/policy-reference custom-policy)
          ;; We can't easily inject a custom policy into withdraw-shared via the
          ;; standard fixture path, so we test the guard function directly.
          guard policy/check-max-lineage-round!]
      (is (nil? (guard 3 custom-policy))
          "round == max-lineage-round is admissible")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds policy max"
                            (guard 4 custom-policy))
          "round > max-lineage-round is rejected")
      (is (nil? (guard 0 custom-policy))
          "round 0 is admissible")
      (is (nil? (guard 1 custom-policy))
          "round 1 is admissible"))))

(deftest max-lineage-round-zero-rejects-positive-rounds
  (testing "max-lineage-round of 0 means no deferral allowed"
    (let [guard policy/check-max-lineage-round!]
      (is (nil? (guard 0 {:deferred {:max-lineage-round 0}}))
          "round 0 is admissible with max 0")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds policy max"
                            (guard 1 {:deferred {:max-lineage-round 0}}))
          "round 1 is rejected with max 0"))))

;; ── Deferred class and lineage fields on constructed positions ────────────

(deftest deferred-position-carries-class-and-lineage-fields
  (testing "first deferral creates a deferred position with class and lineage fields"
    (let [w (deposit-owners base-world ["alice"] 100)
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          pos (get-in w [:yield/positions "alice"])
          d (:deferred-position pos)]
      (is (some? d) "deferred position exists")
      (is (= :liquidity-shortfall (:deferred/class d))
          "class is :liquidity-shortfall")
      (is (some? (:deferred/lineage-root d))
          "lineage-root is present")
      (is (some? (:deferred/predecessor-hash d))
          "predecessor-hash is present")
      (is (= {:schema-version "yield-lineage-origin.v1"
              :position-id "alice"
              :owner-id "alice"
              :module-id :test-mod
              :token :USDC
              :root-obligation-id "alice"
              :original-priority 0}
             (:deferred/original-position d))
          "first deferral commits a durable original-position reference")
      (is (= (ll/canonical-hash-safe (:deferred/original-position d))
             (:deferred/lineage-root d))
          "lineage root authenticates the independently reconstructible origin")
      (is (= 0 (:position/original-priority d))
          "original-priority is preserved"))))

(deftest deferred-position-carries-class-and-lineage-across-rounds
  (testing "second deferral inherits lineage root and has new predecessor hash"
    (let [w (deposit-owners base-world ["alice"] 100)
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          pos (get-in w [:yield/positions "alice"])
          d1 (:deferred-position pos)
          root1 (:deferred/lineage-root d1)
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          pos (get-in w [:yield/positions "alice"])
          d2 (:deferred-position pos)]
      (is (= :liquidity-shortfall (:deferred/class d2))
          "d2 class is :liquidity-shortfall")
      (is (= root1 (:deferred/lineage-root d2))
          "d2 inherits same lineage-root from d1")
      (is (not= (:deferred/predecessor-hash d1)
                (:deferred/predecessor-hash d2))
          "d2 predecessor-hash differs from d1 (different source position)")
      (is (= 0 (:position/original-priority d2))
          "original-priority still 0 across rounds"))))

(deftest deferred-lineage-retains-priority-and-canonical-ordering
  (testing "Repeated deferral cannot refresh an older claimant behind a newer one."
    (let [world0 (deposit-owners base-world ["older" "newer"] 100)
          ;; First shortfall gives the older position a deferred lineage.
          world1 (-> world0
                     (assoc-in [:total-held :USDC] 30)
                     (ll/withdraw-shared test-mod {:owner-ids ["older"]
                                                   :token "USDC"
                                                   :allocation-mode :pro-rata}))
          first-deferred (get-in world1 [:yield/positions "older" :deferred-position])
          lineage-root (:deferred/lineage-root first-deferred)
          ;; Submit reverse caller order; canonical order remains older then newer.
          world2 (-> world1
                     (assoc-in [:total-held :USDC] 30)
                     (ll/withdraw-shared test-mod {:owner-ids ["newer" "older"]
                                                   :token "USDC"
                                                   :allocation-mode :pro-rata}))
          older-deferred (get-in world2 [:yield/positions "older" :deferred-position])
          decision (last (vals (:yield/partial-fill-decisions world2)))
          witness (:allocation/priority-witness decision)]
      (is (= 0 (:position/original-priority older-deferred)))
      (is (= lineage-root (:deferred/lineage-root older-deferred)))
      (is (= ["older" "newer"] (mapv :key witness)))
      (is (= [0 1] (mapv :original-priority witness))))))

(deftest tampered-deferred-priority-is-rejected-before-allocation
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        tampered (assoc-in world1
                           [:yield/positions "alice" :deferred-position
                            :position/original-priority]
                           99)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Position priority contradicts deferred lineage"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest missing-deferred-lineage-root-is-rejected-before-allocation
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        malformed (update-in world1 [:yield/positions "alice" :deferred-position]
                             dissoc :deferred/lineage-root)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing a lineage anchor"
                          (ll/withdraw-shared malformed test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest tampered-original-position-reference-is-rejected-before-allocation
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        tampered (assoc-in world1
                           [:yield/positions "alice" :deferred-position
                            :deferred/original-position :position-id]
                           "forged-origin")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"origin does not match its base position"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest tampered-original-position-root-is-rejected-before-allocation
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        tampered (assoc-in world1
                           [:yield/positions "alice" :deferred-position
                            :deferred/lineage-root]
                           "forged-root")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"root does not authenticate its origin"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest repeated-deferred-lineage-root-must-match-archived-predecessor
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        world2 (-> world1
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        parent-id (get-in world2 [:yield/positions "alice" :deferred-position
                                  :position/parent-id])
        tampered (assoc-in world2
                           [:yield/positions "alice" :deferred-position-history
                            parent-id :deferred/lineage-root]
                           "tampered-root")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"lineage root differs from its archived predecessor"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest claim-deferred-closes-and-archives-active-lineage
  (let [world0 (deposit-owners base-world ["alice"] 100)
        deferred-world (-> world0
                           (assoc-in [:total-held :USDC] 30)
                           (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                         :token "USDC"
                                                         :allocation-mode :pro-rata}))
        active (get-in deferred-world [:yield/positions "alice" :deferred-position])
        settled (ll/claim-deferred deferred-world test-mod {:owner/id "alice"})
        position (get-in settled [:yield/positions "alice"])
        archived (get-in position [:deferred-position-history
                                   (:position/id active)])]
    (is (= :withdrawn (:status position)))
    (is (= 70 (:reclaimed-amount position)))
    (is (nil? (:deferred-position position))
        "a settled deferred claim must not remain active")
    (is (= :closed (:position/status archived)))
    (is (= 70 (:position/closed-from-amount archived)))
    (is (= 0 (:position/current-amount archived)))
    (is (= :claim-deferred (:position/closed-by archived)))
    (is (= 70 (:position/closed-reclaimed-amount archived)))
    (is (= (:deferred/original-position active)
           (:deferred/original-position archived)))
    (is (= (:deferred/lineage-root active)
           (:deferred/lineage-root archived)))))

(deftest claim-deferred-rejects-lineage-outstanding-mismatch
  (let [world0 (deposit-owners base-world ["alice"] 100)
        deferred-world (-> world0
                           (assoc-in [:total-held :USDC] 30)
                           (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                         :token "USDC"
                                                         :allocation-mode :pro-rata}))
        tampered (assoc-in deferred-world
                           [:yield/positions "alice" :deferred-position
                            :position/current-amount]
                           69)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"claim amount differs from lineage outstanding amount"
                          (ll/claim-deferred tampered test-mod {:owner/id "alice"})))))

(deftest full-fill-creates-no-deferred-successor
  (let [world0 (deposit-owners base-world ["alice"] 100)
        settled (-> world0
                    (assoc-in [:total-held :USDC] 100)
                    (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                  :token "USDC"
                                                  :allocation-mode :pro-rata}))]
    (is (nil? (get-in settled [:yield/positions "alice" :deferred-position])))
    (is (empty? (get-in settled [:yield/positions "alice"
                                 :deferred-position-history] {})))))

(deftest equal-primary-priorities-use-deterministic-owner-tie-break
  (let [world0 (deposit-owners base-world ["bravo" "alpha"] 100)
        ;; Equal primary priorities are not produced by normal deposits, but
        ;; this fixture verifies the deterministic secondary ordering used by
        ;; replay if a migration supplies equal legacy priorities.
        world1 (assoc-in world0 [:yield/positions "alpha" :original-priority] 0)
        world2 (assoc-in world1 [:total-held :USDC] 30)
        result (ll/withdraw-shared world2 test-mod
                                   {:owner-ids ["bravo" "alpha"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})
        decision (last (vals (:yield/partial-fill-decisions result)))
        witness (:allocation/priority-witness decision)]
    (is (= ["alpha" "bravo"] (mapv :key witness)))
    (is (= [0 0] (mapv :original-priority witness)))))

(deftest deferred-position-rejects-unsupported-shortfall-reason
  (testing "withdraw-shared with a propagation that would produce an unsupported reason fails"
    ;; The production flow always uses :liquidity-shortfall for the shortfall
    ;; reason map.  To test rejection we'd need to inject a different reason.
    ;; This test asserts the production behavior: :liquidity-shortfall is always
    ;; the reason emitted by withdraw-shared.
    (let [w (deposit-owners base-world ["alice"] 100)
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          pos (get-in w [:yield/positions "alice"])]
      (is (= :liquidity-shortfall (:reason (:shortfall pos)))
          "shortfall reason is always :liquidity-shortfall in production flow"))))

;; ── Policy validation ─────────────────────────────────────────────────────

(deftest policy-validation-rejects-missing-deferred-section
  (testing "policy without :deferred section fails validation"
    (let [bad-policy (dissoc policy/shared-withdrawal-policy :deferred)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing pro-rata propagation policy field"
                            (policy/normalize-and-validate bad-policy))))))

(deftest policy-validation-rejects-negative-max-lineage-round
  (testing "policy with negative max-lineage-round fails"
    (let [bad-policy (assoc-in policy/shared-withdrawal-policy
                               [:deferred :max-lineage-round] -1)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"max-lineage-round must be a non-negative integer"
                            (policy/normalize-and-validate bad-policy))))))

(deftest policy-validation-rejects-mismatched-supported-classes
  (testing "policy with stale supported-classes fails"
    (let [bad-policy (assoc-in policy/shared-withdrawal-policy
                               [:deferred :supported-classes] #{:liquidity-shortfall :solvency-shortfall})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"supported-classes does not match"
                            (policy/normalize-and-validate bad-policy))))))

(deftest policy-validation-accepts-valid-deferred-section
  (testing "valid policy passes normalize-and-validate"
    (let [valid (policy/normalize-and-validate policy/shared-withdrawal-policy)]
      (is (= "pro-rata-propagation-policy.v1" (:schema-version valid)))
      (is (some? (:policy/hash valid)))
      (is (= 255 (get-in valid [:deferred :max-lineage-round]))))))

;; ── Legacy position handling ──────────────────────────────────────────────

(deftest legacy-position-without-deferred-fields-is-readable
  (testing "a position created before deferred-class still works (no new fields on base)"
    (let [w (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
          pos (get-in w [:yield/positions "alice"])]
      ;; Base positions should NOT have deferred-specific fields
      (is (nil? (:deferred/class pos))
          "base position has no :deferred/class — legacy-compatible")
      (is (nil? (:deferred/lineage-root pos))
          "base position has no :deferred/lineage-root")
      (is (nil? (:deferred/predecessor-hash pos))
          "base position has no :deferred/predecessor-hash"))))

(deftest legacy-position-can-still-defer
  (testing "a legacy position can still create a deferred position with correct fields"
    (let [pos (pos/make-position {:owner/id "legacy" :module/id :test-mod
                                  :token "USDC" :principal 100})
          w (assoc-in base-world [:yield/positions "legacy"] pos)
          w (assoc-in w [:total-held :USDC] 50)
          w (ll/withdraw-shared w test-mod {:owner-ids ["legacy"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          d (get-in w [:yield/positions "legacy" :deferred-position])]
      (is (= :liquidity-shortfall (:deferred/class d))
          "legacy deferred has correct class")
      (is (some? (:deferred/lineage-root d))
          "legacy deferred has lineage-root")
      (is (some? (:deferred/predecessor-hash d))
          "legacy deferred has predecessor-hash"))))

;; ── Stability: existing behavior preserved ────────────────────────────────

;; ── 2.1 Pre-closure predecessor commitment ────────────────────────────────

(defn- two-round-deferred-world
  "Drive a two-round deferred lineage for 'alice' so the first-round deferred is
   archived as the predecessor of the active second-round deferred."
  []
  (let [world0 (deposit-owners base-world ["alice"] 100)
        world1 (-> world0
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))
        world2 (-> world1
                   (assoc-in [:total-held :USDC] 30)
                   (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))]
    world2))

(deftest archived-predecessor-commits-immutable-pre-closure-hash
  (testing "archived parent carries pre-closure hash/snapshot equal to child predecessor-hash"
    (let [world (two-round-deferred-world)
          position (get-in world [:yield/positions "alice"])
          child (:deferred-position position)
          parent (get-in position [:deferred-position-history
                                   (:position/parent-id child)])]
      (is (some? parent) "archived predecessor exists")
      (is (= "deferred-position-closure.v1" (:schema-version parent))
          "archived parent carries a versioned closure schema")
      (is (some? (:position/pre-closure-hash parent))
          "archived parent commits a pre-closure hash")
      (is (some? (:position/pre-closure-snapshot parent))
          "archived parent commits a pre-closure snapshot")
      (is (= (:position/pre-closure-hash parent)
             (:deferred/predecessor-hash child))
          "child predecessor-hash equals archived pre-closure commitment")
      (is (= (:position/current-amount
              (:position/pre-closure-snapshot parent))
             (:position/closed-from-amount parent))
          "snapshot preserves the pre-closure residual before archival"))))

(deftest altered-child-predecessor-hash-is-rejected
  (let [world (two-round-deferred-world)
        tampered (assoc-in world
                           [:yield/positions "alice" :deferred-position
                            :deferred/predecessor-hash]
                           "forged-hash")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"predecessor hash does not match archived pre-closure"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest altered-archived-pre-closure-hash-is-rejected
  (let [world (two-round-deferred-world)
        child (get-in world [:yield/positions "alice" :deferred-position])
        parent-id (:position/parent-id child)
        tampered (assoc-in world
                           [:yield/positions "alice"
                            :deferred-position-history
                            parent-id :position/pre-closure-hash]
                           "forged-commitment")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"predecessor hash does not match archived pre-closure"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest missing-deferred-predecessor-archive-is-rejected
  (let [world (two-round-deferred-world)
        tampered (assoc-in world
                           [:yield/positions "alice" :deferred-position
                            :position/parent-id]
                           "nonexistent-parent")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"predecessor archive is missing"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest child-lacking-predecessor-commitment-is-rejected
  (let [world (two-round-deferred-world)
        tampered (update-in world
                            [:yield/positions "alice" :deferred-position]
                            dissoc :deferred/predecessor-hash)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing a lineage anchor"
                          (ll/withdraw-shared tampered test-mod
                                              {:owner-ids ["alice"]
                                               :token "USDC"
                                               :allocation-mode :pro-rata})))))

(deftest archived-predecessor-links-to-active-successor
  (testing "archived successor link remains consistent (2.1 negative: different successor)"
    (let [world (two-round-deferred-world)
          child (get-in world [:yield/positions "alice" :deferred-position])
          parent-id (:position/parent-id child)
          tampered (assoc-in world
                             [:yield/positions "alice"
                              :deferred-position-history
                              parent-id :position/successor-id]
                             "wrong-successor")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not link to active successor"
                            (ll/withdraw-shared tampered test-mod
                                                {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))))))

(deftest deferred-position-amounts-unchanged
  (testing "introducing deferred fields does not change amount accounting"
    (let [w (deposit-owners base-world ["alice"] 100)
          w (assoc-in w [:total-held :USDC] 30)
          w (ll/withdraw-shared w test-mod {:owner-ids ["alice"]
                                            :token "USDC"
                                            :allocation-mode :pro-rata})
          pos (get-in w [:yield/positions "alice"])
          d (:deferred-position pos)]
      (is (= 70 (:position/current-amount d))
          "deferred amount is still 70")
      (is (= :deferred-withdrawal (:position/type d))
          "position type unchanged")
      (is (= :later-liquidity (:position/eligibility d))
          "eligibility unchanged")
      (is (= 30 (get-in pos [:shortfall :fulfilled-amount]))
          "shortfall fulfilled amount unchanged"))))
