;; # Notebook Comparison
;;
;; **Question:** Can two executable notebooks be compared without comparing their
;; source text or rendered HTML?
;;
;; Yes. A notebook explicitly exposes a rooted PRF result, this loader resolves
;; that value, and the existing production comparator decides whether the two
;; results are equal, disagree, or are not comparable.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.notebook-comparison
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.benchmark.research-definition :as research]))

;; ## Load explicit notebook outputs
;;
;; The loader is deliberately small and fail-closed. It loads a namespace and
;; resolves a named var marked `:prf/notebook-output`. It never compares source,
;; rendered markup, or arbitrary namespace state.

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(defn notebook-load
  [notebook-ns output-var]
  (require notebook-ns)
  (let [v (ns-resolve notebook-ns output-var)]
    (when-not (and v (= :research-result-matrix
                        (:prf/notebook-output (meta v))))
      (throw (ex-info "Notebook output is not explicitly marked as a research result"
                      {:notebook notebook-ns :output-var output-var})))
    {:notebook/namespace notebook-ns
     :notebook/var output-var
     :notebook/output @v}))

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(defn compare-loaded
  [left right]
  (research/compare-reproductions
   [(:notebook/output left) (:notebook/output right)]))

;; This demonstration loads two outputs from the executable comparability
;; notebook. In production, `notebook-ns` and `output-var` can refer to two
;; different notebook namespaces; the comparison boundary is identical.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def baseline
  (notebook-load 'notebooks.reproduction-comparability 'baseline))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def exact
  (notebook-load 'notebooks.reproduction-comparability 'exact-reproduction))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def altered
  (notebook-load 'notebooks.reproduction-comparability 'altered-reproduction))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def changed-cases
  (notebook-load 'notebooks.reproduction-comparability 'changed-case-scope))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def comparison-cases
  [[baseline exact "same scope / same results"]
   [baseline altered "same scope / different result"]
   [baseline changed-cases "changed case scope"]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Notebook output A" "Notebook output B" "Roots compared" "Production result"]
  :rows (mapv (fn [[left right _]]
                [(:notebook/var left)
                 (:notebook/var right)
                 (= (select-keys (:notebook/output left)
                                 [:research-definition/root :case-axis/root :measure-axis/root])
                    (select-keys (:notebook/output right)
                                 [:research-definition/root :case-axis/root :measure-axis/root]))
                 (:reproduction/status (compare-loaded left right))])
              comparison-cases)})

;; ## Assertions

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def assertions
  {:same-scope-identical
   (= :equal (:reproduction/status (compare-loaded baseline exact)))
   :same-scope-disagreement
   (= :disagreement (:reproduction/status (compare-loaded baseline altered)))
   :changed-scope-not-comparable
   (= :not-comparable (:reproduction/status (compare-loaded baseline changed-cases)))})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(do
  (assert (every? true? (vals assertions)) assertions)
  (clerk/table
   {:head ["Assertion" "Pass?"]
    :rows (mapv (fn [[label passed?]] [(name label) passed?])
                (sort-by key assertions))}))

;; The loader composes notebook execution; it does not replace PRF semantics.
;; The production condition remains:
;;
;; same definition root + same case-axis root + same measure-axis root
;;   => compare cells
;; different scope roots
;;   => NOT COMPARABLE
