(ns resolver-sim.evidence.chain
  "Content-addressed evidence chain: binds evidence records into a self-hashed
    artifact registry compatible with test-artifacts.json format.

   Chain:
     transition-evidence -> evidence-hash
     evidence-registry   -> registry-hash  (commit to all evidence hashes)
     manifest            -> artifact-registry-sha (signs the registry hash)

   Forensic-grade extensions:
     registry-signature  -> Ed25519 signature over the registry-hash
     cursor-signature    -> Ed25519 signature over the cursor snapshot
     timestamp-proof     -> local or external timestamp binding

   The registry atom accumulates evidence records during a run, then
   build-registry produces a self-hashed registry map that can be written
   to disk or passed to the existing signing infrastructure.

   Use finalize-and-attest! for the full forensic pipeline:
   build → write → sign → timestamp → cursor."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]
            [resolver-sim.vcs :as vcs]))

(declare cursor-snapshot)

(def ^:dynamic *allow-dirty*
  "Dynamic var override for :allow-dirty? in write-chain-cursor-final!.
   Bind to true in dev/test contexts to allow dirty working copies with
   a warning and dirty-diff-hash recorded in cursor-data.
   Defaults to nil — falls back to :allow-dirty? option from the caller.
   When neither is set, dirty state causes a hard failure."
  nil)

;; ── Registry Atom ─────────────────────────────────────────────────────────

(def ^:dynamic ^:private evidence-registry-atom
  "Thread-local evidence chain registry atom.  Bound via with-fresh-registry
   to give each thread its own isolated registry.  All access goes through
   swap!/reset! — atomically safe within a single thread binding.  Dynamic
   binding does NOT auto-propagate to spawned futures/threads; callers must
   wrap async boundaries in (binding [evidence-registry-atom ...] ...)."
  (atom {:artifacts []
         :evidence-hashes []
         :run-id nil
         :run-label nil}))

(def ^:private evidence-registry-locks
  "Registry of locks keyed by atom identity.
   Each evidence-registry-atom gets its own lock, allowing parallel tests
   with separate atoms to proceed concurrently without blocking each other."
  (atom {}))

(defn- registry-lock
  "Get or create a lock for the given registry atom.
   Locks are stored in evidence-registry-locks map keyed by atom identity.
   Atomic swap! with the creation fn prevents a TOCTOU race where two threads
   both see nil, create separate locks, and defeat locking-based synchronization."
  [reg-atom]
  (let [id (System/identityHashCode reg-atom)]
    (get (swap! evidence-registry-locks
                (fn [m]
                  (if (contains? m id)
                    m
                    (assoc m id (Object.)))))
         id)))

(defn reset-registry!
  "Clear the registry atom for a new run.
   Optional: run-id and run-label for identification."
  [& {:keys [run-id run-label]}]
  (reset! evidence-registry-atom
          {:artifacts []
           :evidence-hashes []
           :run-id run-id
           :run-label run-label})
  nil)

