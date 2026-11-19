(ns resolver-sim.protocols.sew.financial.solvency
  "Sew-specific solvency assessment.

   Four dimensions are kept explicitly separate, and a headline assessment is
   derived from them (never the other way around):

     - accounting         held-custody reconciliation + conservation
     - economic solvency  realizable assets >= canonical economic liabilities
     - reserved coverage  policy-defined senior-coverage adequacy
     - liquidity          (illustrative) liquid assets vs due liabilities

   World ──┬─ accounting ──────────┐
           ├─ economic solvency ───┼─► assessment policy ─► headline status
           ├─ reserved coverage ───┘
           └─ liquidity

   economic-liability-set.v1 (resolver-sim.protocols.sew.financial.liabilities)
   is the single source of truth for the liability universe; the liability
   artifact (entries + exclusion decisions + policy + root) makes that
   selection reproducible and machine-verifiable.

   Canonical status vocabulary (see resolver-sim.financial.taxonomies):
     :assessment/status    :solvent | :impaired | :insolvent | :unassessable
                                                  | :assessment-invalid
     :evidence/status      :verified | :insufficient | :unavailable | :stale
                                                  | :invalid-evidence
     :verification/status  :unverified | :verified | :invalid

   Status semantics (higher precedence wins):
     :assessment-invalid > :unassessable > :insolvent > :impaired > :solvent
   :impaired MUST NOT mean assets < liabilities (that is always :insolvent).
   An inconsistent ledger is :assessment-invalid, never :insolvent.

   Commitment layer: when :proof-status :valid is requested, classify-solvency
   verifies the stored SHA-256 state commitment against a recomputation. The
   commitment binds the liability artifact root and asset root — it is an
   integrity commitment over the classifier's inputs and outputs, NOT by itself
   an independently verifiable solvency proof. Absence of external evidence is
   never evidence of solvency.

   See also:
     resolver-sim.financial.taxonomies — status vocabularies
     resolver-sim.protocols.sew.financial.liabilities — canonical liability set

   This is a Sew reference implementation. Protocols with different world state
   shapes should implement their own classifiers using the same vocabulary."
  (:require [clojure.string :as str]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.financial.concentration :as conc]
            [resolver-sim.protocols.sew.invariants.solvency :as solvency-inv]
            [resolver-sim.time.context :as time-ctx]))

;; ── SHA-256 state commitment ──────────────────────────────────────────────────

(def ^:private digest-algorithm "SHA-256")

(defn- hex-str
  ^String [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (.toString sb)))

(defn- sha-256
  "Compute SHA-256 hex digest of a string."
  ^String [^String s]
  (let [md (java.security.MessageDigest/getInstance digest-algorithm)]
    (.update md (.getBytes s "UTF-8"))
    (hex-str (.digest md))))

(defn- sorted-lines
  [m]
  (sort (for [[k v] m] (str (if (keyword? k) (name k) (str k)) ":" v))))

(defn- escrow-summary-seq
  "Deterministic sequence of solvency-relevant entries from the world.
   Sorted by workflow-id for stable hashing."
  [world]
  (let [escrows (get world :escrow-transfers {})]
    (sort-by first
             (for [[wf-id escrow] escrows
                   :let [state (:escrow-state escrow)
                         amount (:amount-after-fee escrow 0)]]
               (str wf-id "|" (name state) "|" amount)))))

(defn- yield-summary-seq
  "Yield positions sorted for stable hashing."
  [world]
  (let [positions (get world :yield/positions {})]
    (sort (for [[pos-id pos] positions]
            (str pos-id "|" (:token pos) "|"
                 (:escrow-state pos "") "|"
                 (:shortfall pos ""))))))

(defn- balance-line
  "One stable line per token for the relevant balance maps."
  [token-map-key world]
  (let [m (get world token-map-key {})]
    (sort (for [[k v] m] (str (name k) ":" v)))))

(defn- liability-asset-roots
  "Deterministic roots over the canonical liability artifact and custody assets
   so the commitment binds the assessment's economic inputs, not only the raw
   balances. The liability root is the committed artifact root (version +
   policy + entries + exclusion decisions), making the liability selection
   reproducible by a verifier."
  [world]
  (let [artifact (liab/liability-artifact world)
        assets (liab/custody-assets world)]
    {:liability-root (:liability-set/root artifact)
     :asset-root     (sha-256 (str/join "\n" (sorted-lines assets)))}))

