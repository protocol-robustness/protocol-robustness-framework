(ns resolver-sim.protocols.sew.accounting
  "Pure Clojure port of EscrowVault balance and fee accounting, plus
   BondCollector fee deduction logic.

   Covers:
     - total-held-per-token tracking (add on create, sub on release/refund)
     - total-fees-per-token (monotonically increasing; withdraw-fees resets)
     - claimable-balances (pull-settlement entitlements; cleared on withdrawEscrow)
     - withdraw-fees
     - BondCollector appeal bond accounting

   All arithmetic uses integer division (uint256 truncation semantics)."
  (:require             [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.related-claims :as rc]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.assurance.custody :as custody-core]
            [resolver-sim.accounting.held-ledger-index :as held-index]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.economics.slash-distribution :as sd]))

(declare sub-held record-fee record-claimable)

;; ---------------------------------------------------------------------------
;; total-held tracking
;; ---------------------------------------------------------------------------

(def ^:private exceptional-held-reasons
  #{:governance-authorised-correction
    :replay-fixture-setup
    :replay-migration
    :force-authorised-release
    :force-authorised-refund
    :partial-fill-principal-loss})

(def ^:private address-scoped-held-reasons
  "Reasons for which :owner/address MUST be explicitly provided.
   Fallback-derived ownership (actor, from, resolver, recipient) is not
   permitted — forensic ownership must be unambiguous."
  #{:escrow-principal-deposited
    :escrow-settlement-released
    :escrow-settlement-refunded
    :force-authorised-release
    :force-authorised-refund
    :partial-fill-principal-loss
    :yield-distributed
    :resolver-slash-custody-debited
    :governance-authorised-correction})

