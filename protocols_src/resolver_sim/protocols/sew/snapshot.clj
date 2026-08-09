(ns resolver-sim.protocols.sew.snapshot
  "Sew per-escrow ModuleSnapshot construction, validation, and protocol-params mapping.

   ## Canonical usage

   * **Production-like scenarios / replay** — `snapshot-from-protocol-params` on
     `protocol-params` (validates by default; optional `{:world w}` registry check).
   * **Named test fixtures** — `snapshot-presets/preset->snapshot` or
     `preset->protocol-params`.
   * **Deprecated** — `types/make-module-snapshot` (alias of `make-escrow-snapshot`).

   Yield *modules* (transition graphs) live in `resolver-sim.yield.modules.*`.
   Yield *stress* (negative yield, shortfall, shocks) belongs in `yield/presets`,
   `yield-config`, `set-yield-risk`, or market-shock events — not in snapshot builders.

   Authority *resolution modules* (`authority/make-default-resolution-module`, etc.)
   are a separate concept from yield modules and escrow snapshots."
  (:require [resolver-sim.yield.protocols :as yield-proto]
            [resolver-sim.protocols.sew.types :as types]))

(def ^:private nat-fields
  "Fields validated as non-negative integers.
   Fields in BOTH nat-fields and bps-fields (e.g. :escrow-fee-bps)
   get validated twice — once as a non-negative integer and once as
   ≤ 10000 bps.  This is intentional: fee fields are integer amounts
   AND bps-constrained."
  #{:escrow-fee-bps :appeal-bond-protocol-fee-bps :yield-protocol-fee-bps
    :default-auto-release-delay :default-auto-cancel-delay
    :max-dispute-duration :appeal-window-duration :evidence-window-duration
    :resolver-response-window
    :appeal-bond-bps :resolver-bond-bps :appeal-bond-amount
    :reversal-slash-bps :challenge-window-duration
    :challenge-bond-bps :challenge-bounty-bps})

(def ^:private bps-fields
  "Fields validated as ≤ 10000 bps.
   Fields in BOTH sets (overlap with nat-fields above is intentional)."
  #{:escrow-fee-bps :appeal-bond-protocol-fee-bps :yield-protocol-fee-bps
    :appeal-bond-bps :resolver-bond-bps :reversal-slash-bps
    :challenge-bond-bps :challenge-bounty-bps})

(defn- snapshot-validation-error
  [message extra]
  (let [error-type (or (:error-type extra) :snapshot/validation-failure)
        field      (:snapshot/field extra)
        expected   (:expected extra)
        actual     (:actual extra)
        hint       (:hint extra)]
    (ex-info message
             (cond-> {:error/type error-type}
               field (assoc :snapshot/field field)
               (contains? extra :expected) (assoc :expected expected)
               (contains? extra :actual) (assoc :actual actual)
               hint (assoc :hint hint)))))

(defn make-escrow-snapshot
  "Construct a ModuleSnapshot map (frozen at create_escrow). Does not validate.

   Numeric fee/timeout fields use integer (uint256 semantics). Stress modes
   (negative yield, shortfall, etc.) belong in yield-config / world risk, not here.

   Prefer `snapshot-from-protocol-params` for scenario/replay params maps."
  [{:keys [resolution-module release-strategy cancellation-strategy
           yield-generation-module yield-distribution-module incentive-module
           yield-module-id yield-profile yield-archetype escrow-modules
           yield-protocol-fee-bps appeal-bond-protocol-fee-bps escrow-fee-bps
            default-auto-release-delay default-auto-cancel-delay
            max-dispute-duration appeal-window-duration dispute-resolver
            resolver-response-window
            appeal-bond-bps resolver-bond-bps appeal-bond-amount
            reversal-slash-bps reversal-detection-probability
            challenge-window-duration challenge-bond-bps challenge-bounty-bps
            evidence-window-duration]}]
  {:resolution-module              resolution-module
   :release-strategy                 release-strategy
   :cancellation-strategy          cancellation-strategy
   :escrow/modules                   escrow-modules  ;; ← destructured as `escrow-modules` from input
   :yield-module-id                  yield-module-id
   :yield-profile                    yield-profile
   :yield-archetype                  yield-archetype
   :yield-generation-module          yield-generation-module
   :yield-distribution-module        yield-distribution-module
   :incentive-module                 incentive-module
   :yield-protocol-fee-bps           (or yield-protocol-fee-bps 0)
   :appeal-bond-protocol-fee-bps     (or appeal-bond-protocol-fee-bps 0)
   :escrow-fee-bps                   (or escrow-fee-bps 0)
   :default-auto-release-delay       (or default-auto-release-delay 0)
   :default-auto-cancel-delay        (or default-auto-cancel-delay 0)
   :max-dispute-duration             (or max-dispute-duration 0)
   :appeal-window-duration           (or appeal-window-duration 0)
   :resolver-response-window         (or resolver-response-window 0)
   :dispute-resolver                 dispute-resolver
   :appeal-bond-bps                  (or appeal-bond-bps 0)
   :resolver-bond-bps                (or resolver-bond-bps 0)
   :appeal-bond-amount               (or appeal-bond-amount 0)
    :reversal-slash-bps               (or reversal-slash-bps 0)
    :reversal-detection-probability   (or reversal-detection-probability 0.0)
    :evidence-window-duration         (or evidence-window-duration 0)
    :challenge-window-duration        (or challenge-window-duration 0)
    :challenge-bond-bps               (or challenge-bond-bps 0)
    :challenge-bounty-bps             (or challenge-bounty-bps 0)})


