(ns resolver-sim.community.task
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "research-task.v0")
(def ^:const supported-task-types #{:benchmark-execution :independent-reproduction :counterexample-search})
(def ^:const domain-tag "COMMUNITY_TASK_V0")
(def ^:const task-ref-prefix "research-task:sha256:")

(defn task-ref [hash] (str task-ref-prefix hash))
(defn task-ref? [s] (and (string? s) (.startsWith s task-ref-prefix)))
(defn parse-task-ref [s] (when (task-ref? s) (subs s (count task-ref-prefix))))

(defn- normalize-for-hash [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (integer? x) x
    (string? x) x
    (keyword? x) x
    (instance? java.time.Instant x) (str x)
    (vector? x) (mapv normalize-for-hash x)
    (map? x) (persistent!
              (reduce-kv (fn [m k v] (assoc! m (normalize-for-hash k) (normalize-for-hash v)))
                         (transient {}) x))
    (set? x) (vec (sort (map normalize-for-hash x)))
    (sequential? x) (mapv normalize-for-hash x)
    :else (str x)))

(defn- canonical-body [m]
  (dissoc m :task/id :task/hash :task/ref :registered-at))

(defn- compute-hash [body]
  (hc/domain-hash domain-tag (normalize-for-hash (canonical-body body))))

(defn build-task
  [m]
  (let [task-type (or (:task/type m) :benchmark-execution)
        title (:title m)
        _ (assert (contains? supported-task-types task-type))
        _ (assert (string? title))
        body {:schema-version schema-version
              :task/type task-type
              :title title
              :description (:description m)
              :subject (:subject m)
              :question (:question m)
              :benchmark/id (:benchmark/id m)
              :suite/id (:suite/id m)
              :claim-ids (vec (or (:claim-ids m) []))
              :acceptance-criteria (vec (or (:acceptance-criteria m) []))
              :registered-at (or (:registered-at m) (str (java.time.Instant/now)))
              :registry-snapshot/hash (:registry-snapshot/hash m)}
        hash (compute-hash body)]
    (assoc body :task/id hash :task/hash hash :task/ref (task-ref hash))))

(defn task-hash [task]
  (:task/hash task))

(defn valid-task?
  [task]
  (and (map? task)
       (= schema-version (:schema-version task))
       (contains? supported-task-types (:task/type task))
       (string? (:task/hash task))
       (= (:task/id task) (:task/hash task))
       (= (compute-hash task) (:task/hash task))))
