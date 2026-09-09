(ns resolver-sim.cancellation.sew-projection
  "P0-A: frozen semantic projection between the SEW protocol (Surface A) and the
   framework cancellation layer (Surface B). Read-only boundary contract."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.cancellation.semantic :as semantic]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]))

(def schema-version "sew-cancellation-projection.v1")
(def projection-domain "SEW_CANCELLATION_PROJECTION_V1")

(def supported-paths
  #{:sender-cancel :recipient-cancel :auto-cancel-disputed-escrow
    :auto-cancel-disputed-on-auto-time})

(defn supported-path? [path] (contains? supported-paths path))

(defn unsupported-scope [fact reason]
  {:projection/fact fact :projection/reason reason :projection/carryed? false})

(def declared-outside-scope
  {:cancel-strategy (unsupported-scope :projection/cancel-strategy
                                       :projection/reason-is-request-parameter)
   :auto-cancel-time (unsupported-scope :projection/auto-cancel-time
                                        :projection/reason-is-time-dependent-declared-on-operation)
   :dispute-timestamps (unsupported-scope :projection/dispute-timestamps
                                          :projection/reason-is-world-level-declared-on-operation)
   :caller/identity (unsupported-scope :projection/caller-identity
                                       :projection/reason-is-authorization-bound)})

(defn- transfer [world workflow-id]
  (t/get-transfer world workflow-id))

(defn- snapshot-map [world workflow-id]
  (t/get-snapshot world workflow-id))

(defn- dispute-timestamp [world workflow-id]
  (get-in world [:dispute-timestamps workflow-id] 0))

(defn- auto-cancel-time [world workflow-id]
  (get-in world [:escrow-transfers workflow-id :auto-cancel-time] 0))

(defn- max-dispute-duration [world workflow-id]
  (get (snapshot-map world workflow-id) :max-dispute-duration 0))

(defn- resolver-response-window [world workflow-id]
  (get (snapshot-map world workflow-id) :resolver-response-window 0))

(defn- dispute-resolver [world workflow-id]
  (:dispute-resolver (transfer world workflow-id)))

(defn- escrow-state [world workflow-id]
  (t/escrow-state world workflow-id))

(defn- sender-status [world workflow-id]
  (:sender-status (transfer world workflow-id)))

(defn- recipient-status [world workflow-id]
  (:recipient-status (transfer world workflow-id)))

(defn- dispute-active? [world workflow-id]
  (t/dispute-active? world workflow-id))

(defn- pending-settlement-exists? [world workflow-id]
  (:exists (t/get-pending world workflow-id)))

(defn- dispute-timeout-exceeded? [world workflow-id]
  (sm/dispute-timeout-exceeded? world workflow-id))

(defn- auto-cancel-due-on-disputed? [world workflow-id]
  (sm/auto-cancel-due-on-disputed? world workflow-id))

(defn- both-agreed-to-cancel? [world workflow-id]
  (sm/both-agreed-to-cancel? world workflow-id))

(defn project-snapshot
  "Project the SEW world state into the framework cancellation input snapshot
   (sew-escrow-state-snapshot.v1). Single read-only input Surface B consumes
   from Surface A for the mutual-consent paths.

   The snapshot carries ONLY facts the protocol cancellation decision depends
   on that are stable across block-time. Time-dependent facts (auto-cancel-time,
   dispute-timestamps) are NOT folded into the snapshot identity; they are
   declared outside scope and bound on the operation."
  [world workflow-id]
  (let [tr (transfer world workflow-id)]
    (-> {:snapshot/schema snapshot/schema-version
         :workflow/id workflow-id
         :escrow/sender (:from tr)
         :escrow/recipient (:to tr)
         :escrow/state (:escrow-state tr)
         :sender/cancellation-status (:sender-status tr)
         :recipient/cancellation-status (:recipient-status tr)}
        (assoc :snapshot/root (snapshot/snapshot-root
                               (dissoc {:snapshot/schema snapshot/schema-version
                                        :workflow/id workflow-id
                                        :escrow/sender (:from tr)
                                        :escrow/recipient (:to tr)
                                        :escrow/state (:escrow-state tr)
                                        :sender/cancellation-status (:sender-status tr)
                                        :recipient/cancellation-status (:recipient-status tr)}
                                       :snapshot/root))))))

