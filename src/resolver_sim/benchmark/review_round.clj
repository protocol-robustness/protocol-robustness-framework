(ns resolver-sim.benchmark.review-round
  "Benchmark review round: a frozen three-member cell evaluating one
    version of a benchmark content entry.
    
    Membership is frozen per round, not permanently per benchmark.
    
    Purpose-specific validation enforces different requirements per review purpose:
      :model-admission       — requires content-root, policy-root, three members
      :model-replication     — requires three completed run reports, compatible execution scopes
      :model-challenge       — requires challenge target, reason code, evidence reference
      :model-revision        — requires parent and proposed content roots, change-set
      :sampling-report       — requires sampling comparison policy, compatible parameter domains
      :force-authorisation   — requires exact target, policy, approvals, branch reference,
                               reservation, terminal receipt, evidence profile
      :pro-rata-allocation   — requires allocation result, mechanism, policy, witness
      :pro-rata-application  — requires propagation, application, world state, evidence ladder,
                               state write-back evidence
      :pro-rata-execution    — requires outcome manifest, allocation and application
                               evidence profiles, theorem and conclusion bindings"
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "benchmark-review-round.v1")

(def ^:const review-purposes
  "Controlled vocabulary for review-round purposes."
  #{:model-admission :model-replication :model-challenge
    :model-revision :sampling-report :force-authorisation
    :pro-rata-allocation :pro-rata-application :pro-rata-execution})

(def ^:const review-statuses
  "Controlled vocabulary for review-round status."
  #{:open :closed :superseded})

(def ^:const member-roles
  "Controlled vocabulary for researcher roles in a review round."
  #{:model-steward :independent-reproducer :adversarial-reviewer})

;; ── Purpose requirement definitions ──────────────────────────────────────

(def ^:private purpose-requirements
  "Maps each purpose to a set of required input keys and an optional
   validation predicate (fn [ctx] -> {:valid? bool :errors [string]})."
  {:model-admission
   {:required-inputs #{:benchmark/content-root :review-round/policy-root}
    :label "Model admission"}

   :model-replication
   {:required-inputs #{:benchmark/content-root :review-round/policy-root
                       :review-round/run-report-refs}
    :label "Model replication"}

   :model-challenge
   {:required-inputs #{:benchmark/content-root :review-round/policy-root
                       :review-round/challenge-target
                       :review-round/challenge-reason-code
                       :review-round/challenge-evidence-ref}
    :label "Model challenge"}

   :model-revision
   {:required-inputs #{:benchmark/content-root
                       :review-round/parent-content-root
                       :review-round/proposed-content-root
                       :review-round/change-set-root}
    :label "Model revision"}

   :sampling-report
   {:required-inputs #{:benchmark/content-root :review-round/policy-root
                       :review-round/sampling-comparison-policy-root}
    :label "Sampling report"}

   :force-authorisation
   {:required-inputs #{:benchmark/content-root :review-round/policy-root
                       :review-round/force-target
                       :review-round/approval-set
                       :review-round/branch-descriptor}
    :label "Force authorisation"}

   :pro-rata-allocation
   {:required-inputs #{:benchmark/content-root
                       :review-round/allocation-result-ref
                       :review-round/allocation-mechanism-ref
                       :review-round/allocation-policy-ref
                       :review-round/allocation-witness-ref}
    :label "Pro-rata allocation evidence"}

   :pro-rata-application
   {:required-inputs #{:benchmark/content-root
                       :review-round/propagation-ref
                       :review-round/application-ref
                       :review-round/state-wb-evidence-ref
                       :review-round/continuity-evidence-ref
                       :review-round/evidence-ladder-ref}
    :label "Pro-rata application evidence"}

   :pro-rata-execution
   {:required-inputs #{:benchmark/content-root
                       :review-round/outcome-manifest-ref
                       :review-round/allocation-evidence-ref
                       :review-round/application-evidence-ref
                       :review-round/theorem-refs
                       :review-round/conclusion-refs}
    :label "Pro-rata execution evidence"}})

;; ── Purpose requirements: creation vs finalisation ───────────────────────
;;
;; Creation requirements — inputs needed to open a review round.
;; Finalisation requirements — outputs needed before the round can close.
;;
;; Purpose               Creation                                Finalisation
;; :model-admission      content-root, members, policy           N/A (admission is final)
;; :model-replication    content-root, members, policy           three completed run reports
;; :model-challenge      target, category, policy                evidence, positions
;; :model-revision       parent, proposed, change-set            positions, certificate
;; :sampling-report      content-root, sampling policy           sample count, coverage
;; :force-authorisation  target, policy                          approvals, branch, reservation,
;;                                                               receipt, evidence profile
;; :pro-rata-allocation  result-ref, mechanism-ref               witness, policy binding
;; :pro-rata-application propagation-ref, application-ref        state-wb, continuity, ladder
;; :pro-rata-execution   outcome-manifest-ref                    allocation-evidence,
;;                                                               application-evidence,
;;                                                               theorem-refs, conclusion-refs

