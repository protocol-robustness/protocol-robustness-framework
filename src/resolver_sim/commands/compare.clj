(ns resolver-sim.commands.compare
  "Canonical comparison of two artifact files (EDN or JSON).

   Two files are canonically equivalent when their parsed values produce
   byte-identical canonical encodings (CANONICAL_HASH_SPEC_V1).

   Usage: java -jar prf.jar compare <path-a> <path-b> [--format edn|json]"
  (:require [clojure.data.json :as json]
            [resolver-sim.compare.canonical :as canonical]))

(defn- print-report
  [result]
  (println "Canonical comparison")
  (println (format "  left:   %s  %s" (:left-file result) (:left-hash result)))
  (println (format "  right:  %s  %s" (:right-file result) (:right-hash result)))
  (println (format "  equivalent: %s" (:equivalent? result)))
  (when-let [d (:divergence result)]
    (println (format "  divergence: path=%s kind=%s"
                     (pr-str (:path d))
                     (name (:kind d))))
    (println (format "    left:  %s" (pr-str (:left d))))
    (println (format "    right: %s" (pr-str (:right d))))))

(defn run
  "compare <path-a> <path-b> [--format edn|json]"
  [{:keys [format json? cmd/args]}]
  (let [[path-a path-b] args]
    (cond
      (or (nil? path-a) (nil? path-b))
      (do (println "Usage: prf.jar compare <path-a> <path-b> [--format edn|json]")
          {:exit-code 2 :message "Two artifact paths required"})

      :else
      (try
        (let [result (canonical/compare-files path-a path-b {:format format})]
          (if json?
            (println (json/write-str result :indent true))
            (print-report result))
          {:exit-code (if (:equivalent? result) 0 1)
           :message (if (:equivalent? result)
                      "canonically equivalent"
                      "not canonically equivalent")})
        (catch Exception e
          (println "compare:" (.getMessage e))
          {:exit-code 1 :message (.getMessage e)})))))
