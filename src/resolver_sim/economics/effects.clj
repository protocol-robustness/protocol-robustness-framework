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
  (:require [resolver-sim.accounting.held-adjustment :as ha]
            [resolver-sim.economics.schemas :as schemas]
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

(def obligation-create-v2-schema
  "with-bounty obligation shape (ADR-0006 D1): carries the deterministic
   obligation id, token, funding, subject, and provenance in addition to the
   v1 fields. v1 keeps its committed meaning for slash-distribution evidence."
  {:effect/type :keyword
   :effect/contract :keyword
   :obligation/type :keyword
   :obligation/id :string
   :obligation/amount :integer
   :obligation/token :keyword
   :obligation/owner :keyword
   :obligation/funding :map
   :obligation/subject :map
   :effect/provenance :map})

(def custody-held-adjustment-schema
  "A custody-held-adjustment effect models an add-held / sub-held custody
   mutation: the :held/kind is the economic custody reason (mapped to the
   add-held :reason), :effect/account the custody account, and the effect may
   carry :owner/address and :parameter/address attribution."
  {:effect/type :keyword
   :effect/contract :keyword
   :effect/direction :keyword
   :effect/account :keyword
   :effect/amount :integer
   :held/kind :keyword})

(def effect-schema-maps
  "Effect contract id -> schema map."
  {:prf.effect/balance-credit.v1 balance-credit-schema
   :prf.effect/obligation-create.v1 obligation-create-schema
   :prf.effect/obligation-create.v2 obligation-create-v2-schema
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

;; ── v1 → v2 migration (ADR-0006 D1) ──────────────────────────────────────

(defn normalize-v1-obligation-create
  "Normalise a compatible v1 obligation-create effect into the v2 internal
   representation. v1 fields are preserved; v2-only fields receive
   deterministic defaults, and the contract reference is set to v2 so a
   v1-shaped payload cannot silently validate as v2."
  [effect]
  (let [v1 (select-keys effect [:effect/type :effect/contract
                                :obligation/type :obligation/amount :obligation/owner])]
    (-> v1
        (assoc :effect/contract :prf.effect/obligation-create.v2)
        (assoc :obligation/id (hc/domain-hash :with-bounty-obligation-v1
                                              [:obligation-create-v1
                                               (:obligation/type v1)
                                               (:obligation/amount v1)
                                               (:obligation/owner v1)]))
        (assoc :obligation/token :token/unspecified)
        (assoc :obligation/funding {})
        (assoc :obligation/subject {})
        (assoc :effect/provenance {}))))

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
     :held/direction (:effect/direction effect)
     :held/account (:effect/account effect)
     :held/kind (:held/kind effect)
     :held/amount (:effect/amount effect)}

    nil))

(defn custody-effect->add-held-opts
  "Pure projection of a custody-held-adjustment effect into the opts consumed
   by the Sew add-held primitive:
     {:action add-held
      :reason <:held/kind>
      :parameter/context ... :parameter/address ...}

   This is a projection only; it never mutates custody. A protocol adapter
   applies it via add-held. Returns nil for non-custody effects."
  [effect]
  (when (= :custody/held-adjustment (:effect/type effect))
    (merge {:action "add-held"
            :reason (:held/kind effect)}
           (when (contains? effect :parameter/context)
             {:parameter/context (:parameter/context effect)})
           (when (contains? effect :parameter/address)
             {:parameter/address (:parameter/address effect)})
           (when (contains? effect :effect/account)
             {:extra {:held/account (:effect/account effect)}}))))

;; ── canonical held-adjustment records ─────────────────────────────────────

(def held-adjustment-domain-tag
  "HELD_ADJUSTMENT_V1")

(defn- held-direction
  "Map an effect direction to the ledger's held direction (:in/:out)."
  [effect]
  (case (:effect/direction effect)
    :add :in
    :sub :out
    :in :in
    :out :out
    nil))

