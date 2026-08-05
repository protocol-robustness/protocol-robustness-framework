(ns resolver-sim.commands.resubmission-issue
  "Out-of-process resubmission receipt signer authority.

   `prf resubmission issue` reads exactly one EDN request from stdin,
   independently re-derives the committed chain transition from the presented
   pre-state and command, verifies the transaction-ordering evidence and the
   candidate receipt binding, signs the submission-attempt receipt, and writes
   exactly one EDN response to stdout. Diagnostics go to stderr only.

   The authority is a key-isolated, STATELESS signer: the validator owns the
   mutable chain store and presents state-before + command + ordering. The
   authority never trusts the caller's claim that something committed — it
   re-runs the pure transition (resolver-sim.resubmission.transition/apply-action)
   over the presented state and compares the derived state-after-root to the
   ordering's committed root.

   Receipt signing is an ATTESTATION AFTER COMMIT: the signature is attached to
   the immutable unsigned receipt projection and does not change its identity
   (resolver-sim.resubmission.receipt/sign-receipt)."
  (:require [clojure.edn :as edn]
             [buddy.core.keys :as keys]
             [resolver-sim.config.hardening :as hardening]
             [resolver-sim.resubmission.issuance :as issuance]
             [resolver-sim.resubmission.receipt :as receipt]
             [resolver-sim.resubmission.transition :as transition]
             [resolver-sim.signed-external-decision :as sed]
             [resolver-sim.transaction.ordering :as ordering])
  (:import [java.io PushbackReader Reader StringReader]))

(def protocol-version 1)
(def request-domain "PRF_RESUBMISSION_ISSUE_REQUEST_V1")
(def response-kind :resubmission-issue-response)
(def error-kind :resubmission-issue-error)

(def request-allowed-top-level
  #{:request/kind :request/version :request/hash :request/id
    :validator :transition :ordering :candidate-receipt})

(defn request-errors
  "Return a vector of human-readable errors for a request, or [] if valid."
  [req]
  (into []
        (remove nil?)
        [(when-not (map? req) "request must be a map")
         (when (and (map? req) (not= :resubmission-issue (:request/kind req)))
           (str "unexpected request/kind: " (:request/kind req)))
         (when (and (map? req) (not= protocol-version (:request/version req)))
           (str "unsupported request/version: " (:request/version req)))
         (when (and (map? req) (nil? (:request/id req)))
           "request/id required")
         (when (and (map? req) (nil? (:request/hash req)))
           "request/hash required")
         (when (and (map? req) (not (map? (:transition req))))
           "transition must be a map with :state-before and :command")
         (when (and (map? req) (not (map? (:ordering req))))
           "ordering must be a transaction-ordering map")
         (when (and (map? req) (not (map? (:candidate-receipt req))))
           "candidate-receipt must be a map")
         (when (and (map? req) (not (map? (:validator req))))
           "validator must be a map")
         (when (and (map? req)
                    (seq (remove request-allowed-top-level (keys req))))
           (str "unexpected top-level keys: "
                (pr-str (vec (sort-by pr-str
                                      (remove request-allowed-top-level (keys req)))))))]))

(defn valid-request?
  [req]
  (let [errors (request-errors req)]
    (and (empty? errors)
         (= (:request/hash req)
            (sed/request-hash request-domain req)))))

