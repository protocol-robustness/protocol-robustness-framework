(ns resolver-sim.protocols.sew.with-bounty-test
  "End-to-end with-bounty test: exercises the complete bounty path from
   fixed regression case through distribution, plan, payable, backing,
   Sew application and receipt verification.

   Uses authoritative production builders throughout.
   No stage is replaced with a precomputed artifact."
  (:require [resolver-sim.time.context :as time-ctx]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.accounting :as act]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.apply-slash-distribution :as apply]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.economics.slash-distribution-application-plan :as plan]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.benchmark.fixed-regression-case :as frc]
            [resolver-sim.economics.calculations :as core-econ]))

;; ── test fixtures ───────────────────────────────────────────────────────────

(def sew-default-policy
  {:schema-version "slash-distribution-policy.v1"
   :policy/id :sew.policy/default-slash-distribution
   :policy/version 1
   :allocation
   {:method :weighted :scale 10000
    :weights {:sew.allocation/insurance 5000
              :sew.allocation/protocol 3000
              :sew.allocation/retained 2000}
    :remainder-to :sew.allocation/retained}
   :awards
   [{:award/id :sew.award/challenge-bounty
     :amount {:method :rate-of-gross
              :parameter-key :sew.parameter/challenge-bounty-bps
              :scale 10000 :rounding :floor}
     :eligibility {:trigger :sew.trigger/successful-challenge
                   :beneficiary-role :sew.participant/challenger
                   :requires-evidence-reference? true}
     :funding {:method :weighted-deduction :scale 10000
               :weights {:sew.allocation/insurance 5000
                         :sew.allocation/protocol 5000}
               :remainder-to :sew.allocation/protocol}
     :settlement {:allocation-id :sew.allocation/challenge-bounty
                  :obligation-kind :sew.obligation/challenge-bounty}}]})

(defn base-world
  "Empty Sew world at block-time 1000 with default params."
  []
  (-> (t/empty-world 1000)
      (assoc :params {:insurance-cut-bps 5000
                      :protocol-retained-bps 3000})))

(defn build-distribution
  "Build a slash-distribution from inputs. Production path."
  [gross-amount bounty-bps challenger workflow-id]
  (let [result (sew-econ/build-sew-slash-distribution
                gross-amount bounty-bps
                :challenger challenger
                :workflow-reference workflow-id
                :evidence-reference (str "sew:slash:" workflow-id))]
    (is (= :valid (:status result)))
    (:distribution result)))

(defn- verify-exact
  "Helper: assert that independent calculation equals production value."
  [label expected actual]
  (is (= expected actual) (str label ": expected " expected " got " actual)))

;; ── independent closed-form oracle ──────────────────────────────────────────

(defn- expected-final-allocations
  "Independently compute expected final allocations from gross and bounty.
   Uses the same arithmetic as the distribution engine but computed through
   a completely independent code path — pure arithmetic, no policy loading."
  [gross-amount bounty-bps]
  (let [bounty (core-econ/calculate-bounty gross-amount bounty-bps)
        insurance-bps 5000
        protocol-bps 3000
        retained-bps (- 10000 insurance-bps protocol-bps)
        insurance-base (* gross-amount insurance-bps 1/10000)
        protocol-base (* gross-amount protocol-bps 1/10000)
        retained-base (* gross-amount retained-bps 1/10000)
        bounty-from-insurance (quot bounty 2)
        bounty-from-protocol (- bounty bounty-from-insurance)]
    {:final-insurance (- (long insurance-base) bounty-from-insurance)
     :final-protocol (- (long protocol-base) bounty-from-protocol)
     :final-retained (long retained-base)
     :bounty bounty}))

;; ── end-to-end scenarios ────────────────────────────────────────────────────

