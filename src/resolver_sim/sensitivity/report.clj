(ns resolver-sim.sensitivity.report
  "Sensitivity report builder for scenario run outputs.
   Produces sensitivity-report.v2 combining classification,
   provenance, and safety-scan results.

   Provenance is constructed here — this is the single canonical
   provenance authority. All downstream artifacts (bundle root,
   attestation bundle) consume this report by persisted reference.

   Provenance records derivation and data lineage only. It does NOT
   imply operator identity, signer identity, trusted execution,
   signature verification, external attestation, or human approval."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.sensitivity.propagation :as prop]
            [resolver-sim.sensitivity.sentinel :as sentinel])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.time Instant]))

(def ^:const report-schema-version "sensitivity-report.v2")

;; ── Policy identity (deterministic) ─────────────────────────────────────────

(def ^:private sentinel-ruleset
  {:ruleset/id "sensitivity-sentinel"
   :ruleset/schema "sentinel-rules.v1"
   :ruleset/version sentinel/sentinel-version
   :ruleset/hash (sentinel/policy-hash)
   :level-ordering/version sentinel/sentinel-version
   :level-ordering/levels (vec (map name sentinel/levels))
   :risk-severity-ordering/version sentinel/sentinel-version
   :risk-severity-ordering/levels (vec (map name sentinel/risk-severities))
   :disclosure-policy/id "disclosure-matrix"
   :disclosure-policy/version "v2"
   :disclosure-policy/hash (sentinel/policy-hash)})

(def ^:private structural-classifier
  {:implementation/id "structural-classifier"
   :implementation/version "v1"
   :implementation/source "resolver-sim.sensitivity.sentinel/classify-structural"
   :ruleset-ref {:ruleset/id "structural-heuristics"
                 :ruleset/schema "sentinel-rules.v1"
                 :ruleset/version sentinel/sentinel-version}
   :ruleset/hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                      {:ruleset/id "structural-heuristics"
                                       :version sentinel/sentinel-version
                                       :source "resolver-sim.sensitivity.sentinel/classify-structural"})})

(def ^:private secret-scanner-classifier
  {:implementation/id "secret-scanner"
   :implementation/version "v1"
   :implementation/source "resolver-sim.commands.scenario-safety/sensitivity-findings"
   :ruleset-ref {:ruleset/id "secret-patterns"
                 :ruleset/schema "regex-patterns.v1"
                 :ruleset/version "v1"}
   :ruleset/hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                      {:ruleset/id "secret-patterns"
                                       :version "v1"
                                       :source "resolver-sim.commands.scenario-safety/secret-rules"})})

(def ^:private merge-function
  {:implementation/id "merge-sensitivity"
   :implementation/version "v2"
   :implementation/source "resolver-sim.sensitivity.propagation/merge-sensitivity"})

(def ^:private canonical-hash-impl
  {:implementation/id "canonical-hash"
   :implementation/version "v1"
   :implementation/source "resolver-sim.hash.canonical/hash-with-intent"
   :domain-tag/intent :evidence-record
   :domain-tag/prefix "EVIDENCE_RECORD_V1"})

;; ── Sensitivity status codes ────────────────────────────────────────────────

(def ^:private sensitivity-status-codes
  #{:sensitivity-status/evaluated
    :sensitivity-status/no-declaration-structural-only
    :sensitivity-status/not-scanned
    :sensitivity-status/evaluation-failed
    :sensitivity-status/malformed-declaration})

;; ── Structured source helpers ───────────────────────────────────────────────

(defn- scenario-source-record
  "Build a structured provenance record for a single scenario's contribution
   to the run-level sensitivity decision."
  [result effective-level run-level]
  (let [sens (get-in result [:scenario-metadata :scenario/sensitivity])
        structural-level (sentinel/classify-structural result)
        declared-level (:level sens)
        at-max? (= effective-level run-level)]
     {:source/type :scenario-sensitivity
      :scenario/id (:scenario-id result)
      :scenario/input-hash (:scenario-input-hash result)
      :scenario/content-hash (:scenario-hash result)
      :scenario/path (:scenario-path result)
      :declared-level (when declared-level (name declared-level))
      :structural-level (name structural-level)
      :effective-level (name effective-level)
      :run-level-role (if at-max? :run-max :below-max)}))

