(ns resolver-sim.assurance.custody
  "Protocol-independent closed-form validation for held-custody artifacts.

   Accepts artifact maps, returns check results. No Sew world-state dependency.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - benchmarks/packs/sew/"
  (:require [resolver-sim.hash.canonical :as hash]))

(defn held-adjustment-order
  "Numeric sequence order for canonical held-adjustment IDs; lexical ordering
   would incorrectly place held-adjustment-10 before held-adjustment-2."
  [adjustment]
  (let [id (:held-adjustment/id adjustment)
        match (and (string? id) (re-matches #"held-adjustment-(\d+)" id))]
    [(if match (Long/parseLong (second match)) Long/MAX_VALUE) (str id)]))

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
        artifact-hash-payload
        (fn [artifact]
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
            (:held/account artifact)
            (assoc :held/account (:held/account artifact))

            (:held/position-id artifact)
            (assoc :held/position-id (:held/position-id artifact))

            (:held/workflow-id artifact)
            (assoc :held/workflow-id (:held/workflow-id artifact))

            (:owner/address artifact)
            (assoc :owner/address (:owner/address artifact))

            (:held/previous-artifact-hash artifact)
            (assoc :held/previous-artifact-hash (:held/previous-artifact-hash artifact))

            (:authorization/provenance artifact)
            (assoc :authorization/provenance (:authorization/provenance artifact))))
        hash-violations
        (->> ordered
             (keep (fn [artifact]
                     (let [expected (-> artifact
                                        artifact-hash-payload
                                        (#(str "sha256:"
                                               (hash/hash-with-intent
                                                {:hash/intent :evidence-record}
                                                %))))]
                       (when (not= expected (:artifact/hash artifact))
                         {:held-adjustment/id (:held-adjustment/id artifact)
                          :expected expected
                          :actual (:artifact/hash artifact)}))))
             vec)
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

(defn replay-held-adjustment-state
  "Replay a held-adjustment ledger into replay-verified materialized custody
   views. The ledger is canonical; returned indexes and balances are derived.

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
             adjustments))))

(def ^:private held-custody-artifact-version "held-custody-adjustment.artifact.v2")

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
        reconstructed (replay-held-adjustments adjustments)
        reconstruction-valid? (= reconstructed total-held)]
    {:by-token token-rows
     :by-workflow wf-rows
     :ledger-adjustment-count (count adjustments)
     :reconstruction-valid? reconstruction-valid?}))