(defn decide
  "Pure decision core for receipt issuance. Returns the response map. Throws
   ex-info (with :reason) on any failure so the caller fails closed.

   auth: {:private-key <ed25519> :validator/key-id <kw|string>}. The signing
   authority must know its own key identity; a candidate receipt claiming a
   different :key/id is rejected so a signed receipt can never claim another
   validator key."
  [{:keys [private-key] :as auth} request]
  (let [validator-key-id (:validator/key-id auth)
        transition (get request :transition)
        ordering (get request :ordering)
        candidate-receipt (get request :candidate-receipt)
        state-before (:state-before transition)
        command (:command transition)
        result (transition/apply-action state-before command)]
    (when-not (and validator-key-id (some? (get-in request [:candidate-receipt
                                                            :attempt-receipt/validator
                                                            :key/id])))
      (throw (ex-info "signing key-id not configured" {:reason :signing-key-id-missing})))
    (when-not (= (:request/hash request)
                 (sed/request-hash request-domain request))
      (throw (ex-info "request hash mismatch" {:reason :request-hash-mismatch})))
    ;; 1. the presented transition must actually commit
    (when-not (= :committed (:status result))
      (throw (ex-info "transition was not committed"
                      {:reason (:reason result)
                       :transition-status (:status result)})))
    ;; 2. ordering integrity and action (checked before the state root so an
    ;;    invalid/hash-tampered ordering is rejected on its own terms)
    (when-not (:valid? (ordering/verify-ordering ordering))
      (throw (ex-info "ordering hash mismatch" {:reason :ordering-hash-mismatch})))
    (when-not (= :prf.resubmission/admit-child (:transaction/action ordering))
      (throw (ex-info "receipt issuance requires an admit-child ordering"
                      {:reason :unexpected-ordering-action
                       :action (:transaction/action ordering)})))
    ;; 3. the derived state-after-root must match the ordering's committed root
    (let [derived-root (transition/state-root (:state result))]
      (when-not (= (:transaction/state-after-root ordering) derived-root)
        (throw (ex-info "ordering state-after-root mismatch"
                        {:reason :state-after-root-mismatch
                         :ordering (:transaction/state-after-root ordering)
                         :derived derived-root}))))
    ;; 3b. the ordering's remaining committed evidence must match the re-derived
    ;;     transition exactly. The authority re-runs the pure transition, so it
    ;;     KNOWS the correct pre-state root, effects root, and expected/observed
    ;;     snapshots; an ordering that is self-hash-consistent but misstates any
    ;;     of them must not be signed.
    (let [state-before-root (transition/state-root state-before)
          effects-root (transition/effects-root (:effects result))
          ordering-input (:ordering-input result)]
      (when-not (= (:transaction/state-before-root ordering) state-before-root)
        (throw (ex-info "ordering state-before-root mismatch"
                        {:reason :state-before-root-mismatch
                         :ordering (:transaction/state-before-root ordering)
                         :derived state-before-root})))
      (when-not (= (:transaction/effects-root ordering) effects-root)
        (throw (ex-info "ordering effects-root mismatch"
                        {:reason :effects-root-mismatch
                         :ordering (:transaction/effects-root ordering)
                         :derived effects-root})))
      (when-not (= (:transaction/expected ordering-input)
                   (:transaction/expected ordering))
        (throw (ex-info "ordering expected snapshot mismatch"
                        {:reason :ordering-expected-mismatch
                         :derived (:transaction/expected ordering-input)
                         :ordering (:transaction/expected ordering)})))
      (when-not (= (:transaction/observed ordering-input)
                   (:transaction/observed ordering))
        (throw (ex-info "ordering observed snapshot mismatch"
                        {:reason :ordering-observed-mismatch
                         :derived (:transaction/observed ordering-input)
                         :ordering (:transaction/observed ordering)}))))
    ;; 3c. v2 change-identity binding: the declared change-input root must match
    ;; the canonical command the authority re-derived (state-after is then the
    ;; deterministic result: apply(change-identity, state-before-root) ->
    ;; state-after-root). v1 orders carry no input-root and skip this check.
    (when (and (ordering/v2? ordering)
               (let [expected-input-root
                     (transition/command-input-root
                      (:transaction/action command)
                      (:transaction/input command))]
                 (not= expected-input-root
                       (:transaction/input-root ordering))))
      (throw (ex-info "ordering input-root does not match re-derived command"
                      {:reason :input-root-mismatch
                       :action (:transaction/action command)
                       :input-root (:transaction/input-root ordering)})))
     ;; 4. candidate receipt binding
    (when-not (receipt/valid-receipt-shape? candidate-receipt)
      (throw (ex-info "invalid candidate receipt"
                      {:reason :invalid-candidate-receipt})))
    (let [claimed-key-id (get-in candidate-receipt
                                 [:attempt-receipt/validator :key/id])]
      (when-not (= validator-key-id claimed-key-id)
        (throw (ex-info "receipt key-id inconsistent with signing key"
                        {:reason :key-id-inconsistent
                         :signing-key-id validator-key-id
                         :claimed-key-id claimed-key-id}))))
    (let [chain (get-in candidate-receipt [:attempt-receipt/chain])]
      (when-not (issuance/receipt-binds-ordering? candidate-receipt ordering)
        (throw (ex-info "candidate receipt does not bind the ordering"
                        {:reason :receipt-ordering-binding-mismatch})))
      (when-not (issuance/transition-outcome-matches? result (:admission-status chain))
        (throw (ex-info "receipt admission status inconsistent with transition"
                        {:reason :admission-status-inconsistent
                         :transition-status (:status result)
                         :claimed (:admission-status chain)})))
      (when-not (= (:sequence (:transaction/input command)) (:sequence chain))
        (throw (ex-info "receipt sequence inconsistent with command"
                        {:reason :sequence-inconsistent
                         :command (:sequence (:transaction/input command))
                         :receipt (:sequence chain)})))
      (when-not (= (:parent-receipt-hash (:transaction/input command))
                   (:parent-receipt-hash chain))
        (throw (ex-info "receipt parent inconsistent with command"
                        {:reason :parent-inconsistent})))
      (let [ordering-family (second (:transaction/conflict-key ordering))]
        (when-not (= ordering-family (:family-id chain))
          (throw (ex-info "receipt family inconsistent with ordering"
                          {:reason :family-inconsistent
                           :ordering-family ordering-family
                           :receipt-family (:family-id chain)})))))
    ;; 5. issue (attestation after commit)
    (let [signed (receipt/sign-receipt candidate-receipt private-key)]
      {:response/kind response-kind
       :response/version protocol-version
       :request/id (:request/id request)
       :receipt signed})))

