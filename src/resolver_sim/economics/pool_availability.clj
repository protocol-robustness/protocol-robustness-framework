(ns resolver-sim.economics.pool-availability
  "Pool availability snapshot and reservation artifacts.

   Pool-availability.v1 captures a point-in-time view of a pool's
   gross, reserved, protected, and available amounts, with committed
   liability and reservation roots.  Successor snapshots (produced by
   pool-after-reservation) bind to the exact predecessor via
   :pool/predecessor-hash.

   Pool-reservation.v1 is an immutable active reservation against a
   specific pool snapshot.

   RESERVATION CONCURRENCY — IMPORTANT BOUNDARY
   A verified reservation artifact proves that the proposed amount was
   within the snapshot's capacity at construction time.  It does NOT
   prove that the reservation has been authoritatively accepted in a
   concurrent execution environment.  Two independently constructed
   reservations may each be individually valid while their combined
   amounts overcommit the same snapshot.  verify-candidate-reservation-set
   detects such overcommitment but does not prevent concurrent
   construction.  Authoritative acceptance (serialization, CAS, batch
   admission) is the responsibility of an execution or registry layer
   above this artifact layer."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const pool-availability-type :pool-availability.v2)
(def ^:const pool-reservation-type :pool-reservation.v1)

(def ^:private pool-source-fields
  [:pool/id :pool/kind :pool/owner-id
   :pool/state-root :pool/policy-root
   :pool/snapshot-time
   :pool/gross-amount :pool/reserved-amount :pool/protected-amount
   :pool/liability-roots :pool/reservation-roots
   :pool/predecessor-hash])

(def ^:private pool-availability-projection-fields
  [:artifact/type
   :pool/id :pool/kind :pool/owner-id
   :pool/state-root :pool/policy-root
   :pool/snapshot-time
   :pool/gross-amount :pool/reserved-amount :pool/protected-amount
   :pool/available-amount
   :pool/liability-roots :pool/reservation-roots
   :pool/predecessor-hash])

(def ^:private reservation-projection-fields
  [:artifact/type
   :reservation/id
   :reservation/pool-root
   :reservation/amount
   :reservation/purpose-root])

(def ^:private component-kind-sign
  {:base (fn [n] (>= n 0))
   :bonus (fn [n] (>= n 0))
   :deduction (fn [n] (<= n 0))})

;; ── Private normalization helpers ────────────────────────────────────────────

(defn- canonicalize-roots
  [label roots]
  (let [sorted (vec (sort roots))]
    (when (not= (count sorted) (count (set roots)))
      (throw (ex-info (str "Duplicate " label) {label roots})))
    sorted))

(def ^:private optional-source-fields
  "Source fields that may be absent or nil."
  #{:pool/predecessor-hash})

(defn- canonicalize-pool-input
  [m]
  (let [source-set (set pool-source-fields)
        key-set (set (keys m))
        extra (set/difference key-set source-set)]
    (when (seq extra)
      (throw (ex-info "Unknown pool-availability source fields" {:extra extra})))
    (let [required (set/difference source-set optional-source-fields)
          missing (set/difference required key-set)]
      (when (seq missing)
        (throw (ex-info "Missing pool-availability source fields" {:missing missing}))))
    (-> m
        (update :pool/liability-roots
                #(canonicalize-roots :pool/liability-roots %))
        (update :pool/reservation-roots
                #(canonicalize-roots :pool/reservation-roots %)))))

(defn- derive-pool-amounts
  [m]
  (let [gross (:pool/gross-amount m)
        reserved (:pool/reserved-amount m)
        protected (:pool/protected-amount m)]
    (when (or (neg? gross) (neg? reserved) (neg? protected))
      (throw (ex-info "Negative pool amount"
                      {:gross gross :reserved reserved :protected protected})))
    (let [encumbered (+' reserved protected)]
      (when (> encumbered gross)
        (throw (ex-info "reserved + protected exceeds gross"
                        {:gross gross :reserved reserved
                         :protected protected :encumbered encumbered})))
      (assoc m :pool/available-amount (-' gross encumbered)))))

;; ── Pool-availability hash ───────────────────────────────────────────────────

(defn pool-availability-hash-projection
  [pa]
  (select-keys pa pool-availability-projection-fields))

(defn pool-availability-hash
  [pa]
  (hc/hash-with-intent {:hash/intent :pool-availability-v2}
                       (pool-availability-hash-projection pa)))

(defn- attach-pool-availability-hash
  [m]
  (assoc m :artifact/hash (pool-availability-hash m)))

;; ── Pool-availability builder ────────────────────────────────────────────────

(defn build-pool-availability
  "Build a content-addressed pool-availability snapshot.
   Input must contain all pool-source-fields.  Unknown fields are rejected.
   Derives :pool/available-amount = gross - reserved - protected."
  [input]
  (let [canonical (-> input
                      canonicalize-pool-input
                      derive-pool-amounts
                      (assoc :artifact/type pool-availability-type))]
    (attach-pool-availability-hash canonical)))

;; ── Pool-availability validation ─────────────────────────────────────────────

(defn- check-required-keys
  [m required label]
  (let [missing (set/difference (set required) (set (keys m)))]
    (when (seq missing)
      (throw (ex-info (str "Missing required keys in " label)
                      {:missing missing :label label})))))

(defn validate-pool-availability
  "Structural validation of a pool-availability artifact.
   Throws on invalid structure."
  [pa]
  (let [required-projection (set/difference (set pool-availability-projection-fields)
                                            optional-source-fields)]
    (check-required-keys pa required-projection "pool-availability"))
  (let [extra (set/difference (set (keys pa))
                              (set pool-availability-projection-fields)
                              #{:artifact/hash})]
    (when (seq extra)
      (throw (ex-info "Unknown pool-availability fields" {:extra extra}))))
  (when-not (= pool-availability-type (:artifact/type pa))
    (throw (ex-info "Wrong artifact type"
                    {:expected pool-availability-type
                     :actual (:artifact/type pa)})))
  (doseq [k [:pool/gross-amount :pool/reserved-amount
             :pool/protected-amount :pool/available-amount]]
    (when-not (nat-int? (get pa k))
      (throw (ex-info (str (name k) " must be a non-negative integer")
                      {k (get pa k)}))))
  (let [encumbered (+' (:pool/reserved-amount pa)
                       (:pool/protected-amount pa))]
    (when (> encumbered (:pool/gross-amount pa))
      (throw (ex-info "reserved + protected exceeds gross in built artifact"
                      {}))))
  (doseq [k [:pool/liability-roots :pool/reservation-roots]]
    (let [v (get pa k)]
      (when-not (vector? v)
        (throw (ex-info (str (name k) " must be a vector")
                        {k v}))))))

;; ── Pool-availability verifier ───────────────────────────────────────────────

(defn verify-pool-availability
  "Independent verification of a pool-availability artifact.
   Returns {:valid? true} or {:valid? false :errors [...]}.
   Never throws."
  [pa]
  (try
    (validate-pool-availability pa)
    (let [errors (atom [])]
      ;; Recompute hash
      (let [expected (pool-availability-hash pa)]
        (when (not= expected (:artifact/hash pa))
          (swap! errors conj {:type :hash-mismatch
                              :expected expected
                              :actual (:artifact/hash pa)})))
      ;; Re-derive available
      (let [available (-' (:pool/gross-amount pa)
                          (:pool/reserved-amount pa)
                          (:pool/protected-amount pa))]
        (when (not= available (:pool/available-amount pa))
          (swap! errors conj {:type :available-mismatch
                              :expected available
                              :actual (:pool/available-amount pa)})))
      (if (empty? @errors) {:valid? true} {:valid? false :errors @errors}))
    (catch Exception e
      {:valid? false
       :errors [{:type :invalid-structure
                 :message (ex-message e)
                 :data (ex-data e)}]})))

;; ── Reservation hash ─────────────────────────────────────────────────────────

(defn reservation-hash-projection
  [r]
  (select-keys r reservation-projection-fields))

(defn reservation-hash
  [r]
  (hc/hash-with-intent {:hash/intent :pool-reservation}
                       (reservation-hash-projection r)))

;; ── Reservation builder ──────────────────────────────────────────────────────

(defn build-reservation
  "Build an active pool reservation against a verified pool snapshot.
   pool — built pool-availability artifact (with :artifact/hash)
   opts — {:keys [reservation/id reservation/amount reservation/purpose-root]}"
  [pool {:keys [reservation/id reservation/amount reservation/purpose-root]}]
  (let [pool-ok (:valid? (verify-pool-availability pool))]
    (when-not pool-ok
      (throw (ex-info "Pool fails verification when building reservation"
                      {:pool/hash (:artifact/hash pool)}))))
  (when (or (nil? amount) (not (nat-int? amount)) (zero? amount))
    (throw (ex-info "Reservation amount must be a positive integer"
                    {:amount amount})))
  (when (< (:pool/available-amount pool) amount)
    (throw (ex-info "Insufficient pool availability"
                    {:available (:pool/available-amount pool)
                     :requested amount})))
  (let [r {:artifact/type pool-reservation-type
           :reservation/id id
           :reservation/pool-root (:artifact/hash pool)
           :reservation/amount amount
           :reservation/purpose-root purpose-root}
        hash (reservation-hash r)]
    (assoc r :artifact/hash hash)))

;; ── Reservation validation ───────────────────────────────────────────────────

(defn validate-reservation
  "Structural validation of a reservation artifact.  Throws on invalid."
  [r]
  (check-required-keys r reservation-projection-fields "reservation")
  (let [extra (set/difference (set (keys r))
                              (set reservation-projection-fields)
                              #{:artifact/hash})]
    (when (seq extra)
      (throw (ex-info "Unknown reservation fields" {:extra extra}))))
  (when-not (= pool-reservation-type (:artifact/type r))
    (throw (ex-info "Wrong artifact type"
                    {:expected pool-reservation-type
                     :actual (:artifact/type r)})))
  (let [amount (:reservation/amount r)]
    (when-not (and (nat-int? amount) (pos? amount))
      (throw (ex-info "Reservation amount must be a positive integer"
                      {:amount amount}))))
  (doseq [k [:reservation/id :reservation/pool-root :reservation/purpose-root]]
    (let [v (get r k)]
      (when-not (and (string? v) (seq v))
        (throw (ex-info (str (name k) " must be a non-empty string")
                        {k v}))))))

;; ── Reservation verifier ─────────────────────────────────────────────────────

(defn verify-reservation
  "Independent verification of a reservation artifact.
   Returns {:valid? true} or {:valid? false :errors [...]}.
   Never throws."
  [r]
  (try
    (validate-reservation r)
    (let [errors (atom [])]
      (let [expected (reservation-hash r)]
        (when (not= expected (:artifact/hash r))
          (swap! errors conj {:type :hash-mismatch
                              :expected expected
                              :actual (:artifact/hash r)})))
      (if (empty? @errors) {:valid? true} {:valid? false :errors @errors}))
    (catch Exception e
      {:valid? false
       :errors [{:type :invalid-structure
                 :message (ex-message e)
                 :data (ex-data e)}]})))

;; ── Reservation helpers ──────────────────────────────────────────────────────

(defn can-reserve?
  "True when pool has at least `amount` available."
  [pool amount]
  (>= (:pool/available-amount pool) amount))

(defn pool-after-reservation
  "Build a successor pool snapshot after applying a reservation.
   pool          — predecessor pool-availability artifact
   reservation   — pool-reservation artifact referencing pool
   successor-opts — {:keys [pool/state-root pool/snapshot-time]}"
  [pool reservation {:keys [pool/state-root pool/snapshot-time]}]
  (when-not (= (:artifact/hash pool) (:reservation/pool-root reservation))
    (throw (ex-info "Reservation pool-root does not match predecessor"
                    {:reservation-root (:reservation/pool-root reservation)
                     :predecessor-hash (:artifact/hash pool)})))
  (let [pool-ok (:valid? (verify-pool-availability pool))
        res-ok (:valid? (verify-reservation reservation))]
    (when-not pool-ok
      (throw (ex-info "Predecessor pool fails verification" {})))
    (when-not res-ok
      (throw (ex-info "Reservation fails verification" {}))))
  (when (some #{(:artifact/hash reservation)}
              (:pool/reservation-roots pool))
    (throw (ex-info "Reservation hash already in pool reservation-roots" {})))
  ;; Time monotonicity: successor time must not precede predecessor time
  (let [pred-time (:pool/snapshot-time pool)
        succ-time snapshot-time]
    (when (and pred-time succ-time (neg? (compare succ-time pred-time)))
      (throw (ex-info "Successor snapshot-time precedes predecessor"
                      {:predecessor pred-time :successor succ-time}))))
  (build-pool-availability
   (-> (select-keys pool pool-source-fields)
       (assoc :pool/state-root state-root
              :pool/snapshot-time snapshot-time
              :pool/predecessor-hash (:artifact/hash pool))
       (update :pool/reserved-amount
               +' (:reservation/amount reservation))
        (update :pool/reservation-roots
                conj (:artifact/hash reservation)))))

;; ── Reservation set verification ─────────────────────────────────────────────

(defn verify-candidate-reservation-set
  "Verify a group of candidate reservations against the same base snapshot.
   Does not reconcile reservations already in the pool's reserved amount.
   Detection-only — does not prevent concurrent construction."
  [pool reservations]
  (try
    (let [pool-ok (:valid? (verify-pool-availability pool))]
      (when-not pool-ok
        (throw (ex-info "Pool fails verification" {}))))
    (let [errors (atom [])]
      (doseq [r reservations]
        (let [v (verify-reservation r)]
          (when-not (:valid? v)
            (swap! errors conj (assoc v :reservation/id (:reservation/id r))))))
      (doseq [r reservations]
        (when (not= (:artifact/hash pool) (:reservation/pool-root r))
          (swap! errors conj {:type :pool-root-mismatch
                              :reservation/id (:reservation/id r)
                              :expected (:artifact/hash pool)
                              :actual (:reservation/pool-root r)})))
      (let [hashes (map :artifact/hash reservations)]
        (when (not= (count hashes) (count (set hashes)))
          (swap! errors conj {:type :duplicate-reservation-hashes
                              :hashes hashes})))
      (let [ids (map :reservation/id reservations)]
        (when (not= (count ids) (count (set ids)))
          (swap! errors conj {:type :duplicate-reservation-ids
                              :ids ids})))
      (let [total (reduce +' 0 (map :reservation/amount reservations))]
        (when (> total (:pool/available-amount pool))
          (swap! errors conj {:type :overcommits
                              :total total
                              :available (:pool/available-amount pool)})))
      (if (empty? @errors) {:valid? true} {:valid? false :errors @errors}))
    (catch Exception e
      {:valid? false
       :errors [{:type :invalid-structure
                 :message (ex-message e)
                 :data (ex-data e)}]})))
