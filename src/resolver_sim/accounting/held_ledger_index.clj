(ns resolver-sim.accounting.held-ledger-index
  "Shared Malli contract for the held-ledger index.

   A held-ledger index is a five-dimensional cumulative map derived from
   replaying held-adjustment operations.  Both the live Sew protocol path
   (update-ledger-index in accounting.clj) and the independent replay path
   (replay-held-adjustment-state in assurance/custody.clj) produce indexes
   that MUST satisfy this schema.

   The implementations remain intentionally independent so that the replay
   path can detect bugs in the live path.  Shared validation catches
   structural drift; semantic equivalence is verified through differential
   tests that compare live and replay outputs."
  (:require [malli.core :as m]))

;; ── Schema versioning ──────────────────────────────────────────────────────────
;; Increment held-ledger-index-v1-schema on any breaking structural change.
;; The version identifier is for code-level contract matching, not persisted
;; state.  Do not embed it in the internal :held-ledger/index map unless a
;; persisted-state migration requires it.

(def ^:const schema-version
  "Code-level schema version.  Bump on breaking changes."
  "held-ledger-index-v1")

;; ── Dimension registry ────────────────────────────────────────────────────────

(def ^:const index-dimensions
  "The five dimensions of a held-ledger index, in canonical order.
   :by-owner may contain negative amounts (net outflow from an address);
   all other dimensions are non-negative by invariant."
  [:by-token :by-position :by-account :by-owner :by-workflow])

;; ── Malli schemas ─────────────────────────────────────────────────────────────

(def Amount
  "A single custody-flow amount.
   Non-negative in :by-token, :by-position, :by-account, :by-workflow.
   :by-owner entries may be negative."
  :int)

(def DimensionMap
  "A map from a dimension key to an integer amount.
   Key types vary per dimension: :by-token and :by-account use keywords,
   :by-position uses vectors, :by-owner uses address strings,
   :by-workflow uses numeric or keyword workflow IDs."
  [:map-of any? int?])

(def held-ledger-index-schema
  "Malli schema for a complete five-dimensional held-ledger index.
   Key types reflect what the Sew protocol actually produces:
     :by-token    — keyword token identifiers (e.g. :USDC, :ETH)
     :by-position — vector position-ids (e.g. [:held/position :USDC :escrow-principal 0])
     :by-account  — keyword account types (e.g. :escrow-principal)
     :by-owner    — string addresses (e.g. \"0xA1b2C3d4E5f6\")
     :by-workflow — integer or keyword workflow IDs"
  [:map {:closed true}
   [:by-token    [:map-of :keyword  Amount]]
   [:by-position [:map-of any?     Amount]]
   [:by-account  [:map-of :keyword Amount]]
   [:by-owner    [:map-of :string  int?]]
   [:by-workflow [:map-of any?     Amount]]])

(def held-custody-state-schema
  "Malli schema for the materialised custody state that wraps an index.
   :total-held and :held/positions are aliases of the corresponding index
   dimensions, kept at the top level for direct world-state access.
   :total-held keys are token keywords; :held/positions keys are vector
   position-ids."
  [:map
   [:held-ledger/index held-ledger-index-schema]
   [:total-held        [:map-of :keyword Amount]]
   [:held/positions    [:map-of any?    Amount]]])

;; ── Empty / default ───────────────────────────────────────────────────────────

(defn empty-held-ledger-index
  "Return an empty (zeroed) held-ledger index."
  []
  {:by-token    {}
   :by-position {}
   :by-account  {}
   :by-owner    {}
   :by-workflow {}})

;; ── Validation predicates ─────────────────────────────────────────────────────

(defn valid-held-ledger-index?
  "True when `idx` conforms to held-ledger-index-schema."
  [idx]
  (m/validate held-ledger-index-schema idx))

(defn validate-held-ledger-index
  "Throw ex-info if `idx` does not conform to the schema.
   Returns `idx` unchanged on success."
  [idx]
  (when-not (valid-held-ledger-index? idx)
    (throw (ex-info "held-ledger-index schema violation"
                    {:type :held-ledger-index-invalid
                     :schema-version schema-version
                     :explain (m/explain held-ledger-index-schema idx)
                     :index idx})))
  idx)

(defn explain-held-ledger-index
  "Return Malli explain data for `idx`, or nil if valid."
  [idx]
  (m/explain held-ledger-index-schema idx))

;; ── Custody state validation ──────────────────────────────────────────────────

(defn valid-held-custody-state?
  [state]
  (m/validate held-custody-state-schema state))

(defn validate-held-custody-state
  [state]
  (when-not (valid-held-custody-state? state)
    (throw (ex-info "held-custody-state schema violation"
                    {:type :held-custody-state-invalid
                     :schema-version schema-version
                     :explain (m/explain held-custody-state-schema state)
                     :state state})))
  state)

(defn explain-held-custody-state
  [state]
  (m/explain held-custody-state-schema state))

;; ── Reconcile helpers (index ↔ top-level aliases) ─────────────────────────────

(defn reconcile?
  "True when the top-level :total-held and :held/positions match the
   corresponding index dimensions."
  [state]
  (let [idx (:held-ledger/index state)]
    (and (= (:total-held state) (:by-token idx))
         (= (:held/positions state) (:by-position idx)))))
