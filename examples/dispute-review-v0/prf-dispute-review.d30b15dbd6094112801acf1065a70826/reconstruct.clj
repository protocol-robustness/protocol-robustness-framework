(require '[clojure.edn :as edn])

(when-not (= 1 (count *command-line-args*))
  (throw (ex-info "usage: reconstruct.clj <frozen-current-world.edn>" {})))

(let [fixture-path (first *command-line-args*)
      x (edn/read-string (slurp fixture-path))
      expected (reduce + (map (:fixture/expected x) [:alice :bob :carol]))
      contradictory (reduce + (map (:fixture/contradictory-observation x) [:alice :bob :carol]))]
  (assert (= expected (get-in x [:fixture/input :allocated])))
  (assert (= 71 contradictory))
  (assert (not= expected contradictory))
  (println (pr-str {:allocated-total expected :contradictory-total contradictory
                    :authoritative-state-mutated? false})))
