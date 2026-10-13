(ns resolver-sim.economics.with-bounty.identity
  "Deterministic with-bounty identities (ADR-0006 D1/D3).

   - :bounty-obligation-id — deterministic obligation identity of a bounty
     payable, derived from a versioned projection (per the design note §6).
     Identical semantic inputs produce the same obligation identity.
   - :bounty-invocation-id — deterministic identity of an eligibility or
     amount invocation, derived from semantic location (per ADR-0005 §6), so
     identical inputs produce identical evidence and cross-references.

   The exact projections are versioned; changing a projection changes the
   identity domain."
  (:require [resolver-sim.hash.canonical :as hc]))

(def obligation-domain-tag
  :with-bounty-obligation-v1)

(def invocation-domain-tag
  :with-bounty-invocation-v1)

;; ── obligation identity ───────────────────────────────────────────────────

(defn obligation-id-projection
  "Versioned projection of a bounty obligation identity:
     [:bounty-payable operation-root bounty-id recipient token amount policy-root]
   Single source of truth: resolver-sim.hash.canonical/project-with-bounty-obligation."
  [{:keys [operation-root bounty-id recipient token amount policy-root]}]
  [:bounty-payable operation-root bounty-id recipient token amount policy-root])

(defn bounty-obligation-id
  "Deterministic obligation identity of a with-bounty payable."
  [args]
  (hc/domain-hash obligation-domain-tag (hc/project-with-bounty-obligation args nil)))

;; ── invocation identity ───────────────────────────────────────────────────

(defn invocation-id-projection
  "Versioned projection of a with-bounty invocation:
     [policy-root step-id index capability-ref]"
  [args]
  [(:policy-root args) (:step/id args) (:index args) (:capability/ref args)])

(defn bounty-invocation-id
  "Deterministic identity of one with-bounty step invocation (eligibility or
   amount)."
  [args]
  (hc/domain-hash invocation-domain-tag (hc/project-with-bounty-invocation args nil)))
