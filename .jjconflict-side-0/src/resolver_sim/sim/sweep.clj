(ns resolver-sim.sim.sweep
  "Parameter sweep runner for sensitivity analysis."
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]))

(defn cartesian-product
  "Generate Cartesian product of sequences."
  [& seqs]
  (if (empty? seqs)
    '(())
    (for [x (first seqs)
          xs (apply cartesian-product (rest seqs))]
      (cons x xs))))

(defn run-parameter-sweep
  "Run multiple scenarios with different parameters and aggregate results.
   
   sweep-params should be a map of parameter keys to lists of values.
   Supports 1D (single key) and 2D+ (multiple keys) sweeps.
   
   Returns a list of batch results for each combination."
  [params base-rng-seed sweep-params]

  (let [; Get all sweep dimensions
        sweep-keys (keys sweep-params)
        sweep-values (vals sweep-params)

        ; Generate all combinations (1D, 2D, or higher)
        combos
        (if (= 1 (count sweep-keys))
          ; 1D sweep: simple mapping
          (for [v (first sweep-values)]
            [(first sweep-keys) v])
          ; 2D+ sweep: Cartesian product
          (let [keys-vec (vec sweep-keys)
                values-list (for [idx (range (count keys-vec))]
                              (nth sweep-values idx))
                ; Generate all combinations recursively
                all-combos (apply cartesian-product values-list)]
            (for [combo all-combos]
              (map vector keys-vec combo))))]

    (for [combo-pair combos]
      (let [; Handle both 1D (single pair) and multi-D (list of pairs)
            ; 1D: combo-pair = [:strategy :honest]
            ; 2D+: combo-pair = ([:slash-mult 1.5] [:detect-prob 0.05])
            combo (if (vector? (first combo-pair))
                    combo-pair      ; multi-D case: already list of pairs
                    [combo-pair])   ; 1D case: wrap single pair in list
            trial-params (reduce
                          (fn [p [k v]] (assoc p k v))
                          params
                          combo)
            rng (rng/make-rng base-rng-seed)
            result (batch/run-batch rng (:n-trials params) trial-params)
            ; Add all swept parameters to result for CSV output
            result-with-params (reduce
                                (fn [r [k v]] (assoc r k v))
                                result
                                combo)]
        result-with-params))))

(defn run-strategy-sweep
  "Convenient sweep across resolver strategies."
  [params base-rng-seed]
  (run-parameter-sweep params base-rng-seed
                       {:strategy [:honest :lazy :malicious :collusive]}))
