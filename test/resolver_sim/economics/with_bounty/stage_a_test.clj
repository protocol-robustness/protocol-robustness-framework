(ns resolver-sim.economics.with-bounty.stage-a-test
  "Stage A thin proof tests (ADR-0006 Stage A).

   Demonstrates that the extension/composition substrate works for with-bounty
   without opening a Sew accounting or custody lifecycle audit: policy
   identity, frozen resolution, structural verification of invocations, pure
   evaluation, a validated v2 effect candidate, and a structural composition
   receipt. No Sew mutation, no custody reservation, no released attestation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.schemas :as schemas]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]
            [resolver-sim.economics.with-bounty.identity :as identity]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.economics.with-bounty.verification :as verification]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.extensions.resolution :as ext-res]))

;; ── policy identity (design note §19 Policy and hashing) ──────────────────

(deftest policy-root-deterministic
  (is (= (policy/with-bounty-policy-root proof/review-policy)
         (policy/with-bounty-policy-root proof/review-policy)))
  (is (= 64 (count (policy/with-bounty-policy-root proof/review-policy)))))

(deftest policy-root-map-order-independence
  (let [a (select-keys proof/review-policy
                       [:composition/type :composition/version :base :bounty])
        b (select-keys proof/review-policy
                       [:bounty :base :composition/version :composition/type])]
    (is (= (policy/with-bounty-policy-root a)
           (policy/with-bounty-policy-root b)))))

(deftest policy-root-sensitive-to-references
  (let [change-eligibility (assoc-in proof/review-policy
                                     [:bounty :eligibility :capability/ref :capability/id]
                                     :fixture/other-eligible)
        change-effect      (assoc-in proof/review-policy
                                     [:bounty :effect-contract]
                                     :prf.effect/balance-credit.v1)
        change-funding     (assoc-in proof/review-policy
                                     [:bounty :funding :source]
                                     :base/gross)
        change-base        (assoc-in proof/review-policy [:base :operation/ref]
                                     :prf/some-other-base)
        base (policy/with-bounty-policy-root proof/review-policy)]
    (is (not= base (policy/with-bounty-policy-root change-eligibility)))
    (is (not= base (policy/with-bounty-policy-root change-effect)))
    (is (not= base (policy/with-bounty-policy-root change-funding)))
    (is (not= base (policy/with-bounty-policy-root change-base)))))

(deftest policy-normalisation-fills-defaults
  (let [normalized (policy/normalize-with-bounty-policy proof/review-policy)]
    (is (= :skip (:bounty/on-ineligible normalized)))
    (is (= :abort-bounty (:bounty/on-calculation-failure normalized)))
    (is (= :abort-before-mutation (:bounty/on-unsupported-effect normalized)))
    (is (= :base-independent (:bounty/failure-mode normalized)))
    (is (:valid? (policy/validate-with-bounty-policy normalized)))))

(deftest policy-validation-rejects-invalid
  (testing "wrong composition type"
    (is (not (policy/valid-with-bounty-policy?
              (assoc proof/review-policy :composition/type :economics/other)))))
  (testing "missing bounty"
    (is (not (policy/valid-with-bounty-policy?
              (dissoc proof/review-policy :bounty)))))
  (testing "undeclared basis source"
    (is (not (policy/valid-with-bounty-policy?
              (assoc-in proof/review-policy
                        [:bounty :amount :basis :source]
                        :undeclared/context)))))
  (testing "unsupported funding source"
    (is (not (policy/valid-with-bounty-policy?
              (assoc-in proof/review-policy
                        [:bounty :funding :source]
                        :undeclared/funding))))))

;; ── fixture package and registration ──────────────────────────────────────

(deftest fixture-package-registers-sealed
  (let [emap (fixtures/extension-map)]
    (is (some? (get emap [:economics/eligibility :fixture/review-bounty-eligible])))
    (is (some? (get emap [:economics/award-amount :fixture/review-bounty-amount])))
    (is (= :artifact-replayable
           (em/sealed-classification fixtures/review-bounties-pack)))
    (is (= 64 (count (em/package-root fixtures/review-bounties-pack))))))

;; ── frozen resolution ─────────────────────────────────────────────────────

(deftest frozen-resolution-dev-and-sealed
  (let [dev (proof/frozen-resolution {:sealed? false})
        sealed (proof/frozen-resolution {:sealed? true})]
    (is (:valid? dev))
    (is (:valid? sealed))
    (is (= (:extensions/resolution-root (:resolution dev))
           (:extensions/resolution-root (:resolution sealed))))
    (is (= 64 (count (:extensions/resolution-root (:resolution dev)))))))

(deftest frozen-resolution-missing-capability-fails
  (let [r (ext-res/resolve-requested (fixtures/extension-map)
                                     [[:economics/eligibility :fixture/nope]]
                                     {:schemas schemas/core-schemas})]
    (is (not (:valid? r)))
    (is (some #(= :extensions/error-missing-capability (:violation/id %))
              (:violations r)))))

;; ── structural verification of invocations (schemas/conformance-check) ────

(deftest eligibility-invocation-structurally-valid
  (let [entry (get (fixtures/extension-map)
                   [:economics/eligibility :fixture/review-bounty-eligible])
        r (schemas/conformance-check
           entry {:event/context {:review/finalised? true}
                  :base/result {:resolved-amount 10000}})]
    (is (:valid? r))
    (is (true? (get-in r [:result :result/value])))))