(def ^:private ^:const creation-requirements
  "Inputs required to CREATE a review round for each purpose."
  {:model-admission     #{:benchmark/content-root :review-round/policy-root}
   :model-replication   #{:benchmark/content-root :review-round/policy-root}
   :model-challenge     #{:benchmark/content-root :review-round/policy-root
                          :review-round/challenge-target
                          :review-round/challenge-reason-code}
   :model-revision      #{:benchmark/content-root
                          :review-round/parent-content-root
                          :review-round/proposed-content-root
                          :review-round/change-set-root}
   :sampling-report     #{:benchmark/content-root :review-round/policy-root
                          :review-round/sampling-comparison-policy-root}
   :force-authorisation #{:benchmark/content-root :review-round/policy-root
                          :review-round/force-target}
   :pro-rata-allocation #{:benchmark/content-root
                          :review-round/allocation-result-ref
                          :review-round/allocation-mechanism-ref}
   :pro-rata-application #{:benchmark/content-root
                           :review-round/propagation-ref
                           :review-round/application-ref}
   :pro-rata-execution   #{:benchmark/content-root
                           :review-round/outcome-manifest-ref}})

(def ^:private ^:const finalisation-requirements
  "Outputs required to FINALISE a review round for each purpose.
   nil means no additional requirements beyond creation."
  {:model-admission     nil
   :model-replication   #{:review-round/run-report-refs}
   :model-challenge     #{:review-round/challenge-evidence-ref}
   :model-revision      nil
   :sampling-report     nil
   :force-authorisation #{:review-round/approval-set
                          :review-round/branch-descriptor}
   :pro-rata-allocation #{:review-round/allocation-witness-ref
                          :review-round/allocation-policy-ref}
   :pro-rata-application #{:review-round/state-wb-evidence-ref
                           :review-round/continuity-evidence-ref
                           :review-round/evidence-ladder-ref}
   :pro-rata-execution   #{:review-round/allocation-evidence-ref
                           :review-round/application-evidence-ref
                           :review-round/theorem-refs
                           :review-round/conclusion-refs}})

