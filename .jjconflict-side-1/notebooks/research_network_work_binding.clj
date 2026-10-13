;; # Research Network: Work Binding, Stealing & Comparability
;;
;; This notebook is an executable tour of the existing research-only APIs.
;; It demonstrates rooted coordinate work, local pull/steal scheduling,
;; pseudonymous attribution, and the boundary between disagreement and
;; not-comparable results. It does not activate a challenge or create rewards.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.research-network-work-binding
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.research.analysis-compiler :as compiler]
            [resolver-sim.research.execution-manifest :as execution-manifest]
            [resolver-sim.research.observation-work :as observation-work]
            [resolver-sim.research.parallel-executor :as parallel-executor]
            [resolver-sim.research.result-store :as result-store]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.researcher-challenges :as challenges]
            [resolver-sim.research.scheduler-harness :as scheduler]))

;; ## A frozen, tiny research computation
;;
;; Two cases run the same deterministic scheduler workload at different GPU
;; scales. One rooted measure observes makespan. The scheduler harness models
;; discrete-event work; its times are simulated milliseconds, not wall-clock
;; speedup claims.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def source
  {:artifact/schema research/source-schema
   :research/id :research-network/work-binding-tour
   :research/title "Scheduler behavior under workload scale"
   :research/question "Does a rooted analysis remain stable when observation work is reassigned?"
   :research/vary {:parameter/load [1 2]}
   :research/measures
   [{:measure/id :measure/makespan
     :measure/kind research/requirement-margin-kind
     :measure/domain {:kind :integer :unit :unit/ms}
     :measure/observation {:observation/id :scheduler/makespan-ms}
     :measure/requirement {:value 0 :unit :unit/ms}
     :measure/satisfying-direction :at-least}]})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def frozen (research/freeze-research source))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def workloads [(scheduler/workload {:id :work-binding/load-1})
                (scheduler/workload {:id :work-binding/load-2 :gpu-scale 2})])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def policy-root (scheduler/policy-subject-root :work-stealing false))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def repeat-fixture {:execution/repeat-id 0
                     :execution/fixture-id :fixture/research-network-tour})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def manifest
  (execution-manifest/build-manifest
   frozen
   {:policy-subject-roots [policy-root]
    :case-workloads
    (into {}
          (map (fn [research-case workload]
                 [(:case/id research-case)
                  {:workload/root (:workload/root workload)
                   :expected/root (scheduler/canonical-root workload)}])
               (get-in frozen [:research-definition :research/cases])
               workloads))
    :repeats [repeat-fixture]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def execution-scope
  {:policy-by-subject-root {policy-root :work-stealing}
   :workload-by-root (into {} (map (juxt :workload/root identity) workloads))})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def execution-report
  (parallel-executor/execute-manifest execution-scope manifest :thread-pool
                                      {:max-concurrency 2}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def result-store
  (let [store (result-store/new-store)]
    (doseq [{:keys [result]} (:execution/entries execution-report)]
      (result-store/put-result! store result))
    store))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def plan
  (observation-work/build-plan frozen manifest policy-root repeat-fixture))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn pseudonymous-runner [n]
  (participation/build-participation
   {:runner-reference (str "sha256:" (format "%064x" n))
    :disclosure-mode :pseudonymous
    :site-id (keyword "notebook-site" (str n))
    :opted-in? true
    :available? true
    :capabilities [:capability/research-observation]
    :job-classes [:job/research-observation]
    :environment-kinds [:execution-environment/local]
    :qualification-references []}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def runner-a (pseudonymous-runner 1))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def runner-b (pseudonymous-runner 2))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn pull-all [runners]
  (loop [assignments [] ingestion {} n 0]
    (let [runner (nth runners (mod n (count runners)))
          assignment (observation-work/steal-next frozen manifest plan runner assignments)]
      (if assignment
        (recur (conj assignments assignment)
               (observation-work/ingest
                ingestion
                (observation-work/observe frozen manifest plan assignment runner
                                          result-store execution-report))
               (inc n))
        {:assignments assignments :ingestion ingestion}))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def schedule-ab (pull-all [runner-a runner-b]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def schedule-ba (pull-all [runner-b runner-a]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def reconciliation-ab
  (observation-work/reconcile frozen manifest plan (:assignments schedule-ab)
                              [runner-a runner-b] (:ingestion schedule-ab)
                              result-store execution-report))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def reconciliation-ba
  (observation-work/reconcile frozen manifest plan (:assignments schedule-ba)
                              [runner-a runner-b] (:ingestion schedule-ba)
                              result-store execution-report))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def serial-analysis
  (compiler/compile-policy-analysis frozen manifest result-store policy-root
                                    repeat-fixture execution-report))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Commitment" "Root / identity"]
  :rows [["research definition" (:research-definition/root frozen)]
         ["execution manifest" (:research-execution-manifest/root manifest)]
         ["work plan" (:research-observation-work-plan/root plan)]
         ["policy subject" policy-root]]})

