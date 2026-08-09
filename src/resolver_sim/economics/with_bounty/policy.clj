(ns resolver-sim.economics.with-bounty.policy
  "with-bounty policy v1: the declared, content-addressed contract of a
   with-bounty composition (ADR-0006 D2/D4).

   The policy is data. It declares a committed base result reference, an
   eligibility capability, an amount capability, a funding declaration, a
   recipient source, the effect contract, and failure semantics. The policy
   root commits the declared order and every reference; replacing or
   reordering any reference changes the identity.

   Stage A scope: policy normalisation and policy-root hashing. Evaluation
   (extension resolution + invocation) lands in Stage B."
  (:require [resolver-sim.hash.canonical :as hc]))

(def composition-type
  :economics/with-bounty)

(def composition-version
  1)

(def policy-domain-tag
  :with-bounty-policy-v1)

(def supported-funding-sources
  #{:declared-reserve :base/gross :external-sponsor})

(def supported-basis-sources
  "Declared bases an amount capability may reference. A basis outside this
   set is an undeclared context read and is rejected."
  #{:base/result :parameter :event})

(def supported-recipient-sources
  #{:event/actor})

(def supported-failure-modes
  #{:base-independent :transaction-fatal})

(def supported-on-ineligible
  #{:skip})

(def supported-on-calculation-failure
  #{:abort-bounty})

(def supported-on-unsupported-effect
  #{:abort-before-mutation})

;; ── defaults / normalisation ──────────────────────────────────────────────

(defn default-policy
  "Stage A defaults for a with-bounty policy (single source of truth:
   resolver-sim.hash.canonical/with-bounty-policy-defaults)."
  []
  hc/with-bounty-policy-defaults)

(defn normalize-with-bounty-policy
  "Fill defaults into an authored policy. The policy root commits the
   normalised form, so identical authored policies with omitted defaults hash
   identically."
  [policy]
  (merge (default-policy) (or policy {})))

;; ── validation ────────────────────────────────────────────────────────────

(defn- valid-capability-ref?
  "A capability reference is a namespaced kind and id with a positive version."
  [ref]
  (and (keyword? (:capability/kind ref)) (namespace (:capability/kind ref))
       (keyword? (:capability/id ref)) (namespace (:capability/id ref))
       (pos-int? (or (:capability/version ref) 0))))
(defn- validate-bounty
  [violations bounty]
  (let [elig (:eligibility bounty)
        amt (:amount bounty)
        funding (:funding bounty)
        recipient (:recipient bounty)
        v (cond-> violations
            (nil? (:bounty/id bounty))
            (conj {:violation/id :violation/missing-bounty-id :details {}})

            (not (keyword? (:bounty/id bounty)))
            (conj {:violation/id :violation/invalid-bounty-id :details {:bounty/id (:bounty/id bounty)}})

            (not (map? elig))
            (conj {:violation/id :violation/missing-eligibility :details {}})

            (and (map? elig) (not (valid-capability-ref? (:capability/ref elig))))
            (conj {:violation/id :violation/invalid-eligibility-capability
                   :details {:capability/ref (:capability/ref elig)}})

            (and (map? elig) (not= :economics/eligibility
                                   (get-in elig [:capability/ref :capability/kind])))
            (conj {:violation/id :violation/eligibility-kind-mismatch
                   :details {:capability/ref (:capability/ref elig)}})

            (not (map? amt))
            (conj {:violation/id :violation/missing-amount :details {}})

            (and (map? amt) (not (valid-capability-ref? (:capability/ref amt))))
            (conj {:violation/id :violation/invalid-amount-capability
                   :details {:capability/ref (:capability/ref amt)}})

            (and (map? amt) (not= :economics/award-amount
                                  (get-in amt [:capability/ref :capability/kind])))
            (conj {:violation/id :violation/amount-kind-mismatch
                   :details {:capability/ref (:capability/ref amt)}})

            (and (map? amt) (:basis amt)
                 (not (contains? supported-basis-sources (:source (:basis amt)))))
            (conj {:violation/id :violation/undeclared-basis-source
                   :details {:basis (:basis amt)
                             :supported (vec supported-basis-sources)}})

            (not (map? funding))
            (conj {:violation/id :violation/missing-funding :details {}})

            (and (map? funding)
                 (not (contains? supported-funding-sources (:source funding))))
            (conj {:violation/id :violation/unsupported-funding-source
                   :details {:source (:source funding)
                             :supported (vec supported-funding-sources)}})

            (and (map? funding) (= :declared-reserve (:source funding))
                 (not (vector? (:parameter/address funding))))
            (conj {:violation/id :violation/invalid-reserve-address
                   :details {:parameter/address (:parameter/address funding)}})

            (not (map? recipient))
            (conj {:violation/id :violation/missing-recipient :details {}})

            (and (map? recipient)
                 (not (contains? supported-recipient-sources (:source recipient))))
            (conj {:violation/id :violation/unsupported-recipient-source
                   :details {:source (:source recipient)
                             :supported (vec supported-recipient-sources)}})

            (not (keyword? (:effect-contract bounty)))
            (conj {:violation/id :violation/invalid-effect-contract
                   :details {:effect-contract (:effect-contract bounty)}}))]
    v))

(defn validate-with-bounty-policy
  "Structurally validate a with-bounty policy (after normalisation).
   Returns {:valid? bool, :violations [violation-maps]}."
  [policy]
  (if-not (map? policy)
    {:valid? false
     :violations [{:violation/id :violation/non-map-with-bounty-policy
                   :details {:policy policy}}]}
    (let [base (:base policy)
          bounty (:bounty policy)
          v (cond-> []
              (not= composition-type (:composition/type policy))
              (conj {:violation/id :violation/invalid-composition-type
                     :details {:type (:composition/type policy)
                               :expected composition-type}})

              (not= composition-version (:composition/version policy))
              (conj {:violation/id :violation/invalid-composition-version
                     :details {:version (:composition/version policy)
                               :supported composition-version}})

              (not (map? base))
              (conj {:violation/id :violation/missing-base :details {}})

              (and (map? base) (nil? (:operation/ref base)))
              (conj {:violation/id :violation/missing-base-operation-ref :details {}})

              (and (map? base) (nil? (:result/schema base)))
              (conj {:violation/id :violation/missing-base-result-schema :details {}})

              (not (map? bounty))
              (conj {:violation/id :violation/missing-bounty :details {}})

              (not (contains? supported-failure-modes (:bounty/failure-mode policy)))
              (conj {:violation/id :violation/unsupported-failure-mode
                     :details {:failure-mode (:bounty/failure-mode policy)
                               :supported (vec supported-failure-modes)}})

              (not (contains? supported-on-ineligible (:bounty/on-ineligible policy)))
              (conj {:violation/id :violation/unsupported-on-ineligible
                     :details {:on-ineligible (:bounty/on-ineligible policy)}})

              (not (contains? supported-on-calculation-failure
                              (:bounty/on-calculation-failure policy)))
              (conj {:violation/id :violation/unsupported-on-calculation-failure
                     :details {:on-calculation-failure (:bounty/on-calculation-failure policy)}})

              (not (contains? supported-on-unsupported-effect
                              (:bounty/on-unsupported-effect policy)))
              (conj {:violation/id :violation/unsupported-on-unsupported-effect
                     :details {:on-unsupported-effect (:bounty/on-unsupported-effect policy)}}))
          v (if (map? bounty)
              (validate-bounty v bounty)
              v)]
      {:valid? (empty? v)
       :violations (vec v)})))

(defn valid-with-bounty-policy?
  [policy]
  (:valid? (validate-with-bounty-policy policy)))

;; ── policy identity ───────────────────────────────────────────────────────

(defn with-bounty-policy-root
  "Content-addressed root of the normalised with-bounty policy. Replacing any
   capability, funding, recipient, effect-contract, or base reference changes
   the root; map key ordering does not.  Projection is the single source of
   truth in resolver-sim.hash.canonical/project-with-bounty-policy and is
   byte-identical to normalize-with-bounty-policy."
  [policy]
  (hc/domain-hash policy-domain-tag (hc/project-with-bounty-policy policy nil)))
