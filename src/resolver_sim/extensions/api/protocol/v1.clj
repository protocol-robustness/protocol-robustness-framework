(ns resolver-sim.extensions.api.protocol.v1
  "Versioned PRF core API for optional protocol capabilities.

   This namespace is intentionally smaller than the aggregate extension API so
   protocol packages can register and resolve capabilities without loading
   unrelated evidence, authorization, or allocation implementations."
  (:require [resolver-sim.protocols.registry :as protocol-registry]))

(defn register-capability!
  "Register an optional capability for a protocol extension.

   Capability IDs are keywords. The value is protocol-defined data or a
   function. Returns the capability ID."
  [protocol-id capability-id value]
  (protocol-registry/register-capability! protocol-id capability-id value))

(defn get-capability
  "Resolve a registered optional capability, returning nil when unavailable.
   The PRF protocol registry performs profile-specific bootstrapping."
  [protocol-id capability-id]
  (protocol-registry/get-capability protocol-id capability-id))

(defn register-capability-provider!
  "Register a reloadable bootstrap for one protocol capability group.

   `group` must be recognized by the host protocol registry. Returns the
   group keyword."
  [protocol-id group bootstrap-fn]
  (protocol-registry/register-capability-provider!
   protocol-id group bootstrap-fn))