(defmacro with-fresh-registry
  "Execute body with a fresh evidence chain registry.
   The outer registry is restored when body exits.
   Useful for isolating runs and preventing cross-run contamination.
   Each fresh registry gets its own lock for thread-safe parallel execution."
  [& body]
  `(let [fresh# (atom {:artifacts []
                       :evidence-hashes []
                       :run-id nil
                       :run-label nil})]
     (binding [evidence-registry-atom fresh#]
       ~@body)))

(defn with-fresh-registry*
  "Thunk-based version of with-fresh-registry for use in higher-order
   contexts (parallel test runner, use-fixtures) where a macro won't
   work.  Calls (f) inside a fresh binding of evidence-registry-atom."
  [f]
  (let [fresh (atom {:artifacts [] :evidence-hashes []
                     :run-id nil :run-label nil})]
    (binding [evidence-registry-atom fresh]
      (f))))

(defn registry-snapshot
  "Return a snapshot of the current bound evidence registry atom.
   Useful for capturing scenario-local registry state inside
   with-fresh-registry blocks before the binding exits."
  []
  @evidence-registry-atom)

;; ── Run-Level Scenario Evidence Accumulator ──────────────────────────────
;; Collects scenario-local registry/cursor snapshots from with-fresh-registry
;; blocks so they can be aggregated into the top-level run registry.

(def ^:dynamic ^:private scenario-evidence-atom
  "Thread-local accumulator for scenario-local evidence snapshots.
   Each entry is {:registry <snapshot> :cursor <snapshot>} from a scenario run.
   Bound together with evidence-registry-atom and chain-cursor under
   with-fresh-evidence-context*.  with-fresh-registry alone does NOT bind this.
   Thread-safe via swap! within a single binding; does NOT auto-propagate
   to spawned futures/threads — wrap async boundaries explicitly."
  (atom []))

(defn reset-scenario-evidence!
  "Clear the scenario evidence accumulator for a new run."
  []
  (reset! scenario-evidence-atom [])
  nil)

(defn register-scenario-snapshot!
  "Register a scenario-local registry and cursor snapshot.
   Call inside with-fresh-registry / with-fresh-chain-cursor bindings
   to capture the per-scenario evidence state before bindings exit."
  ([]
   (register-scenario-snapshot! (registry-snapshot) (cursor-snapshot)))
  ([registry-snapshot cursor-snapshot]
   (when (and registry-snapshot (seq (:artifacts registry-snapshot)))
     (swap! scenario-evidence-atom conj
            {:registry registry-snapshot
             :cursor cursor-snapshot}))
   nil))

(defn scenario-evidence-snapshots
  "Return the accumulated scenario evidence snapshots."
  []
  @scenario-evidence-atom)

(defn accumulate-scenario-evidence!
  "Merge all scenario-local registry snapshots into the top-level
   evidence registry atom. Skips duplicate and nil evidence hashes.
   Returns the total transition-evidence count after aggregation."
  []
  (doseq [snap @scenario-evidence-atom]
    (let [reg (:registry snap)]
      (doseq [artifact (:artifacts reg)
              :when (:evidence-hash artifact)]
        (swap! evidence-registry-atom
               (fn [r]
                 (let [existing (some #(when-let [eh (:evidence-hash %)]
                                         (hc/intent-hash= eh (:evidence-hash artifact)))
                                      (:artifacts r))]
                   (if existing
                     r
                     (-> r
                         (update :artifacts conj artifact)
                         (update :evidence-hashes conj (:evidence-hash artifact))))))))))
  (count (filter :evidence-hash (:artifacts @evidence-registry-atom))))

;; ── Chain Cursor ──────────────────────────────────────────────────────────

(def ^:dynamic ^:private chain-cursor
  "Thread-local evidence chain cursor for targeted event evidence.
   Tracks sequence number and previous hash for chain linking.
   Bound via with-fresh-chain-cursor to isolate per-thread cursor state.
   Thread-safe via swap!/reset! within a single binding; does NOT
   auto-propagate to spawned futures/threads."
  (atom {:seq 0 :last-hash nil}))

(defn reset-chain-cursor!
  "Reset the evidence chain cursor for a new run.
   Idempotent — safe to call multiple times."
  []
  (reset! chain-cursor {:seq 0 :last-hash nil}))

(defmacro with-fresh-chain-cursor
  "Execute body with a fresh evidence chain cursor.
   The outer cursor is restored when body exits.
   Use at the start of a replay run alongside with-fresh-registry."
  [& body]
  `(let [fresh# (atom {:seq 0 :last-hash nil})]
     (binding [chain-cursor fresh#]
       ~@body)))

(defn with-fresh-evidence-context*
  "Run f with a fresh evidence registry, scenario evidence accumulator,
   and chain cursor.  Intended for test namespaces, scenario runs, and
   async/parallel execution boundaries where each thread needs its own
   isolated evidence context.

   This supersedes with-fresh-registry* for parallel contexts because
   it binds all three dynamic vars (evidence-registry-atom,
   scenario-evidence-atom, chain-cursor) instead of only the registry."
  [f]
  (let [fresh-registry (atom {:artifacts []
                              :evidence-hashes []
                              :run-id nil
                              :run-label nil})
        fresh-scenarios (atom [])
        fresh-cursor (atom {:seq 0 :last-hash nil})]
    (binding [evidence-registry-atom fresh-registry
              scenario-evidence-atom fresh-scenarios
              chain-cursor fresh-cursor]
      (f))))

(def chain-hash-scheme "link-v1")

(defn chain-link-hash
  "Return the versioned hash for one targeted-evidence chain link.

   The link hash commits to the immutable evidence-content hash and to the
   chain position and predecessor. A terminal link therefore commits to the
   ordered prefix only when every prior link is verified."
  [evidence-hash chain-seq previous-link-hash]
  (hc/hash-with-intent
   {:hash/intent :evidence-chain-link-v1}
   {:chain/hash-scheme chain-hash-scheme
    :evidence/hash evidence-hash
    :evidence/chain-seq chain-seq
    :evidence/chain-prev-hash previous-link-hash}))

(defn evidence-hash-set-root
  "Return a domain-separated commitment to an unordered evidence-hash set.
   The canonical payload sorts and de-duplicates hashes before hashing."
  [hashes]
  (hc/hash-with-intent
   {:hash/intent :run-evidence-hash-set-v1}
   {:evidence-hashes (vec (sort (set hashes)))}))

(defn reconcile-evidence-sets
  "Compare the four evidence identity sets required for an exact run closure.

   Inputs are collection-valued; duplicates are reported separately and do not
   affect the canonical set comparison. `:exact` means every source declares
   the same unique evidence hashes."
  [{:keys [disk-evidence-hashes registry-evidence-hashes
           chain-reachable-hashes aggregate-declared-hashes]}]
  (let [sources {:disk-evidence-hashes (vec (or disk-evidence-hashes []))
                 :registry-evidence-hashes (vec (or registry-evidence-hashes []))
                 :chain-reachable-hashes (vec (or chain-reachable-hashes []))
                 :aggregate-declared-hashes (vec (or aggregate-declared-hashes []))}
        sets (into {} (map (fn [[k hashes]] [k (set hashes)]) sources))
        disk (:disk-evidence-hashes sets)
        registry (:registry-evidence-hashes sets)
        reachable (:chain-reachable-hashes sets)
        declared (:aggregate-declared-hashes sets)
        duplicates (into {}
                         (keep (fn [[k hashes]]
                                 (let [duplicates (->> hashes frequencies
                                                       (keep (fn [[hash n]] (when (> n 1) hash)))
                                                       sort vec)]
                                   (when (seq duplicates) [k duplicates])))
                               sources))
        exact? (and (= disk registry reachable declared) (empty? duplicates))]
    {:reconciliation/status (if exact? :exact :mismatch)
     :disk-evidence-hashes disk
     :registry-evidence-hashes registry
     :chain-reachable-hashes reachable
     :aggregate-declared-hashes declared
     :disk-only (set/difference disk registry)
     :registry-only (set/difference registry disk)
     :unreachable (set/difference disk reachable)
     :undeclared (set/difference disk declared)
     :chain-only (set/difference reachable disk)
     :declared-only (set/difference declared disk)
     :duplicate-hashes duplicates
     :evidence/set-root (evidence-hash-set-root declared)}))

(defn evaluate-policy-requirement
  "Evaluate one independently configured forensic requirement.

   `requirement` may declare :required?, :trusted-signer?,
   :trusted-authority?, and :allowed-algorithms. `observation` supplies
   :present?, :valid?, :trusted?, and :algorithm. Invalid supplied material
   remains invalid even when the policy does not require that material."
  [requirement observation]
  (let [required? (true? (:required? requirement))
        present? (true? (:present? observation))
        valid? (true? (:valid? observation))
        trusted-required? (or (true? (:trusted-signer? requirement))
                              (true? (:trusted-authority? requirement)))
        trusted? (true? (:trusted? observation))
        allowed-algorithms (:allowed-algorithms requirement)
        algorithm (:algorithm observation)]
    {:requirement/status
     (cond
       (and present? (not valid?)) :requirement/present-invalid
       (and present? trusted-required? (not trusted?)) :requirement/present-untrusted
       (and present? (seq allowed-algorithms) (not (contains? allowed-algorithms algorithm)))
       :requirement/present-invalid
       (and required? (not present?)) :requirement/missing
       present? :requirement/satisfied
       :else :requirement/not-required)
     :requirement/config requirement
     :requirement/observation observation}))

(defn evaluate-forensic-policy
  "Evaluate independent signature, timestamp, registry, and finalization
   requirements. Observations are supplied by the caller so this function stays
   pure and does not choose artifact paths or trust registries."
  [policy observations]
  (let [registry-observation (merge {:present? false :valid? false}
                                    (:registry observations)
                                    {:valid? (and (true? (get-in observations [:registry :valid?]))
                                                  (or (not (true? (get-in policy [:policy/registry :exact-reconciliation?])))
                                                      (true? (get-in observations [:registry :exact?]))))})
        requirements {:signature (evaluate-policy-requirement
                                  (:policy/signature policy) (:signature observations))
                      :timestamp (evaluate-policy-requirement
                                  (:policy/timestamp policy) (:timestamp observations))
                      :registry (evaluate-policy-requirement
                                 (:policy/registry policy) registry-observation)
                      :finalization (evaluate-policy-requirement
                                     (:policy/finalization policy) (:finalization observations))}
        blocking-statuses #{:requirement/missing :requirement/present-invalid
                            :requirement/present-untrusted}]
    {:policy/requirements requirements
     :policy/satisfied? (not-any? blocking-statuses
                                  (map :requirement/status (vals requirements)))}))

(defn inject-chain-fields
  "Add versioned chain-link fields to targeted evidence using the cursor.

   `:evidence/chain-self-hash` is a chain-link hash, not merely the evidence
   content hash: it commits to the content hash, sequence, and predecessor.
   Uses one atomic swap to allocate a sequence and link against the current
   chain head."
  [evidence]
  (let [evidence-hash (:evidence/hash evidence)
        {:keys [seq last-hash prev-hash]}
        (swap! chain-cursor
               (fn [cursor]
                 (let [new-seq (inc (:seq cursor))
                       previous-link-hash (:last-hash cursor)
                       link-hash (chain-link-hash evidence-hash new-seq previous-link-hash)]
                   {:seq new-seq
                    :last-hash link-hash
                    :prev-hash previous-link-hash})))]
    (assoc evidence
           :evidence/chain-hash-scheme chain-hash-scheme
           :evidence/chain-seq seq
           :evidence/chain-prev-hash prev-hash
           :evidence/chain-self-hash last-hash)))

(defn verify-scenario-chain
  "Verify one scenario-local targeted-evidence chain in memory.

   Records must use the `link-v1` chain-hash scheme. The result distinguishes
   an intentionally empty chain from an invalid or incomplete chain and proves
   that a declared terminal hash is unique only when the records form one
   contiguous, hash-linked sequence."
  [records & {:keys [scenario-id]}]
  (let [records (vec records)
        observed-scenario-ids (set (keep :scenario/id records))
        resolved-scenario-id (or scenario-id (when (= 1 (count observed-scenario-ids))
                                               (first observed-scenario-ids)))]
    (if (empty? records)
      {:chain/scope :scenario
       :chain/scenario-id resolved-scenario-id
       :chain/status :empty
       :chain/completeness :complete
       :chain/first-seq 0
       :chain/final-seq 0
       :chain/record-count 0
       :chain/reachable-hashes []
       :chain/head-hash nil
       :chain/links-valid? true
       :chain/hashes-valid? true
       :chain/terminal-unique? true
       :chain/errors []}
      (let [sorted-records (vec (sort-by :evidence/chain-seq records))
            seqs (mapv :evidence/chain-seq sorted-records)
            expected-seqs (vec (range 1 (inc (count sorted-records))))
            duplicate-seqs (->> seqs frequencies (keep (fn [[seq n]] (when (> n 1) seq))) vec)
            identity-valid? (and resolved-scenario-id
                                 (= observed-scenario-ids #{resolved-scenario-id}))
            scheme-errors (let [schemes (set (keep :evidence/chain-hash-scheme sorted-records))]
                            (into (->> sorted-records
                                       (keep (fn [record]
                                               (when (not= chain-hash-scheme (:evidence/chain-hash-scheme record))
                                                 {:chain-seq (:evidence/chain-seq record)
                                                  :reason :unsupported-chain-hash-scheme})))
                                       vec)
                                  ;; Chain-level uniformity: all records must use the same scheme.
                                  ;; A future migration scheme may define cross-scheme transitions,
                                  ;; but for link-v1 mixing is unsupported.
                                  (when (< 1 (count schemes))
                                    [{:reason :chain-hash-schemes-inconsistent
                                      :observed-schemes (vec (sort schemes))
                                      :expected-scheme chain-hash-scheme}])))
            hash-errors (->> sorted-records
                             (keep (fn [record]
                                     (let [expected (chain-link-hash (:evidence/hash record)
                                                                     (:evidence/chain-seq record)
                                                                     (:evidence/chain-prev-hash record))]
                                       (when (not= expected (:evidence/chain-self-hash record))
                                         {:chain-seq (:evidence/chain-seq record)
                                          :reason :chain-link-hash-mismatch}))))
                             vec)
            link-errors (->> (map vector sorted-records (cons nil sorted-records))
                             (keep (fn [[record previous]]
                                     (let [expected-previous (:evidence/chain-self-hash previous)]
                                       (when (not= expected-previous (:evidence/chain-prev-hash record))
                                         {:chain-seq (:evidence/chain-seq record)
                                          :reason :predecessor-mismatch}))))
                             vec)
            sequence-errors (cond-> []
                              (not= seqs expected-seqs)
                              (conj {:reason :non-contiguous-sequence
                                     :observed seqs
                                     :expected expected-seqs})
                              (seq duplicate-seqs)
                              (conj {:reason :duplicate-sequences
                                     :sequences duplicate-seqs}))
            identity-errors (cond-> []
                              (not identity-valid?)
                              (conj {:reason :scenario-identity-mismatch
                                     :expected scenario-id
                                     :observed observed-scenario-ids}))
            errors (vec (concat identity-errors sequence-errors scheme-errors hash-errors link-errors))
            valid? (empty? errors)
            terminal (last sorted-records)]
        {:chain/scope :scenario
         :chain/scenario-id resolved-scenario-id
         :chain/status (if valid? :verified :invalid)
         :chain/completeness (if valid? :complete :incomplete)
         :chain/first-seq (first seqs)
         :chain/final-seq (last seqs)
         :chain/record-count (count sorted-records)
         :chain/reachable-hashes (vec (sort (map :evidence/hash sorted-records)))
         :chain/head-hash (:evidence/chain-self-hash terminal)
         :chain/links-valid? (empty? link-errors)
         :chain/hashes-valid? (and (empty? scheme-errors) (empty? hash-errors))
         :chain/terminal-unique? (and (= seqs expected-seqs) (empty? duplicate-seqs))
         :chain/errors errors}))))

(defn build-scenario-chain-finalization
  "Build the logical, non-persisted scenario evidence finalization payload.
   Artifact placement, signatures, and package inventory are intentionally
   left to the coordinated artifact-contract workstream."
  [{:scenario/keys [id input-hash seq status]
    :keys [records]}]
  (merge {:finalization/type :scenario-evidence
          :finalization/version 1
          :scenario/id id
          :scenario/input-hash input-hash
          :scenario/seq seq
          :scenario/status status}
         (verify-scenario-chain records :scenario-id id)))

(defn build-run-evidence-finalization
  "Build the logical, non-persisted run evidence finalization payload.

   The caller supplies the evidence identities observed on disk and in the
   registry. Scenario finalizations provide chain-reachable identities. This
   function deliberately does not choose filesystem placement, registry
   inclusion, signatures, or DAG bindings."
  [{:run/keys [id input-commitment status]
    :registry/keys [root-hash]
    :keys [scenario-finalizations disk-evidence-hashes registry-evidence-hashes
           aggregate-declared-hashes]}]
  (let [scenario-finalizations (vec (sort-by :scenario/seq scenario-finalizations))
        reachable-hashes (mapcat :chain/reachable-hashes scenario-finalizations)
        declared-hashes (or aggregate-declared-hashes reachable-hashes)
        reconciliation (reconcile-evidence-sets
                        {:disk-evidence-hashes disk-evidence-hashes
                         :registry-evidence-hashes registry-evidence-hashes
                         :chain-reachable-hashes reachable-hashes
                         :aggregate-declared-hashes declared-hashes})]
    {:finalization/type :run-evidence
     :finalization/version 1
     :run/id id
     :run/input-commitment input-commitment
     :run/status status
     :scenario-finalizations scenario-finalizations
     :registry/root-hash root-hash
     :evidence/set-root (:evidence/set-root reconciliation)
     :evidence/total-count (count (set declared-hashes))
     :reconciliation reconciliation}))

(defn registry-status
  "Return summary info from the current registry state: count, run-id."
  []
  (let [r @evidence-registry-atom]
    {:evidence-count (count (:artifacts r))
     :run-id (:run-id r)
     :run-label (:run-label r)
     :has-registry? (boolean (seq (:artifacts r)))}))

;; ── Evidence Registration ─────────────────────────────────────────────────

(defn- evidence->artifact-entry
  "Convert an evidence record (from emit-evidence!) into a registry artifact entry.
   The entry includes the evidence-hash as both identifier and integrity proof,
   plus all component hashes for traceability.

   Note: :kind and :artifact-kind are stored as strings (not keywords) so they
   survive JSON serialization/deserialization without hash mismatch during
   verify-registry-hash — which reads the registry back from disk and recomputes
   the registry hash.  Keywords and strings use different canonical encoding
   type tags (0x22 vs 0x20), so round-tripping keyword values would produce a
   different hash."
  [evidence]
  (let [eh (:evidence-hash evidence)]
    {:id (str "evidence-" (subs eh 0 (min 12 (count eh))))
     :kind "transition-evidence"
     :schema-version (evcfg/schema :evidence-record)
     :evidence-hash eh
     :context-hash (:context-hash evidence)
     :before-hash (or (:before-hash evidence)
                      (:world/before-hash evidence))
     :after-hash (or (:after-hash evidence)
                     (:world/after-hash evidence))
     :action-hash (:action-hash evidence)
     :result-hash (:result-hash evidence)
     :artifact-kind (let [ak (:artifact-kind evidence)]
                      (cond
                        (string? ak) ak
                        (keyword? ak) (name ak)
                        :else (str ak)))}))

(defn register-evidence!
  "Register an evidence record (map from emit-evidence!) into the chain registry.
   Returns the evidence-hash. The registry tracks every evidence record produced
   during a run, enabling the full content-addressed chain to be built later.
   Idempotent: duplicate evidence-hashes are skipped.
   Logs a warning when a duplicate evidence hash is detected.
   Logs a warning when evidence has no hash — the record will not be registered.

   Uses a per-atom lock, allowing parallel tests with separate atoms to proceed
   concurrently without blocking each other."
  [evidence]
  (let [lock (registry-lock evidence-registry-atom)]
    (locking lock
      (let [eh (:evidence-hash evidence)]
        (if (and eh (string? eh))
          (let [reg @evidence-registry-atom]
            (if (some #(hc/intent-hash= eh (:evidence-hash %)) (:artifacts reg))
              (log/debug! :evidence-register-duplicate-hash
                          {:evidence-hash eh
                           :evidence-type (:evidence/type evidence)
                           :artifact-kind (:artifact-kind evidence)})
              (swap! evidence-registry-atom
                     (fn [r]
                       (-> r
                           (update :artifacts conj (evidence->artifact-entry evidence))
                           (update :evidence-hashes conj eh))))))
          (log/warn! :evidence-register-missing-hash
                     {:evidence-type (:evidence/type evidence)
                      :artifact-kind (:artifact-kind evidence)}))
        eh))))

;; ── Registry Builder ──────────────────────────────────────────────────────

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn build-registry
  "Build a self-hashed registry map from the accumulated evidence records.

   The registry includes:
     - schema-version, run-id, generated-at
     - all evidence artifacts with their hashes
     - a registry-hash committing to all entries

   The registry-hash is computed over the registry WITHOUT its own :registry-hash
   field, then appended — matching the pattern used by sign-manifest for
   artifact-registry-sha."
  [& {:keys [run-id] :or {run-id "unknown"}}]
  (let [reg @evidence-registry-atom
        artifacts (:artifacts reg)
        base {:schema-version (evcfg/schema :evidence-registry)
              :contract-version (evcfg/contract-version)
              :run-id (or run-id (:run-id reg) "unknown")
              :generated-at (now-iso)
              :evidence-count (count artifacts)
              :evidence-hashes (:evidence-hashes reg)
              :artifacts artifacts}
        reg-hash (hc/hash-with-intent {:hash/intent :registry} base)]
    (assoc base :registry-hash reg-hash)))

;; ── Persistence ───────────────────────────────────────────────────────────

(def evidence-registry-filename
  "Filename for the evidence registry artifact."
  "evidence-registry.json")

(defn registry->json
  "Serialize a registry map to pretty-printed JSON string.
   Uses preserve-ns-key inline so namespaced keyword keys survive
   the JSON round-trip (matching how chain-cursor-final.json and
   event-evidence files are serialized)."
  [registry]
  (json/write-str registry {:key-fn (fn [k]
                                      (if (keyword? k)
                                        (if-let [ns (namespace k)]
                                          (str ns "/" (name k))
                                          (name k))
                                        (str k)))
                            :indent true}))

(defn write-registry!
  "Write the registry to disk as JSON. Returns the written path.
   Writes to artifact-dir/evidence-registry.json by default."
  ([registry] (write-registry! registry nil))
  ([registry output-path]
   (let [path (or output-path
                  (str (evcfg/artifact-dir) "/" evidence-registry-filename))
         f (io/file path)]
     (.mkdirs (.getParentFile f))
     (spit f (registry->json registry))
     path)))

;; ── Artifact Registry Integration ─────────────────────────────────────────

(defn compute-file-sha256
  "Compute the SHA-256 hex digest of a file's contents."
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn- preserve-ns-key
  "JSON key function that preserves Clojure keyword namespaces.
   :source/hash -> \"source/hash\", not \"hash\"."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn registry-artifact-entry
  "Produce a test-artifacts.json compatible artifact entry for the written
   evidence-registry.json file. Returns nil if the file doesn't exist.
   The entry binds the registry-hash into the artifact chain so that
   sign-manifest's artifact-registry-sha commits to all evidence hashes."
  [registry]
  (let [path (str (evcfg/artifact-dir) "/" evidence-registry-filename)
        f (io/file path)]
    (when (.exists f)
      {:id (get (evcfg/artifact :evidence-registry) "id" "evidence-registry")
       :kind (get (evcfg/artifact :evidence-registry) "kind" "evidence-registry")
       :path path
       :schema-version (evcfg/schema :evidence-registry)
       :contract-version (evcfg/contract-version)
       :producer (evcfg/producer :simulation-engine)
       :importance "CORE"
       :registry-hash (:registry-hash registry)
       :evidence-count (:evidence-count registry)
       :sha256 (compute-file-sha256 path)
       :bytes (.length f)
       :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))})))

(defn index-artifact-entry
  "Produce a test-artifacts.json compatible artifact entry for an index file.
   Returns nil if the file doesn't exist.
   Use for index files like evidence-mechanisms.json, evidence-coverage-report.json."
  [artifact-id filename schema-version importance]
  (let [path (str (evcfg/artifact-dir) "/" filename)
        f (io/file path)]
    (when (.exists f)
      {:id (name artifact-id)
       :kind "evidence-index"
       :path path
       :schema-version schema-version
       :producer "targeted-evidence.v1"
       :importance importance
       :sha256 (compute-file-sha256 path)
       :bytes (.length f)
       :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))})))

