(ns resolver-sim.assurance.held-override-lineage
  "HELD_OVERRIDE_LINEAGE_CONSERVATION — temporal/replay-safe lineage for an
   authoritatively force-authorisation-override held mutation.

   If an authoritative held mutation relied on force-authorisation override, its
   admission preserves an EXACT lineage from the PREDECESSOR authoritative
   configuration

     predecessor authoritative configuration-head/root
     -> authoritative extension selection/root
     -> rooted extension resolution/root
     -> provider package/descriptor root
     -> capability identity/version
     -> exact force-authorisation permit (id + scope-root)
     -> exact held adjustment (content root)
     -> exact consumption record (root)

   with NO current-state lookup and NO caller-asserted substitution during replay.

   The temporal rule is the point of this slice: replay must reconstruct or retain
   the exact authoritative selection/resolution that was CURRENT FOR THAT
   HISTORICAL TRANSITION — never \"which provider is selected now?\". Because the
   lineage commits the PREDECESSOR configuration-head root and the exact selection
   root, substituting the current head or current selection (which differ) makes
   the conserved root diverge and verification fails. `verify-lineage` only reads
   the supplied (historical) inputs; it never queries current extension state.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - prf.extensions.*
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.assurance.held-override-selection :as selection]))

(def lineage-schema
  "Schema of the held-override lineage artifact."
  "held-override-lineage.v1")

(def lineage-domain
  "Domain tag for the lineage self-root."
  :held-override-lineage-v1)

(def consumption-domain
  "Domain tag for the consumption-record root."
  :held-override-consumption)

(defn consumption-root
  "Content root of a force-authorisation consumption record."
  [consumption-record]
  (hash-ref/sha256-ref
   (hc/domain-hash consumption-domain
                   (dissoc (or consumption-record {}) :consumption/root))))

(defn lineage-root
  "Self-rooted content hash of a held-override lineage artifact."
  [lineage]
  (hash-ref/sha256-ref
   (hc/domain-hash lineage-domain
                   (hc/project-canonical-safe
                    (dissoc lineage :lineage/root)))))

(defn build-lineage
  "Build a self-rooted held-override lineage from the EXACT historical roots of a
   completed override admission.

     predecessor-configuration-head — the historical configuration-head-state the
                                      override was agreed under (predecessor).
     extension-selection            — the committed authoritative extension
                                      selection current for that head.
     extension-resolution           — the rooted extension-resolution snapshot
                                      (the bare snapshot, :resolution value).
     provider-package-root          — the selected provider package descriptor root.
     capability-key                 — the override capability [kind id].
     capability-version             — the pinned capability version.
     permit                         — the exact force-authorisation permit.
     held-adjustment                — the resulting held adjustment (must carry
                                      :artifact/hash).
     consumption-record             — the resulting force-authorisation consumption
                                      record.
     successor-state-root           — the exact published successor authoritative
                                      state root (binds state-after to this lineage)."
  [{:keys [predecessor-configuration-head extension-selection extension-resolution
           provider-package-root capability-key capability-version
           permit held-adjustment consumption-record successor-state-root]}]
  (let [base {:lineage/schema lineage-schema
              :lineage/predecessor-head-root
              (:configuration-head-state/root predecessor-configuration-head)
              :lineage/selection-root
              (selection/selection-root extension-selection)
              :lineage/resolution-root
              (:extensions/resolution-root extension-resolution)
              :lineage/provider-root provider-package-root
              :lineage/capability-key capability-key
              :lineage/capability-version capability-version
              :lineage/permit-id (:authorization/id permit)
              :lineage/permit-scope-root (:authorization/scope-hash permit)
              :lineage/adjustment-root (:artifact/hash held-adjustment)
              :lineage/consumption-root (consumption-root consumption-record)
              :lineage/successor-root successor-state-root}]
    (assoc base :lineage/root (lineage-root base))))