(deftest ineligible-result-is-schema-valid-not-an-error
  (let [entry (get (fixtures/extension-map)
                   [:economics/eligibility :fixture/review-bounty-eligible])
        r (schemas/conformance-check
           entry {:event/context {:review/finalised? false}
                  :base/result {:resolved-amount 10000}})]
    (is (:valid? r))
    (is (false? (get-in r [:result :result/value])))
    (is (= :ineligible (get-in r [:result :result/classification])))))

(deftest amount-invocation-structurally-valid-and-deterministic
  (let [entry (get (fixtures/extension-map)
                   [:economics/award-amount :fixture/review-bounty-amount])
        input {:base/result {:resolved-amount 10000}
               :param-values {:fixture/review-bounty-rate 500}}
        r1 (schemas/conformance-check entry input)
        r2 (schemas/conformance-check entry input)]
    (is (:valid? r1))
    (is (= 500 (:amount (:result r1))))
    (is (= r1 r2))))

;; ── pure evaluation: applied and skipped ──────────────────────────────────

(deftest evaluation-eligible-produces-applied-receipt
  (let [{:keys [status receipt plan effect effect-root]}
        (proof/evaluate-bounty {:event/context {:review/finalised? true
                                                :event/actor :researcher/alice}
                                :base/result {:resolved-amount 10000}})]
    (is (= :applied status))
    (is (= 500 (:obligation/amount effect)))
    (is (= 64 (count (:obligation/id effect))))
    (is (= :applied (get-in receipt [:composition/status])))
    (is (= :stage-b (get-in receipt [:composition/stage])))
    (is (= :implementation-replay (get-in receipt [:verification/profile])))
    (is (string? (:bounty/effect-root receipt)))
    (is (string? (:bounty/application-plan-root receipt)))
    (is (nil? (:bounty/transition-evidence-root receipt)))
    (is (some? (get-in receipt [:bounty/eligibility
                                :invocation/evidence-envelope :invocation/id])))
    (is (= 500 (get-in receipt [:bounty/amount :result/amount])))
    (is (= 64 (count (:plan/hash plan))))
    (is (= (:bounty/application-plan-root receipt) (:plan/hash plan)))
    (is (= effect-root (first (:plan/effect-roots plan))))
    (is (= 2 (count (:plan/effects plan))))
    (is (string? (:plan/combined-effect-root plan)))
    (is (:valid? (verification/verify-composition-receipt receipt)))))

(deftest evaluation-ineligible-produces-skipped-receipt
  (let [{:keys [status receipt]} (proof/evaluate-bounty
                                  {:event/context {:review/finalised? false}
                                   :base/result {:resolved-amount 10000}})]
    (is (= :skipped status))
    (is (= :skipped (get-in receipt [:composition/status])))
    (is (= :bounty-ineligible (get-in receipt [:composition/reason])))
    (is (some? (get-in receipt [:bounty/eligibility
                                :invocation/evidence-envelope :invocation/id])))
    (is (not (contains? receipt :bounty/amount)))
    (is (nil? (:bounty/effect-root receipt)))
    (is (nil? (:bounty/application-plan-root receipt)))
    (is (:valid? (verification/verify-composition-receipt receipt)))))

;; ── obligation identity ───────────────────────────────────────────────────

(deftest obligation-id-deterministic-and-sensitive
  (let [args {:operation-root "sha256:op"
              :bounty-id :review-completion
              :recipient :researcher/alice
              :token :token/usdc
              :amount 500
              :policy-root (policy/with-bounty-policy-root proof/review-policy)}]
    (is (= (identity/bounty-obligation-id args)
           (identity/bounty-obligation-id args)))
    (is (not= (identity/bounty-obligation-id args)
              (identity/bounty-obligation-id (assoc args :recipient :researcher/bob))))
    (is (not= (identity/bounty-obligation-id args)
              (identity/bounty-obligation-id (assoc args :amount 600))))))

;; ── effect carries the v2 obligation contract ─────────────────────────────

(deftest effect-is-v2-obligation
  (let [{:keys [effect effects]} (proof/evaluate-bounty
                                  {:event/context {:review/finalised? true}
                                   :base/result {:resolved-amount 10000}})]
    (is (= :obligation/create (:effect/type effect)))
    (is (= :prf.effect/obligation-create.v2 (:effect/contract effect)))
    (is (= :bounty-payable (:obligation/type effect)))
    (is (string? (:obligation/id effect)))
    (is (= 2 (count effects)))
    (is (= #{:prf.effect/obligation-create.v2 :prf.effect/custody-held-adjustment.v2}
           (set (map :effect/contract effects))))))

;; ── boundary assertions: no Sew, no custody, no attestation ───────────────

(deftest proof-has-no-protocol-custody-attestation-dependencies
  (let [required (map (comp str ns-name)
                      (vals (ns-aliases (find-ns 'resolver-sim.economics.with-bounty.proof))))]
    (is (not-any? #(re-find #"^resolver-sim\.protocols(\.|$)" %) required))
    (is (not-any? #(re-find #"held|custody|accounting" %) required))))

(deftest receipts-claim-no-attestation-or-custody
  (let [receipt (get-in (proof/run-stage-a-proof) [:applied :receipt])
        keys (map name (keys receipt))]
    (is (not (some #(re-find #"attestation" %) keys)))
    (is (not (some #(re-find #"custody|held" %) keys)))
    (is (nil? (:bounty/transition-evidence-root receipt)))))