(deftest with-bounty-even
  (testing "even bounty amount — divisible across both funding sources"
    (let [gross-amount 1000
          bounty-bps 1000  ;; 10% bounty
          challenger "0xChallenger"
          workflow-id 0
          w (base-world)]
      ;; 1. Build fixed regression case
      (let [fixed-case (frc/build-fixed-regression-case
                        {:case/id "slash-even-bounty"
                         :case/kind :slash/standard
                         :case/description "Standard slash with even bounty"
                         :case/gross-slash-amount gross-amount
                         :case/policy-root (sd/policy-hash sew-default-policy)
                         :case/parameter-context
                         {:source-root "sew:governance-snapshot"
                          :values {:sew.parameter/challenge-bounty-bps bounty-bps}}
                         :case/challenger challenger
                         :case/evidence-references ["sew:slash:" (str workflow-id)]})]
        (is (string? (:case/hash fixed-case)))
        (is (frc/verify-fixed-regression-case-root fixed-case)))

      ;; 2. Build slash distribution (production builder)
      (let [distribution (build-distribution gross-amount bounty-bps challenger workflow-id)]
        (is (= (:distribution/gross-amount distribution) gross-amount))

        ;; 3. Independently verify distribution before any mutation
        (let [verification (sd/verify-distribution distribution)]
          (is (:valid? verification) "distribution verification passes"))

        ;; 4. Find the challenge-bounty award by stable ID
        (let [awards (:distribution/awards distribution)
              bounty-award (first (filter #(= :sew.award/challenge-bounty (:award/id %)) awards))]
          (is (some? bounty-award) "challenge-bounty award exists")
          (is (= (:award/amount bounty-award) 100) "bounty amount = 100")

          ;; 5. Independent oracle verification
          (let [oracle (expected-final-allocations gross-amount bounty-bps)]
            (verify-exact "bounty" (:bounty oracle) (:award/amount bounty-award))
            (verify-exact "final insurance" (:final-insurance oracle)
                          (get (:distribution/final-allocations distribution) :sew.allocation/insurance 0))
            (verify-exact "final protocol" (:final-protocol oracle)
                          (get (:distribution/final-allocations distribution) :sew.allocation/protocol 0))
            (verify-exact "final retained" (:final-retained oracle)
                          (get (:distribution/final-allocations distribution) :sew.allocation/retained 0)))

          ;; 6. Build application plan (pure, no mutation)
          (let [plan-result (plan/build-application-plan
                             {:distribution distribution
                              :policy sew-default-policy
                              :idempotency-key [:slash-distribution-applied workflow-id challenger]
                              :context {:source "with-bounty-even"}})]
            (is (= :valid (:status plan-result)) "application plan is valid")
            (let [app-plan (:plan plan-result)]

              ;; 7. Apply through Sew adapter
              (let [{:keys [world payables backings receipt]}
                    (apply/apply-with-receipt app-plan w {})]

                ;; 8. Verify post-state
                (is (= (get-in world [:bond-distribution :insurance]) 450)
                    "insurance = 450")
                (is (= (get-in world [:bond-distribution :protocol]) 250)
                    "protocol = 250")
                (is (= (:retained-slash-reserves world) 200)
                    "retained = 200")
                (is (= (get-in world [:claimable-v2 workflow-id :liability/challenge-bounty challenger] 0)
                       100)
                    "challenger claimable = 100")

                ;; 9. Verify payables
                (is (= (count payables) 1))
                (is (= (:payable/amount (first payables)) 100))
                (is (= (:payable/beneficiary (first payables)) challenger))

                ;; 10. Verify backings
                (is (= (count backings) 1))
                (is (= (:backing/amount (first backings)) 100))

                ;; 11. Verify receipt exists
                (is (some? receipt) "application receipt emitted")
                (is (= (:receipt/distribution-root receipt)
                       (:distribution/hash distribution)))
                (is (= (:receipt/plan-root receipt)
                       (:plan/hash app-plan)))
                (is (string? (:receipt/hash receipt)))

                ;; 12. Verify receipt has world before/after commitments
                (is (some? (:receipt/world-before-hash receipt)))
                (is (some? (:receipt/world-after-hash receipt)))
                (is (not= (:receipt/world-before-hash receipt)
                          (:receipt/world-after-hash receipt))
                    "world state changed")

                ;; 13. Conservation: final allocations + bounty = gross
                (let [bounty (:payable/amount (first payables))
                      final-sum (+ (get-in world [:bond-distribution :insurance] 0)
                                   (get-in world [:bond-distribution :protocol] 0)
                                   (:retained-slash-reserves world 0))]
                  (is (= (+ final-sum bounty) gross-amount)
                      "conservation holds"))

                ;; 14. Outstanding payable = restricted backing
                (is (= (:backing/amount (first backings))
                       (:payable/amount (first payables)))
                    "payable = backing")

                ;; 15. Idempotent re-application
                (let [reapply (apply/apply-with-receipt app-plan world {})]
                  (is (:idempotent? reapply) "re-application is idempotent")
                  (is (nil? (:receipt reapply)) "no receipt for idempotent replay"))

                ;; 16. Replay from committed inputs reproduces same distribution
                (let [replay-dist (build-distribution gross-amount bounty-bps challenger workflow-id)]
                  (is (= (:distribution/hash replay-dist) (:distribution/hash distribution))
                      "replay produces identical distribution root"))))))))))

(deftest with-bounty-odd
  (testing "odd bounty amount — invokes canonical rounding policy"
    (let [gross-amount 100
          bounty-bps 500  ;; 5% bounty = 5
          challenger "0xChallenger"
          workflow-id 1
          w (base-world)
          distribution (build-distribution gross-amount bounty-bps challenger workflow-id)
          bounty-award (first (filter #(= :sew.award/challenge-bounty (:award/id %))
                                      (:distribution/awards distribution)))]
      (is (= (:award/amount bounty-award) 5) "bounty = 5")
      (let [oracle (expected-final-allocations gross-amount bounty-bps)]
        (verify-exact "oracle bounty" (:bounty oracle) 5)
        (verify-exact "final insurance" (:final-insurance oracle)
                      (get (:distribution/final-allocations distribution) :sew.allocation/insurance 0))
        (verify-exact "final protocol" (:final-protocol oracle)
                      (get (:distribution/final-allocations distribution) :sew.allocation/protocol 0))
        (verify-exact "final retained" (:final-retained oracle)
                      (get (:distribution/final-allocations distribution) :sew.allocation/retained 0)))
      (let [plan-result (plan/build-application-plan
                         {:distribution distribution
                          :policy sew-default-policy
                          :idempotency-key [:slash-distribution-applied workflow-id challenger]})
            app-plan (:plan plan-result)
            {:keys [world payables backings receipt]}
            (apply/apply-with-receipt app-plan w {})]
        (is (= 48 (get-in world [:bond-distribution :insurance]))
            "insurance = 48 (50 - 2)")
        (is (= 27 (get-in world [:bond-distribution :protocol]))
            "protocol = 27 (30 - 3)")
        (is (= 20 (:retained-slash-reserves world)) "retained = 20")
        (is (= 5 (get-in world [:claimable-v2 workflow-id :liability/challenge-bounty challenger] 0))
            "claimable = 5")
        (is (= (:payable/amount (first payables)) 5))
        ;; Odd remainder: floor(5/2)=2 from insurance, remainder from protocol
        (is (= (get (:backing/source-allocations (first backings)) :sew.allocation/insurance) 2)
            "funding: insurance deduction = 2")
        (is (= (get (:backing/source-allocations (first backings)) :sew.allocation/protocol) 3)
            "funding: protocol deduction = 3")
        ;; Conservation
        (let [bounty (:payable/amount (first payables))
              final-sum (+ (get-in world [:bond-distribution :insurance] 0)
                           (get-in world [:bond-distribution :protocol] 0)
                           (:retained-slash-reserves world 0))]
          (is (= (+ final-sum bounty) gross-amount) "conservation holds with odd bounty"))
        ;; Receipt verification
        (is (some? receipt))
        (is (= (:receipt/distribution-root receipt) (:distribution/hash distribution)))))))

(deftest with-bounty-zero
  (testing "zero bounty — no claimable, no payable"
    (let [gross-amount 1000
          bounty-bps 0
          workflow-id 2
          w (base-world)
          ;; No challenger — no bounty
          distribution (build-distribution gross-amount bounty-bps nil workflow-id)]
      (is (empty? (:distribution/awards distribution)) "no awards")
      (let [plan-result (plan/build-application-plan
                         {:distribution distribution
                          :policy sew-default-policy
                          :idempotency-key [:slash-distribution-applied workflow-id nil]})]
        (is (= :valid (:status plan-result)))
        (let [app-plan (:plan plan-result)]
          (is (empty? (:plan/payables app-plan)) "no payables")
          (let [{:keys [world receipt]}
                (apply/apply-with-receipt app-plan w {})]
            (is (= (get-in world [:bond-distribution :insurance]) 500)
                "insurance = 500")
            (is (= (get-in world [:bond-distribution :protocol]) 300)
                "protocol = 300")
            (is (= (:retained-slash-reserves world) 200) "retained = 200")
            (is (nil? (get-in world [:claimable-v2 workflow-id])) "no claimable created")
            (is (some? receipt) "receipt emitted for zero-bounty case")))))))

(deftest with-bounty-rejects-tampered-application-plan
  (let [workflow-id 77
        challenger "0xChallenger"
        distribution (build-distribution 1000 1000 challenger workflow-id)
        plan-result (plan/build-application-plan
                     {:distribution distribution
                      :policy sew-default-policy
                      :idempotency-key [:slash-distribution-applied workflow-id challenger]})
        tampered (assoc-in (:plan plan-result) [:plan/allocation-credits :sew.allocation/insurance] 999)
        world (base-world)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pre-mutation validation failed"
                          (apply/apply-plan-to-world tampered world {})))
    (is (= world (base-world))
        "rejection occurs before any Sew allocation or claimable mutation")))

(deftest with-bounty-parity-with-characterization
  "Verify that the plan-based application path produces the same state effects
   as the original distribute-slashed-funds path (parity with characterization)."
  (let [gross-amount 1000
        bounty-bps 1000
        challenger "0xChallenger"
        workflow-id 99
        w (base-world)
        ;; Original path
        original-world (act/distribute-slashed-funds w gross-amount challenger bounty-bps workflow-id)
        ;; New plan-based path
        distribution (build-distribution gross-amount bounty-bps challenger workflow-id)
        plan-result (plan/build-application-plan
                     {:distribution distribution
                      :policy sew-default-policy
                      :idempotency-key [:slash-distribution-applied workflow-id challenger]})
        {:keys [world]} (apply/apply-with-receipt (:plan plan-result) w {})]
    (is (= (:bond-distribution original-world) (:bond-distribution world))
        "bond-distribution matches")
    (is (= (:retained-slash-reserves original-world) (:retained-slash-reserves world))
        "retained-slash-reserves matches")
    (is (= (get-in original-world [:claimable-v2 workflow-id])
           (get-in world [:claimable-v2 workflow-id]))
        "claimable matches")
    (is (= (get-in original-world [[:slash-distribution-applied workflow-id challenger]])
           (get-in world [[:slash-distribution-applied workflow-id challenger]]))
        "app-key matches")))
