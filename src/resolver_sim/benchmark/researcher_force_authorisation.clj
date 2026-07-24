(ns resolver-sim.benchmark.researcher-force-authorisation
  "Research force-authorisation: a two-of-three, scoped, dissent-preserving
   authorisation for exceptional research-benchmark actions.
   
   Policy/instance split:
     - The model defines the force-authorisation primitive and policy
       (member-count, threshold, scope-required?, single-use?, ...).
     - A particular authorisation is a review-round artifact that references
       the model policy and supplies the target, approvals and dissents.
   
   This does NOT call the SEW protocol force-authorisation — it is a
   package-level abstraction at the research benchmark layer."
  (:require [clojure.set]))

;; ── Policy definition ─────────────────────────────────────────────────────

(def ^:const default-policy
  "Default three-member force-authorisation policy.
   The benchmark model may override these values."
  {:force-authorisation-policy/schema-version "three-member-force-authorisation-policy.v1"
   :member-count 3
   :threshold 2
   :scope-required? true
   :single-use? true
   :preserve-dissent? true
   :creates-branch? true
   :expiry-required? true})

(defn policy-valid?
  "Validate a force-authorisation policy map."
  [policy]
  (and (= "three-member-force-authorisation-policy.v1"
          (:force-authorisation-policy/schema-version policy))
       (>= (:member-count policy 3) 1)
       (>= (:threshold policy 2) 1)
       (<= (:threshold policy 2) (:member-count policy 3))))

;; ── Authorisation instance ────────────────────────────────────────────────

(defn build-authorisation
  "Build a force-authorisation instance.
   
   review-round      — the benchmark-review-round.v1 map
   target-case-hash  — hash of the benchmark-case being force-authorised
   approvals         — vector of {:researcher/id id :signed-content-hash hash :timestamp iso}
   dissents          — vector of {:researcher/id id :reason string :timestamp iso}
   policy            — force-authorisation policy map (defaults)
   expires-at        — ISO-8601 timestamp or nil
   forced-branch-root — content root of the forced branch (or nil for declared branches)
   
   Returns {:status :authorised-with-dissent | :authorised-unanimous | :blocked
            :authorisation map}"
  [{:keys [review-round target-case-hash approvals dissents policy expires-at forced-branch-root]}]
  (let [policy (merge default-policy policy)
        threshold (:threshold policy 2)
        member-ids (set (map :researcher/id (:review-round/members review-round)))
        approval-ids (set (map :researcher/id approvals))
        dissent-ids (set (map :researcher/id dissents))
        valid-approvals (clojure.set/intersection approval-ids member-ids)
        valid-dissents (clojure.set/intersection dissent-ids member-ids)
        overlapping (clojure.set/intersection valid-approvals valid-dissents)
        effective-approvals (clojure.set/difference valid-approvals overlapping)
        approval-count (count effective-approvals)
        status (cond
                 (>= approval-count threshold)
                 (if (seq valid-dissents)
                   :authorised-with-dissent
                   :authorised-unanimous)
                 :else :blocked)
        instance {:schema-version "research-force-authorisation.v1"
                  :benchmark/content-root (:benchmark/content-root review-round)
                  :review-round/id (:review-round/id review-round)
                  :target-case-hash target-case-hash
                  :authorisation/policy (select-keys policy
                                                     [:member-count :threshold
                                                      :scope-required? :single-use?
                                                      :preserve-dissent? :creates-branch?
                                                      :expiry-required?])
                  :authorisation/approvals (vec approvals)
                  :authorisation/dissents (vec dissents)
                  :authorisation/status status
                  :authorisation/expires-at expires-at
                  :forced-branch-root forced-branch-root}]
    {:status status
     :authorisation instance}))

(defn authorisation-valid?
  "Validate structural integrity of a force-authorisation instance."
  [instance]
  (and (= "research-force-authorisation.v1" (:schema-version instance))
       (some? (:benchmark/content-root instance))
       (some? (:review-round/id instance))
       (some? (:target-case-hash instance))
       (some? (:authorisation/status instance))))

;; ── Status helpers ────────────────────────────────────────────────────────

(defn authorisation-status
  "Return the current status of a force-authorisation instance."
  [instance]
  (:authorisation/status instance))

(defn authorisation-approved?
  "True when the instance has reached the threshold for authorisation."
  [instance]
  (contains? #{:authorised-with-dissent :authorised-unanimous}
             (:authorisation/status instance)))

(defn authorisation-dissents
  "Return the vector of dissenting member records."
  [instance]
  (:authorisation/dissents instance []))

(defn authorisation-approvals
  "Return the vector of approving member records."
  [instance]
  (:authorisation/approvals instance []))
