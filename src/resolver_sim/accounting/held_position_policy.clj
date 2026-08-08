(ns resolver-sim.accounting.held-position-policy
  "Protocol-independent held-position policy for custody adjustments.

   This namespace owns the reason -> custody-position classification that the
   trusted Sew write path enforces AND that the independent closed-form verifier
   re-derives.  Keeping it protocol-independent means the verifier
   (resolver-sim.assurance.custody) can recompute the expected account /
   position-id from :held/reason instead of trusting the artifact's selected
   account (BOUNDARY GUARD: this namespace never imports protocols_src/).

   Committed extension mechanism
   -----------------------------
   An unknown :held/reason is a replay violation UNLESS it is deliberately
   classified here.  Two committed classifications exist:

     - position-policy        reason -> {:held/account ... :scope-keys [...]}
                              position-bearing economic movements (strictly
                              re-derivable: account + position-id).
     - policy-exempt-reasons  reason -> {:rationale ...}
                              reasons intentionally outside the position policy:
                              their account (if any) is effect-carried and their
                              position-id MUST be absent.  Adding a reason here is
                              a committed, reviewed vocabulary change.

   Any reason in neither map is rejected by :held-custody/reason-position-policy.
   There is no implicit 'valid minus declared' default."
  (:require [clojure.string :as str]))

(defn- preferred-held-value
  [m preferred-key fallback-key]
  (or (get m preferred-key)
      (get m fallback-key)))

(def held-position-policy
  "Reason -> custody position policy.  Every position-bearing economic movement
   declares its custody account and the scope keys that (with :token) form the
   position-id.  Moved verbatim from the Sew protocol adapter so the closed-form
   verifier can re-derive positions independently."
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

(def address-scoped-held-reasons
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

(def exceptional-held-reasons
  "Reasons that require explicit authorization provenance before a ledger
   mutation.  Moved verbatim from the Sew protocol adapter."
  #{:governance-authorised-correction
    :replay-fixture-setup
    :replay-migration
    :force-authorised-release
    :force-authorised-refund
    :partial-fill-principal-loss})

(def policy-exempt-reasons
  "COMMITTED extension set: reasons intentionally outside the position policy.
   These movements do not derive a position-id from the policy; if they carry an
   account it is effect-carried.  A reason here is a deliberate, documented
   vocabulary decision — it is the only legitimate way for an artifact to sit
   outside `held-position-policy`."
  {:governance-authorised-correction
   {:rationale "governance-authorized custody correction; scope is intentionally not policy-derived"}
   :replay-fixture-setup
   {:rationale "fixture opening adjustment; not a live economic movement"}
   :replay-migration
   {:rationale "history migration adjustment; not a live economic movement"}
   :bounty-reserve-reservation
   {:rationale "with-bounty effect projection: account is carried by the effect, no policy position-id"}
   :held/unspecified
   {:rationale "unspecified-reason fallback for effect-projected adjustments"}})

(defn policy-for
  "Position policy for a reason, or nil when the reason is not position-bearing."
  [reason]
  (get held-position-policy reason))

(defn policy-exempt?
  "True when the reason is deliberately outside the position policy."
  [reason]
  (contains? policy-exempt-reasons reason))

(defn known-reason?
  "True when the reason is either position-bearing or committed policy-exempt."
  [reason]
  (or (contains? held-position-policy reason)
      (policy-exempt? reason)))

(defn policy-exempt-rationale
  [reason]
  (get-in policy-exempt-reasons [reason :rationale]))

(defn required-owner-attribution?
  "True when the movement's reason demands an explicit, non-fallback owner."
  [reason]
  (contains? address-scoped-held-reasons reason))

(defn position-components
  "Derive the custody position from a reason and its scope fields.

   Mirrors the Sew write-path derivation: account comes from the reason policy
   (or an explicit override), position-id is `[:held/position token account
   scope...]`, and owner-address falls back through actor/from/resolver/recipient.

   Callers deriving the EXPECTED position (for replay verification) must pass
   `extra` with :held/account and :held/position-id removed, exactly as the write
   path's validate-held-position! does."
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
        policy (policy-for reason)
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

(defn blank-owner?
  "True when an owner/address value is absent, blank, or structurally empty."
  [owner-address]
  (or (nil? owner-address)
      (and (string? owner-address) (str/blank? owner-address))))

(defn position-policy-check-error
  "Replay reason-position-policy classification for an adjustment.

   Returns nil when the adjustment conforms to its committed reason
   classification, or a keyword reason:
     :unknown-reason-outside-policy  reason in neither policy nor exemptions
     :exempt-with-position-id        policy-exempt reason carries a position-id
     :position-mismatch              derived account/position != committed

   `adjustment` must carry :token, :held/reason, and its scope fields.  The
   expected position is derived with :held/account / :held/position-id removed
   (replaying the write-path policy validation)."
  [adjustment]
  (let [reason (:held/reason adjustment)
        token (:token adjustment)]
    (cond
      (policy-exempt? reason)
      (when (some? (:held/position-id adjustment))
        :exempt-with-position-id)

      (nil? (policy-for reason))
      :unknown-reason-outside-policy

      :else
      (let [derived (position-components token reason
                                         (dissoc adjustment :held/account :held/position-id))]
        (when (or (not= (:held/account derived) (:held/account adjustment))
                  (not= (:held/position-id derived) (:held/position-id adjustment)))
          :position-mismatch)))))
