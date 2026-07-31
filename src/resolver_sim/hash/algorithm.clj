(ns resolver-sim.hash.algorithm
  "Minimal explicit hash-algorithm representation for workflow-group artifacts.

   The repository effectively uses SHA-256 throughout; this namespace makes the
   algorithm an explicit, declared value rather than an implicit constant, and
   provides validation so callers cannot silently fall back to SHA-256 for an
   unsupported algorithm.

   Deliberately NOT a cryptographic framework: there is no runtime-pluggable
   digest loading, no algorithm negotiation, and no migration machinery for
   hypothetical algorithms. Canonical hashing still flows through
   resolver-sim.hash.canonical (SHA-256 domain hashing).")

(def supported-hash-algorithms
  "The set of hash algorithms this codebase commits to and can verify."
  #{:sha256})

(def default-hash-algorithm
  "Algorithm assumed when a workflow-group artifact does not declare one."
  :sha256)

(defn supported-hash-algorithm?
  "True when `algo` is a supported, committable hash algorithm."
  [algo]
  (contains? supported-hash-algorithms algo))

(defn validate-hash-algorithm!
  "Return `algo` when supported, otherwise throw. Never silently falls back to
   SHA-256 for an unsupported algorithm."
  [algo]
  (when-not (supported-hash-algorithm? algo)
    (throw (ex-info "unsupported hash algorithm"
                    {:type :unsupported-hash-algorithm
                     :algorithm algo
                     :supported supported-hash-algorithms})))
  algo)

(defn hash-algorithm-string
  "Stable textual form of a hash algorithm keyword (e.g. :sha256 -> \"sha256\").
   Validates that the algorithm is supported."
  [algo]
  (name (validate-hash-algorithm! algo)))
