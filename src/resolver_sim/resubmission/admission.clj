(ns resolver-sim.resubmission.admission
  "Storage-independent reservation/finalization contract for resubmission
   admission.  This namespace contains only pure transitions and canonical
   projections; adapters own conditional mutation and durability.

   A reservation is coordination state, never canonical admission.  Fences are
   monotonically increasing per partition for the lifetime of an adapter state.
   A durable adapter must additionally preserve that property across its stated
   recovery model."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def admission-state-schema "resubmission-admission-state.v2")
(def reservation-lease-seconds 120)
(def max-idempotency-key-bytes 512)
(def max-family-id-bytes 512)
(def max-reservation-count 10000)
(def validation-domain "prf.resubmission-validation.v1")
(def reservation-domain "prf.resubmission-reservation.v1")
(def finalization-domain "prf.resubmission-finalization.v1")
(def validation-schema "resubmission-validation.v1")
(def required-check-order
  [:link-artifact :bundle-binding :researcher-authority :derived-kind
   :remediation :parent-receipt :disposition])

(defn partition-key [family-id] [:resubmission-family family-id])

(defn valid-partition-key?
  [key]
  (and (vector? key)
       (= 2 (count key))
       (= :resubmission-family (first key))
       (some? (second key))))

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- utf8-bytes [value]
  (when (string? value) (alength (.getBytes ^String value "UTF-8"))))

(defn- parse-instant [value]
  (when (string? value) (java.time.Instant/parse value)))

(defn- expired? [reservation now]
  (let [expires (parse-instant (:reservation/expires-at reservation))]
    (and expires now (not (.isAfter expires now)))))

(defn normalize-state
  "Deterministically migrate pre-v2 state. Legacy active reservations are
   fail-closed: they become :expired because no historical lease authority can
   be inferred."
  [state]
  (let [state (merge {:admission/schema admission-state-schema
                      :admission/reservations {} :admission/idempotency-index {}
                      :admission/candidate-index {} :admission/finalizations {}}
                     state)]
    (if (= admission-state-schema (:admission/schema state))
      state
      (-> state
          (assoc :admission/schema admission-state-schema)
          (update :admission/reservations
                  (fn [rs] (into {} (map (fn [[id r]]
                                           [id (if (= :active (:reservation/status r))
                                                 (assoc r :reservation/status :expired
                                                        :reservation/terminal-reason :legacy-no-lease)
                                                 r)])) rs)))))))

(declare result)

(defn terminalize-expired
  "Pure lazy expiry performed by the authoritative adapter under its partition
   lock before every operation."
  [state now]
  (update state :admission/reservations
          (fn [rs] (into {} (map (fn [[id r]]
                                   [id (if (and (= :active (:reservation/status r))
                                                (expired? r now))
                                         (assoc r :reservation/status :expired
                                                :reservation/terminal-reason :lease-expired)
                                         r)])) rs))))

(defn expire-transition
  "Materialize expiry only at or after the store-issued deadline."
  [state reservation-id fence now]
  (let [reservation (get-in state [:admission/reservations reservation-id])]
    (cond
      (nil? reservation) (result state :rejected :reason :unknown-reservation)
      (not= fence (:concurrency/fence state)) (result state :rejected :reason :stale-fence)
      (= :expired (:reservation/status reservation))
      (result state :idempotent-replay :reservation-id reservation-id :reservation/status :expired)
      (not= :active (:reservation/status reservation))
      (result state :rejected :reason :reservation-not-active)
      (not (expired? reservation now))
      (result state :rejected :reason :reservation-not-expired)
      :else
      (result (assoc-in state [:admission/reservations reservation-id]
                        (assoc reservation :reservation/status :expired
                               :reservation/terminal-reason :lease-expired))
              :expired :reservation-id reservation-id))))

