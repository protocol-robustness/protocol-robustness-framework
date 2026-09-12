(ns resolver-sim.io.diff
  "Structural diffing for simulation traces.
   Pinpoints the first divergence point between two replay results."
  (:require [clojure.data :as data]
            [clojure.pprint :as pp]))

(defn- world-at-step [trace seq-n]
  (:world (first (filter #(= (:seq %) seq-n) trace))))

(defn diff-traces
  "Compare two simulation trace logs (vectors of steps).
   Returns the first seq number where a divergence is found, plus the diff."
  [trace-a trace-b]
  (let [len-a (count trace-a)
        len-b (count trace-b)
        min-len (min len-a len-b)]
    (loop [i 0]
      (if (>= i min-len)
        (if (= len-a len-b)
          nil
          {:divergence-at i
           :reason :trace-length-mismatch
           :length-a len-a
           :length-b len-b})
        (let [step-a (nth trace-a i)
              step-b (nth trace-b i)
              ;; Compare world states (excluding the trace field which would cause infinite recursion)
              w-a (dissoc (:world step-a) :trace)
              w-b (dissoc (:world step-b) :trace)
              [only-a only-b _same] (data/diff w-a w-b)]
          (if (or only-a only-b)
            {:divergence-at i
             :action (:action step-a)
             :only-in-baseline only-a
             :only-in-candidate only-b}
            (recur (inc i))))))))

(defn print-diff-report
  "Print a human-readable diff report to stdout."
  [diff]
  (if (nil? diff)
    (println "✅ No structural divergence found.")
    (do
      (println "❌ STRUCTURAL DIVERGENCE DETECTED")
      (println "---------------------------------------------------------------------------")
      (if (= :trace-length-mismatch (:reason diff))
        (println (format "Trace length mismatch: Baseline=%d, Candidate=%d"
                         (:length-a diff) (:length-b diff)))
        (do
          (println (format "First divergence at seq=%d (Action: %s)"
                           (:divergence-at diff) (:action diff)))
          (println "\nOnly in Baseline:")
          (pp/pprint (:only-in-baseline diff))
          (println "\nOnly in Candidate:")
          (pp/pprint (:only-in-candidate diff))))
      (println "---------------------------------------------------------------------------"))))
