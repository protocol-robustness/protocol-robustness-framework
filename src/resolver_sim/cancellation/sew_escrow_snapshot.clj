(ns resolver-sim.cancellation.sew-escrow-snapshot
  "Read-only escrow state snapshot used by protocol party cancellation."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "sew-escrow-state-snapshot.v1")
(def snapshot-domain "SEW_ESCROW_STATE_SNAPSHOT_V1")
(def cancellation-statuses #{:none :agree-to-cancel})

(defn snapshot-errors [snapshot]
  (vec (remove nil?
               [(when-not (map? snapshot) :snapshot/not-a-map)
                (when (and (map? snapshot) (not= schema-version (:snapshot/schema snapshot))) :snapshot/unsupported-schema)
                (when (and (map? snapshot) (not (some? (:workflow/id snapshot)))) :snapshot/missing-workflow)
                (when (and (map? snapshot) (not (some? (:escrow/sender snapshot)))) :snapshot/missing-sender)
                (when (and (map? snapshot) (not (some? (:escrow/recipient snapshot)))) :snapshot/missing-recipient)
                (when (and (map? snapshot) (not= :pending (:escrow/state snapshot))) :snapshot/not-pending)
                (when (and (map? snapshot) (not (contains? cancellation-statuses (:sender/cancellation-status snapshot)))) :snapshot/invalid-sender-status)
                (when (and (map? snapshot) (not (contains? cancellation-statuses (:recipient/cancellation-status snapshot)))) :snapshot/invalid-recipient-status)])))

(defn valid-snapshot? [snapshot] (empty? (snapshot-errors snapshot)))
(defn snapshot-root [snapshot]
  (when-not (valid-snapshot? snapshot) (throw (ex-info "invalid SEW escrow state snapshot" {:errors (snapshot-errors snapshot)})))
  (hash-ref/sha256-ref (hc/domain-hash snapshot-domain (dissoc snapshot :snapshot/root))))
(defn snapshot-root-valid? [snapshot]
  (and (valid-snapshot? snapshot) (= (:snapshot/root snapshot) (snapshot-root (dissoc snapshot :snapshot/root)))))
(defn build-snapshot [world workflow-id]
  (let [transfer (get-in world [:escrow-transfers workflow-id])
        s {:snapshot/schema schema-version :workflow/id workflow-id
           :escrow/sender (:from transfer) :escrow/recipient (:to transfer)
           :escrow/state (:escrow-state transfer)
           :sender/cancellation-status (:sender-status transfer)
           :recipient/cancellation-status (:recipient-status transfer)}]
    (assoc s :snapshot/root (snapshot-root s))))