(def ^:private held-position-policy
  {:escrow-principal-deposited {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :escrow-settlement-released {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :escrow-settlement-refunded {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :force-authorised-release {:held/account :escrow-principal
                              :scope-keys [:held/workflow-id]}
   :force-authorised-refund {:held/account :escrow-principal
                             :scope-keys [:held/workflow-id]}
   :deferred-yield-reclassified-out {:held/account :escrow-principal
                                     :scope-keys [:held/workflow-id]}
   :deferred-yield-reserved {:held/account :yield-custody
                             :scope-keys [:held/workflow-id]}
   :appeal-bond-posted {:held/account :appeal-bond
                        :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-returned {:held/account :appeal-bond
                          :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-slashed {:held/account :appeal-bond
                         :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-forfeited {:held/account :appeal-bond
                           :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :yield-accrued {:held/account :yield-custody
                   :scope-keys [:held/workflow-id]}
   :yield-distributed {:held/account :yield-custody
                       :scope-keys [:held/workflow-id]}
   :deferred-yield-claimed {:held/account :yield-custody
                            :scope-keys [:held/workflow-id]}
    :resolver-yield-accrued {:held/account :resolver-yield
                             :scope-keys [:held/owner-id :held/resolver]}
    :resolver-yield-loss {:held/account :resolver-yield
                          :scope-keys [:held/owner-id :held/resolver]}
    :resolver-yield-withdrawn {:held/account :resolver-yield
                               :scope-keys [:held/owner-id :held/resolver]}
   :resolver-slash-custody-debited {:held/account :resolver-slash-custody
                                     :scope-keys [:held/resolver :held/workflow-id]}
   :partial-fill-principal-loss {:held/account :escrow-principal
                                 :scope-keys [:held/workflow-id]}
   :yield-negative-excess {:held/account :escrow-principal
                           :scope-keys [:held/workflow-id]}})

(defn- validate-held-inputs!
  [token amount]
  (when (nil? token)
    (throw (ex-info "held adjustment requires token"
                    {:type :invalid-held-adjustment
                     :reason :missing-token})))
  (when (or (nil? amount) (neg? amount))
    (throw (ex-info "held adjustment requires non-negative amount"
                    {:type :invalid-held-adjustment
                     :reason :invalid-amount
                     :amount amount})))
  (when (and (some? amount) (not (integer? amount)))
    (throw (ex-info "held adjustment requires an integer amount"
                    {:type :invalid-held-adjustment
                     :reason :invalid-amount
                     :amount amount
                     :hint "fractional amounts would fail canonical hashing with an opaque error"}))))

(def ^:const force-authorisation-scope-domain
  "force-authorisation-scope")

(defn- force-authorisation-scope-hash
  [scope-map]
  (hash/domain-hash force-authorisation-scope-domain scope-map))

(defn- scope-hash-mismatch?
  "True when the scope hash in auth-provenance does not match the scope-map
   derived from the actual held adjustment fields.  Prevents scope drift between
   authorization and execution."
  [auth-provenance scope-map]
  (let [expected (:authorization/scope-hash auth-provenance)
        actual (force-authorisation-scope-hash scope-map)]
    (not= expected actual)))

(defn- ensure-force-authorisation-usable!
  "Guard a forced custody adjustment with the persisted authorization record.

   The caller-supplied provenance is evidence only; it is never authority on
   its own.  For a single claim, the active record must commit to precisely the
   scope derived from this adjustment.  Related-claims retain their explicit
   member-scope consumption model.

   Does NOT short-circuit for idempotent replay — that is handled at the outer
   command layer."
  [world auth-provenance scope-map]
  (let [auth-id (:authorization/id auth-provenance)
        scope-kind (:authorization/scope-kind auth-provenance :single-claim)
        record (get-in world [:force-authorisations auth-id])
        now (time-ctx/block-ts world)]
    (when-not record
      (throw (ex-info "force-authorisation record not found"
                      {:type :authorization/not-found
                       :authorization/id auth-id})))
    (when-not (= :active (:authorization/status record))
      (throw (ex-info (let [s (:authorization/status record)]
                        (cond
                          (and (= :consumed s) (= :related-claims scope-kind))
                          "force-authorisation related-claims members already consumed"
                          (= :consumed s)
                          "force-authorisation record already consumed"
                          :else
                          "force-authorisation record is not active"))
                      {:type (if (= :consumed (:authorization/status record))
                               :authorization/already-consumed
                               :authorization/not-active)
                       :authorization/id auth-id
                       :status (:authorization/status record)})))
    (when (:consumed? record)
      (throw (ex-info "force-authorisation record already consumed"
                      {:type :authorization/already-consumed
                       :authorization/id auth-id})))
    (when (< now (:starts-at record))
      (throw (ex-info "force-authorisation record not yet active"
                      {:type :authorization/not-yet-started
                       :authorization/id auth-id
                       :starts-at (:starts-at record)
                       :now now})))
    (when (and (:expires-at record) (>= now (:expires-at record)))
      (throw (ex-info "force-authorisation record expired"
                      {:type :authorization/expired
                       :authorization/id auth-id
                       :expires-at (:expires-at record)
                       :now now})))
    (when (= :related-claims scope-kind)
      (when-not (= :related-claims (:authorization/scope-kind record))
        (throw (ex-info "force-authorisation record is not a related-claims grant"
                        {:type :authorization/related-claims-scope-kind-mismatch
                         :authorization/id auth-id})))
      (when-not (and (= (:relationship/id record) (:relationship/id auth-provenance))
                     (= (:relationship/hash record) (:relationship/hash auth-provenance))
                     (= (set (:member-scope-hashes record))
                        (set (:member-scope-hashes auth-provenance))))
        (throw (ex-info "related-claims authorization provenance differs from grant"
                        {:type :authorization/related-claims-grant-mismatch
                         :authorization/id auth-id}))))
    (when (= :single-claim scope-kind)
      (let [record-scope (:authorization/scope record)
            record-hash (:authorization/scope-hash record)
            derived-hash (force-authorisation-scope-hash scope-map)]
        (when-not (and record-scope record-hash)
          (throw (ex-info "force-authorisation record lacks an immutable scope"
                          {:type :authorization/missing-scope
                           :authorization/id auth-id})))
        (when-not (= record-scope scope-map)
          (throw (ex-info "force-authorisation scope differs from grant"
                          {:type :authorization/grant-scope-mismatch
                           :authorization/id auth-id
                           :granted-scope record-scope
                           :attempt scope-map})))
        (when-not (= record-hash derived-hash)
          (throw (ex-info "force-authorisation grant scope hash mismatch"
                          {:type :authorization/grant-scope-hash-mismatch
                           :authorization/id auth-id
                           :granted-scope-hash record-hash
                           :derived-scope-hash derived-hash})))
        (when-not (= record-hash (:authorization/scope-hash auth-provenance))
          (throw (ex-info "force-authorisation provenance does not match grant"
                          {:type :authorization/provenance-scope-mismatch
                           :authorization/id auth-id
                           :granted-scope-hash record-hash
                           :provenance-scope-hash (:authorization/scope-hash auth-provenance)})))))
    (if (= :related-claims scope-kind)
      ;; Related-claims: per-member consumption tracking
      (let [rel-id (:relationship/id auth-provenance)
            rel (when rel-id (rc/get-related-claims world rel-id))]
        (when-not (and rel (rc/related-claims-active? world rel-id))
          (throw (ex-info "related-claims relationship not active"
                          {:type :authorization/relationship-inactive
                           :authorization/id auth-id
                           :relationship/id rel-id})))
        (let [member-identity {:claim/kind :sew/workflow
                               :workflow/id (:held/workflow-id scope-map)}]
          (when-not (and (:held/workflow-id scope-map)
                         (rc/relationship-member? rel member-identity))
            (throw (ex-info "force-authorisation member not in referenced related-claims relationship"
                            {:type :authorization/member-not-in-relationship
                             :authorization/id auth-id
                             :relationship/id rel-id
                             :member member-identity}))))
        (let [member-hash (force-authorisation-scope-hash scope-map)
              member-hashes (:member-scope-hashes auth-provenance [])]
          (when-not (contains? (set member-hashes) member-hash)
            (throw (ex-info "force-authorisation member scope not in authorized set"
                            {:type :authorization/member-scope-not-authorized
                             :authorization/id auth-id
                             :member-scope-hash member-hash
                             :authorized member-hashes})))
          (let [consumed (get-in world [:force-authorisations/consumed auth-id] {})
                consumed-members (:consumed-members consumed #{})]
            (when (contains? consumed-members member-hash)
              (throw (ex-info "force-authorisation member scope already consumed"
                              {:type :authorization/member-already-consumed
                               :authorization/id auth-id
                               :member-scope-hash member-hash}))))))
      ;; Single-claim (default): check entire auth not consumed, scope hash matches
      (do
        (when-let [consumed (get-in world [:force-authorisations/consumed auth-id])]
          (throw (ex-info "force-authorisation already consumed"
                          {:type :authorization/already-consumed
                           :authorization/id auth-id
                           :consumed consumed
                           :attempt scope-map})))
        (when (scope-hash-mismatch? auth-provenance scope-map)
          (throw (ex-info "force-authorisation scope mismatch"
                          {:type :authorization/scope-mismatch
                           :authorization/id auth-id
                           :authorization/scope-hash (:authorization/scope-hash auth-provenance)
                           :derived-scope-hash (force-authorisation-scope-hash scope-map)
                           :scope-map scope-map})))))))

(defn- parameter-attribution-error!
  [parameter-context parameter-address]
  (when-let [reason (held-adjustment/parameter-attribution-error
                     {:parameter/context parameter-context
                      :parameter/address parameter-address})]
    (throw (ex-info "invalid held adjustment parameter attribution"
                    {:type :invalid-held-adjustment
                     :reason reason
                     :parameter/context parameter-context
                     :parameter/address parameter-address}))))

(defn- parameter-attribution-scope
  [parameter-context parameter-address]
  (held-adjustment/project-held-adjustment-scope
   {:parameter/context parameter-context
    :parameter/address parameter-address}))

(defn- member-scope-hash-from-adjustment
  "Recompute the force-authorisation member scope from a held adjustment.
   Pre-position grants retain their legacy preimage; new grants bind the
   derived custody position."
  [world auth-provenance adjustment]
  (let [record (get-in world [:force-authorisations (:authorization/id auth-provenance)])
        fields (assoc adjustment
                      :authorization/id (:authorization/id auth-provenance)
                      :authorization/type :force-authorisation)
        positioned-scope (held-adjustment/project-held-adjustment-scope fields)
        position-bound? (contains? (set (:member-scope-hashes record))
                                   (force-authorisation-scope-hash positioned-scope))]
    (force-authorisation-scope-hash
     (held-adjustment/project-held-adjustment-scope
      (cond-> fields
        (not position-bound?) (dissoc :held/position-id))))))

(defn- mark-force-authorisation-consumed
  [world auth-provenance adjustment]
  (let [auth-id (:authorization/id auth-provenance)
        scope-kind (:authorization/scope-kind auth-provenance :single-claim)
        base {:consumed? true
              :authorization/id auth-id
              :authorization/type (:authorization/type auth-provenance)
              :authorization/scope-hash (:authorization/scope-hash auth-provenance)
              :held-adjustment/id (:held-adjustment/id adjustment)
              :token (:token adjustment)
              :amount (:amount adjustment)
              :owner/address (:owner/address adjustment)
              :workflow-id (:held/workflow-id adjustment)
              :held/reason (:held/reason adjustment)
              :consumed/action (:held/action adjustment)}]
    (if (= :related-claims scope-kind)
      ;; Per-member consumption: add member scope hash to consumed set. The
      ;; grant remains active while members remain, then becomes terminally consumed.
      ;; `:consumed-relationship-member-hashes` records each consumed member's
      ;; related-claims-member identity hash so the scope-closed invariant can
      ;; prove relationship membership retrospectively, independent of the
      ;; force-authorisation-scope hash.
      (let [member-hash (member-scope-hash-from-adjustment world auth-provenance adjustment)
            rel-member-hash (rc/related-claims-member-hash
                             {:claim/kind :sew/workflow
                              :workflow/id (:held/workflow-id adjustment)})
            existing (or (get-in world [:force-authorisations/consumed auth-id])
                         {:consumed? false
                          :authorization/id auth-id
                          :authorization/type :force-authorisation
                          :authorization/scope-hash (:authorization/scope-hash auth-provenance)
                          :authorization/scope-kind :related-claims
                          :relationship/id (:relationship/id auth-provenance)
                          :relationship/hash (:relationship/hash auth-provenance)
                          :member-scope-hashes (:member-scope-hashes auth-provenance [])
                          :consumed-members #{} :consumed-relationship-member-hashes #{}
                          :member-count 0})
            updated (-> existing
                        (assoc :consumed? true
                               :last-consumed-at (:held-adjustment/id adjustment))
                        (update :consumed-members conj member-hash)
                        (update :consumed-relationship-member-hashes conj rel-member-hash)
                        (update :member-count inc)
                        (assoc :last-consumed-adjustment-id (:held-adjustment/id adjustment)
                               :last-consumed-workflow-id (:held/workflow-id adjustment)))
            committed-members (set (get-in world [:force-authorisations auth-id :member-scope-hashes] []))
            all-members-consumed? (= committed-members (:consumed-members updated))
            consumption-record {:held-adjustment/id (:held-adjustment/id adjustment)
                                :authorization/id auth-id
                                :relationship/id (:relationship/id auth-provenance)
                                :relationship/hash (:relationship/hash auth-provenance)
                                :member-scope-hash member-hash
                                :related-member-hash rel-member-hash
                                :held/workflow-id (:held/workflow-id adjustment)
                                :parameter/context (:parameter/context adjustment)
                                :parameter/address (:parameter/address adjustment)}]
        (cond-> (-> world
                    (assoc-in [:force-authorisations/consumption-records auth-id member-hash]
                              consumption-record)
                    (assoc-in [:force-authorisations/consumed auth-id] updated))
          all-members-consumed?
          (assoc-in [:force-authorisations auth-id]
                    (assoc (get-in world [:force-authorisations auth-id])
                           :authorization/status :consumed
                           :consumed? true))))
      ;; Single-claim: consume entire auth (current behavior)
      (let [consumed-entry (merge base
                                  (when-let [d (:held/direction adjustment)]
                                    {:held/direction d}))]
        (-> world
            (assoc-in [:force-authorisations/consumed auth-id] consumed-entry)
            (assoc-in [:force-authorisations auth-id :consumed?] true)
            (assoc-in [:force-authorisations auth-id :authorization/status] :consumed))))))

(defn- next-held-adjustment-id
  [world]
  (str "held-adjustment-" (count (:held-adjustments world []))))

(defn- preferred-held-value
  [m preferred-key fallback-key]
  (or (get m preferred-key)
      (get m fallback-key)))

(defn- held-position-components
  [token reason extra]
  (let [scope (merge {:held/workflow-id (preferred-held-value extra :held/workflow-id :workflow-id)
                      :held/bond-id (preferred-held-value extra :held/bond-id :bond-id)
                      :held/slash-id (preferred-held-value extra :held/slash-id :slash-id)
                      :held/actor (preferred-held-value extra :held/actor :actor)
                      :held/resolver (preferred-held-value extra :held/resolver :resolver)
                      :held/owner-id (preferred-held-value extra :held/owner-id :owner-id)
                      :held/from (preferred-held-value extra :held/from :from)
                      :held/to (preferred-held-value extra :held/to :to)
                      :held/recipient (preferred-held-value extra :held/recipient :recipient)
                      :owner/address (preferred-held-value extra :owner/address :address)}
                     extra)
        account-override (:held/account scope)
        position-override (:held/position-id scope)
        owner-address-override (:owner/address scope)
        policy (get held-position-policy reason)
        account (or account-override (:held/account policy))
        scope-values (cond
                       position-override nil
                       (seq (:scope-keys policy))
                       (->> (:scope-keys policy)
                            (keep #(get scope %))
                            vec)
                       :else nil)
        position-id (or position-override
                        (when (and account (seq scope-values))
                          (into [:held/position token account] scope-values)))
        owner-address (or owner-address-override
                          (:held/actor scope)
                          (:held/from scope)
                          (:held/resolver scope)
                          (:held/recipient scope))]
    (cond-> {:held/account account
             :held/position-id position-id}
      owner-address
      (assoc :owner/address owner-address))))

(defn- update-ledger-index
  ;; Live Sew world-state mutation corresponding to the pure custody
  ;; reconstruction in resolver-sim.assurance.custody.
  ;;
  ;; Authoritative source: the existing index, NOT :total-held. The seed map
  ;; below is merged UNDER the current index (merge keeps the later index keys),
  ;; so on any normal world the existing index wins and the seed is inert; it
  ;; only supplies defaults when the index is missing a dimension (e.g. the
  ;; opening empty-index case). The adjustment step is then applied on top, and
  ;; :total-held / :held/positions are re-derived FROM the index. Divergence
  ;; between the top-level aliases and the index is therefore impossible after a
  ;; live mutation.
  ;;
  ;; Opening semantics: with an empty index the first :in per token is applied
  ;; from 0. Under the zero-origin contract (first :in per token from 0) this is
  ;; identical to replay-held-adjustment-state from {}; the live path does not
  ;; itself enforce zero-origin because the running level already lives in the
  ;; index/top-level aliases.
  ;;
  ;; Kept protocol-local because it currently operates on the complete Sew world
  ;; and because custody projection authority and live-transition validation have
  ;; not yet been standardised as a protocol-independent contract.
  ;;
  ;; Post-review: extract the shared single-adjustment step (apply-held-adjustment)
  ;; so that replay reuses the same transition as the live path. See
  ;; replay-held-adjustment-state in assurance/custody.clj for the equivalent
  ;; pure function. The existing test `held-custody-closed-form-checks-pass-on-valid-artifacts`
  ;; in accounting_test.clj characterises the current equivalence guarantee.
  [world adjustment]
  (let [{direction :held/direction
         token :token
         amount :amount
         position-id :held/position-id
         held-account :held/account
         owner-address :owner/address} adjustment
        workflow-id (:held/workflow-id adjustment)
        step-fn (case direction
                  :in +
                  :out -
                  ;; Fail-closed on an unknown direction, matching the pure
                  ;; replay path (replay-held-adjustment-state) which rejects
                  ;; an invalid direction rather than silently subtracting.
                  (throw (ex-info "invalid held direction in update-ledger-index"
                                  {:type :invalid-held-adjustment
                                   :direction direction})))
        world* (-> world
                   (update :held-ledger/index
                           (fn [idx]
                             (merge {:by-token (:total-held world {})
                                     :by-position (:held/positions world {})
                                     :by-account {}
                                     :by-owner {}
                                     :by-workflow {}}
                                    idx))))]
    (let [world' (cond-> (update-in world* [:held-ledger/index :by-token token] (fnil step-fn 0) amount)
                   position-id
                   (update-in [:held-ledger/index :by-position position-id] (fnil step-fn 0) amount)

                   held-account
                   (update-in [:held-ledger/index :by-account held-account] (fnil step-fn 0) amount)

                   owner-address
                   (update-in [:held-ledger/index :by-owner owner-address] (fnil step-fn 0) amount)

                   workflow-id
                   (update-in [:held-ledger/index :by-workflow workflow-id] (fnil step-fn 0) amount))
          result (-> world'
                     (assoc :total-held (get-in world' [:held-ledger/index :by-token] {}))
                     (assoc :held/positions (get-in world' [:held-ledger/index :by-position] {})))]
      (held-index/validate-held-custody-state
       (select-keys result [:held-ledger/index :total-held :held/positions]))
      result)))

(defn- build-held-adjustment
  [world token amount direction action reason authorization-provenance extra]
  (let [before (get-in world [:total-held token] 0)
        after  (case direction
                 :in  (+ before amount)
                 :out (- before amount))
        position-fields (held-position-components token reason extra)]
    (held-adjustment/build-held-adjustment
     (merge {:held-adjustment/id (next-held-adjustment-id world)
            :held/direction direction
            :token token
            :amount amount
            :held/before before
            :held/after after
            :held/reason (or reason :held/unspecified)
            :held/action action}
           (when-let [previous-id (some-> world :held-adjustments last :held-adjustment/id)]
             (when-let [previous-hash (get-in world [:held-artifacts previous-id :artifact/hash])]
               {:held/previous-artifact-hash previous-hash}))
           position-fields
           (when authorization-provenance
             {:authorization/provenance authorization-provenance})
           extra))))

(defn- append-held-adjustment
  [world adjustment]
  (update world :held-adjustments (fnil conj []) adjustment))

(defn- append-held-custody-artifact
  [world artifact]
  (assoc-in world [:held-artifacts (:held-adjustment/id artifact)] artifact))

(defn- validate-held-position!
  "Enforce reason-derived custody partitioning before a ledger mutation."
  [world token amount direction reason extra]
  (let [extra (or extra {})
        policy (get held-position-policy reason)
        derived-components (held-position-components token reason (dissoc extra :held/account :held/position-id))
        components (held-position-components token reason extra)
        expected-account (:held/account policy)
        position-id (:held/position-id components)
        derived-position-id (:held/position-id derived-components)
        owner-address (:owner/address components)]
    (when (and expected-account
               (contains? extra :held/account)
               (not= expected-account (:held/account extra)))
      (throw (ex-info "held account conflicts with reason policy"
                      {:type :invalid-held-adjustment
                       :reason reason
                       :expected-account expected-account
                       :actual-account (:held/account extra)})))
    (when (and expected-account
               (contains? extra :held/position-id)
               (not= derived-position-id (:held/position-id extra)))
      (throw (ex-info "held position conflicts with reason policy"
                      {:type :invalid-held-adjustment
                       :reason reason
                       :expected-position-id derived-position-id
                       :actual-position-id (:held/position-id extra)})))
    (when (and expected-account (nil? position-id))
      (throw (ex-info "held adjustment requires complete position scope"
                      {:type :invalid-held-adjustment
                       :reason reason
                       :scope-keys (:scope-keys policy)
                       :extra extra})))
    (when (and (contains? address-scoped-held-reasons reason)
               (nil? owner-address))
      (throw (ex-info "held adjustment requires explicit owner address"
                      {:type :invalid-held-adjustment
                       :reason reason
                       :extra extra})))
    (when (and (= direction :out) position-id)
      (let [position-held (or (get-in world [:held-ledger/index :by-position position-id])
                              (get-in world [:held/positions position-id])
                              0)]
        (when (< position-held amount)
          (throw (ex-info "sub-held position underflow"
                          {:type :sub-held-position-underflow
                           :token token
                           :position-id position-id
                           :held position-held
                           :amount amount})))))))

(defn- adjust-held
  [world token amount direction {:keys [action reason authorization-provenance extra
                                         parameter/context parameter/address]
                                 :or {action "adjust-held"}}]
  (validate-held-inputs! token amount)
  (when-let [reserved (seq (held-adjustment/reserved-adjustment-keys-present extra))]
    (throw (ex-info "held adjustment :extra contains reserved provenance keys"
                    {:type :invalid-held-adjustment
                     :reason :reserved-parameter-attribution-in-extra
                     :keys reserved})))
  (parameter-attribution-error! context address)
  (when (and (contains? exceptional-held-reasons reason)
             (nil? authorization-provenance))
    (throw (ex-info "exceptional held adjustment requires authorization provenance"
                    {:type :invalid-held-adjustment
                     :reason :missing-authorization-provenance
                     :held/reason reason})))
  (let [is-force-auth? (= :force-authorisation (:authorization/type authorization-provenance))]
    (when is-force-auth?
      (let [components (held-position-components token reason (or extra {}))
            record (get-in world [:force-authorisations (:authorization/id authorization-provenance)])
            scope-fields (merge {:authorization/id (:authorization/id authorization-provenance)
                                 :authorization/type :force-authorisation
                                 :held/direction direction
                                 :token token
                                 :amount amount
                                 :held/account (:held/account components)
                                 :held/position-id (:held/position-id components)
                                 :owner/address (:owner/address components)
                                 :held/reason reason}
                                (select-keys (or extra {}) [:held/workflow-id])
                                (parameter-attribution-scope context address))
            position-bound? (if (= :related-claims (:authorization/scope-kind authorization-provenance))
                              (contains? (set (:member-scope-hashes record))
                                         (force-authorisation-scope-hash
                                          (held-adjustment/project-held-adjustment-scope scope-fields)))
                              (contains? (:authorization/scope record) :held/position-id))
            scope-map (held-adjustment/project-held-adjustment-scope
                       (cond-> scope-fields
                         (not position-bound?) (dissoc :held/position-id)))]
        (ensure-force-authorisation-usable! world authorization-provenance scope-map)))
    (let [current (get-in world [:total-held token] 0)]
      (when (and (= direction :out) (< current amount))
        (throw (ex-info "sub-held underflow"
                        {:type   :sub-held-underflow
                         :token  token
                         :held   current
                         :amount amount})))
      (validate-held-position! world token amount direction reason extra)
      (let [adjustment (build-held-adjustment world
                                              token
                                              amount
                                              direction
                                              action
                                              reason
                                              authorization-provenance
                                              (cond-> (or extra {})
                                                context (assoc :parameter/context context)
                                                address (assoc :parameter/address address)))
            artifact (custody-core/build-held-custody-artifact adjustment)
            world' (update-ledger-index world adjustment)
            world'' (-> world'
                        (append-held-adjustment adjustment)
                        (append-held-custody-artifact artifact))]
        (if is-force-auth?
          (mark-force-authorisation-consumed world'' authorization-provenance adjustment)
          world'')))))

(defn add-held
  "Increase protocol-held custody balance for token.

   Use only when assets enter the escrow/bond custody pool.
   Do not use for resolver stake, which is tracked separately in
   :resolver-stakes.

  Optional opts:
   - :action                    logical mutation action string
   - :reason                    economic custody reason keyword
   - :authorization-provenance  structured authorization provenance
   - :parameter/context         compact committed parameter environment reference
   - :parameter/address         semantic parameter locator within that context
   - :extra                     extra machine-readable held-adjustment metadata

   Parameter context and address are optional for compatibility, but must be
   supplied together. They attest attribution only; this primitive does not
   resolve parameter values or evaluate economic policy."
  ([world token amount opts]
   ;; Normalize token to its keyword form (matching create-escrow's
   ;; :held-ledger/index keys) so mixed string/keyword tokens cannot
   ;; fragment the custody index.
   (adjust-held world (if (keyword? token) token (keyword token)) amount :in
                (merge {:action "add-held"} opts))))

(defn sub-held
  "Decrease total-held for token by amount. Called on release/refund.
   Callers must have validated state. Throws a catchable ex-info on underflow
   so process-step's (catch Exception) handler converts it to :dispatch-exception
   rather than propagating an AssertionError past the catch boundary."
  ([world token amount opts]
   (adjust-held world (if (keyword? token) token (keyword token)) amount :out
                (merge {:action "sub-held"} opts))))

;; ---------------------------------------------------------------------------
;; Fee-recipient configuration
;; ---------------------------------------------------------------------------
;; Fee-recipient configuration
;; ---------------------------------------------------------------------------

(defn resolve-fee-recipient
  "Resolve the fee recipient address for a given token.
   Returns per-token override when configured, falling back to :default,
   which itself defaults to the zero-address sentinel."
  [world token]
  (get-in world [:fee-recipients :by-token token]
          (get-in world [:fee-recipients :default]
                  t/zero-address)))

(defn set-fee-recipient
  "Set the fee recipient for a token or the default recipient.
   token-or-default may be a token keyword or :default.
   Returns updated world."
  [world token-or-default recipient]
  (assert (and recipient (not= recipient "")) "recipient must be non-empty")
  (if (= token-or-default :default)
    (assoc-in world [:fee-recipients :default] recipient)
    (assoc-in world [:fee-recipients :by-token token-or-default] recipient)))

;; ---------------------------------------------------------------------------
;; total-fees tracking
;; ---------------------------------------------------------------------------

(defn record-fee
  "Accumulate fee into total-fees. Monotonically increasing.
   Mirrors FeeRecordingLibrary.recordFee in EscrowVault."
  [world token amount]
  (update-in world [:total-fees token] (fnil + 0) amount))

(defn withdraw-fees
   "Withdraw all accumulated fees for token.
    Sets total-fees[token] = 0 and returns {:ok true :world world' :amount amount}.
    Mirrors EscrowVault.withdrawFees.

    recipient — resolved fee recipient address (from policy, not caller-chosen).
    authorized-by — governance actor address that authorized the withdrawal.

    Guard: reentrancy guard must not be set.
    Guard: amount must be > 0.
    Guard: token must not be in a liquidity-crunch."
  [world token recipient authorized-by]
  (let [amount (get-in world [:total-fees token] 0)]
    (cond
      (:reentrancy-guard world)
      (t/fail :reentrancy-guard-violated)

      (zero? amount)
      (t/fail :no-fees-to-withdraw)

      (contains? (:token-liquidity-crunch world #{}) token)
      (t/fail :liquidity-insufficient)

      :else
      (let [world' (-> world
                       (assoc-in [:total-fees token] 0)
                       (update-in [:total-withdrawn token] (fnil + 0) amount)
                       (update-in [:total-fees-withdrawn token] (fnil + 0) amount)
                       (update-in [:fee-payouts token recipient] (fnil + 0) amount))]
        (attr/with-attribution {:subject/type :token
                                :subject/id token
                                :action/type :fees/withdraw
                                :evidence/reason :fees-withdrawn}
          (cap/capture-event-evidence!
           :fees-withdrawn
           {:fee/before {:total-fees amount}}
           {:fee/after {:total-fees 0
                        :total-withdrawn (get-in world' [:total-withdrawn token])
                        :fee-payouts (get-in world' [:fee-payouts token])}}
           {:fee/token token
            :fee/amount amount
            :recipient/address recipient
            :authorized-by authorized-by
            :fee/bucket :protocol}))
        (assoc (t/ok world') :amount amount)))))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-released
  "Track amount released to recipient. Called alongside sub-held on finalize-release."
  [world token amount]
  (update-in world [:total-released token] (fnil + 0) amount))

(defn record-refunded
  "Track amount refunded to sender. Called alongside sub-held on finalize-refund."
  [world token amount]
  (update-in world [:total-refunded token] (fnil + 0) amount))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-claimable-v2
  "Record amount as claimable by addr for workflow-id in a specific domain.
   Mirrors: claimableBalances[workflowId][domain][recipient] += amount
   Legacy dual-writes :settlement/principal and :settlement/yield to :claimable."
  [world workflow-id domain addr amount]
  (-> world
      (update-in [:claimable-v2 workflow-id domain addr] (fnil + 0) amount)
      (cond-> (#{:settlement/principal :settlement/yield} domain)
        (update-in [:claimable workflow-id addr] (fnil + 0) amount))))

(defn clear-claimable-v2-kind
  "Clear all v2 claimables for a workflow + kind, and legacy :claimable for settlement domains.
   Idempotent by construction (dissoc-based), so repeated calls do not create negatives.
   This function never infers claimants and never creates nil claimant keys."
  [world workflow-id kind]
  (-> world
      (update-in [:claimable-v2 workflow-id] dissoc kind)
      (cond-> (#{:settlement/principal :settlement/yield} kind)
        (update :claimable dissoc workflow-id))))

(defn- clear-claimable-v2-for-addr
  "Remove claimable-v2 entries for addr on workflow-id (all domains), and legacy :claimable.
   Dissocs addr from each domain; cleans up empty domain and workflow maps."
  [world wf-id addr]
  (let [legacy-world (update-in world [:claimable wf-id] dissoc addr)]
    (if-let [domains (get-in legacy-world [:claimable-v2 wf-id])]
      (let [cleaned (reduce-kv (fn [m domain addr-map]
                                (let [without-addr (dissoc addr-map addr)]
                                  (if (seq without-addr)
                                    (assoc m domain without-addr)
                                    m)))
                              {}
                              domains)]
        (if (seq cleaned)
          (assoc-in legacy-world [:claimable-v2 wf-id] cleaned)
          (update legacy-world :claimable-v2 dissoc wf-id)))
      legacy-world)))

(defn withdraw-escrow
   "Claim claimable balance for addr on workflow-id.
    Mirrors: BaseEscrow.withdrawEscrow.

    Guard: reentrancy guard must not be set.
    Guard: escrow must be in terminal state (:released/:refunded/:resolved)
           or :pending (for partial release claimables).
    Guard: claimable balance must be > 0.
    Guard: token must not be in a liquidity-crunch."
  [world workflow-id addr]
  (cond
    (:reentrancy-guard world)
    (t/fail :reentrancy-guard-violated)

    (nil? workflow-id)
    (t/fail :invalid-workflow-id)

    :else
    (let [wf-id (t/normalize-workflow-id workflow-id)]
      (cond
        (not (t/valid-workflow-id? world wf-id))
        (t/fail :invalid-workflow-id)

        (let [state (t/escrow-state world wf-id)]
          (not (or (t/terminal-state? world wf-id) (= :pending state))))
        (t/fail :transfer-not-finalized)

         :else
          (let [settlement-amt (get-in world [:claimable-v2 wf-id :settlement/principal addr] 0)
                yield-amt      (get-in world [:claimable-v2 wf-id :settlement/yield addr] 0)
                bond-refund    (get-in world [:claimable-v2 wf-id :bond/refund addr] 0)
                bounty         (get-in world [:claimable-v2 wf-id :liability/challenge-bounty addr] 0)
                amount         (+ settlement-amt yield-amt bond-refund bounty)
               et      (t/get-transfer world wf-id)
               token   (:token et)]
           (cond
             (zero? amount)
             (t/fail :no-claimable-balance)

             (contains? (:token-liquidity-crunch world #{}) token)
             (t/fail :liquidity-insufficient)

             :else
             (let [world' (-> world
                              (clear-claimable-v2-for-addr wf-id addr)
                              (update-in [:total-withdrawn token] (fnil + 0) amount))]
              (attr/with-attribution {:subject/type :escrow
                                      :subject/id wf-id
                                      :action/type :escrow/withdraw
                                      :evidence/reason :escrow-withdrawn}
                (cap/capture-event-evidence!
                 :escrow-withdrawn
                 {:withdraw/before {:claimable amount
                                    :workflow-id wf-id
                                    :recipient addr}}
                  {:withdraw/after {:claimable (get-in world' [:claimable-v2 wf-id :settlement/principal addr] 0)
                                   :total-withdrawn (get-in world' [:total-withdrawn token])}}
                 {:withdraw/workflow-id wf-id
                  :withdraw/recipient addr
                  :withdraw/token token
                  :withdraw/amount amount}))
              (assoc (t/ok world') :amount amount))))))))

;; ---------------------------------------------------------------------------
;; BondCollector appeal bond accounting
;;
;; When an appeal is raised, the appellant posts a bond.
;; Protocol fee is deducted: bond * appeal-bond-protocol-fee-bps / 10000
;; Remainder goes to the incentive module.
;;
;; BondCollector storage (modelled in world):
;;   :bond-balances {workflow-id {addr amount}}   ; posted bonds per escrow/poster
;;   :bond-fees     {token amount}                 ; accumulated protocol fees from bonds
;; ---------------------------------------------------------------------------

(defn post-appeal-bond
  "Record an appeal bond posted by appellant for workflow-id.
   Deducts protocol fee into :bond-fees; records net in :bond-balances.
   Also updates :total-held and :total-bonds-posted (cumulative).

   NOTE: Bond inflow is tracked exclusively via :total-bonds-posted.
   Do NOT also increment :total-principal-deposited — that double-counts
   inflow in the conservation-of-funds and held-delta-accounted invariants.

   SIMULATION GAP: No caller-solvency check.  In a real deployment the
   appellant must have sufficient external balance to post the bond.
   The simulation does not model external wallets, so this enforcement
   is absent.  The invariant :challenge-bond-proportional flags cases
   where the configured bond exceeds the escrow value, which would make
   challenge uneconomic even if the caller had the funds."
  [world workflow-id appellant snap token amount]
  (let [et (t/get-transfer world workflow-id)
        workflow-token (:token et)]
    (when-not et
      (throw (ex-info "appeal bond requires an existing workflow"
                      {:type :invalid-bond-workflow
                       :workflow-id workflow-id})))
    (when-not (= token workflow-token)
      (throw (ex-info "appeal bond token must match workflow escrow token"
                      {:type :bond-token-mismatch
                       :workflow-id workflow-id
                       :expected workflow-token
                       :actual token})))
    (let [fee-bps (or (:appeal-bond-protocol-fee-bps snap) 0)
          {:keys [fee net]} (sew-econ/calculate-appeal-bond-fee amount fee-bps)
        world' (-> world
                   (update-in [:bond-balances workflow-id appellant] (fnil + 0) net)
                   (update-in [:bond-fees token] (fnil + 0) fee)
                   (update-in [:total-bonds-posted token] (fnil + 0) amount)
                   (add-held token
                             net
                             {:action "post-appeal-bond"
                              :reason :appeal-bond-posted
                              :extra {:held/action "post-appeal-bond"
                                      :held/workflow-id workflow-id
                                      :held/bond-id (str workflow-id "-" appellant)
                                      :held/actor appellant}}))]
    (attr/with-attribution {:subject/type :bond
                            :subject/id (str workflow-id "-" appellant)
                            :action/type :bond/post
                            :evidence/reason :bond-posted}
      (cap/capture-event-evidence!
       :bond-posted
        {:bond/before {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                       :total-held (get-in world [:total-held token] 0)}}
        {:bond/after  {:bond-balance (get-in world' [:bond-balances workflow-id appellant] 0)
                       :total-held (get-in world' [:total-held token] 0)}}
       {:bond/workflow-id workflow-id
        :bond/appellant appellant
        :bond/amount amount
        :bond/fee fee
        :bond/net net
        :bond/token token}
       nil
       {:world-before world
        :world-after world'}))
    world')))

(defn distribute-slashed-funds
  "Distribute slashed funds according to the Sew default slash-distribution policy.

   Uses the generic distribution engine (resolver-sim.economics.slash-distribution)
   with the Sew default policy (50/30/20 base, 50/50 bounty funding).

   :insurance-cut-bps and :protocol-retained-bps in world params override
   the default allocation weights.

   If a challenger is provided (Phase L), they receive a bounty from the slashed amount.
   Creates exactly one bounty obligation (claimable) for each successfully applied
   positive-valued award. The application is idempotent: replaying with the same
   distribution hash is a no-op.

   Produces and stores a slash-distribution-application-receipt.v1 binding the
   distribution root, pre/post state roots, abstract allocation effects, concrete
   state deltas, and per-award obligation references.

   Returns updated world."
  ([world amount] (distribute-slashed-funds world amount nil 0 nil))
  ([world amount challenger bounty-bps]
   (distribute-slashed-funds world amount challenger bounty-bps nil))
  ([world amount challenger bounty-bps workflow-id]
   (let [pre-state-root (hash/hash-with-intent {:hash/intent :world-structure} world)
         insurance-bps (get-in world [:params :insurance-cut-bps] 5000)
         protocol-bps  (get-in world [:params :protocol-retained-bps] 3000)
         result (sew-econ/build-sew-slash-distribution
                  amount bounty-bps
                  :challenger challenger
                  :workflow-reference workflow-id
                  :evidence-reference (str "sew:slash:" (or workflow-id "unknown"))
                  :insurance-cut-bps insurance-bps
                  :protocol-retained-bps protocol-bps)
         _ (when (= :invalid (:status result))
             (throw (ex-info "distribute-slashed-funds: invalid distribution"
                             {:violations (:violations result)
                              :amount amount :bounty-bps bounty-bps
                              :challenger challenger :workflow-id workflow-id
                              :insurance-cut-bps insurance-bps
                              :protocol-retained-bps protocol-bps})))
         dist   (:distribution result)
         final  (:distribution/final-allocations dist)
         dist-hash (:distribution/hash dist)
         policy-root (:distribution/policy-root dist)
         param-root (get-in dist [:distribution/parameter-context :source-root] "sew:live-snapshot")
         verification (sd/verify-distribution dist)
         _ (when-not (:valid? verification)
             (throw (ex-info "distribute-slashed-funds: distribution verification failed"
                             {:distribution/hash dist-hash
                              :violations (:violations verification)})))
         bounty-awards (filterv #(= :sew.award/challenge-bounty (:award/id %))
                                (:distribution/awards dist))
         _ (when (and challenger (pos? bounty-bps) (empty? bounty-awards))
             (throw (ex-info "distribute-slashed-funds: missing challenge-bounty award"
                             {:distribution/hash dist-hash
                              :awards (:distribution/awards dist)})))
         _ (when (> (count bounty-awards) 1)
             (throw (ex-info "distribute-slashed-funds: duplicate challenge-bounty awards"
                             {:distribution/hash dist-hash
                              :award-count (count bounty-awards)})))
         bounty-amount (if (seq bounty-awards)
                         (:award/amount (first bounty-awards))
                         0)
         app-key [:slash-distribution-applied (or workflow-id 0) (or challenger 0)]
         app-record (get-in world app-key)
         app-hash (when (map? app-record) (:distribution-hash app-record))]
      (if (and (some? app-hash) (= app-hash dist-hash))
        ;; Idempotent: same hash already applied → no-op.  The app-key record
        ;; (:distribution-hash + :receipt) is already present from the first
        ;; application; writing anything here (e.g. a :skipped receipt) would
        ;; diverge the replayed world from the applied world and break the
        ;; idempotency contract asserted by distribution-characterization-test.
        world
       (let [obligation-ref (when (and challenger (pos? bounty-amount) (some? workflow-id))
                              (str "claimable:" workflow-id ":" challenger))
             world' (-> world
                        (update-in [:bond-distribution :insurance] (fnil + 0)
                                   (get final :sew.allocation/insurance 0))
                        (update-in [:bond-distribution :protocol] (fnil + 0)
                                   (get final :sew.allocation/protocol 0))
                        (update-in [:retained-slash-reserves] (fnil + 0)
                                   (get final :sew.allocation/retained 0))
                        (cond-> (and challenger (pos? bounty-amount) (some? workflow-id))
                          (record-claimable-v2 workflow-id :liability/challenge-bounty
                                               challenger bounty-amount))
                         (assoc-in app-key {:distribution-hash dist-hash}))
             post-state-root (hash/hash-with-intent {:hash/intent :world-structure} world')
             abstract-effects [{:allocation/id :sew.allocation/insurance
                                :amount (get final :sew.allocation/insurance 0)}
                               {:allocation/id :sew.allocation/protocol
                                :amount (get final :sew.allocation/protocol 0)}
                               {:allocation/id :sew.allocation/retained
                                :amount (get final :sew.allocation/retained 0)}
                               (when (and challenger (pos? bounty-amount))
                                 {:allocation/id :sew.allocation/challenge-bounty
                                  :amount bounty-amount})]
             concrete-effects [{:target {:target/type :sew.target/world-ledger
                                         :target/key :bond-distribution/insurance}
                                :delta (get final :sew.allocation/insurance 0)}
                               {:target {:target/type :sew.target/world-ledger
                                         :target/key :bond-distribution/protocol}
                                :delta (get final :sew.allocation/protocol 0)}
                               {:target {:target/type :sew.target/world-ledger
                                         :target/key :retained-slash-reserves}
                                :delta (get final :sew.allocation/retained 0)}]
             obligations (when (and challenger (pos? bounty-amount) obligation-ref)
                           [{:obligation/kind :sew.obligation/challenge-bounty
                             :beneficiary challenger
                             :amount bounty-amount
                             :obligation-reference obligation-ref}])
             receipt (sd/build-application-receipt
                       {:distribution-root dist-hash
                        :policy-root policy-root
                        :parameter-context-root param-root
                        :pre-state-root pre-state-root
                        :post-state-root post-state-root
                        :idempotency-key app-key
                        :status :applied
                        :abstract-effects abstract-effects
                        :concrete-effects concrete-effects
                        :obligations obligations})
             world' (assoc-in world' (conj app-key :receipt) (:receipt/hash receipt))]
         (when (and challenger (pos? bounty-amount))
           (attr/with-attribution
             {:subject/type :challenger
              :subject/id   challenger
              :action/type  :reward-bounty
              :evidence/reason :incentive-payout}
             (cap/capture-event-evidence! :incentive-payout
               {:bounty-claimable 0}
               {:bounty-claimable bounty-amount}
               {:slash-amount amount :bounty-bps bounty-bps
                :distribution-hash dist-hash
                :receipt-hash (:receipt/hash receipt)}
               {:formula "slash-distribution.v1 via build-sew-slash-distribution"}
               {:world-before world
                :world-after world'})))
         world')))))

(defn- reject-bond-evidence!
  "Capture evidence for a rejected bond operation."
  [world token workflow-id appellant amount error-kw action-type evidence-type]
  (attr/with-attribution {:subject/type :bond
                          :subject/id (str workflow-id "-" appellant)
                          :action/type action-type
                          :evidence/reason error-kw}
    (cap/capture-event-evidence!
     evidence-type
     {:bond/before {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                    :bond-status :active}}
     {:bond/after  {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                    :bond-status :unchanged}}
     {:bond/workflow-id workflow-id
      :bond/appellant appellant
      :bond/amount amount
      :bond/error error-kw}
     nil
     {:world-before world
      :world-after world})))

(defn slash-bond
  "Slash the posted bond for a losing appellant.
   Moves balance from :bond-balances to :bond-slashed (for incentive distribution)
   and applies the 50/30/20 split logic.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (do (reject-bond-evidence! world token workflow-id appellant amount :no-bond-to-slash :bond/slash-rejected :bond-slash-rejected)
          (t/fail :no-bond-to-slash))
      (let [world' (-> world
                       (sub-held token
                                 amount
                                 {:action "slash-bond"
                                  :reason :appeal-bond-slashed
                                  :extra {:held/action "slash-bond"
                                          :held/workflow-id workflow-id
                                          :held/bond-id (str workflow-id "-" appellant)
                                          :held/actor appellant}})
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (update-in [:bond-slashed workflow-id] (fnil + 0) amount)
                       (distribute-slashed-funds amount))]
        (attr/with-attribution {:subject/type :bond
                                :subject/id (str workflow-id "-" appellant)
                                :action/type :bond/slash
                                :evidence/reason :bond-slashed}
          (cap/capture-event-evidence!
           :bond-slashed
           {:bond/before {:bond-balance amount
                          :bond-status :active}}
           {:bond/after  {:bond-balance 0
                          :bond-status :slashed}}
           {:bond/workflow-id workflow-id
            :bond/appellant appellant
            :bond/amount amount
            :bond/token token}
           nil
           {:world-before world
            :world-after world'}))
        (assoc (t/ok world') :slashed amount)))))

(defn return-bond
  "Return the posted bond to a winning appellant.
   Clears :bond-balances entry and credits :claimable.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (do (reject-bond-evidence! world token workflow-id appellant amount :no-bond-to-return :bond/return-rejected :bond-return-rejected)
          (t/fail :no-bond-to-return))
      (let [world' (-> world
                       (sub-held token
                                 amount
                                 {:action "return-bond"
                                  :reason :appeal-bond-returned
                                  :extra {:held/action "return-bond"
                                          :held/workflow-id workflow-id
                                          :held/bond-id (str workflow-id "-" appellant)
                                          :held/actor appellant}})
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (record-claimable-v2 workflow-id :settlement/principal appellant amount))]
        (attr/with-attribution {:subject/type :bond
                                :subject/id (str workflow-id "-" appellant)
                                :action/type :bond/return
                                :evidence/reason :bond-returned}
          (cap/capture-event-evidence!
           :bond-returned
           {:bond/before {:bond-balance amount
                          :bond-status :active}}
           {:bond/after  {:bond-balance 0
                          :bond-status :returned}}
           {:bond/workflow-id workflow-id
            :bond/appellant appellant
            :bond/amount amount
            :bond/token token}
           nil
           {:world-before world
            :world-after world'}))
        (assoc (t/ok world') :returned amount)))))

(defn return-all-bonds-for-workflow
  "Return all posted appeal/challenge bonds for a workflow-id on finalization.
   Prevents bonds from leaking/accumulating indefinitely.
   Bonds are returned as claimable to the appellant."
  [world workflow-id]
  (let [wf-bonds (get-in world [:bond-balances workflow-id])]
    (if (seq wf-bonds)
      (let [et    (t/get-transfer world workflow-id)
            token (:token et)]
        (reduce-kv (fn [w appellant amount]
                     (if (pos? amount)
                       (-> w
                           (sub-held token
                                     amount
                                     {:action "return-all-bonds-for-workflow"
                                      :reason :appeal-bond-returned
                                      :extra {:held/action "return-all-bonds-for-workflow"
                                              :held/workflow-id workflow-id
                                              :held/bond-id (str workflow-id "-" appellant)
                                              :held/actor appellant}})
                           (assoc-in [:bond-balances workflow-id appellant] 0)
                            (record-claimable-v2 workflow-id :settlement/principal appellant amount))
                        w))
      world
      wf-bonds))
      world)))

(defn final-held-summary
  "Derived reporting summary of the held-adjustment ledger from the live world state.
   Delegates to the protocol-independent implementation in resolver-sim.assurance.custody."
  [world]
  (custody-core/final-held-summary
    (get world :held-adjustments [])
    (get world :held-ledger/index {})
    (get world :total-held {})))