(defn compact-transition
  "Replace terminal reservation detail with permanent replay tombstones. Tombstones
   retain every field needed for idempotency, stale-fence, and finalization
   replay; v1 never deletes them."
  [state]
  (let [compact (fn [[id r]]
                  (if (= :active (:reservation/status r))
                    [id r]
                    (let [f (get-in state [:admission/finalizations id])]
                      [id (merge
                           (select-keys r [:reservation/id :reservation/partition-key
                                           :reservation/fence :reservation/status
                                           :reservation/terminal-reason
                                           :reservation/expected-state-version
                                           :reservation/candidate-root
                                           :reservation/idempotency-key])
                           (when f {:finalization/request-root (:finalization/request-root f)
                                    :receipt/root (:receipt/root f)
                                    :authorization/evidence-root (:authorization/evidence-root f)
                                    :signing/payload-root (:signing/payload-root f)}))])))]
    (result (update state :admission/reservations
                    (fn [rs] (into {} (map compact rs))))
            :compacted)))

(defn validation-root
  "Identity of a complete, snapshot-bound validation aggregate.  Callers must
   supply every check required by `:validation/check-order` in that order."
  [validation]
  (root validation-domain (dissoc validation :validation/root)))

(defn build-validation
  "Build a canonical validation aggregate.  Check completion order is never
   semantically visible: checks are projected in `required-check-order`."
  [{:keys [partition-key snapshot-root snapshot-version candidate-root
           profile-id profile-version checks]
    :as input}]
  (let [declared-order (:validation/check-order input)
        check-order required-check-order
        check-ids (mapv :check/id checks)
        by-id (into {} (map (juxt :check/id identity) checks))
        missing (vec (remove #(contains? by-id %) check-order))
        unexpected (vec (remove (set check-order) check-ids))
        duplicate? (not= (count check-ids) (count (set check-ids)))
        mismatched (vec (keep :check/id
                              (remove #(and (= snapshot-root (:validated-against/root %))
                                            (= snapshot-version (:validated-against/version %))
                                            (= candidate-root (:validated-against/candidate-root %)))
                                      checks)))]
    (when (or (and declared-order (not= (vec declared-order) check-order))
              (seq missing) (seq unexpected) duplicate? (seq mismatched))
      (throw (ex-info "validation aggregate is incomplete or not snapshot-consistent"
                      {:reason (cond
                                 (and declared-order (not= (vec declared-order) check-order)) :validation-check-order-mismatch
                                 (seq missing) :validation-checks-incomplete
                                 (seq unexpected) :validation-check-unexpected
                                 duplicate? :validation-check-duplicate
                                 :else :validation-snapshot-mismatch)
                       :missing missing :unexpected unexpected :mismatched mismatched})))
    (let [aggregate {:validation/schema validation-schema
                     :validation/partition-key partition-key
                     :validation/snapshot-root snapshot-root
                     :validation/snapshot-version snapshot-version
                     :validation/candidate-root candidate-root
                     :validation/profile-id profile-id
                     :validation/profile-version profile-version
                     :validation/check-order (vec check-order)
                     :validation/checks (mapv by-id check-order)
                     :validation/pass? (every? :valid? (map by-id check-order))}]
      (assoc aggregate :validation/root (validation-root aggregate)))))

(defn empty-partition
  "Reference coordination state for one partition.  `:concurrency/fence` is
   coordination-only; `:family/version` advances only on finalization."
  [family-id]
  {:admission/schema admission-state-schema
   :concurrency/partition-key (partition-key family-id)
   :concurrency/fence 0
   :family/version 0
   :family/head nil
   :admission/reservations {}
   :admission/idempotency-index {}
   :admission/candidate-index {}
   :admission/finalizations {}})

(defn snapshot [state]
  {:concurrency/partition-key (:concurrency/partition-key state)
   :concurrency/snapshot-root
   (root "prf.resubmission-admission-snapshot.v1"
         (select-keys state [:concurrency/partition-key :family/version :family/head
                             :admission/idempotency-index :admission/candidate-index]))
   :concurrency/expected-state-version (:family/version state)
   :concurrency/fence (:concurrency/fence state)
   :family/version (:family/version state)
   :family/head (:family/head state)
   :admission/schema (:admission/schema state)})

(defn- reservation-root [reservation]
  (root reservation-domain (dissoc reservation :reservation/root)))

(defn signing-payload
  "The immutable payload a signer attests.  Signers have no arbitration power:
   this is derived only after `reserve!` grants one caller a fenced attempt.
   Its root is checked again by finalization."
  [reservation]
  (let [payload {:signing/schema "resubmission-admission-signing.v1"
                 :signing/partition-key (:reservation/partition-key reservation)
                 :signing/reservation-id (:reservation/id reservation)
                 :signing/fence (:reservation/fence reservation)
                 :signing/expected-state-version (:reservation/expected-state-version reservation)
                 :signing/candidate-root (:reservation/candidate-root reservation)
                 :signing/validation-root (:reservation/validation-root reservation)
                 :signing/proposed-ordering-root (:reservation/proposed-ordering-root reservation)}]
    (assoc payload :signing/payload-root
           (root "prf.resubmission-admission-signing.v1" payload))))

(defn finalization-request-root [request]
  (root finalization-domain (dissoc request :finalization/request-root)))

(defn- exact-finalization-root [request]
  (finalization-request-root request))

(defn- result [state outcome & kvs]
  (merge {:state state :concurrency/outcome outcome} (apply hash-map kvs)))

(defn reserve-transition
  "Pure conditional reservation transition.  It intentionally has no time
   parameter: only an adapter's explicit `expire!` transition can withdraw
   authority."
  [state {:keys [concurrency/partition-key concurrency/expected-state-version
                 concurrency/idempotency-key reservation/candidate-root
                 reservation/validation-root reservation/proposed-ordering-root
                 reservation/issued-at reservation/expires-at]
          :as request}]
  (cond
    (not= partition-key (:concurrency/partition-key state))
    (result state :rejected :reason :partition-mismatch)

    (not= expected-state-version (:family/version state))
    (result state :contention :reason :state-version-mismatch
            :observed-version (:family/version state))

    (>= (count (:admission/reservations state)) max-reservation-count)
    (result state :rejected :reason :partition-capacity-exhausted)

    (not (and candidate-root validation-root proposed-ordering-root idempotency-key
              issued-at expires-at))
    (result state :rejected :reason :missing-reservation-binding)

    (or (> (or (utf8-bytes idempotency-key) Long/MAX_VALUE) max-idempotency-key-bytes)
        (> (or (utf8-bytes (second partition-key)) Long/MAX_VALUE) max-family-id-bytes))
    (result state :rejected :reason :input-too-large)

    :else
    (let [idem (get-in state [:admission/idempotency-index idempotency-key])
          idem-reservation (get-in state [:admission/reservations (:reservation/id idem)] idem)
          existing-candidate (get-in state [:admission/candidate-index candidate-root])
          active (some (fn [[_ r]] (when (= :active (:reservation/status r)) r))
                       (:admission/reservations state))]
      (cond
        (and idem (not= candidate-root (:reservation/candidate-root idem)))
        (result state :rejected :reason :idempotency-conflict :existing idem)

        (and idem (= :active (:reservation/status idem-reservation)))
        (result state :idempotent-replay :reservation idem-reservation)

        idem
        (result state :rejected :reason :idempotency-attempt-closed
                :existing idem-reservation)

        existing-candidate
        (result state :idempotent-candidate-replay :existing existing-candidate)

        active
        (result state :contention :reason :active-reservation
                :reservation-id (:reservation/id active)
                :concurrency/fence (:concurrency/fence active))

        :else
        (let [fence (inc (:concurrency/fence state))
              reservation {:reservation/id (root reservation-domain
                                               {:partition-key partition-key
                                                :fence fence
                                                :candidate-root candidate-root
                                                :validation-root validation-root
                                                :proposed-ordering-root proposed-ordering-root
                                                :idempotency-key idempotency-key
                                                :issued-at issued-at
                                                :expires-at expires-at})
                           :reservation/partition-key partition-key
                           :reservation/fence fence
                           :reservation/snapshot-root (:concurrency/snapshot-root request)
                           :reservation/expected-state-version expected-state-version
                           :reservation/candidate-root candidate-root
                           :reservation/validation-root validation-root
                           :reservation/proposed-ordering-root proposed-ordering-root
                           :reservation/idempotency-key idempotency-key
                           :reservation/issued-at issued-at
                           :reservation/expires-at expires-at
                           :reservation/status :active}
              reservation (assoc reservation :reservation/root (reservation-root reservation))
              next-state (-> state
                             (assoc :concurrency/fence fence)
                             (assoc-in [:admission/reservations (:reservation/id reservation)] reservation)
                             ;; These bindings intentionally outlive reservation cleanup.
                             (assoc-in [:admission/idempotency-index idempotency-key] reservation))]
          (result next-state :reserved :reservation reservation))))))

(defn finalize-transition
  "Pure, idempotent finalization.  Publishing receipt/order/head/version occurs
   in one state transition.  A matching retry after a lost response returns the
   already committed outcome; a stale fence cannot publish anything."
  [state {:keys [concurrency/partition-key reservation/id concurrency/fence concurrency/expected-state-version
                 reservation/candidate-root reservation/validation-root
                 reservation/proposed-ordering-root signing/payload-root receipt/root
                 authorization/evidence-root]
          :as request}]
  (let [derived-request-root (exact-finalization-root request)
        supplied-request-root (:finalization/request-root request)
        request-root derived-request-root
        reservation (get-in state [:admission/reservations id])
        finalized (get-in state [:admission/finalizations id])]
    (cond
      (not= partition-key (:concurrency/partition-key request))
      (result state :rejected :reason :partition-mismatch)

      (and supplied-request-root (not= supplied-request-root derived-request-root))
      (result state :rejected :reason :finalization-request-root-mismatch)

      finalized
      (if (= request-root (:finalization/request-root finalized))
        (result state :idempotent-replay :finalization finalized)
        (result state :rejected :reason :finalization-conflict :finalization finalized))

      (nil? reservation)
      (result state :rejected :reason :unknown-reservation)

      (not= fence (:concurrency/fence state))
      (result state :rejected :reason :stale-fence
              :current-fence (:concurrency/fence state))

      (not= :active (:reservation/status reservation))
      (result state :rejected :reason :reservation-not-active)

      (not= fence (:reservation/fence reservation))
      (result state :rejected :reason :reservation-fence-mismatch)

      (not= expected-state-version (:family/version state))
      (result state :rejected :reason :state-version-mismatch
              :observed-version (:family/version state))

      (not= [candidate-root validation-root proposed-ordering-root]
            [(:reservation/candidate-root reservation)
             (:reservation/validation-root reservation)
             (:reservation/proposed-ordering-root reservation)])
      (result state :rejected :reason :reservation-binding-mismatch)

      (not= payload-root (:signing/payload-root (signing-payload reservation)))
      (result state :rejected :reason :signing-payload-mismatch)

      (or (nil? root) (nil? evidence-root))
      (result state :rejected :reason :missing-authorization-evidence)

      :else
      (let [finalization {:finalization/request-root request-root
                          :reservation/id id
                          :concurrency/fence fence
                          :family/version (inc (:family/version state))
                          :family/head root
                          :receipt/root root
                          :authorization/evidence-root evidence-root
                          :signing/payload-root payload-root
                          :reservation/candidate-root candidate-root
                          :reservation/validation-root validation-root
                          :reservation/proposed-ordering-root proposed-ordering-root}
            next-state (-> state
                           (assoc :family/version (:family/version finalization)
                                  :family/head root)
                           (assoc-in [:admission/reservations id]
                                     (assoc reservation :reservation/status :finalized))
                           (assoc-in [:admission/finalizations id] finalization)
                           (assoc-in [:admission/candidate-index candidate-root] finalization))]
        (result next-state :finalized :finalization finalization)))))

(defn withdraw-transition
  "Pure abort/expiry transition.  It never creates canonical protocol state.
   Expiry is called by the authoritative adapter, never inferred from a JVM
   clock inside this transition."
  [state reservation-id fence status]
  (let [reservation (get-in state [:admission/reservations reservation-id])]
    (cond
      (not (#{:aborted :expired} status))
      (result state :rejected :reason :invalid-withdrawal-status)

      (nil? reservation)
      (result state :rejected :reason :unknown-reservation)

      (not= fence (:concurrency/fence state))
      (result state :rejected :reason :stale-fence)

      (= status (:reservation/status reservation))
      (result state :idempotent-replay :reservation-id reservation-id
              :reservation/status status)

      (not= :active (:reservation/status reservation))
      (result state :rejected :reason :reservation-not-active)

      :else
      (result (assoc-in state [:admission/reservations reservation-id]
                        (assoc reservation :reservation/status status))
              status :reservation-id reservation-id))))
