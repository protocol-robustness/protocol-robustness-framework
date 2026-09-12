(ns resolver-sim.pro-rata.canonical-effects
  "Protocol-neutral canonical quantity and signed-delta effect kernel.

   Protocol adapters project their state into `{quantity-root integer}` and
   reconstruct it afterwards. This kernel knows neither protocol fields nor
   authorization; it only defines deterministic arithmetic state evolution."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata-contract.contract :as portable-contract]))

(def semantics-schema "canonical-effects-v1")
(def effect-schema "canonical-delta-effect.v1")
(def state-schema "canonical-effect-state.v1")
(def compilation-schema "pro-rata-effect-compilation.v1")

(def semantics portable-contract/effect-semantics)

(def effect-semantics-root portable-contract/effect-semantics-root)

(defn- root? [value]
  (and (string? value) (re-matches #"(?:sha256:)?[0-9a-f]{64}" value)))

(defn state-root [state]
  (portable-contract/state-root state))

(defn effect-root [effects]
  (portable-contract/effects-root effects))

(defn delta
  "Construct the portable signed-delta primitive."
  [quantity-root signed-delta]
  (portable-contract/delta quantity-root signed-delta))

(defn normalize-effects
  "Compose all deltas for each target, discard net-zero changes, and order by target root."
  [effects]
  (portable-contract/normalize-effects effects))

(defn apply-effects
  "Apply normalized deltas atomically to portable canonical quantity state."
  [state effects]
  (portable-contract/apply-effects state effects))

(defn transition-root [transition]
  (portable-contract/transition-root transition))

(defn transition
  "Build the portable derived canonical transition; state-after is never caller-authoritative."
  [state-before raw-effects]
  (portable-contract/transition state-before raw-effects))

(defn compilation-root [compilation]
  (hc/domain-hash :pro-rata-effect-compilation
                  (select-keys compilation [:schema-version :realized-allocation/root
                                            :effect-compilation-semantics/root
                                            :effects/root])))

(defn compile-pro-rata-effects
  "Compile a realized allocation into generic deltas. Target identities are
   supplied by the protocol adapter, not inferred from protocol fields.

   `targets` maps each allocation row id to
   `{:filled/root .. :outstanding/root ..}` and provides `:liquidity/root`.
   A positive allocation consumes liquidity, increases filled quantity, and
   reduces the corresponding outstanding quantity."
  [allocation targets]
  (let [liquidity-root (:liquidity/root targets)
        rows (:rows allocation)
        effects (mapcat (fn [{:keys [allocated] :as row}]
                          (let [row-id (:row/id row)
                                target-map (get targets row-id)
                                filled-root (:filled/root target-map)
                                outstanding-root (:outstanding/root target-map)]
                            (when-not (and (root? filled-root) (root? outstanding-root))
                              (throw (ex-info "missing canonical pro-rata targets" {:row/id row-id})))
                            [(delta filled-root allocated)
                             (delta outstanding-root (- allocated))]))
                        rows)
        total (reduce + 0 (map :allocated rows))]
    (when-not (root? liquidity-root)
      (throw (ex-info "missing canonical liquidity target" {:targets targets})))
    (normalize-effects (into [(delta liquidity-root (- total))] effects))))

(defn build-pro-rata-effect-compilation
  "First-class proof boundary between a realized allocation and the generic
   effects it means. The target mapping is adapter input; the effect set itself
   is always recomputed from allocation rows and that mapping."
  [allocation targets effect-compilation-semantics-root]
  (let [allocation-root (:allocation/hash allocation)
        effects (compile-pro-rata-effects allocation targets)
        base {:schema-version compilation-schema
              :realized-allocation/root allocation-root
              :effect-compilation-semantics/root effect-compilation-semantics-root
              :effects/root (effect-root effects)}]
    (when-not (and (root? allocation-root) (root? effect-compilation-semantics-root))
      (throw (ex-info "missing pro-rata effect compilation commitment" {:allocation allocation})))
    (assoc base :effect-compilation/root (compilation-root base))))

(defn compilation-valid?
  [allocation targets compilation]
  (let [expected (build-pro-rata-effect-compilation
                  allocation targets (:effect-compilation-semantics/root compilation))]
    (= expected compilation)))
