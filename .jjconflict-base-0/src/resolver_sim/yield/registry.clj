(ns resolver-sim.yield.registry
  "Generic yield module registry and dispatch system.

   This registry manages yield providers and their configurations within the
   simulation world state. It is designed to be protocol-agnostic, provided
   the simulation state follows the standard yield engine keys (e.g., :yield/*)."
  (:require [resolver-sim.yield.module :as ymodule]
            [resolver-sim.yield.modules.fixed :as fixed]
            [resolver-sim.yield.modules.none :as none]
            [resolver-sim.yield.modules.adversarial :as adversarial]
            [resolver-sim.yield.modules.liquid-lending :as liquid-lending]
            [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.schedule :as schedule]
            [resolver-sim.logging :as log]))

(def default-behavior-descriptor
  {:yield/provider-kind :immediate-withdrawal-lending
   :yield/accrual-model :deterministic-rate
   :yield/liquidity-model :full-liquidity
   :yield/loss-model :no-loss
   :yield/withdrawal-model :instant
   :yield/reward-model :none})

(defn- archetype->module-id [archetype]
  (case archetype
    :yield.provider/liquid-lending :yield.provider/liquid-lending
    nil))

(defn- profile->archetype [profile-id]
  (case profile-id
    :aave-v3 :yield.provider/liquid-lending
    :aave-v3-derisk :yield.provider/liquid-lending
    :yield.profile/aave-v3-like :yield.provider/liquid-lending
    :aave-v3-v2 :yield.provider/liquid-lending
    :yield.profile/aave-v3-like-v2 :yield.provider/liquid-lending
    nil))

(defn- normalize-module-id [module-id]
  (cond
    (keyword? module-id) module-id
    (string? module-id)  (keyword module-id)
    :else                module-id))

(defn resolve-yield-profile
  "Resolve a profile/module id into {:profile-id :archetype :module-id}.
   Falls back to the provided id when no explicit profile mapping is known."
  [profile-or-module-id]
  (let [profile-id (normalize-module-id profile-or-module-id)
        archetype  (profile->archetype profile-id)
        module-id  (or (archetype->module-id archetype) profile-id)]
    {:profile-id profile-id
     :archetype  archetype
     :module-id  module-id}))

(def default-modules
  "Registry module records — each built by an archetype constructor (see yield.modules.*)."
  {:yield.provider/liquid-lending
   (liquid-lending/make-liquid-lending-module :yield.provider/liquid-lending)

   :aave-v3
   (liquid-lending/make-liquid-lending-module :aave-v3 :yield.profile/aave-v3-like)

   :aave-v3-derisk
   (liquid-lending/make-liquid-lending-module :aave-v3-derisk :yield.profile/aave-v3-like)

   :fixed-rate
   (fixed/make-fixed-module :fixed-rate)

   :none
   (none/make-none-module :none)

   :adversarial
   (adversarial/make-adversarial-module :adversarial)})

(defn register-module
  "Register a validated yield module on the world."
  [world module]
  (let [m (ymodule/validate-module module)]
    (assoc-in world [:yield/modules (:module/id m)] m)))

(defn init-yield-modules [world]
  (reduce register-module world (vals default-modules)))

(defn- normalize-schedule [sch]
  (cond
    (nil? sch) nil
    (and (map? sch) (= (:type sch) "external"))
    (some-> (schedule/load-external-json (:path sch))
            (update :type #(if (string? %) (keyword %) %)))
    (map? sch) (update sch :type #(if (string? %) (keyword %) %))
    :else sch))

(defn- normalize-policy-map [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [k (if (string? v) (keyword v) v)]) m))))

(defn- normalize-token-config [{:keys [initial-index initial-index-ray apy apy-bps
                                       liquidity-mode loss-mode rate-mode
                                       failure-modes shortfall
                                       rate-schedule index-schedule liquidity-schedule module-state-schedule
                                       shortfall-model withdrawal-policy]
                                :as cfg}]
  {:initial-index     (or initial-index
                          (when initial-index-ray
                            (/ (bigdec initial-index-ray)
                               1000000000000000000000000000M))
                          1)
   :apy               (or apy
                          (when apy-bps (/ (double apy-bps) 10000.0))
                          0.0)
   :liquidity-mode    (let [m (or liquidity-mode :available)]
                        (if (string? m) (keyword m) m))
   :loss-mode         (let [m (let [raw (or loss-mode :none)]
                                (if (string? raw) (keyword raw) raw))
                            failure-modes* (risk/normalize-failure-modes failure-modes)]
                        (if (and (= m :none)
                                 (contains? failure-modes* :negative-yield))
                          :mark-to-market
                          m))
   :rate-mode         (let [m (or rate-mode :deterministic)]
                        (if (string? m) (keyword m) m))
   :failure-modes     (risk/normalize-failure-modes failure-modes)
   :shortfall         shortfall
   ;; Fully data-driven yield engine configuration:
   ;; Encompasses market schedules, withdrawal policies, and failure modes.
   :schedules         {:rate-schedule         (normalize-schedule rate-schedule)
                       :index-schedule        (normalize-schedule index-schedule)
                       :liquidity-schedule    (normalize-schedule liquidity-schedule)
                       :module-state-schedule (normalize-schedule module-state-schedule)}
   ;; New policies
   :shortfall-model   (normalize-policy-map shortfall-model)
   :withdrawal-policy (normalize-policy-map withdrawal-policy)})

(defn- normalize-behavior-descriptor [desc]
  (merge default-behavior-descriptor (or desc {})))

(defn- normalize-module-entry [module-id module-config]
  (let [mid* (normalize-module-id module-id)
        behavior (normalize-behavior-descriptor (:behavior module-config))
        archetype (or (:provider/archetype module-config)
                      (:provider/id module-config)
                      (profile->archetype mid*)
                      (get behavior :provider/archetype))
        resolved-mid (or (archetype->module-id archetype) mid*)]
    {:module-id mid*
     :resolved-module-id resolved-mid
     :behavior behavior
     :module-status (:module-status module-config)
     :tokens (:tokens module-config {})}))

(defn apply-yield-config
  "Apply scenario-level yield configuration to an initialized world.

   Expected DSL shape:

   Example shape:
   modules -> module id -> tokens -> token symbol -> config map.

   Also accepts :initial-index-ray and :apy-bps for integer/fixed-point-facing
   fixture definitions. This config initializes the existing generic yield DSL
   world paths rather than introducing a second test-only API."
  [world yield-config]
  (reduce-kv
   (fn [w raw-module-id module-config]
     (let [{normalized-module-id :module-id
            resolved-module-id   :resolved-module-id
            behavior             :behavior
            module-status        :module-status
            tokens               :tokens}
           (normalize-module-entry raw-module-id module-config)
           w* (-> w
                  (assoc-in [:yield/behavior normalized-module-id] behavior)
                  (assoc-in [:yield/module-aliases normalized-module-id] resolved-module-id)
                  (assoc-in [:yield/module-aliases resolved-module-id] resolved-module-id))
           w* (if-let [status module-status]
                (assoc-in w* [:yield/module-status resolved-module-id]
                          (if (string? status) (keyword status) status))
                w*)]
       (reduce-kv
        (fn [w' token token-config]
          (let [kw-token (keyword token)
                {:keys [initial-index apy liquidity-mode loss-mode rate-mode failure-modes shortfall
                        schedules shortfall-model withdrawal-policy]}
                (normalize-token-config token-config)]
            (let [w' (-> w'
                         (assoc-in [:yield/indices resolved-module-id kw-token] initial-index)
                         (assoc-in [:yield/rates resolved-module-id kw-token] apy)
                         (assoc-in [:yield/schedules resolved-module-id kw-token] schedules)
                         (assoc-in [:yield/shortfall-models resolved-module-id kw-token] shortfall-model)
                         (assoc-in [:yield/withdrawal-policies resolved-module-id kw-token] withdrawal-policy)
                         (assoc-in [:yield/risk resolved-module-id kw-token]
                                   {:liquidity-mode liquidity-mode
                                    :loss-mode      loss-mode
                                    :rate-mode      rate-mode
                                    :failure-modes  failure-modes
                                    :shortfall      shortfall}))]
              (log/debug! "stored-schedule" {:module resolved-module-id
                                             :token kw-token
                                             :schedules schedules})
              w')))
        w*
        tokens)))
   world
   (:modules yield-config {})))
