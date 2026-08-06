(ns resolver-sim.benchmark.decision-subject
  "decision-subject.v1 — the reusable, content-addressed identity of the COMPLETE
   subject of a canonical decision.

   A signed researcher position answers a QUESTION (decision scope); what it
   approves or dissents is the SUBJECT. The subject commits the whole content,
   the relevant parameters, the effects, the branch, and the intended state
   transition — so that two members agreeing on the same subject root is
   whole-subject concurrence, and no effect, parameter, branch, or transition
   can be substituted without breaking verification.

   Relationship to the researcher decisions:
     - researcher-decision.v2 binds :outcome/root (the complete proposed
       outcome) directly in its preimage.
     - decision-subject.v1 is the STABLE, reusable subject artifact. A future
       decision version binds :subject/root = <decision-subject.v1 hash> so the
       decision commits content + parameters + effects + branch + transition in
       one verified root. decision-subject.v1 is deliberately independent of the
       decision schema so it can be reused across domains.

   The authoritative root for concurrence is obtained from the authorisation
   target (or a decision-subject reference), NEVER from plurality of submitted
   positions."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version
  "Version of the decision-subject artifact."
  "decision-subject.v1")

(def ^:const required-roots
  "Content-addressed roots every decision-subject must commit."
  #{:subject/content-root
    :subject/parameters-root
    :subject/effects-root
    :subject/branch-descriptor-hash
    :subject/transition-root})

(defn- subject-preimage
  [subject]
  (dissoc subject :subject/hash))

(defn decision-subject-hash
  "Canonical domain-separated hash reference for a decision-subject."
  [subject]
  (str "sha256:" (hc/domain-hash :decision-subject (subject-preimage subject))))

(defn- errors-for
  [subject]
  (cond-> []
    (not= schema-version (:schema-version subject))
    (conj :unsupported-schema-version)
    (and (contains? subject :subject/id)
         (not (keyword? (:subject/id subject))))
    (conj :invalid-subject-id)
    (some #(not (hash-ref/valid-sha256-ref? (get subject %))) required-roots)
    (conj :invalid-subject-root)
    (and (contains? subject :subject/hash)
         (not= (:subject/hash subject) (decision-subject-hash subject)))
    (conj :subject-hash-mismatch)))

(defn build-decision-subject
  "Build a decision-subject.v1 artifact committing content, parameters, effects,
   branch, and the intended state transition — each as a canonical sha256 root.

   Required:
     :subject/content-root          — the subject content
     :subject/parameters-root       — the relevant parameters
     :subject/effects-root          — the effects of the decision
     :subject/branch-descriptor-hash — the branch being decided
     :subject/transition-root       — the intended state transition (hash of a
                                      transition descriptor)

   Optional:
     :subject/id                    — qualified keyword
     :subject/hash                  — pre-computed hash (rejected on mismatch)

   Rejects a supplied hash that does not equal the canonical preimage hash."
  [fields]
  (let [subject (assoc fields :schema-version schema-version)
        errors (errors-for subject)]
    (when (seq (disj (set errors) :subject-hash-mismatch))
      (throw (ex-info "Decision-subject build failed" {:errors errors})))
    (let [computed (decision-subject-hash subject)]
      (when (and (:subject/hash fields) (not= (:subject/hash fields) computed))
        (throw (ex-info "Decision-subject hash mismatch"
                        {:declared (:subject/hash fields) :computed computed})))
      (assoc subject :subject/hash computed))))

(defn validate-decision-subject
  "Standalone validator: schema version, required content-addressed roots, and
   self-hash recomputation. Returns {:valid? bool :errors [kw]}."
  [subject]
  (let [errors (errors-for subject)]
    {:valid? (empty? errors) :errors errors}))

(defn decision-subject-valid?
  "Quick validity check."
  [subject]
  (:valid? (validate-decision-subject subject)))

(defn verify-decision-subject-root
  "Verify that a supplied decision-subject artifact carries exactly a claimed
   :subject/root. The root must recompute (integrity) and the artifact must be
   structurally valid. Returns {:valid? bool :reason str}."
  [subject claimed-root]
  (let [computed (when (map? subject) (:subject/hash subject))]
    (cond
      (nil? claimed-root)
      {:valid? false :reason "no claimed subject root"}

      (not (decision-subject-valid? subject))
      {:valid? false :reason "decision-subject is not structurally valid"}

      (not= computed claimed-root)
      {:valid? false :reason (str "subject root mismatch: declared "
                                  claimed-root " computed " computed)}

      :else
      {:valid? true})))

(defn subject-commitment-summary
  "Human-readable projection of what a decision-subject commits."
  [subject]
  (when (decision-subject-valid? subject)
    {:subject/root (:subject/hash subject)
     :commits #{:content :parameters :effects :branch :intended-state-transition}
     :subject/content-root (:subject/content-root subject)
     :subject/parameters-root (:subject/parameters-root subject)
     :subject/effects-root (:subject/effects-root subject)
     :subject/branch-descriptor-hash (:subject/branch-descriptor-hash subject)
     :subject/transition-root (:subject/transition-root subject)}))
