(ns resolver-sim.protocols.sew.held-mutation-admission
  "Sew-side single sanctioned held-mutation ingress.

   Composes the CORE admission boundary (resolver-sim.assurance.held-admission)
   with the low-level accounting mutation. This is the ONLY production boundary
   through which held-mutation producers (lifecycle, resolution, pro-rata,
   with-bounty) reach `accounting/add-held`. Raw `accounting/add-held` is kept as
   the low-level effect implementation and PRESERVES its independent exact
   force-authorisation scope verification and consumption checks.

     semantic producer
       -> admission (this namespace / core held-admission)
       -> accounting/add-held

   Admission:
     :proceed-ordinary       -> accounting/add-held unchanged (no force permit).
     :proceed-force-authorised -> accounting/add-held with the exact permit
                                  provenance; accounting independently verifies
                                  exact scope and consumes exactly that permit.
     :reject                  -> throw BEFORE mutation; nothing is changed.

   This namespace is thin: it does NOT re-implement classification, override
   resolution, or exact-scope verification. It only turns an admission decision
   into either the low-level accounting mutation or a pre-mutation rejection."
  (:require [resolver-sim.assurance.held-admission :as admission]
            [resolver-sim.protocols.sew.accounting :as accounting]))

(defn- permit-provenance
  "Project the exact selected permit into the :authorization-provenance shape
   accounting/add-held expects for force-authorisation (id, type, and the
   committed scope-hash). The full permit record is authoritative; accounting
   re-verifies the exact scope from world state and that the provenance
   scope-hash matches the grant."
  [permit]
  {:authorization/id (:authorization/id permit)
   :authorization/type (:authorization/type permit)
   :authorization/scope-hash (:authorization/scope-hash permit)})

(defn admit-and-add-held!
  "Sanctioned production held-ingress: admit then apply.

   world       — Sew world-state (immutable, returned transformed).
   token       — held token.
   amount      — non-negative held amount.
   accounting-opts — opts passed verbatim to accounting/add-held (:action :reason
                     :extra ...). For an ordinary ingress this is unchanged.
   admission-opts — opts for the core admission boundary (:operation-id :scope
                     :permits :consumption-registry :now-ts :configuration-head
                     :extension-resolution).

   Throws before mutation on any :reject admission."
  [world token amount accounting-opts admission-opts]
  (let [decision (admission/admit-held-mutation admission-opts)]
    (case (:admission decision)
      :proceed-ordinary
      (accounting/add-held world token amount accounting-opts)

      :proceed-force-authorised
      (accounting/add-held
       world token amount
       (assoc accounting-opts
              :authorization-provenance
              (permit-provenance (:permit decision))))

      :reject
      (throw (ex-info "held-custody mutation admission rejected before mutation"
                      {:type :held-custody/admission-rejected
                       :classification (:classification decision)
                       :semantic-operation-class (:semantic-operation-class decision)
                       :operation-id (:operation-id decision)
                       :blocking-reasons (:blocking-reasons decision)})))))