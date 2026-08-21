(ns resolver-sim.sensitivity.sentinel-client
  "Out-of-process sensitivity sentinel client.

   A caller that must disclose an artifact to a sink that the committed policy
   classifies as :remote obtains a signed decision from an external `prf`
   process, then verifies the decision envelope's signature and commitments
   before trusting it.

   The process boundary is a defense-in-depth measure: even a compromised
   caller cannot fabricate a valid decision because the signing key lives only
   in the authority process. The client still verifies the returned decision so
   a tampered or forged response cannot be accepted.

   Guarantees:
     - ProcessBuilder with an explicit argv vector (never a shell string).
     - stdout (machine output) and stderr (diagnostics) are drained
       concurrently and size-capped to avoid deadlock and memory blowup.
     - Timeouts terminate and reap the child process.
     - Exactly one EDN response is expected; trailing garbage is rejected.
     - Every authorization-relevant commitment is cross-checked before a
       decision is accepted, and the signature is verified against a trusted
       key with the :sensitivity-sentinel role.
     - Any failure is fail-closed: the gate throws and no disclosure proceeds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.config.hardening :as hardening]
            [resolver-sim.sensitivity.contract :as contract]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.signed-external-decision :as sed])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

;; ── Configuration ───────────────────────────────────────────────────────────

(def expected-role :sensitivity-sentinel)

