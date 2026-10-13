(ns resolver-sim.io.phase-z-persist
  "Phase Z trace persistence shell — scores scenarios and writes to trace store."
  (:require [resolver-sim.sim.adversarial.phase-z-scenarios :as phase-z]
            [resolver-sim.io.trace-store :as store])
  (:gen-class))

(defn persist-top-n!
  "Run all Phase Z scenarios, score them, and persist the top n.
   Returns a vector of trace-ids for persisted traces."
  [n & [{:keys [store-dir] :as opts}]]
  (->> (phase-z/run-all)
       (take n)
       (mapv (fn [{:keys [scenario scored-result]}]
               (store/store-trace! scored-result scenario
                                   (merge {:force? true} opts))))))

(defn persist-top-percent!
  "Run all Phase Z scenarios, score them, and persist the top p fraction.
   p=0.01 keeps the top 1%.  Always persists at least 1 scenario."
  [p & [opts]]
  (let [scored (phase-z/run-all)
        n      (max 1 (int (Math/ceil (* p (count scored)))))]
    (persist-top-n! n opts)))

(defn -main
  "Run Phase Z scenarios and persist top 1% to the trace store.

   Usage:
     clojure -M:phase-z-persist
     clojure -M:phase-z-persist <store-dir>"
  [& args]
  (let [store-dir (or (first args) store/default-store-dir)
        ids       (persist-top-percent! 0.01 {:store-dir store-dir})]
    (println (str "Phase Z: persisted " (count ids) " trace(s) → " store-dir))
    (doseq [id ids]
      (when id (println (str "  " id))))
    (System/exit 0)))