(defn- read-limited
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
   :response/version protocol-version
   :request/id request-id
   :error/reason reason
   :error/detail detail})

(defn run-from-reader
  "Execute the authority reading a request from `r`. Prints the response to
   stdout. Returns an exit code (0 on success, 1 on failure)."
  [r private-key validator-key-id]
  (let [request-id (atom nil)]
    (try
       (let [raw (read-limited r
                               (hardening/value :resubmission-max-request-bytes
                                                {:fallback (* 16 1024 1024)}))
            request (read-one-request (StringReader. raw))
            _ (reset! request-id (:request/id request))
            _ (when-not (valid-request? request)
                (throw (ex-info "invalid request"
                                {:reason :invalid-request
                                 :errors (request-errors request)})))
            response (decide {:private-key private-key
                              :validator/key-id validator-key-id}
                             request)]
        (println (pr-str response))
        0)
      (catch Exception e
        (let [reason (or (:reason (ex-data e)) :resubmission-issue-error)
              detail (or (:detail (ex-data e)) (.getMessage e))]
          (binding [*out* (java.io.PrintWriter. *err* true)]
            (println (str "resubmission issue error: " reason " — " detail)))
          (println (pr-str (error-response @request-id reason detail)))
          1)))))

(defn- load-private-key
  [path]
  (when-not path
    (throw (ex-info "no validator signing key configured"
                    {:reason :missing-signing-key})))
  (keys/private-key path))

(defn- get-env
  [k]
  (System/getenv k))

(defn run
  "CLI entry for `prf resubmission issue`. Reads a request from stdin, signs a
   submission-attempt receipt, writes the response to stdout, returns an exit
   code.

   Validator signing key path (and optional key-id) come from the options map
   or, for subprocess invocation, from the environment the caller sets
   (PRF_VALIDATOR_KEY, PRF_VALIDATOR_KEY_ID). Keeping the key path out of argv
   avoids exposing it in the process table."
  [{:keys [validator-key key-id] :as _opts}]
  (let [key (or validator-key (get-env "PRF_VALIDATOR_KEY"))
        validator-key-id (or key-id (get-env "PRF_VALIDATOR_KEY_ID"))
        _ (when-not validator-key-id
            (throw (ex-info "no validator key-id configured"
                            {:reason :missing-key-id})))
        private-key (load-private-key key)]
    (run-from-reader *in* private-key validator-key-id)))
