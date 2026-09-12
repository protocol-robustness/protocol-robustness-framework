(ns resolver-sim.protocols.sew.financial.liabilities
  "Canonical economic liability universe for Sew solvency assessment.

   economic-liability-set.v1 is the single source of truth for what counts as
   an outstanding economic obligation. Every solvency predicate consumes this
   primitive; callers MUST NOT independently re-decide which buckets count.

   Guarantees are deliberately kept separate:

     - accounting conservation — does the internal ledger balance?
     - economic solvency       — are realizable assets >= economic liabilities?
     - observed coverage       — do externally evidenced assets cover the
                                 liabilities the model says exist?

   A realized loss is a flow; a liability is a stock. After a haircut the
   outstanding obligation is reduced by the extinguished portion, so recognized
   (haircut) losses are NOT part of the liability set — they are already
   reflected in reduced entitlement and reduced custody. Counting them again
   double-counts the obligation.

   Reserved senior coverage is capital/coverage, not a second liability, and is
   measured separately by reserved-coverage-sufficient?.

   This is a Sew reference implementation. Protocols with different world state
   shapes should implement their own liability universe using the same
   liability-state vocabulary."
  (:require [clojure.string :as cstr]
            [resolver-sim.protocols.sew.types :as t]))

(def economic-liability-set-version
  "Version identifier for the canonical base liability universe."
  "v1")

(def liability-states
  "Liability state taxonomy. A liability is recognized once it is a present
   obligation; it may be due, deferred, reserved, contingent, impaired,
   extinguished, or settled.

   The base economic liability set counts recognized non-extinguished
   obligations: due, deferred, and accrued amounts that are still owed.
   Reserved coverage is capital, not a liability; contingent obligations are
   not counted until crystallised; extinguished and settled obligations are
   historical, not outstanding."
  [:recognized :due :deferred :reserved :contingent :impaired :extinguished :settled])

