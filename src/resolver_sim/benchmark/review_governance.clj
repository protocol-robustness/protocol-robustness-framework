(ns resolver-sim.benchmark.review-governance
  "Canonical, root-addressed governance facts for review authority.

   This namespace owns governance facts and constitution evaluation.  It does
   not verify cryptographic signatures and it does not mutate governance state.
   Consumers resolve a governance artifact by its committed root, constitute a
   review set, then authenticate submitted positions against that constitution."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "review-governance.v1")
(def ^:const governance-root-key :review-governance/root)
(def ^:const constitution-statuses #{:valid :invalid :unresolved})
(def ^:const independence-statuses #{:independent :not-independent :independence-unresolved})
(def ^:const principal-statuses #{:active :suspended :revoked})
(def ^:const member-statuses #{:active :suspended :revoked})
(def ^:const key-statuses #{:active :retired :revoked})
(def ^:const equivocation-policies #{:invalid-seat :count-as-dissent :fail-certificate})

(def ^:private top-level-keys
  #{:schema-version :governance/epoch :governance/roles :governance/principals
    :governance/members :governance/policies})

(defn- active? [status] (= :active status))
(defn- at-or-before? [from at] (or (nil? from) (not (pos? (compare from at)))))
(defn- before? [until at] (or (nil? until) (pos? (compare until at))))
(defn- valid-at? [entry at]
  (and (active? (:status entry))
       (string? at)
       (at-or-before? (:valid-from entry) at)
       (before? (:valid-until entry) at)))

(defn- canonical-value [value]
  (cond
    (set? value) (mapv canonical-value (sort-by pr-str value))
    (map? value) (into {} (map (fn [[k v]] [k (canonical-value v)]) value))
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn governance-projection
  "The complete canonical governance state.  The validator rejects unknown
   top-level keys before this projection is committed; policy role sets are
   projected as deterministic vectors because canonical hashing has no set type."
  [governance]
  (canonical-value (select-keys governance top-level-keys)))

(defn governance-root [governance]
  (hash-ref/sha256-ref
   (hc/domain-hash :review-governance-v1 (governance-projection governance))))

(defn validate-governance
  "Validate the closed, canonical review-governance.v1 shape and its root.
   Returns {:valid? bool :errors [...]} without trusting a declared root."
  [governance]
  (let [errors (atom [])
        err! #(swap! errors conj %)
        principals (:governance/principals governance)
        members (:governance/members governance)
        policies (:governance/policies governance)]
    (when-not (map? governance) (err! "governance must be a map"))
    (when (map? governance)
      (when-not (= schema-version (:schema-version governance))
        (err! (str "expected schema-version " schema-version)))
      (when-not (= top-level-keys (set (keys governance)))
        (err! "governance has missing or unknown top-level keys"))
      (when-not (and (integer? (:governance/epoch governance))
                     (not (neg? (:governance/epoch governance))))
        (err! "governance epoch must be a non-negative integer"))
      (when-not (and (set? (:governance/roles governance)) (seq (:governance/roles governance)))
        (err! "governance roles must be a non-empty set"))
      (doseq [[label coll] [[:principals principals] [:members members] [:policies policies]]]
        (when-not (vector? coll) (err! (str label " must be a vector"))))
      (when (vector? principals)
        (let [ids (map :principal/id principals)]
          (when-not (= (count ids) (count (set ids)))
            (err! "duplicate principal ids"))))
      (doseq [p (or principals [])]
        (when-not (and (:principal/id p) (contains? principal-statuses (:status p)))
          (err! "invalid principal"))
        (when-not (:principal/independence-group p)
          (err! "principal missing independence group"))
        ;; A missing basis is a resolvable constitution failure, not a malformed
        ;; governance document.  The independence predicate returns
        ;; :independence-unresolved for it and authority fails closed.
        (when (and (some? (:principal/independence-basis-root p))
                   (not (hash-ref/valid-sha256-ref? (:principal/independence-basis-root p))))
          (err! "principal has invalid independence basis root"))
        (when-not (vector? (:principal/keys p))
          (err! "principal keys must be a vector"))
        (doseq [k (:principal/keys p)]
          (when-not (and (:key/id k) (contains? key-statuses (:status k))
                         (= :ed25519 (:key/algorithm k))
                         (string? (:key/public-key k)))
            (err! "invalid signing key"))))
      (when (vector? members)
        (let [ids (map :reviewer/member-id members)
              principal-ids (map :principal/id members)]
          (when-not (= (count ids) (count (set ids)))
            (err! "duplicate member ids"))
          (when-not (= (count principal-ids) (count (set principal-ids)))
            (err! "a principal may bind to only one governed member"))))
      (doseq [m (or members [])]
        (when-not (and (:reviewer/member-id m) (:principal/id m)
                       (contains? member-statuses (:status m)))
          (err! "invalid member"))
        (when-not (and (set? (:granted-roles m))
                       (set/subset? (:granted-roles m) (:governance/roles governance)))
          (err! "member has unrecognized role grant")))
      (doseq [p (or policies [])]
        (when-not (and (:policy/id p) (= 3 (:member-count p))
                       (= 2 (:threshold p))
                       (set? (:required-roles p))
                       (contains? #{:unique :multi} (:role-cardinality p))
                       (contains? equivocation-policies (:equivocation-policy p)))
          (err! "invalid P0 review policy"))
        (when-not (set/subset? (:required-roles p) (:governance/roles governance))
          (err! "policy requires an unknown role"))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn member-by-id [governance member-id]
  (some #(when (= member-id (:reviewer/member-id %)) %) (:governance/members governance)))
(defn principal-by-id [governance principal-id]
  (some #(when (= principal-id (:principal/id %)) %) (:governance/principals governance)))
(defn policy-by-id [governance policy-id]
  (some #(when (= policy-id (:policy/id %)) %) (:governance/policies governance)))

(defn eligible-key-ids
  "All active key IDs which may be used by member at the supplied signing time.
   A set is used because constitution proves the sets of eligible keys cannot
   alias; no particular key is selected until a position is signed."
  [governance member-id signing-time]
  (when-let [member (member-by-id governance member-id)]
    (when-let [principal (principal-by-id governance (:principal/id member))]
      (when (and (valid-at? member signing-time) (valid-at? principal signing-time))
        (->> (:principal/keys principal)
             (filter #(valid-at? % signing-time))
             (map :key/id)
             set)))))

(defn independence
  "P0 independence predicate.  Group equality establishes a prohibited
   relation.  Missing/inactive/stale evidence is unresolved, never independent."
  [governance principal-a principal-b at]
  (let [a (principal-by-id governance principal-a)
        b (principal-by-id governance principal-b)]
    (cond
      (or (nil? a) (nil? b) (not (valid-at? a at)) (not (valid-at? b at))
          (not (hash-ref/valid-sha256-ref? (:principal/independence-basis-root a)))
          (not (hash-ref/valid-sha256-ref? (:principal/independence-basis-root b))))
      :independence-unresolved

      (= principal-a principal-b) :not-independent
      (= (:principal/independence-group a) (:principal/independence-group b)) :not-independent
      :else :independent)))

(defn evaluate-constitution
  "Evaluate governed review-seat constitution before any positions are read.
   `members` are round entries containing :researcher/id and :role; their ID is
   interpreted as the canonical :reviewer/member-id in P0 governed rounds."
  [governance policy-id members constituted-at]
  (let [g-validation (validate-governance governance)
        policy (policy-by-id governance policy-id)
        member-ids (mapv :researcher/id members)
        governed-members (mapv #(member-by-id governance %) member-ids)
        principal-ids (mapv :principal/id governed-members)
        key-sets (mapv #(eligible-key-ids governance % constituted-at) member-ids)
        role-set (set (map :role members))
        pairs (for [[i a] (map-indexed vector principal-ids)
                    b (drop (inc i) principal-ids)] [a b])
        independence-results (mapv (fn [[a b]] {:principals [a b]
                                                :status (independence governance a b constituted-at)}) pairs)
        unresolved? (or (some nil? governed-members)
                        (some #(= :independence-unresolved (:status %)) independence-results))
        errors (cond-> []
                 (not (:valid? g-validation)) (into (:errors g-validation))
                 (nil? policy) (conj "unknown review policy")
                 (not= 3 (count members)) (conj "wrong member count")
                 (not= (count member-ids) (count (set member-ids))) (conj "duplicate member ids")
                 (some nil? governed-members) (conj "unknown member")
                 (not= (count principal-ids) (count (set principal-ids))) (conj "non-distinct principals")
                 (some empty? key-sets) (conj "member has no eligible signing keys")
                 (some identity (for [[i ks] (map-indexed vector key-sets)
                                     other (drop (inc i) key-sets)]
                                 (seq (set/intersection ks other))))
                 (conj "threshold-eligible key shared across members")
                 (some #(= :not-independent (:status %)) independence-results)
                 (conj "independence not satisfied")
                 (and policy (not (set/subset? (:required-roles policy) role-set)))
                 (conj "required role coverage missing")
                 (and policy (= :unique (:role-cardinality policy))
                      (not= (count members) (count role-set)))
                 (conj "duplicate role assignment"))
        status (cond
                 (seq errors) :invalid
                 unresolved? :unresolved
                 :else :valid)]
    {:constitution-status status
     :policy policy
     :governance-root (governance-root governance)
     :member-ids member-ids
     :principal-ids principal-ids
     :eligible-key-sets key-sets
     :independence independence-results
     :errors (vec errors)}))

(defn position-key-valid?
  "Check the concrete signing key selected by a position after constitution.
   Returns false unless the key belongs to the member's principal, was eligible
   at signing time, and is one of the member's governed threshold key set."
  [governance member-id signing-key-id signing-time]
  (contains? (or (eligible-key-ids governance member-id signing-time) #{}) signing-key-id))