(defn held-adjustment
  "Build a canonical held-adjustment record from a validated
   custody-held-adjustment effect (resolver-sim.accounting.held-adjustment/
   build-held-adjustment).

   The effect's :held/kind maps to :held/reason, :effect/account to
   :held/account, and owner/parameter attribution flows through. token and the
   ledger fields (:held-adjustment/id, :held/before, :held/after,
   :held/workflow-id, :authorization/provenance) are supplied in opts; before
   and after remain ledger responsibilities.

   Throws on invalid parameter attribution (context and address must be
   supplied together). This is a value projection; it never mutates custody."
  [effect token {:keys [held-adjustment/id held/before held/after
                        held/workflow-id authorization/provenance]
                 :as opts}]
  (let [has-context (contains? effect :parameter/context)
        has-address (contains? effect :parameter/address)]
    (when-not (or (and has-context has-address)
                  (and (not has-context) (not has-address)))
      (throw (ex-info "custody effect has partial parameter attribution"
                      {:type :invalid-held-adjustment
                       :reason :partial-parameter-attribution
                       :effect effect})))
    (ha/build-held-adjustment
     (merge {:held-adjustment/id id
             :held/direction (or (held-direction effect) :in)
             :token token
             :amount (:effect/amount effect)
             :held/account (:effect/account effect)
             :held/reason (or (:held/kind effect) :held/unspecified)
             :held/action (:held/action opts "held-adjustment")
             :held/before before
             :held/after after}
            (when (contains? effect :owner/address)
              {:owner/address (:owner/address effect)})
            (when (and has-context has-address)
              {:parameter/context (:parameter/context effect)
               :parameter/address (:parameter/address effect)})
            (when workflow-id {:held/workflow-id workflow-id})
            (when provenance
              {:authorization/provenance provenance})))))

(defn add-held-adjustment
  "Build the canonical add-held held-adjustment record from a validated
   custody-held-adjustment effect: direction :add (ledger :in), action
   add-held, token, amount, account, reason from :held/kind, and
   owner/parameter attribution."
  [effect token opts]
  (held-adjustment (assoc effect :effect/direction :add)
                   token
                   (assoc opts :held/action "add-held")))

(defn held-adjustment-valid?
  "True when a custody effect can be projected to a canonical held-adjustment
   (schema-valid and no parameter-attribution error). Never throws."
  [effect]
  (let [fields {:held/direction :in
                :token :token
                :amount 0
                :held/account (:effect/account effect)
                :held/reason (or (:held/kind effect) :held/unspecified)
                :parameter/context (:parameter/context effect)
                :parameter/address (:parameter/address effect)}]
    (and (:valid? (validate-effect effect))
         (nil? (ha/held-adjustment-error fields)))))

(defn held-adjustment-root
  "Content-addressed root of a canonical held-adjustment record, for
   committing the projected adjustment into execution evidence."
  [adjustment]
  (hc/domain-hash held-adjustment-domain-tag adjustment))

(defn custody-effect-conflicts
  "Detect custody-affecting conflicts among a seq of emitted effects: two
   custody-held-adjustment effects targeting the same :effect/account with
   different directions are order-sensitive (non-commutative). Returns a
   vector of conflict descriptions (empty when none).

   This complements the compiler's compile-time :composition/custody conflict
   rejection; a protocol adapter may surface these at the evidence layer."
  [effects]
  (let [custody (vec (filter #(= :custody/held-adjustment (:effect/type %)) effects))]
    (into []
          (for [i (range (count custody))
                j (range (inc i) (count custody))
                :let [a (nth custody i) b (nth custody j)]
                :when (and (= (:effect/account a) (:effect/account b))
                           (not= (:effect/direction a) (:effect/direction b)))]
            {:conflict-kind :custody-direction
             :effect/account (:effect/account a)
             :directions [(:effect/direction a) (:effect/direction b)]}))))
