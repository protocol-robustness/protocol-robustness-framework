(ns resolver-sim.benchmark.packs.partial-fill.evidence
  "Partial-fill benchmark evidence extraction and reconciliation.
   
   Derives benchmark-facing evidence from yield/pro-rata domain artifacts
   without modifying protocol transition code.
   
   Dependency direction: benchmark -> yield/domain artifacts (one-way)."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.partial-fill :as partial-fill]))

;; ── State write-back evidence ─────────────────────────────────────────────

(defn derive-state-write-back
  "Derive authoritative state-write-back evidence from the application artifact
   and the final world state. Does not require modifying liquid-lending
   transition code — this is an additive projection at the benchmark layer.
   
   Returns nil when the application artifact or final world cannot supply
   the required fields."
  [application-artifact final-world]
  (let [participants (:participants application-artifact [])]
    (when (seq participants)
      (mapv
       (fn [participant]
         (let [participant-id (:participant-id participant)
               withdrawn (:withdrawn participant {})
               position-after (:position-after participant)
               before (:before withdrawn 0)
               delta (:delta withdrawn 0)
               after (:after withdrawn 0)
               token (:token withdrawn)
               final-world-withdrawn (get-in final-world
                                             [:yield/withdrawn
                                              token
                                              participant-id]
                                             0)
               final-world-position (get-in final-world
                                            [:yield/positions
                                             participant-id])
               final-world-pos-hash (when final-world-position
                                      (str "sha256:" (hc/domain-hash :state-projection
                                                                     final-world-position)))
               after-hash (:position-after-hash participant)
               deferred (:deferred-position position-after)
               prior-deferred (:deferred-position
                               (:position-before participant))]
           {:participant/id participant-id
            :token token
            :withdrawn
            {:before before
             :delta delta
             :after after
             :final-world-value final-world-withdrawn
             :verified? (= final-world-withdrawn after)}
            :position
            {:before-hash (:position-before-hash participant)
             :after-hash after-hash
             :final-world-position-hash final-world-pos-hash
             :verified? (and after-hash
                             final-world-pos-hash
                             (= after-hash final-world-pos-hash))}
            :deferred-position
            {:prior-closed? (and prior-deferred
                                 (= :closed (:position/status prior-deferred)))
             :prior-current-amount (:position/current-amount prior-deferred)
             :successor-current-amount (:position/current-amount deferred)
             :final-world-current-amount (get-in final-world-position
                                                 [:deferred-position
                                                  :position/current-amount])
             :verified? (and deferred
                             (= (:position/current-amount deferred)
                                (get-in final-world-position
                                        [:deferred-position
                                         :position/current-amount])))}}))
       participants))))

;; ── Propagation/application reference collection ──────────────────────────

(defn collect-application-refs
  "Extract stable references from the applied-pro-rata-propagations world map.
   Returns a vector of reference maps suitable for benchmark evidence inclusion."
  [final-world]
  (let [propagations (get-in final-world [:yield/applied-pro-rata-propagations] {})]
    (mapv (fn [[_ app]]
            {:propagation/id (:propagation-id app)
             :application/hash (:application/hash app)
             :calculation-id (:calculation-id app)
             :outcome-hash (:outcome-hash app)
             :application-order (:application-order app)})
          (sort-by (comp :application-order val) propagations))))

(defn collect-propagation-refs
  "Extract stable references from the pro-rata-propagations world map.
   Returns a vector of reference maps."
  [final-world]
  (let [props (get-in final-world [:yield/pro-rata-propagations] {})]
    (mapv (fn [[_ prop]]
            {:propagation/id (:propagation/id prop)
             :propagation/hash (:propagation/hash prop)
             :propagation/content-hash (:propagation/content-hash prop)
             :calculation-ref (:calculation-ref prop)
             :outcome-ref (:outcome-ref prop)})
          (sort-by (comp :propagation/hash val) props))))

;; ── Semantic commitments root ─────────────────────────────────────────────

(defn semantic-commitments
  "Build the :evidence/semantic-commitments section for a benchmark outcome
   manifest from a final world state and optionally collected evidence maps.

   The partial-fill roots are committed under the :semantic/economic-application
   category, consistent with the generic semantic-commitments concept model
   (decision -> computed economic effect -> application -> resulting state).

   Returns nil when no partial-fill activity occurred."
  [final-world & {:keys [state-write-back-evidence continuity-evidence]}]
  (let [decisions (get-in final-world [:yield/partial-fill-decisions] {})
        prop-refs (collect-propagation-refs final-world)
        app-refs (collect-application-refs final-world)]
    (when (or (seq decisions) (seq prop-refs) (seq app-refs))
      {:semantic/economic-application
       (cond-> {}
         (seq decisions)
         (assoc :partial-fill-decisions-root
                (hc/domain-hash :evidence-collection
                                (vec (sort (map :decision/hash (vals decisions))))))
         (seq prop-refs)
         (assoc :propagation-refs-root
                (hc/domain-hash :evidence-collection
                                (vec (sort (map :propagation/hash (remove nil? prop-refs))))))
         (seq app-refs)
         (assoc :application-refs-root
                (hc/domain-hash :evidence-collection
                                (vec (sort (map :application/hash (remove nil? app-refs))))))
         state-write-back-evidence
         (assoc :state-write-back-root
                (hc/domain-hash :evidence-collection
                                (vec (sort-by :participant/id state-write-back-evidence))))
         continuity-evidence
         (assoc :continuity-root
                (hc/domain-hash :evidence-collection
                                (vec (sort-by :participant/id continuity-evidence)))))})))

;; ── Partial-fill decision verification ────────────────────────────────────
;;
;; The reconciliation below proves two distinct things and labels them
;; separately:
;;
;;   :per-claim-reconciled   filled + deferred == owed (conservation), and
;;   :expected-fill          the fill equals the feasible fill derived from the
;;                           committed capacity and allocation policy.
;;
;; The former proves accounting conservation only; it does not prove the fill
;; was the correct amount given capacity. The latter is asserted only where it
;; is determinable at this projection-only layer (isolated claim, or sufficient
;; unconstrained capacity). A shared pro-rata fill under shortage is reported as
;; :shared-undeterminable rather than falsely claiming exactness.
;;
;; Missing, malformed, non-integral, negative, or contradictory evidence is
;; non-passing.

(def ^:private per-claim-tolerance
  "Permitted per-claim rounding drift in base units (filled+deferred-owed)."
  1)

(def ^:private aggregate-tolerance
  "Permitted aggregate drift across all claims of all decisions. Kept small so
   that one-unit errors on many claims cannot accumulate undetected."
  1)

(defn- decision-claims
  "Canonical claim keys referenced by a decision (from :requested)."
  [d]
  (vec (keys (:requested d))))

(defn- claim-amount
  "A claim amount for a decision by key, or nil when absent."
  [d k]
  (get-in d [:requested k]))

(defn- claim-filled [d k]
  (long (get (:filled d) k 0)))

(defn- claim-deferred [d k]
  (long (get (:deferred d) k 0)))

(defn- decision-total [d field]
  (long (reduce + 0 (vals (get d field {})))))

(defn- amount-violations
  "Non-integral, negative, or out-of-bounds claim amounts."
  [d]
  (into []
        (mapcat
         (fn [k]
           (let [owed (claim-amount d k)
                 filled (claim-filled d k)
                 deferred (claim-deferred d k)
                 bad #(and (some? %) (not (integer? %)))
                 neg #(and (integer? %) (neg? %))]
             (cond-> []
               (bad owed) (conj {:code :non-integral-amount
                                 :decision/id (:decision/id d) :key k :field :owed :value owed})
               (bad filled) (conj {:code :non-integral-amount
                                   :decision/id (:decision/id d) :key k :field :filled :value filled})
               (bad deferred) (conj {:code :non-integral-amount
                                     :decision/id (:decision/id d) :key k :field :deferred :value deferred})
               (neg owed) (conj {:code :negative-amount
                                 :decision/id (:decision/id d) :key k :field :owed :value owed})
               (neg filled) (conj {:code :negative-amount
                                   :decision/id (:decision/id d) :key k :field :filled :value filled})
               (neg deferred) (conj {:code :negative-amount
                                     :decision/id (:decision/id d) :key k :field :deferred :value deferred})
               (and (integer? filled) (> filled owed))
               (conj {:code :bounds-violation
                      :decision/id (:decision/id d) :key k :field :filled :value filled :owed owed})
               (and (integer? deferred) (> deferred owed))
               (conj {:code :bounds-violation
                      :decision/id (:decision/id d) :key k :field :deferred :value deferred :owed owed}))))
         (decision-claims d))))

(defn- per-claim-reconciliation-violations
  "Conservation: filled + deferred == owed, per claim, within tolerance.

   Reads the authoritative :allocation-rows breakdown when present and falls
   back to the top-level requested/filled/deferred maps otherwise."
  [d]
  (let [rows (get-in d [:evidence :allocation-rows])]
    (if (seq rows)
      (into []
            (mapcat
             (fn [r]
               (let [owed (long (:owed r 0))
                     filled (long (:filled r 0))
                     deferred (long (:deferred r 0))
                     delta (- (+ filled deferred) owed)]
                 (when (or (< delta (- per-claim-tolerance))
                           (> delta per-claim-tolerance))
                   [{:code :per-claim-inexact
                     :decision/id (:decision/id d)
                     :key (:key r)
                     :owed owed
                     :filled filled
                     :deferred deferred
                     :delta delta}])))
             rows))
      (into []
            (mapcat
             (fn [k]
               (let [owed (long (or (claim-amount d k) 0))
                     filled (claim-filled d k)
                     deferred (claim-deferred d k)
                     delta (- (+ filled deferred) owed)]
                 (when (or (< delta (- per-claim-tolerance))
                           (> delta per-claim-tolerance))
                   [{:code :per-claim-inexact
                     :decision/id (:decision/id d)
                     :key k
                     :owed owed
                     :filled filled
                     :deferred deferred
                     :delta delta}])))
             (decision-claims d))))))

(defn- capacity-violations
  "Under-capacity and available-liquidity sanity, derived rather than trusted."
  [d]
  (let [ev (:evidence d)
        avail (get-in ev [:available-liquidity])
        owed-total (decision-total d :requested)
        filled-total (decision-total d :filled)
        deferred-total (decision-total d :deferred)
        derived-shortage (- owed-total filled-total)]
    (cond-> []
      (nil? ev)
      (conj {:code :missing-decision-evidence :decision/id (:decision/id d)})

      (and (some? avail) (neg? avail))
      (conj {:code :negative-available-liquidity
             :decision/id (:decision/id d) :available-liquidity avail})

      (and (some? avail) (> filled-total avail))
      (conj {:code :over-capacity-fill
             :decision/id (:decision/id d)
             :filled-total filled-total
             :available-liquidity (long avail)})

      ;; A supplied positive :shortage must equal the derived shortage.
      (and (some? ev) (some? (:shortage ev))
           (not= (long (:shortage ev)) derived-shortage))
      (conj {:code :shortage-inconsistent
             :decision/id (:decision/id d)
             :supplied-shortage (long (:shortage ev))
             :derived-shortage derived-shortage})

      ;; shortage must equal total deferred in an exact fill.
      (not= derived-shortage deferred-total)
      (conj {:code :shortage-deferred-mismatch
             :decision/id (:decision/id d)
             :derived-shortage derived-shortage
             :deferred-total deferred-total}))))

(defn- derived-classification
  "Classification derived from amounts. :full-fill iff deferred == 0;
   :partial-fill iff some claim is partially filled (0 < filled < owed)."
  [d]
  (let [deferred-zero? (every? zero? (vals (:deferred d)))
        any-partial? (some (fn [k]
                             (let [owed (long (or (claim-amount d k) 0))
                                   filled (claim-filled d k)]
                               (and (pos? owed) (< 0 filled owed))))
                           (decision-claims d))]
    (cond deferred-zero? :full-fill
          any-partial? :partial-fill
          :else :indeterminate)))

(defn- classification-violations
  "Settlement-mode must agree with the derived classification."
  [d]
  (let [derived (derived-classification d)
        mode (:settlement-mode d)]
    (when (and (not= :indeterminate derived)
               (not= derived mode))
      [{:code :classification-mismatch
        :decision/id (:decision/id d)
        :derived derived
        :settlement-mode mode}])))

(defn- expected-fill-violations
  "Derive the feasible fill where determinable and compare. Returns
   {:mode ... :violations [...]}.
     :isolated-exact            single unconstrained claim; expected = min(owed, available)
     :full-capacity-exact       available >= owed and no cap below owed; expected = full fill
     :shared-undeterminable     shared pro-rata under shortage; exactness not derivable here"
  [d]
  (let [ev (:evidence d)
        avail (get-in ev [:available-liquidity])
        claims (decision-claims d)
        owed-total (decision-total d :requested)
        rows (get ev [:allocation-rows] [])
        cap-below-owed? (some (fn [k]
                                ;; a per-row cap that constrains the fill below owed
                                (some (fn [r]
                                        (and (= (:key r) k)
                                             (some? (:cap r))
                                             (< (:cap r) (long (or (claim-amount d k) 0)))))
                                      rows))
                              claims)]
    (cond
      (nil? ev)
      {:mode :none :violations []}

      (and (= 1 (count claims))
           (some? avail)
           (not cap-below-owed?))
      (let [k (first claims)
            owed (long (or (claim-amount d k) 0))
            expected (min owed (long avail))
            filled (claim-filled d k)]
        {:mode :isolated-exact
         :violations (when (not= filled expected)
                       [{:code :expected-fill-mismatch
                         :decision/id (:decision/id d)
                         :key k
                         :expected-fill expected
                         :actual-fill filled
                         :reason :isolated-claim-exact}])})

      (and (some? avail)
           (>= (long avail) owed-total)
           (not cap-below-owed?))
      (let [violations (into []
                             (mapcat
                              (fn [k]
                                (let [owed (long (or (claim-amount d k) 0))
                                      filled (claim-filled d k)]
                                  (when (not= filled owed)
                                    [{:code :expected-fill-mismatch
                                      :decision/id (:decision/id d)
                                      :key k
                                      :expected-fill owed
                                      :actual-fill filled
                                      :reason :sufficient-capacity-full-fill}])))
                              claims))]
        {:mode :full-capacity-exact
         :violations violations})

      :else
      {:mode :shared-undeterminable :violations []})))

(defn partial-fill-decisions-root
  "Recompute the :partial-fill-decisions-root commitment over the sorted
   decision hashes in a world state, or nil when no decisions are present.

   When :scope is supplied the root is bound to that case/run/parameter scope,
   so a decision set transplanted from another scope cannot reproduce the same
   root. The unscoped form is preserved for compatibility."
  ([final-world] (partial-fill-decisions-root final-world {}))
  ([final-world {:keys [scope]}]
   (let [hashes (mapv :decision/hash
                      (vals (get-in final-world [:yield/partial-fill-decisions] {})))]
     (when (seq hashes)
       (hc/domain-hash :evidence-collection
                       (if scope
                         {:scope scope :decision-hashes (vec (sort hashes))}
                         (vec (sort hashes))))))))

(defn- membership-violations
  "Bind membership, not only contents: the present claim set matches
   :expected-claims (no missing or unexpected claims); the decision count
   matches :expected-count when supplied; and when :unique-claims? is set, each
   claim may appear in at most one decision.

   Cross-decision repetition of a claim is permitted by default because a
   deferred lineage legitimately settles the same position across multiple
   rounds; :unique-claims? opts into a single-settlement membership contract."
  [decisions & {:keys [expected-claims expected-count unique-claims?]}]
  (let [all-claims (mapcat decision-claims decisions)
        present (set all-claims)
        expected-provided? (some? expected-claims)
        duplicates (when unique-claims?
                     (->> all-claims
                          frequencies
                          (keep (fn [[k c]] (when (> c 1) k)))
                          vec))
        expected (set (or expected-claims []))
        missing (vec (sort (remove present expected)))
        unexpected (vec (sort (remove expected present)))]
    (cond-> []
      (seq duplicates)
      (conj {:code :duplicate-claim :claims duplicates})

      (and expected-provided? (seq missing))
      (conj {:code :missing-claim :claims missing})

      (and expected-provided? (seq unexpected))
      (conj {:code :unexpected-claim :claims unexpected})

      (and (some? expected-count) (not= expected-count (count decisions)))
      (conj {:code :decision-count-mismatch
             :expected-count expected-count
             :actual-count (count decisions)}))))

(defn verify-partial-fill-decisions
  "Projection-only verifier over the committed partial-fill decisions and their
   :partial-fill-decisions-root.

   This verifier establishes two distinct properties:

     - :decision-integrity — every :decision/hash validates, the root recomputes
       from the decision set, membership is bound (no duplicates, no missing or
       unexpected claims), and when :case-scope is supplied the committed root
       is bound to that case/run/parameter scope. This proves commitment and
       integrity of the bundle; it is NOT decidability (a unique-allocation
       ambiguity witness is out of scope for this projection layer).

     - accounting exactness — per-claim reconciliation (filled + deferred ==
       owed) and aggregate drift are bounded; where the feasible fill is
       derivable (:isolated-exact, :full-capacity-exact) the fill must equal it,
       otherwise :shared-undeterminable is reported honestly.

   All redundant fields (:shortage, :settlement-mode) are derived and cross
   checked rather than trusted. Missing or malformed evidence is non-passing.

   Returns a structured result."
  [final-world & {:keys [committed-root case-scope expected-claims expected-count unique-claims?]}]
  (let [decisions (vals (get-in final-world [:yield/partial-fill-decisions] {}))
        scoped? (some? case-scope)
        recomputed (partial-fill-decisions-root final-world
                                                (when case-scope {:scope case-scope}))
        unscoped-root (partial-fill-decisions-root final-world)
        content-root-ok? (or (nil? committed-root)
                             (= committed-root unscoped-root))
        scope-root-ok? (or (not scoped?)
                           (and (some? recomputed) (= committed-root recomputed)))
        root-ok? (or (nil? committed-root)
                     (if scoped? scope-root-ok? content-root-ok?))
        hash-ok? (every? (fn [d]
                           (and (string? (:decision/hash d))
                                (try (partial-fill/decision-hash-valid? d)
                                     (catch Exception _ false))))
                         decisions)
        amount-violations (into [] (mapcat amount-violations decisions))
        reconcile-violations (into [] (mapcat per-claim-reconciliation-violations decisions))
        capacity-violations (into [] (mapcat capacity-violations decisions))
        classification-violations (into [] (mapcat classification-violations decisions))
        expected-fill-results (mapv expected-fill-violations decisions)
        expected-fill-violations (into [] (mapcat :violations expected-fill-results))
        expected-fill-modes (set (map :mode expected-fill-results))
        membership-violations (membership-violations decisions
                                                     :expected-claims expected-claims
                                                     :expected-count expected-count
                                                     :unique-claims? unique-claims?)
        sum-filled (reduce + 0 (map #(decision-total % :filled) decisions))
        sum-deferred (reduce + 0 (map #(decision-total % :deferred) decisions))
        sum-owed (reduce + 0 (map #(decision-total % :requested) decisions))
        aggregate-drift (- (+ sum-filled sum-deferred) sum-owed)
        aggregate-ok? (<= (Math/abs (long aggregate-drift)) aggregate-tolerance)
        violations (vec (concat
                         (when-not root-ok?
                           (if scoped?
                             [{:code :scope-root-mismatch
                               :committed-root committed-root
                               :recomputed-root recomputed
                               :case-scope case-scope}]
                             [{:code :root-mismatch
                               :committed-root committed-root
                               :recomputed-root unscoped-root}]))
                         (when-not hash-ok?
                           [{:code :invalid-decision-hash}])
                         membership-violations
                         amount-violations
                         reconcile-violations
                         capacity-violations
                         classification-violations
                         expected-fill-violations
                         (when-not aggregate-ok?
                           [{:code :aggregate-drift
                             :sum-filled sum-filled
                             :sum-deferred sum-deferred
                             :sum-owed sum-owed
                             :delta aggregate-drift}])))]
    {:classification (cond
                       (empty? decisions) :not-evaluated
                       (seq violations) :evaluated-fail
                       :else :evaluated-pass)
     :decision-count (count decisions)
     :claim-count (count (distinct (mapcat decision-claims decisions)))
     :decision-integrity? (boolean (and (seq decisions)
                                        (some? recomputed)
                                        root-ok?
                                        hash-ok?
                                        (empty? membership-violations)))
     :root-committed committed-root
     :root-recomputed recomputed
     :expected-fill-mode (cond
                           (empty? expected-fill-modes) :none
                           (contains? expected-fill-modes :shared-undeterminable) :shared-undeterminable
                           :else (first expected-fill-modes))
     :aggregate-drift (long aggregate-drift)
     :violations (vec violations)}))

;; ── Verification report file-artifact ────────────────────────────────────
;;
;; The verifier result is promoted to a versioned, content-addressed
;; file-artifact so a downstream consumer can store it and independently
;; re-verify it without re-deriving the analysis.

(def verification-report-schema-version
  "Version of the partial-fill decisions verification report artifact."
  "partial-fill-decisions-verification.v1")

(def verification-report-verifier-id
  "Producer identifier for the partial-fill decisions verification report."
  "partial-fill-decisions-verifier.v1")

(defn build-partial-fill-verification-report
  "Build the versioned, content-addressed verification report file-artifact for
   the partial-fill decisions in a world.

   The report commits the verifier's classification, decision-integrity flag,
   expected-fill mode, aggregate drift, and violations under a content hash and
   an exact preimage, so an independent consumer can re-verify it without
   re-running the analysis. Returns nil when there are no decisions to verify."
  ([final-world] (build-partial-fill-verification-report final-world {}))
  ([final-world {:keys [committed-root case-scope expected-claims expected-count
                        unique-claims?]}]
   (let [result (verify-partial-fill-decisions final-world
                                               :committed-root committed-root
                                               :case-scope case-scope
                                               :expected-claims expected-claims
                                               :expected-count expected-count
                                               :unique-claims? unique-claims?)
         body (assoc (select-keys result
                                  [:classification :decision-count :claim-count
                                   :decision-integrity? :root-committed :root-recomputed
                                   :expected-fill-mode :aggregate-drift :violations])
                     :schema-version verification-report-schema-version
                     :artifact/kind :partial-fill-decisions-verification
                     :artifact/verifier verification-report-verifier-id)
         report-hash (str "sha256:"
                          (hc/hash-with-intent {:hash/intent :evidence-record}
                                               body))]
     (assoc body
            :report/hash report-hash
            :report/preimage (pr-str body)))))

(defn valid-partial-fill-verification-report?
  "Re-verify a partial-fill verification report file-artifact: its schema
   version, artifact kind, verifier id, and content hash must all agree."
  [report]
  (and (map? report)
       (= verification-report-schema-version (:schema-version report))
       (= :partial-fill-decisions-verification (:artifact/kind report))
       (= verification-report-verifier-id (:artifact/verifier report))
       (string? (:report/hash report))
       (string? (:report/preimage report))
       (let [body (dissoc report :report/hash :report/preimage)]
         (= (:report/hash report)
            (str "sha256:"
                 (hc/hash-with-intent {:hash/intent :evidence-record} body))))))

;; ── Continuity evidence ───────────────────────────────────────────────────

(defn derive-continuity-evidence
  "Derive precondition-continuity evidence from the final world state.
   
   Verifies that the position commitment in each application artifact
   is still consistent with the final world state — proving that a
   downstream consumer could have read the updated values.
   
   next-precondition-consumed is conditional (:not-observed when
   no subsequent propagation references the position)."
  [final-world application-refs]
  (let [propagations (get-in final-world [:yield/applied-pro-rata-propagations] {})]
    (mapv
     (fn [app-ref]
       (let [app (get propagations (:propagation/id app-ref))
             participants (:participants app [])]
         (mapv
          (fn [participant]
            (let [pid (:participant-id participant)
                  after-hash (:position-after-hash participant)
                  final-pos (get-in final-world [:yield/positions pid])
                  final-pos-hash (when final-pos
                                   (str "sha256:" (hc/domain-hash :state-projection
                                                                  final-pos)))
                  current-amount (get-in participant
                                         [:position-after
                                          :deferred-position
                                          :position/current-amount])
                  final-amount (get-in final-world
                                       [:yield/positions
                                        pid
                                        :deferred-position
                                        :position/current-amount])]
              {:participant/id pid
               :propagation/id (:propagation/id app-ref)
               :expected-position-hash after-hash
               :current-position-hash final-pos-hash
               :expected-current-amount current-amount
               :current-current-amount final-amount
               :matches? (and after-hash
                              final-pos-hash
                              (= after-hash final-pos-hash))
               :amount-continuous? (or (nil? current-amount)
                                       (= current-amount final-amount))}))
          participants)))
     application-refs)))

;; ── Application evidence ladder ───────────────────────────────────────────

(defn application-evidence-ladder
  "Build a six-level application evidence ladder from available artifacts.
   
   Levels:
     1. allocation-calculated  — partial-fill decision hash present
     2. application-claimed   — propagation artifact with :apparent-application
     3. accounting-emitted    — accounting entry set hash present and balanced
     4. state-written-back    — state write-back derived and verified
     5. continuity-consumed   — next precondition position hash matches
     6. outcome-committed     — application ref included in outcome commitments
   
   Levels 4 and 5 may be :not-observed when the final world state does not
   contain the required propagation or application artifacts."
  [final-world & {:keys [state-write-back-evidence continuity-evidence outcome-hash]}]
  (let [propagations (get-in final-world [:yield/pro-rata-propagations] {})
        applications (get-in final-world [:yield/applied-pro-rata-propagations] {})
        decisions (get-in final-world [:yield/partial-fill-decisions] {})]
    (mapv
     (fn [[prop-id prop]]
       (let [app (get applications prop-id)
             decision (get decisions (:calculation-ref prop))
             participants (:participants prop [])
             level-status
             (fn [level status & {:keys [reason]}]
               (cond-> {:level level :status (name status)}
                 reason (assoc :reason reason)))]
         {:propagation/id prop-id
          :levels
          [(level-status :allocation-calculated
                         (if (some? decision) :verified :failed)
                         :reason (when (nil? decision)
                                   "decision artifact not found in world"))

           (level-status :application-claimed
                         (cond
                           (nil? app) :failed
                           (some? (:applications prop)) :verified
                           :else :inconclusive))

           (level-status :accounting-emitted
                         (cond
                           (nil? (:accounting-entry-set-hash prop)) :failed
                           (some? (:accounting-entries prop)) :verified
                           :else :inconclusive))

           (let [wb (some-> state-write-back-evidence
                            (->> (filter #(= prop-id (:propagation/id %)))
                                 first))]
             (level-status :state-written-back
                           (cond
                             (nil? wb) :not-observed
                             (true? (:verified? wb)) :verified
                             :else :failed)))

           (let [ce (some->> continuity-evidence
                             (mapcat identity)
                             (filter #(= prop-id (:propagation/id %)))
                             seq)]
             (level-status :continuity-consumed
                           (cond
                             (nil? ce) :not-observed
                             (every? :matches? ce) :verified
                             (some (complement :matches?) ce) :failed
                             :else :inconclusive)))

           (level-status :outcome-committed
                         (if (some? outcome-hash) :verified :not-observed))]}))
     propagations)))
