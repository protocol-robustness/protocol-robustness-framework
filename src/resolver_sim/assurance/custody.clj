(ns resolver-sim.assurance.custody
  "Protocol-independent closed-form validation for held-custody artifacts.

   Accepts artifact maps, returns check results. No Sew world-state dependency.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - benchmarks/packs/sew/"
  (:require [resolver-sim.hash.canonical :as hash]
            [resolver-sim.assurance.parameter-attribution :as pa]
            [resolver-sim.accounting.held-ledger-index :as held-index]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.hash.reference :as hash-ref]))

(declare build-held-custody-artifact)

(defn parameter-attribution-check
  "Validate the optional parameter provenance pair carried by a held adjustment.

   A context is a compact, stable reference: exactly one of a cryptographic
   root plus :parameter-context/version (the context-reference schema version,
   not a mutable parameter-set revision), or an explicit non-cryptographic
   context id. An address is exactly one semantic parameter id (optionally
   instance-scoped) or a non-empty EDN path of canonical scalar segments.
   This verifies attribution shape only; parameter resolution and economic
   correctness remain application-layer concerns."
  [parameter-context parameter-address]
  (let [carrier {:parameter/context parameter-context
                 :parameter/address parameter-address}
        error (pa/parameter-attribution-error carrier)]
    {:parameter-pair-complete? (not (contains? #{:parameter-context-without-address
                                                 :parameter-address-without-context} error))
     :parameter-context-valid? (not= :invalid-parameter-context error)
     :parameter-address-valid? (not= :invalid-parameter-address error)}))

(defn parameter-attribution-status
  "Structural verification status for parameter provenance. Resolution is
   deliberately unsupported at the custody layer, so it is always false."
  [parameter-context parameter-address]
  (let [checks (parameter-attribution-check parameter-context parameter-address)
        present? (or (some? parameter-context) (some? parameter-address))]
    (assoc checks
           :present? present?
           :structurally-valid? (every? true? (vals checks))
           :context-resolved? false
           :basis :structural-provenance)))

(defn valid-parameter-attribution?
  [parameter-context parameter-address]
  (:structurally-valid? (parameter-attribution-status parameter-context parameter-address)))

