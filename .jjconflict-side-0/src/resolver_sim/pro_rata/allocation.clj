(ns resolver-sim.pro-rata.allocation
  "Clojure realization of deterministic integer pro-rata allocation.

   portable-pro-rata-contract.v1 owns the canonical request, normalization,
   arithmetic, evidence, and allocation-root semantics. This namespace exposes
   the production Clojure API and validates/realizes that contract. The
   single-pass claimant engine and detached parallel machinery live in
   resolver-sim.pro-rata.engine; cap redistribution lives in
   resolver-sim.pro-rata.redistribution.

   This realization deliberately does not inspect world state, select accounts,
   classify application shortfall, or apply a state transition. Domain adapters
   supply canonical rows and interpret allocated and unmet quantities. Production
   pro-rata namespaces must never depend on protocol, runner, research,
   application, or generic-economics allocator namespaces."
  (:require [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.engine :as engine]
            [resolver-sim.pro-rata.redistribution :as redistribution]
            [prf.pro-rata.rows :as allocation-rows]
            [prf.pro-rata.api :as allocation-api]
            [prf.pro-rata.identity :as allocation-identity]))

(defn allocate-pro-rata [request]
  (let [state (realization/open)]
    (binding [realization/*claimant-execution-realization* state]
      (let [result (engine/allocate-pro-rata* request)]
        (realization/complete! state)
        result))))

(def ^:const mechanism-version 1)

(def ^:const allocation-policy-schema-version "pro-rata-allocation-policy.v1")

(defn allocation-policy-root
  "Derive the canonical domain-neutral policy identity from an authoritative
  pro-rata allocation result. This is a read-only lineage projection: it does
  not alter the existing allocation result artifact or its root."
  [result]
  (allocation-identity/allocation-policy-root result))

(defn canonical-id-key
  "A typed, byte-stable ordering key for identities.  `pr-str` is deliberately
   not used here: printed representation is not an allocation contract."
  [identity]
  (allocation-rows/canonical-id-key identity))

(defn canonical-rows
  "Validate and canonically order domain-neutral allocation rows.

   `:row/id` is an allocation identity, not a participant identity. It must be
   unique so duplicate economic rows remain visible rather than being collapsed.
   Repeated obligation IDs or identical requested/weight/cap values are valid."
  [rows]
  (allocation-rows/canonical-rows rows))

(defn- execute-allocation
  [execution-request redistribution-policy]
  (case redistribution-policy
    :unallocated
    (allocate-pro-rata execution-request)

    :redistribute-cap-excess
    (redistribution/allocate-pro-rata-with-redistribution
     (assoc execution-request :ordering-policy :canonical-id))))

(defn allocate
  "Allocate a constrained integer quantity across canonical allocation rows.

   Input:
   {:schema-version pro-rata-allocation-request.v1
    :mechanism/version 1
    :allocation/id ... :available N :rows [...] :rounding-policy :largest-remainder
    :tie-break-policy :canonical-row-id
    :redistribution-policy :unallocated | :redistribute-cap-excess}

   The result is a hash-committed mathematical evidence envelope. It contains
   no account, token, participant, or transition semantics."
  [request]
  (allocation-api/allocate
   request
   {:execute-allocation execute-allocation
    :hash-request allocation-identity/request-root
    :hash-result allocation-identity/allocation-result-root}))

(defn allocation-hash-valid?
  "Return true only when the persisted result hash commits to every witness
   field and final allocation row.  Non-canonical diagnostic fields
   (:allocation/fractional-remainder-total) are excluded from recomputation."
  [result]
  (= (:allocation/hash result)
     (allocation-identity/allocation-result-root result)))