(defn check-creation-requirements
  "Validate that a review-round context satisfies its purpose's creation requirements.
   
   purpose — the review purpose keyword
   ctx     — map that may include purpose-specific inputs
   
   Returns {:valid? bool :purpose keyword :errors [string]}."
  [purpose ctx]
  (let [required (get creation-requirements purpose)]
    (if-not required
      {:valid? false :purpose purpose
       :errors [(str "Unknown purpose: " purpose)]}
      (let [ctx-keys (set (keys ctx))
            missing (vec (sort (remove #(contains? ctx-keys %) required)))
            errors (cond-> []
                     (seq missing)
                     (conj (str "Missing creation inputs for purpose " purpose ": " missing)))]
        {:valid? (empty? errors) :purpose purpose :errors errors}))))

(defn check-finalisation-requirements
  "Validate that a review-round context satisfies its purpose's finalisation requirements.
   
   Returns {:valid? bool :purpose keyword :errors [string]}.
   Returns {:valid? true} when the purpose has no finalisation requirements."
  [purpose ctx]
  (let [required (get finalisation-requirements purpose)]
    (if (nil? required)
      {:valid? true :purpose purpose :errors []}
      (let [ctx-keys (set (keys ctx))
            missing (vec (sort (remove #(contains? ctx-keys %) required)))
            errors (cond-> []
                     (seq missing)
                     (conj (str "Missing finalisation outputs for purpose " purpose ": " missing)))]
        {:valid? (empty? errors) :purpose purpose :errors errors}))))

(declare valid-member-key?)

;; ── Review-member constructor ─────────────────────────────────────────────

(defn review-member
  "Construct a review-member map.

   Required:
     :researcher/id   — qualified keyword identifying the researcher
     :role             — member role keyword from member-roles

   Optional:
     :review-member/key — non-negative integer key for keyed rounds.
                          When absent, the member is unkeyed (legacy).

   Validates local member shape only.  Collection-level properties
   (duplicate IDs, duplicate keys, dense keys, mixed keyed/unkeyed)
   are enforced by build-review-round and validate-round.

   Returns a member map with the serialized shape preserved."
  [researcher-id role & {:keys [review-member-key]}]
  (when-not (contains? member-roles role)
    (throw (ex-info (str "Invalid member role: " role)
                    {:member {:role role} :allowed member-roles})))
  (when (some? review-member-key)
    (when-not (valid-member-key? review-member-key)
      (throw (ex-info (str "Invalid member key: " review-member-key)
                      {:key review-member-key}))))
  (cond-> {:researcher/id researcher-id :role role}
    (some? review-member-key) (assoc :review-member/key review-member-key)))

;; ── Member-key predicates ─────────────────────────────────────────────────

(defn valid-member-key?
  "True when k is a non-negative integer."
  [k]
  (and (integer? k) (not (neg? k))))

(defn round-uses-member-keys?
  "True when every member of the round carries a :review-member/key.
   A round is keyed only when all members have explicit keys."
  [round]
  (let [members (:review-round/members round)]
    (and (seq members)
         (every? :review-member/key members))))

(defn unique-member-keys?
  "True when no two members share the same :review-member/key."
  [members]
  (let [ks (map :review-member/key members)]
    (= (count ks) (count (set ks)))))

(defn dense-member-key-set?
  "True when member keys form a dense zero-based set: #{0..n-1}."
  [members]
  (= (set (map :review-member/key members))
     (set (range (count members)))))

(defn assign-consecutive-member-keys
  "Assign :review-member/key 0..n-1 in caller vector order.
   Caller declares that insertion order is semantically meaningful.
   Does NOT modify the original member maps — returns new ones.
   Validates each constructed member through review-member."
  [members]
  (mapv (fn [idx m]
          (review-member (:researcher/id m) (:role m)
                         :review-member-key idx))
        (range) members))

;; ── Round builder ─────────────────────────────────────────────────────────

(defn build-review-round
  "Build a benchmark review round with frozen membership.
   
   Required: benchmark/content-root, members (3), membership-frozen-at, policy-root.
   
   Context may include purpose-specific fields
   (e.g. :review-round/run-report-refs for :model-replication).
   
   Raises ex-info if purpose-specific requirements are not met."
  [{:keys [benchmark/content-root
           review-round/purpose
           review-round/members
           review-round/membership-frozen-at
           review-round/policy-root
           review-round/status]
    :as ctx}]
  (let [purpose (or purpose :model-admission)
        st (or status :open)]
    (when-not (contains? review-purposes purpose)
      (throw (ex-info (str "Invalid review-round purpose: " purpose)
                      {:purpose purpose :allowed review-purposes})))
    (when-not (contains? review-statuses st)
      (throw (ex-info (str "Invalid review-round status: " st)
                      {:status st :allowed review-statuses})))
    (when-not (and (seq members) (= 3 (count members)))
      (throw (ex-info "Review-round requires exactly three members"
                      {:member-count (count members)})))
    (when (nil? membership-frozen-at)
      (throw (ex-info "Review-round requires :membership-frozen-at" {})))
    (doseq [m members]
      ;; Validate each member through the review-member constructor.
      ;; This ensures local member shape is consistent without
      ;; duplicating validation logic.
      (review-member (:researcher/id m) (:role m) :review-member-key (:review-member/key m)))
    (let [keyed? (every? :review-member/key members)]
      (when (and keyed? (not (unique-member-keys? members)))
        (throw (ex-info "Duplicate review-member keys"
                        {:members members})))
      (when (and keyed? (not (dense-member-key-set? members)))
        (throw (ex-info "Non-dense review-member keys"
                        {:keys (map :review-member/key members)})))
      (when (and keyed? (not (every? valid-member-key? (map :review-member/key members))))
        (throw (ex-info "Invalid review-member keys"
                        {:keys (map :review-member/key members)})))
      (when (some? (some :review-member/key members))
        (when-not keyed?
          (throw (ex-info "Mixed keyed and unkeyed members in review round"
                          {:members members})))))
    (let [reqs (check-creation-requirements purpose ctx)]
      (when-not (:valid? reqs)
        (throw (ex-info (str "Review-round creation requirements not met: " (:errors reqs))
                        {:purpose purpose :errors (:errors reqs)}))))
    (let [keyed? (every? :review-member/key members)
          sorted-members (if keyed?
                           (vec (sort-by :review-member/key members))
                           (vec (sort-by :researcher/id members)))
          review-round-id (str "review-round:"
                                (hc/domain-hash :review-round-identity
                                                {:benchmark/content-root content-root
                                                 :members sorted-members
                                                 :membership-frozen-at membership-frozen-at
                                                 :policy-root policy-root
                                                 :purpose purpose}))]
      {:schema-version schema-version
       :review-round/id review-round-id
       :benchmark/content-root content-root
       :review-round/purpose purpose
       :review-round/members (vec members)
       :review-round/membership-frozen-at membership-frozen-at
       :review-round/policy-root policy-root
       :review-round/status (or status :open)})))

;; ── Accessors and validation ──────────────────────────────────────────────

(defn round-id
  "Return the unique review-round identifier."
  [round]
  (:review-round/id round))

(defn round-members
  "Return the vector of three frozen members."
  [round]
  (:review-round/members round))

(defn member-ids
  "Return a vector of researcher-ids for all members."
  [round]
  (mapv :researcher/id (:review-round/members round)))

(defn round-purpose
  "Return the review purpose keyword."
  [round]
  (:review-round/purpose round))

(defn round-valid?
  "Quick structural check for a review round."
  [round]
  (let [members (:review-round/members round)]
    (and (= schema-version (:schema-version round))
         (some? (:review-round/id round))
         (some? (:benchmark/content-root round))
         (contains? review-purposes (:review-round/purpose round))
         (contains? review-statuses (:review-round/status round :open))
         (= 3 (count members))
         (every? :researcher/id members)
         (every? (fn [m] (contains? member-roles (:role m))) members)
         (or (not (some? (some :review-member/key members)))
             (and (every? :review-member/key members)
                  (every? valid-member-key? (map :review-member/key members))
                  (unique-member-keys? members)
                  (dense-member-key-set? members))))))

(defn validate-round
  "Standalone validator for a loaded review round.
   
   Checks schema version, required fields, purpose validity, membership
   structure, and creation requirements.
   
   Returns {:valid? bool :errors [string]}."
  [round]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version round))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version round))))
    (when-not (some? (:review-round/id round))
      (swap! errors conj "missing :review-round/id"))
    (when-not (some? (:benchmark/content-root round))
      (swap! errors conj "missing :benchmark/content-root"))
    (let [purpose (:review-round/purpose round)]
      (when-not (contains? review-purposes purpose)
        (swap! errors conj (str "invalid purpose: " purpose)))
      (let [reqs (check-creation-requirements purpose round)]
        (when-not (:valid? reqs)
          (doseq [e (:errors reqs)] (swap! errors conj e)))))
    (let [st (:review-round/status round :open)]
      (when-not (contains? review-statuses st)
        (swap! errors conj (str "invalid status: " st))))
    (let [members (:review-round/members round)
          keyed? (some? (some :review-member/key members))]
      (when-not (= 3 (count members))
        (swap! errors conj (str "expected 3 members, got " (count members))))
      (doseq [m members]
        (when-not (:researcher/id m)
          (swap! errors conj "member missing :researcher/id"))
        (when-not (contains? member-roles (:role m))
          (swap! errors conj (str "invalid role: " (:role m) " in member"))))
      (when keyed?
        (when-not (every? :review-member/key members)
          (swap! errors conj "mixed keyed and unkeyed members"))
        (when (every? :review-member/key members)
          (when-not (unique-member-keys? members)
            (swap! errors conj "duplicate review-member keys"))
          (when-not (dense-member-key-set? members)
            (swap! errors conj (str "non-dense review-member keys: " (map :review-member/key members))))
          (doseq [m members]
            (when-not (valid-member-key? (:review-member/key m))
              (swap! errors conj (str "invalid member key: " (:review-member/key m)))))
          (let [id->key (group-by :researcher/id members)]
            (doseq [[rid ms] id->key]
              (when (> (count ms) 1)
                (swap! errors conj (str "duplicate researcher/id under different keys: " rid))))))))
    (let [purpose (:review-round/purpose round)
          fin-reqs (check-finalisation-requirements purpose round)]
      (when-not (:valid? fin-reqs)
        (doseq [e (:errors fin-reqs)] (swap! errors conj (str "finalisation: " e)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn member-role
  "Return the role keyword for a given researcher-id, or nil if not found."
  [round researcher-id]
  (some #(when (= researcher-id (:researcher/id %)) (:role %))
        (:review-round/members round)))

;; ── Member-key lookup ─────────────────────────────────────────────────────

(defn member-by-key
  "Return the member map for a given member key, or nil."
  [round key]
  (some #(when (= key (:review-member/key %)) %)
        (:review-round/members round)))

(defn member-key-for-researcher
  "Return the :review-member/key for a given researcher-id, or nil."
  [round researcher-id]
  (some #(when (= researcher-id (:researcher/id %))
           (:review-member/key %))
        (:review-round/members round)))

(defn researcher-id-for-member-key
  "Return the :researcher/id for a given member key, or nil."
  [round key]
  (:researcher/id (member-by-key round key)))

(defn member-keys
  "Return a vector of member keys for all members.
   Returns nil when the round is not keyed (no member has a key).
   Returns a vector only when all members have keys."
  [round]
  (when (round-uses-member-keys? round)
    (mapv :review-member/key (:review-round/members round))))
