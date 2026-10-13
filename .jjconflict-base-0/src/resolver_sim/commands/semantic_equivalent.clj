(ns resolver-sim.commands.semantic-equivalent
  "Compare two completed run packages at the result level.

   Two runs are semantically equivalent when both realized a non-empty
   result set with identical stable-result hashes (community stable
   projection) and, where both declare a verdict-policy semantic outcome,
   that outcome matches.

   Usage: java -jar prf.jar semantic-equivalent --package-a DIR --package-b DIR"
  (:require [clojure.data.json :as json]
            [resolver-sim.compare.packages :as packages]))

(defn- print-report
  [report]
  (println "Semantic equivalence")
  (println (format "  left  stable hash:  %s" (:left-stable-hash report)))
  (println (format "  right stable hash:  %s" (:right-stable-hash report)))
  (println (format "  left  scenarios:    %d" (:left-scenario-count report)))
  (println (format "  right scenarios:    %d" (:right-scenario-count report)))
  (println (format "  left  outcome:      %s" (pr-str (:left-semantic-outcome report))))
  (println (format "  right outcome:      %s" (pr-str (:right-semantic-outcome report))))
  (println (format "  stable equivalent:  %s" (:stable-equivalent? report)))
  (println (format "  outcome equivalent: %s" (:outcome-equivalent? report)))
  (println (format "  equivalent:         %s" (:equivalent? report))))

(defn run
  "semantic-equivalent --package-a DIR --package-b DIR"
  [{:keys [package-a package-b json?]}]
  (if-not (and package-a package-b)
    (do (println "Usage: prf.jar semantic-equivalent --package-a DIR --package-b DIR")
        {:exit-code 2 :message "Two package directories required"})
    (let [report (packages/semantic-equivalent package-a package-b)]
      (if json?
        (println (json/write-str report :indent true))
        (print-report report))
      {:exit-code (if (:equivalent? report) 0 1)
       :message (if (:equivalent? report)
                  "semantically equivalent"
                  "not semantically equivalent")})))
