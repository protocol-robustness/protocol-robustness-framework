(ns resolver-sim.economics.with-bounty.evaluation
  "Pure evaluation of a with-bounty policy (ADR-0006 Stage B).

   Consumes a committed base result, resolves the eligibility and amount
   capabilities from a frozen extension snapshot, invokes them through
   core-controlled envelopes, constructs the validated v2 obligation effect and
   any custody reservation effect, builds the composition application plan, and
   emits the structural composition receipt.

   Never mutates protocol state; protocol application is a separate adapter
   concern. Base execution is the caller's responsibility: the evaluator
   consumes a committed base result (ADR-0006 D2)."
  (:require [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.schemas :as schemas]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.composition :as wb-composition]
            [resolver-sim.economics.with-bounty.identity :as identity]
            [resolver-sim.economics.with-bounty.policy :as policy]
            [resolver-sim.extensions.core :as ext-core]
            [resolver-sim.extensions.execution :as ext-exec]
            [resolver-sim.extensions.registry :as ext-reg]
            [resolver-sim.extensions.resolution :as ext-res]))

;; ── defaults ──────────────────────────────────────────────────────────────

(defn- default-extension-map
  []
  (ext-reg/register-package (ext-reg/empty-extension-map)
                            ext-core/core-economics-package))

(defn- canonical-address
  "String address form of an identity keyword/string without a leading colon
   (Clojure (str kw) includes the colon)."
  [v]
  (if (keyword? v)
    (subs (str v) 1)
    (str v)))

;; ── invocation evidence ───────────────────────────────────────────────────

(defn- invocation-evidence
  [policy-root step-id index capability-ref resolution-root result]
  {:invocation/evidence-envelope
   {:invocation/id (identity/bounty-invocation-id
                    {:policy-root policy-root
                     :step/id step-id
                     :index index
                     :capability/ref capability-ref})
    :capability/ref capability-ref
    :extensions/resolution-root resolution-root}
   :result/domain-evidence (:result/domain-evidence result)})

;; ── effect construction ───────────────────────────────────────────────────

(defn build-bounty-effect
  "Construct the normalised v2 obligation effect for an eligible bounty. The
   obligation id is deterministic (design note §6)."
  [{:keys [policy-root operation-root resolution-root amount
           eligibility-invocation-id amount-invocation-id
           recipient token bounty-id funding]}]
  {:effect/type :obligation/create
   :effect/contract :prf.effect/obligation-create.v2
   :obligation/type :bounty-payable
   :obligation/id (identity/bounty-obligation-id
                   {:operation-root operation-root
                    :bounty-id bounty-id
                    :recipient recipient
                    :token token
                    :amount amount
                    :policy-root policy-root})
   :obligation/amount amount
   :obligation/token token
   :obligation/owner recipient
   :obligation/funding funding
   :obligation/subject {:operation-root operation-root
                        :bounty-id bounty-id}
   :effect/provenance {:policy-root policy-root
                       :eligibility-invocation-id eligibility-invocation-id
                       :amount-invocation-id amount-invocation-id
                       :extensions/resolution-root resolution-root}})

(defn build-custody-reservation-effect
  "Construct the custody-held-adjustment effect reserving declared-reserve
   funding for the bounty. It is an intent only: the protocol adapter applies
   it through the canonical add-held path, never by direct mutation.

   Parameter attribution reuses the repository's canonical shape
   (resolver-sim.assurance.parameter-attribution, ADR-0006 D3): a
   :protocol-parameters context map carrying the committed context root and a
   :parameter/path address."
  [{:keys [recipient token amount funding parameter-context-root]}]
  {:effect/type :custody/held-adjustment
   :effect/contract :prf.effect/custody-held-adjustment.v2
   :effect/action "add-held"
   :effect/account (or (:account funding) :bounty-reserve)
   :effect/amount amount
   :effect/token token
   :held/kind (or (:reason funding) :bounty-reserve-reservation)
   :owner/address (canonical-address recipient)
   :parameter/context {:parameter-context/type :protocol-parameters
                       :parameter-context/root parameter-context-root
                       :parameter-context/version 1}
   :parameter/address {:parameter/path (:parameter/address funding)}})

