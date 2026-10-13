(ns resolver-sim.protocols.sew.snapshot-presets
  "Named reusable Sew ModuleSnapshot configurations for tests and scenarios.

   Prefer `preset->snapshot` / `preset->protocol-params` over ad hoc
   `make-escrow-snapshot` calls in tests (see `snapshot-fixtures` in test/).

   Yield stress belongs in `yield/presets`, `yield-config`, or runtime risk updates —
   not in Sew snapshot presets."
  (:require [resolver-sim.protocols.sew.snapshot :as snap]
            [resolver-sim.time.context :as time-ctx]))

(def protocol-param-presets
  "Maps preset id → protocol-params fragment (passed to `snapshot-from-protocol-params`)."
  {:sew.preset/baseline
   {:resolver-fee-bps 50
    :max-dispute-duration 2592000
    :appeal-window-duration 0
    :resolver-bond-bps 1000}

   :sew.preset/moderate-dispute
   {:resolver-fee-bps 50
    :max-dispute-duration 2592000
    :appeal-window-duration 14400
    :appeal-bond-bps 250
    :resolver-bond-bps 1500
    :reversal-slash-bps 500}

   :sew.preset/dispute-heavy
   {:resolver-fee-bps 50
    :max-dispute-duration 2592000
     :appeal-window-duration time-ctx/seconds-per-day
    :appeal-bond-bps 500
    :resolver-bond-bps 2000
    :appeal-bond-protocol-fee-bps 100}

   :sew.preset/yield-aave
   {:resolver-fee-bps 50
    :max-dispute-duration 2592000
    :yield-profile :aave-v3}

   :sew.preset/zero-fee
   {:resolver-fee-bps 0
    :max-dispute-duration 2592000}})

(defn preset->protocol-params
  [preset-id]
  (get protocol-param-presets preset-id))

(defn preset->snapshot
  "Return a snapshot for a preset id (validated by default).

   Optional second arg opts forwarded to `snapshot-from-protocol-params`:
     `{:world w}` — registry check; `{:validate? false}` — skip validation."
  ([preset-id]
   (preset->snapshot preset-id nil))
  ([preset-id opts]
   (if-let [pp (preset->protocol-params preset-id)]
     (snap/snapshot-from-protocol-params pp opts)
     (throw (ex-info "Unknown Sew snapshot preset"
                     {:error/type :snapshot/unknown-preset
                      :preset-id preset-id
                      :hint "Use a key from protocol-param-presets, e.g. :sew.preset/baseline"})))))
