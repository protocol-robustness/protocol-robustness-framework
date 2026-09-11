(ns resolver-sim.protocol-alignment)

(def protocol-statuses
  #{:protocol/current
    :protocol/known-gap
    :protocol/proposed
    :protocol/experimental
    :protocol/deprecated})

(def solidity-statuses
  #{:solidity/implemented
    :solidity/current-behaviour
    :solidity/not-implemented
    :solidity/not-applicable})

(def scenario-kinds
  #{:finding-reproduction
    :mitigation-validation
    :regression
    :exploration})

(defn valid-protocol-status?
  [status]
  (contains? protocol-statuses status))

(defn valid-solidity-status?
  [status]
  (contains? solidity-statuses status))

(defn proposed?
  [m]
  (= :protocol/proposed (:protocol/status m)))

(defn current?
  [m]
  (= :protocol/current (:protocol/status m)))

(defn known-gap?
  [m]
  (= :protocol/known-gap (:protocol/status m)))
