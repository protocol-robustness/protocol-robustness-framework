(ns resolver-sim.allocation.selection
  "Deterministic outcome selection under :domain-hash-rejection-v1.

   Algorithm:
     1. n = number of canonically ordered outcomes; require n > 0.
     2. For counter values beginning at zero, derive:
          candidate-bytes = domain-hash(:selected-outcome,
            {:authoritative-randomness <exact 32 bytes as byte-ints>
             :counter <non-negative integer>
             :outcome-count n})
     3. Interpret the 32-byte digest as an unsigned big-endian integer.
     4. M = 2^256; limit = M - (M mod n).
     5. If candidate < limit, selected-index = candidate mod n.
     6. Otherwise increment counter and derive another candidate.
     7. Return the outcome at selected-index in canonical outcome order.

   The receipt records the accepted counter, candidate digest, selected index,
   and selected outcome ID. Rejection sampling avoids modulo bias."
  (:require [clojure.string :as str]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private two-to-256
  (.shiftLeft (java.math.BigInteger/valueOf 1) 256))

(defn candidate-digest-hex
  "Domain-separated candidate digest for the given counter."
  [authoritative-randomness-bytes counter outcome-count]
  (hc/domain-hash :selected-outcome
                  {:authoritative-randomness authoritative-randomness-bytes
                   :counter (bigint counter)
                   :outcome-count (bigint outcome-count)}))

(defn digest->big-int
  "Interpret a 32-byte hex digest as an unsigned big-endian integer."
  [hex-str]
  (let [hex (if (str/starts-with? hex-str "0x") (subs hex-str 2) hex-str)
        ba (byte-array (map #(Integer/parseInt % 16)
                            (map #(apply str %) (partition 2 hex))))]
    (java.math.BigInteger. 1 ba)))

(defn rejection-limit
  "M - (M mod n) where M = 2^256."
  [n]
  (.subtract two-to-256 (.mod two-to-256 (java.math.BigInteger/valueOf (long n)))))

(defn select-index
  "Run rejection sampling and return {:accepted-counter, :candidate-digest,
   :candidate-value, :selected-index}."
  [authoritative-randomness-bytes outcome-count]
  (when-not (pos? outcome-count)
    (context/rejection! :empty-outcome-set "Outcome count must be positive for selection"))
  (loop [counter 0]
    (let [digest (candidate-digest-hex authoritative-randomness-bytes counter outcome-count)
          value (digest->big-int digest)
          limit (rejection-limit outcome-count)]
      (if (neg? (.compareTo value limit))
          ;; candidate < limit
        (let [selected-index (mod (bigint value) outcome-count)]
          {:accepted-counter counter
           :candidate-digest digest
           :candidate-value value
           :selected-index (bigint selected-index)})
        (recur (inc counter))))))
