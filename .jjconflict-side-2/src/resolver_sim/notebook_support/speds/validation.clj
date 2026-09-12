(ns resolver-sim.notebook-support.speds.validation
  "Lightweight SPEDS validation helpers for consistency checks."
  (:require [clojure.java.io :as io]
            [resolver-sim.notebook-support.speds.config :as config]))

(def required-frame-keys
  #{:header :footer-left :footer-right :content})

(def required-claim-keys
  #{:claim-id :value :source-artifact :source-path})

(def required-issue-keys
  #{:issue/id :scenario/id :kind :severity :status-kind :title
    :story/family :priority :evidence/refs :provenance :one_line_description})

(def required-finding-keys
  #{:finding_id :scenario_id :kind :severity :status_kind :title
    :summary :story :evidence_refs :provenance})

(defn validate-frame-schema
  "Checks that every frame map contains required keys.
   Returns a vector of issue maps."
  [frames]
  (->> frames
       (map-indexed
        (fn [idx frame]
          (let [missing (seq (remove #(contains? frame %) required-frame-keys))]
            (when missing
              {:frame-index idx
               :issue :missing-required-keys
               :missing (vec missing)}))))
       (remove nil?)
       vec))

(defn validate-frame-claims
  "Validates :claims payload for each frame spec.
   Each claim should include required-claim-keys."
  [frames]
  (->> frames
       (map-indexed
        (fn [fidx frame]
          (map-indexed
           (fn [cidx claim]
             (let [missing (seq (remove #(contains? claim %) required-claim-keys))]
               (when missing
                 {:frame-index fidx
                  :claim-index cidx
                  :issue :missing-claim-fields
                  :missing (vec missing)})))
           (or (:claims frame) []))))
       (mapcat identity)
       (remove nil?)
       vec))

(defn scan-hardcoded-success-claims
  "Scans files for hardcoded success claims.
   Returns [{:file ... :pattern ...}] for matches."
  [paths]
  (->> paths
       (mapcat
        (fn [p]
          (let [f (io/file p)]
            (if (.exists f)
              (let [txt (slurp f)]
                (->> config/success-patterns
                     (filter #(re-find % txt))
                     (map (fn [pat]
                            {:file p :issue :hardcoded-success-claim :pattern (str pat)}))))
              []))))
       vec))

(defn validate-claim-sources
  "Verifies claim source paths are present in loaded artifacts map.
   claim-sources format:
   - {claim-id [:summary :git_sha] ...}
   - {claim-id {:path [:summary :git_sha] :required? false} ...}
   Returns issues for missing paths."
  [artifacts claim-sources]
  (->> claim-sources
       (map (fn [[claim-id spec]]
              (let [{:keys [path required?] :or {required? true}}
                    (if (map? spec) spec {:path spec :required? true})]
                (when (and required? (nil? (get-in artifacts path)))
                  {:claim claim-id :issue :missing-claim-source :path path}))))
       (remove nil?)
       vec))

(defn validate-issue-schema
  "Checks required keys and basic shape for each issue map."
  [issues]
  (->> (or issues [])
       (map-indexed
        (fn [idx issue]
          (let [missing (seq (remove #(contains? issue %) required-issue-keys))
                refs (:evidence/refs issue)
                refs-ok? (and (vector? refs) (every? map? refs))]
            (cond
              missing
              {:issue-index idx :issue :missing-issue-keys :missing (vec missing)}

              (not refs-ok?)
              {:issue-index idx :issue :invalid-evidence-refs :value refs}

              :else nil))))
       (remove nil?)
       vec))

(defn validate-issue-evidence-refs
  "Checks that evidence refs contain artifact and path fields."
  [issues]
  (->> (or issues [])
       (map-indexed
        (fn [i issue]
          (->> (or (:evidence/refs issue) [])
               (map-indexed
                (fn [r ref]
                  (when (or (nil? (:artifact ref)) (nil? (:path ref)))
                    {:issue-index i
                     :ref-index r
                     :issue :invalid-evidence-ref
                     :ref ref})))
               (remove nil?))))
       (mapcat identity)
       vec))

(defn validate-finding-schema
  "Checks required keys and basic shape for each finding map."
  [findings]
  (->> (or findings [])
       (map-indexed
        (fn [idx finding]
          (let [missing (seq (remove #(contains? finding %) required-finding-keys))
                refs (:evidence_refs finding)
                refs-ok? (and (vector? refs) (every? map? refs))]
            (cond
              missing {:finding-index idx :issue :missing-finding-keys :missing (vec missing)}
              (not refs-ok?) {:finding-index idx :issue :invalid-finding-evidence-refs :value refs}
              :else nil))))
       (remove nil?)
       vec))

(defn run-speds-consistency-checks
  "Runs all lightweight consistency checks.
   opts keys:
   - :frames
   - :files
   - :artifacts
   - :claim-sources"
  [{:keys [frames files artifacts claim-sources findings issues]}]
  {:frame-schema-issues (validate-frame-schema (or frames []))
   :frame-claim-issues (validate-frame-claims (or frames []))
   :hardcoded-claims (scan-hardcoded-success-claims (or files []))
   :claim-source-issues (validate-claim-sources (or artifacts {}) (or claim-sources {}))
   :finding-schema-issues (validate-finding-schema (or findings []))
   :issue-schema-issues (validate-issue-schema (or issues []))
   :issue-evidence-issues (validate-issue-evidence-refs (or issues []))})
