(ns resolver-sim.commands.compare-runs
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.run.comparison :as comparison]))

(defn run [{:keys [package-a package-b output]}]
  (if-not (and package-a package-b)
    {:exit-code 2 :message "Usage: compare-runs --package-a DIR --package-b DIR [--output DIR]"}
    (let [left (comparison/submission package-a)
          right (comparison/submission package-b)
          result (comparison/compare left right)
          pass? (= :pass (:result result))]
      (when output
        (let [dir (io/file output)]
          (.mkdirs (io/file dir "submissions"))
          (spit (io/file dir "submissions/left.json") (json/write-str (:submission left)))
          (spit (io/file dir "submissions/right.json") (json/write-str (:submission right)))
          (spit (io/file dir "run-comparison.json") (json/write-str result))))
      (println (name (:classification result)))
      {:exit-code (if pass? 0 1) :result result})))
