(ns resolver-sim.commands.scenario-safety
  "Run-root ownership and public-bundle sensitivity checks.
   Produces protected findings with path tokens and value commitments
   to avoid leaking sensitive paths or matched content in reports."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.sensitivity.report :as report]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.sensitivity.sentinel :as sentinel])
  (:import [java.nio.file Files FileAlreadyExistsException StandardCopyOption]
           [java.security MessageDigest]))

(def ^:private lock-name paths/run-lock)

(def ^:private secret-rules
  "Named rules with IDs, versions, and patterns.
   Each rule is a map so that id, version, and pattern are explicit
   provenance metadata."
  [{:rule/id :secret-scanner/private-key
    :rule/version "v2"
    :label "private-key"
    :pattern #"-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----"}
   {:rule/id :secret-scanner/credential-assignment
    :rule/version "v2"
    :label "credential-assignment"
    :pattern #"(?i)(?:api[_-]?key|password|secret|private[_-]?key|access[_-]?token)\s*[:=]"}
   {:rule/id :secret-scanner/bearer-auth
    :rule/version "v2"
    :label "bearer-auth"
    :pattern #"(?i)authorization:\s*bearer\s+"}
   {:rule/id :secret-scanner/jwt-token
    :rule/version "v2"
    :label "jwt-token"
    :pattern #"eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"}
   {:rule/id :secret-scanner/github-token
    :rule/version "v2"
    :label "github-token"
    :pattern #"(?i)ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{82}"}
   {:rule/id :secret-scanner/npm-token
    :rule/version "v2"
    :label "npm-token"
    :pattern #"(?i)npm_[A-Za-z0-9]{36}"}])

(def ^:private structural-rules
  "Structural findings rules that scan artifact content for legacy or
    problematic patterns beyond secret detection. Each rule is a map with
    :rule/id, :rule/version, :label, and :pattern (regex).

    The :current-amount-write-back-verified? rule detects the v1
    overclaiming key whose name implies per-obligation write-back
    verification but actually carries only the aggregate operational
    write-back pass/fail status. Classified per sentinel.clj as
    :sensitivity/internal (fail-closed level) with
    :sensitivity/unknown (no evidence sensitivity established) and reason
    :legacy-v1-operational-write-back-pass."
  [{:rule/id :current-amount-write-back-verified?
    :rule/version "v1"
    :label "legacy-v1-operational-write-back-pass"
    :pattern #"current-amount-write-back-verified\?"}])

(def ^:private all-finding-rules
  "All sensitivity finding rules: secret-scanning rules concatenated with
    structural rules. Both are scanned by sensitivity-findings and
    scan-content-findings so the sentinel authority can derive findings
    from artifact content rather than trusting caller-supplied findings."
  (concat secret-rules structural-rules))

(def ^:private ruleset-hash
  (delay
    (hc/hash-with-intent {:hash/intent :evidence-record}
                         {:ruleset/id "sensitivity-scanner"
                          :ruleset/version "v3"
                          :rules (mapv (fn [r]
                                         {:rule/id (:rule/id r)
                                          :rule/version (:rule/version r)
                                          :pattern (str (:pattern r))})
                                       all-finding-rules)})))

(defn secret-scanner-ruleset-hash
  "Deterministic hash of the sensitivity-scanner ruleset (secret + structural
    rules) for provenance binding."
  []
  @ruleset-hash)

(defn- nonced-hash
  "Produce a salted hash commitment for a value.
   Uses SHA-256(value || random-nonce) to prevent brute-force
   reconstruction of low-entropy values from the hash alone."
  [value]
  (let [nonce (str (java.util.UUID/randomUUID))
        digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (str value) "UTF-8"))
                 (.update (.getBytes nonce "UTF-8")))]
    {:value-commitment (format "%064x" (java.math.BigInteger. 1 (.digest digest)))
     :commitment-nonce nonce
     :commitment-scheme "sha256-salted-v1"}))

(defn- compute-path-token
  "Hash a file path into an opaque token to avoid leaking
   project structure in a broadly readable report."
  [path]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes path "UTF-8")))]
    (format "path-%s" (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 12))))

(def ^:private finding-counter (atom 0))

(defn- next-finding-id
  []
  (str "finding-" (swap! finding-counter inc)))

(defn- text-file? [file]
  (boolean (re-find #"\.(json|edn|md|txt|csv)$" (.getName (io/file file)))))

(defn- sensitivity-findings [run-root]  (let [root (io/file (str run-root))
                                              forbidden #{paths/run-lock paths/run-state paths/completion}]
                                          (reset! finding-counter 0)
                                          (->> (file-seq root)
                                               (filter #(.isFile %))
                                               (remove #(contains? forbidden (.getName %)))
                                               (filter text-file?)
                                               (mapcat (fn [file]
                                                         (let [body (slurp file)
                                                               path (.getPath file)
                                                               path-token (compute-path-token path)]
                                                           (keep (fn [rule]
                                                                   (when (re-find (:pattern rule) body)
                                                                     (let [match-line (first (filter #(re-find (:pattern rule) %) (str/split-lines body)))]
                                                                       {:finding/id (next-finding-id)
                                                                        :finding/path-token path-token
                                                                        :rule/id (:rule/id rule)
                                                                        :rule/version (:rule/version rule)
                                                                        :match/value-commitment
                                                                        (when match-line
                                                                          (:value-commitment (nonced-hash match-line)))})))
                                                                 all-finding-rules))))
                                               vec)))

(defn scan-content-findings
  "Scan a single string body for sensitivity findings (secrets + structural
   legacy patterns) and return findings.

   Used by the out-of-process sentinel authority to derive sensitivity
   findings from the hash-verified artifact disclosure projection itself,
   rather than trusting caller-supplied findings. Findings carry opaque path
   tokens and salted value commitments so no matched content leaks."
  [body]
  (reset! finding-counter 0)
  (->> all-finding-rules
       (keep (fn [rule]
               (when (re-find (:pattern rule) body)
                 (let [match-line (first (filter #(re-find (:pattern rule) %) (str/split-lines body)))]
                   {:finding/id (next-finding-id)
                    :finding/path-token "content"
                    :rule/id (:rule/id rule)
                    :rule/version (:rule/version rule)
                    :match/value-commitment
                    (when match-line
                      (:value-commitment (nonced-hash match-line)))}))))
       vec))

(defn scan-public-bundle! [run-root]
  (let [findings (sensitivity-findings run-root)]
    (when (seq findings)
      (throw (ex-info "Public bundle sensitivity scan failed" {:findings findings})))
    {:profile :public :decision :allowed :findings []}))

(defn acquire-lock!
  "Compatibility delegate; canonical callers use lifecycle/acquire-run-lock!."
  [run-root]
  (lifecycle/acquire-run-lock! run-root nil :scenario))

(defn release-lock! [lock]
  "Compatibility delegate; canonical callers use lifecycle/release-run-lock!."
  (lifecycle/release-run-lock! lock))

(defn scan-internal-bundle!
  "Scan an internal bundle without blocking approved retention. Findings are
   sanitized metadata (path tokens, value commitments, rule IDs) and the
   resulting report explicitly marks the bundle as internal-only whenever
   restricted-looking content is present."
  [run-root]
  (let [findings (sensitivity-findings run-root)]
    {:profile :internal
     :decision (if (seq findings) :internal-retention :allowed)
     :findings findings}))

(defn build-structural-derivation
  "Build a structural derivation record for a single scenario result.
   Connects safety findings to the scenario via path and pattern matches,
   producing structured reasons for the classification level. Evidence-backed
   classification uses actual findings to derive the level.
   
   Arguments:
     result   — scenario result map
     findings — vector of finding maps from sensitivity-findings
   
   Returns nil or a map with :structural/classification-level and
   :structural/reasons, plus :evidence/findings for evidence-backed classification."
  [result findings]
  (let [scenario-path (:scenario-path result)]
    (let [scenario-path-token (when scenario-path
                                (let [digest (doto (MessageDigest/getInstance "SHA-256")
                                               (.update (.getBytes scenario-path "UTF-8")))]
                                  (format "path-%s"
                                          (subs (format "%064x" (java.math.BigInteger. 1 (.digest digest))) 0 12))))]
      (when-let [relevant-findings (seq (filter #(= (:finding/path-token %) scenario-path-token) findings))]
        (let [evidence-classification (sentinel/classify-from-findings relevant-findings)]
          {:structural/classification-level (name (:level evidence-classification))
           :structural/reasons
           (mapv (fn [f]
                   (let [codes (sentinel/finding-reason-codes (:rule/id f))]
                     {:reason/code (first codes)
                      :rule/id (:rule/id f)
                      :rule/version (:rule/version f)
                      :finding/ref (:finding/id f)}))
                 relevant-findings)
           :structural/ruleset-hash (secret-scanner-ruleset-hash)
           :evidence/findings relevant-findings})))))

(defn write-sensitivity-report!
  "Persist the pre-finalization export decision so it can be registered with the bundle.

   When classification data is provided (run-sensitivity, scenarios, context),
   produces sensitivity-report.v2 combining safety-scan results with classification
   and provenance from the canonical report builder.

   When only result is provided (benchmark-caller fallback), writes a v1 legacy
   report without classification or provenance.

   Arguments:
     manifest-dir     — output directory (string or File)
     result           — safety scan result from scan-public-bundle! or scan-internal-bundle!
     run-sensitivity  — optional, from merge-sensitivity
     scenarios        — optional, seq of scenario result maps
     context          — optional, context map with :run-id, :profile, :sentinel-version, :scenario-ids"
  [manifest-dir result & [run-sensitivity scenarios context]]
  (let [report (if run-sensitivity
                 (report/build-sensitivity-report result run-sensitivity scenarios context)
                 (let [legacy {"schema_version" "sensitivity-report.v1"
                               "profile" (name (:profile result))
                               "decision" (name (or (:decision result) :allowed))
                               "findings" (:findings result [])}]
                   (json/write-str legacy)))
        target (io/file (str manifest-dir) "sensitivity-report.json")
        temp (io/file (str (.getPath target) ".tmp"))]
    (if (string? report)
      (let [;; Legacy mode: report is already a JSON string
            _ (.mkdirs (.getParentFile target))]
        (spit temp report)
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
        report)
      ;; V2 mode: report is a map, use report/write
      (report/write-sensitivity-report! manifest-dir report))))
