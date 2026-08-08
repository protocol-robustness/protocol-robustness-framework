(ns resolver-sim.demos.reorder-chain.scenario
  "Demo scenario: three evidence records in a committed order.

   Clerk-free. The same three evidence items, in one order (baseline) and
   another (the intervention). The records are built and verified by the real
   chain verifier (resolver-sim.evidence.chain)."
  (:require [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]))

(def baseline-order [:deposit :dispute :resolve])
(def reordered-order [:deposit :resolve :dispute])

(def evidence-content
  "Content hash of each evidence item. The items never change — only their
   order does."
  {:deposit (hc/hash-with-intent {:hash/intent :evidence-content}
                                 {:event :deposit :amount 100})
   :dispute (hc/hash-with-intent {:hash/intent :evidence-content}
                                 {:event :dispute :id "0x1"})
   :resolve (hc/hash-with-intent {:hash/intent :evidence-content}
                                 {:event :resolve :outcome :release})})

(defn build-records
  "Chain records over the given evidence items, linked in that order. Each
   record commits to its content, its position (:evidence/chain-seq), and its
   predecessor via a self-hash."
  [order]
  (loop [order order prev nil acc [] seq-n 1]
    (if-let [item (first order)]
      (let [content (get evidence-content item)
            self (chain/chain-link-hash content seq-n prev)]
        (recur (next order) self
               (conj acc {:evidence/hash content
                          :evidence/chain-seq seq-n
                          :evidence/chain-prev-hash prev
                          :evidence/chain-self-hash self
                          :evidence/chain-hash-scheme chain/chain-hash-scheme
                          :scenario/id "demo"})
               (inc seq-n)))
      acc)))

(defn baseline-records
  "The committed chain in its verified order: deposit -> dispute -> resolve."
  []
  (build-records baseline-order))

(defn reorder-records
  "The same evidence items presented in a different order
   (deposit -> resolve -> dispute). Each record keeps its committed position
   binding exactly as it was verified — nothing is recomputed."
  [records]
  (let [content-by-seq {1 (:deposit evidence-content)
                        2 (:resolve evidence-content)
                        3 (:dispute evidence-content)}]
    (mapv #(assoc % :evidence/hash (get content-by-seq (:evidence/chain-seq %)))
          records)))
