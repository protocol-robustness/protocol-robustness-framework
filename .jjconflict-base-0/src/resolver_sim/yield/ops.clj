(ns resolver-sim.yield.ops
  "Operation dispatch and main entry points for yield transitions.

   Liveness contract: a missing or explicitly-unavailable yield module must never
   block a caller (e.g. settlement). Such ops are applied as a liveness-preserving
   no-op — the world is returned unchanged with the skip recorded — rather than
   throwing. A present module that does not implement the requested op still throws.")

(def ^:private unavailable-statuses
  "Module statuses that indicate the module cannot serve ops but must not halt a
   caller. Settlement must be able to proceed past an unavailable module."
  #{:unavailable :halted})

(defn- get-module [world module-id]
  (get-in world [:yield/modules module-id]))

(defn- module-status [world module-id]
  (get-in world [:yield/module-status module-id]))

(defn module-unavailable?
  "True when the module record is absent or its status marks it unavailable.
   Missing modules degrade to unavailable so the caller is never blocked."
  [world module-id]
  (or (nil? (get-module world module-id))
      (contains? unavailable-statuses (module-status world module-id))))

(defn- record-skipped-op
  "Preserve liveness: return the world unchanged but record the skipped op so the
   unavailability remains observable to callers and evidence pipelines."
  [world op]
  (update-in world [:yield/skipped-ops]
             (fnil conj [])
             {:module/id (:module/id op)
              :op/type   (:op/type op)}))

(defn apply-yield-op
  "Apply a yield operation to the world state.
   op: {:op/type :yield/deposit :owner/id id :module/id mid :amount a :token t ...}

   Liveness-preserving: if the target module is missing or unavailable, the op is
   a no-op (world returned unchanged, skip recorded) instead of throwing. This
   guarantees settlement can always proceed even when a yield module is absent.
   A present module that does not implement the requested op still throws."
  [world op]
  (let [module-id (:module/id op)
        module    (get-module world module-id)]
    (cond
      (module-unavailable? world module-id)
      (record-skipped-op world op)

      (get-in module [:ops (:op/type op)])
      ((get-in module [:ops (:op/type op)]) world module op)

      :else
      (throw (ex-info "Unsupported yield op"
                      {:op op
                       :module module})))))

(defn accrue-module
  "Advance yield for a module. Usually triggered by time advance."
  [world module-id accrual-event]
  (apply-yield-op world (assoc accrual-event
                               :op/type :yield/accrue
                               :module/id module-id)))
