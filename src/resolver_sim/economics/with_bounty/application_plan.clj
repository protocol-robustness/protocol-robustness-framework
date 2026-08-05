(ns resolver-sim.economics.with-bounty.application-plan
  "Composition application plan for a with-bounty evaluation (ADR-0006 D3).

   A dedicated, content-addressed plan that composes over a base plan rather
   than expanding slash-distribution-application-plan.v2:

     with-bounty plan
     ├── base application-plan root
     ├── base result root
     ├── bounty effect root
     ├── combined effect-set root
     └── composition preconditions

   The plan owns creation-time preconditions only (ADR-0006 D5): effect schema
   validity, amount bounds, funding availability, deterministic obligation
   identity, and the no-duplicate-creation key. Lifecycle conservation is owned
   by bounty-payable/backing and the protocol."
  (:require [resolver-sim.economics.effects :as effects]
            [resolver-sim.economics.with-bounty.identity :as identity]
            [resolver-sim.hash.canonical :as hc]))

(def schema-version
  "with-bounty-application-plan.v1")

(def plan-domain-tag
  :with-bounty-application-plan-v1)

(def effect-domain-tag
  :with-bounty-effect-v1)

(def effect-set-domain-tag
  :with-bounty-effect-set-v1)

;; ── effect roots ──────────────────────────────────────────────────────────

(defn effect-root
  "Content-addressed root of a single validated effect."
  [effect]
  (hc/domain-hash effect-domain-tag effect))

(defn- effect-set-root
  "Combined effect-set root: base plan root plus the ordered effect roots."
  [base-plan-root effect-roots]
  (hc/domain-hash effect-set-domain-tag [base-plan-root (vec effect-roots)]))

;; ── helpers ───────────────────────────────────────────────────────────────

(defn- obligation-effect
  "The v2 obligation effect of the effect set (the bounty itself)."
  [effects]
  (first (filter #(= :prf.effect/obligation-create.v2 (:effect/contract %))
                 effects)))

(defn- obligation-amount
  [effects]
  (:obligation/amount (obligation-effect effects)))

(defn- recomputed-obligation-id
  "Recompute the deterministic obligation id from the effect's committed
   fields; the plan fails closed when it does not match :obligation/id."
  [effect]
  (identity/bounty-obligation-id
   {:operation-root (get-in effect [:obligation/subject :operation-root])
    :bounty-id (get-in effect [:obligation/subject :bounty-id])
    :recipient (:obligation/owner effect)
    :token (:obligation/token effect)
    :amount (:obligation/amount effect)
    :policy-root (get-in effect [:effect/provenance :policy-root])}))

(defn- no-duplicate-creation-key
  "Per-operation uniqueness key: at most one live bounty obligation per
   [operation-root, bounty-id, recipient]."
  [effect]
  [(get-in effect [:obligation/subject :operation-root])
   (get-in effect [:obligation/subject :bounty-id])
   (:obligation/owner effect)])

(defn- violation-for
  [precondition]
  (case precondition
    :effect/schema-valid? :violation/effect-schema-invalid
    :amount/bounded? :violation/bounty-amount-out-of-bounds
    :funding/available? :violation/insufficient-bounty-funding
    :obligation-id/consistent? :violation/obligation-id-mismatch))

;; ── recomputable creation preconditions (verifier-independent of builder) ─

(defn amount-bounded?
  "Recomputable amount-bound precondition: non-negative and within the declared
   maximum (nil maximum means unbounded)."
  [amount declared-maximum]
  (and (integer? amount) (not (neg? amount))
       (or (nil? declared-maximum) (<= amount declared-maximum))))

(defn funding-available?
  "Recomputable funding precondition. :declared-reserve requires a committed
   available amount >= the bounty amount; other funding sources are not
   balance-gated in v1."
  [funding-source amount funding-available]
  (case funding-source
    :declared-reserve (and (some? funding-available) (>= funding-available amount))
    true))