(defn- commitment-preimage
  "Produce the string that gets hashed — deterministic, sorted, newline-separated.
   Binds the committed liability-set artifact root and asset-set root so the
   commitment is an integrity commitment over the assessment's economic inputs."
  [world prev-commitment]
  (let [{:keys [liability-root asset-root]} (liability-asset-roots world)]
    (str/join "\n"
              (concat
               [(str "protocol-version:1")
                (str "block-time:" (time-ctx/block-ts world))
                (str "prev-commitment:" (or prev-commitment "none"))
                (str "liability-root:" liability-root)
                (str "asset-root:" asset-root)]
               (escrow-summary-seq world)
               (yield-summary-seq world)
               (balance-line :total-held world)
               (balance-line :claimable world)))))

(defn compute-state-commitment
  "Compute a SHA-256 state commitment over the solvency-relevant subset of the
   world. Returns a hex string.

   The commitment binds:
     - protocol version, block time, previous commitment hash (hash chaining)
     - the canonical liability-set root and asset-set root
     - per-escrow: (workflow-id, state, amount-after-fee)
     - per-yield-position: (position-id, token, state, shortfall)
     - per-token: total-held, claimable

   Deterministic: same world state + same prev-commitment → same hash.

   NOTE: this is an integrity commitment over the classifier's inputs, not by
   itself an independently verifiable solvency proof."
  [world prev-commitment]
  (sha-256 (commitment-preimage world prev-commitment)))

;; ── Solvency-relevant subset extractor ────────────────────────────────────────

(defn prepare-balances
  "Extract the solvency-relevant subset of world into a merged-balances map.
   Retained for legacy callers; the canonical liability universe lives in
   resolver-sim.protocols.sew.financial.liabilities."
  [world]
  {:total-held (get world :total-held {})
   :claimable  (get world :claimable {})
   :fees       (get world :fees {})
   :withdrawn  (get world :withdrawn {})
   :bond-balances (get world :bond-balances {})
   :bond-fees     (get world :bond-fees {})
   :bond-dist     (get world :bond-distributed {})
   :retained      (get world :retained-slash-reserves 0)})

;; ── Economic solvency ─────────────────────────────────────────────────────────

(defn external-balance-by-token
  "Aggregate observed custody balances per token from
   :solvency/contract-balances. Accepts {[:contract-id token] amount} or
   nested {contract-id {token amount}}. Returns nil when no snapshot exists."
  [world]
  (let [balances (:solvency/contract-balances world)]
    (cond
      (nil? balances) nil
      (map? balances)
      (reduce-kv
       (fn [acc k v]
         (cond
           (and (vector? k) (= 2 (count k))) (update acc (second k) (fnil + 0) (long v))
           (map? v) (reduce-kv (fn [acc2 tok amt] (update acc2 tok (fnil + 0) (long amt))) acc v)
           :else acc))
       {}
       balances)
      :else nil)))

(defn economic-solvency?
   "Economic solvency: are realizable assets sufficient for the canonical
   economic liability set?

   ASSET AUTHORITY HIERARCHY: an attached external balance observation is
   AUTHORITATIVE for the economic asset value (observed balances replace the
   modeled custody figure). The modeled ledger remains the basis for the
   accounting reconciliation (held-custody-reconciles?). Any divergence
   between observed and ledger is reported as an explicit finding
   (:observed-vs-ledger) rather than silently substituted — so a caller can
   see the assessment used observation X against ledger Y.

   Consumes economic-liability-set.v1 — no caller may independently re-decide
   the liability universe.

   Returns:
     {:holds? bool
      :per-token {token {:assets n :liabilities n :surplus n :coverage-ratio r}}
      :observed-vs-ledger [{:token t :observed n :ledger n :delta n} ...] | nil
      :violations [{:token t :assets n :liabilities n} ...]}"
  ([world] (economic-solvency? world nil))
  ([world external-balances]
   (let [rows (liab/asset-liability-rows world external-balances)
         ledger (liab/custody-assets world)
         observed-vs-ledger
         (when external-balances
           (vec (sort-by (comp str first)
                         (for [[tok obs] external-balances
                               :let [led (long (get ledger tok 0))]
                               :when (not= (long obs) led)]
                           {:token tok :observed (long obs) :ledger led
                            :delta (- (long obs) led)}))))
         violations (vec (for [[tok r] rows :when (neg? (:surplus r))]
                           {:token tok :assets (:assets r) :liabilities (:liabilities r)}))]
     {:holds? (empty? violations)
      :per-token rows
      :observed-vs-ledger observed-vs-ledger
      :violations violations})))

