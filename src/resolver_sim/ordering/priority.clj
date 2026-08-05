(ns resolver-sim.ordering.priority
  "Domain-neutral priority-order.v1 primitive.

   Converts a set of subjects into an explicit, evidence-backed ordered
   partition of priority classes. Owns subject membership, strict precedence
   between classes, equal-priority grouping, the basis on which priority was
   derived, unclassified-subject handling, canonical representation, integrity
   checks, queries, and derived claims.

   Deliberately does not own available liquidity, claim amounts, partial-fill
   arithmetic, pro-rata weighting, custody mutation, settlement execution, or
   temporal scheduling. This namespace must not depend on liquidity, claim
   amount, accounting, or allocation implementation namespaces.

   Composition rule:
     priority determines when a class becomes eligible;
     a separate composition layer decides how capacity is shared among the
     members of that class.

   The principal output is an evidence-backed ordered partition of a subject
   set. Members of the same priority class carry no precedence semantics; a
   distinct serialization policy is required before equal-priority subjects
   may be executed sequentially."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Version and artifact identity
;; ──────────────────────────────────────────────────────────────────────────────

(def artifact-kind :priority-order)
(def artifact-version :priority-order.v1)

;; ──────────────────────────────────────────────────────────────────────────────
;; Errors
;; ──────────────────────────────────────────────────────────────────────────────

(defn- invalid!
  [reason data]
  (throw (ex-info "Invalid priority-order request" (assoc data :reason reason))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Canonical subject identity
;; ──────────────────────────────────────────────────────────────────────────────

(defn canonical-subject-key
  "A typed, byte-stable ordering key for subject identities.

   `pr-str` is deliberately not used: printed representation is not a
   priority contract. Map ordering is supplied solely by the canonical
   encoding. Mirrors the pro-rata row-id contract so the same identity
   orders identically across allocation boundaries."
  [identity]
  (try
    (hc/validate-canonical-value! identity)
    (letfn [(key* [value]
              (cond
                (keyword? value) [:keyword (or (namespace value) "") (name value)]
                (string? value) [:string value]
                (vector? value) [:vector (mapv key* value)]
                (map? value) [:map (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value))]
                :else [:scalar (mapv #(bit-and (int %) 0xff) (hc/canonical-bytes value))]))]
      (key* identity))
    (catch clojure.lang.ExceptionInfo error
      (invalid! :unsupported-subject-id
                {:subject/id identity :cause (ex-data error)}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Priority methods (extension registry)
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Each registered method declares:
;;   :method/name                — registry key (also the :comparison-basis :method)
;;   :method/field               — the priority-key field that drives comparison
;;   :method/description         — human contract for the method
;;   :method/validate-key-fn     — reject a malformed classifier result
;;   :method/group-key-fn        — classifier result -> canonical grouping key
;;   :method/compare-keys-fn     — compare two grouping keys (ascending) -> -1|0|1
;;   :method/comparison-contract — the algebraic contract of the comparison
;;   :method/parameter-projection — comparison-basis -> canonical committed basis
;;   :method/evidence-projection  — comparison-basis -> derivation evidence

(defn- integer-value-validate
  [method-name field]
  (fn [value]
    (when-not (integer? value)
      (invalid! :invalid-priority-key-value
                {:method method-name :field field :value value :expected :integer}))))

(defn- normalize-instant
  [value]
  (if (instance? java.time.Instant value) (str value) value))

(defn- instant-or-string-value-validate
  [method-name field]
  (fn [value]
    (when-not (or (string? value) (instance? java.time.Instant value))
      (invalid! :invalid-priority-key-value
                {:method method-name :field field :value value :expected :string-or-instant}))))

(defn- normalize-priority-key
  "Project a classifier result into canonical-safe data before it is stored in
   the artifact. Instants become ISO-8601 strings, sets become sorted vectors,
   and other runtime values are projected deterministically. The result is
   validated canonical-safe so the artifact and all roots remain pure data."
  [key]
  (let [projected (:structure (hc/project-world-to-structure-view key :priority-order-key))]
    (hc/validate-canonical-value! projected)
    projected))

(defn- declared-value-method
  "Construct a total-preorder priority method over one classifier-key field.
   Lower values are higher priority (rank 0) under the :ascending comparator;
   the comparator direction may flip this via :comparator :descending."
  [method-name field description normalize validate]
  {:method/name method-name
   :method/field field
   :method/description description
   :method/comparison-contract {:relation :total-preorder
                                :reflexive? true
                                :transitive? true
                                :total-between-classes? true
                                :ties-permitted? true}
   :method/validate-key-fn
   (fn [key]
     (when (nil? (get key field))
       (invalid! :missing-priority-key-field
                 {:method method-name :field field :key key}))
     (when validate
       (validate (get key field))))
   :method/group-key-fn
   (fn [key] {field (normalize (get key field))})
   :method/compare-keys-fn
   (fn [left right] (compare (get left field) (get right field)))
   :method/parameter-projection
   (fn [basis]
     (cond-> {:method method-name}
       (:parameter-root basis) (assoc :parameter-root (:parameter-root basis))))
   :method/evidence-projection
   (fn [basis]
     {:method method-name
      :field field
      :parameter-root (:parameter-root basis)})})

(def priority-methods
  "Initial generic priority-method registry. Methods are extension-backed:
   domain-specific methods should be registered rather than added as branches
   in the primitive. Each method compares its declared field ascending."
  {:declared-tier
   (declared-value-method :declared-tier :priority/tier
                          "Declared numeric tier; lower tier is higher priority"
                          identity (integer-value-validate :declared-tier :priority/tier))

   :timestamp
   (declared-value-method :timestamp :priority/timestamp
                          "ISO-8601 timestamp; earlier timestamp is higher priority"
                          normalize-instant (instant-or-string-value-validate :timestamp :priority/timestamp))

   :deadline
   (declared-value-method :deadline :priority/deadline
                          "ISO-8601 deadline; earlier deadline is higher priority"
                          normalize-instant (instant-or-string-value-validate :deadline :priority/deadline))

   :severity
   (declared-value-method :severity :priority/severity
                          "Declared severity rank; lower rank is higher priority"
                          identity (integer-value-validate :severity :priority/severity))

   :stake-class
   (declared-value-method :stake-class :priority/stake-class-rank
                          "Declared stake-class rank; lower rank is higher priority"
                          identity (integer-value-validate :stake-class :priority/stake-class-rank))

   :security-interest
   (declared-value-method :security-interest :priority/security-interest-rank
                          "Declared security-interest rank; lower rank is higher priority"
                          identity (integer-value-validate :security-interest :priority/security-interest-rank))

   :governance-rank
   (declared-value-method :governance-rank :priority/governance-rank
                          "Declared governance rank; lower rank is higher priority"
                          identity (integer-value-validate :governance-rank :priority/governance-rank))

   :dependency-depth
   (declared-value-method :dependency-depth :priority/dependency-depth
                          "Declared dependency depth; shallower depth is higher priority"
                          identity (integer-value-validate :dependency-depth :priority/dependency-depth))})

(defn register-method!
  "Register or replace a priority method in the extension registry.
   Returns the updated registry map."
  [method]
  (when-not (and (map? method)
                 (:method/name method)
                 (:method/group-key-fn method)
                 (:method/compare-keys-fn method)
                 (:method/comparison-contract method))
    (invalid! :malformed-priority-method {:method method}))
  (alter-var-root #'priority-methods
                  (fn [registry] (assoc registry (:method/name method) method))))

(defn resolve-method
  [method-name]
  (or (priority-methods method-name)
      (invalid! :unknown-priority-method
                {:method method-name :known (vec (sort (keys priority-methods)))})))

;; ──────────────────────────────────────────────────────────────────────────────
;; Input validation and classification
;; ──────────────────────────────────────────────────────────────────────────────

(defn- validate-subjects
  [subjects]
  (let [subjects (vec (or subjects []))
        ids (mapv :subject/id subjects)]
    (when (some nil? ids)
      (invalid! :missing-subject-id {:subjects subjects}))
    (when-not (= (count ids) (count (distinct ids)))
      (invalid! :duplicate-subject-id {:subject-ids ids}))
    (doseq [subject subjects]
      (canonical-subject-key (:subject/id subject)))
    (vec (sort-by (comp canonical-subject-key :subject/id) subjects))))

(defn- classify-subjects
  "Classify every subject exactly once. Each entry is
   {:subject/id <id> :priority/classified <bool> :priority/key <key-or-nil>}."
  [subjects classifier method]
  (mapv (fn [subject]
          (let [id (:subject/id subject)
                result (try
                         (classifier subject)
                         (catch clojure.lang.ExceptionInfo error
                           (throw (ex-info "Priority classifier failed"
                                           {:reason :classifier-failed
                                            :subject/id id
                                            :cause (ex-data error)} error)))
                         (catch Exception error
                           (throw (ex-info "Priority classifier failed"
                                           {:reason :classifier-failed
                                            :subject/id id}
                                           error))))]
            (if (nil? result)
              {:subject/id id :priority/classified false}
              (do
                (when-not (map? result)
                  (invalid! :invalid-priority-key {:subject/id id :value result}))
                ((:method/validate-key-fn method) result)
                {:subject/id id
                 :priority/classified true
                 :priority/key (normalize-priority-key result)}))))
        subjects))

(defn- compute-ordered-groups
  "Group classified entries by their canonical grouping key and order the
   distinct groups using the method comparator, honoring comparator direction.
   Members within each group are canonically sorted and carry no precedence
   semantics."
  [classified-entries method comparator-direction]
  (let [groups (group-by (fn [entry]
                           ((:method/group-key-fn method) (:priority/key entry)))
                         classified-entries)
        method-compare (:method/compare-keys-fn method)
        compare-fn (if (= comparator-direction :descending)
                     (fn [left right]
                       (method-compare (:priority/key right) (:priority/key left)))
                     (fn [left right]
                       (method-compare (:priority/key left) (:priority/key right))))]
    (->> groups
         vals
         (mapv (fn [entries]
                 {:priority/key ((:method/group-key-fn method)
                                 (:priority/key (first entries)))
                  :priority/members (->> entries
                                         (mapv :subject/id)
                                         (sort-by canonical-subject-key)
                                         vec)}))
         (sort compare-fn)
         vec)))

(defn derive-classes
  "Assign dense, canonical ranks to the ordered priority classes implied by a
   collection of per-subject classification entries. Handles the synthetic
   unclassified class for :highest-priority / :lowest-priority policies.

   Returns {:classes <vector of {:priority/rank :priority/key :members}>
            :unclassified <vector of subject ids>}."
  [subject-keys method unclassified-policy comparator-direction]
  (let [classified (filterv #(:priority/classified %) subject-keys)
        unclassified (->> subject-keys
                          (filterv #(not (:priority/classified %)))
                          (mapv :subject/id)
                          (sort-by canonical-subject-key)
                          vec)
        base (mapv (fn [group rank]
                     {:priority/rank rank
                      :priority/key (:priority/key group)
                      :members (:priority/members group)})
                   (compute-ordered-groups classified method comparator-direction)
                   (range))
        unclassified-class (when (seq unclassified)
                             {:priority/rank nil
                              :priority/key {:priority/unclassified true}
                              :members unclassified})
        classes (case unclassified-policy
                  :reject base
                  :highest-priority (if unclassified-class
                                      (cons unclassified-class base)
                                      base)
                  :lowest-priority (if unclassified-class
                                     (conj base unclassified-class)
                                     base)
                  (invalid! :unsupported-unclassified-policy
                            {:unclassified-policy unclassified-policy}))]
    {:classes (mapv (fn [class rank]
                      (assoc class :priority/rank rank))
                    classes (range))
     :unclassified (vec unclassified)}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Content addressing
;; ──────────────────────────────────────────────────────────────────────────────

(defn- component-root
  "Domain-separated commitment to one artifact component."
  [component data]
  (hc/hash-with-intent {:hash/intent :priority-order-v1}
                       {:priority/component component
                        :priority/data data}))

(defn- subject-set-root
  [subjects]
  (component-root :subject-set {:priority/subjects subjects}))

(defn- comparison-basis-root
  [basis]
  (component-root :comparison-basis {:priority/basis basis}))

(defn- members-root
  [class]
  (component-root :members
                  {:priority/rank (:priority/rank class)
                   :priority/key (:priority/key class)
                   :priority/members (:members class)}))

(defn- classes-root
  [classes]
  (component-root :classes
                  {:priority/classes
                   (mapv (fn [class]
                           {:priority/rank (:priority/rank class)
                            :priority/key (:priority/key class)
                            :priority/members (:members class)
                            :priority/members-root (:members-root class)})
                         classes)}))

(defn- attach-members-roots
  [classes]
  (mapv (fn [class]
          (assoc class :members-root (members-root class)))
        classes))

(defn- canonical-comparison-basis
  [method basis]
  (when-not (map? basis)
    (invalid! :missing-comparison-basis {:comparison-basis basis}))
  (when-not (fn? (:method/parameter-projection method))
    (invalid! :malformed-priority-method {:method (:method/name method)}))
  (let [projected ((:method/parameter-projection method) basis)]
    (hc/validate-canonical-value! projected)
    projected))

(defn- finalize-artifact
  "Attach the content hash and exact preimage to an artifact body. The hash is
   computed over the body before the envelope is attached, so the body and the
   stored preimage can never disagree.

   Envelope metadata is attached last and is excluded from the canonical body,
   preimage, content hash, integrity roots, equality, and derived claims."
  [body metadata]
  (let [content-hash (hc/hash-with-intent {:hash/intent :priority-order-v1} body)]
    (cond-> (assoc body
                   :artifact/preimage (pr-str body)
                   :artifact/content-hash content-hash)
      (seq metadata) (assoc :artifact/metadata metadata))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Construction API
;; ──────────────────────────────────────────────────────────────────────────────

(defn build-priority-order
  "Build a content-addressed priority-order.v1 artifact.

   Request:
     :subjects            — vector of {:subject/id ... :subject/kind ...}
     :classifier          — (fn [subject] priority-key-map | nil)
     :comparison-basis    — {:method <registered-method> :parameter-root ...}
     :tie-policy          — :equal-priority (only supported tie policy)
     :unclassified-policy — :reject (default) | :highest-priority | :lowest-priority
     :comparator          — :ascending (default) | :descending
     :metadata            — optional envelope map (excluded from canonical
                            commitment); :priority/id may also be supplied as a
                            convenience and is folded into the envelope.

   The classifier returns a priority key (e.g. {:priority/tier 1
   :priority/reason :secured-claim}); nil marks an unclassified subject. The
   builder validates every subject, classifies each exactly once, groups equal
   priority keys, orders the groups with the declared comparator, assigns
   canonical dense ranks, and emits a content-addressed artifact."
  [{:keys [subjects classifier comparison-basis tie-policy unclassified-policy
           comparator metadata]
    :or {tie-policy :equal-priority
         unclassified-policy :reject
         comparator :ascending}
    :as request}]
  (when-not (fn? classifier)
    (invalid! :missing-classifier {:request request}))
  (when-not (= tie-policy :equal-priority)
    (invalid! :unsupported-tie-policy {:tie-policy tie-policy}))
  (when-not (#{:reject :highest-priority :lowest-priority} unclassified-policy)
    (invalid! :unsupported-unclassified-policy {:unclassified-policy unclassified-policy}))
  (when-not (#{:ascending :descending} comparator)
    (invalid! :unsupported-comparator {:comparator comparator}))
  (let [subjects (validate-subjects subjects)
        method (resolve-method (:method comparison-basis))
        basis (canonical-comparison-basis method comparison-basis)
        subject-keys (classify-subjects subjects classifier method)
        {:keys [classes unclassified]} (derive-classes subject-keys method
                                                       unclassified-policy comparator)
        _ (when (and (= unclassified-policy :reject) (seq unclassified))
            (invalid! :unclassified-subjects {:subjects unclassified}))
        classes (attach-members-roots classes)
        envelope-metadata (cond-> (or metadata {})
                            (some? (get request :priority/id))
                            (assoc :priority/id (get request :priority/id)))
        body {:artifact/kind artifact-kind
              :artifact/version artifact-version
              :subjects subjects
              :subject-priority-keys (vec (sort-by (comp canonical-subject-key :subject/id)
                                                   subject-keys))
              :priority-classes classes
              :comparison-basis basis
              :comparison-contract (:method/comparison-contract method)
              :tie-policy tie-policy
              :unclassified-policy unclassified-policy
              :derivation {:method (:method/name method)
                           :comparator comparator
                           :tie-policy tie-policy
                           :unclassified-policy unclassified-policy}
              :subject-set-root (subject-set-root subjects)
              :comparison-basis-root (comparison-basis-root basis)
              :priority-classes-root (classes-root classes)}]
    (finalize-artifact body envelope-metadata)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Query surface
;; ──────────────────────────────────────────────────────────────────────────────

(defn priority-classes
  "Ordered priority classes (rank 0 first)."
  [artifact]
  (:priority-classes artifact))

(defn- rank-index
  [artifact]
  (persistent!
   (reduce (fn [acc class]
             (reduce (fn [acc member]
                       (assoc! acc member (:priority/rank class)))
                     acc (:members class)))
           (transient {})
           (:priority-classes artifact))))

(defn priority-rank
  "Rank of a subject, or nil when the subject is not a member of the order."
  [artifact subject-id]
  (get (rank-index artifact) subject-id))

(defn priority-class
  "The priority class containing the subject, or nil."
  [artifact subject-id]
  (some (fn [class]
          (when (some #{subject-id} (:members class)) class))
        (:priority-classes artifact)))

(defn priority-class-by-rank
  "The priority class at a rank, or nil."
  [artifact rank]
  (first (filter #(= rank (:priority/rank %)) (:priority-classes artifact))))

(defn next-priority-class
  "The priority class immediately after the given rank, or nil."
  [artifact rank]
  (priority-class-by-rank artifact (inc rank)))

(defn equal-priority?
  "True when both subjects are members of the same priority class."
  [artifact left right]
  (let [rank-left (priority-rank artifact left)
        rank-right (priority-rank artifact right)]
    (and (some? rank-left) (some? rank-right) (= rank-left rank-right))))

(defn higher-priority?
  "True when left must be considered before right."
  [artifact left right]
  (let [rank-left (priority-rank artifact left)
        rank-right (priority-rank artifact right)]
    (and (some? rank-left) (some? rank-right) (< rank-left rank-right))))

(defn lower-priority?
  "True when left must be considered after right."
  [artifact left right]
  (let [rank-left (priority-rank artifact left)
        rank-right (priority-rank artifact right)]
    (and (some? rank-left) (some? rank-right) (> rank-left rank-right))))

(defn compare-priority
  "Semantic priority comparison:
     :higher :equal :lower :unclassified
   Returns :unclassified when either subject is not a member of the order."
  [artifact left right]
  (let [rank-left (priority-rank artifact left)
        rank-right (priority-rank artifact right)]
    (cond
      (or (nil? rank-left) (nil? rank-right)) :unclassified
      (= rank-left rank-right) :equal
      (< rank-left rank-right) :higher
      :else :lower)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Verification
;; ──────────────────────────────────────────────────────────────────────────────

(defn recomputed-content-hash
  "Recompute the artifact content hash from the body (envelope stripped)."
  [artifact]
  (hc/hash-with-intent {:hash/intent :priority-order-v1}
                       (dissoc artifact :artifact/content-hash
                               :artifact/preimage :artifact/metadata)))

(defn priority-order-violations
  "Independently verify a persisted priority-order.v1 artifact.

   Recomputes subject-set membership, per-subject priority keys, grouping into
   equivalence classes, ordering between classes, dense ranks, class and
   whole-order roots, and the artifact content hash. Never invokes the
   builder's classifier."
  [artifact]
  (let [method (resolve-method (get-in artifact [:derivation :method]))
        comparator (get-in artifact [:derivation :comparator])
        unclassified-policy (get-in artifact [:derivation :unclassified-policy])
        classes (:priority-classes artifact)
        members-flat (mapcat :members classes)
        declared-subjects (:subjects artifact)]
    (vec
     (concat
      ;; Schema identity
      (cond-> []
        (not= artifact-kind (:artifact/kind artifact))
        (conj {:reason :priority-order/artifact-kind-mismatch
               :expected artifact-kind :observed (:artifact/kind artifact)})
        (not= artifact-version (:artifact/version artifact))
        (conj {:reason :priority-order/artifact-version-mismatch
               :expected artifact-version :observed (:artifact/version artifact)})
        (not= (:tie-policy artifact) :equal-priority)
        (conj {:reason :priority-order/unsupported-tie-policy
               :observed (:tie-policy artifact)})
        (not (contains? #{:reject :highest-priority :lowest-priority} unclassified-policy))
        (conj {:reason :priority-order/unsupported-unclassified-policy
               :observed unclassified-policy}))

      ;; Content addressing
      (let [expected-hash (recomputed-content-hash artifact)
            expected-preimage (pr-str (dissoc artifact :artifact/content-hash
                                              :artifact/preimage :artifact/metadata))]
        (cond-> []
          (not= (:artifact/content-hash artifact) expected-hash)
          (conj {:reason :priority-order/content-hash-mismatch
                 :expected expected-hash :observed (:artifact/content-hash artifact)})
          (not= (:artifact/preimage artifact) expected-preimage)
          (conj {:reason :priority-order/preimage-mismatch
                 :expected expected-preimage :observed (:artifact/preimage artifact)})))

      ;; Subject-key coverage
      (let [keys-by-id (frequencies (map :subject/id (:subject-priority-keys artifact)))
            declared-ids (map :subject/id declared-subjects)
            missing-keys (filterv #(not (contains? keys-by-id %)) declared-ids)
            duplicate-keys (for [[id count] keys-by-id :when (< 1 count)] id)
            unclassified-entries (filterv #(not (:priority/classified %))
                                          (:subject-priority-keys artifact))]
        (cond-> []
          (seq missing-keys)
          (conj {:reason :priority-order/missing-subject-priority-key
                 :subject-ids (vec missing-keys)})
          (seq duplicate-keys)
          (conj {:reason :priority-order/duplicate-subject-priority-key
                 :subject-ids (vec duplicate-keys)})
          (and (seq unclassified-entries) (= :reject unclassified-policy))
          (conj {:reason :priority-order/unclassified-subject-with-reject-policy
                 :subject-ids (mapv :subject/id unclassified-entries)})))

      ;; Membership completeness and exclusivity
      (let [declared (set (map :subject/id declared-subjects))
            members (set members-flat)
            missing (set/difference declared members)
            extra (set/difference members declared)]
        (cond-> []
          (seq missing)
          (conj {:reason :priority-order/incomplete-membership :subject-ids (vec missing)})
          (seq extra)
          (conj {:reason :priority-order/extra-members :subject-ids (vec extra)})
          (not= (count members-flat) (count (distinct members-flat)))
          (conj {:reason :priority-order/duplicate-membership
                 :subject-ids (vec (for [[id count] (frequencies members-flat)
                                         :when (< 1 count)] id))})))

      ;; Structural class checks
      (mapcat (fn [class]
                (let [canonical-members (vec (sort-by canonical-subject-key (:members class)))
                      expected-members-root (members-root class)]
                  (cond-> []
                    (empty? (:members class))
                    (conj {:reason :priority-order/empty-class
                           :priority/rank (:priority/rank class)})
                    (not= canonical-members (:members class))
                    (conj {:reason :priority-order/non-canonical-member-order
                           :priority/rank (:priority/rank class)})
                    (not= expected-members-root (:members-root class))
                    (conj {:reason :priority-order/members-root-mismatch
                           :priority/rank (:priority/rank class)
                           :expected expected-members-root
                           :observed (:members-root class)}))))
              classes)

      ;; Dense ranks
      (let [ranks (mapv :priority/rank classes)]
        (when-not (= ranks (vec (range (count classes))))
          [{:reason :priority-order/non-dense-ranks
            :expected (vec (range (count classes))) :observed ranks}]))

      ;; Stable equality, class ordering, and synthetic-class placement
      (let [reconstruction (try
                             (derive-classes (:subject-priority-keys artifact)
                                             method unclassified-policy comparator)
                             (catch Exception error
                               {:reconstruction-failed? true
                                :cause (ex-data error)}))
            structural (fn [classes]
                         (mapv #(select-keys % [:priority/rank :priority/key :members])
                               classes))]
        (cond-> []
          (:reconstruction-failed? reconstruction)
          (conj {:reason :priority-order/class-reconstruction-failed
                 :cause (:cause reconstruction)})
          (and (:classes reconstruction)
               (not= (structural (:classes reconstruction)) (structural classes)))
          (conj {:reason :priority-order/class-reconstruction-mismatch
                 :expected (structural (:classes reconstruction))
                 :observed (structural classes)})))

      ;; Component roots
      (cond-> []
        (not= (:subject-set-root artifact) (subject-set-root declared-subjects))
        (conj {:reason :priority-order/subject-set-root-mismatch
               :expected (subject-set-root declared-subjects)
               :observed (:subject-set-root artifact)})
        (not= (:comparison-basis-root artifact)
              (comparison-basis-root (:comparison-basis artifact)))
        (conj {:reason :priority-order/comparison-basis-root-mismatch
               :expected (comparison-basis-root (:comparison-basis artifact))
               :observed (:comparison-basis-root artifact)})
        (not= (:priority-classes-root artifact) (classes-root classes))
        (conj {:reason :priority-order/classes-root-mismatch
               :expected (classes-root classes)
               :observed (:priority-classes-root artifact)}))))))

(defn valid-priority-order?
  [artifact]
  (empty? (priority-order-violations artifact)))

;; ──────────────────────────────────────────────────────────────────────────────
;; High-value derived claims
;; ──────────────────────────────────────────────────────────────────────────────

(defn derived-claims
  "Evidence-backed structural claims over a priority-order artifact that
   downstream settlement, allocation, benchmark, or governance artifacts can
   reference without duplicating priority logic."
  [artifact]
  [{:claim/kind :priority-completeness
    :holds? (empty? (priority-order-violations artifact))}
   {:claim/kind :priority-policy-reproduction
    :holds? (= (:artifact/content-hash artifact) (recomputed-content-hash artifact))}
   {:claim/kind :priority-membership-complete
    :holds? (= (set (map :subject/id (:subjects artifact)))
               (set (mapcat :members (:priority-classes artifact))))}
   {:claim/kind :priority-dense-ranks
    :holds? (= (mapv :priority/rank (:priority-classes artifact))
               (vec (range (count (:priority-classes artifact)))))}])

(defn equal-priority-claim
  "Claim that two subjects share a priority class."
  [artifact left right]
  {:claim/kind :equal-priority
   :subjects [left right]
   :holds? (equal-priority? artifact left right)})

(defn strict-precedence-claim
  "Claim that the higher subject must be considered before the lower subject."
  [artifact higher lower]
  {:claim/kind :strict-precedence
   :higher higher
   :lower lower
   :holds? (higher-priority? artifact higher lower)})
