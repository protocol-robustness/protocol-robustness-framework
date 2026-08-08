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
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-command.v1")

(def ^:const valid-command-types
  "Controlled vocabulary for command types."
  #{:benchmark-evaluation :theorem-evaluation :claim-evaluation
    :invariant-check :evidence-projection :state-projection})

(defn valid-command-type?
  [t]
  (contains? valid-command-types t))

(def ^:const valid-command-includes
  "Evaluation domains that a research command may request."
  #{:incentive :incentive-compatibility})

(defn valid-command-include?
  [include]
  (contains? valid-command-includes include))

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

(defn build-command
  "Build a canonical research command artifact.

   Required:
     command/id              — qualified keyword
     command/type            — :benchmark-evaluation | :theorem-evaluation | ...
     command/argv            — [\"prf\" \"benchmark\" \"run-and-report\" ...]

   Optional:
     command/include         — [:incentive :incentive-compatibility] | single kw
     command/environment-root — sha256
     command/runner-root     — sha256
     command/input-root      — sha256
     command/output-root     — sha256
     command/hash            — pre-computed hash (rejected on mismatch)

   Returns the command map with :command/hash computed."
  [{:keys [command/id
           command/type
           command/argv
           command/include
           command/environment-root
           command/runner-root
           command/input-root
           command/output-root
           command/hash]}]
  (let [errors (atom [])]
    (when-not (some? id)
      (swap! errors conj "missing :command/id"))
    (when (and (some? id) (not (keyword? id)))
      (swap! errors conj ":command/id must be a keyword"))
    (when-not (and (some? type) (valid-command-type? type))
      (swap! errors conj "missing or invalid :command/type"))
    (when (or (nil? argv) (not (sequential? argv)) (empty? argv))
      (swap! errors conj ":command/argv must be a non-empty sequential"))
    (when (seq @errors)
      (throw (ex-info (str "Command build failed: " (str/join "; " @errors))
                      {:errors @errors})))
    (let [normalised-includes (normalise-includes include)
          base {:schema-version schema-version
                :command/id id
                :command/type type
                :command/argv (vec argv)
                :command/include normalised-includes
                :command/environment-root environment-root
                :command/runner-root runner-root
                :command/input-root input-root
                :command/output-root output-root}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :research-command base))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared command/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :command/hash computed-hash))))

(defn command-hash
  "Return the content-addressed hash of a command."
  [command]
  (:command/hash command))

(defn command-valid?
  "Structural validity check for a research command. Consistent with
   validate-command: a declaration is invalid without a present, keyword
   :command/id or a committed :command/hash."
  [command]
  (and (= schema-version (:schema-version command))
       (keyword? (:command/id command))
       (some? (:command/type command))
       (some? (:command/hash command))
       (sequential? (:command/argv command))
       (seq (:command/argv command))
       (vector? (:command/include command))
       (every? valid-command-include? (:command/include command))))

(defn validate-command
  "Standalone validator for a loaded research command.
   Recomputes the command hash and checks structural integrity.

   A declaration is valid only when it carries a committed :command/hash and a
   present keyword :command/id (mirroring build-command). A malformed
   command-looking map therefore cannot pass validation merely by resembling a
   command.

   Returns {:valid? bool :errors [string]}."
  [command]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version command))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version command))))
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
    (when-not (vector? (:command/include command))
      (swap! errors conj ":command/include must be a vector of supported keywords"))
    (when (and (vector? (:command/include command))
               (not (every? valid-command-include? (:command/include command))))
      (swap! errors conj (str "unsupported :command/include value(s): "
                              (pr-str (remove valid-command-include?
                                              (:command/include command))))))
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

   Returns a sorted set of include keywords."
  [command]
  (set (:command/include command)))

(defn same-semantic-command?
  "True when two commands share the same semantic include set."
  [a b]
  (= (command-semantic-identity a) (command-semantic-identity b)))

;; ── trace metrics (command-count / command-valid-count) ────────────────────

(def ^:const trace-domain-tag
  "Domain tag of a research-command trace root."
  "RESEARCH_COMMAND_TRACE_V1")

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
   command declarations:

     :command-count        — number of discovered command declarations (entries
                             carrying the :command/id key)
     :command-valid-count  — number passing validate-command under the same
                             snapshot
     :trace/root           — canonical root binding the exact declarations
     :trace/valid?         — true when every declaration passes validation
     :trace/skipped        — number of entries in the input that do NOT carry
                             the :command/id discriminator (combinations,
                             evidence maps, …) — never counted as commands

   Declaration-recognition contract:
     - an entry is a declaration iff it carries the :command/id key (presence,
       not truthiness); a malformed or command-looking entry that carries the
       key is therefore DISCOVERED but invalid, never silently skipped;
     - duplicate :command/id values are a malformed trace and FAIL CLOSED
       (:command-trace/duplicate-command-id) — the metric never silently
       deduplicates and never counts a duplicated declaration as two distinct
       ones;
     - combinations of a command with derived artifacts (add-held custody
       evidence, incentive roots, …) bind to the same command-root and are
       relations over already-identified roots — they are never inspected to
       infer additional commands and never change these counts."
  [commands]
  (let [commands (vec commands)
        declarations (filterv #(contains? % :command/id) commands)
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
       :trace/root (command-trace-root declarations)
       :trace/valid? (= (count declarations) valid-count)
       :trace/skipped (- (count commands) (count declarations))})))
