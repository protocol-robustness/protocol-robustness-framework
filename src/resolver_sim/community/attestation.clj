(ns resolver-sim.community.attestation
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.community.task :as task]
            [clojure.java.io :as io]))

(def ^:const schema-version "community-attestation.v0")
(def ^:const domain-tag "COMMUNITY_ATTESTATION_V0")
(def ^:const attestation-ref-prefix "attestation:sha256:")

(def ^:const supported-predicates
  #{:runner/execution-attested :runner/result-reproduced :runner/result-challenged})

(def ^:const reproduction-statuses
  #{:matched :semantically-matched :mismatched :inconclusive})

(def ^:const evidence-ref-prefix "evidence-node:sha256:")

(defn attestation-ref [hash] (str attestation-ref-prefix hash))
(defn attestation-ref? [s] (and (string? s) (.startsWith s attestation-ref-prefix)))
(defn parse-attestation-ref [s] (when (attestation-ref? s) (subs s (count attestation-ref-prefix))))

(defn valid-sha256?
  "True if s is a 64-char lowercase hex string (a valid SHA-256 digest)."
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))

(defn- normalize-for-hash [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (integer? x) x
    (string? x) x
    (keyword? x) x
    (instance? java.time.Instant x) (str x)
    (vector? x) (mapv normalize-for-hash x)
    (map? x) (persistent!
              (reduce-kv (fn [m k v] (assoc! m (normalize-for-hash k) (normalize-for-hash v)))
                         (transient {}) x))
    (set? x) (vec (sort (map normalize-for-hash x)))
    (sequential? x) (mapv normalize-for-hash x)
    :else (str x)))

(defn- canonical-body [m]
  (dissoc m :attestation/id :attestation/hash :attestation/ref :attestation/signature))

(defn- compute-hash [body]
  (hc/domain-hash domain-tag (normalize-for-hash (canonical-body body))))

(defn parse-evidence-ref
  "Extract the sha256 hex from an evidence-node:sha256:<hex> ref.
   Returns nil if the ref is malformed."
  [s]
  (when (and (string? s) (.startsWith s evidence-ref-prefix))
    (let [hash (subs s (count evidence-ref-prefix))]
      (when (valid-sha256? hash) hash))))

(defn build-execution-attestation
  [m]
  (let [task-ref (:task/ref m)
        runner-id (:runner/id m)
        exec-node-hash (:execution-node-hash m)
        result-proj (:result-projection-hash m)
        _ (assert (task/task-ref? task-ref) "task/ref is required")
        _ (assert (string? runner-id) "runner/id is required")
        _ (assert (parse-evidence-ref exec-node-hash)
                  (str "execution-node-hash must be a valid evidence-node:sha256:<hex>, got: " exec-node-hash))
        _ (assert (valid-sha256? result-proj) (str "result-projection-hash must be a valid sha256 hex, got: " result-proj))
        _ (assert (string? (:code-hash m)) "code-hash is required for execution attestation")
        _ (assert (string? (:env-hash m)) "env-hash is required for execution attestation")
        _ (assert (string? (:bundle-root m)) "bundle-root is required for execution attestation")
        _ (assert (string? (:registry-snapshot-hash m)) "registry-snapshot/hash is required for execution attestation")
        body {:schema-version schema-version
              :attestation/predicate :runner/execution-attested
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id runner-id
                          :code-hash (:code-hash m)
                          :env-hash (:env-hash m)
                          :bundle-root (:bundle-root m)
                          :execution-node-hash exec-node-hash
                          :result-projection-hash result-proj}
              :context {:registry-snapshot/hash (:registry-snapshot-hash m)
                        :code-provenance (:code-provenance m)}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn build-reproduction-attestation
  [m]
  (let [task-ref (:task/ref m)
        orig-att-ref (:original-attestation-ref m)
        comparison-status (:comparison-status m)
        orig-result-proj (:original-result-projection-hash m)
        repro-result-proj (:reproduction-result-projection-hash m)
        repro-exec-node (:reproduction-execution-node-hash m)
        _ (assert (task/task-ref? task-ref) "task/ref is required")
        _ (assert (attestation-ref? orig-att-ref) "original-attestation-ref is required")
        _ (assert (contains? reproduction-statuses comparison-status)
                  (str "comparison-status must be one of: " reproduction-statuses))
        _ (assert (valid-sha256? orig-result-proj) (str "original-result-projection-hash must be a valid sha256 hex, got: " orig-result-proj))
        _ (assert (valid-sha256? repro-result-proj) (str "reproduction-result-projection-hash must be a valid sha256 hex, got: " repro-result-proj))
        _ (assert (parse-evidence-ref repro-exec-node)
                  (str "reproduction-execution-node-hash must be a valid evidence-node:sha256:<hex>, got: " repro-exec-node))
        body {:schema-version schema-version
              :attestation/predicate :runner/result-reproduced
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id (:runner/id m)
                          :code-hash (:code-hash m)
                          :env-hash (:env-hash m)
                          :original-attestation-ref orig-att-ref
                          :original-result-projection-hash orig-result-proj
                          :reproduction-execution-node-hash repro-exec-node
                          :reproduction-result-projection-hash repro-result-proj
                          :comparison-policy (:comparison-policy m)
                          :comparison-status comparison-status
                          :mismatch-artifact-refs (vec (or (:mismatch-artifact-refs m) []))}
              :context {:registry-snapshot/hash nil :code-provenance (:code-provenance m)}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn build-challenge-attestation
  [m]
  (let [task-ref (:task/ref m)
        challenged-ref (:challenged-attestation-ref m)
        _ (assert (task/task-ref? task-ref) "task/ref is required")
        _ (assert (attestation-ref? challenged-ref) "challenged-attestation-ref is required")
        _ (assert (string? (:reason m)) "reason is required for challenge attestation")
        body {:schema-version schema-version
              :attestation/predicate :runner/result-challenged
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id (:runner/id m)
                          :challenged-attestation-ref challenged-ref
                          :challenge-type (or (:challenge-type m) :evidence-unresolvable)
                          :reason (:reason m)
                          :challenge-evidence-refs (vec (or (:challenge-evidence-refs m) []))}
              :context {:registry-snapshot/hash nil :code-provenance (:code-provenance m)}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn sign-attestation!
  "Sign an attestation and record the public key path in its context.
   The key-path allows independent verifiers to resolve the public key
   and verify the signature without external coordination."
  [attestation private-key-path]
  (let [hash (:attestation/hash attestation)
        sig (signing/sign-hash hash private-key-path nil)]
    (assoc attestation
           :attestation/signature sig
           :context (assoc (:context attestation {})
                           :key-path (str private-key-path ".pub")))))

(defn verify-attestation-signature
  [attestation public-key-path]
  (let [sig (:attestation/signature attestation)
        hash (:attestation/hash attestation)]
    (if (and sig hash public-key-path (.exists (io/file public-key-path)))
      (let [valid? (signing/verify-signature hash sig public-key-path)]
        {:valid? valid?
         :errors (when-not valid? ["Signature verification failed"])})
      {:valid? false
       :errors [(cond
                  (nil? sig) "No signature"
                  (nil? hash) "No hash"
                  :else (str "Public key not found: " public-key-path))]})))

(defn persist-attestation!
  [attestation dir]
  (let [hash (:attestation/hash attestation)
        f (io/file dir "community-attestations" (str "att-" (subs hash 0 12) ".edn"))]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str attestation))
    {:path (.getPath f) :hash hash}))

(defn resolve-attestation
  [dir hash-or-ref]
  (let [hash (if (attestation-ref? hash-or-ref)
               (parse-attestation-ref hash-or-ref)
               hash-or-ref)
        f (io/file dir "community-attestations" (str "att-" (subs hash 0 12) ".edn"))]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn- validate-attestation-shape
  "Predicate-specific field validation.
   Returns nil if valid, or an error string if fields are missing or malformed."
  [attestation]
  (let [pred (:attestation/predicate attestation)]
    (case pred
      :runner/execution-attested
      (let [a (:assertion attestation)]
        (cond
          (not (valid-sha256? (:code-hash a))) "code-hash must be a valid sha256 hex"
          (not (valid-sha256? (:env-hash a))) "env-hash must be a valid sha256 hex"
          (not (valid-sha256? (:bundle-root a))) "bundle-root must be a valid sha256 hex"
          (not (parse-evidence-ref (:execution-node-hash a))) "execution-node-hash must be a valid evidence-node:sha256:<hex>"
          (not (valid-sha256? (:result-projection-hash a))) "result-projection-hash must be a valid sha256 hex"
          (not (valid-sha256? (get-in attestation [:context :registry-snapshot/hash]))) "registry-snapshot/hash must be a valid sha256 hex"
          :else nil))
      :runner/result-reproduced
      (let [a (:assertion attestation)]
        (cond
          (not (attestation-ref? (:original-attestation-ref a))) "original-attestation-ref must be a valid attestation ref"
          (not (valid-sha256? (:original-result-projection-hash a))) "original-result-projection-hash must be a valid sha256 hex"
          (not (parse-evidence-ref (:reproduction-execution-node-hash a))) "reproduction-execution-node-hash must be a valid evidence-node:sha256:<hex>"
          (not (valid-sha256? (:reproduction-result-projection-hash a))) "reproduction-result-projection-hash must be a valid sha256 hex"
          (nil? (:comparison-policy a)) "comparison-policy is required"
          (not (contains? reproduction-statuses (:comparison-status a))) (str "comparison-status must be one of: " reproduction-statuses)
          :else nil))
      :runner/result-challenged
      (let [a (:assertion attestation)]
        (cond
          (not (attestation-ref? (:challenged-attestation-ref a))) "challenged-attestation-ref must be a valid attestation ref"
          (not (string? (:reason a))) "reason is required"
          :else nil))
      nil)))

(defn valid-attestation?
  "Check record integrity and predicate-specific field completeness.
   Returns true only if all required fields for the predicate are present
   and well-formed."
  [attestation]
  (and (map? attestation)
       (= schema-version (:schema-version attestation))
       (contains? supported-predicates (:attestation/predicate attestation))
       (string? (:attestation/hash attestation))
       (= (:attestation/id attestation) (:attestation/hash attestation))
       (= (compute-hash attestation) (:attestation/hash attestation))
       (nil? (validate-attestation-shape attestation))))
