(ns resolver-sim.protocols.sew.invariant-scenarios.common
  "Shared protocol-param sets for invariant scenario definitions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Shared protocol-param sets
;; ---------------------------------------------------------------------------

 (def dr3
  {:governance-mode :legacy, :resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 2592000})

 (def dr3-module
  {:governance-mode :legacy, :resolver-fee-bps 150 :resolution-module "0xresolver"
   :appeal-window-duration 0 :max-dispute-duration 2592000})

 (def ieo
  {:governance-mode :legacy, :resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 2592000})

 (def ieo-timeout
  {:governance-mode :legacy, :resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 300})

 (def timeout
  {:governance-mode :legacy, :resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 300})

(def stake-cascade
  ;; Zero fee so AFA = amount; short timeout for quick testing.
  ;; resolver-bond-bps=0 skips the creation-time stake-capacity guard,
  ;; letting us register stake separately and observe it deplete under slashing.
  {:governance-mode :legacy, :resolver-fee-bps 0 :max-dispute-duration 300
   :dispute-resolver "0xresolver" :resolver-bond-bps 0})

 (def appeal
  {:governance-mode :legacy, :resolver-fee-bps 150 :appeal-window-duration 120 :max-dispute-duration 600
   :resolver-bond-bps 0})

 (def appeal-60
  {:governance-mode :legacy, :resolver-fee-bps 150 :appeal-window-duration 60 :max-dispute-duration 2592000
   :resolver-bond-bps 0})

(def kleros-resolver-fixture
  (let [fixture-path "data/fixtures/protocol/kleros.edn"
        fixture (edn/read-string (slurp (or (io/resource fixture-path) fixture-path)))]
    (select-keys fixture [:resolution-module :escalation-resolvers])))

(def kleros-defaults
  {:resolver-fee-bps 150
   :escrow-amount 5000})

(defn kleros-params
   "Build Kleros protocol params from a shared resolver fixture.
    Allows fee/window overrides while keeping module/resolver wiring centralized."
   ([] (kleros-params {}))
   ([{:keys [resolver-fee-bps appeal-window-duration max-dispute-duration]
      :or {resolver-fee-bps (:resolver-fee-bps kleros-defaults)
           appeal-window-duration 0
           max-dispute-duration 2592000}}]
     (merge {:governance-mode :legacy
             :resolver-fee-bps resolver-fee-bps
             :appeal-window-duration appeal-window-duration
             :max-dispute-duration max-dispute-duration
             :resolver-bond-bps 0}
            kleros-resolver-fixture)))

(def kleros (kleros-params))

(def kleros-appeal
  (kleros-params {:appeal-window-duration 60}))

(def kleros-default-escrow-amount
  (:escrow-amount kleros-defaults))

(defn kleros-create-escrow-event
  "Create a default USDC create_escrow event for Kleros scenarios."
  ([seq time]
   (kleros-create-escrow-event seq time {}))
  ([seq time {:keys [agent to amount token]
              :or {agent "buyer"
                   to "0xseller"
                   amount kleros-default-escrow-amount
                   token "USDC"}}]
   {:seq seq :time time :agent agent :action "create_escrow"
    :params {:token token :to to :amount amount}}))

(def scenario-author "@grifma")
