(ns resolver-sim.demo.summary
  "Print a researcher-facing summary of a scenario JSON fixture.
   Produces compact event/expectation tables suitable for asciicast recordings."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]))

(defn- load-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn- pad [s w]
  (let [s (str s)]
    (str s (apply str (repeat (max 0 (- w (count s))) " ")))))

(defn print-header
  ([] (print-header "Scenario summary"))
  ([title] (println (str "\n" title "\n" (apply str (repeat (count title) "═"))))))

(defn print-agents [agents]
  (when (seq agents)
    (println "\nAgents")
    (run! (fn [a] (println (str "  " (pad (:id a) 20) (:role a)))) agents)))

(defn print-events [events]
  (when (seq events)
    (println "\nEvents")
    (println (str "  " (pad "SEQ" 6) (pad "TIME" 10) (pad "AGENT" 22) "ACTION"))
    (run! (fn [e]
            (let [agent (or (:id (:agent e)) (:agent e) "?")]
              (println (str "  " (pad (:seq e) 6) (pad (str (:time e)) 10)
                            (pad agent 22) (:action e)))))
          events)))

(defn print-metrics [metrics]
  (when (seq metrics)
    (println "\nExpected metrics")
    (run! (fn [m] (println (str "  " (pad (:name m) 34) (:value m)))) metrics)))

(defn print-summary
  "Print a structured summary of a scenario JSON file to stdout."
  [path]
  (let [sc (load-json path)
        sid (:scenario-id sc)
        agents (:agents sc [])
        events (:events sc [])
        metrics (get-in sc [:expectations :metrics] [])]
    (print-header)
    (println (str "  ID:       " (or sid "—")))
    (println (str "  Title:    " (or (:title sc) "—")))
    (println (str "  Protocol: " (or (:protocol sc) "—")))
    (println (str "  Purpose:  " (or (:purpose sc) "—")))
    (println (str "  Agents:   " (count agents) "  Events: " (count events)))
    (print-agents agents)
    (println)
    (print-events events)
    (println)
    (print-metrics metrics)
    (println)))

(defn -main [& args]
  (let [path (or (first args)
                 (throw (ex-info "Usage: clojure -M -m resolver-sim.demo.summary <scenario.json>" {})))]
    (if-not (.exists (io/file path))
      (do (println (str "File not found: " path))
          (System/exit 1))
      (do (print-summary path)
          (System/exit 0)))))