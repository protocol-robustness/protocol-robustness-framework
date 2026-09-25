(ns resolver-sim.evidence.forensic-graph
  "Typed forensic composition over the canonical PRF evidence-node DAG.

   This namespace deliberately introduces no second persisted evidence graph.
   Each entry is an ordinary `resolver-sim.evidence.node` with a small, typed
   extension that binds a rooted subject, epistemic role, causal inputs,
   visibility, and (where relevant) an explicit forensic gap.  The node DAG
   remains the authoritative graph and its existing hash/parent/cycle
   validation remains in force.

   A forensic graph is an in-memory validation view over those nodes plus a
   declared evidence profile.  It does not change the semantic root of the
   result it describes."
  (:require [clojure.set :as set]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.reference :as hash-ref]))

(def forensic-roles
  #{:declared :observed :derived :validated :verified :proved :attested})

(def visibility-classes
  #{:public :restricted :sealed :commitment-only})

(def gap-statuses
  #{:not-observed :not-available :not-verifiable :not-applicable :conflicting})

(def actor-roles
  #{:runner :observer :executor :prover :verifier :attestor :system})

(defn- root? [x] (hash-ref/valid-sha256-ref? x))

(defn- canonical-roots?
  [xs]
  (and (vector? xs) (= xs (vec (sort xs)))
       (= (count xs) (count (set xs))) (every? root? xs)))

(defn valid-subject?
  "A forensic subject is a typed reference to the exact artifact/fact about
   which the node makes a statement.  It is deliberately not a semantic
   artifact wrapper."
  [subject]
  (and (map? subject)
       (= #{:subject/root :subject/kind} (set (keys subject)))
       (root? (:subject/root subject))
       (qualified-keyword? (:subject/kind subject))))

(defn- valid-actor? [actor]
  (or (nil? actor)
      (and (map? actor)
           (= #{:actor/root :actor/role} (set (keys actor)))
           (root? (:actor/root actor))
           (contains? actor-roles (:actor/role actor)))))

(defn- valid-cutpoint? [cutpoint]
  (or (nil? cutpoint)
      (and (map? cutpoint)
           (= #{:cutpoint/root :cutpoint/kind} (set (keys cutpoint)))
           (root? (:cutpoint/root cutpoint))
           (keyword? (:cutpoint/kind cutpoint)))))

(defn- forensic-extension
  [{:keys [subject role visibility causal-parent-roots custody-roots actor cutpoint
           observation-key observed-root gap consequence?]}]
  (let [causal-parent-roots (vec (sort (or causal-parent-roots [])))
        custody-roots (vec (sort (or custody-roots [])))]
    (when-not (and (valid-subject? subject)
                   (contains? forensic-roles role)
                   (contains? visibility-classes visibility)
                   (canonical-roots? causal-parent-roots)
                   (canonical-roots? custody-roots)
                   (valid-actor? actor)
                   (valid-cutpoint? cutpoint)
                   (or (nil? observation-key) (qualified-keyword? observation-key))
                   (or (nil? observed-root) (root? observed-root))
                   (or (nil? gap)
                       (and (map? gap)
                            (= #{:gap/status :gap/reason} (set (keys gap)))
                            (contains? gap-statuses (:gap/status gap))
                            (keyword? (:gap/reason gap))))
                   (boolean? consequence?))
      (throw (ex-info "Invalid typed forensic evidence binding"
                      {:subject subject :role role :visibility visibility})))
    {:forensic/subject subject
     :forensic/role role
     :forensic/visibility visibility
     :forensic/causal-parent-roots causal-parent-roots
     :forensic/custody-roots custody-roots
     :forensic/actor actor
     :forensic/cutpoint cutpoint
     :forensic/observation-key observation-key
     :forensic/observed-root observed-root
     :forensic/gap gap
     :forensic/consequence? consequence?}))

(defn build-forensic-evidence-node
  "Build an ordinary canonical evidence node carrying a typed forensic binding.

   `parent-nodes` are ordinary evidence-node hashes and preserve DAG causality.
   `causal-parent-roots` are typed artifact-level causes, committed in the
   existing node extension and registered as bootstrap roots when they are not
   represented by another node in this graph.  This supports DAG-shaped
   provenance without pretending every canonical artifact is an evidence node.

   A gap is evidence *about incompleteness*, not fabricated positive evidence;
   recording it does not make the underlying semantic artifact fail."
  [{:keys [subject role visibility causal-parent-roots custody-roots parent-nodes actor cutpoint
           observation-key observed-root gap consequence? status]
    :or {visibility :commitment-only parent-nodes [] consequence? false status :pass}}]
  (let [extension (forensic-extension
                    {:subject subject :role role :visibility visibility
                    :causal-parent-roots causal-parent-roots :custody-roots custody-roots :actor actor
                    :cutpoint cutpoint :observation-key observation-key
                    :observed-root observed-root :gap gap
                    :consequence? consequence?})
        parent-nodes (vec (sort (or parent-nodes [])))
        bootstrap-roots (vec (sort (set (concat (:forensic/causal-parent-roots extension)
                                                (:forensic/custody-roots extension)
                                                [(:subject/root subject)]))))]
    (when-not (and (every? string? parent-nodes)
                   (contains? #{:pass :fail :error} status))
      (throw (ex-info "Invalid forensic evidence node parent/status" {})))
    (node/build-execution-node
     {:execution-id :execution/validation
      :status status
      :parent-hashes parent-nodes
      :bootstrap-roots bootstrap-roots
      :runner (some-> actor :actor/root)
      :inputs {:forensic/subject subject
               :forensic/causal-parent-roots (:forensic/causal-parent-roots extension)
               :forensic/cutpoint cutpoint}
      :outputs {:forensic/role role :forensic/observed-root observed-root
                :forensic/gap gap}
      :extensions extension})))

(defn- node-extension [n]
  (:extensions n))

(defn- valid-extension? [extension]
  (try
    (= extension (forensic-extension
                  {:subject (:forensic/subject extension)
                   :role (:forensic/role extension)
                   :visibility (:forensic/visibility extension)
                   :causal-parent-roots (:forensic/causal-parent-roots extension)
                   :custody-roots (:forensic/custody-roots extension)
                   :actor (:forensic/actor extension)
                   :cutpoint (:forensic/cutpoint extension)
                   :observation-key (:forensic/observation-key extension)
                   :observed-root (:forensic/observed-root extension)
                   :gap (:forensic/gap extension)
                   :consequence? (:forensic/consequence? extension)}))
    (catch Exception _ false)))

(defn- subject-key [n]
  (select-keys (:forensic/subject (node-extension n)) [:subject/root :subject/kind]))

(defn- conflicts
  [nodes]
  (->> nodes
       (filter #(and (nil? (:forensic/gap (node-extension %)))
                     (:forensic/observation-key (node-extension %))
                     (:forensic/observed-root (node-extension %))))
       (group-by #(vector (subject-key %) (:forensic/observation-key (node-extension %))))
       (keep (fn [[[subject observation-key] entries]]
               (let [observed (set (map #(get-in % [:extensions :forensic/observed-root]) entries))]
                 (when (> (count observed) 1)
                   {:conflict/status :conflicting
                    :forensic/subject subject
                    :forensic/observation-key observation-key
                    :forensic/observed-roots (vec (sort observed))
                    :evidence-node-hashes (vec (sort (map :node-hash entries)))}))))
       vec))

(defn- profile-gaps
  [nodes profile]
  (let [requirements (:forensic-profile/requirements profile)]
    (vec
     (mapcat
      (fn [{:keys [subject roles required?]}]
        (let [actual (set (map #(get-in % [:extensions :forensic/role])
                               (filter #(= subject (subject-key %)) nodes)))
              missing (set/difference (set roles) actual)]
          (when (seq missing)
            [{:gap/status (if required? :not-observed :not-applicable)
              :gap/reason :required-evidence-role-missing
              :forensic/subject subject
              :forensic/missing-roles (vec (sort missing))
              :forensic/required? (boolean required?)}])))
      requirements))))

(defn- valid-profile? [profile]
  (and (map? profile)
       (= #{:forensic-profile/requirements} (set (keys profile)))
       (vector? (:forensic-profile/requirements profile))
       (every? (fn [{:keys [subject roles required?] :as requirement}]
                 (and (= #{:subject :roles :required?} (set (keys requirement)))
                      (valid-subject? subject)
                      (set? roles) (seq roles) (set/subset? roles forensic-roles)
                      (boolean? required?)))
               (:forensic-profile/requirements profile))))

(defn validate-forensic-evidence-graph
  "Compose existing evidence-node DAG validation with typed forensic checks.

   `profile` is intentionally a small explicit requirement map, not a policy
   language.  It can strengthen evidence requirements but never changes the
   fixed validation floor or semantic artifact identities.  Optional missing
   observations are returned as gaps but do not fail integrity."
  [{:keys [nodes profile artifact-roots]}]
  (let [nodes (vec nodes)
        profile (or profile {:forensic-profile/requirements []})
        artifact-roots (set (or artifact-roots []))
        dag (node/validate-node-dag nodes)
        extensions-valid? (every? #(valid-extension? (node-extension %)) nodes)
        known-roots (set/union artifact-roots
                               (set (map #(get-in % [:extensions :forensic/subject :subject/root]) nodes)))
        causal-errors
        (vec (mapcat (fn [n]
                       (let [extension (node-extension n)
                             missing (set/difference (set (:forensic/causal-parent-roots extension)) known-roots)
                             missing-custody (set/difference (set (:forensic/custody-roots extension)) known-roots)
                             orphan? (and (:forensic/consequence? extension)
                                          (empty? (:forensic/causal-parent-roots extension)))]
                         (cond-> []
                           (seq missing) (conj {:error :forensic/missing-causal-artifact
                                                :node-hash (:node-hash n)
                                                :missing-roots (vec (sort missing))})
                           (seq missing-custody) (conj {:error :forensic/missing-custody-artifact
                                                        :node-hash (:node-hash n)
                                                        :missing-roots (vec (sort missing-custody))})
                           orphan? (conj {:error :forensic/orphan-required-consequence
                                          :node-hash (:node-hash n)})))) nodes))
        profile-valid? (valid-profile? profile)
        gaps (if profile-valid? (profile-gaps nodes profile) [])
        required-gaps (filter :forensic/required? gaps)
        conflict-list (conflicts nodes)
        integrity-valid? (and (:valid? dag) extensions-valid? (empty? causal-errors))
        policy-satisfied? (and profile-valid? (empty? required-gaps))]
    {:valid? (and integrity-valid? policy-satisfied? (empty? conflict-list))
     :integrity-valid? integrity-valid?
     :policy-satisfied? policy-satisfied?
     :errors (vec (concat (:errors dag)
                          (when-not extensions-valid? [{:error :forensic/invalid-extension}])
                          causal-errors
                          (when-not profile-valid? [{:error :forensic/invalid-profile}])
                          (when (seq required-gaps) [{:error :forensic/required-evidence-missing
                                                     :gaps (vec required-gaps)}])
                          (when (seq conflict-list) [{:error :forensic/conflicting-observations
                                                      :conflicts conflict-list}])))
     :gaps gaps
     :conflicts conflict-list
     :dag dag
     :summary {:node-count (count nodes)
               :gap-count (count gaps)
               :conflict-count (count conflict-list)
               :required-gap-count (count required-gaps)}}))
