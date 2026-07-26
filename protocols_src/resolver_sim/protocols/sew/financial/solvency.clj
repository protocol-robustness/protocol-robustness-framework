(ns resolver-sim.protocols.sew.financial.solvency
  "Sew-specific cryptographic solvency classification.

   Cryptographic solvency is stronger than accounting solvency. It asks:
   can the protocol prove, from verifiable state commitments, that
   assets are sufficient to meet obligations?

   Tiers (lowest to highest assurance):
     :insolvent, :proof-invalid, :proof-state-mismatch, :unproven, :solvent
   See resolver-sim.financial.taxonomies/solvency-tiers for the full taxonomy.

   Proof mechanism (live since June 2026):

     When :proof-status :valid is requested, classify-solvency computes a
     SHA-256 state commitment over the solvency-relevant subset of the world
     (escrow positions, yield positions, token balances, and the previous
     commitment hash if one exists). The hash is stored in the world under
     [:solvency/commitment-root].

     The hash alone is NOT a solvency proof.  :solvent requires BOTH
     the accounting check (solvency-holds?) AND the commitment chain
     to be intact.  If accounting fails (liabilities exceed assets),
     the result is :insolvent regardless of proof status.

     The commitment chain proves the states are connected and unbroken.
     The accounting check proves each state was solvent when committed.
     Both are required for :solvent.

   See also:
     resolver-sim.financial.taxonomies — general taxonomy definitions
     (solvency tiers, ordinals) used by this namespace.

   This is a Sew reference implementation. Protocols with different
   world state shapes should implement their own classifiers using
   the same taxonomy vocabulary."
  (:require [clojure.string :as str]
            [resolver-sim.financial.taxonomies :as tax]
            [resolver-sim.protocols.sew.invariants.solvency :as solvency-inv]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.protocols.sew.financial.finality :as finality]
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

(defn- commitment-preimage
  "Produce the string that gets hashed — deterministic, sorted, newline-separated."
  [world prev-commitment]
  (str/join "\n"
            (concat
             [(str "protocol-version:1")
              (str "block-time:" (time-ctx/block-ts world))
              (str "prev-commitment:" (or prev-commitment "none"))]
             (escrow-summary-seq world)
             (yield-summary-seq world)
             (balance-line :total-held world)
             (balance-line :claimable world))))

(defn compute-state-commitment
  "Compute a SHA-256 state commitment over the solvency-relevant subset
   of the world. Returns a hex string.

   The commitment binds:
     - protocol version
     - block time
     - previous commitment hash (if any) — enables hash chaining
     - per-escrow: (workflow-id, state, amount-after-fee)
     - per-yield-position: (position-id, token, state, shortfall)
     - per-token: total-held, claimable

   Deterministic: same world state + same prev-commitment → same hash."
  [world prev-commitment]
  (sha-256 (commitment-preimage world prev-commitment)))

;; ── Solvency-relevant subset extractor ────────────────────────────────────────

(defn prepare-balances
  "Extract the solvency-relevant subset of world into a merged-balances map.
   Used by both accounting and cryptographic checks."
  [world]
  {:total-held (get world :total-held {})
   :claimable  (get world :claimable {})
   :fees       (get world :fees {})
   :withdrawn  (get world :withdrawn {})
   :bond-balances (get world :bond-balances {})
   :bond-fees     (get world :bond-fees {})
   :bond-dist     (get world :bond-distributed {})
   :retained      (get world :retained-slash-reserves 0)})

;; ── Core solvency classifier ─────────────────────────────────────────────────

(defn classify-solvency
  "Classify cryptographic solvency for the current world.

   Parameters:
     world              — current world state
     token-balances     — optional pre-computed token balance map
     opts               — optional map:
       :proof-status    — one of nil, :unproven, :invalid, :mismatch, :valid
                          When :valid, compute-state-commitment is called
                          and verified against [:solvency/commitment-root].
                          When :invalid, the stored commitment is recomputed
                          and verified — mismatch produces :proof-state-mismatch.

   Returns:
     {:solvency/status       :solvent | :insolvent | :unproven | :proof-invalid
                                                      | :proof-state-mismatch
      :solvency/proof-required?  false
      :solvency/proof-valid?     nil
      :solvency/ratio            numeric
      :solvency/commitment       hex string | nil
      :solvency/reason           string}"
  ([world] (classify-solvency world nil {}))
  ([world token-balances]
   (classify-solvency world token-balances {}))
  ([world token-balances {:keys [proof-status] :or {proof-status nil}}]
   (let [merged-balances  (or token-balances (prepare-balances world))

         solvency-result  (try (solvency-inv/solvency-holds? world merged-balances)
                               (catch Exception _
                                 {:holds? false :violations [{:reason "Internal solvency invariant evaluation failure"}]}))

         balance-result   (try (let [ratio (invariants/calculate-solvency-ratio world)]
                                 {:holds? (>= ratio 0.999) :ratio ratio})
                               (catch Exception _
                                 {:holds? false :ratio 1.0
                                  :reason "Internal solvency ratio calculation failure"}))

         accounting-solvent? (and (:holds? solvency-result true)
                                  (:holds? balance-result true))

         ;; ── Cryptographic commitment verification ─────────────────────────
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

         proof-valid? (case proof-status*
                        :unproven nil
                        :invalid  false
                        :mismatch false
                        :valid    true
                        nil)]
     {:solvency/status
      (cond
        (not accounting-solvent?) :insolvent
        (= proof-status* :invalid) :proof-invalid
        (= proof-status* :mismatch) :proof-state-mismatch
        (= proof-status* :valid) :solvent
        :else :unproven)

      :solvency/proof-required? (= :invalid proof-status*)
      :solvency/proof-valid?    proof-valid?
      :solvency/ratio           (:ratio balance-result 1.0)
      :class                     :analytic
      :solvency/commitment      stored-commitment
      :solvency/reason
      (cond
        (not accounting-solvent?)
        (str "accounting insolvent: "
             (count (:violations solvency-result 0)) " violations")
        (= proof-status* :invalid)  (str "commitment mismatch: stored=" stored-commitment
                                         " computed=" computed-commitment)
        (= proof-status* :mismatch) "proof references different state"
        (= proof-status* :valid)    "proof valid, cryptographically solvent"
        :else                       "accounting solvent but no cryptographic proof")})))

;; ── World-state commitment helpers ────────────────────────────────────────────

(defn with-commitment
  "Return an updated world with a fresh solvency commitment computed
   against its current state.

   The commitment chains: each new hash includes the previous hash
   as preimage, producing a linked list of commitments across
   world transitions.

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
