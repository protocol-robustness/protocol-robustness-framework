(ns resolver-sim.research.partial-fill-file-artifacts
  "Pure rooted artifacts and economic projections for the offline partial-fill file profile."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const profile-schema "partial-fill-file-profile.v0")
(def ^:const operation-schema "partial-fill-file-operation.v1")
(def ^:const submission-schema "partial-fill-file-submission.v1")
(def ^:const source-schema "partial-fill-file-source.v1")
(def ^:const preparation-schema "partial-fill-file-preparation.v1")
(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))
(defn- nonnegative-integer? [value]
  (and (integer? value) (not (neg? value))))
(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))
(defn- canonical-roots? [roots]
  (and (vector? roots) (= roots (vec (sort roots)))
       (= (count roots) (count (set roots))) (every? root? roots)))

(defn operation-root [operation]
  (root "PRF_PARTIAL_FILL_FILE_OPERATION_V1"
        (dissoc operation :operation/root)))

(defn valid-operation? [operation]
  (and (map? operation)
       (= #{:artifact/schema :operation/id :operation/asset-root
            :operation/policy-root :operation/entitlements
            :operation/claimable-total :operation/restricted-refund
            :operation/funded-total :operation/root}
          (set (keys operation)))
       (= operation-schema (:artifact/schema operation))
       (qualified-keyword? (:operation/id operation))
       (every? root? [(:operation/asset-root operation)
                      (:operation/policy-root operation)])
       (vector? (:operation/entitlements operation))
       (seq (:operation/entitlements operation))
       (= (:operation/entitlements operation)
          (vec (sort-by :entitlement/root (:operation/entitlements operation))))
       (= (count (:operation/entitlements operation))
          (count (set (map :entitlement/root (:operation/entitlements operation)))))
       (every? (fn [entitlement]
                 (and (= #{:entitlement/root :beneficiary/root :entitlement/amount}
                         (set (keys entitlement)))
                      (root? (:entitlement/root entitlement))
                      (root? (:beneficiary/root entitlement))
                      (positive-integer? (:entitlement/amount entitlement))))
               (:operation/entitlements operation))
       (nonnegative-integer? (:operation/restricted-refund operation))
       (= (:operation/claimable-total operation)
          (reduce +' 0 (map :entitlement/amount (:operation/entitlements operation))))
       (= (:operation/funded-total operation)
          (+ (:operation/claimable-total operation)
             (:operation/restricted-refund operation)))
       (nonnegative-integer? (:operation/funded-total operation))
       (= (:operation/root operation)
          (try (operation-root operation) (catch Exception _ nil)))))

(defn build-operation
  [{:keys [operation-id asset-root policy-root entitlements restricted-refund]}]
  (let [entitlements (vec (sort-by :entitlement/root entitlements))
        claimable-total (reduce +' 0 (map :entitlement/amount entitlements))
        body {:artifact/schema operation-schema
              :operation/id operation-id
              :operation/asset-root asset-root
              :operation/policy-root policy-root
              :operation/entitlements entitlements
              :operation/claimable-total claimable-total
              :operation/restricted-refund restricted-refund
              :operation/funded-total (+ claimable-total restricted-refund)}
        operation (assoc body :operation/root (operation-root body))]
    (when-not (valid-operation? operation)
      (throw (ex-info "Invalid file-profile operation" {:operation operation})))
    operation))

(defn submission-root [submission]
  (root "PRF_PARTIAL_FILL_FILE_SUBMISSION_V1"
        (dissoc submission :submission/root)))

(defn build-source
  [{:keys [operation-root evidence-root]}]
  (let [body {:artifact/schema source-schema
              :source/operation-root operation-root
              :source/evidence-root evidence-root}
        source (assoc body :source/root
                      (root "PRF_PARTIAL_FILL_FILE_SOURCE_V1" body))]
    (when-not (every? root? [operation-root evidence-root])
      (throw (ex-info "Invalid file-profile source artifact" {})))
    source))

(defn build-preparation
  [{:keys [submission-root snapshot-root worker-root result-root]}]
  (let [body {:artifact/schema preparation-schema
              :preparation/submission-root submission-root
              :preparation/snapshot-root snapshot-root
              :preparation/worker-root worker-root
              :preparation/result-root result-root}
        preparation (assoc body :preparation/root (root "PRF_PARTIAL_FILL_FILE_PREPARATION_V1" body))]
    (when-not (every? root? [submission-root snapshot-root worker-root result-root])
      (throw (ex-info "Invalid file-profile worker preparation" {})))
    preparation))

(defn submission-snapshot-root [operation submission]
  (root "PRF_PARTIAL_FILL_FROZEN_SNAPSHOT_V1"
        {:operation/root (:operation/root operation)
         :asset/root (:operation/asset-root operation)
         :policy/root (:operation/policy-root operation)
         :source-roots (:submission/source-roots submission)}))

(defn build-submission
  [{:keys [idempotency-root operation-root entitlement-root beneficiary-root amount
           asset-root policy-root source-roots submitter-root]}]
  (let [body {:artifact/schema submission-schema
              :submission/idempotency-root idempotency-root
              :submission/operation-root operation-root
              :submission/entitlement-root entitlement-root
              :submission/beneficiary-root beneficiary-root
              :submission/amount amount
              :submission/asset-root asset-root
              :submission/policy-root policy-root
              :submission/source-roots (vec (sort (distinct source-roots)))
              :submission/submitter-root submitter-root}
        submission (assoc body :submission/root (submission-root body))]
    (when-not (and (every? root? [idempotency-root operation-root entitlement-root
                                  beneficiary-root asset-root policy-root submitter-root])
                   (positive-integer? amount)
                   (seq (:submission/source-roots submission))
                   (canonical-roots? (:submission/source-roots submission))
                   (= submission-schema (:artifact/schema submission)))
      (throw (ex-info "Invalid file-profile submission" {})))
    submission))

(defn economic-projection [state]
  (let [operation (:operation state)
        entitlements (:operation/entitlements operation)
        paid (into (sorted-map)
                   (map (fn [entitlement]
                          (let [entitlement-root (:entitlement/root entitlement)]
                            [entitlement-root (get (:paid-by-entitlement state)
                                                   entitlement-root 0)])))
                   entitlements)
        remaining (into (sorted-map)
                        (map (fn [entitlement]
                               (let [entitlement-root (:entitlement/root entitlement)]
                                 [entitlement-root (- (:entitlement/amount entitlement)
                                                      (get paid entitlement-root 0))])))
                        entitlements)
        paid-total (reduce +' 0 (vals paid))
        remaining-total (reduce +' 0 (vals remaining))]
    {:operation/root (:operation/root operation)
     :asset/root (:operation/asset-root operation)
     :policy/root (:operation/policy-root operation)
     :paid-by-entitlement paid
     :remaining-by-entitlement remaining
     :paid-total paid-total
     :remaining-total remaining-total
     :restricted/refund (:operation/restricted-refund operation)
     :funded-total (:operation/funded-total operation)}))

(defn economic-projection-root [state]
  (root "PRF_PARTIAL_FILL_ECONOMIC_PROJECTION_V1" (economic-projection state)))

