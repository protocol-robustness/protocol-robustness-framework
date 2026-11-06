(ns resolver-sim.yield.modules.none
  "Zero-yield module implementation (default/none).")

(defn none-deposit [world _ _] world)
(defn none-accrue [world _ _] world)
(defn none-withdraw [world _ _] world)

(defn make-none-module
  "Build a declarative zero-yield module record (no-op ops; no capabilities advertised)."
  ([module-id]
   (make-none-module module-id :none))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{}
    :accounting/type :none
    :ops {:yield/deposit none-deposit
          :yield/withdraw none-withdraw
          :yield/accrue none-accrue}}))

(def zero-yield-module
  (make-none-module :none))
