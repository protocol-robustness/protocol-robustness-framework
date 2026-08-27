(ns resolver-sim.benchmark.research-command
  "Research command: a structured execution provenance artifact.

   The exact command used to produce a theorem or conclusion is committed
   as execution provenance. Rather than embedding shell text alone, the
   command is a structured artifact capturing:
     - the normalised semantic command (argv and includes)
     - environment, runner, input, and output roots
     - the canonical hash of the complete execution context

   This allows harmless argument ordering or alias variations to retain
   the same identity while still providing full reproducibility metadata."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.hash.sequence :as seq]))

(def ^:const schema-version "research-command.v1")
(def ^:const schema-version-v2 "research-command.v2")

(def ^:const valid-command-types
  "Controlled vocabulary for command types."
  #{:benchmark-evaluation :theorem-evaluation :claim-evaluation
    :invariant-check :evidence-projection :state-projection})

(defn valid-command-type?
  [t]
  (contains? valid-command-types t))

(def ^:const valid-command-includes
  "Evaluation domains that a research command may request.
   DEPRECATED in favour of typed scope-refs (:command/includes)."
  #{:incentive :incentive-compatibility})

(defn valid-command-include?
  [include]
  (contains? valid-command-includes include))

;; ── Typed scope refs (research-command.v2) ──────────────────────────────────

(def ^:const valid-command-include-kinds
  "Typed scope-ref kinds for :command/includes entries.
   Each kind represents a different dimension of what a command's
   research scope means.

     :research-scope/analysis   — the specific analysis (incentive,
                                  incentive-compatibility, …) the command
                                  executes; distinct from consensus dimensions
     :research-scope/dimension  — a consensus dimension the command targets
     :research-scope/artifact   — an artifact class the command produces
                                  (extensible)
     :research-scope/model      — a model variant the command exercises
                                  (extensible)"
  #{:research-scope/analysis
    :research-scope/dimension
    :research-scope/artifact
    :research-scope/model})

(def ^:const valid-command-scope-refs
  "Known scope refs by kind.  Extensible — callers that discover new
   refs can add them without changing the kind vocabulary."
  {:research-scope/analysis
   #{:research-analysis/incentive
     :research-analysis/incentive-compatibility}
   :research-scope/dimension
   #{:incentives/participants
     :incentives/strategies
     :incentives/coalitions}
   :research-scope/artifact
   #{}
   :research-scope/model
   #{}})