(defn register-additional-artifact!
  "Register an additional artifact entry in the evidence registry.
   Useful for index files produced after the main registry is built.
   The entry is appended to the artifacts vector.
   Returns nil.

   Recovery contract (auxiliary artifacts):
     (1) Failure to register does not invalidate any authoritative state.
         Authortative state (attestation registry, run results) is
         established independently and does not depend on chain artifacts.
     (2) Verification does not assume every registered record has a
         chain artifact.  Verification checks the authoritative registry,
         not the chain artifact index.
     (3) The artifact entry is deterministically derivable from the
         source record — it can be rebuilt without loss.
     (4) Failure is silently ignored (returns nil).  Callers that require
         confirmation should verify artifact presence via registry-snapshot.
     (5) Retrying appends a duplicate entry.  The chain is append-only;
         deduplication is the caller's responsibility."
  [entry]
  (when entry
    (swap! evidence-registry-atom update :artifacts conj entry))
  nil)

;; ── Chain Cursor Finalization ───────────────────────────────────────────────

(defn cursor-snapshot
  "Read-only snapshot of the chain cursor's final state.
   Returns {:cursor/seq N :cursor/last-hash ...} or nil if never used."
  []
  (let [c @chain-cursor]
    (when (pos? (:seq c))
      {:cursor/scope :targeted-evidence
       :cursor/final-seq (:seq c)
       :cursor/final-self-hash (:last-hash c)
       :cursor/total-captured (:seq c)})))

