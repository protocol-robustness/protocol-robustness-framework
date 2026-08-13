(ns resolver-sim.workflow-group
  "Framework-neutral immutable workflow-group membership primitives.

   A workflow group is a frozen, explicitly declared collection of workflow
   identities plus the semantics governing the relationship. This namespace owns
   the canonical member identity, the canonical membership predicate, and the
   domain-separated member hash.

   It does NOT own consumer policy: duplicate rules across groups, member
   existence against a world, authorization, lifecycle, or status remain with
   each consumer. This is intentionally a small v1 — no speculative
   lifecycle/state-machine behaviour.

   The member hash domain (WORKFLOW_GROUP_MEMBER_V1) is distinct from adjacent
   domains (claim-set, check-set, authorisation-scope, source-tree, and the
   related-claims member domain) so members committed under this primitive are
   not conflated with other commitments."
  (:require [clojure.string :as str]
            [resolver-sim.hash.algorithm :as halgo]
            [resolver-sim.hash.canonical :as hash]))

(def member-kind-key
  "Canonical member-kind key. Distinguishes a workflow's membership kind (e.g.
   :sew/workflow) so a workflow in the same broad scope is not mistaken for a
   declared member of a specific group."
  :workflow-group/member-kind)

(def member-workflow-id-key
  "Canonical member workflow-id key."
  :workflow-group/workflow-id)

(def member-hash-algorithm-key
  "Canonical member hash-algorithm key committed by workflow-group-member-hash."
  :workflow-group/member-hash-algorithm)

(def ^:const member-hash-domain
  "Domain-separated member hash domain for workflow-group members. Not reused by
   claim-set, check-set, authorisation-scope, source-tree, or related-claims
   member hashing."
  "WORKFLOW_GROUP_MEMBER_V1")

(defn normalize-workflow-id
  "Framework-neutral canonicalization of a workflow identity to a plain integer.
   Accepts integers (canonical), numeric strings (\"0\"), and keyword-like values
   (\":0\"). Non-parseable values are returned unchanged so callers can fail
   cleanly via map lookup."
  [workflow-id]
  (cond
    (integer? workflow-id) workflow-id

    (string? workflow-id)
    (let [s (if (.startsWith workflow-id ":")
              (subs workflow-id 1)
              (str/trim workflow-id))]
      (if (re-matches #"\d+" s)
        (Long/parseLong s)
        workflow-id))

    (keyword? workflow-id) (normalize-workflow-id (name workflow-id))

    :else workflow-id))

(defn workflow-group-member
  "Construct a canonical workflow-group member from a member kind and a workflow
   identity. Returns {:workflow-group/member-kind kind,
   :workflow-group/workflow-id <normalized>}."
  [kind workflow-id]
  {member-kind-key kind
   member-workflow-id-key (normalize-workflow-id workflow-id)})

(defn workflow-group-member?
  "True when `member` ({member-kind, workflow-id}) is an actual declared member of
   the group given by `members` (a seq of canonical member maps). Compares the
   normalized workflow-id and the member kind.

   This distinguishes an actual declared member of a specific workflow group from
   a workflow merely being in the same broad scope."
  [members member]
  (boolean
   (some (fn [m]
           (and (= (get m member-kind-key) (get member member-kind-key))
                (= (normalize-workflow-id (get m member-workflow-id-key))
                   (normalize-workflow-id (get member member-workflow-id-key)))))
         members)))

(defn workflow-group-member-hash
  "Domain-separated canonical hash of one workflow-group member, committing the
   member kind, the normalized workflow-id, and the hash algorithm. Rejects
   unsupported hash algorithms rather than silently falling back to SHA-256."
  [member]
  (let [algo (halgo/validate-hash-algorithm!
              (get member member-hash-algorithm-key halgo/default-hash-algorithm))]
    (hash/domain-hash
     member-hash-domain
     {member-hash-algorithm-key algo
      member-kind-key (get member member-kind-key)
      member-workflow-id-key (normalize-workflow-id
                              (get member member-workflow-id-key))})))

(defn valid-workflow-group-members?
  "Pure structural predicate over a seq of canonical member maps: the group is
   non-empty and contains no duplicate member identity (same kind + normalized
   workflow-id). Existence of members against a world and any cross-group rules
   are consumer-specific and not checked here."
  [members]
  (let [ids (map (fn [m]
                   [(get m member-kind-key)
                    (normalize-workflow-id (get m member-workflow-id-key))])
                 members)]
    (boolean (and (seq members)
                  (= (count ids) (count (distinct ids)))))))

;; ---------------------------------------------------------------------------
;; workflow-group.v1 — first-class hashed artifact
;; ---------------------------------------------------------------------------

(def ^:const workflow-group-schema-version
  "workflow-group.v1")

(def ^:const group-hash-domain
  "WORKFLOW_GROUP_V1")

(defn workflow-group-hash
  "Canonical content hash of a workflow-group.v1 artifact, committing the sorted
   member identity hashes, member count, semantics, and hash algorithm. Member
   ordering is canonicalized (member hashes are sorted), so the group hash is
   order-independent. Rejects unsupported hash algorithms rather than silently
   falling back to SHA-256.
    
    Semantics is converted from set to sorted vector for canonical hashing."
  ([members semantics]
   (workflow-group-hash members semantics halgo/default-hash-algorithm))
  ([members semantics hash-algorithm]
   (let [algo (halgo/validate-hash-algorithm! hash-algorithm)
         member-hashes (vec (sort (map workflow-group-member-hash members)))
         semantics-vec (vec (sort semantics))]
     (hash/domain-hash
      group-hash-domain
      {:workflow-group/schema-version workflow-group-schema-version
       :workflow-group/members member-hashes
       :workflow-group/member-count (count member-hashes)
       :workflow-group/semantics semantics-vec
       :workflow-group/hash-algorithm algo}))))

(defn workflow-group
  "Construct a frozen workflow-group.v1 artifact over `members` (a seq of member
   maps) and `semantics`. Members are normalized to canonical workflow-group
   members and the artifact commits its hash, schema version, and hash algorithm.
   Consumer-specific validation (existence, cross-group rules, authorization) is
   intentionally not applied here."
  [members semantics]
  (let [members' (mapv (fn [m]
                         (workflow-group-member (get m member-kind-key)
                                                (get m member-workflow-id-key)))
                       members)]
    {:workflow-group/schema-version workflow-group-schema-version
     :workflow-group/members members'
     :workflow-group/member-count (count members')
     :workflow-group/semantics semantics
     :workflow-group/hash-algorithm halgo/default-hash-algorithm
     :workflow-group/hash (workflow-group-hash members' semantics)}))
