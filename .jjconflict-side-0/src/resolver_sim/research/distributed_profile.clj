(ns resolver-sim.research.distributed-profile
  "Rooted distributed topology and dependency-placement semantics.

   Distance is a declared integer relation. It is not inferred from IDs and is
   not latency. Version 1 requires symmetric links and identity distance zero,
   but deliberately does not claim triangle inequality or physical units."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "distributed-experiment-profile.v1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn profile-root [profile]
  (root :distributed-experiment-profile-v1
        (dissoc profile :distributed-experiment-profile/root)))

(defn- ids-unique? [xs key-fn]
  (= (count xs) (count (set (map key-fn xs)))))

(defn- nonnegative-int? [x] (and (integer? x) (not (neg? x))))

(defn- link-key [link] [(:link/from-site link) (:link/to-site link)])

(defn validate-profile [profile]
  (let [sites (:topology/sites profile)
        workers (:topology/workers profile)
        links (:topology/links profile)
        site-ids (set (map :site/id sites))
        by-link (into {} (map (juxt link-key identity) links))
        replicas (:dependency/replicas profile)
        distance-properties (:distance/properties profile)
        symmetric? (:symmetric? distance-properties)
        errors
        (cond-> []
          (not (map? profile)) (conj :distributed-profile/not-a-map)
          (and (map? profile) (not= schema (:artifact/schema profile)))
          (conj :distributed-profile/unsupported-schema)
          (not (and (vector? sites) (seq sites) (ids-unique? sites :site/id)
                    (every? #(and (keyword? (:site/id %))
                                  (boolean? (:site/available? %))
                                  (= #{:site/id :site/available?} (set (keys %)))) sites)))
          (conj :distributed-profile/invalid-sites)
          (not (and (vector? workers) (seq workers) (ids-unique? workers :worker/id)
                    (every? #(and (keyword? (:worker/id %))
                                  (contains? site-ids (:worker/site-id %))
                                  (boolean? (:worker/available? %))
                                  (vector? (:worker/resource-kinds %))
                                  (seq (:worker/resource-kinds %))
                                  (every? keyword? (:worker/resource-kinds %))
                                  (= #{:worker/id :worker/site-id :worker/available?
                                       :worker/resource-kinds} (set (keys %)))) workers)))
          (conj :distributed-profile/invalid-workers)
          (not (and (vector? links) (ids-unique? links link-key)
                    (every? #(and (contains? site-ids (:link/from-site %))
                                  (contains? site-ids (:link/to-site %))
                                  (not= (:link/from-site %) (:link/to-site %))
                                  (nonnegative-int? (:link/distance %))
                                  (nonnegative-int? (:link/base-latency-ms %))
                                  (pos-int? (:link/bandwidth-bytes-per-ms %))
                                  (nonnegative-int? (:link/distance-cost-ms-per-unit %))
                                  (boolean? (:link/available? %))
                                  (= #{:link/from-site :link/to-site :link/distance
                                       :link/base-latency-ms :link/bandwidth-bytes-per-ms
                                       :link/distance-cost-ms-per-unit :link/available?}
                                     (set (keys %)))) links)))
          (conj :distributed-profile/invalid-links)
          (not= {:nonnegative? true :identity-zero? true
                 :symmetric? true :triangle-inequality? false}
                distance-properties)
          (conj :distributed-profile/unsupported-distance-properties)
          (and symmetric?
               (some (fn [link]
                       (not= (dissoc link :link/from-site :link/to-site)
                             (some-> (get by-link [(:link/to-site link) (:link/from-site link)])
                                     (dissoc :link/from-site :link/to-site))))
                     links))
          (conj :distributed-profile/asymmetric-link)
          (not (and (map? replicas)
                    (every? (fn [[task-id locations]]
                              (and (nonnegative-int? task-id)
                                   (vector? locations)
                                   (= locations (vec (sort locations)))
                                   (= (count locations) (count (set locations)))
                                   (every? site-ids locations)))
                            replicas)))
          (conj :distributed-profile/invalid-dependency-replicas)
          (and (map? profile)
               (not= (:distributed-experiment-profile/root profile)
                     (try (profile-root profile) (catch Exception _ nil))))
          (conj :distributed-profile/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-profile
  [{:keys [sites workers links dependency-replicas]}]
  (let [base {:artifact/schema schema
              :topology/sites (vec (sort-by :site/id sites))
              :topology/workers (vec (sort-by :worker/id workers))
              :topology/links (vec (sort-by (juxt :link/from-site :link/to-site) links))
              :distance/properties {:nonnegative? true :identity-zero? true
                                    :symmetric? true :triangle-inequality? false}
              :dependency/replicas (into (sorted-map) dependency-replicas)}
        rooted (assoc base :distributed-experiment-profile/root (profile-root base))
        validation (validate-profile rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid distributed experiment profile" validation)))
    rooted))

(defn worker [profile worker-id]
  (some #(when (= worker-id (:worker/id %)) %) (:topology/workers profile)))

(defn site [profile site-id]
  (some #(when (= site-id (:site/id %)) %) (:topology/sites profile)))

(defn link [profile from-site to-site]
  (when-not (= from-site to-site)
    (some #(when (and (= from-site (:link/from-site %))
                      (= to-site (:link/to-site %))) %)
          (:topology/links profile))))

(defn transfer
  "Return deterministic transfer facts, or nil when no declared path exists.

   transfer-time = base latency + distance*declared distance cost
                   + ceil(bytes/bandwidth). Local transfer is zero."
  [profile from-site to-site bytes]
  (if (= from-site to-site)
    {:transfer/path-id :path/local :transfer/distance 0
     :transfer/latency-ms 0 :transfer/bytes 0 :transfer/time-ms 0
     :transfer/available? true}
    (when-let [link (link profile from-site to-site)]
      (let [time (+ (:link/base-latency-ms link)
                    (* (:link/distance link) (:link/distance-cost-ms-per-unit link))
                    (quot (+ bytes (dec (:link/bandwidth-bytes-per-ms link)))
                          (:link/bandwidth-bytes-per-ms link)))]
        {:transfer/path-id
         (root :distributed-placement-projection-v1
               {:from from-site :to to-site :link link})
         :transfer/distance (:link/distance link)
         :transfer/latency-ms (:link/base-latency-ms link)
         :transfer/bytes bytes :transfer/time-ms time
         :transfer/available? (:link/available? link)}))))
