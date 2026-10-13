(ns resolver-sim.benchmark.registry
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.io.edn :as ppedn]))

(def registry-root (str (System/getProperty "user.home") "/.protocol-robustness/evidence"))

(defn- ensure-registry []
  (.mkdirs (io/file registry-root)))

(defn- get-history-file []
  (io/file registry-root "history.edn"))

(defn- load-history []
  (let [f (get-history-file)]
    (if (.exists f)
      (edn/read-string (slurp f))
      [])))

(defn- save-history [history]
  (spit (get-history-file) (ppedn/ppr-str history)))

(defn record-entry [entry]
  (ensure-registry)
  (let [history (load-history)
        new-history (conj history (assoc entry :timestamp (System/currentTimeMillis)))]
    (save-history new-history)))

(defn get-history []
  (load-history))
