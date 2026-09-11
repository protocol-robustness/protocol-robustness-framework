(ns resolver-sim.evidence.registry-validation
  "Validation checks for evidence-registry.json.
   
   Runs post-build and produces evidence-registry-validation.json.
   
   Checks are classified:
     :required    — must pass or validation fails
     :recommended — warning emitted, validation still passes
     :diagnostic  — logged only, no validation impact
   
   Usage:
     (validate-evidence-registry! registry dir)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.registry :as reg]
            [resolver-sim.io.event-evidence :as io-evidence]))

;; ── Check Runner ─────────────────────────────────────────────────────────────

(defn- contained-file
  "Resolve a registry-relative path only when it remains under artifact-dir."
  [artifact-dir relative-path]
  (when (and (string? relative-path)
             (not (.isAbsolute (io/file relative-path))))
    (let [root (.getCanonicalFile (io/file artifact-dir))
          candidate (.getCanonicalFile (io/file root relative-path))]
      (when (.startsWith (.toPath candidate) (.toPath root)) candidate))))

(defn- content-hash-valid?
  [artifact-dir entry]
  (when-let [file (contained-file artifact-dir (:file/path entry))]
    (when (.isFile file)
      (try
        (= (:hash/content entry)
           (or (:evidence/hash (io-evidence/read-evidence-json file))
               (:evidence-hash (io-evidence/read-evidence-json file))))
        (catch Exception _ false)))))

