(ns resolver-sim.commands.publish
  "Out-of-process artifact publisher authority.

   `prf publish check` reads exactly one EDN request from stdin, independently
   re-verifies the declared artifact set from disk (all-or-nothing), signs the
   publish certificate, and writes exactly one EDN response to stdout.
   Diagnostics go to stderr only.

   The certificate is authoritative only because the authority:
     1. verifies :request/hash and the committed policy hash,
     2. recomputes the manifest commitment and cross-checks :publish/declared-commit,
     3. reads every declared artifact from disk and re-verifies its sha256 so a
        missing or modified file fails the whole set,
     4. signs the complete certificate under a dedicated domain tag."
  (:require [clojure.edn :as edn]
            [buddy.core.keys :as keys]
            [resolver-sim.publish.contract :as contract]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.signed-external-decision :as sed])
  (:import [java.io PushbackReader Reader StringReader]))

(def max-request-bytes (* 16 1024 1024))

(def error-kind :artifact-publish-error)

(defn- read-limited
  "Read at most max-chars characters from a reader into a string, throwing if
   the input exceeds the cap (fail-closed against oversized requests)."
  [^Reader r max-chars]
  (let [buf (char-array (min 8192 max-chars))
        sb (StringBuilder.)]
    (loop [total 0]
      (when (>= total max-chars)
        (throw (ex-info "request input exceeds size cap"
                        {:reason :publish-stdin-too-large :max-bytes max-chars})))
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
   bytes (a sha256 digest prefix). Identifier, not a secret."
  [^java.security.PrivateKey private-key]
  (let [digest (doto (java.security.MessageDigest/getInstance "SHA-256")
                 (.update (.getEncoded private-key)))
        hex (format "%064x" (java.math.BigInteger. 1 (.digest digest)))]
    (keyword (subs hex 0 12))))

(defn decide
  "Independently verify an artifact set and sign a publish certificate for a
   validated request. Pure decision core; `run` handles I/O around it.

   Caller-supplied values are commitments the authority cross-checks, never
   trusted inputs. Returns the response map. Throws ex-info (with :reason) on
   any failure so the caller fails closed."
  [{:keys [key-id private-key assurance issued-at] :as _auth}
   request]
  (when-not (= (:request/hash request)
               (sed/request-hash contract/request-domain request))
    (throw (ex-info "request hash mismatch" {:reason :request-hash-mismatch})))
  (when-not (= (contract/policy-hash) (:policy/hash request))
    (throw (ex-info "publish policy hash mismatch; authority policy drifted"
                    {:reason :policy-hash-mismatch
                     :expected (contract/policy-hash)
                     :got (:policy/hash request)})))
  (let [declared (manifest/manifest-commit (:publish/run-id request)
                                           (:publish/manifest request))]
    (when-not (= (:publish/declared-commit request) declared)
      (throw (ex-info "manifest commitment mismatch"
                      {:reason :manifest-commit-mismatch
                       :expected declared
                       :got (:publish/declared-commit request)}))))
  ;; All-or-nothing file verification from disk.
  (let [verification (manifest/verify-set
                      {:root (:publish/root request)
                       :entries (:publish/manifest request)
                       :required (:publish/required request)})]
    (when-not (:ok verification)
      (throw (ex-info "artifact set failed all-or-nothing verification"
                      {:reason :publish-set-rejected
                       :verification verification}))))
  (let [key-id (or key-id (authority-key-id private-key))
        assurance (or assurance :process-isolated)
        commit (manifest/manifest-commit (:publish/run-id request)
                                         (:publish/manifest request))
        decision (contract/build-decision
                  {:request request
                   :manifest-commit commit
                   :authority-key-id key-id
                   :authority-assurance assurance
                   :issued-at (or issued-at (str (java.time.Instant/now)))})
        signed (sed/sign-envelope decision contract/decision-domain private-key key-id)]
    (contract/build-response request signed)))

(defn run-from-reader
  "Execute the publisher authority reading a request from `r`. Prints the
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
        (let [reason (or (:reason (ex-data e)) :publish-error)
              detail (or (:detail (ex-data e)) (.getMessage e))]
          (binding [*out* (java.io.PrintWriter. *err* true)]
            (println (str "publish authority error: " reason " — " detail)))
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
  "CLI entry for `prf publish check`. Reads a request from stdin, signs a
   certificate, writes the response to stdout, returns an exit code.

   Signing key path, key-id and authority-assurance are read from the options
   map or, for subprocess invocation, from the environment the caller sets
   (PRF_PUBLISH_KEY, PRF_PUBLISH_KEY_ID, PRF_PUBLISH_ASSURANCE). Keeping the key
   path out of argv avoids exposing it in the process table."
  [{:keys [key assurance key-id] :as _opts}]
  (let [key (or key (get-env "PRF_PUBLISH_KEY"))
        assurance (or assurance (some-> (get-env "PRF_PUBLISH_ASSURANCE") keyword))
        key-id (or key-id (get-env "PRF_PUBLISH_KEY_ID"))
        private-key (load-private-key key)]
    (run-from-reader *in* private-key {:assurance assurance :key-id key-id})))
