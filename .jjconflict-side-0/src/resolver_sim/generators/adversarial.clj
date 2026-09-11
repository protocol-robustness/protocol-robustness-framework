(ns resolver-sim.generators.adversarial
  "Adversarial profile semantics for stateful generation.

   Encodes both action-priority policy and time-shaping policy so profiles
   are more than simple ordering maps.

   Provider status (2026-06):
   ┌──────────────────────┬─────────────────────────────┬──────────────────────┐
   │ Function             │ Dispatch model               │ Wired provider       │
   ├──────────────────────┼─────────────────────────────┼──────────────────────┤
   │ profile-priority     │ conditional Sew delegate     │ sew-v1 only          │
   │ valid-next-actions   │ case on proto/protocol-id    │ sew-v1 only          │
   │ next-time            │ conditional Sew delegate     │ sew-v1 only          │
   └──────────────────────┴─────────────────────────────┴──────────────────────┘

   Providers are loaded lazily via `requiring-resolve` so the dispatch layer
   loads cleanly in prf-only workspace mode. When the provider is absent,
   profile-priority returns 999 and next-time increments by 1 — both safe
   defaults. To add a new protocol, add a case branch and a protocol-scoped
   implementation."
  (:require [resolver-sim.protocols.protocol :as proto]))

(def ^:private sew-profile-priority
  (delay (try (requiring-resolve 'resolver-sim.generators.sew.adversarial/profile-priority)
              (catch java.io.FileNotFoundException _ nil))))

(defn profile-priority
  [profile action]
  (if-let [f @sew-profile-priority]
    (f profile action)
    999))

(def ^:private sew-valid-next-actions
  (delay (try (requiring-resolve 'resolver-sim.generators.sew.adversarial/valid-next-actions)
              (catch java.io.FileNotFoundException _ nil))))

(defn valid-next-actions
  "Delegates to canonical action validity and applies adversarial profile sort bias."
  [profile protocol context world seq time]
  ;; Protocol dispatch: add a case branch for each supported protocol.
  ;; Non-wired protocols return [] (no valid actions).
  (case (proto/protocol-id protocol)
    "sew-v1" (if-let [f @sew-valid-next-actions]
               (f profile protocol context world seq time)
               [])
    []))

(def ^:private sew-next-time
  (delay (try (requiring-resolve 'resolver-sim.generators.sew.adversarial/next-time)
              (catch java.io.FileNotFoundException _ nil))))

(defn next-time
  "Return next event timestamp according to profile semantics.
   - :same-block-ordering intentionally keeps same time on odd steps.
   - :timeout-boundary targets pending-settlement deadline at t-1/t/t+1 when known.
   - default increments by 1.

   Compatibility adapter: currently delegates to Sew semantics
   when the provider namespace is on the classpath."
  [profile world prev-time step-idx]
  (if-let [f @sew-next-time]
    (f profile world prev-time step-idx)
    (inc prev-time)))
