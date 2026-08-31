(ns resolver-sim.benchmark.research-definition
  "Researcher-authored study sources and their frozen, content-addressed
  research-definition.v1 projections.

  A source is intentionally ergonomic and mutable: it may name a template and
  vary integer parameters. Freezing resolves that authoring syntax into a
  closed definition, deterministic dense axes, and roots suitable for
  independent reproduction.

  Scope rule (see hop-scope in the replay layer): a small integer or local
  identifier is meaningful only inside the exact scope that gives it
  uniqueness. A case key `7` is not \"case 7\" universally — it is case 7 within
  a particular case axis root. Every compiled integer-key space (case, measure,
  change) is scoped; a bare key has no portable meaning. Use `scoped-key` and
  `axis-member` to bind and resolve keys against a committed axis.

  Measure semantics: a `:measure/observation` is a *named observation
  definition* owned by the execution/resolver, not a fully resolved canonical
  semantic root in this slice. `requirement-margin` arithmetic is valid only
  when observed and requirement are in the same canonical quantity domain (here:
  equal integer `:unit`). This slice performs exact integer + exact unit-ID
  arithmetic only; it is not general dimensional analysis."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const source-schema "research-source.v1")
(def ^:const definition-schema "research-definition.v1")
(def ^:const axis-schema "research-axis.v1")
(def ^:const diff-schema "research-definition-diff.v1")
(def ^:const result-matrix-schema "research-result-matrix.v1")
(def ^:const requirement-margin-kind :requirement-margin.v1)

(defn- sha256-root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- non-empty-qualified-keyword? [value]
  (and (keyword? value) (namespace value)))

(defn- integer-domain? [domain]
  (and (map? domain)
       (= :integer (:kind domain))
       (keyword? (:unit domain))))

(defn- sorted-entries [m]
  (sort-by (comp str key) m))

(defn- cartesian-parameter-values [vary]
  (reduce (fn [rows [parameter values]]
            (for [row rows
                  value values]
              (assoc row parameter value)))
          [{}]
          (sorted-entries vary)))

(defn- canonical-cases [vary]
  (mapv (fn [index parameters]
          {:case/id (keyword "generated" (str "case-" index))
           :case/parameters parameters})
        (range)
        (cartesian-parameter-values vary)))

(defn requirement-margin
  "Evaluate an exact, unit-safe requirement margin.

   `observed` and `requirement` must be integer quantities in the same measure
   domain — here meaning equal integer `:unit`, which is the canonical quantity
   domain for this slice; it is not general dimensional analysis. Positive
   values mean headroom, zero is the boundary, and negative values mean deficit.
   `:at-most` normalizes direction so positive still means satisfying the
   requirement. `observed` is the execution/resolver-owned value of the named
   `:measure/observation`; the definition does not itself resolve that
   observation's meaning."
  [{:keys [observed requirement domain satisfying-direction]}]
  (when-not (integer-domain? domain)
    (throw (ex-info "Requirement margin requires an integer measure domain"
                    {:domain domain})))
  (when-not (and (integer? observed) (integer? requirement))
    (throw (ex-info "Requirement margin values must be integers"
                    {:observed observed :requirement requirement})))
  (case satisfying-direction
    :at-least (- observed requirement)
    :at-most (- requirement observed)
    (throw (ex-info "Unsupported requirement-margin direction"
                    {:satisfying-direction satisfying-direction}))))

(defn classify-margin [margin]
  (cond
    (pos? margin) {:measure/status :satisfied :measure/value margin :measure/headroom margin}
    (neg? margin) {:measure/status :deficit :measure/value margin :measure/deficit (- margin)}
    :else {:measure/status :at-boundary :measure/value 0N}))

(defn- validate-measure! [measure]
  (when-not (and (non-empty-qualified-keyword? (:measure/id measure))
                 (= requirement-margin-kind (:measure/kind measure))
                 (integer-domain? (:measure/domain measure))
                 (map? (:measure/observation measure))
                 (non-empty-qualified-keyword? (get-in measure [:measure/observation :observation/id]))
                 (integer? (get-in measure [:measure/requirement :value]))
                 (= (:unit (:measure/domain measure))
                    (get-in measure [:measure/requirement :unit]))
                 (contains? #{:at-least :at-most} (:measure/satisfying-direction measure)))
    (throw (ex-info "Invalid requirement-margin measure"
                    {:measure measure}))))

(defn- resolve-source [source {:keys [templates]}]
  (when-not (= source-schema (:artifact/schema source))
    (throw (ex-info "Unsupported research source schema" {:source source})))
  (let [template-id (:research/from source)
        template (when template-id (get templates template-id))]
    (when (and template-id (nil? template))
      (throw (ex-info "Research template is unavailable" {:template template-id})))
    (merge template (dissoc source :research/from :artifact/schema))))

(defn- validate-source! [source]
  (when-not (and (non-empty-qualified-keyword? (:research/id source))
                 (string? (:research/title source))
                 (string? (:research/question source))
                 (map? (:research/vary source))
                 (every? (fn [[parameter values]]
                           (and (non-empty-qualified-keyword? parameter)
                                (vector? values)
                                (seq values)
                                (every? integer? values)))
                         (:research/vary source))
                 (vector? (:research/measures source))
                 (seq (:research/measures source)))
    (throw (ex-info "Invalid research source" {:source source})))
  (doseq [measure (:research/measures source)]
    (validate-measure! measure))
  source)

(defn axis-root [axis]
  (sha256-root :research-axis (dissoc axis :axis/root)))

(defn- axis-id [kind member]
  (case kind
    :case (:case/id member)
    :measure (:measure/id member)))

(defn build-axis
  "Build a dense content-addressed axis assigning integer keys to semantic
   members.

   Each member commits its full semantic identity (`:axis/member`), not just a
   label, so changing any member changes the axis root. This is the hop-scope
   anti-aliasing guarantee: the same integer key under different axis roots
   names a different semantic member, and a bare key carries no portable
   meaning."
  [kind members]
  (let [axis {:artifact/schema axis-schema
              :axis/kind kind
              :axis/members (mapv (fn [key member]
                                    {:axis/key key
                                     :axis/id (axis-id kind member)
                                     :axis/member member})
                                  (range)
                                  members)}]
    (assoc axis :axis/root (axis-root axis))))

(defn scoped-key
  "Bind a local integer key to the scope root that gives it meaning.

   A bare key carries no portable semantics: `(scoped-key C :case 7)` and
   `(scoped-key D :case 7)` name different members. This is the research-layer
   analogue of hop-scope: local identifiers are meaningful only inside the exact
   scope that gives them uniqueness."
  [scope-root axis-kind key]
  {:scope/root scope-root :axis axis-kind :key key})

(defn- frozen-axis [frozen axis-kind]
  (case axis-kind
    :case (get-in frozen [:derived :case-axis])
    :measure (get-in frozen [:derived :measure-axis])
    (throw (ex-info "Unsupported axis kind" {:axis axis-kind}))))

(defn axis-member
  "Resolve a scoped integer key to its semantic member within a frozen
   definition's axis. Fails closed: a key outside the committed axis has no
   meaning and throws rather than aliasing a different member."
  [frozen axis-kind key]
  (let [axis (frozen-axis frozen axis-kind)
        member (some (fn [m] (when (= key (:axis/key m)) m)) (:axis/members axis))]
    (or member
        (throw (ex-info "Axis key is out of scope (bare key has no portable meaning)"
                        {:scope/root (:axis/root axis) :axis axis-kind :key key})))))

(defn definition-root
  "Return the root of a root-free frozen research definition."
  [definition]
  (sha256-root :research-definition definition))

(defn freeze-research
  "Resolve a research-source.v1 into a closed research-definition.v1 envelope.

   `context` may provide `:templates`, a map from researcher-facing template
   IDs to source fragments. Template names never survive as semantic references:
   the resolved values are embedded in the frozen definition."
  ([source] (freeze-research source {}))
  ([source context]
   (let [resolved (-> source (resolve-source context) validate-source!)
         cases (canonical-cases (:research/vary resolved))
         definition {:artifact/schema definition-schema
                     :research/id (:research/id resolved)
                     :research/title (:research/title resolved)
                     :research/question (:research/question resolved)
                     :research/parameters (into {} (sorted-entries (:research/vary resolved)))
                     :research/cases cases
                     :research/measures (vec (sort-by (comp str :measure/id) (:research/measures resolved)))
                     :research/hypotheses (vec (or (:research/hypotheses resolved) []))}
         case-axis (build-axis :case cases)
         measure-axis (build-axis :measure (:research/measures definition))]
     {:research-definition definition
      :research-definition/root (definition-root definition)
      :derived {:case-axis case-axis
                :case-axis/root (:axis/root case-axis)
                :measure-axis measure-axis
                :measure-axis/root (:axis/root measure-axis)}})))

(defn- axes-by-id [frozen]
  {:case (into {} (map (juxt :axis/id :axis/key) (get-in frozen [:derived :case-axis :axis/members])))
   :measure (into {} (map (juxt :axis/id :axis/key) (get-in frozen [:derived :measure-axis :axis/members])))})

(defn compile-result-matrix
  "Compile exact observations into a case × measure matrix.

   `observations` is keyed by researcher-facing case and measure IDs:
   `{case-id {measure-id observed-integer}}`. The frozen definition supplies
   the observation semantics, required value, unit, and direction."
  [frozen observations implementation-root]
  (let [definition (:research-definition frozen)
        {:keys [case measure]} (axes-by-id frozen)
        case-index case
        measure-index measure
        known-case-ids (set (map :case/id (:research/cases definition)))
        known-measure-ids (set (map :measure/id (:research/measures definition)))
        cells (reduce (fn [result case-entry]
                        (reduce (fn [result measure-definition]
                                  (let [case-id (:case/id case-entry)
                                        measure-id (:measure/id measure-definition)
                                        observed (get-in observations [case-id measure-id])]
                                    (when-not (integer? observed)
                                      (throw (ex-info "Missing or invalid observation"
                                                      {:case/id case-id :measure/id measure-id
                                                       :observed observed})))
                                    (let [margin (requirement-margin
                                                  {:observed observed
                                                   :requirement (get-in measure-definition [:measure/requirement :value])
                                                   :domain (:measure/domain measure-definition)
                                                   :satisfying-direction (:measure/satisfying-direction measure-definition)})]
                                      (assoc result [(case-index case-id) (measure-index measure-id)]
                                             (classify-margin margin)))))
                                result
                                (:research/measures definition)))
                      {}
                      (:research/cases definition))]
    (doseq [[case-id measure-values] observations]
      (when-not (contains? known-case-ids case-id)
        (throw (ex-info "Observation references an unknown case (out of scope)"
                        {:case/id case-id})))
      (doseq [measure-id (keys measure-values)]
        (when-not (contains? known-measure-ids measure-id)
          (throw (ex-info "Observation references an unknown measure (out of scope)"
                          {:measure/id measure-id})))))
    {:artifact/schema result-matrix-schema
     :research-definition/root (:research-definition/root frozen)
     :case-axis/root (get-in frozen [:derived :case-axis/root])
     :measure-axis/root (get-in frozen [:derived :measure-axis/root])
     :implementation/root implementation-root
     :matrix/values cells}))

(defn- strip-requirement-values
  "Project a definition with every measure's requirement value removed, exposing
   everything except the one semantic change V1 can enumerate."
  [definition]
  (update definition :research/measures
          (fn [measures]
            (mapv (fn [measure] (update measure :measure/requirement dissoc :value))
                  measures))))

(defn definition-diff
  "Build the canonical semantic diff between two frozen definitions.

   V1 enumerates requirement-value changes by measure ID and FAILS CLOSED on any
   other semantic difference. It is the only vocabulary supported in this slice:
   if cases, measures, hypotheses, or measure structure differ, the diff reports
   `:diff/status :incomplete` with `:diff/unsupported-change? true` rather than
   claiming a partial enumeration is complete. `:diff/status` is therefore:

     :identical   — no semantic difference
     :complete    — every difference is a requirement-value change (fully enumerated)
     :incomplete  — an unsupported semantic difference exists (changes are not enumerated)

   It intentionally emits no source-line or presentation changes: review
   coordinates identify semantic changes only."
  [base candidate]
  (let [base-definition (:research-definition base)
        candidate-definition (:research-definition candidate)
        base-measures (into {} (map (juxt :measure/id identity) (:research/measures base-definition)))
        candidate-measures (into {} (map (juxt :measure/id identity) (:research/measures candidate-definition)))
        shared-ids (sort-by str (set/intersection (set (keys base-measures)) (set (keys candidate-measures))))
        structural-equal? (= (strip-requirement-values base-definition)
                             (strip-requirement-values candidate-definition))
        changes (->> shared-ids
                     (keep (fn [measure-id]
                             (let [before (get-in base-measures [measure-id :measure/requirement :value])
                                   after (get-in candidate-measures [measure-id :measure/requirement :value])]
                               (when (not= before after)
                                 {:change/kind :measure/requirement-changed
                                  :measure/id measure-id
                                  :change/before before
                                  :change/after after
                                  :change/domain (:measure/domain (base-measures measure-id))}))))
                     (map-indexed #(assoc %2 :change/key %1))
                     vec)
        status (cond
                 (and structural-equal? (empty? changes)) :identical
                 structural-equal? :complete
                 :else :incomplete)
        diff (cond-> {:artifact/schema diff-schema
                      :diff/base-definition-root (:research-definition/root base)
                      :diff/candidate-definition-root (:research-definition/root candidate)
                      :diff/status status
                      :diff/changes (if (= status :incomplete) [] changes)}
               (= status :incomplete)
               (assoc :diff/unsupported-change? true
                      :diff/reason :unsupported-semantic-change))]
    (assoc diff :research-definition-diff/root
           (sha256-root :research-definition-diff diff))))

(defn compare-reproductions
  "Compare independently produced matrices for exact result agreement.

   Distinguishes two scientifically different situations:

     :not-comparable — the matrices do not share the same frozen semantic scope
                       (different definition, case axis, or measure axis root).
                       They did not run the same experiment, so no cell values
                       are compared.
     :equal / :disagreement — the matrices share a semantic scope; `equal`
                       means every exact cell matches, `disagreement` lists the
                       exact cells with unequal classified margins.

   Reviewers are not part of either matrix's identity."
  [matrices]
  (let [matrices (vec matrices)
        scope-of (fn [m] (select-keys m [:research-definition/root :case-axis/root :measure-axis/root]))
        scopes (set (map scope-of matrices))]
    (if (not= 1 (count scopes))
      {:reproduction/status :not-comparable
       :reproduction/compared-count (count matrices)
       :reproduction/scopes (sort-by str (vec scopes))}
      (let [all-cells (apply set/union #{} (map #(set (keys (:matrix/values %))) matrices))
            disagreements (->> all-cells
                               (keep (fn [cell]
                                       (let [values (mapv #(get-in % [:matrix/values cell :measure/value]) matrices)]
                                         (when-not (apply = values)
                                           {:matrix/cell cell
                                            :reproduction/values values}))))
                               (sort-by :matrix/cell)
                               vec)]
        {:reproduction/status (if (seq disagreements) :disagreement :equal)
         :reproduction/compared-count (count matrices)
         :reproduction/disagreements disagreements}))))