(defn project-inputs
  "Project the complete framework cancellation inputs for a path. Returns
   :projection/snapshot (rooted), :projection/declared (outside-scope facts the
   caller must bind on the operation), and :projection/admissibility (derived
   SEW predicates at this block-time).

   :projection/admissibility is NOT part of the snapshot identity - it is a pure
   function of world + block-time, recomputed by admission. Surfaced here so the
   projection contract names every predicate the path consults."
  [world workflow-id path]
  (let [snap (project-snapshot world workflow-id)]
    {:projection/schema schema-version
     :projection/path path
     :projection/snapshot snap
     :projection/declared (select-keys declared-outside-scope
                                       [:cancel-strategy :auto-cancel-time
                                        :dispute-timestamps :caller/identity])
     :projection/admissibility
     (case path
       :sender-cancel
       {:admissibility/escrow-state (= :pending (escrow-state world workflow-id))
        :admissibility/sender-status (sender-status world workflow-id)
        :admissibility/recipient-status (recipient-status world workflow-id)
        :admissibility/both-agreed (both-agreed-to-cancel? world workflow-id)}

       :recipient-cancel
       {:admissibility/escrow-state (= :pending (escrow-state world workflow-id))
        :admissibility/sender-status (sender-status world workflow-id)
        :admissibility/recipient-status (recipient-status world workflow-id)
        :admissibility/both-agreed (both-agreed-to-cancel? world workflow-id)}

       (:auto-cancel-disputed-escrow :auto-cancel-disputed-on-auto-time)
       {:admissibility/dispute-active (dispute-active? world workflow-id)
        :admissibility/pending-settlement (pending-settlement-exists? world workflow-id)
        :admissibility/dispute-timeout-exceeded (dispute-timeout-exceeded? world workflow-id)
        :admissibility/auto-cancel-due-on-disputed (auto-cancel-due-on-disputed? world workflow-id)
        :admissibility/dispute-resolver (dispute-resolver world workflow-id)
        :admissibility/max-dispute-duration (max-dispute-duration world workflow-id)
        :admissibility/resolver-response-window (resolver-response-window world workflow-id)
        :admissibility/dispute-timestamp (dispute-timestamp world workflow-id)
        :admissibility/auto-cancel-time (auto-cancel-time world workflow-id)})}))

;; ---------------------------------------------------------------------------
;; SEW mutations -> canonical cancellation effects
;; ---------------------------------------------------------------------------

(def effect-kinds
  "Canonical cancellation effect vocabulary projected from SEW mutations.
   Each maps to a semantic/derived-effects :effects/kind value."
  {:refunded :refund-sender
   :record-agreement :record-party-agreement})

(defn- effect [kind by]
  {:effects/schema semantic/derived-effects-schema
   :effects/kind (get effect-kinds kind)
   :effects/by by})

(defn- cancellation-final? [world workflow-id path cancel-strategy]
  "Determine if the cancellation is final based on SEW protocol logic.
   Mirrors sender-cancel/recipient-cancel in lifecycle.clj:
   - The protocol sets the caller's status, then checks if the OTHER party
     has already agreed (which completes mutual consent).
   - So for sender-cancel we check recipient-status, and vice versa."
  (case path
    (:sender-cancel :recipient-cancel)
    (cond
      (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy))) false
      (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy)) true
      :else
      (case path
        :sender-cancel (= :agree-to-cancel (recipient-status world workflow-id))
        :recipient-cancel (= :agree-to-cancel (sender-status world workflow-id))))
    (:auto-cancel-disputed-escrow :auto-cancel-disputed-on-auto-time) true
    false))

