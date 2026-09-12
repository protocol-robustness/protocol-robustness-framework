(ns resolver-sim.conformance.identity
  "First-class subject identity binding.

   Reconciling by subject ID correctly bridges heterogeneous root schemes
   (Clojure domain-hash vs Solidity keccak), but 'same ID' must not imply 'same
   subject'.  A subject identity binds an ID to a canonical root, per-domain
   roots, an identity policy, and a profile root.

   Every validation/execution/comparison/coverage/reconciliation receipt should
   bind either the canonical subject root, or a domain root accompanied by a
   verified derivation to the canonical root.

   Reconciliation continues to join by ID, but rejects:
     - the same ID with inconsistent canonical roots;
     - a receipt whose domain root is not linked to the subject identity;
     - one subject ID appearing under multiple subject kinds;
     - receipts created under a different profile root;
      - included and excluded records referring to different roots for the same ID."
  (:require [resolver-sim.conformance.canonical :as canonical]))

(def identity-schema-version "conformance.subject-identity/v1")

(defn subject-identity
  "Build a subject identity record.

      {:subject/id, :subject/kind, :subject/canonical-root,
       :subject/domain-roots {:domain-key <root> ...},
       :subject/identity-policy, :subject/profile-root}"
  [m]
  (let [sid (:subject/id m)
        canonical (:subject/canonical-root m)]
    (when-not sid
      (throw (ex-info "subject identity requires :subject/id" {})))
    (when-not canonical
      (throw (ex-info "subject identity requires :subject/canonical-root"
                      {:subject/id sid})))
    {:schema-version identity-schema-version
     :subject/id sid
     :subject/kind (:subject/kind m)
     :subject/canonical-root canonical
     :subject/domain-roots (or (:subject/domain-roots m) {})
     :subject/identity-policy (:subject/identity-policy m)
     :subject/profile-root (:subject/profile-root m)}))

(defn identity-roots
  "All roots a receipt may legitimately bind for an identity: the canonical
   root plus every declared domain root."
  [identity]
  (into #{(:subject/canonical-root identity)}
        (vals (:subject/domain-roots identity))))

(defn receipt-binds-identity?
  "True when a receipt's :subject/root is one of the identity's legitimate roots."
  [identity receipt]
  (contains? (identity-roots identity) (:subject/root receipt)))

(defn same-canonical-root?
  [a b]
  (= (:subject/canonical-root a) (:subject/canonical-root b)))

(defn validate-identities
  "Validate a collection of subject identities and receipts bound to them.

   Rejects:
     - the same subject/id appearing with inconsistent canonical roots;
     - the same subject/id appearing under multiple subject kinds;
     - a receipt whose domain root is not linked to the identity;
     - a receipt whose profile root differs from the identity's profile root;
     - included and excluded records that bind different roots for the same ID.

   identities — seq of subject-identity records (must carry :included? or
                :excluded? as admission classification)
   receipts   — seq of {:subject/id :subject/root :profile-root ...}

   Returns {:valid? bool :violations [...]}."
  [identities receipts]
  (let [by-id (group-by :subject/id identities)
        v (atom [])
        add! (fn [x] (swap! v conj x))
        ;; same ID -> consistent canonical root + single kind
        _ (doseq [[id group] by-id]
            (let [canonicals (set (map :subject/canonical-root group))
                  kinds (set (keep :subject/kind group))]
              (when (> (count canonicals) 1)
                (add! {:violation/id :violation/inconsistent-canonical-root
                       :details {:subject/id id
                                 :canonical-roots (vec canonicals)}}))
              (when (> (count kinds) 1)
                (add! {:violation/id :violation/multiple-subject-kinds
                       :details {:subject/id id :kinds (vec kinds)}}))))
        identities-by-id (into {} (map (fn [i] [(:subject/id i) i]) identities))
        ;; receipts must bind a legitimate root and the identity's profile root
        _ (doseq [r receipts]
            (let [id (:subject/id r)
                  identity (get identities-by-id id)]
              (cond
                (nil? identity)
                (add! {:violation/id :violation/unknown-subject-id
                       :details {:subject/id id}})
                (not (receipt-binds-identity? identity r))
                (add! {:violation/id :violation/unlinked-subject-root
                       :details {:subject/id id
                                 :subject/root (:subject/root r)
                                 :legitimate-roots (vec (identity-roots identity))}})
                (and (:subject/profile-root identity) (:profile-root r)
                     (not= (:subject/profile-root identity) (:profile-root r)))
                (add! {:violation/id :violation/profile-root-mismatch
                       :details {:subject/id id
                                 :identity-profile (:subject/profile-root identity)
                                 :receipt-profile (:profile-root r)}}))))
        ;; included and excluded records for the same ID must bind the same root
        _ (doseq [[id group] by-id
                  :when (> (count group) 1)]
            (let [included-rec (first (filter :included? group))
                  excluded-rec (first (filter :excluded? group))]
              (when (and included-rec excluded-rec
                         (not (same-canonical-root? included-rec excluded-rec)))
                (add! {:violation/id :violation/inclusion-exclusion-root-conflict
                       :details {:subject/id id}}))))]
    {:valid? (empty? @v) :violations (vec @v)}))

(defn identity-root
  "Content root of a subject identity record (deterministic)."
  [identity]
  (canonical/root
   (select-keys identity
                [:schema-version :subject/id :subject/kind
                 :subject/canonical-root :subject/domain-roots
                 :subject/identity-policy :subject/profile-root])))