(defn- self-jar-command
  "Resolve the sentinel authority command from the current distribution jar.
   Only succeeds when the classpath is exactly one unambiguous prf jar;
   otherwise nil (caller must fail closed)."
  []
  (let [cp (System/getProperty "java.class.path")
        parts (str/split cp (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
        jars (filter #(re-find #"prf[^/]*\.jar$" %) parts)]
    (when (= 1 (count jars))
      [(str (System/getProperty "java.home") "/bin/java") "-jar" (first jars)
       "sentinel" "check"])))

(defn- env
  "Read an environment variable. A thin, redefinable seam for tests."
  [k]
  (System/getenv k))

(defn default-command
  "Build the default argv vector for the authority process.

   Precedence:
     1. PRF_SENTINEL_JAR (+ optional PRF_JAVA) if set;
     2. self-jar discovery when running from one unambiguous distribution jar;
     3. nil otherwise (caller must fail closed with :sentinel-command-unavailable).

   The result is always an argv vector consumed by ProcessBuilder — never a
   shell string — so configuration cannot introduce shell metacharacters."
  []
  (if-let [jar (env "PRF_SENTINEL_JAR")]
    [(or (env "PRF_JAVA") (str (System/getProperty "java.home") "/bin/java"))
     "-jar" jar "sentinel" "check"]
    (self-jar-command)))

;; ── Process runner (injectable seam) ───────────────────────────────────────

(def ^:dynamic *runner*
  "Process runner used to invoke the authority. Rebind in tests to avoid
   spawning a real JVM. Signature: (fn [argv input timeout-ms]
   => {:exit int :stdout str :stderr str}). Must return :exit 0 on success."
  nil)

(defn- drain-capped
  "Drain a stream into a string capped at max-chars, continuing to consume
   beyond the cap to avoid blocking a full pipe."
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
  "Run the authority via ProcessBuilder. Explicit argv, no shell."
  [argv ^String input timeout-ms]
  (let [max-chars (hardening/value :sentinel-max-io-chars {:fallback (* 8 1024 1024)})
        pb (ProcessBuilder. argv)
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        stdin (.getOutputStream proc)
        out-fut (drain-capped (.getInputStream proc) max-chars)
        err-fut (drain-capped (.getErrorStream proc) max-chars)]
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
        (throw (ex-info "sentinel authority timed out"
                        {:reason :sentinel-timeout :timeout-ms timeout-ms})))
      (let [exit (.exitValue proc)
            out (deref out-fut)
            err (deref err-fut)]
        {:exit exit :stdout out :stderr err}))))

(defn- runner-for
  [config]
  (or (:runner config) *runner* process-runner))

;; ── Response parsing ────────────────────────────────────────────────────────

(defn- read-one-form
  "Read exactly one EDN form, rejecting trailing non-whitespace."
  [^String s]
  (let [pr (PushbackReader. (io/reader (java.io.StringReader. s)))
        eof (Object.)
        form (edn/read {:eof eof} pr)]
    (when (= eof form)
      (throw (ex-info "empty authority response" {:reason :empty-response})))
    (loop []
      (let [ch (.read pr)]
        (cond
          (neg? ch) nil
          (Character/isWhitespace (char ch)) (recur)
          :else (throw (ex-info "trailing content after authority response"
                                {:reason :trailing-response})))))
    form))

(defn- parse-response
  [^String stdout]
  (let [form (read-one-form stdout)]
    (when-not (map? form)
      (throw (ex-info "authority response is not a map" {:reason :malformed-response})))
    (let [kind (:response/kind form)]
      (cond
        (= contract/response-kind kind) form
        (= :sensitivity-sentinel-error kind)
        (throw (ex-info (or (:error/detail form) "sentinel authority rejected request")
                        {:reason (:error/reason form)
                         :sentinel/blocked true}))
        :else
        (throw (ex-info "unexpected authority response kind"
                        {:reason :unexpected-response-kind
                         :kind kind}))))))

;; ── Decision verification ───────────────────────────────────────────────────

(defn verify-decision
  "Verify a signed decision envelope against a trust policy and its
   commitments. Returns {:valid? true :allowed? bool :decision <envelope>} or
   {:valid? false :reason <kw>}.

   Cross-checks:
     - kind + version
     - signature validity against a trusted key with the sentinel role
     - policy hash matches the committed policy
     - artifact hash matches the requested projection
     - sink matches the requested sink
     - the decision actually permits export (a blocked or override-pending
       decision is never treated as approval)"
  [response request trust-policy]
  (let [decision (:decision response)]
    (cond
      (not (map? decision))
      {:valid? false :reason :missing-decision}

      (not= contract/decision-kind (:artifact/kind decision))
      {:valid? false :reason :unexpected-decision-kind :detail (:artifact/kind decision)}

      (not= contract/protocol-version (:artifact/version decision))
      {:valid? false :reason :unexpected-decision-version :detail (:artifact/version decision)}

      (not= (:request/id request) (:request/id response))
      {:valid? false :reason :request-id-mismatch}

      (not= (:request/hash request) (:request/hash response))
      {:valid? false :reason :request-hash-mismatch}

      (not= (:artifact/declared-hash request) (:sentinel/artifact-hash decision))
      {:valid? false :reason :artifact-hash-mismatch}

      (not= (:sink request) (:sentinel/sink decision))
      {:valid? false :reason :sink-mismatch
       :detail (str "requested " (:sink request) " decided " (:sentinel/sink decision))}

      (not= (:policy/hash request) (:sentinel/policy-hash decision))
      {:valid? false :reason :policy-hash-mismatch}

      :else
      (let [v (sed/verify-envelope decision contract/decision-domain
                                   trust-policy expected-role)]
        (cond
          (not (:valid? v))
          {:valid? false :reason (:reason v) :detail (:detail v)}

          (:sentinel/override-required? decision)
          {:valid? false :reason :decision-not-permitting
           :decision-decision (:sentinel/decision decision)
           :override-required? true}

          (not= :allow (:sentinel/decision decision))
          {:valid? false :reason :decision-not-permitting
           :decision-decision (:sentinel/decision decision)
           :override-required? (:sentinel/override-required? decision)}

          :else
          {:valid? true :allowed? true :decision decision})))))

;; ── High-level client ───────────────────────────────────────────────────────

(defn- request-for
  "Build the sentinel-check request for an artifact and sink."
  [artifact sink]
  (contract/build-request
   {:artifact-id (str (java.util.UUID/randomUUID))
    :content artifact
    :sink sink
    :declared-level (:sensitivity/level artifact)
    :risk-meta (:sensitivity/risk-meta artifact)
    :policy-hash-str (sentinel/policy-hash)}))

(defn request-decision
  "Request and verify a signed sentinel decision from the authority process.

   config: {:command [argv...] :trust-policy {...} :timeout-ms N :runner f}
   `:command` defaults to (default-command). `:trust-policy` carries the public
   keys with :key/role :sensitivity-sentinel.

   Returns the signed decision envelope. Throws (fail-closed) on any process,
   protocol, commitment or signature failure."
  [artifact sink config]
  (let [request (request-for artifact sink)
        command (or (:command config) (default-command))
        _ (when-not command
            (throw (ex-info "no sentinel authority command configured"
                            {:reason :sentinel-command-unavailable})))
        timeout-ms (or (:timeout-ms config)
                       (hardening/value :sentinel-timeout-ms {:fallback 30000}))
        input (pr-str request)
        {:keys [exit stdout stderr]} ((runner-for config) command input timeout-ms)]
    (when-not (zero? exit)
      (throw (ex-info "sentinel authority exited non-zero"
                      {:reason :sentinel-process-exit
                       :exit exit
                       :stderr (str (when (seq stderr) stderr))})))
    (let [response (parse-response stdout)
          result (verify-decision response request (:trust-policy config))]
      (if (:valid? result)
        (:decision result)
        (throw (ex-info (str "sentinel decision verification failed: " (:reason result))
                        {:sentinel/blocked true
                         :reason (:reason result)
                         :detail (:detail result)}))))))

(defn out-of-process-gate!
  "Authorize disclosure of an artifact to a sink according to the committed
   policy.

   For a sink classified :remote, OR an artifact that itself requires remote
   authority (for example force-authorisation add-held evidence — local
   in-process authorization is forbidden for it), obtains and verifies a signed
   decision from the out-of-process authority and returns it. Otherwise uses
   the local sentinel assertion. Throws (fail-closed) when disclosure is not
   independently authorized."
  [artifact sink config]
  (if (or (sentinel/remote-authority-required? sink)
          (sentinel/remote-authority-required-artifact? artifact))
    (let [decision (request-decision artifact sink config)]
      (when (= :block (:sentinel/decision decision))
        (throw (ex-info "Disclosure blocked by out-of-process sensitivity sentinel"
                        {:sentinel/blocked true :decision decision})))
      decision)
    (sentinel/assert-disclosure-allowed! artifact {:sink sink})))
