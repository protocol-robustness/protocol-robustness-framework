(ns resolver-sim.yield.module
  "Yield module record validation and lookup helpers.

   Module maps are immutable declarative records (ops + metadata).
   Runtime risk, rates, and stress live on the world, not on the module.")

(def ^:private capability->op
  {:deposit           :yield/deposit
   :withdraw          :yield/withdraw
   :withdraw-shared   :yield/withdraw-shared
   :accrue            :yield/accrue
   :emergency-unwind  :yield/emergency-unwind
   :claim-deferred    :yield/claim-deferred})

(def ^:private required-keys
  #{:module/id :module/type :module/capabilities :accounting/type :ops})

(defn validate-module
  "Validate a declarative yield module map. Returns module on success; throws ex-info on failure."
  [module]
  (when-not (map? module)
    (throw (ex-info "Yield module must be a map" {:module module})))
  (when-let [missing (seq (remove #(contains? module %) required-keys))]
    (throw (ex-info "Yield module missing required keys"
                    {:module/id (:module/id module)
                     :missing (vec missing)})))
  (let [caps (:module/capabilities module)
        ops  (:ops module)]
    (doseq [cap caps]
      (let [op-kw (capability->op cap)]
        (when-not op-kw
          (throw (ex-info "Unknown yield module capability"
                          {:module/id (:module/id module) :capability cap})))
        (when-not (fn? (get ops op-kw))
          (throw (ex-info "Yield module advertises capability without op fn"
                          {:module/id (:module/id module)
                           :capability cap
                           :op op-kw})))))
    (doseq [[op-kw f] ops]
      (when-not (fn? f)
        (throw (ex-info "Yield module op must be a function"
                        {:module/id (:module/id module) :op op-kw})))))
  module)

(defn module-capable?
  [module capability]
  (contains? (:module/capabilities module) capability))

(defn describe-module
  [module]
  {:module/id           (:module/id module)
   :module/type         (:module/type module)
   :accounting/type     (:accounting/type module)
   :capabilities        (vec (sort (:module/capabilities module)))
   :ops                 (vec (sort (keys (:ops module))))})

(defn resolve-module-id
  "Resolve a profile or registry module id to the archetype id used for risk/indices paths."
  [world module-id]
  (let [mid (cond
              (keyword? module-id) module-id
              (string? module-id)  (keyword module-id)
              :else                module-id)]
    (get-in world [:yield/module-aliases mid] mid)))
