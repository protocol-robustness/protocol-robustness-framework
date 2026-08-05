(ns resolver-sim.economics.with-bounty.proof
  "Stage A/B proof runner (ADR-0006 Stage A, then Stage B evaluation):
   extension resolution → policy identity → pure evaluation → validated v2
   obligation effect → composition application plan → structural composition
   receipt.

   Explicitly out of scope: Sew application, custody reservation, transition
   evidence, and any released compatibility attestation. This namespace imports
   no protocol/Sew code."
  (:require [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.schemas :as schemas]
            [resolver-sim.economics.with-bounty.evaluation :as evaluation]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.extensions.resolution :as ext-res]))

;; ── reference policy ──────────────────────────────────────────────────────

(def review-policy
  "The reference policy: a review-completion bounty over a committed base
   result, resolved against the fixture package."
  {:composition/type :economics/with-bounty
   :composition/version 1
   :base {:operation/ref :prf/slash-distribution-v1
          :result/schema :prf/base-result.v1}
   :bounty {:bounty/id :review-completion
            :eligibility
            {:capability/ref
             {:capability/kind :economics/eligibility
              :capability/id :fixture/review-bounty-eligible
              :capability/version 1}}
            :amount
            {:capability/ref
             {:capability/kind :economics/award-amount
              :capability/id :fixture/review-bounty-amount
              :capability/version 1}
             :basis {:source :base/result :field :resolved-amount}}
            :funding {:source :declared-reserve
                      :parameter/address [:bounties :review-reserve]}
            :recipient {:source :event/actor}
            :effect-contract :prf.effect/obligation-create.v2}})

;; ── frozen resolution ─────────────────────────────────────────────────────

(def requested-capabilities
  [[:economics/eligibility :fixture/review-bounty-eligible]
   [:economics/award-amount :fixture/review-bounty-amount]])

(defn frozen-resolution
  "Resolve the reference capabilities against the fixture extension-map.
   Supports dev (default) and sealed runs; the fixture package is sealed, so
   the sealed run passes."
  ([] (frozen-resolution {:sealed? false}))
  ([{:keys [sealed?] :or {sealed? false}}]
   (ext-res/resolve-requested (fixtures/extension-map)
                              requested-capabilities
                              {:schemas schemas/core-schemas
                               :effect-schemas effects/effect-schema-roots
                               :sealed? sealed?})))

;; ── pure evaluation ───────────────────────────────────────────────────────

(def parameter-context-root
  "Committed root of the fixture parameter context (canonical sha256 ref)."
  (str "sha256:" (apply str (repeat 64 "a"))))

(def adapter-support
  "Fixture adapter-support committed into the application plan so the plan is
   bound to the adapter declaration it was validated against (ADR-0006 D3).
   The Sew adapter commits the same-shaped declaration for its own plans."
  {:adapter/id :fixture/with-bounty-adapter
   :adapter/supported-effects
   #{:prf.effect/obligation-create.v2
     :prf.effect/custody-held-adjustment.v1}})

(defn evaluate-bounty
  "Evaluate the reference policy against a committed base result and event
   context through the generic evaluator (src), using the fixture extension
   map. Returns the evaluation result map (:status :applied | :skipped | ...)."
  [input]
  (evaluation/evaluate-with-bounty
   (merge {:policy review-policy
           :base-result (:base/result input {})
           :base-operation-root (:base/result-root input "sha256:stage-a-base")
           :event-context (:event/context input {})
           :parameter-context {:fixture/review-bounty-rate 500}
           :parameter-context-root parameter-context-root
           :extension-map (fixtures/extension-map)
           :sealed? true
           :token :token/usdc
           :funding-available 1000
           :adapter-support adapter-support}
          input)))

;; ── runnable proof ────────────────────────────────────────────────────────

(defn run-stage-a-proof
  "Run the proof and return a summary map: frozen resolution (dev + sealed),
   policy root, applied and skipped evaluations. No Sew mutation, no custody
   reservation, no released attestation."
  []
  (let [dev (frozen-resolution {:sealed? false})
        sealed (frozen-resolution {:sealed? true})
        applied (evaluate-bounty {:event/context {:review/finalised? true
                                                  :event/actor :researcher/alice}
                                  :base/result {:resolved-amount 10000}})
        skipped (evaluate-bounty {:event/context {:review/finalised? false
                                                  :event/actor :researcher/alice}
                                  :base/result {:resolved-amount 10000}})]
    {:resolution {:dev-valid? (:valid? dev)
                  :sealed-valid? (:valid? sealed)
                  :resolution-root (get-in sealed [:resolution :extensions/resolution-root])}
     :policy-root (policy/with-bounty-policy-root review-policy)
     :applied applied
     :skipped skipped}))
