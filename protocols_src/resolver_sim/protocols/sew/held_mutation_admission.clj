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
            [resolver-sim.assurance.held-override-publication :as publication]
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

(defn- apply-accounting-and-publish!
  "Admit then apply a held mutation (add-held or sub-held) via the low-level
   accounting primitive, and — for an override — build + commit the held-override
   lineage so the successor retains it and is discoverable.

   accounting-fn is the low-level primitive (accounting/add-held or
   accounting/sub-held). Semantic authority comes from admission-opts
   :operation-id (never the accounting direction or the action name)."
  [accounting-fn world token amount accounting-opts admission-opts]
  (let [decision (admission/admit-held-mutation admission-opts)]
    (case (:admission decision)
      :proceed-ordinary
      (accounting-fn world token amount accounting-opts)

      :proceed-force-authorised
      (let [world' (accounting-fn
                    world token amount
                    (assoc accounting-opts
                           :authorization-provenance
                           (permit-provenance (:permit decision))))
            permit (:permit decision)
            j0 (last (:held-adjustments world'))
            x0 (get-in world' [:force-authorisations/consumed
                               (:authorization/id permit)])
            publication' (publication/build-override-publication
                          {:state-after world'
                           :held-adjustment j0
                           :consumption-record x0
                           :predecessor-configuration-head
                           (:configuration-head admission-opts)
                           :extension-selection
                           (:held-override/selection admission-opts)
                           :extension-resolution
                           (:extension-resolution admission-opts)
                           :provider-package-root
                           (:held-override/provider-root admission-opts)
                           :capability-key
                           (:held-override/capability-key admission-opts)
                           :capability-version
                           (:held-override/capability-version admission-opts)
                           :permit permit})]
        (publication/commit world' publication'))

      :reject
      (throw (ex-info "held-custody mutation admission rejected before mutation"
                      {:type :held-custody/admission-rejected
                       :classification (:classification decision)
                       :semantic-operation-class (:semantic-operation-class decision)
                       :operation-id (:operation-id decision)
                       :blocking-reasons (:blocking-reasons decision)})))))

(defn admit-and-add-held!
  "Sanctioned production held-ingress (add-held, :in). See apply-accounting-and-publish!."
  [world token amount accounting-opts admission-opts]
  (apply-accounting-and-publish! accounting/add-held world token amount
                                 accounting-opts admission-opts))

(defn admit-and-sub-held!
  "Sanctioned production held-egress (sub-held, :out). Semantic authority is
   determined by the operation (ordinary release/refund vs
   :force-authorisation-override egress), never by sub-held/:out itself. Same
   accounting primitive as ordinary egress; override egress verifies the exact
   :out scope and consumes the exact permit once, then retains the lineage."
  [world token amount accounting-opts admission-opts]
  (apply-accounting-and-publish! accounting/sub-held world token amount
                                 accounting-opts admission-opts))