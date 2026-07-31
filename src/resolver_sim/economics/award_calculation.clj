(ns resolver-sim.economics.award-calculation
  "Award calculation artifact.
   Award-calculation.v1 captures an immutable calculation of an award
   amount, including policy, pool, claim, and evidence references,
   calculation components, and structured eligibility checks.
   Eligibility is bound to a committed eligibility-policy-root and
   check-set-root, ensuring the supplied checks match the expected set."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const award-calculation-type :award-calculation.v2)

(def ^:private award-projection-fields
  [:artifact/type
   :award/id
   :award/policy-root
   :award/pool-availability-root
   :award/claim-set-root
   :award/evidence-set-root
   :award/beneficiary-id
   :award/calculation-time
   :award/amount
   :award/scale
   :award/calculation-components
   :award/eligibility-result
   :award/eligibility-policy-root
   :award/check-set-root
   :award/mode])

(def ^:private component-kind-sign
  {:base (fn [n] (>= n 0))
   :bonus (fn [n] (>= n 0))
   :deduction (fn [n] (<= n 0))})

;; ── Claim-set root helper ────────────────────────────────────────────────────

(defn claim-set-root
  "Build a canonical content-addressed claim-set root from claim hashes.
   claim-roots — vector of hash strings"
  [claim-roots]
  (let [sorted (vec (sort claim-roots))]
    (when (not= (count sorted) (count (set sorted)))
      (throw (ex-info "Duplicate claim roots" {:roots claim-roots})))
    (hc/hash-with-intent {:hash/intent :claim-set} {:claim/roots sorted})))

(def ^:const award-modes
  "Supported award calculation modes.
     :review  — normative award.  Requires :award/eligibility-policy-root
                and :award/check-set-root so the check set is committed
                and not caller-selected.
     :generic — legacy/generic calculation.  Both roots optional."
  #{:review :generic})

(defn check-set-root
  "Build a canonical content-addressed check-set root from eligibility
   check IDs.  check-ids — vector of keyword or string check identifiers.
   Uses the dedicated :check-set hash domain (CHECK_SET_V1), distinct
   from :claim-set."
  [check-ids]
  (let [sorted (vec (sort check-ids))]
    (when (not= (count sorted) (count (set sorted)))
      (throw (ex-info "Duplicate check IDs" {:ids check-ids})))
    (hc/hash-with-intent {:hash/intent :check-set} {:check/ids sorted})))

;; ── Private normalization helpers ────────────────────────────────────────────

(defn- canonicalize-eligibility-checks
  [checks]
  (let [ids (map :check/id checks)]
    (when (some nil? ids)
      (throw (ex-info "Eligibility checks must have :check/id" {})))
    (when (not= (count ids) (count (set ids)))
      (throw (ex-info "Duplicate eligibility check IDs" {:ids ids})))
    (vec (sort-by :check/id checks))))

(defn- require-keyword-or-string
  [v label]
  (when-not (or (keyword? v) (string? v))
    (throw (ex-info (str label " must be a keyword or string")
                    {label v}))))

;; ── Award hash ───────────────────────────────────────────────────────────────

(defn award-calculation-hash-projection
  [award]
  (select-keys award award-projection-fields))

(defn award-calculation-hash
  [award]
  (hc/hash-with-intent {:hash/intent :award-calculation-v2}
                       (award-calculation-hash-projection award)))

;; ── Structural validation ────────────────────────────────────────────────────

(defn- check-required-keys
  [m required label]
  (let [missing (set/difference (set required) (set (keys m)))]
    (when (seq missing)
      (throw (ex-info (str "Missing required keys in " label)
                      {:missing missing :label label})))))

(defn validate-award-calculation
  "Structural validation of an award-calculation artifact.
   Throws on invalid structure."
  [award]
  (check-required-keys award award-projection-fields "award-calculation")
  (let [extra (set/difference (set (keys award))
                              (set award-projection-fields)
                              #{:artifact/hash})]
    (when (seq extra)
      (throw (ex-info "Unknown award-calculation fields" {:extra extra}))))
  (when-not (= award-calculation-type (:artifact/type award))
    (throw (ex-info "Wrong artifact type"
                    {:expected award-calculation-type
                     :actual (:artifact/type award)})))
  (let [mode (:award/mode award)]
    (when-not (contains? award-modes mode)
      (throw (ex-info "Unsupported award mode"
                      {:award/mode mode
                       :supported (vec award-modes)})))
    (when (and (= :review mode)
               (or (nil? (:award/eligibility-policy-root award))
                   (nil? (:award/check-set-root award))))
      (throw (ex-info "Review-mode award requires :award/eligibility-policy-root and :award/check-set-root"
                      {:award/mode mode}))))
  (let [amount (:award/amount award)]
    (when-not (nat-int? amount)
      (throw (ex-info ":award/amount must be a non-negative integer"
                      {:amount amount}))))
  (let [scale (:award/scale award)]
    (when-not (and (integer? scale) (pos? scale))
      (throw (ex-info ":award/scale must be a positive integer"
                      {:scale scale}))))
  (doseq [k [:award/id :award/policy-root :award/pool-availability-root
             :award/claim-set-root :award/evidence-set-root
             :award/beneficiary-id :award/calculation-time]]
    (let [v (get award k)]
      (when-not (and (string? v) (seq v))
        (throw (ex-info (str (name k) " must be a non-empty string")
                        {k v})))))
  ;; Components
  (let [components (:award/calculation-components award)]
    (when-not (vector? components)
      (throw (ex-info ":award/calculation-components must be a vector"
                      {})))
    (let [comp-ids (map :component/id components)]
      (when (some nil? comp-ids)
        (throw (ex-info "All components must have :component/id" {})))
      (doseq [cid comp-ids]
        (require-keyword-or-string cid ":component/id"))
      (when (not= (count comp-ids) (count (set comp-ids)))
        (throw (ex-info "Duplicate component IDs" {:ids comp-ids}))))
    (doseq [c components]
      (when-not (contains? c :component/kind)
        (throw (ex-info "Component missing :component/kind"
                        {:component/id (:component/id c)})))
      (let [kind (:component/kind c)]
        (when-not (keyword? kind)
          (throw (ex-info ":component/kind must be a keyword"
                          {:component/id (:component/id c) :kind kind})))
        (let [sign-check (get component-kind-sign kind)]
          (when (and sign-check (not (sign-check (:component/amount c))))
            (throw (ex-info "Component amount sign does not match kind"
                            {:component/id (:component/id c)
                             :kind kind
                             :amount (:component/amount c)})))))
      (let [src (:component/source-root c)]
        (when-not (and (string? src) (seq src))
          (throw (ex-info ":component/source-root must be a non-empty string"
                          {:component/id (:component/id c)}))))
      (when-not (integer? (:component/amount c))
        (throw (ex-info ":component/amount must be an integer"
                        {:component/id (:component/id c)
                         :amount (:component/amount c)}))))
    ;; Validate sum
    (let [sum (reduce +' 0 (map :component/amount components))
          declared (:award/amount award)]
      (when (not= sum declared)
        (throw (ex-info "Component sum does not match award amount"
                        {:sum sum :award/amount declared})))))
  ;; Eligibility
  (let [er (:award/eligibility-result award)
        _ (when-not (map? er)
            (throw (ex-info ":award/eligibility-result must be a map" {})))
        eligible? (:eligible? er)
        _ (when-not (true? eligible?)
            (when-not (false? eligible?)
              (throw (ex-info ":eligible? must be a boolean"
                              {:eligible? eligible?}))))
        checks (:checks er)
        _ (when-not (vector? checks)
            (throw (ex-info ":checks must be a vector" {})))
        _ (when (empty? checks)
            (throw (ex-info "Eligibility checks must be non-empty" {})))
        check-ids (map :check/id checks)
        _ (when (some nil? check-ids)
            (throw (ex-info "Each check must have :check/id" {})))
        _ (when (not= (count check-ids) (count (set check-ids)))
            (throw (ex-info "Duplicate check IDs" {:ids check-ids})))]
    (doseq [c checks]
      (when-not (contains? c :check/pass?)
        (throw (ex-info "Check missing :check/pass?"
                        {:check/id (:check/id c)})))
      (when-not (true? (:check/pass? c))
        (when-not (false? (:check/pass? c))
          (throw (ex-info ":check/pass? must be a boolean"
                          {:check/id (:check/id c)
                           :pass? (:check/pass? c)})))))
    ;; Derive eligibility from checks
    (let [computed-eligible (every? true? (map :check/pass? checks))]
      (when (not= computed-eligible eligible?)
        (throw (ex-info "Eligibility result does not match check outcomes"
                        {:declared eligible?
                         :computed computed-eligible}))))
    ;; Verify check-set-root equals derived canonical root, when present
    (let [declared-root (:award/check-set-root award)
          derived-root (check-set-root (sort (map :check/id checks)))]
      (when (and declared-root (not= declared-root derived-root))
        (throw (ex-info "check-set-root does not match canonicalized check IDs"
                        {:declared-root declared-root
                         :derived-root derived-root
                         :check-ids (map :check/id checks)}))))
    ;; Ineligible + positive amount rejected
    (when (and (false? eligible?) (pos? (:award/amount award)))
      (throw (ex-info "Ineligible award must have zero amount"
                      {:award/id (:award/id award)
                       :amount (:award/amount award)}))))
  nil)

;; ── Builder ──────────────────────────────────────────────────────────────────

(defn build-award-calculation
  "Build a content-addressed award-calculation artifact.
   Validates component sum = amount, canonicalizes component order and
   eligibility checks, derives eligibility from check outcomes.
   Ineligible awards must have zero amount."
  [args]
  (let [{:keys [award/id award/policy-root award/pool-availability-root
                award/claim-set-root award/evidence-set-root
                award/beneficiary-id award/calculation-time
                award/scale
                award/calculation-components
                award/eligibility-result]}
        args
        eligibility-policy-root (:award/eligibility-policy-root args)
        declared-check-set-root (:award/check-set-root args)
        award-mode (or (:award/mode args) :generic)]
    ;; Validate mode
    (when-not (contains? award-modes award-mode)
      (throw (ex-info "Unsupported award mode"
                      {:award/mode award-mode
                       :supported (vec award-modes)})))
    ;; Review mode requires eligibility binding roots
    (when (and (= :review award-mode)
               (or (nil? eligibility-policy-root)
                   (nil? declared-check-set-root)))
    (throw (ex-info "Review-mode award requires :award/eligibility-policy-root and :award/check-set-root"
                    {:award/mode award-mode
                     :award/eligibility-policy-root eligibility-policy-root
                     :award/check-set-root check-set-root})))
  ;; Validate component IDs
  (let [comp-ids (map :component/id calculation-components)]
    (when (some nil? comp-ids)
      (throw (ex-info "All components must have :component/id" {})))
    (doseq [cid comp-ids]
      (require-keyword-or-string cid ":component/id"))
    (when (not= (count comp-ids) (count (set comp-ids)))
      (throw (ex-info "Duplicate component IDs" {:ids comp-ids}))))
  ;; Validate component kinds and amounts
  (doseq [c calculation-components]
    (let [kind (:component/kind c)]
      (when-not (keyword? kind)
        (throw (ex-info ":component/kind must be a keyword"
                        {:component/id (:component/id c)})))
      (let [sign-check (get component-kind-sign kind)]
        (when (and sign-check (not (sign-check (:component/amount c))))
          (throw (ex-info "Component amount sign does not match kind"
                          {:component/id (:component/id c)
                           :kind kind
                           :amount (:component/amount c)})))))
    (when-not (integer? (:component/amount c))
      (throw (ex-info ":component/amount must be an integer"
                      {:component/id (:component/id c)}))))
  ;; Canonicalize component order
  (let [components (vec (sort-by :component/id calculation-components))
        amount (reduce +' 0 (map :component/amount components))]
    (when (neg? amount)
      (throw (ex-info "Award amount would be negative"
                      {:amount amount})))
    ;; Validate eligibility
    (let [checks (get eligibility-result :checks [])
          checks-canonical (canonicalize-eligibility-checks checks)
          computed-eligible (and (seq checks-canonical)
                                (every? true?
                                        (map :check/pass? checks-canonical)))
          declared-eligible (:eligible? eligibility-result)]
      ;; Check consistency
      (when (not= computed-eligible declared-eligible)
        (throw (ex-info "Eligibility result does not match check outcomes"
                        {:declared declared-eligible
                         :computed computed-eligible})))
      ;; Ineligible + positive amount → reject
      (when (and (not computed-eligible) (pos? amount))
        (throw (ex-info "Ineligible award must have zero amount"
                        {:award/id id :amount amount})))
      ;; Verify check-set-root if provided
      (when declared-check-set-root
        (let [check-ids (map :check/id checks-canonical)
              computed-root (check-set-root check-ids)]
          (when (not= declared-check-set-root computed-root)
            (throw (ex-info "check-set-root does not match canonicalized check IDs"
                            {:declared-root declared-check-set-root
                             :computed-root computed-root
                             :check-ids check-ids})))))
      ;; Build artifact
      (let [result {:artifact/type award-calculation-type
                    :award/id id
                    :award/policy-root policy-root
                    :award/pool-availability-root pool-availability-root
                    :award/claim-set-root claim-set-root
                    :award/evidence-set-root evidence-set-root
                    :award/beneficiary-id beneficiary-id
                    :award/calculation-time calculation-time
                    :award/amount amount
                    :award/scale scale
                    :award/calculation-components components
                    :award/eligibility-result
                    (assoc eligibility-result :checks checks-canonical)
                    :award/eligibility-policy-root eligibility-policy-root
                    :award/check-set-root declared-check-set-root
                    :award/mode award-mode}
            hash (award-calculation-hash result)]
        (assoc result :artifact/hash hash))))))

;; ── Verifier ─────────────────────────────────────────────────────────────────

(defn verify-award-calculation
  "Independent verification of an award-calculation artifact.
   Returns {:valid? true} or {:valid? false :errors [...]}.
   Never throws.
   opts — {:keys [policy-resolver]}, where policy-resolver is a function
   (fn [policy-root] -> award-policy artifact) or nil.  When supplied,
   the verifier resolves :award/eligibility-policy-root and establishes
   the policy-relative completeness invariant:
     (:policy/check-set-root resolved-policy)
     == (:award/check-set-root award)
     == (check-set-root supplied-check-ids)"
  ([award] (verify-award-calculation award nil))
  ([award {:keys [policy-resolver]}]
   (try
    (validate-award-calculation award)
    (let [errors (atom [])]
      ;; Recompute hash
      (let [expected (award-calculation-hash award)]
        (when (not= expected (:artifact/hash award))
          (swap! errors conj {:type :hash-mismatch
                              :expected expected
                              :actual (:artifact/hash award)})))
      ;; Recompute amount from components
      (let [recomp (reduce +' 0
                           (map :component/amount
                                (:award/calculation-components award)))]
        (when (not= recomp (:award/amount award))
          (swap! errors conj {:type :amount-mismatch
                              :expected recomp
                              :actual (:award/amount award)})))
      ;; Verify canonical component ordering
      (let [sorted (vec (sort-by :component/id
                                  (:award/calculation-components award)))]
        (when (not= sorted (:award/calculation-components award))
          (swap! errors conj {:type :non-canonical-components})))
      ;; Verify canonical eligibility check ordering
      (let [checks (get-in award [:award/eligibility-result :checks])
            sorted-checks (canonicalize-eligibility-checks checks)]
        (when (not= sorted-checks checks)
          (swap! errors conj {:type :non-canonical-eligibility-checks})))
      ;; Derive eligibility from checks
      (let [checks (get-in award [:award/eligibility-result :checks])
            computed-eligible (and (seq checks)
                                   (every? true?
                                           (map :check/pass? checks)))
            declared-eligible (get-in award
                                      [:award/eligibility-result :eligible?])]
        (when (not= computed-eligible declared-eligible)
          (swap! errors conj {:type :eligibility-mismatch
                              :declared declared-eligible
                              :computed computed-eligible}))
        (when (and (not computed-eligible)
                   (pos? (:award/amount award)))
          (swap! errors conj {:type :ineligible-positive-amount
                              :amount (:award/amount award)})))
      ;; Policy-relative completeness: when a policy-resolver is supplied,
      ;; resolve the committed policy and require its check-set-root to equal
      ;; both the artifact check-set-root and the derived root from supplied checks.
      (when policy-resolver
        (let [policy-root (:award/eligibility-policy-root award)
              award-csr (:award/check-set-root award)
              supplied-csr (check-set-root
                            (map :check/id
                                 (get-in award [:award/eligibility-result :checks])))]
          (if (and policy-root award-csr)
            (let [resolved (try (policy-resolver policy-root)
                                (catch Exception e
                                  (swap! errors conj {:type :policy-resolve-error
                                                      :policy-root policy-root
                                                      :message (ex-message e)})
                                  nil))
                  _ (when resolved
                      (let [policy-csr (:policy/check-set-root resolved)]
                        (when (not= policy-csr award-csr)
                          (swap! errors conj {:type :policy-check-set-mismatch
                                              :policy-root policy-root
                                              :policy-check-set-root policy-csr
                                              :award-check-set-root award-csr}))
                        (when (not= policy-csr supplied-csr)
                          (swap! errors conj {:type :policy-supplied-check-set-mismatch
                                              :policy-root policy-root
                                              :policy-check-set-root policy-csr
                                              :supplied-check-set-root supplied-csr
                                              :supplied-ids (mapv :check/id
                                                                  (get-in award [:award/eligibility-result :checks]))}))))]
              nil))))
      (if (empty? @errors) {:valid? true} {:valid? false :errors @errors}))
    (catch Exception e
      {:valid? false
       :errors [{:type :invalid-structure
                 :message (ex-message e)
                 :data (ex-data e)}]}))))
