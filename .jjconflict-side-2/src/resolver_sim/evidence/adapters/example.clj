(ns resolver-sim.evidence.adapters.example
  "Example forensic adapter that derives a compact mechanism-specific report
   from the evidence links index."
  (:require [resolver-sim.evidence.forensic-adapter :as forensic]
            [resolver-sim.io.event-evidence :as event-evidence]))

(defrecord MechanismReportAdapter [mechanism]
  forensic/ForensicEvidenceAdapter
  (adapter-id [_] :resolver-sim.example/mechanism-report)
  (adapter-version [_] "0.1.0")
  (adapter-kind [_] :derived-index)
  (build-adapter-artifact [this opts]
    (let [{:keys [artifact-root event-evidence-dir output-path]
           :or {output-path "forensics/example-mechanism-report.json"}} opts
          links (event-evidence/build-evidence-links-index-v1 event-evidence-dir)
          wanted (:mechanism this)
          mechanism-name (fn [m] (when m (if (keyword? m) (name m) (str m))))
          selected (for [[gid group] (:groups links)
                         artifact (:artifacts group)
                         :when (= (mechanism-name wanted)
                                  (mechanism-name (:evidence/mechanism artifact)))]
                     {:group-id gid
                      :relative-path (:relative-path artifact)
                      :hash (:hash artifact)
                      :evidence/type (:evidence/type artifact)
                      :event/seq (:event/seq artifact)})
          report {:mechanism wanted
                  :selected-count (count selected)
                  :groups (->> selected (map :group-id) distinct vec)
                  :artifacts (vec selected)}]
      (forensic/write-forensic-adapter-output!
       {:adapter-id (forensic/adapter-id this)
        :adapter-version (forensic/adapter-version this)
        :adapter-kind (forensic/adapter-kind this)
        :artifact-root artifact-root
        :output-path output-path
        :input-artifacts (mapv #(select-keys % [:relative-path :hash]) selected)
        :output report}))))

(defn write-example-mechanism-report!
  "Write an example derived forensic report for one mechanism, defaulting to :slashing."
  [& {:keys [mechanism] :as opts
      :or {mechanism :slashing}}]
  (forensic/run-adapter! (->MechanismReportAdapter mechanism) opts))