(defn- non-negative-int?
  [v]
  (and (number? v) (not (neg? (long v))) (zero? (- v (long v)))))

(defn- keyword-id?
  [v]
  (or (nil? v) (keyword? v)))

(defn- yield-fields-consistent?
  "Profile id (:aave-v3) may differ from archetype module id (:yield.provider/liquid-lending).
   Generation module may be either the registry profile key or the resolved archetype id."
  [{:keys [yield-profile yield-archetype yield-generation-module]}]
  (let [seed (or yield-profile yield-generation-module)]
    (if seed
      (let [{:keys [profile-id archetype module-id]}
            (yield-proto/resolve-yield-profile seed)]
        (and (or (nil? yield-profile)
                 (= yield-profile profile-id))
             (or (nil? yield-archetype)
                 (= yield-archetype archetype))
             (or (nil? yield-generation-module)
                 (= yield-generation-module module-id)
                 (= yield-generation-module profile-id))))
      true)))

(defn validate-snapshot
  "Validate a ModuleSnapshot map. Returns snapshot on success.

   On failure throws `ex-info` with structured data:
     :error/type     — keyword (e.g. `:snapshot/non-negative-integer`)
     :snapshot/field — field keyword when applicable
     :expected       — constraint description
     :actual         — offending value
     :hint           — optional fix guidance

   Options:
     :world — when provided, require :yield-generation-module (if set) to exist in
              [:yield/modules] (after registry init)."
  ([snapshot] (validate-snapshot snapshot nil))
  ([snapshot {:keys [world]}]
   (when-not (map? snapshot)
     (throw (snapshot-validation-error
             "Escrow snapshot must be a map"
             {:error-type :snapshot/invalid-type
              :actual (type snapshot)
              :hint "Pass a map from make-escrow-snapshot or snapshot-from-protocol-params"})))
   (doseq [k nat-fields]
     (when-let [v (get snapshot k)]
       (when-not (non-negative-int? v)
         (throw (snapshot-validation-error
                 "Escrow snapshot field must be a non-negative integer"
                 {:error-type :snapshot/non-negative-integer
                  :snapshot/field k
                  :expected "non-negative integer"
                  :actual v
                  :hint "Use a non-negative integer fee, duration, or bps value"})))))
   (doseq [k bps-fields]
     (when-let [v (get snapshot k)]
       (when (> (long v) 10000)
         (throw (snapshot-validation-error
                 "Escrow snapshot bps field exceeds 10000"
                 {:error-type :snapshot/bps-out-of-range
                  :snapshot/field k
                  :expected "<= 10000 bps"
                  :actual v})))))
   (let [prob (:reversal-detection-probability snapshot 0.0)]
      ;; reversal-detection-probability is the only float-valued snapshot field;
      ;; all other numeric fields use integer (uint256) semantics.
     (when (or (< prob 0.0) (> prob 1.0))
       (throw (snapshot-validation-error
               "reversal-detection-probability must be in [0, 1]"
               {:error-type :snapshot/probability-out-of-range
                :snapshot/field :reversal-detection-probability
                :expected "[0.0, 1.0]"
                :actual prob}))))
   (doseq [k [:yield-generation-module :yield-profile :yield-archetype :yield-module-id]]
     (when-let [v (get snapshot k)]
       (when-not (keyword-id? v)
         (throw (snapshot-validation-error
                 "Yield snapshot id must be a keyword or nil"
                 {:error-type :snapshot/invalid-yield-id
                  :snapshot/field k
                  :expected "keyword or nil"
                  :actual v})))))
   (when-not (yield-fields-consistent? snapshot)
     (let [{:keys [profile-id archetype module-id]}
           (yield-proto/resolve-yield-profile
            (or (:yield-profile snapshot) (:yield-generation-module snapshot)))]
       (throw (snapshot-validation-error
               "Yield profile, archetype, and generation-module are inconsistent"
               {:error-type :snapshot/yield-fields-inconsistent
                :expected {:yield-profile profile-id
                           :yield-archetype archetype
                           :yield-generation-module module-id}
                :actual {:yield-profile (:yield-profile snapshot)
                         :yield-archetype (:yield-archetype snapshot)
                         :yield-generation-module (:yield-generation-module snapshot)}
                :hint "Use snapshot-from-protocol-params or align ids via resolve-yield-profile"}))))
   (when world
     (when-let [ymid (:yield-generation-module snapshot)]
       (when-not (contains? (:yield/modules world {}) ymid)
         (throw (snapshot-validation-error
                 "Yield generation module not registered on world"
                 {:error-type :snapshot/yield-module-not-registered
                  :snapshot/field :yield-generation-module
                  :expected "module id present in [:yield/modules]"
                  :actual ymid
                  :hint "Call yield-reg/init-yield-modules before validating with :world"})))))
   snapshot))

