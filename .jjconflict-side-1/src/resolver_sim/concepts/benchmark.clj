(ns resolver-sim.concepts.benchmark
  "Shared benchmark concept resolution for runner, report, and validation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.concepts.registry :as concepts-registry]
            [resolver-sim.concepts.reporting :as concepts-reporting]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.logging :as log]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.use-cases.registry :as use-cases]))

(def ^:private embedded-benchmark-concept-paths
  "Reference benchmark concept files embedded in the JAR.
   Discovered via explicit list — no directory scan inside JAR."
  ["resource:benchmarks/concepts/evidence-integrity-v1.edn"
   "resource:benchmarks/concepts/protocol-value-conservation-v1.edn"
   "resource:benchmarks/concepts/protocol-robustness-v0.edn"
   "resource:benchmarks/concepts/shortfall-allocation-v0.edn"])

(defn benchmark-concept-files
  []
  (let [root (io/file (paths/benchmarks-concepts))]
    (if (.exists root)
      (->> (file-seq root)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))
           (mapv #(.getPath %)))
      embedded-benchmark-concept-paths)))

(defn- load-concept-file
  [path]
  (:concepts (rp/edn-read path)))

(defn load-benchmark-local-concepts
  ([] (load-benchmark-local-concepts (benchmark-concept-files)))
  ([paths]
   (try
     (->> paths
          (mapcat (fn [path]
                    (try
                      (load-concept-file path)
                      (catch Exception e
                        (log/warn! "benchmark/local-concepts-load-failed"
                                   {:path (str path)
                                    :error (.getMessage e)})
                        []))))
          vec)
     (catch Exception e
       (log/warn! "benchmark/local-concepts-load-failed"
                  {:error (.getMessage e)})
       []))))

(defn benchmark-local-concept-summary
  [concept]
  {:concept/id (:concept/id concept)
   :concept/name (or (:concept/name concept) (:concept/title concept))
   :concept/title (:concept/title concept)
   :concept/summary (:concept/summary concept)
   :concept/stakeholder-question (or (:concept/stakeholder-question concept)
                                     (:concept/stakeholder-language concept))
   :concept/stakeholder-language (:concept/stakeholder-language concept)
   :concept/assumptions (or (:concept/assumptions concept) [])
   :concept/out-of-scope (or (:concept/out-of-scope concept) [])
   :concept/why-it-matters (:concept/why-it-matters concept)
   :concept/maps-to (:concept/maps-to concept)
   :concept/failure-modes (:concept/failure-modes concept)})

(defn- benchmark-local-concept-section
  [concepts]
  (when (seq concepts)
    {:concept/summaries (mapv benchmark-local-concept-summary concepts)}))

(defn- merge-concept-sections
  [& sections]
  (let [sections (remove nil? sections)
        summaries (vec (mapcat :concept/summaries sections))
        risk-annotations (vec (mapcat :risk-annotations sections))]
    (when (or (seq summaries) (seq risk-annotations))
      (cond-> {:concept/summaries summaries}
        (seq risk-annotations) (assoc :risk-annotations risk-annotations)))))

(defn- concept->report-fields
  [source concept]
  (merge
   {:concept/id (:concept/id concept)
    :concept/title (or (:concept/title concept) (:concept/name concept))
    :concept/summary (:concept/summary concept)
    :concept/stakeholder-language (or (:concept/stakeholder-language concept)
                                      (:concept/stakeholder-question concept))
    :concept/why-it-matters (:concept/why-it-matters concept)
    :concept/maps-to (:concept/maps-to concept)}
   (when (= source :benchmark-local)
     {:concept/shadows-global? (:concept/shadows-global? concept)})))

(defn resolve-benchmark-concepts
  ([concept-ids] (resolve-benchmark-concepts concept-ids {}))
  ([concept-ids {:keys [local-concepts use-case-registry]}]
   (let [{:keys [concepts]} (concepts-registry/load-registry)
         local-concepts (or local-concepts (load-benchmark-local-concepts))
         use-case-by-id (if use-case-registry
                          (use-cases/use-case-index use-case-registry)
                          {})
         global-by-id (concepts-registry/concept-index concepts)
         local-by-id (concepts-registry/concept-index local-concepts)
         resolved-entries (mapv (fn [concept-id]
                                  (cond
                                    (contains? use-case-by-id concept-id)
                                    {:concept/id concept-id
                                     :concept/source :external-use-case
                                     :concept (get use-case-by-id concept-id)}

                                    (contains? local-by-id concept-id)
                                    {:concept/id concept-id
                                     :concept/source :benchmark-local
                                     :concept (get local-by-id concept-id)}

                                    (contains? global-by-id concept-id)
                                    {:concept/id concept-id
                                     :concept/source :global
                                     :concept (get global-by-id concept-id)}

                                    :else
                                    {:concept/id concept-id
                                     :concept/source :missing
                                     :concept nil}))
                                concept-ids)]
     {:global-concepts (->> resolved-entries
                            (filter #(= :global (:concept/source %)))
                            (mapv :concept))
      :local-concepts (->> resolved-entries
                           (filter #(= :benchmark-local (:concept/source %)))
                           (mapv :concept))
      :resolved-concepts (->> resolved-entries
                              (remove #(= :missing (:concept/source %)))
                              (mapv :concept))
      :report-concepts (->> resolved-entries
                            (remove #(= :missing (:concept/source %)))
                            (mapv (fn [{:keys [concept] :as entry}]
                                    (concept->report-fields (:concept/source entry) concept))))
      :resolved-entries resolved-entries
      :unknown-concept-ids (->> resolved-entries
                                (filter #(= :missing (:concept/source %)))
                                (mapv :concept/id))})))

(defn resolved-concept-section
  [{:keys [global-concepts local-concepts]}]
  (let [global-section (when (seq global-concepts)
                         (:concept/section
                          (concepts-reporting/enrich-report nil global-concepts)))
        local-section (benchmark-local-concept-section local-concepts)]
    (merge-concept-sections global-section local-section)))
