(ns resolver-sim.economics.effects
  "Normalised, versioned effect intents (ADR-0005 Phase 5).

   Calculations never mutate custody or ledger state. Instead they emit typed
   effects, each carrying a versioned effect-contract reference. The protocol
   adapter validates the effect schema, checks its own support declaration,
   and converts validated effects into protocol transitions. Extensions must
   never call adjust-held (or any custody mutation) directly.

   Fail-before-mutation pipeline:
     extension result
     → effect schema validation      (validate-effect)
     → adapter support validation    (supported-effect?)
     → application plan v2           (build-effects-plan)
     → plan verification
     → protocol mutation
     → transition evidence"
  (:require [resolver-sim.economics.schemas :as schemas]
            [resolver-sim.hash.canonical :as hc]))

(def effect-schema-domain-tag
  "PRF_EFFECT_CONTRACT_V1")

;; ── versioned effect contracts ────────────────────────────────────────────

(def balance-credit-schema
  {:effect/type :keyword
   :effect/contract :keyword
   :effect/account :keyword
   :effect/amount :integer})

(def obligation-create-schema
  {:effect/type :keyword
   :effect/contract :keyword
   :obligation/type :keyword
   :obligation/amount :integer
   :obligation/owner :keyword})

(def custody-held-adjustment-schema
  {:effect/type :keyword
   :effect/contract :keyword
   :effect/direction :keyword
   :effect/account :keyword
   :effect/amount :integer})

(def effect-schema-maps
  "Effect contract id -> schema map."
  {:prf.effect/balance-credit.v1 balance-credit-schema
   :prf.effect/obligation-create.v1 obligation-create-schema
   :prf.effect/custody-held-adjustment.v1 custody-held-adjustment-schema})

(defn effect-schema-root
  "Content-addressed root of an effect contract schema."
  [schema]
  (hc/domain-hash effect-schema-domain-tag schema))

(def effect-schema-roots
  "Effect contract id -> content-addressed root, committed into resolution
   (:effect-schemas) so the extension resolution root reflects the effect
   vocabulary in force."
  (into {} (map (fn [[id m]] [id (effect-schema-root m)])) effect-schema-maps))

;; ── validation ────────────────────────────────────────────────────────────

(defn validate-effect
  "Structurally validate an effect against its versioned contract.
   Returns {:valid? bool, :violations [...]}. Fails when the contract
   reference is missing or unknown, or when the payload fails its schema."
  [effect]
  (let [contract-id (:effect/contract effect)]
    (cond
      (nil? contract-id)
      {:valid? false
       :violations [{:violation/id :violation/effect-missing-contract
                     :details {:effect effect}}]}

      (not (contains? effect-schema-maps contract-id))
      {:valid? false
       :violations [{:violation/id :violation/unknown-effect-contract
                     :details {:effect/contract contract-id}}]}

      :else
      (schemas/validate-against-schema (get effect-schema-maps contract-id)
                                       effect))))

(defn validate-effects
  "Validate a vector of effects; returns {:valid? bool :violations [...]}."
  [effects]
  (let [violations (into [] (mapcat :violations (map validate-effect effects)))]
    {:valid? (empty? violations)
     :violations violations}))

;; ── derivation ────────────────────────────────────────────────────────────

(defn award->effects
  "Derive the normalised effects of a single positive award:
   a settlement :balance/credit plus an :obligation/create payable."
  [award]
  (let [amount (:award/amount award)
        account (get-in award [:settlement :allocation-id])
        owner (get-in award [:beneficiary :participant/id])]
    [{:effect/type :balance/credit
      :effect/contract :prf.effect/balance-credit.v1
      :effect/account account
      :effect/amount amount}
     {:effect/type :obligation/create
      :effect/contract :prf.effect/obligation-create.v1
      :obligation/type (get-in award [:settlement :obligation-kind])
      :obligation/amount amount
      :obligation/owner owner}]))

(defn distribution->effects
  "Derive and validate the effects of every positive award in a distribution.
   Returns {:effects [...] :valid? bool :violations [...]}."
  [distribution]
  (let [effects (into [] (mapcat award->effects) (:distribution/awards distribution []))
        {:keys [valid? violations]} (validate-effects effects)]
    {:effects effects
     :valid? valid?
     :violations violations}))

;; ── adapter support (fail-before-mutation) ────────────────────────────────

(defn supported-effect?
  "True when an adapter's support declaration covers an effect's contract.
   adapter-support — {:adapter/id <kw> :adapter/supported-effects #{contract-id}}"
  [adapter-support effect]
  (contains? (:adapter/supported-effects adapter-support) (:effect/contract effect)))

(defn unsupported-effects
  "Return the effects not supported by the adapter support declaration."
  [adapter-support effects]
  (vec (remove #(supported-effect? adapter-support %) effects)))

(defn validate-effects-for-adapter
  "Fail-before-mutation check: every effect must be schema-valid and supported
   by the adapter. Returns {:valid? bool :violations [...]}."
  [adapter-support effects]
  (let [{:keys [violations]} (validate-effects effects)
        unsupported (unsupported-effects adapter-support effects)]
    (cond-> {:valid? (and (empty? violations) (empty? unsupported))
             :violations (vec violations)}
      (seq unsupported)
      (update :violations conj
              {:violation/id :violation/unsupported-effect-for-adapter
               :details {:adapter/id (:adapter/id adapter-support)
                         :unsupported (mapv :effect/contract unsupported)}}))))

;; ── transition projection ─────────────────────────────────────────────────

(defn effect->transition
  "Project a validated effect to an abstract protocol transition. The protocol
   adapter interprets this transition into concrete state mutations (custody,
   balances, obligations); this projection itself never mutates state."
  [effect]
  (case (:effect/type effect)
    :balance/credit
    {:transition/type :credit
     :transition/account (:effect/account effect)
     :transition/amount (:effect/amount effect)}

    :obligation/create
    {:transition/type :obligation
     :obligation/type (:obligation/type effect)
     :obligation/amount (:obligation/amount effect)
     :obligation/owner (:obligation/owner effect)}

    :custody/held-adjustment
    {:transition/type :custody-credit
     :custody/account (:effect/account effect)
     :custody/direction (:effect/direction effect)
     :custody/amount (:effect/amount effect)}

    nil))