(defn enrich-cursor-data
  "Add source provenance to a cursor snapshot.

   Takes a cursor snapshot map and optional source-provenance map
   (from vcs/source-provenance) plus an optional dirty-diff-hash.

   When source-provenance is provided, includes :cursor/source in
   cursor-data. If dirty-diff-hash is provided, it is merged into
   the :cursor/source map.

   Returns cursor-data map suitable for hashing, signing, or persistence.
   This function is pure — no side effects."
  ([snapshot]
   (enrich-cursor-data snapshot (vcs/source-provenance) nil))
  ([snapshot source-provenance]
   (enrich-cursor-data snapshot source-provenance nil))
  ([snapshot source-provenance dirty-diff-hash]
   (let [base {:cursor/scope (:cursor/scope snapshot)
               :cursor/final-seq (:cursor/final-seq snapshot)
               :cursor/final-self-hash (:cursor/final-self-hash snapshot)
               :cursor/total-captured (:cursor/total-captured snapshot)}]
     (if source-provenance
       (assoc base :cursor/source
              (cond-> source-provenance
                dirty-diff-hash (assoc :dirty-diff-hash dirty-diff-hash)))
       base))))

(defn write-chain-cursor-final!
  "Snapshot the chain cursor at run finalization and persist as
   chain-cursor-final.json. Registers the artifact for test-artifacts.json.
   Returns the path written, or nil if the cursor was never used.

   When private-key-path is provided, signs the cursor content (final-seq +
   final-self-hash) and includes the Ed25519 signature in the artifact.

   Optional:
     :allow-dirty?     — when true, allows dirty working copy and includes
                         dirty-diff-hash in cursor-data (default false)
     :run-config-hash  — hex hash of run configuration parameters, added to
                         :cursor/source map under :run-config-hash

   Dirty policy:
     Development:      dirty allowed with warning, dirty? recorded
     Override:         allow-dirty? true + dirty-diff-hash committed
     Default (CI/prod/attestation): throws on dirty

   One-shot: call once when the run completes (no new captures after)."
  [& {:keys [dir private-key-path password allow-dirty? run-config-hash run-id]
      :or {password nil}}]
  (let [allow-dirty? (or allow-dirty? *allow-dirty* false)]
    (when-let [snapshot (cursor-snapshot)]
      (let [out-dir (or dir (str (evcfg/artifact-dir)))
            f (io/file out-dir "chain-cursor-final.json")
            source (vcs/source-provenance)
            is-dirty (get source :dirty?)
            diff-hash (when (and is-dirty allow-dirty?) (vcs/dirty-diff-hash))
            source (cond-> source
                     run-config-hash (assoc :run-config-hash run-config-hash))
            cursor-data (enrich-cursor-data snapshot source diff-hash)]

      ;; Enforce dirty policy
        (when is-dirty
          (if allow-dirty?
            (log/warn! :dirty-working-copy {:message "Including dirty-diff-hash in cursor-data"})
            (throw (ex-info "Dirty working copy - use :allow-dirty? true to override"
                            {:dirty? true
                             :hint "Re-run with --allow-dirty to include dirty-diff-hash in cursor-data"}))))

        (let [signed (when (and private-key-path (:cursor/final-self-hash snapshot))
                       (let [cursor-hash (hc/hash-with-intent {:hash/intent :evidence-chain} cursor-data)
                             sig (signing/sign-hash cursor-hash private-key-path password)]
                         {:cursor/hash cursor-hash
                          :cursor/signature sig
                          :cursor/signer private-key-path
                          :cursor/signed-at (now-iso)}))
              artifact (merge {:schema/version "chain-cursor-final.v1"
                               :run/id (or run-id (get-in @evidence-registry-atom [:run-id]) "unknown")}
                              cursor-data
                              (when source
                                {:source/hash           (:source/hash source)
                                 :source/hash-algorithm (:source/hash-algorithm source)
                                 :source/hash-roots     (:source/hash-roots source)
                                 :code-hash             (:code-hash source)
                                 :deps-hash             (:deps-hash source)
                                 :input-hash            (:input-hash source)
                                 :run-config-hash (:run-config-hash source)})
                              (when signed
                                {:cursor/forensic (dissoc signed :cursor/hash)
                                 :cursor/signed-hash (:cursor/hash signed)}))]
          (.mkdirs (io/file out-dir))
          (spit f (json/write-str artifact :key-fn preserve-ns-key :indent true))
          (log/info! :chain-cursor-written {:file "chain-cursor-final.json"
                                            :seq (:cursor/final-seq snapshot)
                                            :signed? (boolean signed)})
          (register-additional-artifact!
           (index-artifact-entry :chain-cursor-final "chain-cursor-final.json"
                                 "chain-cursor-final.v1" "DIAGNOSTIC"))
          (.getPath f))))))

