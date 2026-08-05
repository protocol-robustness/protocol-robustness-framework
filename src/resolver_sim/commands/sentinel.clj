(ns resolver-sim.commands.sentinel
  "Out-of-process sensitivity sentinel authority.

   `prf sentinel check` reads exactly one EDN request from stdin, makes a
   disclosure decision from the hash-verified artifact content it scans
   itself, signs the complete decision envelope, and writes exactly one EDN
   response to stdout. Diagnostics go to stderr only.

   The decision is authoritative only because the authority:
     1. recomputes and verifies :artifact/declared-hash from :artifact/content,
     2. cross-checks :policy/hash against the committed sentinel policy,
     3. derives sensitivity findings from the content (not caller findings),
     4. signs the complete decision envelope under a dedicated domain tag."
  (:require [clojure.edn :as edn]
            [buddy.core.keys :as keys]
            [resolver-sim.sensitivity.contract :as contract]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.commands.scenario-safety :as safety])
  (:import [java.io PushbackReader Reader StringReader]))

(def max-request-bytes (* 4 1024 1024))

(def error-kind :sensitivity-sentinel-error)

(defn- read-limited
  "Read at most max-chars characters from a reader into a string, throwing if
   the input exceeds the cap (fail-closed against oversized requests)."
  [^Reader r max-chars]
  (let [buf (char-array (min 8192 max-chars))
        sb (StringBuilder.)]
    (loop [total 0]
      (when (>= total max-chars)
        (throw (ex-info "request input exceeds size cap"
                        {:reason :stdin-too-large :max-bytes max-chars})))
      (let [n (.read r buf 0 (min (alength buf) (- max-chars total)))]
        (if (neg? n)
          (str sb)
          (do (.append sb buf 0 n)
              (recur (+ total n))))))))

(defn- read-one-request
  "Read exactly one EDN request form, rejecting trailing non-whitespace."
  [^Reader r]
  (let [pr (PushbackReader. r)
        eof (Object.)
        form (edn/read {:eof eof} pr)]
    (when (= eof form)
      (throw (ex-info "empty request" {:reason :empty-request})))
    (loop []
      (let [ch (.read pr)]
        (cond
          (neg? ch) nil
          (Character/isWhitespace (char ch)) (recur)
          :else (throw (ex-info "trailing content after request form"
                                {:reason :trailing-request})))))
    form))

(defn- error-response
  [request-id reason detail]
  {:response/kind error-kind
   :response/version contract/protocol-version
   :request/id request-id
   :error/reason reason
   :error/detail detail})

(defn- authority-key-id
  "Derive a stable key-id for signing from the loaded private key's encoded
   bytes (a sha256 digest prefix). This is an identifier, not a secret."
  [^java.security.PrivateKey private-key]
  (let [digest (doto (java.security.MessageDigest/getInstance "SHA-256")
                 (.update (.getEncoded private-key)))
        hex (format "%064x" (java.math.BigInteger. 1 (.digest digest)))]
    (keyword (subs hex 0 12))))

(defn decide
  "Make and sign a sentinel decision for a validated request.

   This is the pure decision core; `run` handles I/O around it. Caller-supplied
   values are commitments the authority cross-checks, never trusted inputs.

   Returns the response map. Throws ex-info (with :reason) on any failure so
   the caller can fail closed."
  [{:keys [key-id private-key assurance issued-at] :as _auth}
   request]
  (let [content (:artifact/content request)
        sink (:sink request)]
    ;; 1. hash-verify the artifact projection
    (when-not (= (:artifact/declared-hash request)
                 (contract/projection-hash content))
      (throw (ex-info "artifact projection hash mismatch"
                      {:reason :artifact-hash-mismatch})))
    ;; 2. cross-check the committed policy
    (when-not (= (sentinel/policy-hash) (:policy/hash request))
      (throw (ex-info "policy hash mismatch; authority policy drifted"
                      {:reason :policy-hash-mismatch
                       :expected (sentinel/policy-hash)
                       :got (:policy/hash request)})))
    ;; 3. derive findings from the content, then classify the report
    (let [body (pr-str content)
          findings (safety/scan-content-findings body)
          artifact (cond-> content
                     (seq findings) (assoc :sensitivity/findings findings))
          report (sentinel/sentinel-report artifact sink)
          key-id (or key-id (authority-key-id private-key))
          assurance (or assurance :process-isolated)
          decision (contract/build-decision
                    {:request request
                     :report report
                     :sink sink
                     :artifact-hash (:artifact/declared-hash request)
                     :authority-key-id key-id
                     :authority-assurance assurance
                     :issued-at (or issued-at (str (java.time.Instant/now)))})
          signed (sed/sign-envelope decision contract/decision-domain private-key key-id)]
      (contract/build-response request signed))))

(defn run-from-reader
  "Execute the sentinel authority reading a request from `r`. Prints the
   response to stdout. Returns an exit code (0 on success, 1 on failure)."
  [r private-key {:keys [assurance key-id] :as _opts}]
  (let [request-id (atom nil)]
    (try
      (let [raw (read-limited r max-request-bytes)
            request (read-one-request (StringReader. raw))
            _ (reset! request-id (:request/id request))
            _ (when-not (contract/valid-request? request)
                (throw (ex-info "invalid request"
                                {:reason :invalid-request
                                 :errors (contract/request-errors request)})))
            response (decide {:private-key private-key :assurance assurance :key-id key-id}
                             request)]
        (println (pr-str response))
        0)
      (catch Exception e
        (let [reason (or (:reason (ex-data e)) :sentinel-error)
              detail (or (:detail (ex-data e)) (.getMessage e))]
          (binding [*out* (java.io.PrintWriter. *err* true)]
            (println (str "sentinel authority error: " reason " — " detail)))
          (println (pr-str (error-response @request-id reason detail)))
          1)))))

(defn- load-private-key
  [path]
  (when-not path
    (throw (ex-info "no signing key configured" {:reason :missing-signing-key})))
  (keys/private-key path))

(defn- get-env
  "Read an environment variable. A thin, redefinable seam for tests."
  [k]
  (System/getenv k))

(defn run
  "CLI entry for `prf sentinel check`. Reads a request from stdin, signs a
   decision, writes the response to stdout, returns an exit code.

   Signing key path, key-id and authority-assurance are read from the options
   map or, for subprocess invocation, from the environment the caller sets
   (PRF_SENTINEL_KEY, PRF_SENTINEL_KEY_ID, PRF_SENTINEL_ASSURANCE). Keeping the
   key path out of argv avoids exposing it in the process table."
  [{:keys [key assurance key-id] :as _opts}]
  (let [key (or key (get-env "PRF_SENTINEL_KEY"))
        assurance (or assurance (some-> (get-env "PRF_SENTINEL_ASSURANCE") keyword))
        key-id (or key-id (get-env "PRF_SENTINEL_KEY_ID"))
        private-key (load-private-key key)]
    (run-from-reader *in* private-key {:assurance assurance :key-id key-id})))
