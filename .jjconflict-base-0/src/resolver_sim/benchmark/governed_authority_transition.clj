(ns resolver-sim.benchmark.governed-authority-transition
  "AUTH-STATE-AFTER governed-authority content transition kernel.

   Governed-authority performs content/configuration adoption, NOT quantity
   effects. This namespace owns the one portable semantic derivation:

       S1 = derive-governed-successor(T, S0, O)

   where T is the exact committed transition definition, S0 the accepted
   authoritative predecessor, and O the independently verified authorised
   target/outcome. S1 is always derived; it is never an input. There is exactly
   one canonical transition-definition in V1 (:configuration-adoption), so T is
   a fixed protocol constant, not caller- or proposal-selected.

   This reuses derive-successor-head, build-envelope-v2, and
   chain-configuration-transition.v1 rather than reimplementing their
   semantics. It does not reuse the pro-rata quantity kernel."
  (:require [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.configuration-head :as config-head]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def transition-definition-schema "governed-authority-transition-definition.v1")
(def material-state-schema "authoritative-material-state.v1")
(def content-transition-schema "governed-authority-content-transition.v1")

;; ── Transition definition (V1: exactly one portable semantic algorithm) ──────

(def transition-definition
  "The single canonical governed-authority transition definition in V1:
   configuration adoption. A fixed protocol constant — never caller- or
   proposal-selected."
  {:artifact/kind transition-definition-schema
   :transition/type :configuration-adoption
   :transition/version 1})

(def transition-definition-root
  "Root of the canonical transition definition. T/root in derive-governed-successor."
  (ref/sha256-ref
   (hc/domain-hash :governed-authority-transition-definition-v1
                   (hc/project-canonical-safe transition-definition))))

;; ── Authoritative material state projection ──────────────────────────────────

(def authoritative-material-state-fields
  "Closed semantic projection of the authoritative material. Only
   protocol-semantic authoritative fields and their frozen bodies; indexes,
   caches, observations, history, and implementation bookkeeping are excluded.
   Unknown/unclassified material must not enter the state root."
  #{:chain-instance-genesis/root
    :chain-configuration/root
    :review-governance/root
    :review-governance-activation/root
    :signer-key-set/root
    :review-round/root
    :review-round/hash
    :position-time-basis/root
    :position-time-index/root
    :control-plane-evidence/root
    :review-governance-admissibility/root
    :authority-material/review-governance
    :authority-material/signer-key-set
    :authority-material/review-round
    :authority-material/position-time-index})

(defn authoritative-material-state
  "M0 — the closed semantic projection of the authoritative material. Fields
   outside the projection are never silently folded into the state root."
  [material]
  (select-keys material authoritative-material-state-fields))

(defn material-state-root
  "Canonical root of an authoritative-material-state projection."
  [m0]
  (ref/sha256-ref
   (hc/domain-hash :authoritative-material-state-v1
                   (hc/project-canonical-safe (authoritative-material-state m0)))))

(defn adopt-configuration-v1
  "M1 — wholly derived: the authoritative material state with the authorised
   proposed content adopted as the new chain-configuration root. No caller
   selects successor material."
  [m0 proposed-content-root]
  (assoc m0 :chain-configuration/root proposed-content-root))

;; ── Pure successor derivation ────────────────────────────────────────────────

(defn- config-transition
  "Rooted chain-configuration-transition.v1 derived from (S0, O). epoch is
   monotonically derived; parent = S0 config; new = authorised proposed content."
  [S0 m0 proposed-content-root]
  (let [parent-head (:configuration/head S0)]
    {:transition/schema "chain-configuration-transition.v1"
     :protocol/genesis-root (:chain-instance-genesis/root m0)
     :target {:target/type :chain-instance
              :target/root (:chain-instance-genesis/root m0)}
     :configuration/parent-root (:chain-configuration/root m0)
     :configuration/new-root proposed-content-root
     :verifier-registry/root (:verifier-registry/root (:config S0))
     :epoch (inc (:configuration/epoch parent-head))}))

