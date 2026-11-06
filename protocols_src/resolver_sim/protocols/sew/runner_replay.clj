(ns resolver-sim.protocols.sew.runner-replay
  "Optional Sew replay orchestration owned by the simulation runner."
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.db.temporal :as temporal]))

(defn replay-with-sew-protocol
  "Replay one scenario with the Sew protocol and optional temporal recorder."
  ([scenario] (replay-with-sew-protocol scenario {}))
  ([scenario replay-opts]
   (let [scenario*
         (if-let [te (:temporal-evidence scenario)]
           (if (and (:enabled? te) (not (:recorder te)))
             (assoc scenario :temporal-evidence
                    (assoc te :recorder temporal/record-from-replay!
                           :protocol sew/protocol))
             scenario)
           scenario)]
     (replay/replay-with-protocol sew/protocol scenario* replay-opts))))
