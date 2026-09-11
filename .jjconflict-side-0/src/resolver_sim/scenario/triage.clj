(ns resolver-sim.scenario.triage
  "Failure triage helpers grouped by scenario purpose and threat tags.

   Usage:
     clojure -M -m resolver-sim.scenario.triage
     clojure -M -m resolver-sim.scenario.triage data/fixtures/traces"
  (:require [clojure.string :as str]
            [resolver-sim.scenario.coverage :as cov]
            [resolver-sim.config.paths :as paths]))

(defn- id->str [x]
  (if (keyword? x) (name x) (str x)))

(defn- guess-outcome-from-id [id]
  (let [s (id->str id)]
    (cond
      (str/includes? s "-fail") :fail
      (str/includes? s "-inconclusive") :inconclusive
      (str/includes? s "-not-applicable") :not-applicable
      :else :pass)))

(defn triage-report
  "Build triage groupings from scenario metadata.

    Since this operates on fixture metadata only, we classify likely non-pass
    scenarios by ID naming conventions (e.g. *-fail, *-inconclusive).
    Returns maps grouped by :purpose and :threat-tags for quick diagnosis."
  [dir]
  (let [scenarios (cov/scan-traces dir)
        failing   (filter (fn [s] (not= :pass (guess-outcome-from-id (:id s)))) scenarios)
        by-purpose (->> failing
                        (group-by #(or (:purpose %) :unclassified))
                        (reduce-kv (fn [m k vs] (assoc m k (mapv :id vs))) {}))
        by-threat  (->> failing
                        (mapcat (fn [s]
                                  (if (seq (:threat-tags s))
                                    (for [t (:threat-tags s)] [t (:id s)])
                                    [[:untagged (:id s)]])))
                        (reduce (fn [m [tag id]] (update m tag (fnil conj []) id)) {})
                        (reduce-kv (fn [m k vs] (assoc m k (vec (distinct vs)))) {}))
        failed-scenarios (filter #(= :fail (guess-outcome-from-id (:id %))) scenarios)
        inconclusive-scenarios (filter #(= :inconclusive (guess-outcome-from-id (:id %))) scenarios)
        not-applicable-scenarios (filter #(= :not-applicable (guess-outcome-from-id (:id %))) scenarios)]
    {:scanned-dir dir
     :failing-count (count failing)
     :by-purpose by-purpose
     :by-threat-tag by-threat
     :failing-ids (mapv :id failing)
     :failed-scenarios (mapv :id failed-scenarios)
     :inconclusive-scenarios (mapv :id inconclusive-scenarios)
     :not-applicable-scenarios (mapv :id not-applicable-scenarios)}))

(defn- print-group [title m]
  (println title)
  (if (seq m)
    (doseq [[k ids] (sort-by (comp str key) m)]
      (println (str "  - " (name (if (keyword? k) k (keyword (str k)))) ": " (count ids)))
      (doseq [id ids]
        (println (str "      · " (id->str id)))))
    (println "  (none)")))

(defn -main [& [dir]]
  (let [d (or dir (paths/traces-dir))
        r (triage-report d)]
    (println "\nFailure triage report")
    (println "====================")
    (println (str "Scanned: " (:scanned-dir r)))
    (println (str "Likely non-pass scenarios: " (:failing-count r)))
    (println (str "Failed scenarios: " (count (:failed-scenarios r))))
    (println (str "Inconclusive scenarios: " (count (:inconclusive-scenarios r))))
    (println (str "Not applicable scenarios: " (count (:not-applicable-scenarios r))))
    (println)
    (print-group "By purpose:" (:by-purpose r))
    (println)
    (print-group "By threat-tag:" (:by-threat-tag r))))