;; ## Work binding: semantic unit versus forensic assignment
;;
;; The plan freezes the finite case × measure obligations. Each assignment
;; binds one exact unit and plan to an opted-in runner. Here the runners pull
;; the next unclaimed unit in deterministic coordinate order. Reversing runner
;; arrival changes assignment/forensic roots, but not the semantic analysis.
;; This selector is local; it does not claim cross-host dynamic-stealing
;; qualification.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Coordinate" "Work-unit root" "Assigned pseudonymous runner" "Assignment root" "Observation root" "Value"]
  :rows (mapv (fn [assignment]
                (let [observation (get (:ingestion schedule-ab)
                                       (:research-observation-work-unit/root assignment))]
                  [(:analysis/coordinate observation)
                   (:research-observation-work-unit/root assignment)
                   (:runner/reference assignment)
                   (:research-observation-assignment/root assignment)
                   (:research-coordinate-observation/root observation)
                   (:observation/value observation)]))
              (:assignments schedule-ab))})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Check" "Outcome"]
  :rows [["All declared work units assigned exactly once"
          (= (count (:observation/work-units plan))
             (count (:assignments schedule-ab)))]
         ["Stealing/pull reconciliation equals serial compilation"
          (= serial-analysis (:analysis reconciliation-ab))]
         ["Runner arrival order preserves semantic analysis"
          (= (:research-analysis/root (:analysis reconciliation-ab))
             (:research-analysis/root (:analysis reconciliation-ba)))]
         ["Different runner assignment retains different forensic reconciliation"
          (not= (get-in reconciliation-ab [:forensic-reconciliation
                                           :research-observation-reconciliation/root])
                (get-in reconciliation-ba [:forensic-reconciliation
                                           :research-observation-reconciliation/root]))]]})

;; A forged assignment to a different work unit cannot pass the existing
;; rooted assignment/plan checks. This is a binding failure, not a failed
;; semantic computation.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def wrong-unit-binding-rejected?
  (let [assignment (first (:assignments schedule-ab))
        wrong (assoc assignment :research-observation-work-unit/root
                     (:research-observation-work-unit/root
                      (last (:observation/work-units plan))))]
    (try
      (observation-work/observe frozen manifest plan wrong runner-a result-store
                                execution-report)
      false
      (catch clojure.lang.ExceptionInfo _ true))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Adversarial binding check" "Result"]
  :rows [["Assignment body changed to name another unit without a matching root"
          (if wrong-unit-binding-rejected? "rejected" "unexpectedly accepted")]]})