;; ── Evidence Registration ─────────────────────────────────────────────────

(defn finalize-and-write!
  "Build the evidence registry from accumulated records, write it to disk,
   and return {:registry ... :artifact-entry ... :path ...}.
   This is the single call to complete a run's evidence chain.
   Optional: run-id for identification."
  [& {:keys [run-id] :or {run-id "unknown"}}]
  (let [registry (build-registry :run-id run-id)
        path (write-registry! registry)
        entry (registry-artifact-entry registry)]
    {:registry registry
     :artifact-entry entry
     :path path}))

;; ── Chain Verification ────────────────────────────────────────────────────

(defn verify-registry-hash
  "Verify that the registry-hash in a registry map is consistent with its content.
   Returns {:valid true} or {:valid false :computed <hash> :recorded <hash>}."
  [registry]
  (let [recorded (:registry-hash registry)
        base (dissoc registry :registry-hash)
        computed (hc/hash-with-intent {:hash/intent :registry} base)]
    (if (= recorded computed)
      {:valid true}
      {:valid false :computed computed :recorded recorded})))

(defn verify-evidence-in-registry
  "Verify that a specific evidence-hash is recorded in the registry.
   Returns {:present true :entry <entry>} or {:present false}."
  [registry evidence-hash]
  (if-let [entry (some #(when (hc/intent-hash= evidence-hash (:evidence-hash %)) %)
                       (:artifacts registry))]
    {:present true :entry entry}
    {:present false}))

