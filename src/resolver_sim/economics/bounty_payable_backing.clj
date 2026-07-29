(ns resolver-sim.economics.bounty-payable-backing
  "bounty-payable-backing.v1

   Content-addressed artifact representing restricted backing for a bounty
   payable. The backing classifies the amount already deducted from funding
   allocations as restricted — it does not create a new economic credit or
   debit.

   The backing record commits to:
   - payable reference
   - distribution root
   - amount and scale
   - source allocations (where the value was deducted from)
   - restriction kind
   - lifecycle status

   Key accounting rule:
   outstanding bounty payable = restricted bounty backing
   while gross conservation remains:
     final allocations + outstanding payables = gross slash amount"
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version "bounty-payable-backing.v1")

(def backing-lifecycle-states
  #{:committed :consumed :released})

;; ── hash projection ─────────────────────────────────────────────────────────

(defn backing-hash-projection
  [backing]
  (select-keys backing
               [:schema-version
                :backing/id
                :backing/payable-root
                :backing/payable-id
                :backing/distribution-root
                :backing/amount
                :backing/source-allocations
                :backing/kind
                :backing/lifecycle
                :backing/context]))

(defn backing-hash
  [backing]
  (hc/domain-hash :bounty-payable-backing-v1
                  (backing-hash-projection backing)))

;; ── builder ─────────────────────────────────────────────────────────────────

(defn build-bounty-payable-backing
  "Build a bounty-payable-backing.v1 artifact.

   Args:
     :payable-root        — root hash of the bounty-payable artifact
     :payable-id          — payable identifier
     :distribution-root   — root hash of the slash-distribution artifact
     :amount              — backing amount (non-negative integer)
     :source-allocations  — map of {source-allocation-id amount-deducted}
     :kind                — backing kind keyword (default :funding-deduction-restricted)
     :context             — any additional context map (optional)

   Returns the backing map with :backing/hash attached.

   The backing must not exceed the total deduction amount. It classifies
   already-deducted value as restricted rather than creating a new deduction."
  [{:keys [payable-root payable-id distribution-root amount source-allocations kind context
           backing-lifecycle]
    :or {kind :funding-deduction-restricted context {}}
    :as args}]
  (when-not payable-root
    (throw (ex-info "bounty-payable-backing requires :payable-root"
                    {:provided payable-root})))
  (when-not (and (integer? amount) (not (neg? amount)))
    (throw (ex-info "bounty-payable-backing requires non-negative :amount"
                    {:provided amount})))
  (when (and amount source-allocations (not= amount (reduce + 0 (vals source-allocations))))
    (throw (ex-info "bounty-payable-backing :amount must equal sum of :source-allocations"
                    {:amount amount :source-sum (reduce + 0 (vals source-allocations))})))
  (let [custom-backing-id (when (contains? args :backing/id) (:backing/id args))
        effective-id (or custom-backing-id (str "backing-" payable-id))
        base {:schema-version schema-version
              :backing/id effective-id
              :backing/payable-root payable-root
              :backing/payable-id payable-id
              :backing/distribution-root distribution-root
              :backing/amount amount
              :backing/source-allocations (or source-allocations {})
              :backing/kind kind
              :backing/lifecycle (or backing-lifecycle :committed)
              :backing/context context}
        h (backing-hash base)]
    (assoc base :backing/hash h)))

;; ── validation ──────────────────────────────────────────────────────────────

(defn validate-bounty-payable-backing
  [backing]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version backing))
                 (conj :unsupported-schema-version)
                 (not (string? (:backing/id backing)))
                 (conj :missing-backing-id)
                 (not (string? (:backing/payable-root backing)))
                 (conj :missing-payable-root)
                 (not (and (integer? (:backing/amount backing))
                           (not (neg? (:backing/amount backing)))))
                 (conj :invalid-amount)
                 (and (:backing/lifecycle backing)
                      (not (contains? backing-lifecycle-states (:backing/lifecycle backing))))
                 (conj :unsupported-lifecycle-state))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-bounty-payable-backing
  [backing]
  (let [v (validate-bounty-payable-backing backing)]
    (if-not (:valid? v)
      v
      (let [computed (backing-hash backing)
            stored (:backing/hash backing)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false :errors [:hash-mismatch]
           :stored stored :computed computed})))))

(defn backing-amount-reconciliation
  "Returns the backing amount sum from source-allocations.
   Useful for verifying that the backing amount matches deductions."
  [backing]
  (reduce + 0 (vals (:backing/source-allocations backing))))
