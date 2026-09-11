(ns resolver-sim.evidence.attestation-report
  "Attestation reporting and aggregation over the attestation registry.

   Builds on the attestation registry's query functions to provide structured
   reports, time-range queries, and grouped aggregations for analysis and audit.

   All functions operate on a sequence of attestation records. Pass the result
   of ar/all-attestations or ar/find-attestations-by-* as the data argument.

   Usage:
     (require '[resolver-sim.evidence.attestation-registry :as ar])
     (require '[resolver-sim.evidence.attestation-report :as arpt])

     ;; Full report over all registered attestations
     (arpt/attestation-report (ar/all-attestations))

     ;; Filtered report for a specific attestor
     (arpt/attestation-report (ar/find-attestations-by-attestor :ci-validation))

     ;; Attestations within a time window
     (arpt/find-attestations-by-time-range (ar/all-attestations)
       \"2025-01-01T00:00:00Z\" \"2025-12-31T23:59:59Z\")"

  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

;; ── Time-range query ─────────────────────────────────────────────────────────

(defn- parse-instant
  [s]
  (try
    (Instant/parse s)
    (catch DateTimeParseException _
      nil)))

(defn- instant-compare
  [a b]
  (cond
    (instance? Instant a) (.compareTo ^Instant a b)
    (string? a) (if-let [p (parse-instant a)]
                  (.compareTo ^Instant p b)
                  (throw (ex-info "Cannot parse instant" {:value a})))
    :else (throw (ex-info "Unsupported instant type" {:type (type a) :value a}))))

(defn find-attestations-by-time-range
  "Find attestations whose :attestation/signed-at falls within a time range.
   Returns a vector sorted by signed-at.

   Arguments:
     attestations — seq of attestation records
     start       — ISO-8601 string or Instant (inclusive, or nil for unbounded)
     end         — ISO-8601 string or Instant (inclusive, or nil for unbounded)

   Both start and end boundaries are inclusive."
  [attestations start end]
  (let [start-inst (when start (if (instance? Instant start) start (parse-instant start)))
        end-inst (when end (if (instance? Instant end) end (parse-instant end)))]
    (->> attestations
         (filter (fn [a]
                   (let [signed (:attestation/signed-at a)]
                     (and (if start-inst
                            (>= (instant-compare signed start-inst) 0)
                            true)
                          (if end-inst
                            (<= (instant-compare signed end-inst) 0)
                            true)))))
         (sort-by :attestation/signed-at)
         vec)))

;; ── Grouped queries ──────────────────────────────────────────────────────────

(defn attestations-by-attestor-grouped
  "Group attestations by :attestation/attestor-id.
   Returns a map of {attestor-id -> [attestations]}."
  [attestations]
  (->> attestations
       (group-by :attestation/attestor-id)))

(defn attestations-by-claim-result-grouped
  "Group attestations by :attestation/claim-result.
   Returns a map of {claim-result -> [attestations]}."
  [attestations]
  (->> attestations
       (group-by :attestation/claim-result)))

(defn attestations-by-subject-kind-grouped
  "Group attestations by :attestation/subject-kind.
   Returns a map of {subject-kind -> [attestations]}."
  [attestations]
  (->> attestations
       (group-by :attestation/subject-kind)))

(defn attestations-by-claim-id-grouped
  "Group attestations by :attestation/claim-id, discarding nil claim-ids.
   Returns a map of {claim-id -> [attestations]}."
  [attestations]
  (->> attestations
       (remove #(nil? (:attestation/claim-id %)))
       (group-by :attestation/claim-id)))

;; ── Summary ──────────────────────────────────────────────────────────────────

(defn attestation-summary
  "Generate a structured summary of attestation records.

   Returns a map with:
     :total-count        — total number of attestations
     :signed-count       — attestations with a signature
     :unsigned-count     — attestations without a signature
     :attestors          — map of {attestor-id -> count}
     :claim-results      — map of {claim-result -> count}
     :subject-kinds      — map of {subject-kind -> count}
     :claim-ids          — map of {claim-id -> count} (non-nil only)
     :subject-hash-count — number of unique subjects attested
     :time-range         — {:earliest signed-at, :latest signed-at} or nil
     :signed-ratio       — float ratio of signed to total (or nil if empty)"
  [attestations]
  (let [attestations (vec attestations)
        total (count attestations)
        signed-count (count (filter #(some? (:attestation/signature %)) attestations))
        unsigned-count (- total signed-count)
        signed-at-vals (keep :attestation/signed-at attestations)]
    {:total-count total
     :signed-count signed-count
     :unsigned-count unsigned-count
     :attestors (->> attestations
                     (map :attestation/attestor-id)
                     frequencies)
     :claim-results (->> attestations
                         (map :attestation/claim-result)
                         frequencies)
     :subject-kinds (->> attestations
                         (map :attestation/subject-kind)
                         frequencies)
     :claim-ids (->> attestations
                     (keep :attestation/claim-id)
                     frequencies)
     :subject-hash-count (->> attestations
                              (map :attestation/subject-hash)
                              distinct
                              count)
     :time-range (when (seq signed-at-vals)
                   (let [sorted (sort signed-at-vals)]
                     {:earliest (first sorted)
                      :latest (last sorted)}))
     :signed-ratio (when (pos? total)
                     (float (/ signed-count total)))}))

;; ── Full report ──────────────────────────────────────────────────────────────

(defn attestation-report
  "Generate a comprehensive attestation report including summary, per-attestor
   breakdown, per-claim breakdown, and full attestation listing.

   Returns a map with:
     :generated-at   — ISO-8601 timestamp of report generation
     :attestation-count — total attestations covered
     :summary        — result of attestation-summary
     :by-attestor    — result of attestations-by-attestor-grouped with per-group summaries
     :by-claim-result — result of attestations-by-claim-result-grouped with per-group counts"
  [attestations]
  (let [atts (vec attestations)
        by-attestor (attestations-by-attestor-grouped atts)
        by-claim-result (attestations-by-claim-result-grouped atts)]
    {:generated-at (str (Instant/now))
     :attestation-count (count atts)
     :summary (attestation-summary atts)
     :by-attestor (reduce-kv (fn [m k v] (assoc m k {:count (count v)
                                                     :claim-results (->> v (map :attestation/claim-result) frequencies)
                                                     :signed-count (count (filter #(some? (:attestation/signature %)) v))}))
                             {}
                             by-attestor)
     :by-claim-result (reduce-kv (fn [m k v] (assoc m k {:count (count v)
                                                         :attestors (->> v (map :attestation/attestor-id) frequencies)}))
                                 {}
                                 by-claim-result)}))
