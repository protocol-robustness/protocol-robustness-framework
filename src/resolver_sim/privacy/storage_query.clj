(ns resolver-sim.privacy.storage-query
  "Read-only queries over signed storage inventory snapshots.

   Results are relative to caller-supplied storage and subject trust policies.
   This namespace has no live storage adapter and cannot prove inventory
   completeness beyond the signed coverage declaration."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.sensitivity.sentinel :as sentinel]))

(def inventory-schema "stablecoin-privacy-storage-inventory.v1")
(def authorization-schema "stablecoin-privacy-query-authorization.v1")
(def inventory-domain "PRF_STABLECOIN_PRIVACY_STORAGE_INVENTORY_V1")
(def authorization-domain "PRF_STABLECOIN_PRIVACY_QUERY_AUTHORIZATION_V1")
(def ^:private result-schema "stablecoin-privacy-storage-query.v1")
(def ^:private max-query-authorization-seconds 300)
(def ^:private max-inventory-records 10000)

(def ^:private inventory-fields
  #{:storage-inventory/schema :storage-inventory/subject-ref
    :storage-inventory/observed-at :storage-inventory/coverage
    :storage-inventory/records :storage-inventory/root :signature})
(def ^:private coverage-fields #{:coverage/sink :coverage/status})
(def ^:private record-fields
  #{:record/event-root :record/data-category :record/policy-root :record/sink
    :record/state :record/created-at :record/observed-at
    :record/retention-until})
(def ^:private authorization-fields
  #{:query-authorization/schema :query-authorization/scope
    :query-authorization/subject-ref :query-authorization/target-ref
    :query-authorization/issued-at :query-authorization/expires-at :signature})

(defn- epoch? [value]
  (and (integer? value) (<= 0 value Long/MAX_VALUE)))

(defn- non-empty-string? [value]
  (and (string? value) (seq value)))

(defn- hash-ref? [value]
  (ref/valid-sha256-ref? value))

(defn inventory-root [inventory]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-storage-inventory.v1"
                   (dissoc inventory :storage-inventory/root :signature))))