(defn- display-sources
  "Human-readable summary derived from structured records."
  [structured]
  (mapv (fn [s]
          (case (:source/type s)
            :scenario-sensitivity
            (str "scenario:" (:scenario/id s)
                 ":" (:effective-level s)
                 ":" (name (:run-level-role s)))
            :sentinel-implementation
            (str "impl:" (:implementation/id s) ":" (:implementation/version s))
            :policy-profile
            (str "profile:" (:profile/id s))
            :agg-function
            (str "agg:" (:implementation/id s) ":" (:implementation/version s))
            :scenario-set
            (str "scenarios:" (:count s))
            (pr-str s)))
        structured))

;; ── Per-scenario entry ──────────────────────────────────────────────────────

(defn- scenario-path-token
  "Deterministic truncated SHA-256 of a file path for finding matching.
   Same algorithm as scenario-safety/compute-path-token."
  [path]
  (when path
    (let [digest (doto (MessageDigest/getInstance "SHA-256")
                   (.update (.getBytes path "UTF-8")))]
      (format "path-%s" (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 12)))))

(defn- matching-findings
  "Find safety findings whose path token matches the scenario path."
  [scenario-path findings]
  (when-let [token (scenario-path-token scenario-path)]
    (filter #(= (:finding/path-token %) token) findings)))

(defn- per-scenario-entry
  "Build a deterministic scenario entry for the report.

   Includes structural/declared/effective levels, exact declaration provenance
   (source path, hash, schema version when available), structured status code,
   and structural derivation evidence (rule IDs, finding refs) connecting
   safety-scan findings to the scenario."
  [result findings]
  (let [sens (get-in result [:scenario-metadata :scenario/sensitivity])
        declared-level (:level sens)
        structural (sentinel/classify-structural result)
        effective (if (and declared-level
                           (sentinel/level>= declared-level structural))
                    declared-level
                    structural)
        status (cond
                 (nil? sens) :sensitivity-status/no-declaration-structural-only
                 (some? declared-level) :sensitivity-status/evaluated
                 :else :sensitivity-status/evaluated)
        ;; Declaration provenance — where did the declared level come from?
        dec-source (when sens
                     (let [path (:scenario-path result)
                           source-hash (:scenario-input-hash result)
                           scenario-hash (:scenario-hash result)]
                       (cond-> {:schema "scenario-sensitivity.v1"
                                :value (name declared-level)
                                :declaration/source-artifact-id (:scenario-id result)}
                         path (assoc :declaration/source-path path)
                         source-hash (assoc :declaration/source-bytes-hash source-hash)
                         scenario-hash (assoc :declaration/source-content-hash scenario-hash)
                         (:risk-meta sens) (assoc :declaration/risk-meta-hash
                                                  (hc/hash-with-intent
                                                   {:hash/intent :evidence-record}
                                                   (:risk-meta sens))))))
        ;; Result-artifact provenance — sensitivity of the scenario execution
        ;; output artifact.  This is distinct from the scenario's effective
        ;; sensitivity (which classifies the scenario result content).
        ;; Populated when the pipeline makes the output artifact metadata
        ;; (path, sha256, byte-length) available on the scenario result.
        result-artifact (when-let [artifact-sens (:sensitivity/result-level result)]
                          {:result/artifact-id (:scenario-id result)
                           :result/sensitivity (name artifact-sens)})
        scenario-path (:scenario-path result)
        matched-findings (vec (matching-findings scenario-path findings))
        structural-reasons (when (seq matched-findings)
                             {:structural/classification-level (name structural)
                              :structural/reasons
                              (mapv (fn [f]
                                      {:reason/code (case (:rule/id f)
                                                      :secret-scanner/private-key :contains-live-vulnerability
                                                      :secret-scanner/credential-assignment :contains-unpublished-evidence
                                                      :secret-scanner/bearer-auth :contains-unpublished-evidence
                                                      :secret-scanner/jwt-token :contains-protocol-identifier
                                                      :secret-scanner/github-token :contains-linkable-subject-hash
                                                      :secret-scanner/npm-token :contains-linkable-subject-hash
                                                      :contains-unpublished-evidence)
                                       :rule/id (:rule/id f)
                                       :rule/version (:rule/version f)
                                       :finding/ref (:finding/id f)})
                                    matched-findings)})]
    (cond-> {:id (:scenario-id result)
             :input-hash (:scenario-input-hash result)
             :structural-level (name structural)
             :effective-level (name effective)
             :sensitivity/status (name status)}
      declared-level (assoc :declared-level (name declared-level))
      dec-source (assoc :declaration-provenance dec-source)
      sens (assoc :risk-meta (when-let [rm (:risk-meta sens)]
                               (update rm :reason-codes (fn [v]
                                                          (vec (sort (map name v)))))))
      structural-reasons (assoc :structural-derivation structural-reasons)
      result-artifact (assoc :result-artifact result-artifact))))

;; ── Provenance construction (canonical source) ──────────────────────────────

(defn- build-canonical-report-provenance
  "Build the canonical provenance record for a sensitivity report.

   This is the single call site for prop/build-sensitivity-derivation on the
   report's sensitivity decision. No other layer independently
   persists a materially equivalent provenance object.

   propagation.clj's build-sensitivity-derivation is a pure derivation
   helper — it computes fact records from inputs.  This function binds
   those facts to exact inputs, policy, implementation, and report identity.

   Arguments:
     run-sensitivity — result from prop/merge-sensitivity
     scenarios       — seq of scenario result maps (used for per-scenario
                        structural classification and contribution tracking)
     context         — map with :run-id, :profile, :scenario-ids, :sentinel-version"
  [run-sensitivity scenarios context]
  (let [level (:level run-sensitivity)
        ;; Per-scenario structured records
        scenario-records
        (mapv (fn [result]
                (let [sens (get-in result [:scenario-metadata :scenario/sensitivity])
                      declared-level (:level sens)
                      s-level (sentinel/classify-structural result)
                      effective (if (and declared-level
                                         (sentinel/level>= declared-level s-level))
                                  declared-level
                                  s-level)]
                  (scenario-source-record result effective level)))
              scenarios)
        ;; Implementation sources
        impl-records [sentinel-ruleset
                      structural-classifier
                      secret-scanner-classifier
                      merge-function
                      canonical-hash-impl]
        ;; Policy source
        profile-name (name (:profile context))
        policy-source {:source/type :policy-profile
                       :profile/id profile-name
                       :profile/hash (hc/hash-with-intent
                                      {:hash/intent :evidence-record}
                                      {:profile profile-name
                                       :policy-id "sensitivity-disclosure-policy"
                                       :policy-version "v2"})}
        ;; Scenario-set reconciliation
        scenario-ids (vec (sort (map :scenario-id scenarios)))
        scenario-set-hash (hc/hash-with-intent
                           {:hash/intent :evidence-record}
                           {:scenario-ids scenario-ids
                            :count (count scenario-ids)})
        scenario-set-source {:source/type :scenario-set
                             :scenario-ids scenario-ids
                             :scenario-id-set-hash (str scenario-set-hash)
                             :count (count scenario-ids)
                             :missing-scenario-ids []
                             :unexpected-scenario-ids []}
        ;; All structured sources
        structured (vec (concat scenario-records
                                impl-records
                                [policy-source scenario-set-source]))
        ;; Display summary
        display (display-sources structured)
        effective-level (name level)]
    (prop/build-sensitivity-derivation
     {:sentinel/effective-level effective-level
      :sentinel/structural-level (name (:structural-level run-sensitivity :sensitivity/internal))
      :sentinel/declared-level (when-let [d (:declared-level run-sensitivity)]
                                 (name d))
      :sentinel/reasons (vec (sort (map name (sentinel/default-reasons level))))
      :sentinel/risk-meta (:risk-meta run-sensitivity)
      :sentinel/sources display
      :sentinel/structured-sources structured}
     ;; Additional context as structured records
     {:source/type :run-context
      :run/id (:run-id context)
      :profile profile-name
      :sentinel-version (:sentinel-version context)})))

;; ── Aggregation derivation ──────────────────────────────────────────────────

(defn- build-aggregation-derivation
  "Build a structured derivation record showing how the run-level
   sensitivity was computed from per-scenario sensitivities.
   Records input-set hash, winners (including ties), and risk
   aggregation provenance."
  [run-sensitivity scenarios findings]
  (let [scenario-entries (mapv #(per-scenario-entry % findings) scenarios)
        total (count scenarios)
        missing (count (filter #(= ":sensitivity-status/no-declaration-structural-only"
                                   (:sensitivity/status %))
                                scenario-entries))
        evaluated (count (filter #(= ":sensitivity-status/evaluated"
                                     (:sensitivity/status %))
                                  scenario-entries))
        run-level-name (when run-sensitivity (name (:level run-sensitivity)))
        winners (filterv #(= (:effective-level %) run-level-name)
                         scenario-entries)
        multiple-winners? (> (count winners) 1)
        ;; Input-set hash: canonical hash of the collection of
        ;; per-scenario sensitivity input vectors.
        input-set-hash (when run-sensitivity
                         (str (hc/hash-with-intent
                               {:hash/intent :evidence-record}
                               (vec (sort-by :scenario-id
                                     (mapv (fn [s]
                                             (let [sens (get-in s [:scenario-metadata :scenario/sensitivity])
                                                   structural (sentinel/classify-structural s)]
                                               {:scenario-id (:scenario-id s)
                                                :declared-level (when (:level sens) (name (:level sens)))
                                                :structural-level (name structural)
                                                :effective-level (name (if (and (:level sens)
                                                                                (sentinel/level>= (:level sens) structural))
                                                                         (:level sens)
                                                                         structural))}))
                                           scenarios))))))
        ;; Risk-aggregation winner: find the scenario whose risk-severity
        ;; matches the merged result's risk-severity.
        risk-severity (when run-sensitivity
                        (get-in run-sensitivity [:risk-meta :risk-severity]))
        risk-winner (when risk-severity
                      (first (filterv (fn [s]
                                        (let [sens (get-in s [:scenario-metadata :scenario/sensitivity])
                                              sev (get-in sens [:risk-meta :risk-severity])]
                                          (= sev risk-severity)))
                                      scenarios)))]
    {:aggregation/function :max-effective-sensitivity
     :aggregation/version "merge-sensitivity.v2"
     :aggregation/merge-function-ref merge-function
     :aggregation/input-count total
     :aggregation/included-count evaluated
     :aggregation/missing-count missing
     :aggregation/scenario-id-set-hash (str (hc/hash-with-intent
                                              {:hash/intent :evidence-record}
                                              (vec (sort (map :id scenario-entries)))))
     :aggregation/input-set-hash input-set-hash
     :aggregation/winners (mapv :id winners)
     :aggregation/multiple-winners? multiple-winners?
     :aggregation/result (when run-sensitivity (name (:level run-sensitivity)))
     :risk-aggregation/function :max-risk-severity
     :risk-aggregation/winner-scenario-id (when risk-winner (:scenario-id risk-winner))
     :risk-aggregation/result (when risk-severity (name risk-severity))}))

;; ── Report builder ──────────────────────────────────────────────────────────

(defn build-sensitivity-report
  "Build a v2 sensitivity report combining classification,
   safety-scan results, and provenance.

   This is the canonical provenance authority for the run-level
   sensitivity decision. Provenance is constructed here, not by
   callers. Downstream artifacts consume the persisted report or
   reference its hash.

   Arguments:
     safety-result     — map from scan-public-bundle! or scan-internal-bundle!
     run-sensitivity   — map from prop/merge-sensitivity or nil
     scenarios         — seq of scenario result maps
     context           — map with :run-id, :profile, :scenario-ids, :sentinel-version

   Returns a sensitivity-report.v2 map."
  [safety-result run-sensitivity scenarios context]
  (let [findings (vec (sort-by (fn [f] (str (:finding/id f) (:rule/id f))) (:findings safety-result [])))
        scenario-entries (vec (sort-by :id (mapv #(per-scenario-entry % findings) scenarios)))
        sensitive-count (count (filter :declared-level scenario-entries))
        total-count (count scenario-entries)
        has-prov? (some? run-sensitivity)
        provenance (when has-prov?
                     (build-canonical-report-provenance run-sensitivity scenarios context))
        aggregation-derivation (when has-prov?
                                 (build-aggregation-derivation run-sensitivity scenarios findings))
        ;; Decision provenance — separates classification (what level) from
        ;; decision (what action was taken given profile + policy).
        decision-provenance
        (let [profile-name (name (:profile safety-result :internal))
              profile-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                                 {:profile profile-name
                                                  :scan-mode (name (:profile safety-result :internal))
                                                  :ruleset-ref {:ruleset/id "disclosure-policy"
                                                                :ruleset/version "v2"}})
              decision-str (name (or (:decision safety-result) :allowed))
              decision-reasons (cond
                                 (= "blocked" decision-str)
                                 [{:reason/code :blocked-by-sensitivity-policy
                                   :reason/detail "Findings detected under current profile"}]
                                 (= "internal-retention" decision-str)
                                 [{:reason/code :internal-retention-required
                                   :reason/detail "Findings detected; bundle retained internally"}]
                                 :else [])]
          {:policy/id :sensitivity-disclosure-policy
           :policy/version "v2"
           :policy/hash (sentinel/policy-hash)
           :profile/id profile-name
           :profile/hash profile-hash
           :evaluation/input-level (when has-prov? (name (:level run-sensitivity)))
           :evaluation/risk-severity (when-let [sev (get-in run-sensitivity [:risk-meta :risk-severity])]
                                        (name sev))
           :evaluation/decision decision-str
            :evaluation/reasons decision-reasons})
         ;; Scenario-set reconciliation — proves the report considered the
         ;; full scenario set.  missing-scenario-ids and unexpected-scenario-ids
         ;; are populated when reconciling against an authoritative execution
         ;; inventory or package index (future — currently empty).
         scenario-ids (vec (sort (map :id scenario-entries)))
         scenario-id-set-hash (str (hc/hash-with-intent
                                    {:hash/intent :evidence-record}
                                    scenario-ids))
         duplicate-ids (let [freq (frequencies (map :id scenario-entries))]
                         (vec (keys (filter (fn [[_k v]] (> v 1)) freq))))
         scenario-set-reconciliation
         {:report/scenario-count total-count
          :scenario-id-set-hash scenario-id-set-hash
          :scenario-ids scenario-ids
          :missing-scenario-ids []
          :unexpected-scenario-ids []
          :duplicate-scenario-ids duplicate-ids}
         base {:schema-version report-schema-version
              :run-id (:run-id context)
              :profile (name (:profile safety-result :internal))
              :decision (name (or (:decision safety-result) :allowed))
              :findings findings
              :scenario-count total-count
              :sensitive-scenario-count sensitive-count
              :scenarios scenario-entries}
        base (cond-> base
               has-prov? (assoc :run-level (name (:level run-sensitivity))
                                :structural-level (name (:structural-level run-sensitivity :sensitivity/internal))
                                :risk-meta (:risk-meta run-sensitivity))
               provenance (assoc :provenance provenance)
               aggregation-derivation (assoc :aggregation-derivation aggregation-derivation)
               true (assoc :decision-provenance decision-provenance)
               true (assoc :scenario-set-reconciliation scenario-set-reconciliation))
        ;; Semantic hash: computed over the canonical semantic projection,
        ;; excluding volatile envelope fields (evaluated-at, report-hash,
        ;; report-byte-hash). Multiple reports with the same semantic content
        ;; at different times share the same semantic-hash.
        hash-input (dissoc base :evaluated-at :report-hash :report-byte-hash)
        semantic-hash (hc/hash-with-intent {:hash/intent :evidence-record} hash-input)]
    (assoc base :evaluated-at (str (Instant/now))
               :report-hash semantic-hash
               :report/semantic-hash semantic-hash)))

;; ── Persistence ────────────────────────────────────────────────────────────

(defn write-sensitivity-report!
  "Atomically write a sensitivity report to manifest/sensitivity-report.json.

   After writing, reads back the file and computes the byte hash and length
   for the report-byte-hash field. Verifies semantic payload hash before returning.

   Arguments:
     manifest-dir — directory for manifest artifacts
     report       — sensitivity-report.v2 map from build-sensitivity-report

   Returns the written map (post-readback with byte-hash attached)."
  [manifest-dir report]
  (let [target (io/file (str manifest-dir) "sensitivity-report.json")
        temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str report))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    ;; Readback: compute byte hash and length
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath target))
          digest (doto (MessageDigest/getInstance "SHA-256")
                   (.update bytes))
          byte-hash (format "%064x" (java.math.BigInteger. 1 (.digest digest)))
          byte-length (alength bytes)
          ;; Verify semantic payload was not corrupted during serialization
          read-back (json/read-str (String. bytes "UTF-8") :key-fn keyword)
          orig-semantic-hash (:report/semantic-hash report)
          reported-semantic-hash (:report/semantic-hash read-back)]
      (when (and orig-semantic-hash reported-semantic-hash
                 (not= orig-semantic-hash reported-semantic-hash))
        (throw (ex-info "Sensitivity report semantic hash mismatch after write/readback"
                        {:report/orig-hash orig-semantic-hash
                         :report/readback-hash reported-semantic-hash
                         :report/path (.getPath target)})))
      (assoc report
             :report/semantic-hash orig-semantic-hash
             :report-byte-hash byte-hash
             :report-byte-length byte-length))))