(defn verify-lineage
  "Independently recompute HELD_OVERRIDE_LINEAGE_CONSERVATION.

   Verifies that the supplied `lineage` is exactly conserved by the supplied
   HISTORICAL inputs: every committed lineage root equals the independently
   recomputed root, and the lineage self-root recomputes.

   It reads ONLY the supplied inputs — never current configuration or extension
   selection. If replay supplies the CURRENT head or CURRENT selection (which
   differ from the historical predecessor/selection committed in the lineage),
   the recomputed roots diverge and conservation fails. A caller cannot assert
   substitution: the roots are content-addressed and recomputed independently.

   Returns {:conserved? true} or {:conserved? false :mismatch <path>}."
  [{:keys [lineage] :as inputs}]
  (let [expected (build-lineage (dissoc inputs :lineage))
        mismatches (->> (keys expected)
                        (keep (fn [k]
                                (when (not= (get lineage k) (get expected k))
                                  k)))
                        vec)]
    (if (seq mismatches)
      {:conserved? false :mismatch (first mismatches)}
      {:conserved? true})))

(defn lineage-uses-predecessor?
  "True when a lineage binds a predecessor configuration-head root (i.e. it is
   anchored to a historical head, not the current one by convention). Used to
   make the temporal intent explicit."
  [lineage]
  (and (map? lineage)
       (some? (:lineage/predecessor-head-root lineage))))

(defn verify-lineage-completeness
  "HELD_OVERRIDE_LINEAGE_COMPLETENESS.

   An authoritative exceptional held mutation exists
     <=>  an exact valid held-override-lineage exists
       ∧  the lineage is retained
       ∧  the lineage is discoverable from the successor authoritative state
       ∧  all addressed historical bodies needed to verify it are retained.

   `mutation-exists?` is the protocol/world fact that an override held mutation
   was committed. The four retention/discoverability/body-retention facts are
   provided by the storage layer. Completeness holds only when they coincide —
   a committed override without a retained, discoverable, body-complete valid
   lineage (or a retained lineage for effects not committed) fails closed.

   Returns {:complete? bool :valid-lineage? bool :lineage-retained? bool
            :lineage-discoverable? bool :bodies-retained? bool}."
  [{:keys [mutation-exists? lineage-retained? lineage-discoverable?
           bodies-retained?] :as inputs}]
  (let [valid? (:conserved? (verify-lineage inputs))
        complete? (and mutation-exists?
                       valid?
                       lineage-retained?
                       lineage-discoverable?
                       bodies-retained?)]
    {:complete? complete?
     :mutation-exists? mutation-exists?
     :valid-lineage? valid?
     :lineage-retained? (boolean lineage-retained?)
     :lineage-discoverable? (boolean lineage-discoverable?)
     :bodies-retained? (boolean bodies-retained?)}))

(defn verify-state-after-binding
  "HELD_OVERRIDE_STATE_AFTER_BINDING.

   The lineage's committed consequence roots must equal EXACTLY what the
   published successor authoritative state commits, and the predecessor/successor
   roots must match:

     L0.adjustment-root   == adjustment committed by successor state
     L0.consumption-root  == consumption committed by successor state
     L0.predecessor-root  == predecessor authoritative state/head
     L0.successor-root    == exact published successor state

   This prevents a correctly-authorized lineage from being attached to a
   DIFFERENT successor state (transplantation).

   Returns {:bound? true} or {:bound? false :mismatches [path...]}."
  [{:keys [lineage predecessor-state-root successor-state-root
           successor-adjustment-root successor-consumption-root]}]
  (let [checks {:lineage/predecessor-root
                (= (:lineage/predecessor-head-root lineage) predecessor-state-root)
                :lineage/adjustment-root
                (= (:lineage/adjustment-root lineage) successor-adjustment-root)
                :lineage/consumption-root
                (= (:lineage/consumption-root lineage) successor-consumption-root)
                :lineage/successor-root
                (= (:lineage/successor-root lineage) successor-state-root)}]
    (if (every? true? (vals checks))
      {:bound? true}
      {:bound? false
       :mismatches (vec (keep (fn [[k v]] (when-not v k)) checks))})))