(defn- funding-map
  [funding parameter-context-root]
  (merge {:source (:source funding)}
         (when-let [addr (:parameter/address funding)]
           {:parameter-address addr})
         (when parameter-context-root
           {:parameter-context-root parameter-context-root})))

;; ── evaluation ────────────────────────────────────────────────────────────

(defn evaluate-with-bounty
  "Evaluate a with-bounty policy.

   Args:
     :policy                 — authored with-bounty policy
     :base-result            — committed base result map
     :base-operation-root    — committed base result root
     :base-plan-root         — optional base application-plan root
     :event-context          — event context (may carry :event/actor)
     :parameter-context      — parameter values passed to the amount capability
     :parameter-context-root — committed attribution context root
     :extension-map          — frozen extension-map (defaults to core package)
     :schema-registry        — schema id -> root (defaults to core schemas)
     :effect-schema-registry — effect contract id -> root
     :sealed?                — require sealed providers for resolution
     :token                  — bounty token
     :declared-maximum       — optional amount cap
     :funding-available      — declared funding available for :declared-reserve
     :adapter-support        — adapter-support declaration committed into the
                               application plan (bound so two adapters cannot
                               apply the same plan root differently)

   Returns:
     {:status :invalid-policy | :resolution-failed | :skipped | :failed
      | :applied
      :violations [...]}       for failures
      :receipt <composition receipt>}       for :skipped and :applied
     :plan :effect :effects :effect-root}   for :applied"
  [{:keys [policy base-result base-operation-root base-plan-root
           event-context parameter-context parameter-context-root
           extension-map schema-registry effect-schema-registry
           sealed? token declared-maximum funding-available adapter-support]}]
  (let [extension-map (or extension-map (default-extension-map))
        schema-registry (or schema-registry schemas/core-schemas)
        effect-schema-registry (or effect-schema-registry effects/effect-schema-roots)
        event-context (or event-context {})
        base-result (or base-result {})
        normalized-policy (policy/normalize-with-bounty-policy policy)
        policy-result (policy/validate-with-bounty-policy normalized-policy)
        effective-inputs {:policy normalized-policy
                          :base-result base-result
                          :base-operation-root base-operation-root
                          :base-plan-root base-plan-root
                          :event-context event-context
                          :parameter-context parameter-context
                          :parameter-context-root parameter-context-root
                          :extension-map extension-map
                          :schema-registry schema-registry
                          :effect-schema-registry effect-schema-registry
                          :sealed? (boolean sealed?)
                          :token token
                          :declared-maximum declared-maximum
                          :funding-available funding-available
                          :adapter-support adapter-support}]
    (if-not (:valid? policy-result)
      {:status :invalid-policy :violations (:violations policy-result)}
      (let [policy-root (policy/with-bounty-policy-root normalized-policy)
            bounty (get-in policy [:bounty])
            bounty-id (:bounty/id bounty)
            funding (:funding bounty)
            elig-ref [:economics/eligibility
                      (get-in bounty [:eligibility :capability/ref :capability/id])]
            amt-ref [:economics/award-amount
                     (get-in bounty [:amount :capability/ref :capability/id])]
            resolution (ext-res/resolve-requested extension-map
                                                  [elig-ref amt-ref]
                                                  {:schemas schema-registry
                                                   :effect-schemas effect-schema-registry
                                                   :sealed? (boolean sealed?)})
            resolution-valid? (:valid? resolution)]
        (if-not resolution-valid?
          {:status :resolution-failed :violations (:violations resolution)}
          (if (nil? adapter-support)
            {:status :failed :reason :adapter-not-committed
             :violations [{:violation/id :violation/with-bounty-adapter-not-committed
                           :details {:reason "an application plan must commit the adapter-support declaration it was validated against"}}]}
            (let [resolution-root (get-in resolution [:resolution :extensions/resolution-root])
                  operation-root (or base-operation-root "sha256:base-operation")
                  recipient (or (:event/actor event-context)
                                (get-in bounty [:recipient :default])
                                :recipient/unspecified)
                  token (or token (get-in funding [:token] :token/usdc))
                  elig-result (ext-exec/invoke-capability
                               (get extension-map elig-ref)
                               {:event/context event-context
                                :base/result base-result})
                  elig-evidence (invocation-evidence policy-root :eligibility 0
                                                     elig-ref resolution-root
                                                     elig-result)]
              (if (false? (:result/value elig-result))
                {:status :skipped
                 :replay/inputs effective-inputs
                 :receipt (wb-composition/skipped-receipt
                           {:policy-root policy-root
                            :base-operation-root operation-root
                            :resolution-root resolution-root
                            :bounty-id bounty-id
                            :eligibility-evidence elig-evidence})}
                (let [amt-result (ext-exec/invoke-capability
                                  (get extension-map amt-ref)
                                  {:base/result base-result
                                   :param-values (or parameter-context {})})
                      amount (:amount amt-result)
                      amt-evidence (assoc (invocation-evidence policy-root :amount 1
                                                               amt-ref resolution-root
                                                               amt-result)
                                          :result/amount amount)
                      funding-ok? (wb-plan/funding-available? (:source funding)
                                                              amount funding-available)]
                  (cond
                    (or (nil? amount) (not (integer? amount)) (neg? amount))
                    {:status :failed :reason :invalid-amount
                     :violations [{:violation/id :violation/invalid-bounty-amount
                                   :details {:amount amount}}]}

                    (not funding-ok?)
                    {:status :failed :reason :insufficient-funding
                     :violations [{:violation/id :violation/insufficient-bounty-funding
                                   :details {:available funding-available
                                             :required amount}}]}

                    :else
                    (let [elig-inv-id (get-in elig-evidence
                                              [:invocation/evidence-envelope :invocation/id])
                          amt-inv-id (get-in amt-evidence
                                             [:invocation/evidence-envelope :invocation/id])
                          obligation-effect (build-bounty-effect
                                             {:policy-root policy-root
                                              :operation-root operation-root
                                              :resolution-root resolution-root
                                              :amount amount
                                              :eligibility-invocation-id elig-inv-id
                                              :amount-invocation-id amt-inv-id
                                              :recipient recipient
                                              :token token
                                              :bounty-id bounty-id
                                              :funding (funding-map funding
                                                                    parameter-context-root)})
                          effects (if (= :declared-reserve (:source funding))
                                    [obligation-effect
                                     (build-custody-reservation-effect
                                      {:recipient recipient
                                       :token token
                                       :amount amount
                                       :funding funding
                                       :parameter-context-root parameter-context-root})]
                                    [obligation-effect])
                          effect-validation (effects/validate-effects effects)
                          plan-result (wb-plan/build-with-bounty-plan
                                       {:policy-root policy-root
                                        :base-operation-root operation-root
                                        :base-result-root operation-root
                                        :base-plan-root base-plan-root
                                        :extensions-resolution-root resolution-root
                                        :adapter adapter-support
                                        :effects effects
                                        :effect-schema-roots effect-schema-registry
                                        :declared-maximum declared-maximum
                                        :funding-available funding-available})
                          effect-root (wb-plan/effect-root obligation-effect)]
                      (cond
                        (not (:valid? effect-validation))
                        {:status :failed :reason :effect-invalid
                         :violations (:violations effect-validation)}

                        (not= :valid (:status plan-result))
                        {:status :failed :reason :plan-invalid
                         :violations (:violations plan-result)}

                        :else
                        (let [plan (:plan plan-result)]
                          {:status :applied
                           :replay/inputs effective-inputs
                           :receipt (wb-composition/applied-receipt
                                     {:policy-root policy-root
                                      :base-operation-root operation-root
                                      :resolution-root resolution-root
                                      :bounty-id bounty-id
                                      :eligibility-evidence elig-evidence
                                      :amount-evidence amt-evidence
                                      :obligation-id (:obligation/id obligation-effect)
                                      :effect-root effect-root
                                      :application-plan-root (:plan/hash plan)})
                           :plan plan
                           :effect obligation-effect
                           :effects effects
                           :effect-root effect-root})))))))))))))
