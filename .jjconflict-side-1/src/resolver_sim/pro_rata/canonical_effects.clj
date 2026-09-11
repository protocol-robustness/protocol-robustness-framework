(ns resolver-sim.pro-rata.canonical-effects
  "Protocol-neutral canonical quantity and signed-delta effect kernel.

   Protocol adapters project their state into `{quantity-root integer}` and
   reconstruct it afterwards. This kernel knows neither protocol fields nor
   authorization; it only defines deterministic arithmetic state evolution."
  (:require [resolver-sim.hash.canonical :as hc]))

(def semantics-schema "canonical-effects-v1")
(def effect-schema "canonical-delta-effect.v1")
(def state-schema "canonical-effect-state.v1")
(def compilation-schema "pro-rata-effect-compilation.v1")

(def semantics
  {:schema-version semantics-schema
   :integer-domain :signed-arbitrary-precision
   :quantity-domain :non-negative-integer
   :underflow :forbidden
   :overflow :impossible
   :ordering :canonical-target-order
   :emission-order :irrelevant
   :duplicate-targets :compose
   :composition :atomic-net-delta
   :failure :reject-entire-transition})

(def effect-semantics-root
  (hc/domain-hash :canonical-effects-v1 semantics))

(defn- root? [value]
  (and (string? value) (re-matches #"(?:sha256:)?[0-9a-f]{64}" value)))

(defn state-root [state]
  (hc/domain-hash :canonical-effect-state
                  {:schema-version state-schema
                   :quantities (into (sorted-map) state)}))

(defn effect-root [effects]
  (hc/domain-hash :canonical-effect-set
                  {:schema-version effect-schema
                   :effect-semantics/root effect-semantics-root
                   :effects effects}))

(defn delta
  "Human-readable add/subtract adapters should compile to this sole primitive."
  [quantity-root signed-delta]
  (when-not (and (root? quantity-root) (integer? signed-delta))
    (throw (ex-info "invalid canonical delta effect"
                    {:quantity-root quantity-root :delta signed-delta})))
  {:schema-version effect-schema
   :quantity/root quantity-root
   :delta signed-delta})

(defn normalize-effects
  "Compose all deltas for each target, discard net-zero changes, and order by
   target root. This makes the effect root independent of raw emission order."
  [effects]
  (let [deltas (reduce (fn [acc {:keys [schema-version quantity/root delta] :as effect}]
                         (when-not (and (= effect-schema schema-version)
                                        (root? root) (integer? delta))
                           (throw (ex-info "invalid canonical effect" {:effect effect})))
                         (update acc root (fnil + 0) delta))
                       {} effects)]
    (mapv (fn [[target amount]] (delta target amount))
          (remove (comp zero? val) (sort-by key deltas)))))

(defn apply-effects
  "Apply normalized deltas to a protocol-neutral canonical quantity state.
   Every known value and resulting value must be non-negative; a failed target
   rejects the entire transition rather than producing a partial state."
  [state effects]
  (when-not (every? (fn [[target value]] (and (root? target)
                                              (integer? value)
                                              (not (neg? value)))) state)
    (throw (ex-info "invalid canonical effect state" {:state state})))
  (reduce (fn [next-state {:keys [quantity/root delta]}]
            (let [before (get next-state root 0)
                  after (+ before delta)]
              (when (neg? after)
                (throw (ex-info "canonical effect underflow"
                                {:quantity/root root :before before :delta delta})))
              (assoc next-state root after)))
          state (normalize-effects effects)))

(defn transition-root [transition]
  (hc/domain-hash :canonical-effect-transition
                  (select-keys transition [:schema-version :effect-semantics/root
                                           :state-before/root :effects/root
                                           :state-after/root])))

(defn transition
  "Build a fully derived generic transition. `state-after/root` is never an
   input: it is recomputed from `state-before` and canonical effects."
  [state-before raw-effects]
  (let [effects (normalize-effects raw-effects)
        state-after (apply-effects state-before effects)]
    (let [base {:schema-version "canonical-effect-transition.v1"
                :effect-semantics/root effect-semantics-root
                :state-before/root (state-root state-before)
                :effects/root (effect-root effects)
                :state-after/root (state-root state-after)
                :effects effects
                :state-after state-after}]
      (assoc base :canonical-effect-transition/root (transition-root base)))))

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