(defn obligation-id-consistent?
  "Recomputable obligation-id precondition: the committed obligation id matches
   the deterministic identity derived from the obligation effect."
  [obligation-effect]
  (and obligation-effect
       (= (:obligation/id obligation-effect)
          (recomputed-obligation-id obligation-effect))))

(defn- compute-preconditions
  [effects obligation amount funding-available declared-maximum]
  (let [funding-source (get-in obligation [:obligation/funding :source])]
    {:effect/schema-valid? (:valid? (effects/validate-effects effects))
     :amount/bounded? (amount-bounded? amount declared-maximum)
     :funding/available? (funding-available? funding-source amount funding-available)
     :obligation-id/consistent? (obligation-id-consistent? obligation)}))

;; ── plan identity ─────────────────────────────────────────────────────────

(def plan-hash-projection-fields
  [:schema-version
   :plan/policy-root
   :plan/base-operation-root
   :plan/base-result-root
   :plan/base-plan-root
   :plan/extensions-resolution-root
   :plan/adapter
   :plan/effects
   :plan/effect-roots
   :plan/combined-effect-root
   :plan/effect-schema-roots
   :plan/declared-maximum
   :plan/funding-available
   :plan/obligation-id
   :plan/no-duplicate-creation-key
   :plan/preconditions
   :plan/idempotency-key
   :plan/context])

(defn plan-hash
  "Content-addressed root of a with-bounty application plan."
  [plan]
  (hc/domain-hash plan-domain-tag
                  (select-keys plan plan-hash-projection-fields)))

;; ── plan builder ──────────────────────────────────────────────────────────

(defn build-with-bounty-plan
  "Build a with-bounty-application-plan.v1 from a validated effect set and
   composition context.

   Args:
     :policy-root               — with-bounty policy root
     :base-operation-root       — committed base operation/result root
     :base-result-root          — committed base result root (v1: same as
                                  base-operation-root; committed separately so
                                  a change to the base result identity is
                                  plan-relevant)
     :base-plan-root            — optional base application-plan root
     :extensions-resolution-root — run-level frozen resolution root
     :adapter                   — adapter-support declaration the effects were
                                  validated against (committed so two adapters
                                  cannot apply the same plan root differently)
     :effects                   — validated effect set (bounty + custody)
     :effect-schema-roots       — effect vocabulary roots in force
     :declared-maximum          — optional amount cap (committed)
     :funding-available         — declared funding available for the funding
                                  source (committed; recomputable precondition)

   Fails closed (no mutation) when any creation precondition is false:
   effect schema validity, amount bounds, funding availability, and
   obligation-id consistency. Returns {:status :valid, :plan <plan>}
   or {:status :invalid, :violations [...]}."
  [{:keys [policy-root base-operation-root base-result-root base-plan-root
           extensions-resolution-root adapter
           effects effect-schema-roots
           declared-maximum funding-available context]}]
  (let [obligation (obligation-effect effects)
        amount (obligation-amount effects)
        obligation-id (when obligation (:obligation/id obligation))
        effect-roots (mapv effect-root effects)
        preconditions (compute-preconditions effects obligation amount
                                              funding-available declared-maximum)
        failures (into []
                       (keep (fn [[k ok]]
                               (when (false? ok)
                                 {:violation/id (violation-for k)
                                  :details {:precondition k}})))
                       preconditions)
        base {:schema-version schema-version
              :plan/policy-root policy-root
              :plan/base-operation-root base-operation-root
              :plan/base-result-root (or base-result-root base-operation-root)
              :plan/base-plan-root base-plan-root
              :plan/extensions-resolution-root extensions-resolution-root
              :plan/adapter adapter
              :plan/effects (vec effects)
              :plan/effect-roots effect-roots
              :plan/combined-effect-root (effect-set-root base-plan-root effect-roots)
              :plan/effect-schema-roots (or effect-schema-roots {})
              :plan/declared-maximum declared-maximum
              :plan/funding-available funding-available
              :plan/obligation-id obligation-id
              :plan/no-duplicate-creation-key (when obligation
                                                (no-duplicate-creation-key obligation))
              :plan/preconditions preconditions
              :plan/idempotency-key [:with-bounty/obligations obligation-id]
              :plan/context (or context {})}]
    (if (seq failures)
      {:status :invalid :violations (vec failures)}
      (let [plan (assoc base :plan/hash (plan-hash base))]
        {:status :valid :plan plan}))))

