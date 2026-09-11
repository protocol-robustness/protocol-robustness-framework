(ns resolver-sim.evidence.attestation-lifecycle
  "Attestation lifecycle management: formalise creation, superseding,
   cascading, and revocation workflows.

   Each attestation has a lifecycle state stored in the lifecycle registry:
     :active     — current and valid (default for newly created attestations)
     :superseded — replaced by a newer attestation
     :revoked    — explicitly revoked

   Lifecycle records are stored separately from attestations — the original
   attestation record is never modified (immutability by design).

   Integration with the revocation system: when checking an attestation's
   state, if a revocation record exists in the revocation registry, the
   state is reported as :revoked even if the lifecycle registry shows
   another state.

   Usage:
     (require '[resolver-sim.evidence.attestation-lifecycle :as alc])

     ;; Mark an attestation as superseded
     (alc/supersede-attestation! \"attestation-id-1\" \"attestation-id-2\"
                                  \"Updated claim result\")

     ;; Get current state
     (alc/attestation-state \"attestation-id-1\")
     ;; => {:state :superseded :superseded-by \"attestation-id-2\" ...}

     ;; Trace the supersession chain
     (alc/attestation-supersession-chain \"attestation-id-1\")
     ;; => [{:attestation-id \"id-1\" :state :superseded}
           {:attestation-id \"id-2\" :state :active}]"
  (:import [java.time Instant]))

;; ── Lifecycle Registry ───────────────────────────────────────────────────────

(def ^:dynamic *lifecycle-registry*
  "In-memory registry tracking attestation lifecycle state.
   Maps attestation-id to lifecycle record:
     {:state :active|:superseded|:revoked
      :superseded-by <id>   ;; when :superseded
      :reason <string>
      :updated-at <ISO-8601>
      :supersedes [<ids>]   ;; attestations this one supersedes
      :revocation <rev-map> ;; when :revoked (from revocation registry)}"
  (atom {}))

(defmacro with-fresh-registry
  "Execute body with a fresh lifecycle registry.
   The outer registry is restored when body exits.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [*lifecycle-registry* fresh-atom#]
       ~@body)))

(defn clear-lifecycle!
  "Reset the lifecycle registry to empty."
  []
  (reset! *lifecycle-registry* {})
  nil)

;; ── Lifecycle Operations ─────────────────────────────────────────────────────

(defn- now-iso
  []
  (str (Instant/now)))

(defn activate-attestation!
  "Record an attestation as active in the lifecycle registry.

   This is called automatically when an attestation is created via the
   emitter pipeline. It establishes the attestation as the current/valid
   version and optionally records which attestations it supersedes.

   Arguments:
     attestation-id — string hash of the attestation
     opts           — optional map:
                       :supersedes — vector of attestation-ids this one replaces
                       :reason     — string description

   Returns the lifecycle record."
  [attestation-id & [{:keys [supersedes reason]}]]
  (let [record {:state :active
                :updated-at (now-iso)
                :reason (or reason "Attestation created")}]
    (swap! *lifecycle-registry* assoc attestation-id
           (cond-> record
             supersedes (assoc :supersedes (vec supersedes))))
    (get @*lifecycle-registry* attestation-id)))

(defn supersede-attestation!
  "Mark an attestation as superseded by another.

   The old attestation's state changes to :superseded and records
   which attestation superseded it. The new attestation's state is
   set to :active with a reference to what it supersedes.

   Supports multiple supersessions: calling this multiple times with
   the same new-id appends to the :supersedes list.

   Arguments:
     old-id     — attestation-id of the superseded attestation
     new-id     — attestation-id of the superseding attestation
     reason     — string describing why the supersession occurred

   Returns {:old-state <lifecycle> :new-state <lifecycle>}."
  [old-id new-id reason]
  (let [now (now-iso)
        old-record {:state :superseded
                    :superseded-by new-id
                    :reason reason
                    :updated-at now}
        new-record-fn (fn [existing]
                        (let [existing-supersedes (:supersedes existing [])]
                          {:state :active
                           :supersedes (if (some #{old-id} existing-supersedes)
                                         existing-supersedes
                                         (conj existing-supersedes old-id))
                           :reason reason
                           :updated-at now}))]
    (swap! *lifecycle-registry* update old-id merge old-record)
    (swap! *lifecycle-registry* (fn [reg]
                                  (let [existing (get reg new-id {})
                                        existing-supersedes (:supersedes existing [])]
                                    (assoc reg new-id
                                           (merge existing
                                                  {:state :active
                                                   :supersedes (if (some #{old-id} existing-supersedes)
                                                                 existing-supersedes
                                                                 (conj existing-supersedes old-id))
                                                   :reason reason
                                                   :updated-at now})))))
    {:old-state (get @*lifecycle-registry* old-id)
     :new-state (get @*lifecycle-registry* new-id)}))

(defn revoke-attestation!
  "Mark an attestation as revoked in the lifecycle registry.

   This is a soft revocation that records the state in the lifecycle
   registry. For hard revocation (with revocation records), use the
   revocation system directly — this function will also detect those.

   Arguments:
     attestation-id — string hash of the attestation
     reason         — string describing the revocation reason

   Returns the lifecycle record."
  [attestation-id reason]
  (let [now (now-iso)
        record {:state :revoked
                :reason reason
                :updated-at now}]
    (swap! *lifecycle-registry* update attestation-id merge record)
    (get @*lifecycle-registry* attestation-id)))

;; ── State Queries ────────────────────────────────────────────────────────────

(defn lifecycle-record
  "Get the lifecycle record for an attestation-id.

   Returns the lifecycle record map or nil if no lifecycle data exists.
   Note: all attestations are considered :active by default even without
   a lifecycle record (the absence of lifecycle data implies :active)."
  [attestation-id]
  (get @*lifecycle-registry* attestation-id))

(defn attestation-state
  "Get the current lifecycle state of an attestation.

   Checks (in order):
   1. If the revocation registry has a revocation for this attestation → :revoked
   2. If the lifecycle registry has a record → its :state
   3. Otherwise → :active (default)

   Arguments:
     attestation-id — string hash of the attestation
     opts           — optional map:
                       :revocation-resolver — fn (id) -> boolean, e.g. rev/attestation-revoked?
                       :default-state — fallback state if no lifecycle data (default :active)

   Returns {:state <keyword> :lifecycle <record-or-nil> :revoked? <boolean>}."
  [attestation-id & [{:keys [revocation-resolver default-state]
                      :or {default-state :active}}]]
  (let [lifecycle (get @*lifecycle-registry* attestation-id)
        revoked-by-registry? (when revocation-resolver
                               (boolean (revocation-resolver attestation-id)))
        lifecycle-state (if lifecycle (:state lifecycle) default-state)
        effective-state (if revoked-by-registry? :revoked lifecycle-state)]
    {:state effective-state
     :lifecycle lifecycle
     :revoked? (boolean (or revoked-by-registry?
                            (= :revoked lifecycle-state)))}))

(defn attestation-active?
  "Check if an attestation is currently active (not superseded or revoked).

   Returns true if the attestation's effective state is :active."
  [attestation-id & opts]
  (= :active (:state (apply attestation-state attestation-id opts))))

;; ── Supersession Chain ───────────────────────────────────────────────────────

(defn attestation-supersession-chain
  "Trace the supersession chain from an attestation forward and backward.

   Traverses both directions:
   - Backward: follows :superseded-by links to find predecessor attestations
   - Forward: follows :supersedes links to find successor attestations

   Arguments:
     attestation-id — starting attestation-id

   Returns {:id <id>
            :state <state>
            :predecessors [<chain-entry> ...]
            :successors [<chain-entry> ...]}"
  [attestation-id]
  (letfn [(trace-predecessors [id]
            (when-let [rec (get @*lifecycle-registry* id)]
              (when-let [prev-ids (:supersedes rec)]
                (mapcat (fn [prev-id]
                          (let [prev-rec (get @*lifecycle-registry* prev-id)]
                            (cons {:attestation-id prev-id
                                   :state (:state prev-rec :active)
                                   :reason (:reason rec)}
                                  (trace-predecessors prev-id))))
                        prev-ids))))
          (trace-successors [id]
            (when-let [rec (get @*lifecycle-registry* id)]
              (when-let [next-id (:superseded-by rec)]
                (let [next-rec (get @*lifecycle-registry* next-id)]
                  (cons {:attestation-id next-id
                         :state (:state next-rec :active)
                         :reason (:reason rec)}
                        (trace-successors next-id))))))]
    (let [lifecycle (get @*lifecycle-registry* attestation-id)]
      {:id attestation-id
       :state (:state lifecycle :active)
       :predecessors (vec (trace-predecessors attestation-id))
       :successors (vec (trace-successors attestation-id))})))

(defn find-superseded-by
  "Find all attestation-ids that were superseded by the given attestation-id.
   Returns a vector of attestation-ids."
  [attestation-id]
  (let [rec (get @*lifecycle-registry* attestation-id)]
    (vec (:supersedes rec []))))

;; ── Cascading ────────────────────────────────────────────────────────────────

(defn cascade-supersession!
  "When an attestation is superseded, also supersede all attestations that
   share the same subject or claim context.

   This function:
   1. Looks up all attestations in the attestation registry for the same
      subject hash as the old attestation
   2. Marks each of them as superseded by the new attestation
   3. Returns a summary of what was cascaded

   Arguments:
     old-id           — attestation-id of the superseded attestation
     new-id           — attestation-id of the superseding attestation
     attestation-resolver — fn (id) -> attestation record; used to find
                            the subject-hash for grouping
     reason           — string reason for the cascade

   Returns {:cascaded-count <int> :cascaded [<id> ...] :errors [...]}."
  [old-id new-id attestation-resolver reason & [{:keys [all-attestations]}]]
  (let [old-attestation (attestation-resolver old-id)
        errors (volatile! [])]
    (if (nil? old-attestation)
      (do
        (vswap! errors conj (str "Attestation not found: " old-id))
        {:cascaded-count 0 :cascaded [] :errors @errors})
      (let [subject-hash (:attestation/subject-hash old-attestation)
            subject-kind (:attestation/subject-kind old-attestation)
            all-attestations (or all-attestations [])
            same-subject (filter #(and (= (:attestation/subject-hash %) subject-hash)
                                       (= (:attestation/subject-kind %) subject-kind)
                                       (not= (:attestation/id %) old-id)
                                       (not= (:attestation/id %) new-id))
                                 all-attestations)
            cascaded (volatile! [])]
        (doseq [a same-subject]
          (let [cid (:attestation/id a)]
            (supersede-attestation! cid new-id reason)
            (vswap! cascaded conj cid)))
        {:cascaded-count (count @cascaded)
         :cascaded @cascaded
         :errors @errors}))))
