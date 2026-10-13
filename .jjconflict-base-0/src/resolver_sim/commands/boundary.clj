(ns resolver-sim.commands.boundary
  "Shared command-boundary errors and safe input parsing."
  (:require [clojure.edn :as edn]))

(defn fail
  ([kind message] (fail kind message nil))
  ([kind message data]
   (throw (ex-info message (merge {:error/kind kind} data)))))

(defn required-value [option value]
  (when (or (nil? value) (and (string? value) (empty? value)))
    (fail :invalid-option (str option " requires a value")))
  value)

(defn parse-integer [option value]
  (try
    (Long/parseLong (required-value option value))
    (catch NumberFormatException _
      (fail :invalid-option (str option " must be an integer")))))

(defn read-edn [path]
  (required-value "--input" path)
  (try
    (edn/read-string (slurp (if (= "-" path) *in* path)))
    (catch java.io.FileNotFoundException e (fail :missing-input (.getMessage e)))
    (catch java.io.IOException e (fail :unreadable-input (.getMessage e)))
    (catch Exception e (fail :malformed-input (.getMessage e)))))
