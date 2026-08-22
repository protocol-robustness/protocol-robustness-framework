(ns resolver-sim.scenario.normalize
  "JSON/EDN scenario normalization for file-backed traces (pure)."
  (:require [clojure.walk :as walk]))

(defn- normalize-keyword-strings
  [v]
  (cond
    (string? v)
    (if (and (.startsWith v ":") (> (count v) 1))
      (keyword (subs v 1))
      v)
    (keyword? v) v
    :else v))

(defn- normalize-map-keys
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (let [normalized-k (if (string? k)
                                      (try (Integer/parseInt k)
                                           (catch Exception _ k))
                                      k)]
                   (assoc acc normalized-k v)))
               {} m)
    m))

(defn- normalize-error-kw [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn- normalize-seq [v]
  (if (string? v) (try (Integer/parseInt v) (catch Exception _ v)) v))

(defn- normalize-expected-errors
  [scenario]
  (if-let [errs (:expected-errors scenario)]
    (assoc scenario :expected-errors
           (mapv #(-> %
                      (update :error normalize-error-kw)
                      (update :seq normalize-seq))
                 errs))
    scenario))

(defn- normalize-yield-preset-param
  [v]
  (cond
    (nil? v)     :off
    (keyword? v) v
    (string? v)  (keyword (.replace ^String v "_" "-"))
    :else        (keyword (str v))))

(defn- normalize-reason-param
  [v]
  (cond
    (nil? v)        nil
    (keyword? v)    v
    (string? v)     (keyword v)
    :else           (keyword (str v))))

(defn- normalize-event-params
  [params]
  (if-not (map? params)
    params
    (cond-> params
      (contains? params :yield-preset)
      (update :yield-preset normalize-yield-preset-param)
      (contains? params :yield_preset)
      (update :yield_preset normalize-yield-preset-param)
      (contains? params :reason)
      (update :reason normalize-reason-param))))

(defn- normalize-scenario-events
  [scenario]
  (if-let [events (:events scenario)]
    (assoc scenario :events
           (mapv #(cond-> %
                    :always (update :seq normalize-seq)
                    (:params %)
                    (update :params normalize-event-params))
                 events))
    scenario))

(defn normalize-scenario
  "Recursively normalize a loaded scenario to fix JSON deserialization issues."
  [x]
  (let [normalized (walk/postwalk
                    (fn [v]
                      (cond
                        (map? v)
                        (let [key-normalized (normalize-map-keys v)]
                          (reduce-kv (fn [m k kv]
                                       (assoc m k (normalize-keyword-strings kv)))
                                     key-normalized key-normalized))

                        (string? v)
                        (normalize-keyword-strings v)

                        :else v))
                    x)]
    (if (map? normalized)
      (-> normalized normalize-expected-errors normalize-scenario-events)
      normalized)))