(defn project-effects
  "Project the SEW mutations performed by a path into canonical cancellation
   effects (sew-party-cancellation-derived-effects.v1).

   For the sender/recipient-cancel paths the mutation depends on cancel-strategy:
     - If cancel-strategy provided with :can-cancel? false -> record agreement (not final)
     - If cancel-strategy provided with :unilateral-cancel? true -> refund (final)
     - If no cancel-strategy (mutual consent) -> check both-agreed-to-cancel?

   For the auto-cancel paths the mutation is unconditional: finalize to
   :refunded plus a resolver stake slash. The slash is a register-level
   mutation on :resolver-stakes / :resolver-slash-total, outside the
   cancellation effect vocabulary; it is declared outside scope."
  [world workflow-id path & [cancel-strategy]]
  (let [final? (cancellation-final? world workflow-id path cancel-strategy)
        kind (if final? :refunded :record-agreement)
        by (case path
             :sender-cancel :sender
             :recipient-cancel :recipient
             :auto-cancel-disputed-escrow :keeper
             :auto-cancel-disputed-on-auto-time :keeper)]
    (-> (effect kind by)
        (assoc :effects/final? final?
               :effects/slash-declared-outside-scope
               (unsupported-scope :projection/resolver-slash
                                  :projection/reason-is-register-level-not-cancellation-effect))
        (assoc :effects/root (semantic/derived-effects-root
                              (dissoc {:effects/schema semantic/derived-effects-schema
                                       :effects/kind (get effect-kinds kind)
                                       :effects/by by}
                                      :effects/root))))))

(defn project-state-after
  "Project the SEW world state after the path fires into the framework
   execution-effects :execution-effects/derived-effects-root reference."
  [world workflow-id path]
  {:state-after/schema "sew-party-cancellation-state-after.v1"
   :state-after/workflow-id workflow-id
   :state-after/path path
   :state-after/escrow-state (escrow-state world workflow-id)
   :state-after/sender-status (sender-status world workflow-id)
   :state-after/recipient-status (recipient-status world workflow-id)
   :state-after/terminal? (t/terminal-state? world workflow-id)})

(defn project
  "Full projection for a path: inputs + effects + state-after. Complete
   Surface-A -> Surface-B mapping for one cancellation path.

   For sender-cancel/recipient-cancel paths, an optional cancel-strategy
   map can be provided to mirror the SEW protocol's unilateral decision logic."
  [world workflow-id path & [cancel-strategy]]
  (when-not (supported-path? path)
    (throw (ex-info "unsupported cancellation projection path"
                    {:path path :supported (sort supported-paths)})))
  {:projection/schema schema-version
   :projection/path path
   :projection/inputs (project-inputs world workflow-id path)
   :projection/effects (project-effects world workflow-id path cancel-strategy)
   :projection/state-after (project-state-after world workflow-id path)})

;; ---------------------------------------------------------------------------
;; Coverage audit
;; ---------------------------------------------------------------------------

(defn coverage-audit
  "Audit that every SEW fact a path consults is either carried by the snapshot,
   rooted through another authoritative input, or explicitly declared outside
   scope. Returns {:audit/ok? bool :audit/gaps [...]}. A gap is a consulted
   fact with no declared home - the failure mode this boundary exists to
   prevent."
  [world workflow-id path]
  (let [inputs (project-inputs world workflow-id path)
        carried (set (keys (:projection/snapshot inputs)))
        declared (set (keys (:projection/declared inputs)))
        consulted (case path
                    (:sender-cancel :recipient-cancel)
                    #{:escrow-state :sender-status :recipient-status :both-agreed}
                    (:auto-cancel-disputed-escrow :auto-cancel-disputed-on-auto-time)
                    #{:dispute-active :pending-settlement :dispute-timeout-exceeded
                      :auto-cancel-due-on-disputed :dispute-resolver
                      :max-dispute-duration :resolver-response-window
                      :dispute-timestamp :auto-cancel-time})
        gaps (->> consulted
                  (remove (fn [f]
                            (or (contains? carried f)
                                (contains? declared f)
                                (contains? #{:both-agreed :dispute-active
                                             :pending-settlement :dispute-timeout-exceeded
                                             :auto-cancel-due-on-disputed}
                                           f))))
                  vec)]
    {:audit/schema schema-version
     :audit/path path
     :audit/consulted consulted
     :audit/carried carried
     :audit/declared declared
     :audit/ok? (empty? gaps)
     :audit/gaps gaps}))

(defn projection-root [p]
  (hash-ref/sha256-ref (hc/domain-hash projection-domain (dissoc p :projection/root))))

(defn projection-root-valid? [p]
  (and (= schema-version (:projection/schema p))
       (supported-path? (:projection/path p))
       (= (:projection/root p) (projection-root (dissoc p :projection/root)))))