(defn derive-governed-successor
  "Pure deterministic successor derivation. S1 is never an input.

   S0 — {:envelope authoritative-envelope :material authoritative-material
         :configuration/head config-head-state
         :config {:verifier-registry/root ...} :config-body parent-configuration}
   O  — {:proposed-content-root ...}

   Returns {:successor-material M1 :successor-config-head H1
            :successor-envelope E1 :successor/root S1 :state-after/root ...}."
  [T S0 O]
  (when-not (= transition-definition-root T)
    (throw (ex-info "non-canonical transition definition" {:transition-definition/root T})))
  (let [m0 (authoritative-material-state (:material S0))
        proposed (:proposed-content-root O)
        m1 (adopt-configuration-v1 m0 proposed)
        state-after-root (material-state-root m1)
        transition (config-transition S0 m0 proposed)
        h1 (config-head/derive-successor-head
            (:configuration/head S0) transition (:config-body S0) (:new-config-body O))
        _ (when-not (= :committed (:status h1))
            (throw (ex-info "successor config-head derivation rejected"
                            {:reason (:reason h1)})))
        envelope1 (state/build-envelope-v2
                   {:chain-instance-genesis/root (:chain-instance-genesis/root m0)
                    :execution/state-root state-after-root
                    :chain-configuration/root proposed
                    :review-governance/root (:review-governance/root m0)
                    :review-governance-activation/root (:review-governance-activation/root m0)
                    :control-plane-evidence/root (:control-plane-evidence/root m0)
                    :position-time-index/root (:position-time-index/root m0)
                    :publication/sequence (inc (:publication/sequence (:envelope S0)))
                    :publication/predecessor-root (:authoritative-state-envelope/root (:envelope S0))}
                   (:configuration/head h1))
        s1 (:authoritative-state-envelope/root envelope1)]
    {:successor-material m1
     :successor-config-head (:configuration/head h1)
     :successor-envelope envelope1
     :successor/root s1
     :state-after/root state-after-root
     :transition/root (genesis/chain-configuration-transition-root transition)}))

;; ── Derived content-transition identity ──────────────────────────────────────

(defn content-transition
  "Produce the closed governed-authority-content-transition.v1 identity. This
   artifact is produced ONLY from an already-derived successor (never from a
   caller-selected successor)."
  [derived S0 O]
  (let [base {:artifact/kind content-transition-schema
              :transition-definition/root transition-definition-root
              :predecessor/root (:authoritative-state-envelope/root (:envelope S0))
              :authorised-target/root (:proposed-content-root O)
              :successor-material/root (:state-after/root derived)
              :configuration-head/root (config-head/head-state-root (:successor-config-head derived))
              :successor/root (:successor/root derived)
              :transition/root (:transition/root derived)}]
    (assoc base :governed-authority-content-transition/root
           (ref/sha256-ref
            (hc/domain-hash :governed-authority-content-transition-v1
                            (hc/project-canonical-safe base))))))

;; ── Derived-successor verification (authoritative path) ──────────────────────

(defn verify-derived-successor
  "Check a candidate successor envelope/material against the derived successor
   (AUTH-LINEAGE-CONSERVATION). S1 is never caller-selected: the candidate must
   equal the mechanically derived successor values."
  [T S0 O candidate-envelope candidate-material]
  (let [derived (derive-governed-successor T S0 O)]
    (cond
      (not= (:successor/root derived) (:authoritative-state-envelope/root candidate-envelope))
      {:valid? false :reason :successor-root-mismatch}

      (not= (:state-after/root derived) (:execution/state-root candidate-envelope))
      {:valid? false :reason :state-after-mismatch}

      (not= (:successor-material derived)
            (authoritative-material-state candidate-material))
      {:valid? false :reason :successor-material-mismatch}

      :else {:valid? true :derived derived})))

(defn build-authoritative-transition-binding
  "Authoritative governed-authority-transition-binding as a PROJECTION over the
   derived content transition. :transaction/state-before-root,
   :transaction/state-after-root, and :transition/root come from the derived
   transition — never from caller selection.

   state-before-root  = the authoritative predecessor S0 state root.
   context-root       = the resolved review-authority-context/root from the
                        governed evaluation.
   authorised-target/root = the authorised outcome O.

   The low-level build-transition-binding (governed-authority-resolution)
   remains only as non-authoritative construction machinery for tests/artifacts."
  [state-before-root derived context-root target-root]
  (let [binding {:artifact/schema "governed-authority-transition-binding.v1"
                 :resolved-review-authority-context/root context-root
                 :transition/root (:transition/root derived)
                 :transaction/state-before-root state-before-root
                 :transaction/state-after-root (:state-after/root derived)
                 :authorization/result-root target-root}]
    (assoc binding :governed-authority-transition-binding/root
           (ref/sha256-ref
            (hc/domain-hash :governed-authority-transition-binding-v1
                            (hc/project-canonical-safe binding))))))