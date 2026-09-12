(ns resolver-sim.allocation.activation
  "Allocation activation receipt: `allocation-activation.v1`.

   Activation is a separate authenticated protocol step from the allocation
   proof. The proof establishes:

     this computation produced: rejection = X, allocation-result = Y

   The activation receipt binds that proof to an activation decision and an
   economic effect, enforcing:

     rejected proof ⇒ activation prohibited

   Verification failure is NOT informational metadata — it is an authorization
   boundary. A valid (activated) receipt can only be emitted for a passing
   proof; a rejected proof can only ever produce a :prohibited receipt that
   binds the rejection classification.

   All-active no-churn: when the allocation is all-active — no rejection and no
   deferred/haircut fail action — the receipt binds the same result-root as the
   independently supplied unfiltered result. The rejection/fail-action filter is
   a no-op. The activation receipt root remains distinct because it also commits
   proof and policy metadata.

   This mirrors the existing distinction between a *decision being computed*
   and a *decision authorizing an irreversible effect*."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "allocation-activation.v1")

(def ^:const activation-statuses #{:activated :prohibited})

(defn rejected-proof?
  "True when the proof result carries a rejection classification (i.e. the
   proof did not verify / the allocation was rejected)."
  [proof]
  (boolean (:rejection/classification proof)))

(defn proof-status
  "Derive the activation status for a proof result.
   A passing proof may be activated only when it carries no rejection
   classification. Contradictory proof data is prohibited at construction time."
  [proof]
  (if (and (= :passing (:result/status proof))
           (not (rejected-proof? proof)))
    :activated
    :prohibited))

(defn proof-root
  "The proof root committed by the receipt. For the allocation kernel this is
   the certificate-assertions-digest (commits assertions, selected outcome,
   result root, totals). Falls back to :result-root when no digest is present."
  [proof]
  (or (:certificate-assertions-digest proof)
      (:result-root proof)))

(defn activation-policy-root
  "Commit the activation policy under ALLOCATION_ACTIVATION_POLICY_V1.
   The policy declares the activation authority (e.g. coordinator) and the
   fail-closed default: a rejected proof can never be activated."
  [policy]
  (hc/domain-hash :allocation-activation-policy policy))

(defn receipt-preimage
  "The canonical receipt value committed by the receipt root. Reads the
   namespaced storage keys of the receipt map."
  [{:keys [proof-root result-root activation-policy-root] :as receipt}]
  {:activation/schema-version schema-version
   :proof-root proof-root
   :result-root result-root
   :rejection/classification (:rejection/classification receipt)
   :activation/status (:activation/status receipt)
   :activation-policy-root activation-policy-root})

(defn receipt-root
  "Commit the allocation-activation.v1 receipt under
   ALLOCATION_ACTIVATION_V1."
  [receipt]
  (hc/domain-hash :allocation-activation (receipt-preimage receipt)))

(defn build-receipt
  "Build the allocation-activation.v1 receipt for a proof result and an
   activation policy.

   Fail-closed: a rejected proof produces a :prohibited receipt that binds the
   rejection classification. A :prohibited receipt is never valid for
   authorization (see valid-activated-receipt?); it exists only to record the
   prohibition in the same canonical object as the proof binding.

   Inputs:
     :proof    — allocation kernel public result (result/status, result-root,
                 certificate-assertions-digest, rejection/classification)
     :policy   — activation policy map

   Returns the receipt map including :activation/root."
  [{:keys [proof policy]}]
  (let [receipt {:activation/schema-version schema-version
                 :proof-root (proof-root proof)
                 :result-root (:result-root proof)
                 :rejection/classification (:rejection/classification proof)
                 :activation/status (proof-status proof)
                 :activation-policy-root (activation-policy-root policy)}]
    (assoc receipt :activation/root
           (hc/domain-hash :allocation-activation (receipt-preimage receipt)))))

(defn valid-activated-receipt?
  "A receipt is a valid authorization only when:
     - it is activated (not prohibited);
     - the proof it binds was passing (no rejection classification);
     - its root recomputes.

   This is the authorization boundary: a prohibited receipt is never valid, so
   verification failure can never be mistaken for an activated allocation."
  [receipt]
  (and (= :activated (:activation/status receipt))
       (nil? (:rejection/classification receipt))
       (= (:activation/root receipt)
          (hc/domain-hash :allocation-activation (receipt-preimage receipt)))))

(defn all-active?
  "True when the proof is passing and carries no deferred/haircut fail action.
   For the activation receipt, all-active means: no rejection, and the realized
   allocation is the unfiltered result (no fail-action filtering applied)."
  [proof]
  (and (= :passing (:result/status proof))
       (not (rejected-proof? proof))
       (nil? (get-in proof [:realized/fail-action]))))

(defn no-churn-root
  "The unfiltered result root an all-active activation is byte-identical to:
   the proof's result-root with no rejection/fail-action filtering."
  [proof]
  (:result-root proof))

(defn all-active-no-churn?
  "All-active no-churn: independently derive the pre-realization result root
   and require realization to be a no-op before comparing it with the receipt.
   This is intentionally about the result binding, not the activation receipt
   root (which also commits proof and policy metadata)."
  [{:keys [proof policy unfiltered-result-root]}]
  (let [receipt (build-receipt {:proof proof :policy policy})
        unfiltered-root (or unfiltered-result-root
                            (:unfiltered/result-root proof))]
    (and (all-active? proof)
         (string? unfiltered-root)
         (= unfiltered-root (:result-root proof))
         (= (:result-root receipt) unfiltered-root))))