(defn valid-scope-ref?
  "True when the typed scope-ref {:kind kw :ref kw} has a known kind
   and the ref is in the known set for that kind."
  [{:keys [kind ref]}]
  (and (contains? valid-command-include-kinds kind)
       (contains? (get valid-command-scope-refs kind #{}) ref)))

(def ^:private legacy-include->scope-ref
  "Migration map from deprecated :command/include keywords to typed
   scope-refs.  Preserves meaning: :incentive is an analysis directive,
   not a consensus-dimension alias."
  {:incentive               {:kind :research-scope/analysis
                             :ref :research-analysis/incentive}
   :incentive-compatibility {:kind :research-scope/analysis
                             :ref :research-analysis/incentive-compatibility}})

(defn- normalise-includes
  "Normalise :command/include to a sorted vector of supported keywords.
   Accepts a set, vector, or single keyword."
  [includes]
  (let [values (cond
                 (nil? includes) []
                 (keyword? includes) [includes]
                 (set? includes) (vec includes)
                 (sequential? includes) (vec includes)
                 :else ::invalid)]
    (when (= ::invalid values)
      (throw (ex-info ":command/include must be a keyword or collection of keywords"
                      {:command/include includes})))
    (when-not (every? keyword? values)
      (throw (ex-info ":command/include entries must be keywords"
                      {:command/include includes})))
    (let [unsupported (remove valid-command-include? values)]
      (when (seq unsupported)
        (throw (ex-info "Unsupported :command/include value"
                        {:command/include includes :unsupported (vec unsupported)}))))
    (vec (sort (distinct values)))))

(defn- normalise-includes-v2
  "Normalise :command/includes to a sorted vector of typed scope-refs.

   Accepts:
     - nil → []
     - a single keyword (legacy migrate) → [{...}]
     - a vector or set of keywords (legacy migrate) → [{...} ...]
     - a vector or set of typed scope-ref maps → [{...} ...]

   Legacy keywords (:incentive, :incentive-compatibility) are migrated
   to :research-scope/analysis scope-refs via legacy-include->scope-ref.
   Unknown keywords are rejected.

   Returns a sorted vector of {:kind kw :ref kw} maps."
  [includes]
  (let [raw (cond
              (nil? includes) []
              (keyword? includes) [includes]
              (set? includes) (vec includes)
              (sequential? includes) (vec includes)
              :else ::invalid)]
    (when (= ::invalid raw)
      (throw (ex-info ":command/includes must be a keyword, scope-ref map, or collection"
                      {:command/includes includes})))
    (let [entries (mapv (fn [entry]
                          (cond
                            (keyword? entry)
                            (if-let [sr (get legacy-include->scope-ref entry)]
                              sr
                              (throw (ex-info "Unsupported legacy :command/include keyword"
                                              {:command/includes includes
                                               :entry entry
                                               :supported-legacy (keys legacy-include->scope-ref)})))
                            (and (map? entry) (:kind entry) (:ref entry))
                            entry
                            :else
                            (throw (ex-info ":command/includes entries must be keywords or {:kind kw :ref kw} maps"
                                            {:command/includes includes
                                             :entry entry}))))
                        raw)]
      (when-not (every? valid-scope-ref? entries)
        (let [invalid (remove valid-scope-ref? entries)]
          (throw (ex-info "Unsupported :command/includes scope-ref(s)"
                          {:command/includes includes :invalid invalid}))))
      (vec (sort-by (juxt :kind :ref) (distinct entries))))))

(defn build-command
  "Build a canonical research command artifact.

   Research-command.v1 (default):
     Required:
       command/id              — qualified keyword
       command/type            — :benchmark-evaluation | :theorem-evaluation | ...
       command/argv            — [\"prf\" \"benchmark\" \"run-and-report\" ...]
     Optional:
       command/include         — [:incentive :incentive-compatibility] | single kw

   Research-command.v2 (schema-version \"research-command.v2\"):
     Required:
       command/id, command/type, command/argv (same as v1)
     Optional:
       command/includes        — [{:kind :research-scope/analysis
                                    :ref :research-analysis/incentive} ...]
                                  or legacy keywords (auto-migrated)

   Both versions accept:
       command/environment-root — sha256
       command/runner-root     — sha256
       command/input-root      — sha256
       command/output-root     — sha256

   V1 and V2 produce different :command/hash values — the schema version
   and include representation are committed into the hash.  A command
   built under one version cannot masquerade as the other.

   Returns the command map with :command/hash computed."
  [{:keys [command/id
           command/type
           command/argv
           command/include
           command/includes
           command/environment-root
           command/runner-root
           command/input-root
           command/output-root
           command/hash]
    :as options}]
  (let [sv (or (:schema-version options) schema-version)
        v2? (= schema-version-v2 sv)
        errors (atom [])
        _ (when v2? (when (and (some? include) (some? includes))
                      (swap! errors conj "supply :command/includes (v2) or :command/include (v1), not both")))
        _ (when-not (some? id)
            (swap! errors conj "missing :command/id"))
        _ (when (and (some? id) (not (keyword? id)))
            (swap! errors conj ":command/id must be a keyword"))
        _ (when-not (and (some? type) (valid-command-type? type))
            (swap! errors conj "missing or invalid :command/type"))
        _ (when (or (nil? argv) (not (sequential? argv)) (empty? argv))
            (swap! errors conj ":command/argv must be a non-empty sequential"))
        _ (when-not (#{schema-version schema-version-v2} sv)
            (swap! errors conj (str "unsupported schema-version: " sv)))
        _ (when-let [req-sv (:requested-schema-version options)]
            (when-not (= sv req-sv)
              (swap! errors conj "cannot supply both :schema-version and :requested-schema-version with different values")))
        _ (when (seq @errors)
            (throw (ex-info (str "Command build failed: " (str/join "; " @errors))
                            {:errors @errors})))
        normalised-includes (if v2?
                              (normalise-includes-v2 (or includes include))
                              (normalise-includes include))
        base (cond-> {:schema-version sv
                      :command/id id
                      :command/type type
                      :command/argv (vec argv)
                      :command/environment-root environment-root
                      :command/runner-root runner-root
                      :command/input-root input-root
                      :command/output-root output-root}
               v2? (assoc :command/includes normalised-includes)
               (not v2?) (assoc :command/include normalised-includes))
        computed-hash (hash-ref/sha256-ref
                       (hc/domain-hash :research-command base))]
    (when (and (some? hash) (not= hash computed-hash))
      (throw (ex-info "Declared command/hash does not match computed value"
                      {:declared hash :computed computed-hash})))
    (assoc base :command/hash computed-hash)))

(defn command-hash
  "Return the content-addressed hash of a command."
  [command]
  (:command/hash command))

(defn command-valid?
  "Structural validity check for a research command. Consistent with
   validate-command: a declaration is invalid without a present, keyword
   :command/id or a committed :command/hash.

   Supports both research-command.v1 and research-command.v2 schema
   versions."
  [command]
  (let [v (or (:schema-version command) schema-version)]
    (and (contains? #{schema-version schema-version-v2} v)
         (keyword? (:command/id command))
         (some? (:command/type command))
         (some? (:command/hash command))
         (sequential? (:command/argv command))
         (seq (:command/argv command))
         (if (= schema-version-v2 v)
           (and (vector? (:command/includes command))
                (every? (fn [entry]
                          (and (map? entry)
                               (:kind entry)
                               (:ref entry)))
                        (:command/includes command)))
           (and (vector? (:command/include command))
                (every? valid-command-include? (:command/include command)))))))

(defn validate-command
  "Standalone validator for a loaded research command.
   Recomputes the command hash and checks structural integrity.

   Supports both research-command.v1 and research-command.v2 schema
   versions.

   A declaration is valid only when it carries a committed :command/hash and a
   present keyword :command/id (mirroring build-command). A malformed
   command-looking map therefore cannot pass validation merely by resembling a
   command.

   Returns {:valid? bool :errors [string]}."
  [command]
  (let [errors (atom [])
        sv (:schema-version command)]
    (when-not (contains? #{schema-version schema-version-v2} sv)
      (swap! errors conj (str "expected schema-version " schema-version
                              " or " schema-version-v2 " got " sv)))
    (when-not (and (some? (:command/id command))
                   (keyword? (:command/id command)))
      (swap! errors conj ":command/id must be a present keyword"))
    (when-not (some? (:command/type command))
      (swap! errors conj "missing :command/type"))
    (when (and (some? (:command/type command))
               (not (valid-command-type? (:command/type command))))
      (swap! errors conj (str "invalid :command/type: " (:command/type command))))
    (when (or (nil? (:command/argv command))
              (not (sequential? (:command/argv command)))
              (empty? (:command/argv command)))
      (swap! errors conj ":command/argv must be a non-empty vector"))
    (if (= schema-version-v2 sv)
      (do
        (when-not (vector? (:command/includes command))
          (swap! errors conj ":command/includes must be a vector of typed scope-refs"))
        (when (and (vector? (:command/includes command))
                   (not (every? valid-scope-ref? (:command/includes command))))
          (let [invalid (remove valid-scope-ref? (:command/includes command))]
            (swap! errors conj (str "unsupported :command/includes scope-ref(s): "
                                    (pr-str invalid))))))
      (do
        (when-not (vector? (:command/include command))
          (swap! errors conj ":command/include must be a vector of supported keywords"))
        (when (and (vector? (:command/include command))
                   (not (every? valid-command-include? (:command/include command))))
          (swap! errors conj (str "unsupported :command/include value(s): "
                                  (pr-str (remove valid-command-include?
                                                  (:command/include command))))))))
    (when (nil? (:command/hash command))
      (swap! errors conj ":command/hash is required"))
    (when (some? (:command/hash command))
      (let [without-hash (dissoc command :command/hash)
            computed (hash-ref/sha256-ref (hc/domain-hash :research-command without-hash))]
        (when-not (= computed (:command/hash command))
          (swap! errors conj (str "command/hash mismatch: declared "
                                  (:command/hash command)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn command-semantic-identity
  "The semantic identity of a command for comparison purposes.
   Two commands share the same semantic identity when they target
   the same includes — regardless of argument ordering or aliases.

   For v1 commands: returns a sorted set of include keywords.
   For v2 commands: returns a sorted set of [kind ref] pairs."
  [command]
  (if (= schema-version-v2 (:schema-version command))
    (set (map (juxt :kind :ref) (:command/includes command)))
    (set (:command/include command))))

(defn same-semantic-command?
  "True when two commands share the same semantic include set."
  [a b]
  (= (command-semantic-identity a) (command-semantic-identity b)))

;; ── trace metrics (command-count / command-valid-count) ────────────────────

(def ^:const trace-domain-tag
  "Domain tag of a research-command trace root.
   DEPRECATED for new commitments: use research-command-trace.v2
   (canonical-value-sequence.v1 contract) instead.  The v1 tag is registered
   in resolver-sim.hash.canonical/domain-tags; it hashes under the registered
   keyword so prefix-freeness governance applies."
  :research-command-trace-v1)

(defn command-trace-root
  "Content root of a collection (trace) of research command declarations.
   Committed so the derived metrics are reproducible: the same declarations
   produce the same root and the same numbers. Declarations are ordered by a
   type-safe rendering of :command/id (a malformed declaration may carry a
   keyword, string, or nil id), and set-valued fields are projected to
   canonical-safe form before hashing."
  [commands]
  (hash-ref/sha256-ref
   (hc/domain-hash trace-domain-tag
                   (hc/project-committable-content
                    (vec (sort-by (comp pr-str :command/id) commands))))))

(defn command-trace-metrics
  "Derive the research-command trace metrics from ONE canonical snapshot of
   trace entries.

   DEPRECATED for new commitments: use command-trace-metrics-v2, which
   produces a research-command-trace.v2 root under the
   canonical-value-sequence.v1 contract.

      :command-count        — number of discovered command declarations (entries
                             carrying the :command/id key)
     :command-valid-count  — number passing validate-command under the same
                             snapshot
     :combination-count    — number of combination artifacts (entries carrying
                             the :combination/id key) — relations over
                             already-identified roots, never counted as
                             commands
     :trace/root           — canonical root binding the exact command
                             declarations
     :trace/valid?         — true when every command declaration passes
                             validation
     :trace/skipped        — number of entries that carry neither the
                             :command/id nor the :combination/id discriminator
                             (evidence maps, garbage, …)

   Category contract:
     - a command declaration is an entry carrying the :command/id key (presence,
       not truthiness); a malformed or command-looking entry that carries the
       key is DISCOVERED but invalid, never silently skipped;
     - a combination is an entry carrying the :combination/id key (without
       :command/id): it is a distinct first-class trace entity, counted
       separately, and never affects :command-count / :command-valid-count;
     - duplicate :command/id values are a malformed trace and FAIL CLOSED
       (:command-trace/duplicate-command-id) — the metric never silently
       deduplicates and never counts a duplicated declaration as two distinct
       ones;
     - combinations are relations over already-identified roots; they are never
       inspected to infer additional commands."
  [commands]
  (let [commands (vec commands)
        declarations (filterv #(contains? % :command/id) commands)
        combinations (filterv #(and (contains? % :combination/id)
                                    (not (contains? % :command/id)))
                              commands)
        duplicates (->> (frequencies (map :command/id declarations))
                        (filter (fn [[_ n]] (> n 1)))
                        (map key)
                        vec)]
    (when (seq duplicates)
      (throw (ex-info "command trace: duplicate :command/id declarations"
                      {:error :command-trace/duplicate-command-id
                       :duplicates duplicates
                       :declaration-count (count declarations)})))
    (let [valid-count (count (filter :valid? (map validate-command declarations)))]
      {:command-count (count declarations)
       :command-valid-count valid-count
       :combination-count (count combinations)
       :trace/root (command-trace-root declarations)
       :trace/valid? (= (count declarations) valid-count)
       :trace/skipped (- (count commands) (count declarations) (count combinations))})))

;; ── research-command-trace.v2 ────────────────────────────────────────────────

(def ^:const command-trace-v2-schema-version "research-command-trace.v2")

(def ^:const command-trace-v2-purpose
  "Purpose tag bound into every research-command-trace.v2 commitment via
   the canonical-value-sequence.v1 contract.  Separates command traces
   from all other sequence commitments."
  :research-command-trace)

(def ^:const command-trace-v2-domain
  "Domain tag for research-command-trace.v2 content-addressed hashing."
  :research-command-trace-v2)

(defn build-command-trace-v2
  "Build a v2 command trace: a canonical-value-sequence.v1 commitment over
   the ordered sequence of command hashes.

    Fails closed:
      - Empty commands vector → :trace/empty-trace (a trace with no
        commands provides no meaningful provenance)
      - Any component without a valid :command/hash → :trace/invalid-component
        (the trace commitment refuses invalid components outright; metrics
        can separately count them as discovered-but-invalid)

    Components are command :command/hash values, not full command maps.
    The trace commits to ordered composition of command *identities* — it does
    not redefine what a command means.

    Returns {:trace/schema-version \"research-command-trace.v2\"
             :trace/purpose :research-command-trace
             :trace/component-count n
             :trace/components [<sha256>…]
             :trace/root <sha256:hex>
             :trace/commitment <byte-array>}"
  [{:keys [commands]}]
  (when (empty? commands)
    (throw (ex-info "research-command-trace.v2 requires at least one command"
                    {:error :trace/empty-trace
                     :schema-version command-trace-v2-schema-version})))
  (let [invalid (remove (comp :valid? validate-command) commands)]
    (when (seq invalid)
      (throw (ex-info "research-command-trace.v2: all components must carry a valid :command/hash"
                      {:error :trace/invalid-component
                       :invalid-count (count invalid)})))
    (let [command-hashes (mapv :command/hash commands)
          n (count command-hashes)
          options {:purpose command-trace-v2-purpose
                   :expected-component-count n}
          root (hash-ref/sha256-ref
                (seq/sequence-hash options command-hashes))]
      {:trace/schema-version command-trace-v2-schema-version
       :trace/purpose command-trace-v2-purpose
       :trace/component-count n
       :trace/components command-hashes
       :trace/root root
       :trace/commitment (seq/canonical-sequence-bytes options command-hashes)})))

(defn command-trace-root-v2
  "Convenience: return just the v2 trace root from a collection of valid
   command artifacts.  Calls build-command-trace-v2 and extracts :trace/root."
  [commands]
  (:trace/root (build-command-trace-v2 {:commands commands})))

(defn verify-command-trace-v2
  "Verify a v2 command trace from its committed byte encoding.

   Uses verify-sequence-commitment for framing/contract validation, then
   checks that the :purpose matches :research-command-trace.

   Returns {:valid? bool :trace decoded-map :issues [<structured>]}."
  [^bytes ba]
  (let [{:keys [valid? value issues resource-limit? resource-reason]}
        (seq/verify-sequence-commitment ba)]
    (cond
      (not valid?)
      {:valid? false :issues issues
       :resource-limit? resource-limit? :resource-reason resource-reason}

      (nil? value)
      {:valid? false :issues [{:code :trace/null-value :detail "decoded value is nil"}]}

      (not= command-trace-v2-purpose (:purpose value))
      {:valid? false
       :issues [{:code :trace/wrong-purpose
                 :expected command-trace-v2-purpose
                 :actual (:purpose value)}]}

      :else
      {:valid? true
       :trace value
       :issues []})))

(def ^:const command-trace-v3-schema-version "research-command-trace.v3")

(def ^:const command-trace-v3-purpose
  :research-command-executable-provenance)

(defn- valid-executable-provenance?
  [provenance]
  (every? hash-ref/valid-sha256-ref?
          ((juxt :command/root
                 :command/combination-root
                 :command/concatenation-chain-root)
           provenance)))

(defn build-command-trace-v3
  "Build an ordered trace binding a research-command.v2 identity to verified
   executable-command provenance. The components are exactly command hash,
   CC3 command root, include-combination root, and concatenation-chain root."
  [{:keys [research-command executable-command-provenance]}]
  (when-not (:valid? (validate-command research-command))
    (throw (ex-info "research-command-trace.v3 requires a valid research command"
                    {:error :trace/invalid-component})))
  (when-not (valid-executable-provenance? executable-command-provenance)
    (throw (ex-info "research-command-trace.v3 requires rooted executable provenance"
                    {:error :trace/invalid-executable-provenance})))
  (let [components [(:command/hash research-command)
                    (:command/root executable-command-provenance)
                    (:command/combination-root executable-command-provenance)
                    (:command/concatenation-chain-root executable-command-provenance)]
        options {:purpose command-trace-v3-purpose
                 :expected-component-count (count components)}]
    {:trace/schema-version command-trace-v3-schema-version
     :trace/purpose command-trace-v3-purpose
     :trace/component-count (count components)
     :trace/components components
     :trace/root (hash-ref/sha256-ref (seq/sequence-hash options components))
     :trace/commitment (seq/canonical-sequence-bytes options components)}))

(defn command-trace-metrics-v2
  "Derive research-command trace metrics using the v2 trace root.

   Same category contract as command-trace-metrics (command-count,
   command-valid-count, combination-count, trace/skipped, trace/valid?)
   but the :trace/v2-root is computed via build-command-trace-v2 from
   only the valid declarations — invalid declarations are counted as
   discovered but excluded from the trace commitment.

   Returns:
     :command-count         — declared commands
     :command-valid-count   — validated commands
     :combination-count     — combination artifacts
     :trace/v2-root         — canonical-value-sequence.v1 root of valid commands
     :trace/valid?          — true when every declaration validates
     :trace/skipped         — unrecognized entries"
  [commands]
  (let [commands (vec commands)
        declarations (filterv #(contains? % :command/id) commands)
        combinations (filterv #(and (contains? % :combination/id)
                                    (not (contains? % :command/id)))
                              commands)
        duplicates (->> (frequencies (map :command/id declarations))
                        (filter (fn [[_ n]] (> n 1)))
                        (map key)
                        vec)]
    (when (seq duplicates)
      (throw (ex-info "command trace: duplicate :command/id declarations"
                      {:error :command-trace/duplicate-command-id
                       :duplicates duplicates
                       :declaration-count (count declarations)})))
    (let [validation-results (map validate-command declarations)
          valid-declarations (->> (map vector declarations validation-results)
                                  (filter (fn [[_ r]] (:valid? r)))
                                  (map first))
          valid-count (count valid-declarations)
          v2-root (if (pos? valid-count)
                    (:trace/root (build-command-trace-v2 {:commands valid-declarations}))
                    nil)]
      {:command-count (count declarations)
       :command-valid-count valid-count
       :combination-count (count combinations)
       :trace/v2-root v2-root
       :trace/valid? (= (count declarations) valid-count)
       :trace/skipped (- (count commands) (count declarations) (count combinations))})))
