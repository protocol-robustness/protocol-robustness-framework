(ns resolver-sim.risk.projection
  "risk-projection.v1 — generic loss-bearing exposure projection.

   The initial primitive is loss-bearing exposure, NOT probabilistic VaR:

     For an exact source state, what value is presently exposed to loss,
     and through which risk domains?

   The kernel knows about exact source/state roots, valuation and currentness
   bases, integer exposure amounts, per-row provenance-bound subjects, and
   open-ontology domain attribution. It knows NOTHING about pro-rata claims,
   escrows, buyers, sellers, or resolvers — those live in producers.

   Per-row exposure phases (exact integers, same unit):

     :exposure/current — exposure as of the exact source state (at rest).
     :exposure/after   — candidate exposure after the proposed operation.
     :exposure/peak    — the EXACT maximum exposure of THAT SUBJECT at any
                         point of the operation path (>= max(current, after)).
                         For at-rest projections peak = current = after.

   Aggregate exposure is deliberately NOT an exact simultaneous peak:

     :exposure/conservative-peak (summary) = the SUM of per-row individual
     peaks. It is an upper bound on the exact aggregate exposure at any path
     point, not the exact path-level peak:

         actual-path-peak <= sum-of-individual-peaks = conservative-peak

     Exact path-level aggregate peak requires simultaneous per-stage row data
     and is deliberately deferred (a future, compatible measure). V1 never
     labels its aggregate peak as exact.

   Live-consumption state binding:

     A projection with :time-basis {:basis :state-derived} is EXACT-STATE-BOUND:
     its :risk-projection/source-root IS the exact state it derives from, and
     its :exposure/current is a live (not historical) reading of that state.
     Live admission evidence (future authoritative gate) must satisfy
     candidate-state-root == :risk-projection/source-root on an
     exact-state-bound projection. A projection with :time-basis {:basis :as-of}
     is observational/historical evidence and must never be used as live
     admission evidence.

   Non-additivity: domain attribution is MEMBERSHIP. A row's exposure is
   attributed to every listed domain in full; amounts are never summed across
   DIFFERENT domains. Only exposure attributed to the SAME exact domain
   identity (see risk-limit-evaluation) is aggregateable, and only because the
   authoritative policy explicitly commits to that identity."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "risk-projection.v1")

(def ^:private sha256-ref-re #"sha256:[0-9a-f]{64}")

(def projection-fields
  "Closed field set of risk-projection.v1."
  [:risk-projection/schema
   :risk-projection/source-root
   :risk-projection/valuation-basis-root
   :risk-projection/unit-root
   :risk-projection/time-basis
   :risk-projection/exposures
   :risk-projection/summary
   :risk-projection/root])

(def exposure-fields
  "Closed field set of one exposure row."
  [:exposure/id
   :exposure/subject-root
   :exposure/current
   :exposure/after
   :exposure/peak
   :exposure/domains])

(def domain-fields
  "Closed field set of one domain-attribution entry. `:risk/member` is
   optional; when absent the subject root is the member at evaluation time.
   Canonical form: an entry whose :risk/member equals the row's
   :exposure/subject-root is normalized to the omitted form, so explicit
   member == subject root and omitted member are SEMANTICALLY IDENTICAL and
   produce the same committed root."
  [:risk/domain :risk/member])

(def time-basis-fields
  "Closed field set of the time/currentness basis. `:basis` is :state-derived
   (exact-state-bound: currentness follows exactly from the source state root)
   or :as-of (observational, with an integer epoch coordinate)."
  [:basis :at])

(defn exact-state-bound?
  "True when the projection is exact-state-bound (live evidence): its
   :risk-projection/source-root IS the exact state its exposure derives from.
   Only such projections may be used as live admission evidence."
  [p]
  (= :state-derived (get-in p [:risk-projection/time-basis :basis])))

(defn- canonical-domain-entries
  "Canonicalize domain entries for one row: an explicit :risk/member equal to
   the row's subject root is normalized to the omitted form (semantically
   identical), entries are sorted by (domain, member), and exact duplicates
   (identical domain + identical member) are collapsed to one entry.
   Canonicalization is deterministic — identical input produces identical
   committed content."
  [subject-root domains]
  (->> domains
       (map (fn [d]
              (let [member (:risk/member d)]
                (cond-> {:risk/domain (:risk/domain d)}
                  (and member (not= member subject-root)) (assoc :risk/member member)))))
       (sort-by (juxt :risk/domain :risk/member))
       distinct
       vec))

(defn exposure-row
  "Build one validated, canonicalized exposure row. `domains` is a vector of
   {:risk/domain s :risk/member s?}; entries are canonicalized (member equal
   to the subject root collapses to omitted; exact duplicates collapse) and
   sorted. current/after/peak must be non-negative integers and
   peak >= max(current, after)."
  [row]
  (let [id (:exposure/id row)
        root (:exposure/subject-root row)
        current (:exposure/current row)
        after (:exposure/after row)
        peak (:exposure/peak row)
        domains (:exposure/domains row)]
    (when-not (and (string? id) (seq id))
      (throw (ex-info "exposure row requires :exposure/id string" {:id id})))
    (when-not (and (string? root) (re-matches sha256-ref-re root))
      (throw (ex-info "exposure row requires :exposure/subject-root sha256 ref"
                      {:subject/root root})))
    (when-not (and (int? current) (int? after) (int? peak)
                   (not (neg? current)) (not (neg? after)) (not (neg? peak))
                   (>= peak (max current after)))
      (throw (ex-info "invalid exposure phase amounts"
                      {:current current :after after :peak peak})))
    (when-not (vector? domains)
      (throw (ex-info "exposure row :exposure/domains must be a vector" {})))
    (doseq [d domains]
      (when-not (and (map? d) (every? (set domain-fields) (keys d))
                     (string? (:risk/domain d)) (seq (:risk/domain d)))
        (throw (ex-info "invalid domain attribution entry" {:entry d})))
      (when-some [m (:risk/member d)]
        (when-not (and (string? m) (seq m))
          (throw (ex-info "invalid :risk/member" {:member m})))))
    {:exposure/id id
     :exposure/subject-root root
     :exposure/current current
     :exposure/after after
     :exposure/peak peak
     :exposure/domains (canonical-domain-entries root domains)}))

(defn- summary
  [rows]
  {:exposure/current (reduce + 0 (map :exposure/current rows))
   :exposure/after (reduce + 0 (map :exposure/after rows))
   :exposure/conservative-peak (reduce + 0 (map :exposure/peak rows))})

(defn projection-root
  "Domain-separated canonical identity over the semantic fields. The summary
   is derived (never input); its values are committed as part of the body."
  [p]
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-risk-projection-v1
                   {:schema-version (:risk-projection/schema p)
                    :source/root (:risk-projection/source-root p)
                    :valuation-basis/root (:risk-projection/valuation-basis-root p)
                    :unit/root (:risk-projection/unit-root p)
                    :time-basis (:risk-projection/time-basis p)
                    :exposures (:risk-projection/exposures p)
                    :summary (:risk-projection/summary p)})))

