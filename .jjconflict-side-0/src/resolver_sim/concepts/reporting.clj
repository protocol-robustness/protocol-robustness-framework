(ns resolver-sim.concepts.reporting
  "Concept-aware report enrichment.

   Phase 1 stub: defines the shape of concept-enriched report sections
   without modifying any existing report or runner code.

   When integrated, `enrich-report` will inject concept metadata into
   simulation/benchmark output so that stakeholder-facing summaries
   are available alongside protocol-level results.

   Related: resolver-sim.concepts.registry provides the concept data
   that this namespace formats into report sections."
  (:require [resolver-sim.concepts.registry :as registry]))

;; ── Report enrichment shape ──────────────────────────────────────────────────

(defn- not-claimed
  [concept]
  (cond-> ["This stakeholder-facing concept does not assert implementation, deployment, legal, economic-safety, or benchmark coverage."
           "Protocol support must be established independently through adapter capabilities, executable scenarios, benchmark claims, and deployment configuration."]
    (= :illustrative (:concept/maturity concept))
    (conj "This mapping is illustrative; it is not a production-support claim.")

    (and (map? (:concept/evidence concept))
         (every? empty? (vals (:concept/evidence concept))))
    (conj "No executable scenario, benchmark, or claim evidence is linked to this concept.")))

(defn enrich-report
  "Given a raw simulation or benchmark result map and a vector of
   loaded concept definitions, return the report with an added
   :concept/section key containing stakeholder-facing summaries.

   Called by the benchmark runner after scenario execution completes."
  [report concepts]
  {:concept/section
   {:concept/summaries
    (mapv (fn [c]
            (let [c (registry/normalize-concept c)]
              {:concept/id (:concept/id c)
               :concept/name (:concept/name c)
               :concept/summary (:concept/summary c)
               :concept/stakeholder-question (:concept/stakeholder-question c)
               :concept/maturity (:concept/maturity c)
               :concept/support-status (:concept/support-status c)
               :concept/assumptions (:concept/assumptions c)
               :concept/out-of-scope (:concept/out-of-scope c)
               :concept/known-gaps (:concept/known-gaps c)
               :concept/evidence (:concept/evidence c)
               :concept/not-claimed (not-claimed c)
               :concept/mappings (select-keys c [:concept/roles :concept/entities
                                                 :concept/actions :concept/outcomes])}))
          concepts)
    :risk-annotations
    (let [all-failure-modes (mapcat :concept/failure-modes concepts)]
      (when (seq all-failure-modes)
        (mapv (fn [fm]
                {:concept/failure-id (:failure/id fm)
                 :concept/failure-name (:failure/name fm)
                 :concept/failure-summary (:failure/summary fm)
                 :concept/stakeholder-impact (:stakeholder-impact fm)})
              all-failure-modes)))}})

(comment "Expected shape of a concept-enriched report section:

   {:concept/section
    {:concept/summaries
     [{:concept/id <qualified-kw>
       :concept/name <string>
       :concept/summary <string>
       :concept/stakeholder-question <string>
       :concept/assumptions [<string> ...]
       :concept/out-of-scope [<string> ...]}
      ...]
     :risk-annotations
     [{:concept/failure-id <keyword>
       :concept/failure-name <string>
       :concept/failure-summary <string>
       :concept/stakeholder-impact <string>}
      ...]}}")
