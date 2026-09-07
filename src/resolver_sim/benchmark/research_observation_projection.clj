(ns resolver-sim.benchmark.research-observation-projection
  "Closed semantic observations emitted by benchmark execution producers."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "research-observation-projection.v1")

(defn- valid-observation? [{:observation/keys [value domain]}]
  (and (integer? value)
       (= :integer (:kind domain))
       (keyword? (:unit domain))))

(defn projection-root [projection]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-observation-projection
                   (dissoc projection :research-observation-projection/root))))

(defn validate-projection [projection]
  (let [observations (:observations projection)
        errors (cond-> []
                 (not= schema (:artifact/schema projection))
                 (conj :research-observation/unsupported-schema)
                 (not (and (map? observations) (seq observations)))
                 (conj :research-observation/invalid-observations)
                 (and (map? observations)
                      (some (fn [[id observation]]
                              (or (not (and (keyword? id) (namespace id)))
                                  (not (valid-observation? observation))))
                            observations))
                 (conj :research-observation/invalid-observation)
                 (not= (:research-observation-projection/root projection)
                       (projection-root projection))
                 (conj :research-observation/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-projection [observations]
  (let [projection {:artifact/schema schema :observations observations}
        rooted (assoc projection :research-observation-projection/root (projection-root projection))]
    (when-not (:valid? (validate-projection rooted))
      (throw (ex-info "Invalid research observation projection" (validate-projection rooted))))
    rooted))

(defn observation-values [projection]
  (when-not (:valid? (validate-projection projection))
    (throw (ex-info "Invalid research observation projection" (validate-projection projection))))
  (into {} (map (fn [[id observation]] [id (:observation/value observation)])
                (:observations projection))))
