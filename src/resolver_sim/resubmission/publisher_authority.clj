(ns resolver-sim.resubmission.publisher-authority
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "attempt-publisher-authority.v1")
(def domain :prf-attempt-publisher-authority-v1)
(def publish-action :prf.resubmission/publish-attempt)

(defn projection [authority]
  (select-keys authority [:artifact/schema :publisher-authority/entries]))

(defn root [authority]
  (ref/sha256-ref (hc/domain-hash domain (projection authority))))

(defn entry-valid? [entry]
  (and (= #{:principal/id :key/id :key/public :authorized-actions}
          (set (keys entry)))
       (string? (:principal/id entry))
       (string? (:key/id entry))
       (string? (:key/public entry))
       (= [publish-action] (:authorized-actions entry))))

(defn valid? [authority]
  (and (map? authority)
       (= #{:artifact/schema :publisher-authority/entries :attempt-publisher-authority/root}
          (set (keys authority)))
       (= schema (:artifact/schema authority))
       (vector? (:publisher-authority/entries authority))
       (every? entry-valid? (:publisher-authority/entries authority))
       (= (count (:publisher-authority/entries authority))
          (count (set (map :key/id (:publisher-authority/entries authority)))))
       (= (:attempt-publisher-authority/root authority) (root authority))))

(defn build [authority]
  (let [built (assoc authority :artifact/schema schema)]
    (assoc built :attempt-publisher-authority/root (root built))))

(defn authorized-key [authority key-id]
  (some #(when (and (= key-id (:key/id %))
                    (some #{publish-action} (:authorized-actions %))) %)
        (:publisher-authority/entries authority)))