(defn snapshot-from-protocol-params
  "Build a snapshot from a scenario/protocol-params map.

   Maps :resolver-fee-bps → :escrow-fee-bps. Resolves yield profile/module ids
   via the yield registry (same logic as the former sew/build-snapshot helper).

   Note: the :escrow-modules map is always constructed from individual protocol-params
   keys (:resolution-module, :release-strategy, etc.); any :escrow-modules key in
   protocol-params is ignored.

   Options (second argument):
     :validate? true (default) — run `validate-snapshot`; set false only for legacy
       tests or intentionally malformed fixture construction.
     :world — passed to `validate-snapshot` when validating.
     :validate-world? — when true, same as supplying :world for registry check."
  [protocol-params & [{:keys [world validate-world? validate?] :as opts}]]
  (require '[resolver-sim.protocols.sew.types :as types])
  (let [pp (or protocol-params {})
        yield-id (or (get pp :yield-generation-module nil)
                     (get pp :yield-profile nil))
        {:keys [profile-id archetype module-id]}
        (yield-proto/resolve-yield-profile yield-id)
        snap (make-escrow-snapshot
              {               :escrow-fee-bps         (get pp :resolver-fee-bps 100)
               :resolution-module            (get pp :resolution-module nil)
               :appeal-window-duration       (get pp :appeal-window-duration 0)
               :max-dispute-duration         (get pp :max-dispute-duration types/default-max-dispute-duration)
               :resolver-response-window     (get pp :resolver-response-window 0)
               :appeal-bond-protocol-fee-bps (get pp :appeal-bond-protocol-fee-bps 0)
               :dispute-resolver             (get pp :dispute-resolver nil)
               :appeal-bond-bps              (get pp :appeal-bond-bps 0)
               :resolver-bond-bps            (get pp :resolver-bond-bps 1000)
               :appeal-bond-amount           (get pp :appeal-bond-amount 0)
               :reversal-slash-bps           (get pp :reversal-slash-bps 0)
               :reversal-detection-probability (get pp :reversal-detection-probability 0.0)
                :evidence-window-duration     (get pp :evidence-window-duration 0)
                :challenge-window-duration    (get pp :challenge-window-duration 0)
                :challenge-bond-bps           (get pp :challenge-bond-bps 0)
               :challenge-bounty-bps         (get pp :challenge-bounty-bps 0)
               :default-auto-release-delay   (get pp :default-auto-release-delay 0)
               :default-auto-cancel-delay    (get pp :default-auto-cancel-delay 0)
               :escrow-modules               {:resolution (get pp :resolution-module nil)
                                              :yield      profile-id
                                              :release    (get pp :release-strategy nil)
                                              :cancel     (get pp :cancellation-strategy nil)}
               :yield-module-id              (or (get pp :yield-module-id) profile-id)
               :yield-profile                profile-id
               :yield-archetype              archetype
               :yield-generation-module      module-id
               :yield-distribution-module    (get pp :yield-distribution-module nil)
               :yield-protocol-fee-bps       (get pp :yield-protocol-fee-bps 0)
               :cancellation-strategy        (get pp :cancellation-strategy nil)
               :release-strategy             (get pp :release-strategy nil)
               :incentive-module             (get pp :incentive-module nil)})]
    (if (false? validate?)
      snap
      (validate-snapshot snap (cond-> opts
                                (or validate-world? world) (assoc :world world))))))
