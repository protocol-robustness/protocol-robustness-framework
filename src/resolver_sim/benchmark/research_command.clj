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
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "research-command.v1")

(def ^:const valid-command-types
  "Controlled vocabulary for command types."
  #{:benchmark-evaluation :theorem-evaluation :claim-evaluation
    :invariant-check :evidence-projection :state-projection})

(defn valid-command-type?
  [t]
  (contains? valid-command-types t))

(defn- normalise-includes
  "Normalise :command/include to a sorted vector of keywords.
   Accepts a set, vector, or single keyword."
  [includes]
  (cond
    (nil? includes) []
    (keyword? includes) [includes]
    (set? includes) (vec (sort includes))
    (sequential? includes) (vec (sort includes))
    :else (vec (sort includes))))

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
          computed-hash (str "sha256:"
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
  "Structural validity check for a research command."
  [command]
  (and (= schema-version (:schema-version command))
       (some? (:command/id command))
       (some? (:command/type command))
       (some? (:command/hash command))
       (sequential? (:command/argv command))
       (seq (:command/argv command))))

(defn validate-command
  "Standalone validator for a loaded research command.
   Recomputes the command hash and checks structural integrity.

   Returns {:valid? bool :errors [string]}."
  [command]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version command))
      (swap! errors conj (str "expected schema-version " schema-version
                               " got " (:schema-version command))))
    (when-not (some? (:command/id command))
      (swap! errors conj "missing :command/id"))
    (when-not (some? (:command/type command))
      (swap! errors conj "missing :command/type"))
    (when (and (some? (:command/type command))
               (not (valid-command-type? (:command/type command))))
      (swap! errors conj (str "invalid :command/type: " (:command/type command))))
    (when (or (nil? (:command/argv command))
              (not (sequential? (:command/argv command)))
              (empty? (:command/argv command)))
      (swap! errors conj ":command/argv must be a non-empty vector"))
    (when (some? (:command/hash command))
      (let [without-hash (dissoc command :command/hash)
            computed (str "sha256:" (hc/domain-hash :research-command without-hash))]
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
