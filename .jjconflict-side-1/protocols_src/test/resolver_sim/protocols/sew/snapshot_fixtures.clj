(ns resolver-sim.protocols.sew.snapshot-fixtures
  "Test helpers for Sew ModuleSnapshot maps.

   Prefer these over `types/make-module-snapshot` (deprecated).

   See `docs/architecture/YIELD_AND_SNAPSHOT_MODULES.md`."
  (:require [resolver-sim.protocols.sew.snapshot :as snap]
            [resolver-sim.protocols.sew.snapshot-presets :as presets]))

(defn escrow-snapshot
  "Build a validated ModuleSnapshot for tests.

   `params` — keys for `make-escrow-snapshot` (e.g. :escrow-fee-bps, :appeal-window-duration).

   Optional first arg `preset-id` (e.g. `:sew.preset/baseline`) merges a named preset first.

   Options map (last arg when using preset, or second arg without preset):
     :validate? false — skip validation (intentionally malformed fixtures only)
     :world / :validate-world? — registry check for :yield-generation-module"
  ([params]
   (escrow-snapshot nil params nil))
  ([preset-id params]
   (if (map? preset-id)
     (escrow-snapshot nil preset-id params)
     (escrow-snapshot preset-id params nil)))
  ([preset-id params opts]
   (let [base (when (and preset-id (not (map? preset-id)))
                (presets/preset->snapshot preset-id (assoc opts :validate? false)))
         merged (if base (merge base params) params)
         snap (snap/make-escrow-snapshot merged)]
     (if (false? (:validate? opts))
       snap
       (snap/validate-snapshot snap (cond-> {}
                                      (:world opts) (assoc :world (:world opts))
                                      (:validate-world? opts) (assoc :world (:world opts))))))))

(defn protocol-params-snapshot
  "Snapshot from protocol-params shape (`:resolver-fee-bps` → `:escrow-fee-bps`, yield profile resolution).

   Same as `snapshot/snapshot-from-protocol-params`."
  [protocol-params & [opts]]
  (apply snap/snapshot-from-protocol-params protocol-params opts))

(defn baseline-snapshot
  "`:sew.preset/baseline` with optional snapshot-field overrides."
  ([] (presets/preset->snapshot :sew.preset/baseline))
  ([overrides]
   (escrow-snapshot :sew.preset/baseline overrides)))

(defn zero-fee-snapshot
  ([] (presets/preset->snapshot :sew.preset/zero-fee))
  ([overrides]
   (escrow-snapshot :sew.preset/zero-fee overrides)))

(defn yield-aave-snapshot
  ([] (presets/preset->snapshot :sew.preset/yield-aave))
  ([overrides]
   (escrow-snapshot :sew.preset/yield-aave overrides)))
