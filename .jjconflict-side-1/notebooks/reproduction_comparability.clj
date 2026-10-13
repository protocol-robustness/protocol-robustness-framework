;; # Reproduction Comparability
;;
;; ## Question: When can two research runs legitimately be compared?
;;
;; A result delta has scientific meaning only after the two runs commit to the
;; same frozen research scope. PRF binds that scope to the frozen definition,
;; rooted case axis, and rooted measure axis. This notebook uses the production
;; research-definition builder and reproduction comparator directly.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.reproduction-comparability
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.benchmark.research-definition :as research]))

;; ## One frozen research definition
;;
;; The source is authoring input. `freeze-research` resolves it into a closed,
;; content-addressed definition and two dense, rooted axes.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def reserve-measure
  {:measure/id :measure/reserve-margin
   :measure/kind :requirement-margin.v1
   :measure/domain {:kind :integer :unit :tokens}
   :measure/observation {:observation/id :reserve/available}
   :measure/requirement {:requirement/kind :constant :value 100N :unit :tokens}
   :measure/satisfying-direction :at-least})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def latency-measure
  {:measure/id :measure/latency-margin
   :measure/kind :requirement-margin.v1
   :measure/domain {:kind :integer :unit :blocks}
   :measure/observation {:observation/id :latency/blocks}
   :measure/requirement {:requirement/kind :constant :value 50N :unit :blocks}
   :measure/satisfying-direction :at-most})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def baseline-source
  {:artifact/schema "research-source.v1"
   :research/id :study/reserve-under-load
   :research/title "Reserve and latency under load"
   :research/question "Do reserves and latency remain within their declared bounds?"
   :research/vary {:parameter/load-bps [5000N 9000N]}
   :research/measures [reserve-measure latency-measure]})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn observations-for
  "Deterministic local fixture values keyed by the frozen definition's case IDs."
  [frozen]
  (into {}
        (map-indexed
         (fn [index {:case/keys [id]}]
           [id {:measure/reserve-margin (+ 110N (* 10N index))
                :measure/latency-margin (+ 40N (* 5N index))}]))
        (get-in frozen [:research-definition :research/cases])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def implementation-roots
  {:baseline "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :exact "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :altered "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
   :case-scope "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
   :measure-scope "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def frozen-baseline (research/freeze-research baseline-source))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def baseline-observations (observations-for frozen-baseline))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :prf/notebook-output :research-result-matrix}
(def ^{:prf/notebook-output :research-result-matrix} baseline
  (research/compile-result-matrix frozen-baseline baseline-observations
                                  (:baseline implementation-roots)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Frozen commitment" "Root"]
  :rows [["research definition" (:research-definition/root baseline)]
         ["case axis" (:case-axis/root baseline)]
         ["measure axis" (:measure-axis/root baseline)]]})

;; The matrix is exact: every frozen case × measure coordinate is compiled.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case key" "Measure key" "Margin" "Classification"]
  :rows (mapv (fn [[[case-key measure-key] value]]
                [case-key measure-key (:measure/value value) (:measure/status value)])
              (sort-by key (:matrix/values baseline)))})