(defn- valid-coverage? [coverage]
  (and (map? coverage)
       (= coverage-fields (set (keys coverage)))
       (contains? sentinel/all-sinks (:coverage/sink coverage))
       (contains? #{:complete :partial} (:coverage/status coverage))))

(defn- valid-record? [record inventory-observed-at]
  (and (map? record)
       (= record-fields (set (keys record)))
       (hash-ref? (:record/event-root record))
       (non-empty-string? (:record/data-category record))
       (hash-ref? (:record/policy-root record))
       (contains? sentinel/all-sinks (:record/sink record))
       (contains? #{:stored :deleted} (:record/state record))
       (epoch? (:record/created-at record))
       (epoch? (:record/observed-at record))
       (<= (:record/created-at record) (:record/observed-at record)
           inventory-observed-at)
       (epoch? (:record/retention-until record))
       (<= (:record/created-at record) (:record/retention-until record))))

(defn validate-inventory [inventory]
  (let [observed-at (:storage-inventory/observed-at inventory)
        coverage (:storage-inventory/coverage inventory)
        records (:storage-inventory/records inventory)
        coverage-sinks (when (vector? coverage) (mapv :coverage/sink coverage))
        coverage-sink-set (set coverage-sinks)
        record-keys (when (vector? records)
                      (mapv (juxt :record/event-root :record/sink) records))
        root-valid? (try
                      (= (:storage-inventory/root inventory)
                         (inventory-root inventory))
                      (catch Exception _ false))
        errors (cond-> []
                 (not (map? inventory)) (conj :inventory-not-map)
                 (and (map? inventory)
                      (not= inventory-fields (set (keys inventory))))
                 (conj :inventory-fields-invalid)
                 (not= inventory-schema (:storage-inventory/schema inventory))
                 (conj :inventory-schema-invalid)
                 (not (hash-ref? (:storage-inventory/subject-ref inventory)))
                 (conj :inventory-subject-ref-invalid)
                 (not (epoch? observed-at)) (conj :inventory-time-invalid)
                 (not (vector? coverage)) (conj :inventory-coverage-invalid)
                 (and (vector? coverage)
                      (not-every? valid-coverage? coverage))
                 (conj :inventory-coverage-entry-invalid)
                 (and (vector? coverage)
                      (not= (count coverage-sinks) (count (distinct coverage-sinks))))
                 (conj :inventory-coverage-duplicate-sink)
                 (not (and (vector? records)
                           (<= (count records) max-inventory-records)))
                 (conj :inventory-records-invalid)
                 (and (vector? records)
                      (not-every? #(valid-record? % observed-at) records))
                 (conj :inventory-record-invalid)
                 (and (vector? records)
                      (not-every? #(contains? coverage-sink-set
                                              (:record/sink %))
                                  records))
                 (conj :inventory-record-sink-not-covered)
                 (and (vector? records)
                      (not= (count record-keys) (count (distinct record-keys))))
                 (conj :inventory-record-duplicate)
                 (not root-valid?)
                 (conj :inventory-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn- trust-policy-root [trust-policy]
  (try
    (when (map? trust-policy)
      (ref/sha256-ref
       (hc/domain-hash "prf.stablecoin-privacy-query-caller-trust-policy.v1"
                       trust-policy)))
    (catch Exception _ nil)))

(defn- verify-authorization
  [{:keys [authorization authorization-trust-policy scope target-ref subject-ref now]}]
  (let [structure-valid? (and (map? authorization)
                              (= authorization-fields (set (keys authorization)))
                              (= authorization-schema
                                 (:query-authorization/schema authorization))
                              (= scope (:query-authorization/scope authorization))
                              (= target-ref (:query-authorization/target-ref authorization))
                              (= subject-ref (:query-authorization/subject-ref authorization))
                              (hash-ref? subject-ref)
                              (hash-ref? target-ref)
                              (epoch? (:query-authorization/issued-at authorization))
                              (epoch? (:query-authorization/expires-at authorization))
                              (epoch? now)
                              (< (:query-authorization/issued-at authorization)
                                 (:query-authorization/expires-at authorization))
                              (<= (- (:query-authorization/expires-at authorization)
                                     (:query-authorization/issued-at authorization))
                                  max-query-authorization-seconds)
                              (<= (:query-authorization/issued-at authorization) now
                                  (:query-authorization/expires-at authorization)))
        verification (when structure-valid?
                       (try
                         (signed/verify-envelope authorization authorization-domain
                                                 authorization-trust-policy
                                                 :privacy-data-subject)
                         (catch Exception _ {:valid? false :reason :invalid-trust-input})))
        key-id (:key-id verification)
        trusted-key (some #(when (= key-id (:key/id %)) %)
                          (:trusted-keys authorization-trust-policy))
        subject-key-bound? (= subject-ref (:key/subject-ref trusted-key))]
    {:valid? (boolean (and structure-valid? (:valid? verification)
                           subject-key-bound?))
     :key-id key-id
     :reason (cond
               (not structure-valid?) :authorization-context-invalid
               (not (:valid? verification)) (:reason verification)
               (not subject-key-bound?) :authorization-subject-binding-mismatch
               :else nil)}))

(defn- verify-inventory
  [{:keys [inventory storage-trust-policy subject-ref now
           max-observation-age-seconds]}]
  (let [structure (validate-inventory inventory)
        time-valid? (and (epoch? now)
                         (epoch? max-observation-age-seconds))
        observed-at (:storage-inventory/observed-at inventory)
        fresh? (and time-valid? (epoch? observed-at)
                    (<= observed-at now)
                    (<= (- now observed-at) max-observation-age-seconds))
        records-fresh? (and time-valid?
                            (every? (fn [record]
                                      (and (epoch? (:record/observed-at record))
                                           (<= (:record/observed-at record) now)
                                           (<= (- now (:record/observed-at record))
                                               max-observation-age-seconds)))
                                    (:storage-inventory/records inventory)))
        signature (when (:valid? structure)
                    (try
                      (signed/verify-envelope inventory inventory-domain
                                              storage-trust-policy
                                              :privacy-storage-observer)
                      (catch Exception _ {:valid? false :reason :invalid-trust-input})))
        subject-matches? (= subject-ref (:storage-inventory/subject-ref inventory))]
    {:valid? (boolean (and (:valid? structure) time-valid? fresh? records-fresh?
                           subject-matches? (:valid? signature)))
     :structure-valid? (:valid? structure)
     :errors (:errors structure)
     :fresh? (boolean fresh?)
     :signature-valid? (boolean (:valid? signature))
     :signature-reason (:reason signature)
     :subject-matches? subject-matches?
     :trust-policy-root (trust-policy-root storage-trust-policy)
     :trust-policy-id (:trust/id storage-trust-policy)}))

(defn- safe-record [record now]
  {:sink (:record/sink record)
   :data-category (:record/data-category record)
   :state (:record/state record)
   :policy-root (:record/policy-root record)
   :observed-at (:record/observed-at record)
   :retention-until (:record/retention-until record)
   :seconds-until-retention (max 0 (- (:record/retention-until record) now))
   :retention-status (cond
                       (= :deleted (:record/state record)) :deleted-observed
                       (< (:record/retention-until record) now)
                       :deadline-passed-but-still-reported
                       :else :within-reported-retention)})

(defn- coverage-summary [inventory]
  (mapv (fn [coverage]
          {:sink (:coverage/sink coverage)
           :status (:coverage/status coverage)})
        (:storage-inventory/coverage inventory)))

(defn- event-status [records inventory]
  (let [coverage (:storage-inventory/coverage inventory)
        covered-sinks (set (map :coverage/sink coverage))]
    (cond
      (some #(= :stored (:record/state %)) records) :stored-observed
      (seq records) :deleted-observed-in-covered-sinks
      (and (= sentinel/all-sinks covered-sinks)
           (every? #(= :complete (:coverage/status %)) coverage))
      :not-observed-in-covered-sinks
      :else :unknown)))

(defn- base-result [scope verified-inventory authorization]
  {:privacy-storage-query/schema result-schema
   :privacy-storage-query/scope scope
   :privacy-storage-query/status :available
   :privacy-storage-query/event-status :unknown
   :privacy-storage-query/current-policy-status :not-resolved-by-storage-query
   :privacy-storage-query/storage-trust-status
   (if (and (:valid? verified-inventory) (:valid? authorization))
     :verified-relative-to-caller-supplied-policy :unverified)
   :privacy-storage-query/authorization-status
   (if (:valid? authorization)
     :verified-relative-to-caller-supplied-policy :denied)
   :privacy-storage-query/trust-provenance :caller-supplied-policies
   :privacy-storage-query/usable? false
   :privacy-storage-query/execution :not-executed
   :privacy-storage-query/details-omitted? true})

(defn query-event
  "Query a single event from one signed inventory snapshot. Absence is never
   presented as global deletion or non-storage; the result names the covered
   sinks and caller-relative trust status."
  [{:keys [event-root subject-ref inventory storage-trust-policy
           authorization authorization-trust-policy now
           max-observation-age-seconds] :as input}]
  (let [authorization-result
        (verify-authorization {:authorization authorization
                               :authorization-trust-policy authorization-trust-policy
                               :scope :event :target-ref event-root
                               :subject-ref subject-ref :now now})
        inventory-result
        (verify-inventory {:inventory inventory
                           :storage-trust-policy storage-trust-policy
                           :subject-ref subject-ref :now now
                           :max-observation-age-seconds max-observation-age-seconds})
        base (base-result :event inventory-result authorization-result)
        records (when (and (:valid? authorization-result)
                           (:valid? inventory-result)
                           (hash-ref? event-root))
                  (filterv #(= event-root (:record/event-root %))
                           (:storage-inventory/records inventory)))
        status (if (and (:valid? authorization-result) (:valid? inventory-result))
                 (event-status records inventory) :unknown)]
    (cond-> (assoc base :privacy-storage-query/event-status status
                   :privacy-storage-query/records [])
      (and (:valid? authorization-result) (:valid? inventory-result))
      (assoc :privacy-storage-query/coverage (coverage-summary inventory)
             :privacy-storage-query/observed-at
             (:storage-inventory/observed-at inventory)
             :privacy-storage-query/storage-trust-policy-id
             (:trust-policy-id inventory-result)
             :privacy-storage-query/storage-trust-policy-root
             (:trust-policy-root inventory-result)
             :privacy-storage-query/details-omitted? false
             :privacy-storage-query/records (mapv #(safe-record % now) records))
      (not (:valid? authorization-result))
      (assoc :privacy-storage-query/status :authorization-denied
             :privacy-storage-query/authorization-reason :denied)
      (not (:valid? inventory-result))
      (assoc :privacy-storage-query/status :unavailable
             :privacy-storage-query/records []))))

(defn query-wallet
  "Return an authenticated, privacy-minimized summary for one opaque subject
   reference. This reports only the signed snapshot supplied by the caller."
  [{:keys [subject-ref inventory storage-trust-policy authorization
           authorization-trust-policy now max-observation-age-seconds]}]
  (let [authorization-result
        (verify-authorization {:authorization authorization
                               :authorization-trust-policy authorization-trust-policy
                               :scope :wallet :target-ref subject-ref
                               :subject-ref subject-ref :now now})
        inventory-result
        (verify-inventory {:inventory inventory
                           :storage-trust-policy storage-trust-policy
                           :subject-ref subject-ref :now now
                           :max-observation-age-seconds max-observation-age-seconds})
        base (base-result :wallet inventory-result authorization-result)
        records (when (and (:valid? authorization-result)
                           (:valid? inventory-result))
                  (:storage-inventory/records inventory))
        grouped (when records
                  (->> records
                       (group-by (juxt :record/data-category :record/sink
                                       :record/state :record/retention-until
                                       :record/policy-root))
                       (map (fn [[[category sink state retention-until policy-root] rows]]
                              {:data-category category :sink sink :state state
                               :retention-until retention-until
                               :policy-root policy-root :record-count (count rows)}))
                       (sort-by (juxt :data-category :sink :state :retention-until))
                       vec))
        status (if (and (:valid? authorization-result) (:valid? inventory-result))
                 (event-status records inventory) :unknown)]
    (cond-> (assoc base :privacy-storage-query/event-status status
                   :privacy-storage-query/summary []
                   :privacy-storage-query/record-count 0)
      (and (:valid? authorization-result) (:valid? inventory-result))
      (assoc :privacy-storage-query/coverage (coverage-summary inventory)
             :privacy-storage-query/observed-at
             (:storage-inventory/observed-at inventory)
             :privacy-storage-query/storage-trust-policy-id
             (:trust-policy-id inventory-result)
             :privacy-storage-query/storage-trust-policy-root
             (:trust-policy-root inventory-result)
             :privacy-storage-query/details-omitted? false
             :privacy-storage-query/summary grouped
             :privacy-storage-query/record-count (count records))
      (not (:valid? authorization-result))
      (assoc :privacy-storage-query/status :authorization-denied
             :privacy-storage-query/authorization-reason :denied)
      (not (:valid? inventory-result))
      (assoc :privacy-storage-query/status :unavailable
             :privacy-storage-query/summary []
             :privacy-storage-query/record-count 0))))