;; ## Comparable, disagreement, and not-comparable
;;
;; A result comparison is valid only within the same frozen definition, case
;; axis, and measure axis. Different values inside that scope are a
;; disagreement—not a vote and not automatic claim falsification. A changed
;; case domain is not contradictory evidence; it is a different experiment.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def observations
  (into {}
        (map (fn [observation]
               [(:case/id observation)
                {(:measure/id (first (get-in frozen [:research-definition
                                                    :research/measures])))
                 (:observation/value observation)}])
             (vals (:ingestion schedule-ab)))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def implementation-roots
  {:observed "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :same "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :different "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
   :changed-domain "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def matrix-a (research/compile-result-matrix frozen observations
                                               (:observed implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def matrix-same (research/compile-result-matrix frozen observations
                                                  (:same implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def first-case-id (-> frozen :research-definition :research/cases first :case/id))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def changed-observations
  (update-in observations [first-case-id :measure/makespan] inc))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def matrix-different
  (research/compile-result-matrix frozen changed-observations
                                  (:different implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def expanded-source
  (assoc-in source [:research/vary :parameter/load] [1 2 3]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def expanded-frozen (research/freeze-research expanded-source))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def expanded-observations
  (into {}
        (map-indexed (fn [i research-case]
                       [(:case/id research-case)
                        {:measure/makespan (+ 100N (* 10N i))}])
                     (get-in expanded-frozen [:research-definition :research/cases]))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def matrix-expanded
  (research/compile-result-matrix expanded-frozen expanded-observations
                                  (:changed-domain implementation-roots)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def comparison-results
  {:same-result (research/compare-reproductions [matrix-a matrix-same])
   :different-result (research/compare-reproductions [matrix-a matrix-different])
   :changed-domain (research/compare-reproductions [matrix-a matrix-expanded])})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Comparison" "Frozen scope" "Comparator status" "Interpretation"]
  :rows [["Same observations, different implementation reference" "same"
          (get-in comparison-results [:same-result :reproduction/status])
          "Equal outputs; not proof of human or organisational independence"]
         ["One changed cell" "same"
          (get-in comparison-results [:different-result :reproduction/status])
          "Comparable disagreement; preserve the differing cell"]
         ["Added case to research domain" "different"
          (get-in comparison-results [:changed-domain :reproduction/status])
          "Not comparable; no disagreement is inferred"]]})

;; ## Pseudonymous participants and experience
;;
;; Runner references are scoped pseudonyms. Distinct references are useful for
;; attribution but do not establish distinct people, organisations, or
;; machines. The existing reward-policy helper derives experience from unique
;; accepted, verified, incentive-eligible history; this table is illustrative
;; only and creates no quote, obligation, payout, or live challenge.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def illustrative-experience
  (mapv (fn [n]
          (let [{:keys [minimum numerator denominator]} (challenges/experience-tier n)]
            [n minimum (str numerator "/" denominator)]))
        [0 2 3 7 8]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Accepted eligible contributions (illustrative)" "Tier starts at" "Multiplier"]
  :rows illustrative-experience})

;; In the bounded pilot proposal, the 3-contribution transition is in scope;
;; the 8-contribution tier is not. No test fixture keys or identities are
;; publication authorities.

;; ## What this demonstrates—and what it does not
;;
;; * Work units and exact coverage are rooted before assignment. Runner order
;;   and assignment alter forensic lineage, not the analysis result root.
;; * The current notebook uses a local deterministic pull/steal selector and
;;   simulated scheduler workloads. It does not qualify multi-host dynamic
;;   stealing or demonstrate a throughput win.
;; * Same-scope differences are comparable disagreements. Changed definitions,
;;   case axes, or measure axes must remain `:not-comparable`.
;; * Reproduction, evidence attribution, reviewer acceptance, eligibility, and
;;   reward obligation creation are separate. Nothing here accepts a claim or
;;   creates a reward obligation.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def assertions
  {:all-work-bound (= (count (:observation/work-units plan))
                      (count (:assignments schedule-ab)))
   :semantic-root-independent-of-runner-order
   (= (:research-analysis/root (:analysis reconciliation-ab))
      (:research-analysis/root (:analysis reconciliation-ba)))
   :forensic-root-retains-assignment-difference
   (not= (get-in reconciliation-ab [:forensic-reconciliation
                                    :research-observation-reconciliation/root])
         (get-in reconciliation-ba [:forensic-reconciliation
                                    :research-observation-reconciliation/root]))
   :wrong-binding-rejected wrong-unit-binding-rejected?
   :same-scope-equivalent (= :equal (get-in comparison-results
                                             [:same-result :reproduction/status]))
   :same-scope-disagreement (= :disagreement (get-in comparison-results
                                                       [:different-result :reproduction/status]))
   :changed-scope-not-comparable (= :not-comparable
                                   (get-in comparison-results
                                           [:changed-domain :reproduction/status]))
   :runner-references-pseudonymous
   (and (= :pseudonymous (:runner/disclosure-mode runner-a))
        (not= (:runner/reference runner-a) (:runner/reference runner-b)))})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(do
  (assert (every? true? (vals assertions)) assertions)
  (clerk/table
   {:head ["Executable invariant" "Pass"]
    :rows (mapv (fn [[label passed?]] [(name label) passed?])
                (sort-by key assertions))}))