(defn reserved-coverage-sufficient?
  "Separate PROTECTION-POLICY measure (NOT base economic solvency; the name
   deliberately avoids \"solvency\" because it measures coverage adequacy, not
   ability to pay).

   Reserved senior coverage is capital/coverage intended as additional
   protection against junior resolver losses, not a second liability. This
   predicate verifies that reserved coverage stays within the policy-defined
   coverage-max bound.

   The model does not track dedicated backing assets for coverage commitments
   (register-senior-bond records only coverage-max), so this is a
   policy-compliance measure: a breach means the protocol's required safety
   margin is exceeded, which is distinct from being unable to pay. Economic
   solvency and reserved-coverage sufficiency must be reported independently.

   Returns {:holds? bool :total-reserved n :violations [...]}"
  [world]
  (let [violations
        (vec (for [[addr {:keys [coverage-max reserved-coverage]}] (:senior-bonds world {})
                   :let [reserved (long (or reserved-coverage 0))
                         max-allowed (long (or coverage-max 0))]
                   :when (> reserved max-allowed)]
               {:type :coverage-policy-breach
                :senior addr :reserved reserved :coverage-max max-allowed}))]
    {:holds? (empty? violations)
     :total-reserved (reduce + 0 (map (comp long :reserved-coverage)
                                      (vals (:senior-bonds world {}))))
     :violations violations}))

(defn reserved-coverage-solvency?
  "DEPRECATED: renamed to reserved-coverage-sufficient?. Compatibility alias."
  [world]
  (reserved-coverage-sufficient? world))

(defn liquidity-sufficient?
  "ILLUSTRATIVE fourth dimension (parallel to the other policy measures; NOT
   enforced in check-all): are liquid assets sufficient for currently-due
   obligations?

   Liquid assets: total-held + total-fees + bond-fees (immediately
   distributable custody). Currently-due liabilities: claimable-v2 payables +
   deferred amounts still owed.

   NOTE: after a settlement the settled amount moves out of :total-held into
   :claimable-v2 (still physically in custody), so this predicate reports a
   liquidity shortfall on every settled-but-unwithdrawn claim. It is kept as a
   standalone diagnostic measure, not as a world invariant, because a
   settlement is a legitimate state rather than a liquidity failure.
   liquidity-solvency? is a deprecated compatibility alias.

   Returns {:holds? bool :liquid-assets n :due-liabilities n :violations [...]}"
  [world]
  (let [liquid-assets (merge-with +
                                  (:total-held world {})
                                  (:total-fees world {})
                                  (:bond-fees world {}))
        due (merge-with +
                        (liab/claimable-v2-liability-by-token world)
                        (liab/deferred-liability-by-token world))
        tokens (into (set (keys liquid-assets)) (keys due))
        violations (vec (for [tok tokens
                              :let [a (long (get liquid-assets tok 0))
                                    l (long (get due tok 0))]
                              :when (< a l)]
                          {:token tok :liquid-assets a :due l}))]
    {:holds? (empty? violations)
     :liquid-assets (reduce + 0 (vals liquid-assets))
     :due-liabilities (reduce + 0 (vals due))
     :violations violations}))

(defn liquidity-solvency?
  "DEPRECATED: renamed to liquidity-sufficient? for a parallel four-dimension
   vocabulary (held-custody-reconciles? / economic-solvency? /
   reserved-coverage-sufficient? / liquidity-sufficient?). Compatibility alias."
  [world]
  (liquidity-sufficient? world))

;; ── Observed coverage ─────────────────────────────────────────────────────────

(defn observed-coverage?
  "Observed coverage: do externally evidenced custody balances cover modeled
   outstanding obligations? Delegates to contract-payout-solvency? and reports
   the canonical evidence status.

   Absence of evidence is :coverage :unavailable — never a silent pass.

   Returns {:holds? bool :status :evaluated|:not-evaluated
            :coverage :verified|:insufficient|:unavailable|:stale|:invalid-evidence
            :violations [...]}"
  [world]
  (solvency-inv/contract-payout-solvency? world))

;; ── Core assessment classifier ────────────────────────────────────────────────

