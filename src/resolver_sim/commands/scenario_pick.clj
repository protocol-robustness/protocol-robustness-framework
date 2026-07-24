(ns resolver-sim.commands.scenario-pick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [resolver-sim.commands.scenario-run :as scenario-run]
            [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.io.resource-path :as rp]))

(defn- read-catalog
  []
  (let [catalog-file "scenarios/catalog.edn"]
    (when-let [path (rp/resolve-path catalog-file)]
      (try (edn/read-string (slurp path))
           (catch Exception _ nil)))))

(defn- prompt-for-numbers
  [^long max]
  (println)
  (printf "Enter scenario numbers to run (e.g. 1,3,5-10, or 'q' to quit): ")
  (flush)
  (let [input (str/trim (read-line))]
    (when (and (seq input) (not= input "q") (not= input "quit"))
      (let [parts (str/split input #",\s*")]
        (set (mapcat (fn [part]
                       (if-let [[_ lo hi] (re-find #"^\s*(\d+)\s*-\s*(\d+)\s*$" part)]
                         (let [lo (Integer/parseInt lo)
                               hi (min (Integer/parseInt hi) max)]
                           (range lo (inc hi)))
                         (when-let [m (re-find #"^\s*(\d+)\s*$" part)]
                           (let [n (Integer/parseInt (second m))]
                             (when (<= 1 n max)
                               [n])))))
                     parts))))))

(defn pick-scenarios
  [{:keys [search protocol]}]
  (let [catalog (read-catalog)
        all (if catalog (:scenarios catalog) [])]
    (if (empty? all)
      (do (println "No scenario catalog found at scenarios/catalog.edn")
          {:exit-code 1 :message "No catalog"})
      (let [filtered (filter (fn [s]
                               (and (or (nil? protocol)
                                        (= protocol (:scenario/protocol s)))
                                    (or (nil? search)
                                        (str/includes?
                                         (str/lower-case (:scenario/id s))
                                         (str/lower-case search)))))
                             all)
            sorted (sort-by :scenario/id filtered)
            indexed (map-indexed (fn [i s] [(inc i) s]) sorted)
            total (count sorted)]
        (println)
        (printf "Available scenarios: %d\n" total)
        (println "--------------------")
        (doseq [[idx s] indexed]
          (printf "%4d. %s  [%s]\n" idx (:scenario/id s) (:scenario/protocol s)))
        (when-let [numbers (prompt-for-numbers total)]
          (let [selected (keep (fn [n] (some (fn [[i s]] (when (= i n) s)) indexed)) numbers)]
            (if (empty? selected)
              (do (println "No valid selections.") {:exit-code 1})
              (do (printf "\nRunning %d scenario(s)...\n\n" (count selected))
                  (doseq [entry selected]
                    (let [path (:scenario/path entry)
                          id (:scenario/id entry)]
                      (println (str "▶ " id " (" path ")"))
                      (let [parsed (scenario-run/parse-request [path "--run-root"
                                                                (str "results/runs/pick-" id)])]
                        (if-not (:ok? parsed)
                          (println "  ✗ Parse error:" (:errors parsed))
                          (let [context (scenario-run/build-run-context (:request parsed) {:project-root "."})
                                result (orchestration/run-scenario! context)]
                            (println (str "  " (name (:command/status result))
                                          "/" (name (:scenario/outcome result))
                                          " → " (:run/root result))))))))
                  {:exit-code 0 :message (str "Ran " (count selected) " scenario(s)")})))
          {:exit-code 0 :message "Cancelled"})))))

(defn -main [& args]
  (let [result (pick-scenarios {:search (first args)})]
    (System/exit (:exit-code result))))
