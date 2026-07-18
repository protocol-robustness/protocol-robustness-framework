(require '[clojure.string :as str])

(let [f "protocols_src/test/resolver_sim/protocols/sew/replay_test.clj"
      s (slurp f)]
  (loop [open [], [line & rest] (map vector (range) (str/split-lines s))]
    (if line
      (let [idx (first line) text (second line)]
        (loop [open open, [c & cs] (seq text), col 0]
          (if c
            (case c
              \( (recur (conj open [:paren idx col]) cs (inc col))
              \) (if (and (seq open) (= :paren (first (peek open))))
                  (recur (pop open) cs (inc col))
                  (println (str "Unmatched ) at line " (inc idx) " col " (inc col))))
              \[ (recur (conj open [:bracket idx col]) cs (inc col))
              \] (if (and (seq open) (= :bracket (first (peek open))))
                  (recur (pop open) cs (inc col))
                  (println (str "Unmatched ] at line " (inc idx) " col " (inc col))))
              \{ (recur (conj open [:brace idx col]) cs (inc col))
              \} (if (and (seq open) (= :brace (first (peek open))))
                  (recur (pop open) cs (inc col))
                  (println (str "Unmatched } at line " (inc idx) " col " (inc col))))
              (recur open cs (inc col)))
            (recur open rest))))
      (if (seq open)
        (doseq [[type line col] open]
          (println (str "Unclosed " (name type) " at line " (inc line) " col " (inc col))))
        (println "All balanced!")))))
