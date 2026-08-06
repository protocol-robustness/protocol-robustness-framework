(ns resolver-sim.publish-client
  "Out-of-process artifact publisher client.

   A caller that must publish a staged artifact set obtains a signed publish
   certificate from an external `prf publish check` process, verifies the
   certificate's signature and commitments, and only then atomically promotes
   the staged files to the canonical target directory (all-or-nothing).

   The process boundary is a defense-in-depth measure: the authority
   independently re-reads and re-hashes the whole set from disk, so a modified
   or missing artifact cannot receive a certificate. The client still verifies
   the returned certificate so a tampered or forged response cannot be
   accepted, and performs the atomic directory promotion.

   Guarantees:
     - ProcessBuilder with an explicit argv vector (never a shell string).
     - stdout (machine output) and stderr (diagnostics) are drained
       concurrently and size-capped; timeouts terminate and reap the child.
     - Exactly one EDN response is expected; trailing garbage is rejected.
     - Every commitment is cross-checked and the signature is verified against
       a trusted key with the :artifact-publisher role.
     - Promotion writes all files into a fresh sibling directory then atomically
       renames it into place; the target is never observed half-populated.
     - Any failure is fail-closed: the gate throws and nothing is promoted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.publish.contract :as contract]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.signed-external-decision :as sed])
  (:import            [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

;; ── Configuration ───────────────────────────────────────────────────────────

(def default-timeout-ms 60000)
(def max-io-chars (* 16 1024 1024))
(def expected-role :artifact-publisher)

(defn- self-jar-command
  "Resolve the publisher authority command from the current distribution jar.
   Only succeeds when the classpath is exactly one unambiguous prf jar;
   otherwise nil (caller must fail closed)."
  []
  (let [cp (System/getProperty "java.class.path")
        parts (str/split cp (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
        jars (filter #(re-find #"prf[^/]*\.jar$" %) parts)]
    (when (= 1 (count jars))
      [(str (System/getProperty "java.home") "/bin/java") "-jar" (first jars)
       "publish" "check"])))

(defn- env [k] (System/getenv k))

(defn default-command
  "Build the default argv vector for the publisher process.

   Precedence: PRF_PUBLISH_JAR (+ optional PRF_JAVA) → self-jar discovery → nil.
   The result is always an argv vector consumed by ProcessBuilder — never a
   shell string."
  []
  (if-let [jar (env "PRF_PUBLISH_JAR")]
    [(or (env "PRF_JAVA") (str (System/getProperty "java.home") "/bin/java"))
     "-jar" jar "publish" "check"]
    (self-jar-command)))

;; ── Process runner (injectable seam) ───────────────────────────────────────

(def ^:dynamic *runner*
  "Process runner used to invoke the authority. Rebind in tests to avoid
   spawning a real JVM. Signature: (fn [argv input timeout-ms]
   => {:exit int :stdout str :stderr str})."
  nil)

(defn- drain-capped
  "Drain a stream into a string capped at max-chars, continuing past the cap."
  [stream max-chars]
  (future
    (let [sb (StringBuilder.)]
      (with-open [r (io/reader stream)]
        (loop [total 0]
          (let [buf (char-array 8192)
                n (.read r buf 0 8192)]
            (if (neg? n)
              (str sb)
              (do (when (< total max-chars)
                    (.append sb buf 0 (min n (- max-chars total))))
                  (recur (+ total n))))))))))

(defn process-runner
  "Run the publisher via ProcessBuilder. Explicit argv, no shell."
  [argv ^String input timeout-ms]
  (let [pb (ProcessBuilder. argv)
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        stdin (.getOutputStream proc)
        out-fut (drain-capped (.getInputStream proc) max-io-chars)
        err-fut (drain-capped (.getErrorStream proc) max-io-chars)]
    (try
      (let [bytes (.getBytes input StandardCharsets/UTF_8)]
        (doto stdin
          (.write bytes)
          (.flush)
          (.close)))
      (catch Exception _))
    (let [done? (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (when-not done?
        (.destroy proc)
        (when-not (.waitFor proc 1000 TimeUnit/MILLISECONDS)
          (.destroyForcibly proc))
        (throw (ex-info "publisher authority timed out"
                        {:reason :publish-timeout :timeout-ms timeout-ms})))
      (let [exit (.exitValue proc)
            out (deref out-fut)
            err (deref err-fut)]
        {:exit exit :stdout out :stderr err}))))

(defn- runner-for [config] (or (:runner config) *runner* process-runner))

;; ── Response parsing ────────────────────────────────────────────────────────

(defn- read-one-form
  "Read exactly one EDN form, rejecting trailing non-whitespace."
  [^String s]
  (let [pr (PushbackReader. (io/reader (java.io.StringReader. s)))
        eof (Object.)
        form (edn/read {:eof eof} pr)]
    (when (= eof form)
      (throw (ex-info "empty publish response" {:reason :empty-response})))
    (loop []
      (let [ch (.read pr)]
        (cond
          (neg? ch) nil
          (Character/isWhitespace (char ch)) (recur)
          :else (throw (ex-info "trailing content after publish response"
                                {:reason :trailing-response})))))
    form))

(defn- parse-response
  [^String stdout]
  (let [form (read-one-form stdout)]
    (when-not (map? form)
      (throw (ex-info "publish response is not a map" {:reason :malformed-response})))
    (let [kind (:response/kind form)]
      (cond
        (= contract/response-kind kind) form
        (= :artifact-publish-error kind)
        (throw (ex-info (or (:error/detail form) "publisher rejected request")
                        {:reason (:error/reason form)
                         :publish/blocked true}))
        :else
        (throw (ex-info "unexpected publish response kind"
                        {:reason :unexpected-response-kind :kind kind}))))))

;; ── Certificate verification ────────────────────────────────────────────────

(defn verify-certificate
  "Verify a signed publish certificate against the request and a trust policy.
   Returns {:valid? true :certificate <envelope> :reason kw} or
   {:valid? false :reason kw :detail ...}.

   Cross-checks: kind/version, request id/hash, run-id, manifest commit,
   root, the :artifact-publisher role/key-status, and the :approve decision."
  [response request trust-policy]
  (let [cert (:decision response)
        expected-commit (manifest/manifest-commit (:publish/run-id request)
                                                  (:publish/manifest request))]
    (cond
      (not (map? cert))
      {:valid? false :reason :missing-certificate}

      (not= contract/decision-kind (:artifact/kind cert))
      {:valid? false :reason :unexpected-certificate-kind :detail (:artifact/kind cert)}

      (not= contract/protocol-version (:artifact/version cert))
      {:valid? false :reason :unexpected-certificate-version :detail (:artifact/version cert)}

      (not= (:request/id request) (:request/id response))
      {:valid? false :reason :request-id-mismatch}

      (not= (:request/hash request) (:request/hash response))
      {:valid? false :reason :request-hash-mismatch}

      (not= (:publish/run-id request) (:publish/run-id cert))
      {:valid? false :reason :run-id-mismatch}

      (not= (:publish/root request) (:publish/root cert))
      {:valid? false :reason :root-mismatch}

      (not= expected-commit (:publish/manifest-commit cert))
      {:valid? false :reason :manifest-commit-mismatch}

      (not= (:policy/hash request) (:publish/policy-hash cert))
      {:valid? false :reason :policy-hash-mismatch}

      (not= :approve (:publish/decision cert))
      {:valid? false :reason :certificate-not-approving
       :publish/decision (:publish/decision cert)}

      :else
      (let [v (sed/verify-envelope cert contract/decision-domain
                                   trust-policy expected-role)]
        (if (:valid? v)
          {:valid? true :certificate cert}
          {:valid? false :reason (:reason v) :detail (:detail v)})))))

;; ── High-level client ───────────────────────────────────────────────────────

(defn request-certificate
  "Request and verify a signed publish certificate.

   opts: {:command [argv...] :trust-policy {...} :runner f
          :request <built request>}
   config defaults (:command default-command, :timeout-ms default-timeout-ms).

   Returns the signed certificate. Throws (fail-closed) on any process,
   protocol, commitment or signature failure."
  [{:keys [request trust-policy timeout-ms] :as opts}]
  (let [command (or (:command opts) (default-command))
        _ (when-not command
            (throw (ex-info "no publisher authority command configured"
                            {:reason :publish-command-unavailable})))
        timeout-ms (or timeout-ms default-timeout-ms)
        input (pr-str request)
        {:keys [exit stdout stderr]} ((runner-for opts) command input timeout-ms)]
    (when-not (zero? exit)
      (throw (ex-info "publisher authority exited non-zero"
                      {:reason :publish-process-exit
                       :exit exit
                       :stderr (str (when (seq stderr) stderr))})))
    (let [response (parse-response stdout)
          result (verify-certificate response request trust-policy)]
      (if (:valid? result)
        (:certificate result)
        (throw (ex-info (str "publish certificate verification failed: " (:reason result))
                        {:publish/blocked true
                         :reason (:reason result)
                         :detail (:detail result)}))))))

;; ── Atomic promotion (all-or-nothing) ─────────────────────────────────────

(defn- files-under
  "All regular files under a directory, recursively, as java.io.File."
  [dir]
  (let [root (io/file dir)]
    (if (.isFile root)
      [root]
      (mapcat (fn [^java.io.File f]
                (if (.isDirectory f) (files-under f) [f]))
              (.listFiles root)))))

(defn- stage-entries
  "Read a stage directory and produce manifest entries ({:path :sha256}) for
   every file under it, recursively, with relative paths."
  [stage]
  (let [root (.toPath (io/file stage))]
    (->> (files-under stage)
         (map (fn [^java.io.File f]
                {:path (str (.relativize root (.toPath f)))
                 :sha256 (manifest/file-sha256 f)}))
         (vec))))

(defn- copy-tree
  "Copy every relative :path entry from stage into temp-dir, creating parent
   directories. Returns temp-dir."
  [stage temp-dir entries]
  (doseq [{:keys [path]} entries]
    (let [src (.toPath (io/file stage path))
          dst (.resolve temp-dir path)]
      (Files/createDirectories (.getParent dst) (into-array java.nio.file.attribute.FileAttribute []))
      (Files/copy src dst (into-array java.nio.file.CopyOption []))))
  temp-dir)

(defn- delete-tree!
  "Recursively delete a file or directory tree."
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn publish!
  "Authorize and atomically promote a staged artifact set to a target.

   opts:
     :stage  — directory of staged artifact files (the all-or-nothing source)
     :target — canonical output directory (created via atomic rename; must not
               already exist)
     :required — subset of relative paths that must be present (default: all)
     :run-id   — run identity
     :request  — prebuilt request (optional; built from stage when absent)
     :trust-policy, :command, :runner, :timeout-ms — as in request-certificate

   Flow: enumerate stage files → request signed certificate → if valid, copy
   every file into a fresh sibling temp directory (same filesystem) → atomically
   rename temp → target. The certificate is written as publication.json inside
   the promoted directory.

   Returns {:promoted-path <target> :run-id <...> :certificate <signed>}.
   Throws on any failure; target is never left partially populated."
  [{:keys [stage target required run-id request] :as opts}]
  (let [stage-dir (io/file stage)
        _ (when-not (.isDirectory stage-dir)
            (throw (ex-info "publish stage does not exist"
                            {:reason :missing-stage :stage stage})))
        target-dir (io/file target)
        _ (when (.exists target-dir)
            (throw (ex-info "publish target already exists (finalized output is immutable)"
                            {:reason :target-exists :target target})))
        entries (or (:publish/manifest request)
                    (stage-entries stage-dir))
        required (or required (mapv :path entries))
        req (or request
                (contract/build-request {:id (str (java.util.UUID/randomUUID))
                                         :root (.getAbsolutePath stage-dir)
                                         :run-id (or run-id (str "run-" (java.util.UUID/randomUUID)))
                                         :manifest entries
                                         :required required}))
        certificate (request-certificate (assoc opts :request req))]
    ;; All-or-nothing promotion: copy into a fresh sibling temp dir, then
    ;; atomically rename the whole directory into place.
    (let [parent (.toPath (.getAbsoluteFile (.getParentFile target-dir)))
          _ (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute []))
          temp-dir (Files/createTempDirectory parent ".publish-tmp-" (into-array java.nio.file.attribute.FileAttribute []))
          _ (copy-tree stage-dir temp-dir entries)
          pub-file (.resolve temp-dir "publication.json")]
      (try
        (spit (.toFile pub-file) (pr-str certificate))
        (Files/move temp-dir (.toPath (.getAbsoluteFile target-dir))
                    (into-array java.nio.file.CopyOption []))
        {:promoted-path (.getAbsolutePath target-dir)
         :run-id (:publish/run-id req)
         :certificate certificate}
        (catch Throwable t
          (try (delete-tree! (.toFile temp-dir))
               (catch Throwable _))
          (throw (ex-info "publish promotion failed (target untouched)"
                          {:reason :publish-promotion-failed
                           :cause (.getMessage t)})))))))
