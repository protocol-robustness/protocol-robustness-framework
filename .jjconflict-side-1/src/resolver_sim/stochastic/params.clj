(ns resolver-sim.stochastic.params
  "Param bridge: translate replay engine snapshot maps to MC param maps.

   The replay engine uses module-snapshot maps (see protocols/sew/snapshot.clj).
   The MC engine uses flat param maps (see data/params/*.edn).

   Two bridge functions serve different input sources:

     from-snap           — read a ModuleSnapshot (from make-escrow-snapshot or
                           snapshot-from-protocol-params) and extract the small
                           subset of fields that have MC equivalents.

     scenario->mc-params — read an invariant scenario map and build an MC param
                           map by merging shared fields from :protocol-params
                           with the optional :mc-params map. :mc-params wins
                           on conflict.

   Neither function produces a complete MC parameter set. Callers must merge
   the result with default-params (stochastic/types.clj) for fields not covered
   by the snapshot or scenario."

  (:require [resolver-sim.stochastic.types :as types]))

;; ── ModuleSnapshot → MC param bridge ──────────────────────────────────────────

(def snapshot->mc-keys
  "Mapping of ModuleSnapshot keys (from make-escrow-snapshot) to their MC param
   equivalents. Only fields that genuinely exist in both models are listed.
   See protocols/sew/snapshot.clj for the full ModuleSnapshot schema."
  {:escrow-fee-bps                :resolver-fee-bps
   :appeal-bond-bps               :appeal-bond-bps
   :reversal-slash-bps            :reversal-slash-bps
   :resolver-bond-bps             :resolver-bond-bps
   :reversal-detection-probability :reversal-detection-probability})

(defn from-snap
  "Translate a replay ModuleSnapshot map to MC param keys.

   Only fields that genuinely exist in a real ModuleSnapshot
   (produced by make-escrow-snapshot) are mapped. Unknown fields
   are dropped.

   Returns a map with at most 5 keys. Callers should merge this into
   their base MC param map:

     (merge default-params (params/from-snap snap))

   See snapshot->mc-keys for the full mapping."
  [snap]
  (reduce-kv (fn [m snap-key mc-key]
               (if (contains? snap snap-key)
                 (assoc m mc-key (get snap snap-key))
                 m))
             {}
             snapshot->mc-keys))

;; ── Scenario protocol-params → MC param bridge ────────────────────────────────

(defn protocol-params->mc-overrides
  "Extract MC-settable fields from scenario :protocol-params as a flat override map.
   Uses 1:1 key mapping — keys that exist in protocol-params AND are consumed
   by the MC engine pass through unchanged.  Keys absent from protocol-params
   are skipped (their value comes from types/default-params).

   Override chain (rightmost wins):
     types/default-params
       ← protocol-params->mc-overrides(:protocol-params)
       ← scenario :mc-params
       ← runtime CLI options"
  [pp]
  (let [pp (or pp {})]
    (cond-> {}
      (:resolver-fee-bps pp)
      (assoc :resolver-fee-bps (:resolver-fee-bps pp))

      (:fraud-slash-bps pp)
      (assoc :fraud-slash-bps (:fraud-slash-bps pp))

      (:timeout-slash-bps pp)
      (assoc :timeout-slash-bps (:timeout-slash-bps pp))

      (:reversal-slash-bps pp)
      (assoc :reversal-slash-bps (:reversal-slash-bps pp))

      (:fraud-detection-probability pp)
      (assoc :fraud-detection-probability (:fraud-detection-probability pp))

      (:timeout-detection-probability pp)
      (assoc :timeout-detection-probability (:timeout-detection-probability pp))

      (:reversal-detection-probability pp)
      (assoc :reversal-detection-probability (:reversal-detection-probability pp))

      (:appeal-bond-bps pp)
      (assoc :appeal-bond-bps (:appeal-bond-bps pp))

      (:slashing-detection-probability pp)
      (assoc :slashing-detection-probability (:slashing-detection-probability pp)))))

(defn scenario->mc-params
  "Build a complete MC param map from a scenario's :protocol-params
   and optional :mc-params.

   Layers (rightmost wins):
     1. types/default-params          — full 35+ key baseline
     2. protocol-params->mc-overrides — 7 fields mapped 1:1 from :protocol-params
     3. scenario :mc-params           — explicit MC-only override map

   The result is a complete MC param set ready for batch/run-batch.
   No separate merge with default-params is needed.

   Example:
     (params/scenario->mc-params scenario)"
  [scenario]
  (let [pp (:protocol-params scenario)
        mc (:mc-params scenario)]
    (merge types/default-params
           (protocol-params->mc-overrides pp)
           mc)))

(defn merge-snap
  "Merge a replay ModuleSnapshot into a base MC param map.

   Snapshot fields override base fields. Fields absent from snap
   are kept as-is. This is a convenience wrapper over from-snap.

   Example:
     (merge-snap default-params snap)"
  [base-params snap]
  (merge base-params (from-snap snap)))
