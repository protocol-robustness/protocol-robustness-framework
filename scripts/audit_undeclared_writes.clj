(ns scripts.audit-undeclared-writes
  "Review runtime undeclared-write findings across a soak's results files
   (review §4/§10).

   For each namespace, aggregates across every results-*.edn in <dir>:
     - scope-status (complete/incomplete)
     - undeclared files written outside the confined API
     - scope problems (hash mismatch, duplicate ids, temps, timeouts)
     - which modes exhibited the findings

   Flags any namespace that is incomplete or that persistently shows
   undeclared writes — the runtime audit confirming/refuting confinement.

   Usage:
     clojure -M:test:with-sew -m scripts.audit-undeclared-writes [dir]
   Exits 1 when any incomplete scope or persistent undeclared write is found."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- load-results
  [f]
  (try (edn/read-string (slurp f))
       (catch Exception _ nil)))

(defn- results-files
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".edn")
                     (str/starts-with? (.getName %) "results-")))
       (sort-by #(.getName %))
       vec))

(defn -main
  [& args]
  (let [dir (or (first args) "results/soak")
        files (results-files dir)]
    (if (empty? files)
      (do (println (str "no results-*.edn files in " dir)) (System/exit 0))
      (let [runs (keep load-results files)
            entries (for [run runs
                          r (:results run)]
                      (assoc r :mode (:mode run) :file (:run-id run)))
            grouped (group-by :namespace entries)
            findings (for [[ns entries] grouped]
                       (let [incomplete (some #(= :incomplete (:scope-status %)) entries)
                             undeclared (into (sorted-set)
                                              (mapcat :undeclared-files entries))
                             problems (into (sorted-set)
                                            (mapcat (comp (partial map :type) :scope-problems) entries))
                             modes (vec (sort (distinct (map :mode entries))))]
                         {:namespace ns
                          :runs (count entries)
                          :modes modes
                          :incomplete? (boolean incomplete)
                          :undeclared undeclared
                          :problems (vec problems)}))
            flags (filter #(or (:incomplete? %)
                               (and (seq (:undeclared %))
                                    (not (= (set ["_owner.edn"]) (:undeclared %)))))
                          findings)]
        (println (str "reviewing " (count files) " result files in " dir))
        (println (str "namespaces observed: " (count findings)))
        (doseq [f findings]
          (println (format "  %-55s runs=%d modes=%s scope=%s undeclared=%s problems=%s"
                           (str (:namespace f))
                           (:runs f)
                           (str/join "," (:modes f))
                           (if (:incomplete? f) "INCOMPLETE" "complete")
                           (pr-str (:undeclared f))
                           (pr-str (:problems f)))))
        (println)
        (if (seq flags)
          (do
            (println "FLAGGED (needs triage):")
            (doseq [f flags]
              (println (str "  " (:namespace f)
                            (when (:incomplete? f) " [INCOMPLETE]")
                            (when (seq (:undeclared f)) (str " undeclared=" (pr-str (:undeclared f)))))))
            (System/exit 1))
          (println "no incomplete scopes or undeclared writes across the soak."))))))
