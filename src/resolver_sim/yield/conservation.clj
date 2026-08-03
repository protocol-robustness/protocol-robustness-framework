(ns resolver-sim.yield.conservation
  "Lineage-wide amount conservation verification for deferred yield positions.

   Reconstructs a deferred lineage from the base position, its active deferred
   position, and its archived predecessor records, then reconciles every unit of
   the committed origin obligation against disjoint disposition buckets.

   Returns a structured result (not a bare boolean) so callers can attribute any
   imbalance to specific violations and so that an absent origin is classified
   :not-evaluated rather than falsely passing."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:private tolerance
  "Rounding tolerance in base units for exact-amount reconciliation."
  1)

(defn- non-neg
  "Coerce a possibly-nil amount to a non-negative long."
  [x]
  (long (or x 0)))

(defn lineage-records
  "Collect the deferred lineage records for a position: the active deferred
   position (if any) plus every archived predecessor record. Returns a vector."
  [position]
  (cond-> []
    (:deferred-position position) (conj (:deferred-position position))
    :always (into (vals (:deferred-position-history position {})))))

(defn- archived-records
  "Archived predecessor records for a position (excluding the active deferred)."
  [position]
  (vals (:deferred-position-history position {})))

(defn- origin-amount
  "The committed origin obligation shared by all lineage records.
   Must be uniform across the whole lineage. Missing or divergent obligations
   mean the origin cannot be committed and the lineage is :not-evaluated."
  [records]
  (let [amounts (distinct (keep :position/original-obligation records))]
    (when (= 1 (count amounts))
      (first amounts))))

(defn- archived-amount-mismatch?
  "Any archived record whose closed-from-amount disagrees with the current-amount
   committed in its pre-closure snapshot indicates a tampered archived amount."
  [archived]
  (boolean
   (some (fn [r]
           (let [snapshot (:position/pre-closure-snapshot r)]
             (and (map? snapshot)
                  (not= (non-neg (:position/current-amount snapshot))
                        (non-neg (:position/closed-from-amount r))))))
         archived)))

(defn- reclaim-amount-mismatch?
  "A record closed via claim-deferred must reclaim its full outstanding amount.
   A mismatch here is an inconsistent reclaim/disposition record."
  [archived]
  (boolean
   (some (fn [r]
           (and (= :claim-deferred (:position/closed-by r))
                (not= (non-neg (:position/closed-from-amount r))
                      (non-neg (:position/closed-reclaimed-amount r)))))
         archived)))

(defn verify-lineage-conservation
  "Reconcile a deferred lineage's committed origin amount against the disjoint
   disposition buckets: :fulfilled, :active-deferred, :reversed, :written-down.

   The equation is stated explicitly:
     original-amount = fulfilled + active-deferred + reversed + written-down

   Archived intermediate 'closed-deferred' amounts are deliberately EXCLUDED
   from the reconstructed total: they are money carried forward into successor
   generations, so adding them would count the same deferred amount once as
   active and again as archived. They are reported separately for transparency.

   Classification:
     :evaluated-pass   all terms reconcile and no archival inconsistency
     :evaluated-fail   an imbalance or inconsistent record was found
     :not-evaluated    the lineage commits no origin amount (nothing to verify)"
  [position]
  (let [active (:deferred-position position)
        archived (archived-records position)
        records (lineage-records position)
        original (origin-amount records)
        fulfilled (non-neg (:cumulative-fulfilled position))
        active-deferred (non-neg (:position/current-amount active))
        reversed (+ (reduce + 0 (map #(if (= :claim-deferred (:position/closed-by %))
                                        (non-neg (:position/closed-reclaimed-amount %))
                                        0)
                                     records))
                    (reduce + 0 (map #(if (= :reversed (:position/closed-by %))
                                        (non-neg (:position/reversed-amount %))
                                        0)
                                     records)))
        written-down (+ (non-neg (:principal-impairment position))
                        (non-neg (:haircut-yield position))
                        (reduce + 0 (map #(non-neg (:position/written-down-amount %))
                                         records)))
        closed-deferred (reduce + 0 (map #(non-neg (:position/closed-from-amount %))
                                         archived))
        reconstructed-total (+ fulfilled active-deferred reversed written-down)
        imbalance? (and original
                        (not (<= (- original tolerance)
                                 reconstructed-total
                                 (+ original tolerance))))
        violations (cond-> []
                     (nil? original)
                     (conj {:code :missing-origin-amount
                            :message "Lineage records do not commit a uniform origin obligation"})

                     imbalance?
                     (conj {:code :lineage-amount-imbalance
                            :original-amount original
                            :reconstructed-total reconstructed-total
                            :delta (- reconstructed-total original)})

                     (archived-amount-mismatch? archived)
                     (conj {:code :archived-amount-mismatch
                            :message "Archived closed-from-amount differs from its pre-closure snapshot"})

                     (reclaim-amount-mismatch? archived)
                     (conj {:code :reclaim-amount-mismatch
                            :message "Claim-deferred reclaimed amount differs from deferred outstanding amount"}))]
    {:classification (cond
                       (nil? original) :not-evaluated
                       (seq violations) :evaluated-fail
                       :else :evaluated-pass)
     :original-amount original
     :fulfilled fulfilled
     :active-deferred active-deferred
     :closed-deferred closed-deferred
     :reversed reversed
     :written-down written-down
     :reconstructed-total reconstructed-total
     :equation {:terms [:original-amount
                        :fulfilled :active-deferred
                        :reversed :written-down]
                :relation :equal
                :anti-double-counting
                "closed-deferred (in-flight intermediate) is excluded from the reconstructed total"}
     :violations (vec violations)}))

;; ── Lineage conservation verification report file-artifact ──────────────

(def conservation-report-schema-version
  "Version of the lineage conservation verification report artifact."
  "lineage-conservation-verification.v1")

(def conservation-report-verifier-id
  "Producer identifier for the lineage conservation verification report."
  "lineage-conservation-verifier.v1")

(defn build-conservation-report
  "Build the versioned, content-addressed verification report file-artifact for
   a deferred position's lineage conservation result.

   The report commits the classification, disposition buckets, equation, and
   violations under a content hash and an exact preimage so an independent
   consumer can re-verify it without re-deriving the analysis."
  [position]
  (let [result (verify-lineage-conservation position)
        body (assoc (select-keys result
                                 [:classification :original-amount :fulfilled
                                  :active-deferred :closed-deferred :reversed
                                  :written-down :reconstructed-total :equation
                                  :violations])
                    :schema-version conservation-report-schema-version
                    :artifact/kind :lineage-conservation-verification
                    :artifact/verifier conservation-report-verifier-id)
        report-hash (str "sha256:"
                         (hc/hash-with-intent {:hash/intent :evidence-record}
                                              body))]
    (assoc body
           :report/hash report-hash
           :report/preimage (pr-str body))))

(defn valid-conservation-report?
  "Re-verify a lineage conservation report file-artifact: its schema version,
   artifact kind, verifier id, and content hash must all agree."
  [report]
  (and (map? report)
       (= conservation-report-schema-version (:schema-version report))
       (= :lineage-conservation-verification (:artifact/kind report))
       (= conservation-report-verifier-id (:artifact/verifier report))
       (string? (:report/hash report))
       (string? (:report/preimage report))
       (let [body (dissoc report :report/hash :report/preimage)]
         (= (:report/hash report)
            (str "sha256:"
                 (hc/hash-with-intent {:hash/intent :evidence-record} body))))))
