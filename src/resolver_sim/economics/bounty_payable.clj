(ns resolver-sim.economics.bounty-payable
  "bounty-payable.v1

   Content-addressed artifact representing a bounty payable derived from a
   verified slash-distribution award. The payable is a liability: it records
   the obligation to pay a beneficiary from a specific award.

   The payable is not itself a transfer or settlement. It must be backed by
   a bounty-payable-backing.v1 record before it becomes claimable.

   One payable per award. Multi-source funding does not create multiple payables."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version "bounty-payable.v1")

(def payable-lifecycle-states
  #{:pending-backing :backed :settled :cancelled})

;; ── hash projection ─────────────────────────────────────────────────────────

(defn payable-hash
  "Content-addressed root of a bounty-payable.v1 artifact.  The committed
   identity fields are projected canonical-safe (single source of truth:
   resolver-sim.hash.canonical/project-bounty-payable), so set- or
   seq-bearing :payable/context / :payable/evidence-references hash
   deterministically instead of failing the encoder."
  [payable]
  (hc/domain-hash :bounty-payable-v1
                  (hc/project-bounty-payable payable nil)))

;; ── builder ─────────────────────────────────────────────────────────────────

(defn build-bounty-payable
  "Build a bounty-payable.v1 artifact from a distribution award and context.

   Args:
     :distribution-root  — root hash of the slash-distribution artifact
     :award-id           — award identifier from the distribution
     :beneficiary        — beneficiary identifier string
     :amount             — payable amount (non-negative integer)
     :kind               — obligation kind keyword
     :evidence-references — vector of evidence reference strings (optional)
     :context            — any additional context map (optional)

   Returns the payable map with :payable/hash attached."
  [{:keys [award-id amount beneficiary kind lifecycle distribution-root payable-id
           evidence-references context]
    :or {evidence-references [] context {}}
    :as args}]
  (let [custom-payable-id (when (contains? args :payable/id) (:payable/id args))
        effective-id (or custom-payable-id payable-id (when award-id (str "payable-" award-id)))]
    (when-not (and (string? (str effective-id)) (seq (str effective-id)))
      (throw (ex-info "bounty-payable requires :payable/id or :award-id"
                      {:provided args})))
    (when-not (and (integer? amount) (not (neg? amount)))
      (throw (ex-info "bounty-payable requires non-negative :amount"
                      {:provided amount})))
    (when-not beneficiary
      (throw (ex-info "bounty-payable requires :beneficiary"
                      {:provided args})))
    (let [base {:schema-version schema-version
                :payable/id effective-id
                :payable/distribution-root distribution-root
                :payable/award-id award-id
                :payable/beneficiary beneficiary
                :payable/amount amount
                :payable/kind (or kind :sew.obligation/challenge-bounty)
                :payable/lifecycle (or lifecycle :pending-backing)
                :payable/evidence-references evidence-references
                :payable/context context}
          h (payable-hash base)]
      (assoc base :payable/hash h))))

;; ── validation ──────────────────────────────────────────────────────────────

(defn validate-bounty-payable
  [payable]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version payable))
                 (conj :unsupported-schema-version)
                 (not (string? (:payable/id payable)))
                 (conj :missing-payable-id)
                 (not (and (integer? (:payable/amount payable))
                           (not (neg? (:payable/amount payable)))))
                 (conj :invalid-amount)
                 (nil? (:payable/beneficiary payable))
                 (conj :missing-beneficiary)
                 (and (:payable/lifecycle payable)
                      (not (contains? payable-lifecycle-states (:payable/lifecycle payable))))
                 (conj :unsupported-lifecycle-state))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-bounty-payable
  [payable]
  (let [v (validate-bounty-payable payable)]
    (if-not (:valid? v)
      v
      (let [computed (payable-hash payable)
            stored (:payable/hash payable)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false :errors [:hash-mismatch]
           :stored stored :computed computed})))))

;; ── lifecycle helpers ──────────────────────────────────────────────────────

(defn transition-payable-lifecycle
  "Produce a new payable map with the given lifecycle state and updated hash.
   Returns nil if the transition is invalid."
  [payable new-state]
  (when (contains? payable-lifecycle-states new-state)
    (let [updated (assoc payable :payable/lifecycle new-state)]
      (assoc updated :payable/hash (payable-hash updated)))))