(defn projection
  "Build a validated risk-projection.v1. `rows` is a vector of row inputs for
   exposure-row; `time-basis` is {:basis :state-derived} (exact-state-bound,
   live) or {:basis :as-of :at <int>} (observational). All identity roots are
   sha256 refs. The root is always derived, never input."
  [{:keys [time-basis rows] :as args}]
  (let [src (:source/root args)
        vb (:valuation-basis/root args)
        unit (:unit/root args)]
    (when-not (and (string? src) (re-matches sha256-ref-re src))
      (throw (ex-info "projection requires :risk-projection/source-root sha256 ref"
                      {:source/root src})))
    (when-not (and (string? vb) (re-matches sha256-ref-re vb))
      (throw (ex-info "projection requires :risk-projection/valuation-basis-root sha256 ref"
                      {:valuation-basis/root vb})))
    (when-not (and (string? unit) (re-matches sha256-ref-re unit))
      (throw (ex-info "projection requires :risk-projection/unit-root sha256 ref"
                      {:unit/root unit})))
    (when-not (and (map? time-basis) (every? (set time-basis-fields) (keys time-basis))
                   (contains? #{:state-derived :as-of} (:basis time-basis)))
      (throw (ex-info "invalid time basis" {:time-basis time-basis})))
    (when (= :as-of (:basis time-basis))
      (when-not (int? (:at time-basis))
        (throw (ex-info ":as-of time basis requires integer :at" {:time-basis time-basis}))))
    (when-not (vector? rows)
      (throw (ex-info "rows must be a vector" {})))
    (let [ids (map :exposure/id rows)]
      (when-not (= (count ids) (count (distinct ids)))
        (throw (ex-info "duplicate :exposure/id" {}))))
    (let [rs (mapv exposure-row rows)
          sm (summary rs)
          base {:risk-projection/schema schema-version
                :risk-projection/source-root src
                :risk-projection/valuation-basis-root vb
                :risk-projection/unit-root unit
                :risk-projection/time-basis time-basis
                :risk-projection/exposures rs
                :risk-projection/summary sm}]
      (assoc base :risk-projection/root (projection-root base)))))

(defn validate-projection
  "Strict fail-closed closed-shape validation: unknown keys, missing keys,
   malformed roots, non-integer/negative amounts, bad phase ordering, unknown
   time basis, non-canonical domain entries, summary that does not match row
   totals. Returns {:valid? :errors}."
  [p]
  (let [errors (atom [])
        report! (fn [msg] (swap! errors conj msg))]
    (if-not (map? p)
      {:valid? false :errors ["not a map"]}
      (do
        (doseq [k (keys p)]
          (when-not (contains? (set projection-fields) k)
            (report! (str "unknown key " k))))
        (doseq [k projection-fields]
          (when-not (contains? p k)
            (report! (str "missing key " k))))
        (when-not (= schema-version (:risk-projection/schema p))
          (report! "wrong schema-version"))
        (doseq [k [:risk-projection/source-root :risk-projection/valuation-basis-root
                   :risk-projection/unit-root]]
          (when-not (re-matches sha256-ref-re (str (get p k)))
            (report! (str "malformed root " k))))
        (let [tb (:risk-projection/time-basis p)]
          (when-not (and (map? tb) (every? (set time-basis-fields) (keys tb))
                         (contains? #{:state-derived :as-of} (:basis tb)))
            (report! "invalid time basis"))
          (when (and (= :as-of (:basis tb)) (not (int? (:at tb))))
            (report! ":as-of time basis requires integer :at")))
        (let [rows (:risk-projection/exposures p)]
          (if-not (vector? rows)
            (report! ":risk-projection/exposures must be a vector")
            (doseq [r rows]
              (cond
                (not (map? r)) (report! "exposure row not a map")
                (not (every? (set exposure-fields) (keys r)))
                (report! (str "exposure row keys not closed: " (:exposure/id r)))
                (not (string? (:exposure/id r))) (report! "exposure row :exposure/id not string")
                (not (re-matches sha256-ref-re (str (:exposure/subject-root r))))
                (report! (str "exposure row malformed :exposure/subject-root " (:exposure/id r)))
                (let [{:exposure/keys [current after peak]} r]
                  (not (and (int? current) (int? after) (int? peak)
                            (not (neg? current)) (not (neg? after)) (not (neg? peak))
                            (>= peak (max current after)))))
                (report! (str "exposure row invalid phase amounts " (:exposure/id r)))
                (not= (:exposure/domains r)
                      (canonical-domain-entries (:exposure/subject-root r)
                                                (:exposure/domains r)))
                (report! (str "exposure row domain entries not canonical " (:exposure/id r)))
                :else
                (doseq [d (:exposure/domains r)]
                  (when-not (and (map? d) (every? (set domain-fields) (keys d))
                                 (string? (:risk/domain d)) (seq (:risk/domain d)))
                    (report! (str "exposure row invalid domain entry " (:exposure/id r))))))))
          (when (vector? rows)
            (let [ids (map :exposure/id rows)]
              (when-not (= (count ids) (count (distinct ids)))
                (report! "duplicate :exposure/id")))))
        (let [rs (:risk-projection/exposures p)
              sm (:risk-projection/summary p)]
          (when (and (vector? rs) (map? sm))
            (when-not (and (every? (set #{:exposure/current :exposure/after
                                          :exposure/conservative-peak}) (keys sm))
                           (= (:exposure/current sm) (reduce + 0 (map :exposure/current rs)))
                           (= (:exposure/after sm) (reduce + 0 (map :exposure/after rs)))
                           (= (:exposure/conservative-peak sm) (reduce + 0 (map :exposure/peak rs))))
              (report! "summary does not match row totals"))))
        {:valid? (empty? @errors) :errors @errors}))))

(defn projection-valid?
  [p]
  (:valid? (validate-projection p)))

(defn verify-root
  "Recompute the projection root from semantic fields and compare."
  [p]
  (cond
    (not (projection-valid? p)) {:status :fail :reason :invalid-projection}
    (not= (:risk-projection/root p) (projection-root p))
    {:status :fail :reason :root-mismatch}
    :else {:status :pass}))

(defn at-rest-projection
  "Convenience: build an at-rest, exact-state-bound projection (no candidate
   operation), where current = after = peak = the supplied per-subject amounts."
  [base-args amounts]
  (projection
   (assoc base-args
          :rows (mapv (fn [[subject-root amount]]
                        {:exposure/id (str "exp-" (subs subject-root 7 23))
                         :exposure/subject-root subject-root
                         :exposure/current amount
                         :exposure/after amount
                         :exposure/peak amount
                         :exposure/domains []})
                      amounts))))