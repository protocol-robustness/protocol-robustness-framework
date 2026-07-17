(ns resolver-sim.io.event-evidence
  "System for capturing and persisting high-fidelity evidence for high-impact transitions.
   Integrates with with-attribution context to bind causal metadata.

   Phase 3 enhancements:
     - Pure builder layer in resolver-sim.evidence.capture (cap-field, cap-fields, require-fields, finalize-evidence)
     - Content-addressed before/after world hashes via world-hash
     - Replay coordinates (seed, oracle mode/fixture/cursor) from attribution context
     - Causality links (caused-by/evidence-id, caused-by/rule, caused-by/action)
     - Evidence importance levels (:core, :diagnostic, :trace)
     - Composite evidence-hash chain for tamper evidence
     - event-evidence.v1 schema compliance"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.logging :as log]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn qualified-key
  "Serialize a Clojure keyword to a JSON key that preserves its namespace.
   :evidence/type → \"evidence/type\"
   Uses the full str representation (namespaced keyword → \"ns/name\") to
   ensure round-trip fidelity with (json/read-str ... :key-fn keyword)."
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (string? k) k
    :else (str k)))

(defn read-evidence-json
  "Read an evidence artifact JSON file and return the map with keyword keys.
   Handles both qualified JSON keys (evidence/type) and legacy unqualified keys
   (type) by normalizing unqualified keys to their namespaced equivalents
   when the expected namespaced lookup is nil."
  [f]
  (let [data (json/read-str (slurp f) :key-fn keyword)]
    ;; Normalize: if unqualified keys were stored (legacy format), promote
    ;; :type → :evidence/type, :chain-seq → :evidence/chain-seq, etc.
    (reduce-kv (fn [m k v]
                 (if (and (keyword? k) (not (namespace k)))
                   ;; Try to find a matching namespaced key by checking
                   ;; common known prefixes for the unqualified name.
                   (let [qualified (keyword "evidence" (name k))]
                     (if (get data qualified)
                       m ;; already present, skip legacy fallback
                       (assoc m qualified v)))
                   m))
               data data)))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn evidence-filename
  "Derive a collision-resistant filename from evidence record metadata.
   Sanitize evidence-type to avoid directory traversal issues. The injected
   chain sequence is the unique ordering key; a replay event sequence may be
   shared by several targeted protocol records."
  [evidence]
  (let [etype-str (fn [v] (if (keyword? v) (name v) (str v)))
        etype (:evidence/type evidence)
        reason (-> (etype-str etype)
                   (str/replace #":|-|/" "-")
                   (str/replace #"\\.\\." ""))
        sid    (-> (etype-str (:scenario/id evidence))
                   (str/replace #":|-|/" "-")
                   (str/replace #"\\.\\." ""))
        idx    (or (:evidence/chain-seq evidence)
                   (:event/seq evidence)
                   "unknown")]
    (str reason "-" sid "-" idx ".json")))

(def ^:dynamic *write-verification* :exists)

(defn- verify-write!
  "Verify that an evidence artifact was written correctly.
   Throws on verification failure."
  [evidence path]
  (case *write-verification*
    :none
    nil

    :exists
    (when (or (not (.exists path)) (zero? (.length path)))
      (throw (ex-info "Evidence write verification failed"
                      {:path (str path)
                       :exists (.exists path)
                       :length (when (.exists path) (.length path))
                       :evidence-type (:evidence/type evidence)})))

    :readback
    (let [written (json/read-str (slurp path) :key-fn keyword)]
      (when (not= (dissoc evidence :persistence)
                  (dissoc written :persistence))
        (throw (ex-info "Evidence readback mismatch"
                        {:path (str path)
                         :evidence-type (:evidence/type evidence)
                         :expected-keys (keys (dissoc evidence :persistence))
                         :actual-keys (keys (dissoc written :persistence))}))))))

;; ── Performance Metrics ──────────────────────────────────────────────────────
;;
;; Phase 4: Latency tracking for evidence capture pipeline.
;; Metrics collected:
;;   - capture-latency-ms: wall-clock time per capture-event-evidence! call
;;   - hash-latency-ms: wall-clock time per hash-with-intent computation
;;   - serialize-latency-ms: wall-clock time per JSON serialization + disk write
;;
;; Access metrics from tests or production monitors:
;;   (resolver-sim.io.event-evidence/collect-and-reset-metrics!)
;; Returns a map of aggregate stats and resets the counters.

(def ^:private metrics (atom {:capture-count 0
                              :capture-total-ns 0
                              :hash-count 0
                              :hash-total-ns 0
                              :serialize-count 0
                              :serialize-total-ns 0}))

(defn collect-and-reset-metrics!
  "Return accumulated metrics and reset counters.
   Returns {:capture-count N :capture-latency-ms avg-ms
            :hash-count N :hash-latency-ms avg-ms
            :serialize-count N :serialize-latency-ms avg-ms}"
  []
  (let [m @metrics]
    (reset! metrics {:capture-count 0 :capture-total-ns 0
                     :hash-count 0 :hash-total-ns 0
                     :serialize-count 0 :serialize-total-ns 0})
    {:capture-count (:capture-count m)
     :capture-latency-ms (if (pos? (:capture-count m))
                           (/ (:capture-total-ns m) (:capture-count m) 1e6)
                           0)
     :hash-count (:hash-count m)
     :hash-latency-ms (if (pos? (:hash-count m))
                        (/ (:hash-total-ns m) (:hash-count m) 1e6)
                        0)
     :serialize-count (:serialize-count m)
     :serialize-latency-ms (if (pos? (:serialize-count m))
                             (/ (:serialize-total-ns m) (:serialize-count m) 1e6)
                             0)}))

(defn- record-capture-latency! [start-ns]
  (swap! metrics update :capture-count inc)
  (swap! metrics update :capture-total-ns + (- (System/nanoTime) start-ns)))

(defn- record-hash-latency! [start-ns]
  (swap! metrics update :hash-count inc)
  (swap! metrics update :hash-total-ns + (- (System/nanoTime) start-ns)))

(defn- record-serialize-latency! [start-ns]
  (swap! metrics update :serialize-count inc)
  (swap! metrics update :serialize-total-ns + (- (System/nanoTime) start-ns)))

;; ── Chain Registry Integration ────────────────────────────────────────────────

(defn- normalize-for-chain
  "Add chain-compatible keys from namespaced evidence fields."
  [evidence]
  (cond-> evidence
    (:evidence/hash evidence) (assoc :evidence-hash (:evidence/hash evidence))
    (:evidence/type evidence) (assoc :artifact-kind (:evidence/type evidence))))

(defn- validate-attribution!
  "Log a warning if required attribution keys are missing.
   Only warns when a scenario context is active (scenario-id present) —
   direct function calls outside the scenario runner have no run-id."
  [resolved-attr]
  (when (and (map? resolved-attr)
             (:ctx/scenario-id resolved-attr)
             (nil? (:ctx/run-id resolved-attr)))
    (log/warn! "capture-event-evidence! called without :ctx/run-id in attribution — evidence will be orphaned"
               {:resolved-attr (dissoc resolved-attr :ctx/scenario-id)})))

;; ── Primary Capture API ──────────────────────────────────────────────────────

(defn capture-event-evidence!
  "Persist a structured evidence record for a critical transition.

   Two calling conventions:

   A) Positional (legacy, backward-compatible):
      (capture-event-evidence! :reason pre post inputs)
      (capture-event-evidence! :reason pre post inputs calc)
      (capture-event-evidence! :reason pre post inputs calc ctx-or-opts)

      Where ctx-or-opts is nil, an attribution context map, or an opts map
      with optional keys :attribution-context, :importance (default :core).

   B) Pre-built evidence map (preferred for Phase 3):
      (capture-event-evidence! finalized-evidence)

      Where finalized-evidence was built with cap-field / cap-fields,
      validated with require-fields, and finalized with finalize-evidence."
  ([reason pre post inputs]
   (capture-event-evidence! reason pre post inputs nil nil))
  ([reason pre post inputs calc]
   (capture-event-evidence! reason pre post inputs calc nil))
  ([reason pre post inputs calc ctx-or-opts]
   (let [capture-start (System/nanoTime)
         hash-opts? (and (map? ctx-or-opts)
                         (or (:world-before ctx-or-opts)
                             (:world-after ctx-or-opts)))
         resolved-attr (cond
                         (and (map? ctx-or-opts) (:attribution-context ctx-or-opts))
                         (attr/get-attribution (:attribution-context ctx-or-opts))
                         (and (map? ctx-or-opts) (not hash-opts?))
                         ctx-or-opts
                         (map? reason)
                         (attr/get-attribution reason)
                         :else
                         (attr/current-attribution))
         _ (validate-attribution! resolved-attr)
         importance (if (map? ctx-or-opts) (or (:importance ctx-or-opts) :core) :core)
         hash-start (System/nanoTime)
         before-hash (hc/hash-with-intent {:hash/intent :world-structure} pre)
         after-hash  (hc/hash-with-intent {:hash/intent :world-structure} post)
         _ (record-hash-latency! hash-start)
         e (-> (cap/evidence-base {:type reason :importance importance
                                   :ctx resolved-attr})
               (cap/cap-fields {:scenario/id     (:ctx/scenario-id resolved-attr)
                                :run/id          (:ctx/run-id resolved-attr)
                                :trial/id        (:ctx/trial-id resolved-attr)
                                :event/seq       (:ctx/event-index resolved-attr 0)
                                :replay/seed     (:ctx/replay-seed resolved-attr)
                                :oracle/cursor   (:ctx/oracle-cursor resolved-attr)
                                :oracle/mode     (:ctx/oracle-mode resolved-attr)
                                :oracle/fixture-id (:ctx/oracle-fixture-id resolved-attr)})
               (assoc :inputs inputs :pre-state pre :post-state post)
               (cond-> calc (assoc :calculation calc))
               (cap/cap-field :world/before-hash before-hash)
               (cap/cap-field :world/after-hash after-hash)
               (cond-> (and (map? ctx-or-opts) (:world-before ctx-or-opts))
                 (assoc :world/before-full-hash (hc/hash-with-intent {:hash/intent :world-structure} (:world-before ctx-or-opts)))
                 (and (map? ctx-or-opts) (:world-after ctx-or-opts))
                 (assoc :world/after-full-hash (hc/hash-with-intent {:hash/intent :world-structure} (:world-after ctx-or-opts)))
                 (:ctx/evidence-group-id resolved-attr)
                 (assoc :evidence/group-id (:ctx/evidence-group-id resolved-attr)
                        :evidence/layer :targeted-protocol))
               (cap/finalize-evidence)
               (chain/inject-chain-fields))
         serialize-start (System/nanoTime)
         out-dir  (str (evcfg/artifact-dir) "/event-evidence")
         filename (evidence-filename e)
         f        (io/file out-dir filename)]
     (.mkdirs (io/file out-dir))
     (spit f (json/write-str e {:key-fn qualified-key :indent true}))
     (verify-write! e f)
     (record-serialize-latency! serialize-start)
     (record-capture-latency! capture-start)
     (println "Captured event evidence:" (:evidence/type e) "hash:" (:evidence/hash e))
     (chain/register-evidence! (normalize-for-chain e))
     e))
  ([evidence]
   (let [capture-start (System/nanoTime)]
     (when (map? evidence)
       (let [evidence (cap/require-fields evidence)
             resolved-attr (attr/current-attribution)
             _ (validate-attribution! resolved-attr)
             group-id (:ctx/evidence-group-id attr/*attribution*)
             evidence (cond-> evidence
                        group-id (assoc :evidence/group-id group-id :evidence/layer :targeted-protocol))
             evidence (chain/inject-chain-fields evidence)
             serialize-start (System/nanoTime)
             out-dir  (str (evcfg/artifact-dir) "/event-evidence")
             filename (evidence-filename evidence)
             f        (io/file out-dir filename)]
         (.mkdirs (io/file out-dir))
         (spit f (json/write-str evidence {:key-fn qualified-key :indent true}))
         (verify-write! evidence f)
         (record-serialize-latency! serialize-start)
         (record-capture-latency! capture-start)
         (println "Captured event evidence:" (:evidence/type evidence) "hash:" (:evidence/hash evidence))
         (chain/register-evidence! (normalize-for-chain evidence))
         evidence)))))

;; ── Evidence Links Index ──────────────────────────────────────────────────────
;;
;; Post-run index that groups generic-trace and targeted-protocol evidence by
;; :evidence/group-id.  Lets researchers navigate from a dispatcher trace event
;; to all targeted evidence captured during that same replay event.

(defn- relative-path
  [root f]
  (try
    (str (.relativize (.toPath (io/file root)) (.toPath (io/file f))))
    (catch Exception _
      (.getName (io/file f)))))

(defn- generic-trace-layer? [layer]
  (or (= "generic-trace" layer)
      (= :generic-trace layer)))

(defn- evidence-artifact-summary
  [root f data]
  (let [attr (attr/nested-attribution data)
        field (fn [k] (or (get data k) (get attr k)))]
    (cond-> {:relative-path (relative-path root f)
             :hash (or (:evidence-hash data) (:evidence/hash data))
             :evidence/type (:evidence/type data)
             :evidence/layer (:evidence/layer data)
             :evidence/importance (:evidence/importance data)
             :evidence/mechanism (:evidence/mechanism data)
             :evidence/chain-seq (:evidence/chain-seq data)
             :evidence/chain-prev-hash (:evidence/chain-prev-hash data)
             :evidence/chain-self-hash (:evidence/chain-self-hash data)
             :scenario/id (:scenario/id data)
             :run/id (:run/id data)
             :trial/id (:trial/id data)
             :event/seq (:event/seq data)
             :subject/type (field :subject/type)
             :subject/id (field :subject/id)
             :action/type (field :action/type)
             :evidence/reason (field :evidence/reason)}
      ;; Legacy compatibility fields used by existing tests/callers.
      true (assoc :group-id (:evidence/group-id data)
                  :layer (:evidence/layer data)
                  :path (.getPath (io/file f))
                  :event-type (:evidence/type data)
                  :artifact-kind (:artifact-kind data)))))

(defn- group-diagnostics
  [artifacts degraded?]
  (let [generic-count (count (filter #(generic-trace-layer? (:evidence/layer %)) artifacts))
        targeted (remove #(generic-trace-layer? (:evidence/layer %)) artifacts)
        targeted-count (count targeted)
        hashes-present? (every? #(seq (:hash %)) artifacts)
        chain-fields-present? (and (seq targeted)
                                   (every? #(and (contains? % :evidence/chain-seq)
                                                 (contains? % :evidence/chain-prev-hash)
                                                 (contains? % :evidence/chain-self-hash))
                                           targeted))]
    {:artifact-count (count artifacts)
     :generic-trace-count generic-count
     :targeted-count targeted-count
     :has-generic-trace? (pos? generic-count)
     :has-targeted-evidence? (pos? targeted-count)
     :hashes-present? hashes-present?
     :chain-fields-present? (boolean chain-fields-present?)
     :status (cond
               degraded? :degraded
               (and (pos? generic-count) (pos? targeted-count)) :linked
               (and (pos? generic-count) (zero? targeted-count)) :no-targeted-evidence
               :else :partial)}))

(defn- attach-group-diagnostics
  [groups degraded?]
  (into {}
        (map (fn [[gid group]]
               [gid (assoc group :diagnostics (group-diagnostics (:artifacts group) degraded?))]))
        groups))

(defn build-evidence-links-index-v1
  "Build a versioned, forensic-friendly links index envelope. Best-effort:
   malformed artifacts are logged and counted in :read-errors instead of thrown."
  ([]
   (build-evidence-links-index-v1 (str (evcfg/artifact-dir) "/event-evidence")))
  ([dir]
   (let [dir-file (when dir (io/file dir))]
     (if-not (and dir-file (.isDirectory dir-file))
       (do (log/warn! :evidence-links-dir-not-found {:path dir})
           {:schema-version "evidence-links-index.v1"
            :generated-at (str (java.time.Instant/now))
            :artifact-root dir
            :group-count 0
            :artifact-count 0
            :degraded true
            :error :directory-not-found
            :read-errors 0
            :groups {}})
       (let [read-errors (atom [])
             files (filter #(.isFile %) (file-seq dir-file))
             artifacts (vec
                        (keep (fn [f]
                                (try
                                  (let [data (read-evidence-json f)
                                        gid (:evidence/group-id data)]
                                    (when gid
                                      {:group-id gid
                                       :summary (evidence-artifact-summary dir f data)}))
                                  (catch Exception e
                                    (log/warn! :evidence-read-failed
                                               {:path (str f) :error (.getMessage e)})
                                    (swap! read-errors conj {:path (relative-path dir f)
                                                             :error (.getMessage e)})
                                    nil)))
                              files))
             degraded? (pos? (count @read-errors))
             groups (attach-group-diagnostics
                     (reduce (fn [acc {:keys [group-id summary]}]
                               (update acc group-id
                                       (fn [group]
                                         (update (or group {:artifacts []})
                                                 :artifacts conj summary))))
                             {}
                             artifacts)
                     degraded?)]
         (cond-> {:schema-version "evidence-links-index.v1"
                  :generated-at (str (java.time.Instant/now))
                  :artifact-root dir
                  :group-count (count groups)
                  :artifact-count (count artifacts)
                  :degraded degraded?
                  :read-errors (count @read-errors)
                  :groups groups}
           degraded? (assoc :read-error-paths (mapv :path @read-errors))))))))

(defn build-evidence-links-index
  "Compatibility wrapper returning the legacy bare map keyed by group-id.
   Degradation metadata is preserved as top-level keys when read errors occur."
  ([]
   (build-evidence-links-index (str (evcfg/artifact-dir) "/event-evidence")))
  ([dir]
   (let [idx (build-evidence-links-index-v1 dir)]
     (cond-> (:groups idx)
       (:degraded idx) (assoc :degraded true
                              :read-errors (:read-errors idx)
                              :read-error-paths (:read-error-paths idx))))))

(defn write-evidence-links-index-v1!
  "Build and persist the versioned evidence links index envelope to evidence-links.json.
   Also registers the index in the chain registry for test-artifacts.json.
   Returns the path to the written file."
  [& [dir]]
  (let [idx (build-evidence-links-index-v1 dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-links.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str idx {:key-fn qualified-key :indent true}))
    (println "Wrote evidence links index:" (.getPath f))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-links "evidence-links.json"
                                 "evidence-links-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

(defn write-evidence-links-index!
  "Compatibility writer: persists the legacy bare links map to evidence-links.json.
   New forensic consumers should prefer write-evidence-links-index-v1!."
  [& [dir]]
  (let [idx (build-evidence-links-index dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-links.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str idx {:key-fn qualified-key :indent true}))
    (println "Wrote evidence links index:" (.getPath f))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-links "evidence-links.json"
                                 "evidence-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

;; ── Content Hash Verification Helper ──────────────────────────────────────────

;; ── Post-hoc Chain Integrity Verification ──────────────────────────────────────
;;
;; Scans all targeted evidence artifacts in a directory, validates internal
;; hash consistency and chain linking.  Detects missing, inserted, or
;; tampered artifacts.

(defn verify-chain-integrity
  "Verify the evidence chain from artifacts alone.

   Reads all artifacts, sorts by :evidence/chain-seq, and checks:
   1. Each artifact's evidence-hash matches a re-computed hash of its content
      (excluding :evidence/hash, :evidence/chain-self-hash, :evidence/chain-prev-hash)
   2. Each artifact's :evidence/chain-prev-hash matches the previous artifact's
      :evidence/chain-self-hash (or :evidence/hash)
   3. No gaps in :evidence/chain-seq numbering

   Returns a map:
   :valid       — true if all checks pass
   :artifact-count — total artifacts with chain fields
   :content-hash-valid — count of artifacts with content-verified hashes
   :prev-hash-valid — count of artifacts with matching prev->previous link
   :gaps        — list of missing chain-seq numbers
   :violations  — list of {:chain-seq N :reason ...} maps

   Throws if artifact-dir does not exist (fail-closed)."
  [& [artifact-dir]]
  (let [dir (or artifact-dir (str (evcfg/artifact-dir) "/event-evidence"))
        dir-file (io/file dir)]
    (when-not (.isDirectory dir-file)
      (throw (ex-info "Cannot verify chain integrity: artifact directory not found"
                      {:path (str dir)})))
    (let [artifacts (sort-by :evidence/chain-seq
                             (keep (fn [f]
                                     (try (read-evidence-json f)
                                          (catch Exception e
                                            (throw (ex-info "Failed to read evidence artifact"
                                                            {:path (str f) :error (.getMessage e)})))))
                                   (filter #(.isFile %) (file-seq dir-file))))
          with-chain (filter :evidence/chain-seq artifacts)
          sorted (sort-by :evidence/chain-seq with-chain)
          ;; Check 1: content hash — re-compute hash from artifact content
          content-ok (filter (fn [a]
                               (let [content (dissoc a :evidence/hash
                                                     :evidence/chain-self-hash
                                                     :evidence/chain-prev-hash)
                                     expected (hc/hash-with-intent {:hash/intent :evidence-content} content)]
                                 (= expected (:evidence/hash a))))
                             sorted)
          content-bad (remove (fn [a]
                                (let [content (dissoc a :evidence/hash
                                                      :evidence/chain-self-hash
                                                      :evidence/chain-prev-hash)
                                      expected (hc/hash-with-intent {:hash/intent :evidence-content} content)]
                                  (hc/intent-hash= expected (:evidence/hash a))))
                              sorted)
          ;; Check 2: prev-hash links to previous artifact
          prev-results (mapv (fn [a prev]
                               {:artifact a
                                :valid (hc/intent-hash= (:evidence/chain-prev-hash a)
                                                        (or (:evidence/chain-self-hash prev)
                                                            (:evidence/hash prev)))})
                             sorted (cons nil sorted))
          prev-valid (count (filter :valid (rest prev-results)))
          prev-bad (remove :valid (rest prev-results))
          ;; Check 3: sequence gaps
          seqs (sort (map :evidence/chain-seq sorted))
          gaps (if (seq seqs)
                 (let [expected (range (first seqs) (inc (last seqs)))]
                   (remove (set seqs) expected))
                 [])
          violations (concat
                      (map (fn [a] {:chain-seq (:evidence/chain-seq a)
                                    :reason "Content hash mismatch — evidence content has been modified"})
                           content-bad)
                      (map (fn [r] {:chain-seq (:evidence/chain-seq (:artifact r))
                                    :reason (str "Prev-hash mismatch: "
                                                 (:evidence/chain-prev-hash (:artifact r))
                                                 " does not link to previous")})
                           prev-bad)
                      (map (fn [g] {:chain-seq g
                                    :reason (str "Gap in chain: seq " g " missing")})
                           gaps))]
      {:valid (and (empty? content-bad) (empty? prev-bad) (empty? gaps))
       :artifact-count (count sorted)
       :content-hash-valid (count content-ok)
       :content-hash-failed (count content-bad)
       :prev-hash-valid prev-valid
       :prev-hash-failed (count prev-bad)
       :gaps (vec gaps)
       :violations (vec violations)})))

;; ── Scenario Evidence Verification ────────────────────────────────────────────
;;
;; Scenarios may declare :expected-evidence — an array of evidence types the
;; scenario is expected to produce.  verify-scenario-evidence compares the
;; declared expectations against the captured artifacts.
;;
;; Example scenario metadata:
;;   :expected-evidence [{:type "escrow-created", :importance :core}
;;                       {:type "escrow-released", :importance :core}]

(defn verify-scenario-evidence
  "Compare :expected-evidence declared on a scenario against captured artifacts.

   scenario — a scenario map (after normalization) with optional :expected-evidence
   artifact-dir — path to the event-evidence directory

   Returns a map:
   :declared   — the full :expected-evidence vector (nil if absent)
   :matched    — entries that were found in captured artifacts
   :missing    — expected entries not found
   :unexpected — captured evidence types not declared as expected
   :degraded   — true if some artifacts could not be read (partial results)

   When the scenario has no :expected-evidence, returns nil.
   Returns {:degraded true :error :directory-not-found} when dir does not exist."
  [scenario & [artifact-dir]]
  (let [expected (:expected-evidence scenario)
        dir (or artifact-dir (str (evcfg/artifact-dir) "/event-evidence"))
        dir-file (io/file dir)]
    (when (seq expected)
      (if-not (.isDirectory dir-file)
        (do (log/warn! :verify-evidence-dir-not-found {:path dir})
            {:declared expected :matched [] :missing expected :unexpected #{}
             :degraded true :error :directory-not-found})
        (let [results (keep (fn [f]
                              (try (read-evidence-json f)
                                   (catch Exception e
                                     (log/warn! :evidence-read-failed
                                                {:path (str f) :error (.getMessage e)})
                                     nil)))
                            (filter #(.isFile %) (file-seq dir-file)))
              captured-types (into #{} (keep :evidence/type) results)
              expected-types (set (map :type expected))
              read-errors (count (filter (fn [f]
                                           (try (read-evidence-json f) false
                                                (catch Exception _ true)))
                                         (filter #(.isFile %) (file-seq dir-file))))]
          (cond-> {:declared expected
                   :matched (filter #(contains? captured-types (:type %)) expected)
                   :missing (remove #(contains? captured-types (:type %)) expected)
                   :unexpected (remove #(contains? expected-types %) captured-types)}
            (pos? read-errors) (assoc :degraded true :read-errors read-errors)))))))

(defn- read-all-artifacts
  "Read all valid evidence artifacts from a directory.
   Returns a vector of parsed maps, each with :path added.
   Corrupt or unparseable files are logged at :warn and skipped."
  [dir]
  (vec (keep (fn [f]
               (try (let [d (read-evidence-json f)]
                      (assoc d :path (.getPath f)))
                    (catch Exception e
                      (log/warn! :evidence-read-failed
                                 {:path (str f) :error (.getMessage e)})
                      nil)))
             (filter #(.isFile %) (file-seq (io/file dir))))))

;; ── Cross-Run Evidence Diff ───────────────────────────────────────────────────
;;
;; Compare evidence from two runs (or directories) to find added, missing, and
;; changed artifacts.  Useful for regression analysis.

(defn diff-evidence-directories
  "Compare evidence artifacts between two directories.

   dir-a — baseline directory (\"before\")
   dir-b — comparison directory (\"after\")

   Returns a map:
   :added       — artifacts in B not in A
   :missing     — artifacts in A not in B
   :changed     — artifacts in both but with different content (by evidence-hash)
   :unchanged   — artifacts with same evidence-hash in both
   :summary     — {:a-only N, :b-only N, :common N, :changed N, :unchanged N}"
  [dir-a dir-b]
  (let [a (read-all-artifacts dir-a)
        b (read-all-artifacts dir-b)
        hash-a (into {} (map (fn [a] [(:evidence/hash a) a]) a))
        hash-b (into {} (map (fn [a] [(:evidence/hash a) a]) b))
        hashes-a (set (keys hash-a))
        hashes-b (set (keys hash-b))
        common (set/intersection hashes-a hashes-b)
        a-only (set/difference hashes-a hashes-b)
        b-only (set/difference hashes-b hashes-a)]
    {:added (sort-by :evidence/chain-seq (vals (select-keys hash-b b-only)))
     :missing (sort-by :evidence/chain-seq (vals (select-keys hash-a a-only)))
     :changed (sort-by :evidence/chain-seq
                       (filter (fn [h] (not= (dissoc (get hash-a h) :path)
                                             (dissoc (get hash-b h) :path)))
                               common))
     :unchanged (sort-by :evidence/chain-seq (vals (select-keys hash-b common)))
     :summary {:a-only (count a-only) :b-only (count b-only)
               :common (count common) :unchanged (count common)
               :changed (count (filter (fn [h] (not= (get hash-a h) (get hash-b h))) common))}}))

;; ── Evidence Bundle Export ────────────────────────────────────────────────────
;;
;; Export a set of evidence artifacts (by group-id) into a portable directory
;; for sharing with collaborators.

(defn export-evidence-bundle
  "Copy evidence artifacts matching the given group-ids into an output directory.

   group-ids   — one or more :evidence/group-id strings to filter by
   output-dir  — directory to write the bundle into
   artifact-dir   — source event-evidence directory (optional)

   Returns the path to output-dir.

   The bundle directory contains:
   - All matching JSON artifacts (renamed to chain-seq for stable ordering)
   - manifest.json with source metadata and artifact list"
  [group-ids output-dir & [artifact-dir]]
  (let [src (or artifact-dir (str (evcfg/artifact-dir) "/event-evidence"))
        gids (set (if (coll? group-ids) group-ids [group-ids]))
        files (filter #(.isFile %) (file-seq (io/file src)))
        matched (keep (fn [f]
                        (try (let [d (read-evidence-json f)]
                               (when (contains? gids (:evidence/group-id d))
                                 {:data d :file f}))
                             (catch Exception e
                               (log/warn! :evidence-bundle-export-skipped
                                          {:path (str f) :error (.getMessage e)})
                               nil)))
                      files)]
    (.mkdirs (io/file output-dir))
    (doseq [{:keys [data file]} (sort-by (comp :evidence/chain-seq :data) matched)]
      (let [seq-no (or (:evidence/chain-seq data) 0)
            etype (:evidence/type data)
            fname (str (format "%04d" seq-no) "-" (name (keyword etype)) ".json")]
        (io/copy file (io/file output-dir fname))))
    ;; Write manifest
    (let [manifest {:schema-version "evidence-bundle.v1"
                    :exported-at (str (java.time.Instant/now))
                    :group-ids (vec gids)
                    :artifact-count (count matched)
                    :artifacts (vec (for [{:keys [data]} (sort-by (comp :evidence/chain-seq :data) matched)]
                                      {:group-id (:evidence/group-id data)
                                       :evidence-type (:evidence/type data)
                                       :chain-seq (:evidence/chain-seq data)
                                       :evidence-hash (:evidence/hash data)}))}]
      (spit (io/file output-dir "manifest.json")
            (json/write-str manifest {:key-fn qualified-key :indent true})))
    (println "Exported" (count matched) "evidence artifacts to" output-dir)
    output-dir))

(defn linked-evidence-group
  "Researcher-facing helper for inspecting one replay event.

   Given an :evidence/group-id, returns the generic transition trace and
   all targeted evidence artifacts captured for that same event.

   This function does not create or modify links. It is a read-only projection
   over existing event-evidence files.

   Returns nil when no artifacts exist for the group-id.

   Given an :evidence/group-id, finds every artifact in the event-evidence directory
   whose :evidence/group-id matches.  Returns nil if no artifacts are found.

   Returns a map with:
   :group-id       — the supplied group-id
   :generic-trace  — the artifact whose :evidence/layer is \"generic-trace\" or :generic-trace, or nil
   :targeted       — vector of all non-generic-trace artifacts, sorted by :evidence/chain-seq
   :diagnostics    — light-weight summary:
                     :generic-trace-found?  boolean
                     :targeted-count        number
                     :artifact-count        total number of artifacts in the group

   Malformed JSON files are silently skipped.

   Usage:
     (linked-evidence-group \"S19-run:3:raise_dispute\")
     (linked-evidence-group \"S19-run:3:raise_dispute\" \"/path/to/event-evidence\")"
  [group-id & [artifact-dir]]
  (let [dir (or artifact-dir (str (evcfg/artifact-dir) "/event-evidence"))
        files (when (.isDirectory (io/file dir))
                (filter #(.isFile %) (file-seq (io/file dir))))
        artifacts (keep (fn [f]
                          (try (read-evidence-json f)
                               (catch Exception e
                                 (log/warn! :linked-evidence-read-failed
                                            {:path (str f) :error (.getMessage e)})
                                 nil)))
                        files)
        grouped (filter #(= (:evidence/group-id %) group-id) artifacts)]
    (when (seq grouped)
      (let [generic (some #(when (or (= "generic-trace" (:evidence/layer %))
                                     (= :generic-trace (:evidence/layer %)))
                             %)
                          grouped)
            targeted (vec (sort-by :evidence/chain-seq
                                   (remove #(= % generic) grouped)))]
        {:group-id group-id
         :generic-trace generic
         :targeted targeted
         :diagnostics {:generic-trace-found? (some? generic)
                       :targeted-count (count targeted)
                       :artifact-count (count grouped)}}))))

(def evidence-type->mechanism
  {:stake-registered :staking
   :stake-withdrawn :staking
   :slashing :slashing
   :incentive-payout :bonding
   :escrow-created :escrow-lifecycle
   :escrow-released :escrow-lifecycle
   :escrow-refunded :escrow-lifecycle
   :dispute-raised :dispute-resolution
   :dispute-escalated :dispute-resolution
   :resolution-challenged :dispute-resolution
   :fraud-slash-proposed :slashing
   :fraud-slash-appealed :slashing
   :slash-appeal/reversed :slashing
   :slash-appeal/rejected :slashing
   :bond-posted :bonding
   :bond-slashed :bonding
   :bond-returned :bonding
   :yield-accrual :yield
   :settlement-fill :settlement
   :resolver-unfrozen :system
   :resolver-unavailability-changed :system})

(defn mechanism-for-type
  "Look up the mechanism group for an evidence type.
   Accepts keyword or string. Falls back to :uncategorized."
  [etype]
  (let [k (if (keyword? etype) etype (keyword etype))]
    (or (evidence-type->mechanism k) :uncategorized)))

;; ── Query API ─────────────────────────────────────────────────────────────────
;;
;; Researcher-facing discovery API for targeted evidence artifacts.
;; All filters are optional and AND-combined.
;;
;; Usage:
;;   (find-evidence :by-type :escrow-created)
;;   (find-evidence :by-workflow 42 :by-chain-seq {:from 1 :to 10})
;;   (find-evidence :by-group-id "S19-run:0:create_escrow" :include-body? true)

(defn- artifact-summary
  "Produce a lightweight summary of an evidence artifact.
   Does NOT include the full body — researcher must opt in via :include-body?."
  [data path]
  (let [etype (:evidence/type data)]
    (merge
     {:id (some-> (:evidence/chain-self-hash data)
                  (subs 0 16)
                  (str "ev-"))
      :evidence/type etype
      :evidence/mechanism (mechanism-for-type etype)
      :evidence/chain-seq (:evidence/chain-seq data)
      :evidence/group-id (:evidence/group-id data)
      :evidence/layer (:evidence/layer data)
      :evidence/hash (or (:evidence-hash data) (:evidence/hash data))
      :path path}
      ;; Include subject keys when present
     (when-let [sid (:subject/id data)] {:subject/id sid})
     (when-let [stype (:subject/type data)] {:subject/type stype})
      ;; Include any workflow-id from domain payload
     (some (fn [k] (when-let [v (get data k)] {k v}))
           [:escrow/workflow-id :finalize/workflow-id :bond/workflow-id
            :dispute/workflow-id :appeal/slash-id :appeal-resolution/slash-id
            :proposal/workflow-id :unfreeze/resolver
            :challenge/workflow-id :escalation/workflow-id]))))

(defn- matches-filter?
  "Check a single evidence artifact map against one filter."
  [data filter-type filter-val]
  (case filter-type
    :by-type
    (= (name filter-val) (:evidence/type data))

    :by-mechanism
    (= filter-val (mechanism-for-type (keyword (:evidence/type data))))

    :by-workflow
    (let [w (and (number? filter-val) filter-val)]
      (some (fn [k] (= w (get data k)))
            [:escrow/workflow-id :finalize/workflow-id :bond/workflow-id
             :dispute/workflow-id :appeal/slash-id :appeal-resolution/slash-id
             :proposal/workflow-id]))

    :by-group-id
    (= filter-val (:evidence/group-id data))

    :by-chain-seq
    (let [s (:evidence/chain-seq data)]
      (and (number? s)
           (or (nil? (:from filter-val)) (>= s (:from filter-val)))
           (or (nil? (:to filter-val)) (<= s (:to filter-val)))))

    :by-run-id
    (= filter-val (:run/id data))

    :by-scenario-id
    (= filter-val (:scenario/id data))

    true))

(defn find-evidence
  "Search targeted evidence artifacts matching all given filters.
   Filters are AND-combined. Returns a vector of lightweight summaries.

   Optional keyword arguments:
   :by-type        — evidence type keyword or string
   :by-mechanism   — mechanism keyword (e.g. :slashing, :escrow-lifecycle)
   :by-workflow    — workflow-id integer
   :by-group-id    — evidence group-id string
   :by-chain-seq   — {:from N :to M} map (inclusive)
   :by-run-id      — run-id string
   :by-scenario-id — scenario-id string
   :include-body?  — boolean, include full parsed artifact body
   :artifact-dir   — override artifact directory path

   Examples:
     (find-evidence :by-type :escrow-created)
     (find-evidence :by-workflow 42)
     (find-evidence :by-chain-seq {:from 1 :to 10} :include-body? true)"
  [& {:keys [by-type by-mechanism by-workflow by-group-id by-chain-seq
             by-run-id by-scenario-id include-body? artifact-dir]
      :or {artifact-dir (str (evcfg/artifact-dir) "/event-evidence")
           include-body? false}}]
  (let [filters (concat
                 (when by-type [:by-type by-type])
                 (when by-mechanism [:by-mechanism by-mechanism])
                 (when by-workflow [:by-workflow by-workflow])
                 (when by-group-id [:by-group-id by-group-id])
                 (when by-chain-seq [:by-chain-seq by-chain-seq])
                 (when by-run-id [:by-run-id by-run-id])
                 (when by-scenario-id [:by-scenario-id by-scenario-id]))]
    (if-let [dir (io/file artifact-dir)]
      (if (.isDirectory dir)
        (let [files (filter #(.isFile %) (file-seq dir))]
          (keep (fn [f]
                  (try
                    (let [data (read-evidence-json f)
                          matches (every? (fn [[ft fv]] (matches-filter? data ft fv))
                                          (partition 2 filters))]
                      (when matches
                        (if include-body?
                          (assoc data :path (.getPath f))
                          (artifact-summary data (.getPath f)))))
                    (catch Exception e
                      (log/warn! :find-evidence-read-failed
                                 {:path (str f) :error (.getMessage e)})
                      nil)))
                files))
        [])
      [])))

;; ── Mechanism Index ───────────────────────────────────────────────────────────
;;
;; Groups evidence artifacts by mechanism for quick researcher discovery.
;; Written post-run alongside evidence-links.json.

(defn build-mechanism-index
  "Group targeted evidence artifacts by mechanism.
   Each group entry includes count and artifact summaries.
   Mechanism is derived from :evidence/mechanism field if present,
   falling back to evidence-type->mechanism mapping."
  [& [dir]]
  (let [files (filter #(.isFile %) (file-seq (io/file (or dir (str (evcfg/artifact-dir) "/event-evidence")))))
        entries (keep (fn [f]
                        (try
                          (let [data (read-evidence-json f)
                                etype (:evidence/type data)
                                mech (or (:evidence/mechanism data)
                                         (mechanism-for-type (keyword etype)))]
                            (when mech
                              {:mechanism mech
                               :summary (artifact-summary data (.getPath f))}))
                          (catch Exception e
                            (log/warn! :mechanism-index-read-failed
                                       {:path (str f) :error (.getMessage e)})
                            nil)))
                      files)]
    (reduce (fn [acc entry]
              (let [mech (:mechanism entry)
                    sum (:summary entry)]
                (-> acc
                    (update-in [mech]
                               (fnil #(update % :artifacts conj sum)
                                     {:mechanism mech
                                      :count 0
                                      :artifacts []}))
                    (update-in [mech :count] (fnil inc 0)))))
            {} entries)))

(defn write-mechanism-index!
  "Build and persist the mechanism index to evidence-mechanisms.json.
   Also registers the index in the chain registry for test-artifacts.json.
   Returns the path to the written file."
  [& [dir]]
  (let [idx (build-mechanism-index dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-mechanisms.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str (sort-by key idx) {:key-fn qualified-key :indent true}))
    (println "Wrote mechanism index:" (.getPath f))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-mechanisms "evidence-mechanisms.json"
                                 "evidence-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

;; ── Coverage Report ───────────────────────────────────────────────────────────
;;
;; Post-run report that compares generic trace events, targeted evidence,
;; and evidence links to identify coverage gaps.

(defn- read-evidence-links-index
  "Read evidence-links.json from the artifact directory.
   Returns nil if the file does not exist or cannot be parsed."
  [dir]
  (let [f (io/file dir "evidence-links.json")]
    (when (.exists f)
      (try (json/read-str (slurp f) :key-fn keyword)
           (catch Exception e
             (log/warn! :evidence-links-index-corrupt
                        {:path (str f) :error (.getMessage e)})
             nil)))))

(defn- generic-trace-event-count
  "Approximate count of generic trace events from evidence-registry.json.
   Uses :artifact-kind :transition entries in the registry.
   Returns 0 on error — sets :degraded true on the coverage report."
  [dir]
  (let [f (io/file dir "evidence-registry.json")]
    (if (.exists f)
      (try
        (let [reg (json/read-str (slurp f) :key-fn keyword)]
          {:count (count (:artifacts reg))})
        (catch Exception e
          (log/warn! :registry-corrupt
                     {:path (str f) :error (.getMessage e)})
          {:count 0 :degraded true}))
      {:count 0})))

(defn- check-expected-evidence
  "Scan scenario files for :expected-evidence declarations and verify them
   against captured evidence artifacts.

   scenarios-dir — directory containing scenario JSON files
   artifact-dir  — directory containing event-evidence/ subdirectory

   Returns a map:
   :scenarios-checked — count of scenarios that declare :expected-evidence
   :all-matched?      — true if every declared type was captured
   :checked          — per-scenario results [{:scenario file :matched [...] :missing [...]}]
   or nil when scenarios-dir is not a directory."
  [scenarios-dir artifact-dir]
  (let [sd (io/file scenarios-dir)]
    (when (.isDirectory sd)
      (let [scenario-files (filter #(or (.endsWith (.getName %) ".edn")
                                        (.endsWith (.getName %) ".json"))
                                   (.listFiles sd))
            ev-dir (str artifact-dir "/event-evidence")
            results (keep (fn [f]
                            (try (let [scenario (json/read-str (slurp f) :key-fn keyword)
                                       expected (:expected-evidence scenario)]
                                   (when (seq expected)
                                     (let [v (verify-scenario-evidence scenario ev-dir)]
                                       {:scenario (.getName f)
                                        :declared (map :type expected)
                                        :matched (mapv :type (:matched v))
                                        :missing (mapv :type (:missing v))
                                        :all-ok? (empty? (:missing v))})))
                                 (catch Exception e
                                   (log/warn! :coverage-expected-evidence-read-failed
                                              {:path (.getName f) :error (.getMessage e)})
                                   nil)))
                          scenario-files)
            checked (vec results)]
        (when (seq checked)
          (let [missing-counts (map (fn [c] (count (:missing c))) checked)]
            {:scenarios-checked (count checked)
             :all-matched? (every? :all-ok? checked)
             :checked checked
             :total-missing-count (reduce + 0 missing-counts)}))))))

(defn build-evidence-coverage-report
  "Analyze evidence artifacts in a run directory and produce a coverage report.

   The report includes:
   - Total events with generic trace evidence
   - Events with targeted protocol evidence
   - Mechanism counts (number of artifacts per mechanism)
   - Missing group-ids (events with generic trace but no targeted evidence)
   - Expected-evidence verification against scenario declarations
   - Warnings about potential gaps

   Accepts an optional map with:
   - :dir          — run artifact directory (default: evcfg/artifact-dir)
   - :scenarios-dir — path to scenario JSON files (default: \"scenarios\" in project root)

   Returns a structured map suitable for JSON serialization."
  [& [opts]]
  (let [{:keys [dir scenarios-dir]
         :or {dir (str (evcfg/artifact-dir))
              scenarios-dir "scenarios"}} (if (map? opts) opts {:dir opts})
        artifact-dir dir
        ev-dir (str artifact-dir "/event-evidence")
        generic-result (generic-trace-event-count artifact-dir)
        generic-count (:count generic-result)
        links (read-evidence-links-index artifact-dir)
        targeted-events (when links (count links))
        ;; Count by mechanism using the mechanism index
        mech-idx (build-mechanism-index ev-dir)
        mechanism-counts (into {} (map (fn [[k v]] [k (:count v)]) mech-idx))
        ;; Build targeted type counts
        read-errors (atom 0)
        type-counts (let [files (filter #(.isFile %) (file-seq (io/file ev-dir)))]
                      (reduce (fn [acc f]
                                (try
                                  (let [data (read-evidence-json f)
                                        etype (:evidence/type data)]
                                    (if etype
                                      (update-in acc [(name (keyword etype))] (fnil inc 0))
                                      acc))
                                  (catch Exception e
                                    (swap! read-errors inc)
                                    (log/warn! :coverage-report-read-failed
                                               {:path (str f) :error (.getMessage e)})
                                    acc)))
                              {} files))
        total-targeted (reduce + 0 (vals type-counts))
        ;; Check expected-evidence against scenario declarations
        expected-evidence-check (check-expected-evidence scenarios-dir artifact-dir)
        degraded (or (:degraded generic-result) (pos? @read-errors))]
    (cond-> {:run-directory artifact-dir
             :generic-trace-event-count generic-count
             :targeted-evidence-count total-targeted
             :targeted-group-count (or targeted-events 0)
             :mechanism-counts mechanism-counts
             :type-counts type-counts
             :links-index-present? (some? links)
             :expected-evidence-check expected-evidence-check
             :warnings
             (cond-> []
               (zero? generic-count)
               (conj "No generic trace artifacts found — evidence-registry.json may be missing")
               (zero? total-targeted)
               (conj "No targeted evidence artifacts found — event-evidence directory may be empty")
               (and links (zero? targeted-events))
               (conj "Evidence links index exists but contains no group-ids")
               (and expected-evidence-check (not (:all-matched? expected-evidence-check)))
               (conj (str "Expected-evidence check failed: "
                          (:total-missing-count expected-evidence-check)
                          " declared evidence type(s) missing across "
                          (:scenarios-checked expected-evidence-check)
                          " scenario(s)")))}
      degraded (assoc :degraded true :read-errors @read-errors))))

(defn write-evidence-coverage-report!
  "Build and persist the evidence coverage report to evidence-coverage-report.json.
   Also registers the report in the chain registry for test-artifacts.json.

   Optional args (varargs or single map):
   - :dir           — run artifact directory
   - :scenarios-dir — path to scenario JSON files

   Returns the path to the written file."
  [& args]
  (let [opts (cond
               (map? (first args)) (first args)
               (= 1 (count args)) {:dir (first args)}
               :else (apply hash-map args))
        dir (:dir opts)
        report (build-evidence-coverage-report opts)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-coverage-report.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str report {:key-fn qualified-key :indent true}))
    (println "Wrote evidence coverage report:" (.getPath f))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-coverage-report "evidence-coverage-report.json"
                                 "evidence-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

;; ── Wire up capture implementation for protocol layers ──────────────────────
;; Load-time wiring: injects this namespace's capture-event-evidence!
;; into the dynamic var in resolver-sim.evidence.capture.
;; This is startup registration, NOT test isolation — safe to keep
;; alter-var-root because it runs once at load, not during parallel tests.
;;
;; If this causes issues with parallel test isolation, the dynamic var
;; *capture-event-evidence!* in capture.clj should use the same binding[]
;; pattern as the with-fresh-registry macros.

(let [v (find-var 'resolver-sim.evidence.capture/*capture-event-evidence!*)]
  (when v
    (alter-var-root v (constantly (var-get #'capture-event-evidence!)))))