;; ── plan validation ───────────────────────────────────────────────────────

(defn validate-with-bounty-plan
  "Structurally validate a with-bounty application plan: exact committed shape
   (unknown top-level keys are rejected), required committed fields, and the
   schema-version."
  [plan]
  (if-not (map? plan)
    {:valid? false :errors [:non-map-plan]}
    (let [known (set plan-hash-projection-fields)
          unknown (vec (sort (remove known (keys plan))))
          errors (cond-> []
                   (not= schema-version (:schema-version plan))
                   (conj :unsupported-schema-version)

                   (seq unknown)
                   (conj [:unknown-keys unknown])

                   (nil? (:plan/policy-root plan))
                   (conj :missing-policy-root)

                   (nil? (:plan/base-operation-root plan))
                   (conj :missing-base-operation-root)

                   (nil? (:plan/base-result-root plan))
                   (conj :missing-base-result-root)

                   (nil? (:plan/extensions-resolution-root plan))
                   (conj :missing-extensions-resolution-root)

                   (not (vector? (:plan/effects plan)))
                   (conj :invalid-effects)

                   (nil? (:plan/obligation-id plan))
                   (conj :missing-obligation-id)

                   (not (map? (:plan/preconditions plan)))
                   (conj :missing-preconditions)

                   (not (map? (:plan/adapter plan)))
                   (conj :missing-adapter-commitment))]
      (if (seq errors)
        {:valid? false :errors errors}
        {:valid? true}))))

(defn recomputed-plan-roots
  "Recompute the derived roots and preconditions of a plan from its committed
   effects and inputs. Returns a map of derived-value-name -> recomputed-value.
   The verifier compares these to the committed values, so the verifier never
   trusts constructor-assembled caches."
  [plan]
  (let [effects (:plan/effects plan [])
        obligation (obligation-effect effects)
        amount (obligation-amount effects)
        effect-roots (mapv effect-root effects)
        preconditions (compute-preconditions effects obligation amount
                                              (:plan/funding-available plan)
                                              (:plan/declared-maximum plan))]
    {:plan/effect-roots effect-roots
     :plan/combined-effect-root (effect-set-root (:plan/base-plan-root plan)
                                                 effect-roots)
     :plan/obligation-id (when obligation (:obligation/id obligation))
     :plan/no-duplicate-creation-key (when obligation
                                       (no-duplicate-creation-key obligation))
     :plan/preconditions preconditions}))

(defn verify-with-bounty-plan
  "Verify a plan: exact-shape validation, recomputed hash match, and
   reconciliation of the committed derived fields against values recomputed
   from the committed effects and inputs."
  [plan]
  (let [v (validate-with-bounty-plan plan)]
    (if-not (:valid? v)
      v
      (let [computed-hash (plan-hash plan)
            recomputed (recomputed-plan-roots plan)
            mismatches (into []
                             (keep (fn [k]
                                     (when (not= (get recomputed k) (get plan k))
                                       {:field k
                                        :committed (get plan k)
                                        :recomputed (get recomputed k)})))
                             (keys recomputed))
            hash-ok? (= computed-hash (:plan/hash plan))]
        (cond
          (not hash-ok?)
          {:valid? false :errors [:hash-mismatch]
           :computed computed-hash :stored (:plan/hash plan)}

          (seq mismatches)
          {:valid? false :errors [:derived-field-mismatch] :mismatches (vec mismatches)}

          :else {:valid? true})))))

(defn validate-with-bounty-plan-for-adapter
  "Fail-before-mutation check of a plan's effect set against an adapter support
   declaration."
  [adapter-support plan]
  (let [r (effects/validate-effects-for-adapter adapter-support
                                                (:plan/effects plan []))]
    (if (:valid? r)
      {:valid? true}
      {:valid? false :violations (:violations r)})))