;; ## Four reproduction outcomes
;;
;; Each classification below is the output of `compare-reproductions`, not a
;; notebook rule. The comparator examines the full scope commitments first; it
;; does not use an intersection of result cells to decide comparability.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :prf/notebook-output :research-result-matrix}
(def ^{:prf/notebook-output :research-result-matrix} exact-reproduction
  (research/compile-result-matrix frozen-baseline baseline-observations
                                  (:exact implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :prf/notebook-output :research-result-matrix}
(def ^{:prf/notebook-output :research-result-matrix} altered-reproduction
  (research/compile-result-matrix
   frozen-baseline
   (assoc-in baseline-observations [:generated/case-0 :measure/reserve-margin] 99N)
   (:altered implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def frozen-changed-cases
  (research/freeze-research
   (assoc-in baseline-source [:research/vary :parameter/load-bps] [5000N 9000N 9500N])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}
  :prf/notebook-output :research-result-matrix}
(def ^{:prf/notebook-output :research-result-matrix} changed-case-scope
  (research/compile-result-matrix frozen-changed-cases (observations-for frozen-changed-cases)
                                  (:case-scope implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def frozen-changed-measures
  (research/freeze-research (assoc baseline-source :research/measures [reserve-measure])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def changed-measure-scope
  (research/compile-result-matrix
   frozen-changed-measures
   (update-vals (observations-for frozen-changed-measures)
                #(select-keys % [:measure/reserve-margin]))
   (:measure-scope implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def comparisons
  {:identical (research/compare-reproductions [baseline exact-reproduction])
   :different (research/compare-reproductions [baseline altered-reproduction])
   :changed-cases (research/compare-reproductions [baseline changed-case-scope])
   :changed-measures (research/compare-reproductions [baseline changed-measure-scope])})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Run A" "Run B" "Scope" "Results" "Derived interpretation"]
  :rows [["baseline" "exact reproduction" "same" "same"
          (if (= :equal (get-in comparisons [:identical :reproduction/status]))
            "IDENTICAL" "unexpected")]
         ["baseline" "altered result" "same" "one cell differs"
          (if (= :disagreement (get-in comparisons [:different :reproduction/status]))
            "COMPARABLE — DISAGREEMENT" "unexpected")]
         ["baseline" "changed case set" "different" "not examined"
          (if (= :not-comparable (get-in comparisons [:changed-cases :reproduction/status]))
            "NOT COMPARABLE" "unexpected")]
         ["baseline" "changed measures" "different" "not examined"
          (if (= :not-comparable (get-in comparisons [:changed-measures :reproduction/status]))
            "NOT COMPARABLE" "unexpected")]]})

;; ### Why the scope changes are not disagreements
;;
;; We did not observe evidence against the original result. We performed a
;; different research scope. The same local coordinate `[0 0]` cannot override
;; the changed case-axis or measure-axis root that gives that coordinate meaning.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Comparison" "Definition root" "Case-axis root" "Measure-axis root" "Comparator status"]
  :rows [["baseline vs changed cases"
          (= (:research-definition/root baseline) (:research-definition/root changed-case-scope))
          (= (:case-axis/root baseline) (:case-axis/root changed-case-scope))
          (= (:measure-axis/root baseline) (:measure-axis/root changed-case-scope))
          (get-in comparisons [:changed-cases :reproduction/status])]
         ["baseline vs changed measures"
          (= (:research-definition/root baseline) (:research-definition/root changed-measure-scope))
          (= (:case-axis/root baseline) (:case-axis/root changed-measure-scope))
          (= (:measure-axis/root baseline) (:measure-axis/root changed-measure-scope))
          (get-in comparisons [:changed-measures :reproduction/status])]]})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Misleading shortcut" "What executable PRF semantics say"]
  :rows [[(str "The overlapping [0 0] margin is "
              (get-in baseline [:matrix/values [0 0] :measure/value])
              " in both the baseline and changed-case run.")
          "The changed case-axis root makes the runs NOT COMPARABLE; no disagreement list is emitted."]
         ["The shared reserve coordinate also has the same value after removing the latency measure."
          "The changed measure-axis root makes the runs NOT COMPARABLE; shared cells are not an equivalence proof."]]})

;; ## Fail closed: no partial matrix becomes a comparison result
;;
;; `compile-result-matrix` requires every frozen coordinate and rejects unknown
;; case/measure IDs. There is no production `:incomplete` reproduction status in
;; this API: incomplete evidence fails during compilation and cannot be promoted
;; to `:equal` or `:disagreement`.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def incomplete-rejected?
  (try
    (research/compile-result-matrix
     frozen-baseline
     (dissoc baseline-observations :generated/case-0)
     (:exact implementation-roots))
    false
    (catch clojure.lang.ExceptionInfo _ true)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def out-of-scope-rejected?
  (try
    (research/compile-result-matrix
     frozen-baseline
     (assoc baseline-observations :case/outside {:measure/reserve-margin 110N
                                                  :measure/latency-margin 40N})
     (:exact implementation-roots))
    false
    (catch clojure.lang.ExceptionInfo _ true)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Guard" "Derived result"]
  :rows [["Missing required case × measure evidence" (if incomplete-rejected? "rejected before comparison" "unexpected")]
         ["Out-of-scope case cannot enter the matrix" (if out-of-scope-rejected? "rejected before comparison" "unexpected")]]})

;; ## Executable assertions

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def assertions
  {:exact-scope-comparable (= :equal (get-in comparisons [:identical :reproduction/status]))
   :identical-results (= :equal (get-in comparisons [:identical :reproduction/status]))
   :same-scope-delta (= :disagreement (get-in comparisons [:different :reproduction/status]))
   :changed-case-not-disagreement (= :not-comparable (get-in comparisons [:changed-cases :reproduction/status]))
   :changed-measure-not-disagreement (= :not-comparable (get-in comparisons [:changed-measures :reproduction/status]))
   :out-of-scope-rejected out-of-scope-rejected?
   :incomplete-rejected incomplete-rejected?})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(do
  (assert (every? true? (vals assertions)) assertions)
  (clerk/table
   {:head ["Assertion" "Pass?"]
    :rows (mapv (fn [[label passed?]] [(name label) passed?]) (sort-by key assertions))}))

;; ## Takeaway
;;
;; **Same definition root + same case-axis root + same measure-axis root** is
;; the machine-checkable condition that permits a result comparison. Within that
;; scope, different cell values are a disagreement. If any scope root differs,
;; the correct conclusion is **NOT COMPARABLE**, not a failed reproduction.
