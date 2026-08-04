(ns resolver-sim.conformance.coverage
  "Per-subject coverage reconciliation.

   A claim must not arise from aggregate success while individual subject
   coverage is incomplete.  A coverage receipt reconciles the declared subject
   set against the subjects that were validated, executed, compared, and
   explicitly excluded."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

;; ---------------------------------------------------------------------------
;; Universe / inclusion / exclusion commitments
;; ---------------------------------------------------------------------------

(def exclusion-classes
  "Useful exclusion classes.  An invalid fixture, a valid-but-unsupported
   fixture, and an intentionally out-of-scope fixture must NOT all become a
   generic exclusion."
  #{:unsupported-capability
    :out-of-profile
    :known-implementation-divergence
    :fixture-invalid
    :not-selected
    :superseded})

(defn exclusion
  "Build a structured exclusion.

   {:subject/id ...
    :exclusion/reason ...
    :exclusion/class ...
    :exclusion/profile-root ...
    :exclusion/evidence-root ...
    :exclusion/claim-effect ...}

   claim-effect: :no-claim (default) | :claim-excluded-subjects | :claim-narrowed."
  [{:keys [subject/id reason class profile-root evidence-root claim-effect]}]
  (cond-> {:subject/id id
           :exclusion/reason reason
           :exclusion/class class
           :exclusion/claim-effect (or claim-effect :no-claim)}
    profile-root (assoc :exclusion/profile-root profile-root)
    evidence-root (assoc :exclusion/evidence-root evidence-root)))

(defn validate-exclusions
  "Structural validation of a set of exclusions: every entry must identify a
   subject, carry a reason, and use a known exclusion class."
  [exclusions]
  (reduce (fn [v e]
            (cond-> v
              (nil? (:subject/id e))
              (conj {:violation/id :violation/missing-exclusion-subject
                     :details {:exclusion e}})
              (nil? (:exclusion/reason e))
              (conj {:violation/id :violation/missing-exclusion-reason
                     :details {:exclusion e}})
              (not (contains? exclusion-classes (:exclusion/class e)))
              (conj {:violation/id :violation/unknown-exclusion-class
                     :details {:exclusion e
                               :known (vec (sort exclusion-classes))}})))
          [] exclusions))

(defn universe-split
  "Partition a declared universe into included and excluded subject ids.

   Enforces: included ∩ excluded = ∅ and included ∪ excluded = universe.
   Returns {:universe/root ... :included-subject-set/root ...
            :exclusion-set/root ... :partition-ok? bool
            :included <ids> :excluded <ids>}."
  [universe included excluded]
  (let [excluded-ids (mapv :subject/id excluded)
        included-set (set included)
        excluded-set (set excluded-ids)
        partition-ok? (and (empty? (set/intersection included-set excluded-set))
                           (= (set universe) (set (concat included excluded-ids))))]
    {:universe/root (hc/domain-hash "conformance.universe.v1" universe)
     :included-subject-set/root (hc/domain-hash "conformance.included.v1" (vec (sort included)))
     :exclusion-set/root (hc/domain-hash "conformance.exclusion.v1"
                                         (vec (sort (map :subject/id excluded))))
     :partition-ok? partition-ok?
     :included (vec included)
     :excluded excluded-ids}))

(defn coverage-complete?
  "True when every required subject (not explicitly excluded) was validated,
   executed, and compared.  Exclusions must be explicit."
  [required excluded validated executed compared]
  (let [need (remove (set excluded) required)]
    (and (every? (set validated) need)
         (every? (set executed) need)
         (every? (set compared) need))))

(defn coverage-receipt
  "Build a coverage receipt.

   Keys:
     :universe-root          content root of the declared universe
     :required-subjects      seq of subject ids that must be covered
     :validated-subjects     seq covered by validation receipts
     :executed-subjects      seq covered by execution (replay) receipts
     :compared-subjects      seq covered by comparison receipts
     :excluded-subjects      seq explicitly excluded (with machine-readable
                             reason — tracked by the caller)"
  [{:keys [universe-root required-subjects validated-subjects
           executed-subjects compared-subjects excluded-subjects]}]
  (let [required (vec required-subjects)
        excluded (vec excluded-subjects)]
    {:coverage/universe-root universe-root
     :coverage/required-subjects required
     :coverage/validated-subjects (vec validated-subjects)
     :coverage/executed-subjects (vec executed-subjects)
     :coverage/compared-subjects (vec compared-subjects)
     :coverage/excluded-subjects excluded
     :coverage/complete?
     (coverage-complete? required excluded
                         validated-subjects executed-subjects compared-subjects)}))

(defn coverage-root
  "Content root of a coverage receipt (deterministic)."
  [receipt]
  (hc/domain-hash "conformance.coverage.v1"
                  (select-keys receipt
                               [:coverage/universe-root
                                :coverage/required-subjects
                                :coverage/validated-subjects
                                :coverage/executed-subjects
                                :coverage/compared-subjects
                                :coverage/excluded-subjects])))