(defn- tk
  "Canonical token-ID grammar (STRICT, for a financial commitment boundary).

   - A keyword that is NAMESPACE-FREE is canonical as-is (:USDC stays :USDC);
     a namespaced keyword (:foo/USDC) is a DISTINCT identity and is rejected
     rather than silently aliased.
   - A string is cleaned (trim whitespace, drop a leading ':', uppercase) and
     must then match [A-Z0-9_]+ — anything else (empty, whitespace-only, a '/',
     embedded punctuation) is rejected. \"usdc\", \" USDC \", \":USDC\" unify
     with :USDC; \"foo/USDC\" is rejected.

   This is strict normalization: equivalent spellings of the SAME identity land
   in one bucket, and DISTINCT identities are never collapsed."
  [x]
  (cond
    (keyword? x)
    (if (namespace x)
      (throw (ex-info "namespaced token key is not a single token identity"
                      {:token x}))
      x)

    (string? x)
    (let [clean (-> x (cstr/trim) (cstr/replace #"^:" "") (cstr/upper-case))]
      (when-not (and (pos? (count clean))
                     (re-matches #"[A-Z0-9_]+" clean))
        (throw (ex-info "non-canonical token key"
                        {:token x :cleaned clean})))
      (keyword clean))

    :else
    (throw (ex-info "non-keyword/non-string token key" {:token x}))))

(defn- normalize-token-keys
  "Normalize every token key in a per-token map to a keyword."
  [m]
  (into {} (map (fn [[k v]] [(tk k) v])) (or m {})))

;; ── Liability extractors (per-token) ─────────────────────────────────────────
;; These mirror the legacy extractors formerly in
;; resolver-sim.protocols.sew.invariants.solvency, made public so the strict
;; held-custody reconciliation and the economic liability set share one source.

(defn escrow-liability-by-token
  "Live escrow amount-after-fee per token (escrow state in live-states)."
  [world]
  (reduce (fn [acc [_ et]]
            (if (and (:token et) (contains? t/live-states (:escrow-state et)))
              (update acc (tk (:token et)) (fnil + 0) (:amount-after-fee et 0))
              acc))
          {}
          (:escrow-transfers world)))

(defn bond-liability-by-token
  "Active bond balances held per token (posted bonds are held in custody)."
  [world]
  (reduce (fn [acc [wf agents]]
            (let [tok (get-in world [:escrow-transfers wf :token])]
              (if tok
                (update acc (tk tok) (fnil + 0) (reduce + 0 (vals agents)))
                acc)))
          {}
          (:bond-balances world)))

(defn slash-appeal-bond-liability-by-token
  "Appeal-bond amounts held pending per token."
  [world]
  (reduce (fn [acc [slash-id ev]]
            (let [custody (get-in world [:appeal-bond-custody slash-id])
                  token   (or (:token custody)
                              (get-in world [:escrow-transfers (:workflow-id custody) :token]))]
              (if token
                (update acc (tk token) (fnil + 0) (:appeal-bond-held ev 0))
                acc)))
          {}
          (:pending-fraud-slashes world {})))

(defn yield-liability-by-token
  "Yield obligations per token:
     - active positions in live escrows (or resolver-owned): realized + unrealized
     - unwinding non-resolver positions: deferred residue still owed
   Deferred amounts are counted here because they are still owed despite timing.
   Realized-yield on active positions is not double-counted with :total-held:
   it is the corresponding liability for custody already accrued into held."
  [world]
  (reduce (fn [acc [oid pos]]
            (let [et (when (vector? oid) (get-in world [:escrow-transfers (second oid)]))
                  tok (:token pos)]
              (cond
                (and tok (= (:status pos) :active)
                     (or (and et (contains? t/live-states (:escrow-state et)))
                         (t/resolver-yield-owner-id? oid)))
                (update acc (tk tok) (fnil + 0)
                        (+ (:unrealized-yield pos 0) (:realized-yield pos 0)))

                (and tok (= (:status pos) :unwinding)
                     (not (t/resolver-yield-owner-id? oid)))
                (update acc (tk tok) (fnil + 0) (get-in pos [:shortfall :deferred-amount] 0))

                :else acc)))
          {}
          (:yield/positions world {})))

(defn deferred-liability-by-token
  "Deferred amounts still owed per token across all yield positions.
   Used by liquidity-solvency? (deferred obligations are delayed, not due now)."
  [world]
  (reduce (fn [acc [_ pos]]
            (let [tok (:token pos)]
              (if tok
                (update acc (tk tok) (fnil + 0)
                        (get-in pos [:shortfall :deferred-amount] 0))
                acc)))
          {}
          (:yield/positions world {})))

(defn claimable-v2-liability-by-token
  "Settled-but-unwithdrawn claimable obligations per token. Every outstanding
   v2 claimable is a present obligation to transfer value. The legacy :claimable
   map is deliberately excluded because settlement principal/yield are
   dual-written there and including it would double-count liabilities."
  [world]
  (reduce-kv
   (fn [totals workflow-id domains]
     (let [token (get-in world [:escrow-transfers workflow-id :token])]
       (if token
         (let [tok (tk token)]
           (assoc totals tok
                  (+ (get totals tok 0)
                     (reduce + 0
                             (for [[_domain recipients] domains
                                   [_recipient amount] recipients]
                               amount)))))
         totals)))
   {}
   (:claimable-v2 world {})))

(defn stable-token
  "Resolve the stable-asset token used for token-agnostic protocol obligations
   (e.g. slash credits).

     {:token <kw> :source <:configured|:legacy-default>}

   :configured when [:params :solvency/stable-token] is present; otherwise
   :legacy-default (the :USDC compatibility default). The SOURCE is committed in
   the liability artifact so a verifier can never mistake a legacy inference for
   a configured choice — and a strictly canonical assessment path can reject
   :legacy-default (see the assessment's require-stable-token? option)."
  [world]
  (if-let [configured (get-in world [:params :solvency/stable-token])]
    {:token (tk configured) :source :configured}
    {:token :USDC :source :legacy-default}))

(defn slash-credit-liability-by-token
  "Protocol obligations to restore resolver stake after a vindicated reversal
   slash (see resolver-sim.protocols.sew.resolution/execute-resolution). These
   are real economic obligations: excluding them would systematically overstate
   economic solvency.

   Resolver stakes are stable-denominated; the attribution token comes from
   stable-token ([:params :solvency/stable-token] or the :USDC legacy default)
   so the obligation concatenates into the SAME bucket as the world's actual
   stable-asset token. The chosen token and its source are committed in the
   liability artifact."
  [world]
  (let [stable (:token (stable-token world))
        total (reduce + 0 (vals (:slash-credit-liabilities world {})))]
    (if (pos? total) {stable total} {})))

(defn recognized-loss-by-token
  "Realized (haircut) principal losses per token. A loss is a flow/outcome
   classification, NOT a present liability: after a haircut the outstanding
   obligation is reduced by the extinguished portion. Exposed for conservation
   accounting and for the :impaired assessment status."
  [world]
  (reduce (fn [acc [_ pos]]
            (let [sf (:shortfall pos)
                  tok (:token pos)]
              (if (and tok sf (#{:principal-loss :negative-carry-loss} (:reason sf)))
                (update acc tok (fnil + 0) (:haircut-amount sf 0))
                acc)))
          {}
          (:yield/positions world {})))

;; ── Canonical liability universe ─────────────────────────────────────────────

(defn economic-liability-set
  "The canonical base economic liability universe (economic-liability-set.v1).

   Includes (base):
     - live escrow amount-after-fees        (:escrow)
     - active bonds                         (:bonds)
     - slash-appeal bonds                   (:appeal-bonds)
     - yield obligations incl. deferred     (:yield)
     - claimable-v2 payables                (:claimable-v2)
     - slash-credit liabilities             (:slash-credits)

   Excludes (by design):
     - legacy :claimable (dual-writes settlement)
     - already-haircutted / extinguished amounts (historical loss)
     - reserved senior coverage (coverage/capital, not a second liability)
     - contingent obligations (not crystallised)
     - settled liabilities (no present obligation)

   Returns:
     {:version \"v1\"
      :buckets  {bucket {token amount} ...}
      :per-token {token amount}
      :total amount}"
  [world]
  (let [buckets {:escrow        (escrow-liability-by-token world)
                 :bonds         (bond-liability-by-token world)
                 :appeal-bonds  (slash-appeal-bond-liability-by-token world)
                 :yield         (yield-liability-by-token world)
                 :claimable-v2  (claimable-v2-liability-by-token world)
                 :slash-credits (slash-credit-liability-by-token world)}
        tokens (reduce (fn [acc m] (into acc (keys m))) #{} (vals buckets))
        per-token (into {}
                        (for [tok tokens]
                          [tok (reduce + 0 (map #(get % tok 0) (vals buckets)))]))]
    {:version   economic-liability-set-version
     :buckets   buckets
     :per-token per-token
     :total     (reduce + 0 (vals per-token))}))

(defn custody-assets
  "Realizable custody assets per token: total-held + claimable-v2. Token keys
   are normalized to keywords so \"USDC\" and :USDC concatenate into one bucket.

   Settled-but-unwithdrawn claims remain physically in custody until withdrawn,
   so both pools are realizable. When an external balance observation is
   supplied, it replaces this modeled figure (see observed-coverage in
   resolver-sim.protocols.sew.financial.solvency)."
  [world]
  (merge-with + (normalize-token-keys (:total-held world))
               (claimable-v2-liability-by-token world)))

(defn recognized-loss-total
  "Aggregate recognized (haircut) loss for a token, or all tokens when token is nil."
  ([world] (reduce + 0 (vals (recognized-loss-by-token world))))
  ([world token] (get (recognized-loss-by-token world) token 0)))

;; ── Convenience helpers ──────────────────────────────────────────────────────

(defn asset-liability-rows
  "Per-token {token {:assets n :liabilities n :surplus n :coverage-ratio r}}.
   Uses modeled custody assets unless external-balances (a {token amount} map
   or nil) is supplied. Token keys are normalized to keywords so string and
   keyword representations of the same asset concatenate into one bucket.

   COVERAGE-RATIO CONVENTION (not literal division in the degenerate cases):
     - liabilities > 0          → assets / liabilities
     - liabilities = 0, assets > 0 → +inf  (surplus with no obligations)
     - liabilities = 0, assets = 0 → 1.0   (no obligations are fully covered;
                                    a defined convention, not a division result)
     - liabilities < 0 / malformed → treated via the above guards"
  [world external-balances]
  (let [assets (normalize-token-keys (or external-balances (custody-assets world)))
        {:keys [per-token]} (economic-liability-set world)
        tokens (into (set (keys per-token)) (keys assets))]
    (into {}
          (for [tok tokens
                :let [a (max 0 (long (get assets tok 0)))
                      l (max 0 (long (get per-token tok 0)))]]
            [tok {:assets          a
                  :liabilities     l
                  :surplus         (- a l)
                  :coverage-ratio  (cond
                                     (zero? l) (if (pos? a) ##Inf 1.0)
                                     :else (double (/ a l)))}]))))

;; ── Assessment reproducibility artifact ──────────────────────────────────────
;;
;; economic-liability-set.v1 being the single implementation is "code
;; centralization". The artifact below additionally makes the selection
;; REPRODUCIBLE: a verifier can establish, from world state alone, exactly
;; which obligations were included and which were excluded, and can recompute
;; the committed liability-set root. This is stronger than centralization: two
;; implementations cannot agree on the root format while disagreeing on what
;; belongs in the set — the entries and exclusion decisions are part of the
;; committed preimage.

(defn- sha-256-of
  "Stable sha-256 over an ordered seq of strings."
  [lines]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [line lines]
      (.update md (.getBytes (str line "\n") "UTF-8")))
    (let [sb (StringBuilder.)]
      (doseq [b (.digest md)]
        (.append sb (format "%02x" (bit-and b 0xff))))
      (.toString sb))))

(defn- legacy-claimable-total
  "Aggregate legacy :claimable amount (excluded: dual-writes settlement).
   Accepts both the canonical nested {workflow-id {address amount}} shape and
   flat {token amount} maps found in some fixtures."
  [world]
  (let [c (:claimable world {})
        entries (vals c)]
    (if (every? number? entries)
      (reduce + 0 (map long entries))
      (reduce + 0
              (for [[_ addr-map] c
                    [_ amt] (or addr-map {})]
                (long (or amt 0)))))))

(defn- un-attributable-claimable-total
  "claimable-v2 entries whose workflow has no escrow token — cannot be assigned
   to a token, so they are surfaced as an explicit exclusion rather than
   silently dropped."
  [world]
  (reduce-kv
   (fn [total workflow-id domains]
     (let [token (get-in world [:escrow-transfers workflow-id :token])]
       (if token
         total
         (+ total (reduce + 0 (for [[_domain recipients] domains
                                   [_recipient amount] recipients]
                                (long (or amount 0))))))))
   0
   (:claimable-v2 world {})))

(defn liability-policy
  "Machine-readable inclusion/exclusion policy for economic-liability-set.v1.
   The policy hash is committed so a change in selection semantics changes the
   root even when a given world's entries happen to be identical."
  []
  {:policy/version economic-liability-set-version
   :policy/included
   [{:bucket :escrow :note "live escrow amount-after-fee (live-states)"}
    {:bucket :bonds :note "active bond balances held in custody"}
    {:bucket :appeal-bonds :note "slash-appeal bond amounts held pending"}
    {:bucket :yield :note "yield obligations: active realized+unrealized + unwinding deferred residue"}
    {:bucket :claimable-v2 :note "settled-but-unwithdrawn v2 payables (all domains)"}
    {:bucket :slash-credits :note "protocol obligations to restore resolver stake after vindicated reversal slash"}]
   :policy/excluded
   [{:bucket :legacy-claimable :reason :dual-write :note "settlement principal/yield dual-written; canonical payable is :claimable-v2"}
    {:bucket :haircutted :reason :flow-not-stock :note "extinguished portion of realized loss is not outstanding"}
    {:bucket :reserved-senior-coverage :reason :coverage-not-liability :note "reserve allocation is protection, not a second liability; measured by reserved-coverage-sufficient?"}
    {:bucket :contingent :reason :not-crystallised :note "no modeled contingent-obligation source in v1"}
    {:bucket :settled :reason :no-present-obligation :note "settled/extinguished liabilities are historical"}
    {:bucket :zero-valued :reason :no-economic-signal :note "zero-valued obligations are normalized away"}
    {:bucket :malformed :reason :un-attributable :note "obligation entries with no resolvable token are surfaced, not silently counted"}]})

(defn liability-source-summary
  "Canonical source roots for the world-state inputs to the liability selection.
   A verifier recomputes these from the raw world maps; together with the
   policy they uniquely determine the entries and exclusions."
  [world]
  (let [summarize (fn [label m]
                    [label (sha-256-of (sort (for [[k v] m] (pr-str [k v]))))])]
    (into {}
          (map (fn [[label m]] (summarize label m)))
          {:escrow-transfers      (:escrow-transfers world {})
           :bond-balances         (:bond-balances world {})
           :pending-fraud-slashes (:pending-fraud-slashes world {})
           :yield/positions       (:yield/positions world {})
           :claimable-v2          (:claimable-v2 world {})
           :slash-credit-liabilities (:slash-credit-liabilities world {})
           :claimable             (:claimable world {})
           :senior-bonds          (:senior-bonds world {})})))

(defn liability-exclusions
  "Explicit, machine-verifiable record of every exclusion decision with the
   excluded amount where determinable. The liability-set root commits these, so
   a verifier can prove which obligations were deliberately excluded."
  [world]
  (let [els (economic-liability-set world)
        {:keys [per-token]} els
        excluded
        [{:exclusion/bucket :legacy-claimable
          :exclusion/reason :dual-write
          :exclusion/note "settlement principal/yield are dual-written; canonical payable is :claimable-v2"
          :excluded-amount (legacy-claimable-total world)}
         {:exclusion/bucket :haircutted
          :exclusion/reason :flow-not-stock
          :exclusion/note "realized loss already reduced entitlement and custody"
          :excluded-amount (reduce + 0 (vals (recognized-loss-by-token world)))}
         {:exclusion/bucket :reserved-senior-coverage
          :exclusion/reason :coverage-not-liability
          :exclusion/note "reserve allocation is protection, not a second liability"
          :excluded-amount (reduce + 0 (map (comp long :reserved-coverage)
                                            (vals (:senior-bonds world {}))))}
         {:exclusion/bucket :contingent
          :exclusion/reason :not-crystallised
          :exclusion/note "no modeled contingent-obligation source in v1"
          :excluded-amount 0}
         {:exclusion/bucket :settled
          :exclusion/reason :no-present-obligation
          :exclusion/note "settled/extinguished liabilities are historical"
          :excluded-amount 0}
         {:exclusion/bucket :zero-valued
          :exclusion/reason :no-economic-signal
          :exclusion/note "zero-valued obligations normalized away"
          :excluded-amount 0}
         {:exclusion/bucket :malformed
          :exclusion/reason :un-attributable
          :exclusion/note "claimable-v2 entries with no resolvable escrow token are surfaced, not silently counted"
          :excluded-amount (un-attributable-claimable-total world)}]
        zero-tokens (into {} (filter (fn [[_ v]] (zero? v)) per-token))]
    {:exclusion/decisions excluded
     :exclusion/zero-valued-tokens zero-tokens}))

(defn liability-artifact
  "The full reproducibility artifact for the canonical liability universe.

     {:liability-set/version   \"v1\"
      :liability-set/policy    {...}        — committed inclusion/exclusion policy
      :liability-set/source    {...}        — source-state roots (world inputs)
      :liability-set/entries   [{:token .. :amount .. :buckets [...]} ...]
      :liability-set/exclusions {:exclusion/decisions [...] :exclusion/zero-valued-tokens {...}}
      :liability-set/root      <sha-256>    — commitment over version+policy+entries+exclusions}

   A verifier can recompute every field from world state and re-derive :root;
   :root is what the state commitment and the assessment bind."
  [world]
  (let [els (economic-liability-set world)
        {:keys [per-token buckets]} els
        entries (vec
                 (sort-by (comp str first)
                          (for [[tok total] per-token]
                            {:token tok
                             :amount total
                             :buckets (into {}
                                             (for [[bk m] buckets
                                                   :let [amt (get m tok 0)]
                                                   :when (pos? amt)]
                                               [bk amt]))})))
        exclusions (liability-exclusions world)
        policy (liability-policy)
        source (liability-source-summary world)
        stable (stable-token world)
        root-preimage (sha-256-of
                       (concat
                        [(str "liability-set/version:" economic-liability-set-version)
                         (str "policy-root:" (sha-256-of (map pr-str (sort-by pr-str (:policy/included policy)))))
                         (str "policy-exclusions-root:" (sha-256-of (map pr-str (sort-by pr-str (:policy/excluded policy)))))
                         (str "stable-token:" (pr-str (sort-by pr-str (seq stable))))]
                        (for [e entries]
                          (pr-str e))
                        (for [d (:exclusion/decisions exclusions)]
                          (pr-str d))))]
    {:liability-set/version   economic-liability-set-version
     :liability-set/policy    policy
     :liability-set/source    source
     :liability-set/stable-token stable
     :liability-set/entries   entries
     :liability-set/exclusions exclusions
     :liability-set/root      root-preimage}))

