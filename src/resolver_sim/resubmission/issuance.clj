(ns resolver-sim.resubmission.issuance
  "Attempt-receipt issuance helpers.

   Receipt issuance binds a signed submission-attempt receipt to the committed
   transaction ordering WITHOUT creating a hash cycle:

     - the transaction ordering commits the chain-state transition
       (state-before/state-after roots), excluding the receipt artifact;
     - the attempt receipt's :attempt-receipt/chain block commits the resulting
       :transaction-ordering/hash;
     - the validator signature is an ATTESTATION over the immutable unsigned
       receipt projection (attached after commit; it does not change the
       receipt identity).

   V2 (application-aware) issuance:
     - the request carries the canonical application target
       (:attempt-target/type :use-case-application / :attempt-target/root)
     - the issuer resolves the V2 evaluation, extracts
       :use-case-application/root, and REQUIRES the request target to equal it
     - the attempt subject is constructed from the evaluation root, the
       submitted-bundle root (from the candidate receipt), and the
       request-matched application target
     - the V2 receipt (submission-attempt-receipt.v2) is signed under the V2
       domain, binding the attempt subject root

   These helpers are pure. The signer authority
   (resolver-sim.commands.resubmission-issue) independently re-derives the
   transition from the presented pre-state and command, then issues here."
  (:require [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.resubmission.attempt-subject :as attempt-subject]
            [resolver-sim.resubmission.acceptance-evaluation :as evaluation]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const request-domain-v1
  "Domain tag for the V1 resubmission-issue request."
  "PRF_RESUBMISSION_ISSUE_REQUEST_V1")

(def ^:const request-domain-v2
  "Domain tag for the V2 (application-aware) resubmission-issue request."
  "PRF_RESUBMISSION_ISSUE_REQUEST_V2")

(def ^:const attempt-target-type
  "The only target type permitted for attempt-subject targets."
  :use-case-application)

(def ^:const attempt-target-fields
  "Exactly-permitted fields of a V2 request's :attempt-target map."
  #{:attempt-target/type :attempt-target/root})

;; ── V1 helpers (unchanged semantics) ─────────────────────────────────────────

(defn admission-status-for
  "Map a pure transition :status to the receipt's :attempt-receipt/chain
   :admission-status. Only a :committed transition admits a chain successor."
  [transition-status]
  (case transition-status
    :committed :admitted
    :not-admitted))

(defn receipt-binds-attempt-subject?
  "True when a receipt explicitly attests the canonical attempt subject root.
   Reservation and fence fields are intentionally ignored."
  [receipt subject]
  (and (attempt-subject/valid? subject)
       (= (:attempt/subject-root subject)
          (:attempt-receipt/attempt-subject-root receipt))))

(defn bind-attempt-subject
  "Bind a validated canonical attempt subject to a receipt candidate. The
   subject root is semantic identity; reservation and fence data remain in the
   separate chain/admission block."
  [candidate subject]
  (when-not (attempt-subject/valid? subject)
    (throw (ex-info "invalid attempt subject" {:reason :invalid-attempt-subject})))
  (assoc candidate :attempt-receipt/attempt-subject-root
         (:attempt/subject-root subject)))

(defn receipt-candidate
  "Attach the :attempt-receipt/chain block to a candidate receipt.

   `candidate` must already carry roots, results status, submitter, outcome,
   finality, eligibility, findings, evaluation, and validator authority (per
   valid-receipt-shape?).

   `chain` facts:
     {:admission-status kw
      :family-id str
      :sequence int
      :parent-receipt-hash str|nil
      :transaction-ordering-hash str}"
  [candidate {:keys [admission-status family-id sequence parent-receipt-hash
                     transaction-ordering-hash attempt-subject]}]
  (cond-> (assoc candidate :attempt-receipt/chain
                 {:admission-status admission-status
                  :family-id family-id
                  :sequence sequence
                  :parent-receipt-hash parent-receipt-hash
                  :transaction-ordering-hash transaction-ordering-hash})
    attempt-subject (bind-attempt-subject attempt-subject)))

(defn transition-outcome-matches?
  "The receipt's claimed admission status must be consistent with the pure
   transition outcome."
  [transition-result claimed-admission]
  (= (admission-status-for (:status transition-result)) claimed-admission))

(defn receipt-binds-ordering?
  "The receipt's :attempt-receipt/chain must commit the ordering hash and claim
   :admitted. The ordering is authoritative for what actually committed.

   The binding is only trustworthy if the ordering itself is sound, so this
   additionally requires the ordering's self-hash to recompute (ordering/
   verify-ordering) and the action to be the admit-child admission it claims.

   NOTE: the admit-child action gate is a whitelist of exactly one ordering
   action. If new ordering actions are introduced, this predicate must be
   extended to admit them."
  [receipt ordering]
  (and (map? ordering)
       (map? receipt)
       (let [chain (:attempt-receipt/chain receipt)]
         (and (:valid? (ordering/verify-ordering ordering))
              (= :prf.resubmission/admit-child (:transaction/action ordering))
              (= (:transaction-ordering/hash ordering)
                 (get-in receipt [:attempt-receipt/chain :transaction-ordering-hash]))
              (= :admitted (get-in receipt [:attempt-receipt/chain :admission-status]))))))

(defn receipt-chain-join
  "Validate receipt chain metadata against the committed admit-child ordering.
   Returns {:valid? boolean :reason keyword} and never signs a receipt.
   This is shared by synchronous and post-commit issuance paths."
  [receipt ordering]
  (let [chain (get-in receipt [:attempt-receipt/chain])
        input (:transaction/input ordering)]
    (cond
      (not (receipt-binds-ordering? receipt ordering))
      {:valid? false :reason :receipt-ordering-binding-mismatch}
      (not= (second (:transaction/conflict-key ordering)) (:family-id chain))
      {:valid? false :reason :family-inconsistent}
      (not= (:sequence input) (:sequence chain))
      {:valid? false :reason :sequence-inconsistent}
      (not= (:parent-receipt-hash input) (:parent-receipt-hash chain))
      {:valid? false :reason :parent-inconsistent}
      :else
      {:valid? true :reason :ok})))

;; ── V2: application-aware issuance ───────────────────────────────────────────

(defn valid-attempt-target?
  "Validate the V2 request's :attempt-target map. The target type must be
   :use-case-application and the target root must be a valid canonical
   sha256 reference. The target is resolved ONLY from the request, never from
   reservation, candidate, validation, fence, or transaction fields."
  [target]
  (and (map? target)
       (= attempt-target-fields (set (keys target)))
       (= attempt-target-type (:attempt-target/type target))
       (hash-ref/valid-sha256-ref? (:attempt-target/root target))
       (some? (:attempt-target/root target))))

(defn resolve-evaluation-target
  "Resolve the application target from the V2 acceptance evaluation basis.
   Returns {:valid? bool :reason kw :use-case-application-root str|nil}."
  [evaluation]
  (let [schema (:artifact/schema evaluation)
        app-root (get-in evaluation [:evaluation/basis :use-case-application/root])]
    (cond
      (not= evaluation/evaluation-v2-schema schema)
      {:valid? false :reason :not-v2-evaluation
       :use-case-application-root nil}

      (nil? app-root)
      {:valid? false :reason :evaluation-missing-application-root
       :use-case-application-root nil}

      (not (hash-ref/valid-sha256-ref? app-root))
      {:valid? false :reason :evaluation-invalid-application-root
       :use-case-application-root nil}

      :else
      {:valid? true :reason :ok :use-case-application-root app-root})))

(defn verify-request-evaluation-root
  "Verify the evaluation's self-hash (root) matches the recomputed root."
  [evaluation]
  (= (:acceptance-evaluation/root evaluation)
     (evaluation/evaluation-root evaluation)))

(defn verify-target-matches-evaluation
  "Require the request's application target to equal the evaluation's
   :use-case-application/root. Reject mismatches before signing."
  [target evaluation]
  (let [resolved (resolve-evaluation-target evaluation)]
    (cond
      (not (:valid? resolved))
      {:valid? false :reason (:reason resolved)}

      (not= (:attempt-target/root target)
            (:use-case-application-root resolved))
      {:valid? false :reason :target-application-root-mismatch
       :request-root (:attempt-target/root target)
       :evaluation-root (:use-case-application-root resolved)}

      :else
      {:valid? true :reason :ok
       :use-case-application-root (:use-case-application-root resolved)})))

(defn build-attempt-subject-from-evaluation
  "Build a canonical attempt subject from the V2 evaluation root, the
   submitted-bundle root (from the candidate receipt), and the evaluation/
   request-matched application target. The application root is reused
   directly — never re-hashed."
  [evaluation-root submitted-bundle-root target]
  (attempt-subject/build evaluation-root submitted-bundle-root
                         {:attempt-target/type (:attempt-target/type target)
                          :attempt-target/root (:attempt-target/root target)}))

(defn verify-subject-reconstruction
  "Reject caller-supplied subject roots unless reconstruction agrees exactly.
   Returns {:valid? bool :reason kw :reconstructed-root str}."
  [evaluation-root submitted-bundle-root target caller-root]
  (let [reconstructed (build-attempt-subject-from-evaluation
                       evaluation-root submitted-bundle-root target)
        own-root (:attempt/subject-root reconstructed)]
    (cond
      (nil? caller-root)
      {:valid? true :reason :ok :reconstructed-root own-root}

      (not (hash-ref/valid-sha256-ref? caller-root))
      {:valid? false :reason :malformed-subject-root
       :reconstructed-root own-root}

      (not= caller-root own-root)
      {:valid? false :reason :subject-root-mismatch
       :reconstructed-root own-root
       :caller-root caller-root}

      :else
      {:valid? true :reason :ok :reconstructed-root own-root})))

(defn receipt-candidate-v2
  "Construct a V2 (application-aware) receipt candidate.

   `evaluation` must be a valid V2 acceptance evaluation (with
   :acceptance-evaluation/root and :evaluation/basis containing
   :use-case-application/root).

   `target` is the request's {:attempt-target/type :use-case-application
   :attempt-target/root ...}.

   `submitted-bundle-root` comes from the candidate receipt (never from the
   request's reservation, validation, or transaction fields).

   The attempt subject is constructed from the evaluation root, the
   submitted-bundle root, and the matched application target. The subject root
   is bound to the V2 receipt candidate.

   Returns the receipt candidate with :attempt-receipt/chain and
   :attempt-receipt/attempt-subject-root attached, or throws on mismatch."
  [candidate-receipt evaluation submitted-bundle-root target
   {:keys [admission-status family-id sequence parent-receipt-hash
           transaction-ordering-hash]}]
  (when-not (valid-attempt-target? target)
    (throw (ex-info "invalid attempt target" {:reason :invalid-attempt-target})))
  (when-not (verify-request-evaluation-root evaluation)
    (throw (ex-info "evaluation root mismatch" {:reason :evaluation-root-mismatch})))
  (let [target-check (verify-target-matches-evaluation target evaluation)]
    (when-not (:valid? target-check)
      (throw (ex-info "request target does not match evaluation"
                      {:reason (:reason target-check)
                       :detail (pr-str target-check)})))
    (let [evaluation-root (:acceptance-evaluation/root evaluation)
          caller-root (:attempt-receipt/attempt-subject-root candidate-receipt)
          subject-check (verify-subject-reconstruction
                         evaluation-root submitted-bundle-root target caller-root)]
      (when-not (:valid? subject-check)
        (throw (ex-info "attempt subject reconstruction mismatch"
                        {:reason (:reason subject-check)
                         :detail (pr-str subject-check)})))
      (let [subject (build-attempt-subject-from-evaluation
                     evaluation-root submitted-bundle-root target)]
        (receipt-candidate candidate-receipt
                           {:admission-status admission-status
                            :family-id family-id
                            :sequence sequence
                            :parent-receipt-hash parent-receipt-hash
                            :transaction-ordering-hash transaction-ordering-hash
                            :attempt-subject subject})))))
