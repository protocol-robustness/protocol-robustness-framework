(ns intent-hash-test
  (:require [resolver-sim.hash.canonical :as hc]
            [clojure.string :as str]))

(defn -main []
  (let [intent {:intent/type :pro-rata/allocation
                :intent/version 1
                :intent/purpose :slash-obligation-allocation
                :intent/scope {:protocol :sew :domain :economic-allocation}
                :intent/inputs #{:obligations :weights}
                :intent/constraints #{:conservation :ordering-independent}
                :intent/output {:type :allocation-vector}
                :intent/extensions {:sew/epoch 42}}
        projected (hc/project-intent-dsl intent intent)
        h (hc/domain-hash :intent-dsl projected)
        bytes (hc/canonical-bytes projected)
        hex (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) bytes))]
    (println "hash:" h)
    (println "bytes-hex:" hex)
    (println "len:" (alength bytes))))

(-main)
