(ns resolver-sim.db.telemetry-seed
  (:require [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.runner :as runner]
            [resolver-sim.db.telemetry :as tel]))

(defn parse-long-safe [s default]
  (try (Long/parseLong (str s)) (catch Exception _ default)))

(defn -main [& args]
  (let [n        (parse-long-safe (or (first args) 10) 10)
        ds       (xtdb/->datasource)
        batch-id (str "telemetry-seed-" (System/currentTimeMillis))
        rng-fn   (fn [] 0.9)
        trial-ids
        (mapv (fn [i]
                (let [trial-id (str batch-id "-" i)
                      params   {:block-time (+ 1000 (* i 3600))
                                :escrow-size 100000
                                :strategy :honest
                                :resolver-fee-bps 50
                                :appeal-bond-bps 200
                                :appeal-probability-if-correct 0.0
                                :appeal-probability-if-wrong 0.0
                                :slashing-detection-probability 0.0}
                      result   (runner/run-trial rng-fn params)]
                  (tel/record-trial! ds sew/protocol batch-id trial-id params result)
                  trial-id))
              (range n))]
    (println "telemetry persisted")
    (println (str "batch-id=" batch-id))
    (doseq [tid trial-ids]
      (println (str "trial-id=" tid)))))
