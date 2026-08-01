(ns resolver-sim.assurance.custody
  "Protocol-independent closed-form validation for held-custody artifacts.

   Accepts artifact maps, returns check results. No Sew world-state dependency.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - benchmarks/packs/sew/"
  (:require [resolver-sim.hash.canonical :as hash]
            [resolver-sim.assurance.parameter-attribution :as pa]
            [resolver-sim.accounting.held-ledger-index :as held-index]))

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

(defn held-custody-closed-form-checks
  "Deterministic closed-form checks for derived held custody artifacts.
   These checks do not replace the canonical held-adjustment ledger; they
   verify that the first-class artifact surface is internally consistent and
   replay-consistent enough for researcher-facing validation.

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
                                        (#(str "sha256:"
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
                      expected-hash (str "sha256:"
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
    (let [results [{:check/id :held-custody/hash-integrity
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
                              :replayed-final-state replay-state}}]
          failed (filterv #(= :fail (:status %)) results)]
      (when (seq failed)
        (throw (ex-info "Held custody closed-form checks failed"
                        {:type :closed-form-failure
                         :check-results results
                         :failed-checks failed})))
      results)))

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
           :artifact/hash (str "sha256:"
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
           :principal-final 0
           :yield-custody-final 0
           :final-held 0}}
      :ledger-adjustment-count 2
      :reconstruction-valid? true}

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
                                (if (= :in dir)
                                  (update-in acc [t :in] (fnil + 0) amt)
                                  (update-in acc [t :out] (fnil + 0) amt))))
                            {} adjustments)
        token-rows (into {} (map (fn [[t current]]
                                   [t {:opening 0
                                       :in (get-in token-flows [t :in] 0)
                                       :out (get-in token-flows [t :out] 0)
                                       :final current}])
                                 (sort-by key total-held)))
        wf-adjs (group-by :held/workflow-id adjustments)
        wf-rows (into {} (map (fn [[wf-id adjs]]
                                (let [token (some :token adjs)
                                      principal-pos [:held/position token :escrow-principal wf-id]
                                      yield-pos    [:held/position token :yield-custody wf-id]
                                      principal-final (get position-index principal-pos 0)
                                      yield-final    (get position-index yield-pos 0)]
                                  [wf-id {:token token
                                          :principal-final principal-final
                                          :yield-custody-final yield-final
                                          :final-held (get workflow-index wf-id 0)}]))
                              (sort-by key wf-adjs)))
        zero-origin? (held-history-zero-origin? adjustments)
        reconstructed (when zero-origin?
                        (replay-held-adjustments adjustments))
        reconstruction-valid? (and zero-origin?
                                   (= reconstructed total-held))]
    {:by-token token-rows
     :by-workflow wf-rows
     :ledger-adjustment-count (count adjustments)
     :reconstruction-valid? reconstruction-valid?
     :reconstruction-issue (when-not zero-origin? :missing-opening-state)}))

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
