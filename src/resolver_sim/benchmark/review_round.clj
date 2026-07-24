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
     :force-authorisation   — requires exact target, policy, approvals, branch reference"
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "benchmark-review-round.v1")

(def ^:const review-purposes
  "Controlled vocabulary for review-round purposes."
  #{:model-admission :model-replication :model-challenge
    :model-revision :sampling-report :force-authorisation})

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
    :label "Force authorisation"}})

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
;; :force-authorisation  target, policy                          approvals, branch

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
                          :review-round/force-target}})

(def ^:private ^:const finalisation-requirements
  "Outputs required to FINALISE a review round for each purpose.
   nil means no additional requirements beyond creation."
  {:model-admission     nil
   :model-replication   #{:review-round/run-report-refs}
   :model-challenge     #{:review-round/challenge-evidence-ref}
   :model-revision      nil
   :sampling-report     nil
   :force-authorisation #{:review-round/approval-set
                          :review-round/branch-descriptor}})

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
  (let [purpose (or purpose :model-admission)]
    (when-not (contains? review-purposes purpose)
      (throw (ex-info (str "Invalid review-round purpose: " purpose)
                      {:purpose purpose :allowed review-purposes})))
    (let [reqs (check-creation-requirements purpose ctx)]
      (when-not (:valid? reqs)
        (throw (ex-info (str "Review-round creation requirements not met: " (:errors reqs))
                        {:purpose purpose :errors (:errors reqs)}))))
    (let [review-round-id (str "review-round:"
                               (hc/domain-hash :review-round-identity
                                               {:benchmark/content-root content-root
                                                :members (vec (sort-by :researcher/id members))
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
  [round]
  (and (= schema-version (:schema-version round))
       (some? (:review-round/id round))
       (some? (:benchmark/content-root round))
       (contains? review-purposes (:review-round/purpose round))
       (= 3 (count (:review-round/members round)))
       (every? :researcher/id (:review-round/members round))
       (every? :role (:review-round/members round))))

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
    (let [members (:review-round/members round)]
      (when-not (= 3 (count members))
        (swap! errors conj (str "expected 3 members, got " (count members))))
      (doseq [m members]
        (when-not (:researcher/id m)
          (swap! errors conj "member missing :researcher/id"))
        (when-not (:role m)
          (swap! errors conj "member missing :role"))))
    {:valid? (empty? @errors) :errors @errors}))

(defn member-role
  "Return the role keyword for a given researcher-id, or nil if not found."
  [round researcher-id]
  (some #(when (= researcher-id (:researcher/id %)) (:role %))
        (:review-round/members round)))
