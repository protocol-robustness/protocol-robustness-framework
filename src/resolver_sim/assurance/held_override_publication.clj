(ns resolver-sim.assurance.held-override-publication
  "Retention/publication kernel for the held-override lineage (P0).

   One pure-ish construction phase, then ONE publication step. The owner is the
   admission-bearing production boundary (`admit-and-add-held!`), NOT the low-level
   accounting mutation. Accounting stays the low-level mutation/verification layer.

     admit
       -> derive held adjustment J0
       -> derive force-authorisation consumption X0
       -> derive held-override-lineage L0
       -> derive successor state W1
       -> publish ONE immutable successor that commits L0/root

   The publication is an immutable map (not accounting-mutates-then-lineage-
   appended), so there is no crash/partial-publication gap: either the successor
   carries L0/root AND the retained historical bodies, or it does not.

   DISCOVERABILITY: the successor authoritative state carries
   :held-override/lineage-root -> L0 -> retained bodies S0/R0/P0/F0/J0/X0, so an
   auditor walks deterministically from the result back to authority with NO
   current-configuration lookup and NO scanning an artifact store.

   This makes HELD_OVERRIDE_LINEAGE_COMPLETENESS and HELD_OVERRIDE_STATE_AFTER_BINDING
   production-enforced (not merely testable): `commit` refuses an incomplete
   publication and `audit-from-successor` verifies the binding from the successor
   alone.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - prf.extensions.*
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.assurance.held-override-lineage :as lineage]))

(def successor-state-domain
  "Domain tag for the content root of the successor held-override EFFECTS state."
  :held-override-successor-state)

(def effects-state-projection
  "The FROZEN effects-state projection (E1) — the protocol/effect state-after
   surface that an override mutation changes. It deliberately EXCLUDES
   :held-override/lineage-root and :held-override/bodies, so E1 never depends on
   the lineage. This exclusion is part of the Held Override Admission V1 contract:
   changing this projection changes the meaning of state-after binding, so it is
   pinned (see HELD_OVERRIDE_PUBLICATION_DAG)."
  [:held-adjustments
   :force-authorisations/consumed
   :total-held
   :held-ledger/index])

(defn effects-state-root
  "E1 = root of the exact effects-state-after projection. Acyclic by contract: E1
   depends on J0/X0 (and the ledger), never on :held-override/lineage-root or
   retained evidence, so there is no L0 <-> W1 hash cycle:

     H0/S0/R0/P0/K0/F0 -> J0/X0 -> E1 -> L0 -> authoritative successor W1"
  [successor]
  (hash-ref/sha256-ref
   (hc/domain-hash successor-state-domain
                   (select-keys successor effects-state-projection))))

(defn build-override-publication
  "Pure construction phase. Given the exact historical inputs and the accounting
   result (successor state W1 carrying J0/X0), derive L0 and the retained-body
   set. Returns the publication map (NOT yet committed)."
  [{:keys [state-after held-adjustment consumption-record
           predecessor-configuration-head extension-selection extension-resolution
           provider-package-root capability-key capability-version permit]}]
  (let [l0 (lineage/build-lineage
            {:predecessor-configuration-head predecessor-configuration-head
             :extension-selection extension-selection
             :extension-resolution extension-resolution
             :provider-package-root provider-package-root
             :capability-key capability-key
             :capability-version capability-version
             :permit permit
             :held-adjustment held-adjustment
             :consumption-record consumption-record
             :successor-state-root (effects-state-root state-after)})]
    {:state-after state-after
     :held-adjustment held-adjustment
     :force-authorisation-consumption consumption-record
     :held-override-lineage l0
     :retained-bodies {:selection extension-selection
                       :resolution extension-resolution
                       :provider-descriptor provider-package-root
                       :capability-key capability-key
                       :capability-version capability-version
                       :permit permit
                       :held-adjustment held-adjustment
                       :consumption consumption-record}}))

(defn commit
  "ONE publication step. Publishes L0/root and the retained historical bodies into
   the immutable successor state, making the lineage discoverable from it.

   Enforces HELD_OVERRIDE_LINEAGE_COMPLETENESS at publication time: every
   addressed body (selection, resolution, provider, capability, permit, adjustment,
   consumption) must be present; otherwise commit refuses (throws) rather than
   publishing a partial lineage.

   Returns the committed successor state (which carries
   :held-override/lineage-root and :held-override/bodies)."
  [successor publication]
  (let [{:keys [held-override-lineage retained-bodies]} publication
        l0-root (:lineage/root held-override-lineage)
        bodies-present? (every? some? (vals retained-bodies))]
    (when-not bodies-present?
      (throw (ex-info "held-override publication incomplete: missing retained body"
                      {:error :held-override/incomplete-publication
                       :missing (vec (keep (fn [[k v]] (when (nil? v) k))
                                           retained-bodies))})))
    (assoc successor
           :held-override/lineage-root l0-root
           :held-override/bodies {l0-root retained-bodies})))

(defn audit-from-successor
  "Deterministic proof path from the successor authoritative state back to
   authority — NO current-configuration lookup, NO artifact-store scan.

     successor state -> :held-override/lineage-root -> L0
     -> retained bodies S0/R0/P0/K0/F0/J0/X0
     -> recompute every root from the retained bodies
     -> verify HELD_OVERRIDE_LINEAGE_CONSERVATION (recomputed root == committed root)
     -> verify HELD_OVERRIDE_STATE_AFTER_BINDING against the successor's own
        committed J0/X0 and successor-state-root

   Returns {:audited? true} or {:audited? false :reason <kw>}."
  [successor]
  (let [l0-root (:held-override/lineage-root successor)
        bodies (get-in successor [:held-override/bodies l0-root])]
    (cond
      (nil? l0-root) {:audited? false :reason :no-lineage-root}
      (nil? bodies) {:audited? false :reason :no-retained-bodies}
      :else
      (let [{:keys [selection resolution provider-descriptor capability-key
                    capability-version permit held-adjustment consumption]} bodies
            predecessor-head-root (get-in selection [:selection/config-head-root])
            l0' (lineage/build-lineage
                 {:predecessor-configuration-head
                  {:configuration-head-state/root predecessor-head-root}
                  :extension-selection selection
                  :extension-resolution resolution
                  :provider-package-root provider-descriptor
                  :capability-key capability-key
                  :capability-version capability-version
                  :permit permit
                  :held-adjustment held-adjustment
                  :consumption-record consumption
                  :successor-state-root (effects-state-root successor)})
            conservation-ok? (= (:lineage/root l0') l0-root)
            bound? (lineage/verify-state-after-binding
                    {:lineage l0'
                     :predecessor-state-root (:lineage/predecessor-head-root l0')
                     :successor-state-root (effects-state-root successor)
                     :successor-adjustment-root (:artifact/hash held-adjustment)
                     :successor-consumption-root
                     (lineage/consumption-root consumption)})]
        (cond
          (not conservation-ok?) {:audited? false :reason :lineage-not-conserved}
          (not (:bound? bound?)) {:audited? false :reason :state-after-not-bound}
          :else {:audited? true})))))