(defn held-adjustment-order
  "Numeric sequence order for canonical held-adjustment IDs; lexical ordering
   would incorrectly place held-adjustment-10 before held-adjustment-2."
  [adjustment]
  (let [id (:held-adjustment/id adjustment)
        match (and (string? id) (re-matches #"held-adjustment-(\d+)" id))]
    [(if match (Long/parseLong (second match)) Long/MAX_VALUE) (str id)]))

(def ^:private held-custody-artifact-v2 "held-custody-adjustment.artifact.v2")
(def ^:private held-custody-artifact-v3 "held-custody-adjustment.artifact.v3")
(def ^:private supported-held-custody-artifact-versions
  #{held-custody-artifact-v2 held-custody-artifact-v3})

(defn held-custody-artifact-payload
  "The schema-specific, hash-committed projection of a held custody artifact."
  [artifact]
  (let [v3? (= held-custody-artifact-v3 (:schema-version artifact))]
    (cond-> {:schema-version (:schema-version artifact)
             :artifact/kind (:artifact/kind artifact)
             :held-adjustment/id (:held-adjustment/id artifact)
             :held/direction (:held/direction artifact)
             :token (:token artifact)
             :amount (:amount artifact)
             :held/before (:held/before artifact)
             :held/after (:held/after artifact)
             :held/reason (:held/reason artifact)
             :held/action (:held/action artifact)}
      (:held/account artifact) (assoc :held/account (:held/account artifact))
      (:held/position-id artifact) (assoc :held/position-id (:held/position-id artifact))
      (:held/workflow-id artifact) (assoc :held/workflow-id (:held/workflow-id artifact))
      (:owner/address artifact) (assoc :owner/address (:owner/address artifact))
      (and v3? (:parameter/context artifact)) (assoc :parameter/context (:parameter/context artifact))
      (and v3? (:parameter/address artifact)) (assoc :parameter/address (:parameter/address artifact))
      (:held/previous-artifact-hash artifact) (assoc :held/previous-artifact-hash (:held/previous-artifact-hash artifact))
      (:authorization/provenance artifact) (assoc :authorization/provenance (:authorization/provenance artifact)))))

(defn- artifact-surface-checks
  "Deterministic closed-form checks over the held custody ARTIFACT surface.
   These checks do not replace the canonical held-adjustment ledger; they
   verify that the first-class artifact surface is internally consistent and
   replay-consistent enough for researcher-facing validation.  They operate on
   the artifact package alone (no ledger dependency).

   Check ids:
   - :held-custody/hash-integrity
   - :held-custody/local-delta
   - :held-custody/non-negative-after
   - :held-custody/sequence-replay"
  [artifacts]
  (let [ordered (sort-by held-adjustment-order artifacts)
        hash-violations
        (->> ordered
             (keep (fn [artifact]
                     (let [expected (-> artifact
                                        held-custody-artifact-payload
                                        (#(hash-ref/sha256-ref
                                           (hash/hash-with-intent
                                            {:hash/intent :evidence-record}
                                            %))))]
                       (when (not= expected (:artifact/hash artifact))
                         {:held-adjustment/id (:held-adjustment/id artifact)
                          :expected expected
                          :actual (:artifact/hash artifact)}))))
             vec)
        schema-violations
        (->> ordered
             (keep (fn [artifact]
                     (when-not (contains? supported-held-custody-artifact-versions
                                          (:schema-version artifact))
                       {:held-adjustment/id (:held-adjustment/id artifact)
                        :schema-version (:schema-version artifact)
                        :reason :unsupported-schema-version})))
             vec)
        parameter-attribution-statuses
        (mapv (fn [artifact]
                (let [status (parameter-attribution-status
                              (:parameter/context artifact)
                              (:parameter/address artifact))
                      v2? (= held-custody-artifact-v2 (:schema-version artifact))
                      v3? (= held-custody-artifact-v3 (:schema-version artifact))
                      schema-valid? (or v2? v3?)
                      expected-hash (hash-ref/sha256-ref
                                     (hash/hash-with-intent {:hash/intent :evidence-record}
                                                            (held-custody-artifact-payload artifact)))
                      attribution-valid? (and (:structurally-valid? status)
                                              (not (and v2? (:present? status))))
                      artifact-valid? (and schema-valid?
                                           (= expected-hash (:artifact/hash artifact))
                                           attribution-valid?)
                      classification (if artifact-valid?
                                       (cond v2? :legacy-v2
                                             (:present? status) :attributed-v3
                                             :else :unattributed-v3)
                                       :invalid)]
                  (assoc status
                         :held-adjustment/id (:held-adjustment/id artifact)
                         :structurally-valid? attribution-valid?
                         :artifact-valid? artifact-valid?
                         :parameter-attribution/classification classification
                         :attribution/authenticated?
                         (if-not artifact-valid?
                           :invalid
                           (cond v2? :not-authenticated-by-schema
                                 (:present? status) :authenticated-structural
                                 :else :absent))
                         :basis :structural-provenance)))
              ordered)
        parameter-attribution-violations
        (filterv #(not (:structurally-valid? %)) parameter-attribution-statuses)
        local-delta-violations
        (->> ordered
             (keep (fn [artifact]
                     (let [before (long (:held/before artifact 0))
                           after (long (:held/after artifact 0))
                           amount (long (:amount artifact 0))
                           expected-after (case (:held/direction artifact)
                                            :in (+ before amount)
                                            :out (- before amount)
                                            ::invalid)]
                       (when (not= expected-after after)
                         {:held-adjustment/id (:held-adjustment/id artifact)
                          :expected-after expected-after
                          :actual-after after}))))
             vec)
        negative-after-violations
        (->> ordered
             (keep (fn [artifact]
                     (when (neg? (long (:held/after artifact 0)))
                       {:held-adjustment/id (:held-adjustment/id artifact)
                        :held/after (:held/after artifact)})))
             vec)
        replay-state
        (reduce (fn [state artifact]
                  (let [token (:token artifact)
                        current (get state token (:held/before artifact))
                        amount (long (:amount artifact 0))
                        expected-after (case (:held/direction artifact)
                                         :in (+ (long current) amount)
                                         :out (- (long current) amount)
                                         ::invalid)]
                    (assoc state token expected-after)))
                {}
                ordered)
        predecessor-violations
        (loop [previous-hash nil
               remaining ordered
               violations []]
          (if-let [artifact (first remaining)]
            (recur (:artifact/hash artifact)
                   (next remaining)
                   (cond-> violations
                     (not= previous-hash (:held/previous-artifact-hash artifact))
                     (conj {:held-adjustment/id (:held-adjustment/id artifact)
                            :expected-previous-artifact-hash previous-hash
                            :actual-previous-artifact-hash (:held/previous-artifact-hash artifact)})))
            violations))
        sequence-replay-violations
        (loop [state {}
               remaining ordered
               violations []]
          (if-let [artifact (first remaining)]
            (let [token (:token artifact)
                  current (get state token (:held/before artifact))
                  before (long (:held/before artifact 0))
                  amount (long (:amount artifact 0))
                  expected-after (case (:held/direction artifact)
                                   :in (+ (long current) amount)
                                   :out (- (long current) amount)
                                   ::invalid)
                  violations' (cond-> violations
                                (not= current before)
                                (conj {:held-adjustment/id (:held-adjustment/id artifact)
                                       :expected-before current
                                       :actual-before before})
                                (not= expected-after (:held/after artifact))
                                (conj {:held-adjustment/id (:held-adjustment/id artifact)
                                       :expected-after expected-after
                                       :actual-after (:held/after artifact)}))]
              (recur (assoc state token expected-after) (next remaining) violations'))
            violations))]
    [{:check/id :held-custody/hash-integrity
      :status (if (empty? hash-violations) :pass :fail)
      :details {:violations hash-violations}}
     {:check/id :held-custody/artifact-schema
      :status (if (empty? schema-violations) :pass :fail)
      :details {:violations schema-violations}}
     {:check/id :held-custody/parameter-attribution
      :status (if (empty? parameter-attribution-violations) :pass :fail)
      :details {:basis :structural-provenance
                :valid-classification-counts
                (frequencies (map :parameter-attribution/classification
                                  (filter :artifact-valid?
                                          parameter-attribution-statuses)))
                :invalid-artifact-count
                (count (remove :artifact-valid? parameter-attribution-statuses))
                :attributions parameter-attribution-statuses
                :violations parameter-attribution-violations}}
     {:check/id :held-custody/local-delta
      :status (if (empty? local-delta-violations) :pass :fail)
      :details {:violations local-delta-violations}}
     {:check/id :held-custody/non-negative-after
      :status (if (empty? negative-after-violations) :pass :fail)
      :details {:violations negative-after-violations}}
     {:check/id :held-custody/predecessor-continuity
      :status (if (empty? predecessor-violations) :pass :fail)
      :details {:violations predecessor-violations}}
     {:check/id :held-custody/sequence-replay
      :status (if (empty? sequence-replay-violations) :pass :fail)
      :details {:violations sequence-replay-violations
                :replayed-final-state replay-state}}]))

(defn- throw-on-failed-checks
  "Fail closed: throw with the full check results when any check is :fail."
  [results]
  (let [failed (filterv #(= :fail (:status %)) results)]
    (when (seq failed)
      (throw (ex-info "Held custody closed-form checks failed"
                      {:type :closed-form-failure
                       :check-results results
                       :failed-checks failed})))
    results))

;; ═══════════════════════════════════════════════════════════════════════════
;; Ledger <-> artifact completeness (P0)
;; ═══════════════════════════════════════════════════════════════════════════
;; The artifact-surface battery alone cannot prove that the artifact package is
;; an exact image of the canonical ledger.  These two checks close that gap by
;; taking BOTH the ledger and the artifacts.

(defn held-custody-ledger-artifact-checks
  "Ledger <-> artifact completeness and ordering.

   :held-custody/ledger-artifact-bijection — the artifact package is an exact,
   one-to-one image of the canonical ledger: same count, same
   :held-adjustment/id set (no missing, extra, or duplicate ids), and every
   artifact recomputes from the ledger adjustment with the same id (catching an
   artifact bound to a different adjustment).  A chain that omits a ledger entry,
   contains a spurious artifact, or substitutes one adjustment's artifact for
   another is rejected here.

   :held-custody/ledger-artifact-order — the artifact sequence corresponds to the
   ledger's canonical sequence: the canonical id-sequence (by held-adjustment-order)
   equals the ledger's, and the artifacts are PRESENTED in that canonical order
   (a reordered or subset presentation is flagged).  Chain integrity between
   artifacts is verified separately by :held-custody/predecessor-continuity.

   Fail closed: nil for either side is :not-evaluated, never a silent pass."
  [adjustments artifacts]
  (if (or (nil? adjustments) (nil? artifacts))
    [{:check/id :held-custody/ledger-artifact-bijection
      :status :not-evaluated
      :details {:reason :missing-ledger-or-artifacts}}
     {:check/id :held-custody/ledger-artifact-order
      :status :not-evaluated
      :details {:reason :missing-ledger-or-artifacts}}]
    (let [adjustments (vec adjustments)
          artifacts (vec artifacts)
          ordered-adj (sort-by held-adjustment-order adjustments)
          ordered-arts (sort-by held-adjustment-order artifacts)
          adj-ids (mapv :held-adjustment/id ordered-adj)
          art-ids (mapv :held-adjustment/id ordered-arts)
          given-art-ids (mapv :held-adjustment/id artifacts)
          adj-set (set adj-ids)
          art-set (set art-ids)
          art-by-id (into {} (map (juxt :held-adjustment/id identity)) artifacts)
          adj-by-id (into {} (map (juxt :held-adjustment/id identity)) adjustments)
          missing (vec (remove art-set adj-ids))
          extra (vec (remove adj-set art-ids))
          dup-adj (->> (frequencies adj-ids)
                       (keep (fn [[id n]] (when (> n 1) id)))
                       vec)
          dup-art (->> (frequencies art-ids)
                       (keep (fn [[id n]] (when (> n 1) id)))
                       vec)
          hash-mismatches
          (into []
                (keep (fn [id]
                        (let [expected (build-held-custody-artifact (get adj-by-id id))
                              actual (get art-by-id id)]
                          (when (or (nil? actual)
                                    (not= (:artifact/hash expected) (:artifact/hash actual)))
                            {:held-adjustment/id id
                             :expected-hash (:artifact/hash expected)
                             :actual-hash (:artifact/hash actual)}))))
                adj-ids)
          bijection-violations
          (cond-> []
            (not= (count adjustments) (count artifacts))
            (conj {:type :count-mismatch
                   :adjustment-count (count adjustments)
                   :artifact-count (count artifacts)})
            (seq dup-adj)
            (conj {:type :duplicate-adjustment-ids :ids dup-adj})
            (seq dup-art)
            (conj {:type :duplicate-artifact-ids :ids dup-art})
            (seq missing)
            (conj {:type :missing-artifacts :ids missing})
            (seq extra)
            (conj {:type :extra-artifacts :ids extra})
            (seq hash-mismatches)
            (conj {:type :artifact-identity-mismatch :mismatches hash-mismatches}))
          order-violations
          (cond-> []
            (not= adj-ids art-ids)
            (conj {:type :sequence-mismatch
                   :ledger-ids adj-ids
                   :artifact-ids art-ids})
            (not= given-art-ids art-ids)
            (conj {:type :presentation-not-canonical
                   :presented-ids given-art-ids
                   :canonical-ids art-ids}))]
      [{:check/id :held-custody/ledger-artifact-bijection
        :status (if (empty? bijection-violations) :pass :fail)
        :details {:violations bijection-violations}}
       {:check/id :held-custody/ledger-artifact-order
        :status (if (empty? order-violations) :pass :fail)
        :details {:violations order-violations}}])))

;; ═══════════════════════════════════════════════════════════════════════════
;; Replayed reason/position policy and attribution requirements (P1)
;; ═══════════════════════════════════════════════════════════════════════════
;; The write path enforces reason-derived positions and ownership requirements;
;; these checks re-derive the same obligations from the ledger so the verifier
;; does not trust the artifact's selected account.

(defn held-custody-reason-attribution-checks
  "Replay reason-position policy and attribution requirements over the LEDGER
   (not the artifact surface), so the verifier does not trust the artifact's
   selected account.

   :held-custody/reason-position-policy — every :held/reason is classified by the
   shared held-position-policy: position-bearing reasons must match the derived
   account/position-id, policy-exempt reasons must carry no position-id, and an
   unknown reason (:unknown-reason-outside-policy) is a violation unless it is
   committed in held-position-policy/policy-exempt-reasons.

   :held-custody/attribution-shape — when owner/address or the parameter pair is
   present it must be structurally valid.

   :held-custody/required-attribution — a reason that demands explicit ownership
   (held-position-policy/address-scoped-held-reasons) must have committed a
   non-blank :owner/address."
  [adjustments]
  (let [adjustments (vec (or adjustments []))
        policy-violations
        (into []
              (keep (fn [adj]
                      (let [err (held-policy/position-policy-check-error adj)]
                        (when err
                          {:held-adjustment/id (:held-adjustment/id adj)
                           :held/reason (:held/reason adj)
                           :error err})))
                    adjustments))
        shape-violations
        (into []
              (keep (fn [adj]
                      (let [owner (:owner/address adj)
                            bad-owner (and (some? owner) (held-policy/blank-owner? owner))
                            pa-error (pa/parameter-attribution-error
                                      {:parameter/context (:parameter/context adj)
                                       :parameter/address (:parameter/address adj)})]
                        (when (or bad-owner (some? pa-error))
                          {:held-adjustment/id (:held-adjustment/id adj)
                           :owner-invalid? bad-owner
                           :parameter-attribution-error pa-error})))
                    adjustments))
        required-violations
        (into []
              (keep (fn [adj]
                      (let [reason (:held/reason adj)]
                        (when (and (held-policy/required-owner-attribution? reason)
                                   (held-policy/blank-owner? (:owner/address adj)))
                          {:held-adjustment/id (:held-adjustment/id adj)
                           :held/reason reason})))
                    adjustments))]
    [{:check/id :held-custody/reason-position-policy
      :status (if (empty? policy-violations) :pass :fail)
      :details {:violations policy-violations}}
     {:check/id :held-custody/attribution-shape
      :status (if (empty? shape-violations) :pass :fail)
      :details {:violations shape-violations}}
     {:check/id :held-custody/required-attribution
      :status (if (empty? required-violations) :pass :fail)
      :details {:violations required-violations}}]))

(defn held-custody-closed-form-checks
  "Deterministic closed-form checks for held custody.  Fail closed: any failing
   check throws ex-info carrying :check-results (and :failed-checks) in ex-data.

   One-arity (artifacts only) runs the artifact-surface battery
   (artifact-surface-checks).

   Two-arity (adjustments + artifacts) additionally runs:
     - :held-custody/ledger-artifact-bijection
     - :held-custody/ledger-artifact-order
     - :held-custody/reason-position-policy
     - :held-custody/attribution-shape
     - :held-custody/required-attribution"
  ([artifacts]
   (throw-on-failed-checks (artifact-surface-checks artifacts)))
  ([adjustments artifacts]
   (throw-on-failed-checks
    (into (vec (artifact-surface-checks artifacts))
          (concat (held-custody-ledger-artifact-checks adjustments artifacts)
                  (held-custody-reason-attribution-checks adjustments))))))

(defn replay-held-adjustment-state
  "Replay a held-adjustment ledger into replay-verified materialized custody
   views. The ledger is canonical; returned indexes and balances are derived.

   Opening semantics: replay starts from `initial-held` (default {}). This is
   the STRONG reconstruction path and it enforces the zero-origin contract —
   for every token, the first adjustment's :held/before must equal the running
   replay value (0 with the default opening), otherwise a :held-adjustment
   before-mismatch is thrown. Use held-history-zero-origin? to check the
   contract before replaying, or supply a committed initial-held.

   Index keys:
   - :by-token     — total held per token (always >= 0 by invariant)
   - :by-position  — total held per position-id (always >= 0 by invariant)
   - :by-account   — total held per account type (always >= 0 by invariant)
   - :by-owner     — net custody-flow attribution per owner address.
                     This is NOT a custody balance: an owner may receive flow
                     from multiple positions, and funds can move between owners
                     via settlement. Negative values indicate net outflow from
                     that address's custody flow, which is expected when
                     escrows are released or refunded.
   - :by-workflow  — total held per workflow-id (always >= 0 by invariant)"
  ([adjustments] (replay-held-adjustment-state {} adjustments))
  ([initial-held adjustments]
   (let [initial-state {:held-ledger/index {:by-token initial-held
                                            :by-position {}
                                            :by-account {}
                                            :by-owner {}
                                            :by-workflow {}}
                        :total-held initial-held
                        :held/positions {}}]
     (let [replayed
           (reduce (fn [{total-held :total-held
                         index :held-ledger/index
                         positions :held/positions} adjustment]
                     (let [{direction :held/direction
                            token :token
                            amount :amount
                            before :held/before
                            after :held/after
                            position-id :held/position-id
                            held-account :held/account
                            owner-address :owner/address} adjustment
                           workflow-id (:held/workflow-id adjustment)
                           current (get total-held token 0)
                           expected-after (case direction
                                            :in  (+ current amount)
                                            :out (- current amount)
                                            (throw (ex-info "invalid held direction"
                                                            {:type :invalid-held-adjustment
                                                             :direction direction
                                                             :adjustment adjustment})))]
                       (when-not (valid-parameter-attribution?
                                  (:parameter/context adjustment)
                                  (:parameter/address adjustment))
                         (throw (ex-info "held adjustment has invalid parameter attribution"
                                         {:type :invalid-held-adjustment
                                          :parameter-attribution
                                          (parameter-attribution-check
                                           (:parameter/context adjustment)
                                           (:parameter/address adjustment))
                                          :adjustment adjustment})))
                       (when (nil? token)
                         (throw (ex-info "held adjustment missing token"
                                         {:type :invalid-held-adjustment
                                          :adjustment adjustment})))
                       (when (or (nil? amount) (neg? amount))
                         (throw (ex-info "held adjustment amount must be non-negative"
                                         {:type :invalid-held-adjustment
                                          :adjustment adjustment})))
                       (when (neg? expected-after)
                         (throw (ex-info "held adjustment replay underflow"
                                         {:type :invalid-held-adjustment
                                          :adjustment adjustment
                                          :current current})))
                       (when (not= current before)
                         (throw (ex-info "held adjustment before mismatch"
                                         {:type :invalid-held-adjustment
                                          :adjustment adjustment
                                          :current current})))
                       (when (not= expected-after after)
                         (throw (ex-info "held adjustment after mismatch"
                                         {:type :invalid-held-adjustment
                                          :adjustment adjustment
                                          :expected-after expected-after})))
                       (let [step-fn (case direction
                                       :in +
                                       :out -)
                             index' (cond-> (update-in index [:by-token token] (fnil step-fn 0) amount)
                                      position-id
                                      (update-in [:by-position position-id] (fnil step-fn 0) amount)

                                      held-account
                                      (update-in [:by-account held-account] (fnil step-fn 0) amount)

                                      owner-address
                                      (update-in [:by-owner owner-address] (fnil step-fn 0) amount)

                                      workflow-id
                                      (update-in [:by-workflow workflow-id] (fnil step-fn 0) amount))]
                         {:held-ledger/index index'
                          :total-held (:by-token index')
                          :held/positions (:by-position index')})))
                   initial-state
                   adjustments)]
       (held-index/validate-held-custody-state replayed)
       replayed))))

(def ^:private held-custody-artifact-version held-custody-artifact-v3)

(defn build-held-custody-artifact
  "Build a minimal research-grade artifact from a canonical held adjustment.
   The held adjustment remains authoritative; this artifact is a stable,
   content-addressed consumer surface for later closed-form validation."
  [adjustment]
  (let [body (cond-> {:schema-version held-custody-artifact-version
                      :artifact/kind :held-custody-adjustment
                      :held-adjustment/id (:held-adjustment/id adjustment)
                      :held/direction (:held/direction adjustment)
                      :token (:token adjustment)
                      :amount (:amount adjustment)
                      :held/before (:held/before adjustment)
                      :held/after (:held/after adjustment)
                      :held/reason (:held/reason adjustment)
                      :held/action (:held/action adjustment)}
               (:held/account adjustment)
               (assoc :held/account (:held/account adjustment))

               (:held/position-id adjustment)
               (assoc :held/position-id (:held/position-id adjustment))

               (:held/workflow-id adjustment)
               (assoc :held/workflow-id (:held/workflow-id adjustment))

               (:owner/address adjustment)
               (assoc :owner/address (:owner/address adjustment))

               (:parameter/context adjustment)
               (assoc :parameter/context (:parameter/context adjustment))

               (:parameter/address adjustment)
               (assoc :parameter/address (:parameter/address adjustment))

               (:held/previous-artifact-hash adjustment)
               (assoc :held/previous-artifact-hash (:held/previous-artifact-hash adjustment))

               (:authorization/provenance adjustment)
               (assoc :authorization/provenance
                      (select-keys (:authorization/provenance adjustment)
                                   [:authorization/schema-version
                                    :authorization/type
                                    :authorization/id
                                    :authorization/scope-hash
                                    :authorization/workflow-id
                                    :authorization/allowed-action
                                    :authorization/basis
                                    :authorization/check
                                    :authorization/actor-id
                                    :authorization/source])))]
    (assoc body
           :artifact/id (str "held-custody-" (:held-adjustment/id adjustment))
           :artifact/hash (hash-ref/sha256-ref
                           (hash/hash-with-intent {:hash/intent :evidence-record}
                                                  body)))))

(defn rebuild-held-custody-artifacts
  "Derive the materialized held-custody artifact map from the canonical
   held-adjustment ledger."
  [adjustments]
  (into {}
        (map (fn [adjustment]
               (let [artifact (build-held-custody-artifact adjustment)]
                 [(:held-adjustment/id artifact) artifact])))
        adjustments))

(defn replay-held-adjustments
  "Replay a held-adjustment ledger back into a token=>amount map.
   Used for forensic reconstruction when a world declares that its held
   adjustments are complete."
  ([adjustments] (replay-held-adjustments {} adjustments))
  ([initial-held adjustments]
   (:total-held (replay-held-adjustment-state initial-held adjustments))))

(defn held-history-zero-origin?
  "True when the held-adjustment ledger is zero-origin for every token: the
   first adjustment for each token records :held/before 0.

   This is the opening contract for a 'complete' held history. Full
   reconstruction from a zero opening (replay-held-adjustment-state from {})
   is deterministic only under zero-origin. A ledger whose first adjustment for
   some token has a non-zero :held/before is not reconstructable without a
   committed opening state — treat it as a subset/imported history, or one whose
   opening was never posted as an explicit adjustment."
  [adjustments]
  (let [first-per-token (reduce (fn [m adjustment]
                                  (cond-> m
                                    (not (contains? m (:token adjustment)))
                                    (assoc (:token adjustment) adjustment)))
                                {}
                                adjustments)]
    (every? (fn [[_ adjustment]]
              (zero? (long (:held/before adjustment 0))))
            first-per-token)))

(defn final-held-summary
  "Derived reporting summary of the held-adjustment ledger.

   Takes the three data components directly (not a protocol world map).

   Returns:

     {:by-token
      {:USDC {:opening 0, :in 1000, :out 1000, :final 0}}
      :by-workflow
      {42 {:token :USDC
           :tokens [:USDC :ETH]
           :principal-final 0
           :yield-custody-final 0
           :final-held 0}}
      :ledger-adjustment-count 2
      :reconstruction-valid? true}

   Each :by-workflow row reports the lexically-first token as :token and
   the full set of distinct tokens for the workflow as :tokens.

   Reconstruction assumes the zero-origin contract (see
   held-history-zero-origin?). A non-zero-origin ledger cannot be replayed from
   an empty opening, so :reconstruction-valid? is false and
   :reconstruction-issue is :missing-opening-state rather than throwing.

   Suitable for scenario reports, benchmark evidence packages, and review material."
  [adjustments index total-held]
  (let [position-index (get index :by-position {})
        workflow-index (get index :by-workflow {})
        token-flows (reduce (fn [acc adj]
                              (let [t (:token adj)
                                    amt (:amount adj 0)
                                    dir (:held/direction adj)]
                                (case dir
                                  :in  (update-in acc [t :in] (fnil + 0) amt)
                                  :out (update-in acc [t :out] (fnil + 0) amt)
                                  (throw (ex-info "held adjustment has invalid direction in summary"
                                                  {:type :invalid-held-direction
                                                   :held/direction dir
                                                   :adjustment adj})))))
                            {} adjustments)
        token-rows (into {} (map (fn [[t current]]
                                   [t {:opening 0
                                       :in (get-in token-flows [t :in] 0)
                                       :out (get-in token-flows [t :out] 0)
                                       :final current}])
                                 (sort-by key total-held)))
        recon-token-issues (into []
                                 (keep (fn [[t {:keys [opening in out final]}]]
                                         (when-not (= (+ opening in) (+ final out)) t)))
                                 (sort-by key token-rows))
        wf-adjs (group-by :held/workflow-id adjustments)
        wf-rows (into {} (map (fn [[wf-id adjs]]
                                (let [tokens (vec (sort (distinct (map :token adjs))))
                                      primary (first tokens)
                                      principal-pos [:held/position primary :escrow-principal wf-id]
                                      yield-pos    [:held/position primary :yield-custody wf-id]
                                      principal-final (get position-index principal-pos 0)
                                      yield-final    (get position-index yield-pos 0)]
                                  [wf-id {:token primary
                                          :tokens tokens
                                          :principal-final principal-final
                                          :yield-custody-final yield-final
                                          :final-held (get workflow-index wf-id 0)}]))
                              (sort-by key wf-adjs)))
        zero-origin? (held-history-zero-origin? adjustments)
        reconstructed (when zero-origin?
                        (replay-held-adjustments adjustments))
        reconstruction-valid? (and zero-origin?
                                   (empty? recon-token-issues)
                                   (= reconstructed total-held))
        reconstruction-issue (cond
                               (not zero-origin?) :missing-opening-state
                               (seq recon-token-issues) {:token-reconciliation-failed recon-token-issues}
                               :else nil)]
    {:by-token token-rows
     :by-workflow wf-rows
     :ledger-adjustment-count (count adjustments)
     :reconstruction-valid? reconstruction-valid?
     :reconstruction-issue reconstruction-issue}))

(defn ledger-workflow-write-down
  "Canonical negative-yield principal write-down for a workflow, derived from the
   held-adjustment ledger (:yield-negative-excess reason). This is the canonical
   source of truth for the write-down component of the settlement reconciliation
   and for the :finalize/write-down field on settlement evidence."
  [world workflow-id]
  (reduce + 0
          (for [adj (:held-adjustments world [])
                :when (and (= (:held/workflow-id adj) workflow-id)
                           (= :yield-negative-excess (:held/reason adj)))]
            (long (:amount adj 0)))))

(defn artifact-content-hash-valid?
  "True when the artifact's :evidence/hash matches a recomputation over its content
   (excluding :evidence/hash, :evidence/timestamp and chain fields added after
   finalization). Mirrors the disk-level content-hash verification in
   resolver-sim.io.event-evidence/verify-chain-integrity."
  [artifact]
  (let [content (dissoc artifact
                        :evidence/hash :evidence/timestamp
                        :evidence/chain-self-hash :evidence/chain-prev-hash
                        :evidence/chain-hash-scheme :evidence/chain-seq)
        expected (hash/hash-with-intent {:hash/intent :evidence-content} content)]
    (= expected (:evidence/hash artifact))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Held-custody summary file-artifact
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; A single auditor entry point over the whole custody exposure story: counts
;; and amounts by custody dimension, opening/flow/closing by token, attribution
;; posture, completeness, and closed-form failure counts with triage. This is a
;; projection/report — it is not a second verifier; it aggregates the existing
;; ledger, index, artifacts, and closed-form checks.

(def held-custody-summary-schema-version "held-custody-summary.v2")
(def held-custody-summary-verifier-id "held-custody-summary-verifier.v1")

(defn ledger-root
  "Content root over the canonical ledger: intent-tagged hash of the adjustments
   in canonical (held-adjustment-order) sequence.  Any change to any adjustment
   changes the root."
  [adjustments]
  (hash-ref/sha256-ref
   (hash/hash-with-intent {:hash/intent :evidence-record}
                          (vec (sort-by held-adjustment-order adjustments)))))

(defn artifact-sequence-root
  "Order-sensitive hash chain over the artifact hashes in canonical sequence.
   Reordering or substituting any artifact changes the root.  Nil for an empty
   artifact set."
  [artifacts]
  (reduce (fn [acc a]
            (hash-ref/sha256-ref
             (hash/hash-with-intent {:hash/intent :evidence-record}
                                    {:previous-sequence-root acc
                                     :artifact/hash (:artifact/hash a)})))
          nil
          (vec (sort-by held-adjustment-order artifacts))))

(defn- details-for
  "Details of the closed-form check result with the given id, or nil."
  [check-results check-id]
  (some (fn [r] (when (= check-id (:check/id r)) (:details r))) check-results))

(defn- finalize-artifact
  "Attach the content hash and exact preimage to an artifact body."
  [body]
  (let [hash (hash-ref/sha256-ref
              (hash/hash-with-intent {:hash/intent :evidence-record} body))]
    (assoc body
           :artifact/hash hash
           :artifact/preimage (pr-str body))))

(defn- guarded-closed-form-checks
  "Run the full closed-form battery (artifact surface + ledger/artifact
   completeness + replayed reason/attribution), returning {:results [...]}
   without throwing so a summary can count failures rather than fail."
  [adjustments artifacts]
  (try {:results (held-custody-closed-form-checks adjustments artifacts)}
       (catch Exception e
         {:results (get-in (ex-data e) [:check-results] [])})))

(defn- tally-count
  "Count adjustments by a field value."
  [adjustments k]
  (into (sorted-map)
        (reduce (fn [acc a]
                  (let [v (get a k)]
                    (if (some? v) (update acc v (fnil + 0) 1) acc)))
                {}
                adjustments)))

(defn- tally-amount
  "Sum adjustment amounts by a field value."
  [adjustments k]
  (into (sorted-map)
        (reduce (fn [acc a]
                  (let [v (get a k)]
                    (if (some? v) (update acc v (fnil + 0) (long (or (:amount a) 0))) acc)))
                {}
                adjustments)))

(defn- token-flows
  "Per-token opening (first :held/before), in, out, and closing (total-held)."
  [adjustments total-held]
  (let [openings (reduce (fn [acc a]
                           (let [t (:token a)]
                             (if (and t (nil? (get acc t)))
                               (assoc acc t (long (:held/before a 0)))
                               acc)))
                         {}
                         adjustments)
        flows (reduce (fn [acc a]
                        (let [t (:token a)
                              amt (long (or (:amount a) 0))
                              dir (:held/direction a)]
                          (if (= :in dir)
                            (update-in acc [t :in] (fnil + 0) amt)
                            (update-in acc [t :out] (fnil + 0) amt))))
                      {}
                      adjustments)]
    (into (sorted-map)
          (map (fn [[t closing]]
                 (let [f (get flows t {})]
                   [t {:opening (get openings t 0)
                       :in (get f :in 0)
                       :out (get f :out 0)
                       :closing (long (or closing 0))}]))
               total-held))))

(defn- attribution-classification-counts
  "Extract attribution classification counts from the closed-form
   parameter-attribution check result."
  [check-results]
  (let [pa (some #(when (= :held-custody/parameter-attribution (:check/id %)) %)
                 check-results)
        details (:details pa)]
    (or (:valid-classification-counts details)
        {:legacy-v2 0 :unattributed-v3 0 :attributed-v3 0})))

(defn build-held-custody-summary
  "Build the versioned, content-addressed held-custody summary file-artifact.

   opts:
     :adjustments   held-adjustment ledger (vector of adjustment maps)
     :artifacts     derived held-custody artifacts (vector)
     :total-held    observed closing balance per token
     :completeness  completeness declaration — either a boolean or a map that
                    may carry :held-adjustments/complete?

   Commits counts and amounts by custody dimension, opening/flow/closing per
   token, adjustment sequence range and artifact-chain head, attribution
   posture, completeness/reconciliation posture, closed-form failure counts by
   check, and triage vectors for predecessor breaks, invalid artifacts,
   overdraw attempts, and replay mismatches."
  [{:keys [adjustments artifacts total-held completeness]}]
  (let [adjustments (vec (or adjustments []))
        artifacts (vec (or artifacts []))
        total-held (or total-held {})
        ordered (sort-by held-adjustment-order adjustments)
        closed-form (guarded-closed-form-checks adjustments artifacts)
        check-results (:results closed-form)
        check-status (into {} (map (fn [r] [(:check/id r) (:status r)]) check-results))
        check-failure-counts (into {}
                                   (map (fn [r] [(:check/id r) (count (:violations (:details r)))]))
                                   check-results)
        pa-details (details-for check-results :held-custody/parameter-attribution)
        predecessor-details (details-for check-results :held-custody/predecessor-continuity)
        replay-details (details-for check-results :held-custody/sequence-replay)
        bijection-details (details-for check-results :held-custody/ledger-artifact-bijection)
        order-details (details-for check-results :held-custody/ledger-artifact-order)
        reason-policy-details (details-for check-results :held-custody/reason-position-policy)
        attribution-shape-details (details-for check-results :held-custody/attribution-shape)
        required-attribution-details (details-for check-results :held-custody/required-attribution)
        complete? (if (map? completeness)
                    (get completeness :held-adjustments/complete? true)
                    (if (nil? completeness) true completeness))
        replayed-state (try (replay-held-adjustment-state adjustments)
                            (catch Exception _ nil))
        replayed-closing (get-in replayed-state [:total-held] {})
        reconciliation-valid? (and replayed-state
                                   (= replayed-closing total-held))
        invalid-artifacts (into []
                                (keep (fn [a]
                                        (when-not (artifact-content-hash-valid? a)
                                          (:held-adjustment/id a))))
                                artifacts)
        overdraw (into []
                       (keep (fn [a]
                               (when (neg? (long (:held/after a 0)))
                                 (:held-adjustment/id a))))
                       artifacts)
        body {:schema-version held-custody-summary-schema-version
              :artifact/kind :held-custody-summary
              :artifact/verifier held-custody-summary-verifier-id
              :adjustment-count (count adjustments)
              :artifact-count (count artifacts)
              :adjustment-sequence-range (when (seq ordered)
                                           {:first (held-adjustment-order (first ordered))
                                            :last (held-adjustment-order (last ordered))})
              :artifact-chain-head (let [ordered-arts (sort-by held-adjustment-order artifacts)]
                                     (:artifact/hash (last ordered-arts)))
              :ledger-root (ledger-root adjustments)
              :artifact-sequence-root (artifact-sequence-root artifacts)
              :by-token (tally-count adjustments :token)
              :by-direction (tally-count adjustments :held/direction)
              :by-account (tally-count adjustments :held/account)
              :by-owner (tally-count adjustments :owner/address)
              :by-position (tally-count adjustments :held/position-id)
              :by-workflow (tally-count adjustments :held/workflow-id)
              :by-reason (tally-count adjustments :held/reason)
              :amount-by-token (tally-amount adjustments :token)
              :amount-by-direction (tally-amount adjustments :held/direction)
              :amount-by-account (tally-amount adjustments :held/account)
              :amount-by-owner (tally-amount adjustments :owner/address)
              :token-balances (token-flows adjustments total-held)
              :attribution-counts (attribution-classification-counts check-results)
              :attribution-invalid-count (get-in pa-details [:invalid-artifact-count] 0)
              :completeness {:held-adjustments/complete? complete?
                             :replayed-closing replayed-closing
                             :observed-closing total-held
                             :reconciliation-valid? reconciliation-valid?}
              :closed-form-failure-counts check-failure-counts
              :closed-form-status check-status
              :triage {:broken-predecessor-links (mapv :held-adjustment/id (:violations predecessor-details))
                       :invalid-artifacts invalid-artifacts
                       :overdraw-attempts overdraw
                       :replay-mismatches (mapv :held-adjustment/id (:violations replay-details))
                       :ledger-artifact-bijection-violations (:violations bijection-details)
                       :ledger-artifact-order-violations (:violations order-details)
                       :reason-policy-violations (:violations reason-policy-details)
                       :attribution-shape-violations (:violations attribution-shape-details)
                       :required-attribution-violations (:violations required-attribution-details)}}]
    (finalize-artifact body)))

(defn valid-held-custody-summary?
  "Re-verify a held-custody-summary file-artifact."
  [report]
  (and (map? report)
       (= held-custody-summary-schema-version (:schema-version report))
       (= :held-custody-summary (:artifact/kind report))
       (= held-custody-summary-verifier-id (:artifact/verifier report))
       (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (dissoc report :artifact/hash :artifact/preimage)]
         (= (:artifact/hash report)
            (hash-ref/sha256-ref
             (hash/hash-with-intent {:hash/intent :evidence-record} body))))))

(defn verify-settlement-evidence-fidelity
  "For every terminal settlement evidence artifact (:escrow-released /
   :escrow-refunded), require :finalize/write-down to equal the ledger-derived
   workflow write-down (:yield-negative-excess) computed from the post-settlement
   `world`. Returns {:holds? bool :violations [...]}.

   This guarantees the committed evidence does not merely contain the field — it
   reports the correct value against the canonical held ledger."
  [world artifacts]
  (let [violations
        (for [a artifacts
              :let [etype (:evidence/type a)
                    inputs (:inputs a)
                    wf (or (:finalize/workflow-id inputs)
                           (:finalize/workflow_id inputs))]
              :when (and (contains? #{"escrow-released" "escrow-refunded"} etype)
                         (some? wf))
              :let [reported (long (:finalize/write-down inputs 0))
                    ledger (ledger-workflow-write-down world wf)]
              :when (not= reported ledger)]
          {:evidence/hash (:evidence/hash a)
           :evidence/type etype
           :workflow-id wf
           :reported-write-down reported
           :ledger-write-down ledger})]
    {:holds? (empty? violations) :violations (vec violations)}))
