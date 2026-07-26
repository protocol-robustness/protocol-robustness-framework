(ns resolver-sim.stochastic.rng
  "RNG management and seeding strategy.
   
   Uses SplittableRandom for parallel determinism.
   Each seed splits into N independent streams, one per core.
   Results are deterministic: same seed = same results, regardless of parallelism."
  (:import [java.util SplittableRandom]))

(defn make-rng
  "Create a SplittableRandom from a seed (reproducible)."
  [^long seed]
  (SplittableRandom. seed))

(defn split-rng
  "Split an RNG into two independent streams.
   Both are deterministic; splitting with same RNG yields same pair."
  [^SplittableRandom rng]
  [(.split rng) (.split rng)])

(defn next-long
  "Generate next long from RNG."
  [^SplittableRandom rng]
  (.nextLong rng))

(defn next-double
  "Generate next double [0, 1) from RNG."
  [^SplittableRandom rng]
  (.nextDouble rng))

(defn roll-double
  "Sample uniform double in [0, 1) using the given RNG.

   Requires an explicit rng argument — no-arg fallback to (rand) was
   removed for determinism. All callers must pass a seeded SplittableRandom."
  (^double []
   (throw (ex-info "roll-double requires an explicit rng argument — call (roll-double rng) instead"
                   {:function `roll-double})))
  (^double [rng]
   (if rng
     (next-double rng)
     (throw (ex-info "roll-double rng argument is nil — pass a valid seeded SplittableRandom"
                     {:function `roll-double :rng rng})))))

(defn next-int
  "Generate next int [0, bound) from RNG."
  [^SplittableRandom rng ^long bound]
  (.nextInt rng bound))

(defn seed-from-index
  "Derive a seed from base-seed + index.
   Ensures each parallel trial gets unique but deterministic seed.
   
   Example:
   (seed-from-index 42 0) ; trial 0
   (seed-from-index 42 1) ; trial 1
   Both are deterministic and different."
  [^long base-seed ^long idx]
  (+ base-seed (* idx 1000000007))) ; Large prime ensures good spacing

(defn sample-index
  "Sample a random index in [0, n) from the RNG.
   Equivalent to next-int but named for intent clarity."
  [^SplittableRandom rng ^long n]
  (.nextInt rng (int n)))

(defn shuffle-with-rng
  "Return a seeded Fisher-Yates shuffle of the given collection using `rng`.

   Unlike Clojure's `shuffle` (which calls java.util.Collections/shuffle
   with a non-seeded PRNG), this function produces fully reproducible
   orderings for the same rng state.

   Returns a vector."
  [coll ^SplittableRandom rng]
  (let [arr (object-array coll)
        n   (alength arr)]
    (loop [i (dec n)]
      (when (pos? i)
        (let [j (int (.nextInt rng (inc i)))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp))
        (recur (dec i))))
    (vec arr)))
