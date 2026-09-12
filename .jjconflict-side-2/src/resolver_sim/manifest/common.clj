(ns resolver-sim.manifest.common
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [resolver-sim.logging :as log]))

(defn safe-slurp [path]
  (try
    (let [f (io/file path)]
      (when (.exists f) (slurp f)))
    (catch Exception e
      (log/warn! "manifest/safe-slurp-failed" {:path path :error (.getMessage e)})
      nil)))

(defn read-json [path]
  (when-let [s (safe-slurp path)]
    (try
      (json/read-str s {:key-fn keyword})
      (catch Exception e
        (log/warn! "manifest/read-json-failed" {:path path :error (.getMessage e)})
        nil))))

(defn read-edn [path]
  (when-let [s (safe-slurp path)]
    (try
      (edn/read-string s)
      (catch Exception e
        (log/warn! "manifest/read-edn-failed" {:path path :error (.getMessage e)})
        nil))))