(defn- accounting-coherent?
  "True when the internal ledger reconciles: the held-custody reconciliation
   (held-custody-reconciles?) holds. Full value conservation is enforced
   separately by conservation-of-funds? in the world invariant runner; a failure
   there also means the ledger cannot be trusted for a solvency claim."
  [world]
  (try
    (:holds? (solvency-inv/held-custody-reconciles? world))
    (catch Exception _
      false)))

(defn- legacy-tier-for
  "Derive the deprecated five-tier value from the canonical assessment.
   Compatibility view only — never authoritative."
  [status proof-valid]
  (cond
    (= status :insolvent) :insolvent
    (= status :assessment-invalid) :insolvent
    (= status :unassessable) :unproven
    (true? proof-valid) :solvent
    (false? proof-valid) :proof-invalid
    :else :unproven))

(defn classify-solvency
  "Classify solvency for the current world under the canonical assessment
   vocabulary.

   Parameters:
     world              — current world state
     token-balances     — optional pre-computed token balance map (legacy)
     opts               — optional map:
        :proof-status    — nil | :unproven | :invalid | :mismatch | :valid
                           When :valid, the stored commitment is recomputed and
                           verified against [:solvency :commitment-root].
        :require-external-coverage? — when true, missing/stale/invalid external
                           balance evidence downgrades the assessment to
                           :unassessable (fail-closed). Default false.
        :external-horizon — (future) staleness horizon for balance observations.

   Returns:
     {:assessment/status       :solvent | :impaired | :insolvent | :unassessable
                                                      | :assessment-invalid
      :assessment/reasons      #{:realized-loss :obligation-haircut ...}
      :assessment/legacy-tier  :solvent | :insolvent | :unproven | :proof-invalid
                                                      | :proof-state-mismatch
      :assessment/ratio        numeric (custody assets / economic liabilities)
      :evidence/status         :verified | :insufficient | :unavailable | :stale
                                                      | :invalid-evidence
      :verification/status     :unverified | :verified | :invalid
      :assessment/dimensions   {:accounting {:status :consistent|:inconsistent}
                                :economic-solvency {...}
                                :reserved-coverage {...}
                                :liquidity {...}}
      :liability-set           <liability reproducibility artifact incl. committed root>
      :assessment/commitment   hex string | nil
      :assessment/reason       string}

   STATUS SEMANTICS (precedence low → high):
     :solvent        — accounting consistent, assets >= liabilities.
     :impaired       — a realized loss/haircut occurred but assets still cover
                       liabilities. MUST NOT mean assets < liabilities — that is
                       always :insolvent. Reasons carry the dimension
                       (#{:realized-loss :obligation-haircut}).
     :insolvent      — valid, sufficient inputs: assets < liabilities.
     :unassessable   — required information is ABSENT (missing/stale evidence).
     :assessment-invalid — supplied information is internally CONTRADICTORY
                       (accounting inconsistent, malformed/invalid evidence).

   Precedence used for the headline (higher wins):
     :assessment-invalid > :unassessable > :insolvent > :impaired > :solvent.

   Observed-balance authority: when an external balance snapshot is attached it
   is authoritative for the economic asset value; any divergence from the
   modeled ledger is reported as an explicit finding (:observed-ledger-mismatch),
   never silently substituted."
  ([world] (classify-solvency world nil {}))
  ([world token-balances]
   (classify-solvency world token-balances {}))
  ([world _token-balances {:keys [proof-status require-external-coverage?]
                           :or {require-external-coverage? false}}]
   (let [coherent?   (accounting-coherent? world)
         ext-bal     (external-balance-by-token world)
         econ        (economic-solvency? world ext-bal)
         coverage    (observed-coverage? world)
         ev-status   (:coverage coverage)
         losses      (liab/recognized-loss-total world)
         rows        (:per-token econ)
         total-assets  (reduce + 0 (map :assets (vals rows)))
         total-liab    (reduce + 0 (map :liabilities (vals rows)))
         ratio       (if (zero? total-liab)
                       (if (pos? total-assets) ##Inf 1.0)
                       (/ (double total-assets) (double total-liab)))

         ;; ── Observed-balance authority findings ─────────────────────────
         ledger-assets (liab/custody-assets world)
         observed-vs-ledger
         (when ext-bal
           (vec (sort-by (comp str first)
                         (for [[tok obs] ext-bal
                               :let [led (long (get ledger-assets tok 0))]
                               :when (not= (long obs) led)]
                           {:token tok :observed (long obs) :ledger led
                            :delta (- (long obs) led)}))))

         ;; ── Dimension measures ──────────────────────────────────────────
         reserved (reserved-coverage-sufficient? world)
         liq      (liquidity-sufficient? world)
         concentration (conc/concentration-profile world)
         concentration-risk? (conc/concentration-risk? concentration)

         ;; ── Cryptographic commitment verification ─────────────────────
         stored-commitment (get-in world [:solvency :commitment-root])
         computed-commitment (when (and stored-commitment
                                        (#{:valid :invalid :mismatch} proof-status))
                               (compute-state-commitment world
                                                         (get-in world [:solvency :prev-commitment])))
         commitment-valid? (and stored-commitment computed-commitment
                                (= stored-commitment computed-commitment))
         proof-status* (case proof-status
                         (:nil :unproven) :unproven
                         :valid    (if commitment-valid? :valid :invalid)
                         :invalid  :invalid
                         :mismatch :mismatch
                         :unproven)
         verification (case proof-status*
                        :unproven :unverified
                        :invalid  :invalid
                        :mismatch :invalid
                        :valid    :verified)

         ;; ── Assessment status (canonical) ─────────────────────────────
         reasons (cond-> #{}
                   (pos? losses) (conj :realized-loss :obligation-haircut)
                   (= ev-status :unavailable) (conj :external-evidence-missing)
                   (= ev-status :insufficient) (conj :external-evidence-insufficient)
                   (= ev-status :stale) (conj :external-evidence-stale)
                   (not coherent?) (conj :accounting-inconsistent)
                   (seq observed-vs-ledger) (conj :observed-ledger-mismatch)
                   concentration-risk? (conj :concentration-risk))
         status (cond
                  (not coherent?) :assessment-invalid
                  (and require-external-coverage?
                       (#{:unavailable :stale :invalid-evidence} ev-status)) :unassessable
                  (not (:holds? econ)) :insolvent
                  (pos? losses) :impaired
                  :else :solvent)

         reason-str (cond
                      (not coherent?) "accounting inconsistent (reconciliation or conservation failed)"
                      (= status :unassessable) "external coverage evidence missing or stale"
                      (= status :insolvent) "assets insufficient for economic liabilities"
                      (= status :impaired) "realized loss recorded; obligations still covered"
                      :else "assets sufficient for economic liabilities")]
     {:assessment/status       status
      :assessment/reasons      reasons
      :assessment/legacy-tier  (legacy-tier-for status
                                                (case proof-status* :valid true :invalid false :mismatch false nil))
      :assessment/ratio        ratio
      :evidence/status         ev-status
      :verification/status     verification
      :assessment/commitment   stored-commitment
      :assessment/reason       reason-str
      :assessment/cutpoint-at  (time-ctx/block-ts world)
      :assessment/dimensions
      {:accounting {:status (if coherent? :consistent :inconsistent)
                    :holds? coherent?}
       :economic-solvency {:status (if (:holds? econ) :solvent :insolvent)
                           :holds? (:holds? econ)
                           :assets total-assets
                           :liabilities total-liab
                           :ratio ratio
                           :per-token rows
                           :observed-vs-ledger observed-vs-ledger}
       :reserved-coverage {:status (if (:holds? reserved) :sufficient :insufficient)
                           :holds? (:holds? reserved)
                           :total-reserved (:total-reserved reserved)}
        :liquidity {:status (cond
                              (:holds? liq) :sufficient
                              (nil? (:holds? liq)) :not-evaluated
                              :else :insufficient)
                    :holds? (:holds? liq)
                    :liquid-assets (:liquid-assets liq)
                    :due-liabilities (:due-liabilities liq)}
        :concentration concentration}
      :liability-set           (liab/liability-artifact world)
      :class                   :analytic})))

;; ── World-state commitment helpers ────────────────────────────────────────────

(defn with-commitment
  "Return an updated world with a fresh solvency commitment computed against its
   current state.

   The commitment chains: each new hash includes the previous hash as preimage,
   producing a linked list of commitments across world transitions. It also
   binds the canonical liability-set root and asset-set root.

   Usage:
     (-> world
         (solv/with-commitment)
         (classify-solvency nil {:proof-status :valid}))"
  [world]
  (let [prev-hash (get-in world [:solvency :commitment-root])
        new-hash  (compute-state-commitment world prev-hash)]
    (assoc world :solvency
           {:commitment-root new-hash
            :prev-commitment prev-hash
            :block-time (time-ctx/block-ts world)})))