(defn evidence-chain-integrity
  "Full chain integrity check: verify the registry hash is consistent,
   every evidence hash is non-nil and well-formed, and all component
   hashes are present. Returns a summary map.
   Note: individual evidence content hashes are verified at the disk
   level by verify-chain-integrity in resolver-sim.io.event-evidence."
  [registry]
  (let [artifacts (:artifacts registry)
        reg-valid (:valid (verify-registry-hash registry))
        all-hashes (map :evidence-hash artifacts)
        all-non-nil (every? some? all-hashes)
        all-well-formed (every? #(re-matches #"^[0-9a-f]{64}$" (or % "")) all-hashes)
        all-with-component-hashes (every? (fn [a]
                                            (and (:context-hash a)
                                                 (:before-hash a)
                                                 (:after-hash a)
                                                 (:action-hash a)
                                                 (:result-hash a)))
                                          artifacts)]
    {:registry-hash-valid reg-valid
     :artifact-count (count artifacts)
     :all-hashes-non-nil all-non-nil
     :all-hashes-well-formed all-well-formed
     :all-hashes-registered (and all-non-nil all-well-formed)
     :all-with-component-hashes all-with-component-hashes
     :chain-intact (and reg-valid all-non-nil all-well-formed)}))

;; ── Evidence Reconciliation ────────────────────────────────────────────
;; Validates that evidence files on disk match the registry and cursor.
;; Detects unregistered evidence and stale cursors.

(defn reconcile-evidence!
  "Reconcile evidence on disk against the registry and chain cursor.
   Fails (throws) if unregistered evidence files exist or the cursor
   is behind disk evidence.

   Reads evidence files from event-evidence/ and compares counts and
   chain-seq values against evidence-registry.json and
   chain-cursor-final.json.

   Options:
     :artifact-dir  — artifact directory (default: evcfg/artifact-dir)
     :throw-on-error — when true, throws on mismatch (default: true)"
  [& {:keys [artifact-dir throw-on-error]
      :or {artifact-dir (str (evcfg/artifact-dir))
           throw-on-error true}}]
  (let [ev-dir (io/file artifact-dir "event-evidence")
        disk-files (when (.isDirectory ev-dir)
                     (sort (filter #(.endsWith (.getName %) ".json")
                                   (or (.listFiles ev-dir) []))))
        disk-count (count disk-files)
        registry-file (io/file artifact-dir "evidence-registry.json")
        registry (when (.exists registry-file)
                   (json/read-str (slurp registry-file) :key-fn keyword))
        registry-count (get registry :evidence-count 0)
        cursor-file (io/file artifact-dir "chain-cursor-final.json")
        cursor (when (.exists cursor-file)
                 (json/read-str (slurp cursor-file) :key-fn keyword))
        cursor-seq (get cursor :cursor/final-seq 0)
        parsed-files (mapv (fn [f]
                             (try
                               {:file f
                                :data (json/read-str (slurp f) :key-fn keyword)}
                               (catch Exception e
                                 {:file f :error (.getMessage e)})))
                           disk-files)
        parse-errors (vec (keep (fn [{:keys [file error]}]
                                  (when error
                                    (str "Unreadable evidence file " (.getName file) ": " error)))
                                parsed-files))
        disk-seqs (keep (comp :evidence/chain-seq :data) parsed-files)
        max-disk-seq (when (seq disk-seqs) (apply max disk-seqs))
        chain-integrity (when (and (seq disk-seqs) (empty? parse-errors))
                          (try
                            (let [verify-chain (requiring-resolve
                                                'resolver-sim.io.event-evidence/verify-chain-integrity)]
                              (verify-chain (.getPath ev-dir)))
                            (catch Exception e
                              {:valid false
                               :error (.getMessage e)})))
        errors (cond-> parse-errors
                 (pos? (- disk-count registry-count))
                 (conj (str "Unregistered evidence: " disk-count " files on disk, "
                            registry-count " in registry"))
                 (and (some? max-disk-seq) (pos? max-disk-seq) (< cursor-seq max-disk-seq))
                 (conj (str "Cursor behind disk: cursor seq " cursor-seq
                            " but disk evidence has seq up to " max-disk-seq))
                 (and chain-integrity (not (:valid chain-integrity)))
                 (conj (str "Evidence chain integrity failed: "
                            (or (:error chain-integrity)
                                (pr-str (:violations chain-integrity))))))]
    (doseq [e errors]
      (log/error! :evidence-reconciliation-error {:error e}))
    (when (and throw-on-error (seq errors))
      (throw (ex-info "Evidence reconciliation failed"
                      {:errors errors
                       :disk-count disk-count
                       :registry-count registry-count
                       :cursor-seq cursor-seq
                       :max-disk-seq max-disk-seq
                       :parse-errors parse-errors
                       :chain-integrity chain-integrity})))
    {:reconciled? (empty? errors)
     :disk-count disk-count
     :registry-count registry-count
     :cursor-seq cursor-seq
     :max-disk-seq max-disk-seq
     :parse-errors parse-errors
     :chain-integrity chain-integrity
     :errors errors}))

;; ── Aggregate Cursor ───────────────────────────────────────────────────
;; A run-level aggregate cursor that commits to all scenario chain heads.

(defn build-aggregate-cursor
  "Build an aggregate cursor that commits to all scenario chain heads.
   Takes the accumulated scenario evidence snapshots and produces a
   cursor referencing every scenario chain, total evidence count,
   and registry root hash.

   Returns a cursor data map or nil if no scenarios ran."
  [scenario-snapshots registry-root-hash]
  (when (seq scenario-snapshots)
    (let [scenario-heads (keep (fn [s]
                                 (when-let [c (:cursor s)]
                                   {:scenario/seq (:cursor/final-seq c)
                                    :scenario/last-hash (:cursor/final-self-hash c)
                                    :scenario/total-captured (:cursor/total-captured c)}))
                               scenario-snapshots)
          total-evidence (reduce + (map :cursor/total-captured scenario-heads))]
      {:cursor/scope :aggregate-run
       :cursor/scenario-count (count scenario-heads)
       :cursor/scenario-heads scenario-heads
       :cursor/total-evidence total-evidence
       :cursor/registry-root-hash registry-root-hash
       :cursor/reconciled? true})))

;; ── Forensic-Grade Acceptance Criteria ───────────────────────────────────────
;;
;; A run is forensic-grade only if:
;;   1. Registry hash verifies against all artifact entries
;;   2. Registry hash is signed by a known signer (optional but checked when present)
;;   3. Final chain cursor verifies against the evidence chain
;;   4. TSA token verifies against the registry hash or signed attestation
;;   5. Strict validation passes (checked externally)

;; ── Dynamic Signing Context ──────────────────────────────────────────────────
;;
;; Dynamic vars for threading signing credentials through the pipeline without
;; changing protocol or function signatures. Bound at the entry point (CLI,
;; runner) and read by replay-with-protocol when replay-opts don't provide keys.

(def ^:dynamic *signing-key*
  "Path to Ed25519 private key for forensic-grade evidence chain signing.
   Bound at pipeline entry (CLI, runner) and read by replay-with-protocol
   when replay-opts don't provide :signing-key."
  nil)

(def ^:dynamic *signing-password*
  "Password for *signing-key*."
  nil)

;; ── Forensic-Grade Attestation ─────────────────────────────────────────────┬⌐
;;                                                                           │
;; These functions extend the research-grade pipeline with cryptographic      │
;; signing, timestamp binding, and signature-anchored cursors.                │
;;                                                                           │
;; Flow:                                                                     │
;;   1. build-registry  (self-hashed registry map)                            │
;;   2. sign-registry!  (Ed25519 signature over registry-hash)               │
;;   3. write-registry! (persist registry + signature artifact)              │
;;   4. write-chain-cursor-final! (persist signed cursor snapshot)           │
;;                                                                           │
;; Call finalize-and-attest! to run the full pipeline in one step.           │
;; ───────────────────────────────────────────────────────────────────────────

(defn sign-registry!
  "Sign the registry-hash with an Ed25519 private key.
   Returns {:signature hex :signer path :signed-at iso :hash hex}
   or nil if no registry-hash is present."
  [registry & {:keys [private-key-path password]
               :or {password nil}}]
  (when-let [reg-hash (:registry-hash registry)]
    (let [sig (signing/sign-hash reg-hash private-key-path password)]
      {:signature sig
       :signer private-key-path
       :signed-at (now-iso)
       :hash reg-hash
       :schema/version (evcfg/schema :signature)})))

(defn write-registry-signature!
  "Sign the registry hash and persist the signature to
   signature.json alongside the registry. Returns {:signature ... :path ...}
   or nil if no key is provided."
  [registry & {:keys [private-key-path password dir]
               :or {password nil}}]
  (when private-key-path
    (let [sig-map (sign-registry! registry :private-key-path private-key-path :password password)
          out-dir (or dir (str (evcfg/artifact-dir)))
          sig-path (str out-dir "/" "signature.json")
          env-path (str out-dir "/" "envelope.json")]
      (when sig-map
        (.mkdirs (io/file out-dir))
        (spit sig-path (json/write-str sig-map {:indent true}))
        (let [envelope {:registry_sha256 (:hash sig-map)
                        :schema/version (evcfg/schema :envelope)
                        :signed-at (:signed-at sig-map)
                        :signer (:signer sig-map)
                        :signature (:signature sig-map)
                        :chain-final true}]
          (spit env-path (json/write-str envelope {:indent true})))
        (log/info! :registry-signed {:registry-hash (:hash sig-map)})
        {:signature sig-map
         :signature-path sig-path
         :envelope-path env-path}))))

(defn verify-registry-signature
  "Verify an Ed25519 signature over the registry-hash.
   registry can be a registry map (with :registry-hash) or a hash string.
   signature-map should have :signature and :signer keys.
   Returns {:valid true/false :hash h} or {:error ...}."
  [registry signature-map]
  (try
    (let [h (if (string? registry) registry (:registry-hash registry))
          sig (:signature signature-map)
          pub-key-path (str (:signer signature-map) ".pub")]
      (if (and h sig (:signer signature-map))
        (let [valid? (true? (signing/verify-signature h sig pub-key-path))]
          (cond-> {:valid valid? :hash h}
            (not valid?) (assoc :reason :invalid-signature)))
        {:valid false :reason :missing-signature-data
         :error "Missing hash, signature, or signer"}))
    (catch Exception e
      {:valid false :reason :signature-verification-error
       :error (.getMessage e)})))

(defn verify-cursor-signature
  "Verify the Ed25519 signature on a chain-cursor-final artifact.
   cursor should be the parsed cursor JSON artifact.
   Reconstructs cursor-data the same way enrich-cursor-data does
   during signing — using the :cursor/source map embedded in the
   artifact alongside the cursor snapshot fields.

   Note: :cursor/scope is stored as a keyword internally but
   becomes a string through JSON serialization.  The
   verify-registry-hash function must promote known keyword
   values back to keywords so the hash matches."
  [cursor]
  (try
    (let [base {:cursor/scope (keyword (:cursor/scope cursor))
                :cursor/final-seq (:cursor/final-seq cursor)
                :cursor/final-self-hash (:cursor/final-self-hash cursor)
                :cursor/total-captured (:cursor/total-captured cursor)}
          ;; Use the embedded :cursor/source map directly.  The artifact
          ;; stores it as written by enrich-cursor-data, so reconstructing
          ;; from top-level source-key copies would risk key name drift.
          cursor-data (if (contains? cursor :cursor/source)
                        (assoc base :cursor/source (:cursor/source cursor))
                        base)
          h (hc/hash-with-intent {:hash/intent :evidence-chain} cursor-data)
          recorded-hash (:cursor/signed-hash cursor)
          forensic (:cursor/forensic cursor)]
      (if (and forensic recorded-hash)
        (if (hc/intent-hash= h recorded-hash)
          (let [sig (or (:cursor/signature forensic) (:signature forensic))
                signer (or (:cursor/signer forensic) (:signer forensic))
                pub-key-path (str signer ".pub")]
            (if (and sig signer)
              (let [valid? (true? (signing/verify-signature h sig pub-key-path))]
                (cond-> {:valid valid? :hash h :cursor-seq (:cursor/final-seq cursor)}
                  (not valid?) (assoc :reason :invalid-signature)))
              {:valid false :reason :missing-signature-data
               :error "Missing signature or signer in cursor forensic data"}))
          {:valid false :reason :signed-payload-mismatch
           :hash h :recorded-hash recorded-hash
           :error "Cursor data hash does not match signed hash"})
        {:valid false :reason :missing-signature-data
         :error "Cursor has no forensic signature data"}))
    (catch Exception e
      {:valid false :reason :signature-verification-error
       :error (.getMessage e)})))

;; ── Forensic-Grade Acceptance Criteria ───────────────────────────────────────
;;
;; A run is forensic-grade only if:
;;   1. Registry hash verifies against all artifact entries
;;   2. Registry hash is signed by a known signer (optional but checked when present)
;;   3. Final chain cursor verifies against the evidence chain
;;   4. TSA token verifies against the registry hash or signed attestation
;;   5. Strict validation passes (checked externally)

(defn forensic-status
  "Check all forensic-grade acceptance criteria for a completed run.
   Returns a map with :all-pass? boolean and per-criterion status.

   Reads artifacts from dir (default: artifact-dir). Criterion 5 (strict
   validation) is checked externally via build-evidence-registry! with
   :strict true — this function checks criteria 1-4."
  [& {:keys [registry dir registry-hash]
      :or {dir (str (evcfg/artifact-dir))}}]
  (let [registry-path (str dir "/evidence-registry.json")
        registry (or registry
                     (try (json/read-str (slurp registry-path)
                                         :key-fn keyword)
                          (catch Exception e
                            (log/warn! "forensic-status: failed to read evidence registry" {:path registry-path :error (.getMessage e)})
                            nil)))
        sig-path (str dir "/signature.json")
        tsr-path (str dir "/time-stamping-authority/tsa-response.tsr")
        cur-path (str dir "/chain-cursor-final.json")
        rh (or registry-hash (:registry-hash registry))

        c1 (if registry
             (let [v (verify-registry-hash registry)]
               {:criterion :registry-hash-verifies
                :pass (:valid v)
                :detail v})
             {:criterion :registry-hash-verifies
              :pass false
              :detail {:reason :missing-evidence-registry
                       :path registry-path}})

        c2 (when (and rh (.exists (io/file sig-path)))
             (try
               (let [sig-map (json/read-str (slurp sig-path) :key-fn keyword)
                     v (verify-registry-signature registry sig-map)]
                 {:criterion :registry-hash-signed
                  :pass (:valid v)
                  :detail v})
               (catch Exception e
                 {:criterion :registry-hash-signed
                  :pass false
                  :detail {:error (.getMessage e)}})))

        c3 (if (.exists (io/file cur-path))
             (try
               (let [cursor (json/read-str (slurp cur-path) :key-fn keyword)
                     v (verify-cursor-signature cursor)]
                 {:criterion :cursor-verifies
                  :pass (:valid v)
                  :detail v})
               (catch Exception e
                 {:criterion :cursor-verifies
                  :pass false
                  :detail {:reason :cursor-read-error
                           :error (.getMessage e)}}))
             {:criterion :cursor-verifies
              :pass false
              :detail {:reason :missing-chain-cursor
                       :path cur-path}})

        c4 (when (and rh (.exists (io/file tsr-path)))
             (try
               (let [v (ts/verify-tsa-token-from-file rh :dir dir)]
                 {:criterion :tsa-token-verifies
                  :pass (:timestamp/verified? v)
                  :detail v})
               (catch Exception e
                 {:criterion :tsa-token-verifies
                  :pass false
                  :detail {:error (.getMessage e)}})))

        criteria (vec (remove nil? [c1 c2 c3 c4]))
        all-pass? (and (seq criteria) (every? :pass criteria))]
    {:all-pass? all-pass?
     :criteria-met (count (filter :pass criteria))
     :criteria-total (count criteria)
     :criteria criteria}))

(defn evidence-root-hash
  "Read the final chain cursor and return the evidence root hash.
   Returns nil if the cursor file doesn't exist or can't be read."
  [& {:keys [dir] :or {dir (str (evcfg/artifact-dir))}}]
  (try
    (let [cursor (json/read-str (slurp (str dir "/chain-cursor-final.json"))
                                :key-fn keyword)]
      (:cursor/final-self-hash cursor))
    (catch Exception e
      (log/warn! :evidence-root-hash-read-failed
                 {:dir dir :error (.getMessage e)})
      nil)))

(defn finalize-and-attest!
  "Full forensic-grade evidence chain finalization in one step.
   Builds the registry, writes it, signs the registry hash, timestamps
   the registry hash via RFC 3161 TSA, persists the signed cursor.

   Sequencing (per forensic-grade spec):
     1. Build all evidence records (during replay)
     2. Finalize evidence chain (build-registry)
     3. Write final chain cursor
     4. Build registry (registry-hash commits to all entries)
     5. Compute registry hash
     6. Sign registry hash (optional, via private-key-path)
     7. Submit registry hash to TSA (optional, via tsa-url)
     8. Store TSA sidecar artifacts (registry.tsr, registry.tsq, registry.tsa.json)
     9. Verify TSA response locally

   Optional:
     :run-id            — identifier for the run
     :private-key-path  — path to Ed25519 private key for signing
     :password          — private key password
     :tsa-url           — RFC 3161 TSA URL (falls back to ts/*tsa-url*)
     :dir               — output directory (default: artifact-dir)

   Returns:
     {:registry       registry-map
      :registry-path  path to evidence-registry.json
      :signature      {:signature ... :path ... :envelope-path ...} or nil
      :cursor-path    path to chain-cursor-final.json or nil
      :tsa            TSA result map or nil
      :forensic?      boolean: all configured forensic checks passed}"
  [& {:keys [run-id private-key-path password tsa-url dir
             allow-dirty? run-config-hash]
      :or {run-id "unknown" password nil}}]
  (let [allow-dirty? (or allow-dirty? *allow-dirty* false)
        registry (build-registry :run-id run-id)
        reg-hash (:registry-hash registry)
        reg-path (write-registry! registry
                                  (when dir (str (io/file dir evidence-registry-filename))))
        sig-result (write-registry-signature! registry
                                              :private-key-path private-key-path
                                              :password password
                                              :dir dir)
        cursor-path (write-chain-cursor-final!
                     :dir dir
                     :run-id run-id
                     :private-key-path private-key-path
                     :password password
                     :allow-dirty? allow-dirty?
                     :run-config-hash run-config-hash)
        tsa-url (or tsa-url ts/*tsa-url*)
        tsa-result (when (and tsa-url reg-hash)
                     (ts/write-tsa-timestamp! reg-hash
                                              :tsa-url tsa-url
                                              :dir dir))]
    {:registry registry
     :registry-path reg-path
     :signature sig-result
     :cursor-path cursor-path
     :tsa tsa-result
     :forensic? (boolean (or sig-result
                             (and tsa-result (not (:error tsa-result)))))}))