(defn- check-required
  "Run all required checks. Returns a vector of check result maps."
  [registry artifact-dir]
  (let [entries (:entries registry)
        ;; Every entry has :evidence/id
        c1 (every? :evidence/id entries)
        ;; Every entry has :evidence/type
        c2 (every? :evidence/type entries)
        ;; Every entry has :hash/content
        c3 (every? :hash/content entries)
        ;; Every entry has :file/path
        c4 (every? :file/path entries)
        ;; Every path is relative, contained, and names an existing file.
        c5 (every? (fn [e] (some-> (contained-file artifact-dir (:file/path e)) .isFile)) entries)
        ;; No duplicate IDs
        c6 (let [ids (map :evidence/id entries)]
             (= (count ids) (count (set ids))))
        ;; Registry content hash must match evidence currently on disk.
        c7 (every? #(content-hash-valid? artifact-dir %) entries)]
    [{:id "every-entry-has-id" :status (if c1 :passed :failed) :detail {:count (count entries) :missing (count (remove :evidence/id entries))}}
     {:id "every-entry-has-type" :status (if c2 :passed :failed) :detail {:count (count entries) :missing (count (remove :evidence/type entries))}}
     {:id "every-entry-has-content-hash" :status (if c3 :passed :failed) :detail {:count (count entries) :missing (count (remove :hash/content entries))}}
     {:id "every-entry-has-path" :status (if c4 :passed :failed) :detail {:count (count entries) :missing (count (remove :file/path entries))}}
     {:id "every-path-exists-and-is-contained" :status (if c5 :passed :failed) :detail {:entries (count entries) :invalid (count (remove (fn [e] (some-> (contained-file artifact-dir (:file/path e)) .isFile)) entries))}}
     {:id "no-duplicate-evidence-ids" :status (if c6 :passed :failed) :detail {:total (count entries) :unique (count (set (map :evidence/id entries)))}}
     {:id "every-content-hash-matches-file" :status (if c7 :passed :failed) :detail {:entries (count entries) :mismatched (count (remove #(content-hash-valid? artifact-dir %) entries))}}]))

(defn- check-attribution
  "Run attribution completeness checks. Returns a vector of check result maps.
   These were previously :recommended but are now :required."
  [registry]
  (let [entries (:entries registry)
        targeted (filter #(= :targeted-protocol (:evidence/layer %)) entries)
        w1 (remove :scenario/id entries)
        w2 (remove :run/id entries)
        w3 (remove :event/index entries)
        w4 (remove :subject/type targeted)
        w5 (remove :subject/id targeted)
        w6 (remove :action/type targeted)]
    [{:id "entries-have-scenario-id" :status (if (empty? w1) :passed :failed) :detail {:total (count entries) :missing (count w1)}}
     {:id "entries-have-run-id" :status (if (empty? w2) :passed :failed) :detail {:total (count entries) :missing (count w2)}}
     {:id "entries-have-event-index" :status (if (empty? w3) :passed :failed) :detail {:total (count entries) :missing (count w3)}}
     {:id "targeted-entries-have-subject-type" :status (if (empty? w4) :passed :failed) :detail {:targeted (count targeted) :missing (count w4)}}
     {:id "targeted-entries-have-subject-id" :status (if (empty? w5) :passed :failed) :detail {:targeted (count targeted) :missing (count w5)}}
     {:id "targeted-entries-have-action-type" :status (if (empty? w6) :passed :failed) :detail {:targeted (count targeted) :missing (count w6)}}]))

(defn- check-recommended
  "Run all recommended checks. Returns a vector of check result maps with :warning status.
   Attribution completeness checks (scenario-id, run-id, event-index, subject, action)
   moved to check-required — only ancillary checks remain here."
  [registry]
  (let [entries (:entries registry)
        targeted (filter #(= :targeted-protocol (:evidence/layer %)) entries)
        w1 (remove :evidence/reason targeted)
        gids (set (keep :evidence/group-id entries))
        group-idx (:by-group-id (:indexes registry))
        gid-ok (every? (fn [gid] (seq (get group-idx gid))) gids)]
    [{:id "targeted-entries-have-reason" :status (if (empty? w1) :passed :warning) :detail {:targeted (count targeted) :missing (count w1)}}
     {:id "group-ids-resolve" :status (if gid-ok :passed :warning) :detail {:group-ids (count gids) :unresolved (count (remove (fn [gid] (seq (get group-idx gid))) gids))}}]))

(defn- check-diagnostic
  "Run diagnostic checks. Returns a vector of informative entries."
  [registry]
  (let [entries (:entries registry)
        ;; World hashes present
        wh-before (count (remove :hash/world-before entries))
        wh-after (count (remove :hash/world-after entries))
        ;; Chain-seq monotonic
        with-chain (sort-by :evidence/chain-seq (filter :evidence/chain-seq entries))
        chain-mono (or (nil? (seq (keep :evidence/chain-seq with-chain))) (apply < (keep :evidence/chain-seq with-chain)))
        ;; Chain-seq gaps
        chain-seqs (sort (keep :evidence/chain-seq entries))
        gaps (if (seq chain-seqs)
               (let [expected (range (first chain-seqs) (inc (last chain-seqs)))]
                 (remove (set chain-seqs) expected))
               [])
        ;; Metadata completeness — targeted entries with full attribution
        targeted (filter #(= :targeted-protocol (:evidence/layer %)) entries)
        has-subject-type (count (remove :subject/type targeted))
        has-subject-id (count (remove :subject/id targeted))
        has-action-type (count (remove :action/type targeted))
        has-reason (count (remove :evidence/reason targeted))
        has-world-before (count (remove :hash/world-before entries))
        has-world-after (count (remove :hash/world-after entries))
        incomplete (set (keep (fn [e]
                                (let [missing (filter (fn [k] (nil? (get e k)))
                                                      [:subject/type :subject/id :action/type :evidence/reason])]
                                  (when (seq missing) (:evidence/id e))))
                              targeted))]
    [{:id "world-before-hashes-present" :status :diagnostic :detail {:total (count entries) :missing wh-before}}
     {:id "world-after-hashes-present" :status :diagnostic :detail {:total (count entries) :missing wh-after}}
     {:id "chain-seq-monotonic" :status (if chain-mono :diagnostic :warning) :detail {:chained (count with-chain)}}
     {:id "metadata-subject-type" :status (if (zero? has-subject-type) :diagnostic :warning) :detail {:total (count targeted) :missing has-subject-type}}
     {:id "metadata-subject-id" :status (if (zero? has-subject-id) :diagnostic :warning) :detail {:total (count targeted) :missing has-subject-id}}
     {:id "metadata-action-type" :status (if (zero? has-action-type) :diagnostic :warning) :detail {:total (count targeted) :missing has-action-type}}
     {:id "metadata-reason" :status (if (zero? has-reason) :diagnostic :warning) :detail {:total (count targeted) :missing has-reason}}
     {:id "metadata-world-hashes-complete" :status (if (and (zero? has-world-before) (zero? has-world-after)) :diagnostic :warning) :detail {:total (count entries) :missing-before has-world-before :missing-after has-world-after}}
     {:id "metadata-incomplete-entries" :status (if (empty? incomplete) :diagnostic :warning) :detail {:incomplete (vec (take 5 incomplete))}}
     {:id "chain-seq-no-gaps" :status (if (empty? gaps) :diagnostic :warning) :detail {:gaps (vec gaps)}}]))

;; ── Cross-Registry Consistency ─────────────────────────────────────────────────

(defn check-registry-consistency
  "Cross-validate the chain registry (from evidence-registry.json, produced by
   chain.clj finalize-and-write!) against the directory-scan registry (from
   event-evidence/, produced by registry.clj build-evidence-registry).
   
   Returns a check result map:
   :id          \"chain-vs-dir-registry-consistency\"
   :status      :passed | :failed | :skipped
   :detail      map with comparison metrics
   
   The chain registry stores artifact entries with :evidence-hash (component
   hashes).  The directory-scan registry stores entries with :hash/content
   and :file/path.  This check verifies:
   - Entry count parity
   - Every chain evidence hash has a file on disk
   - Every directory entry has a matching hash in the chain registry"
  [registry artifact-dir]
  (let [;; Build the directory-scan registry from event-evidence/
        ev-dir (str artifact-dir "/event-evidence")
        ev-dir-file (io/file ev-dir)
        _ registry] ;; registry param retained for API consistency
    (if-not (.isDirectory ev-dir-file)
      {:id "chain-vs-dir-registry-consistency"
       :status :skipped
       :detail {:reason "event-evidence directory not found" :path ev-dir}}
      (let [dir-registry (reg/build-evidence-registry ev-dir)
            dir-entries (:entries dir-registry [])
            dir-hashes (set (keep :hash/content dir-entries))
            dir-count (count dir-entries)
            ;; Chain registry has :entries from the directory scan — but this
            ;; function is called from validate-evidence-registry which receives
            ;; the directory-scan registry as `registry`. The chain registry
            ;; is on disk at evidence-registry.json. We need to read it.
            chain-reg-path (str artifact-dir "/evidence-registry.json")
            chain-file (io/file chain-reg-path)]
        (if-not (.exists chain-file)
          {:id "chain-vs-dir-registry-consistency"
           :status :skipped
           :detail {:reason "chain registry (evidence-registry.json) not found"
                    :path chain-reg-path}}
          (try
            (let [chain-reg (json/read-str (slurp chain-file) :key-fn keyword)
                  chain-artifacts (:artifacts chain-reg [])
                  chain-hashes (set (keep :evidence-hash chain-artifacts))
                  chain-count (count chain-artifacts)
                  ;; Cross-reference
                  chain-only (set/difference chain-hashes dir-hashes)
                  dir-only (set/difference dir-hashes chain-hashes)
                  count-match? (= chain-count dir-count)
                  all-chain-on-disk? (empty? chain-only)
                  all-dir-in-chain? (empty? dir-only)
                  consistent? (and count-match? all-chain-on-disk? all-dir-in-chain?)]
              {:id "chain-vs-dir-registry-consistency"
               :status (if consistent? :passed :failed)
               :detail {:chain-artifact-count chain-count
                        :dir-entry-count dir-count
                        :counts-match? count-match?
                        :chain-hashes-without-file (vec (take 10 chain-only))
                        :dir-hashes-without-chain-entry (vec (take 10 dir-only))
                        :all-chain-hashes-on-disk? all-chain-on-disk?
                        :all-dir-hashes-in-chain? all-dir-in-chain?}})
            (catch Exception e
              {:id "chain-vs-dir-registry-consistency"
               :status :skipped
               :detail {:reason "failed to read chain registry"
                        :error (.getMessage e)}})))))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn validate-evidence-registry
  "Run all validation checks against an evidence registry map.
   Returns a validation result map suitable for JSON serialization.
   
   Checks are grouped by category:
   - required:   must pass or status = :failed
   - recommended: warnings, status = :warning (unless :strict)
   - diagnostic:  informational, no status impact
   
   When :strict is true, recommended checks are promoted to required —
   warnings become failures.  Use for CI or research-release gates.
   Diff-evidence artifacts (:diff layer) are excluded from strict checks
   since they are always diagnostic."
  [registry & {:keys [strict artifact-dir]
               :or {strict false
                    artifact-dir (str (evcfg/artifact-dir))}}]
  (let [dir artifact-dir
        entries (:entries registry)
        ;; Filter out diagnostic diff artifacts from strict checks
        strict-entries (if strict
                         (remove #(= :diff (:evidence/layer %)) entries)
                         entries)
        strict-registry (assoc registry :entries strict-entries)
        required-ch (concat (check-required strict-registry dir) (check-attribution strict-registry))
        recommended-ch (check-recommended strict-registry)
        diagnostic-ch (check-diagnostic strict-registry)
        ;; Cross-registry consistency check (recommended level)
        consistency (check-registry-consistency registry dir)
        ;; Include consistency in recommended checks when it ran
        recommended-ch (cond-> recommended-ch
                         (= :passed (:status consistency)) (conj (assoc consistency :status :passed))
                         (= :failed (:status consistency)) (conj (assoc consistency :status :warning)))
        all-ch (concat required-ch recommended-ch diagnostic-ch)
        ;; In strict mode, :warning status from recommended checks is promoted to :failed
        promoted (if strict
                   (map (fn [c]
                          (if (= :warning (:status c))
                            (assoc c :status :failed :message "Promoted to required by strict mode")
                            c))
                        all-ch)
                   all-ch)
        has-failed (some #(= :failed (:status %)) promoted)
        passed (count (filter #(= :passed (:status %)) promoted))
        warnings (count (filter #(= :warning (:status %)) promoted))
        failed (count (filter #(= :failed (:status %)) promoted))
        diagnostics (count (filter #(= :diagnostic (:status %)) promoted))]
    {:schema/version "evidence-registry-validation.v1"
     :mode (if strict :strict :normal)
     :registry-path "evidence-registry.json"
     :status (if has-failed :failed :passed)
     :checks (vec promoted)
     :warnings (mapv (fn [c] {:check (:id c) :detail (:detail c)})
                     (filter #(= :warning (:status %)) promoted))
     :metrics {:total (count promoted)
               :passed passed
               :warnings warnings
               :failed failed
               :diagnostics diagnostics}}))

(defn write-evidence-registry-validation!
  "Run validation and persist to evidence-registry-validation.json.
   Also registers the artifact in the chain registry.
   Returns {:validation-path \"...\" :validation <map>}"
  [registry & [dir]]
  (let [out-dir (or dir (str (evcfg/artifact-dir)))
        validation (validate-evidence-registry registry :artifact-dir out-dir)
        f (io/file out-dir "evidence-registry-validation.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str validation {:indent true}))
    (println "Wrote evidence registry validation:" (.getPath f) "- status:" (:status validation))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-registry-validation
                                 "evidence-registry-validation.json"
                                 "evidence-registry-validation.v1" "DIAGNOSTIC"))
    {:validation-path (.getPath f)
     :validation validation}))

(defn build-evidence-registry!
  "Full pipeline: build evidence registry, write to disk, run validation, write validation.
    
   When :strict is true, recommended checks are promoted to failures.
   Defaults to the value of strict_mode in evidence config when not explicitly passed.
   Use :strict for CI or research-release gates.
    
   Returns a map with:
   :registry-path      — path to evidence-registry.json
   :validation-path    — path to evidence-registry-validation.json
   :entry-count        — number of evidence entries
   :validation-status  — :passed or :failed"
  [& {:keys [strict dir]
      :or {strict (evcfg/strict-mode?)}}]
  (let [out-dir (or dir (str (evcfg/artifact-dir)))
        {:keys [registry-path entry-count registry]} (reg/write-evidence-registry! out-dir)
        {:keys [validation-path validation]} (write-evidence-registry-validation! registry out-dir)]
    (when strict
      (let [strict-result (validate-evidence-registry registry
                                                      :strict true :artifact-dir out-dir)]
        (println "Strict validation:" (:status strict-result)
                 "—" (:failed (:metrics strict-result)) "failures")
        (when (= :failed (:status strict-result))
          (throw (ex-info "Evidence registry strict validation failed"
                          {:validation strict-result})))))
    {:registry-path registry-path
     :validation-path validation-path
     :entry-count entry-count
     :validation-status (:status validation)}))