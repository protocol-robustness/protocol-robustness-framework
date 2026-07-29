(ns resolver-sim.protocols.sew.apply-slash-distribution
  "Sew-specific application adapter for verified slash-distribution
   application plans.

   Translates generic plan effects into concrete Sew world mutations.
   The plan is already verified — this adapter only applies pre-checked
   effects atomically and emits the application receipt.

   Boundaries:
   - This namespace imports generic economics contracts (plan, payable,
     backing) but not the distribution builder itself.
   - Generic namespaces must not import this namespace."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.accounting :as act]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.economics.slash-distribution-application-plan :as plan]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.hash.canonical :as hc]))

(defn validate-plan-for-application
  "Validate that a plan can be applied to a given Sew world.
   Checks preconditions without mutating state.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [plan world]
  (let [app-key (:plan/idempotency-key plan)
        app-hash (get-in world app-key)
        dist-root (:plan/distribution-root plan)
        errors (cond-> []
                 (nil? plan) (conj :missing-plan)
                 (not (:plan/preconditions plan)) (conj :missing-preconditions)
                 (and app-hash (not= app-hash dist-root))
                 (conj :conflicting-application-key
                       {:stored app-hash :expected dist-root}))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn apply-plan-to-world
  "Apply a verified application plan to a Sew world state.

   Performs pre-mutation validation, then applies all effects:
   - Writes insurance, protocol, retained allocations
   - Creates bounty payable and backing records
   - Writes claimable from payable amount
   - Records idempotency key

   Returns {:world <updated-world>
            :payables [<payable-maps>]
            :backings [<backing-maps>]}"
  [plan world context]
  (let [validation (validate-plan-for-application plan world)]
    (when-not (:valid? validation)
      (throw (ex-info "apply-plan-to-world: pre-mutation validation failed"
                      {:errors (:errors validation)}))))
  (let [dist-root (:plan/distribution-root plan)
        gross (:plan/gross-amount plan)
        credits (:plan/allocation-credits plan)
        payables (:plan/payables plan)
        backings (:plan/backing-records plan)
        app-key (:plan/idempotency-key plan)
        app-hash (get-in world app-key)]
    (if (and (some? app-hash) (= app-hash dist-root))
      ;; Idempotent: same plan root already applied → no-op
      {:world world
       :payables payables
       :backings backings
       :idempotent? true}
      (let [world' (reduce (fn [w payable]
                             (let [beneficiary (:payable/beneficiary payable)
                                   workflow-id (get app-key 1)
                                   amount (:payable/amount payable)]
                               (if (and beneficiary (pos? amount) (some? workflow-id))
                                 (act/record-claimable-v2 w workflow-id
                                                         :liability/challenge-bounty
                                                         beneficiary amount)
                                 w)))
                           world
                           payables)
            world' (assoc-in world' [:bond-distribution :insurance]
                            (+ (get-in world [:bond-distribution :insurance] 0)
                               (get credits :sew.allocation/insurance 0)))
            world' (assoc-in world' [:bond-distribution :protocol]
                            (+ (get-in world [:bond-distribution :protocol] 0)
                               (get credits :sew.allocation/protocol 0)))
            world' (update-in world' [:retained-slash-reserves]
                              (fnil + 0) (get credits :sew.allocation/retained 0))
            world' (assoc-in world' app-key dist-root)]
        {:world world'
         :payables payables
         :backings backings
         :idempotent? false}))))

(defn build-application-receipt
  "Build a content-addressed application receipt that binds the plan root,
   pre/post-state commitments, payable references, and backing references.

   Args:
     :plan           — verified application plan
     :world-before   — world state before application
     :world-after    — world state after application
     :payables       — vector of payable artifacts
     :backings       — vector of backing artifacts
     :context        — any additional context map

   Returns a receipt map."
  [{:keys [plan world-before world-after payables backings context]}]
  (let [world-before-hash (hc/hash-with-intent {:hash/intent :world-structure}
                                                (select-keys world-before
                                                             [:bond-distribution
                                                              :retained-slash-reserves
                                                              :claimable-v2]))
        world-after-hash (hc/hash-with-intent {:hash/intent :world-structure}
                                               (select-keys world-after
                                                            [:bond-distribution
                                                             :retained-slash-reserves
                                                             :claimable-v2]))
        receipt {:receipt/version "slash-distribution-application-receipt.v1"
                 :receipt/plan-root (:plan/hash plan)
                 :receipt/distribution-root (:plan/distribution-root plan)
                 :receipt/world-before-hash world-before-hash
                 :receipt/world-after-hash world-after-hash
                 :receipt/payable-roots (mapv :payable/hash payables)
                 :receipt/backing-roots (mapv :backing/hash backings)
                 :receipt/context (or context {})}
        receipt-hash (hc/hash-with-intent {:hash/intent :evidence-record} receipt)]
    (assoc receipt :receipt/hash receipt-hash)))

(defn apply-with-receipt
  "Convenience: validate plan, apply to world, build receipt, return all.
   Single entry point for the end-to-end path."
  [plan world {:keys [context] :or {context {}}}]
  (let [world-before world
        {:keys [world payables backings idempotent?]}
        (apply-plan-to-world plan world context)
        receipt (when-not idempotent?
                  (build-application-receipt
                   {:plan plan
                    :world-before world-before
                    :world-after world
                    :payables payables
                    :backings backings
                    :context context}))]
    {:world world
     :payables payables
     :backings backings
     :receipt receipt
     :idempotent? idempotent?}))
