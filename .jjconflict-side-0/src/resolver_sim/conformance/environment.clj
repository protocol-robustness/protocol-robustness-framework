(ns resolver-sim.conformance.environment
  "Hermetic conformance environment receipt.

   The registry root identifies the implementation surface, but the verdict may
   also depend on canonicalisation, schema interpretation, toolchain behaviour,
   and committed policy files.  An environment receipt separates COMMITTED
   fields (which enter the environment root) from INFORMATIONAL fields (runtime
   details that must NOT affect equivalence).

   :environment/root is bound into execution plans, validation receipts,
   capability receipts, reconciliation, coverage, and terminal claims, so two
   processes with the same profile and registry ids but materially different
   canonicalisation or policy implementations cannot produce indistinguishable
   claims."
  (:require [resolver-sim.conformance.canonical :as canonical]))

(def environment-schema-version "conformance.environment/v1")

(defn environment
  "Build a hermetic environment receipt.

   Committed fields (enter the root):
     :profile/root, :implementation-registry/root, :schema-catalog/root,
     :claim-policy-catalog/root, :canonicalisation/id,
     :canonicalisation/implementation-root

   Informational fields (excluded from the root):
     :runtime {:clojure-version ... :jvm-version ...}, :source-revisions {...}

   Returns {:environment/schema-version ...
            :environment/committed {...} :environment/informational {...}
            :environment/root <sha256>}."
  [m]
  (let [committed {:profile/root (:profile/root m)
                   :implementation-registry/root (:implementation-registry/root m)
                   :schema-catalog/root (:schema-catalog/root m)
                   :claim-policy-catalog/root (:claim-policy-catalog/root m)
                   :canonicalisation/id (or (:canonicalisation/id m) :prf-canonical-edn.v1)
                   :canonicalisation/implementation-root (:canonicalisation/implementation-root m)}
        informational {:runtime (or (:runtime m) {})
                       :source-revisions (or (:source-revisions m) {})}]
    {:environment/schema-version environment-schema-version
     :environment/committed committed
     :environment/informational informational
     :environment/root (canonical/root committed)}))

(defn environment-root
  "Return the committed environment root."
  [env]
  (:environment/root env))

;; A verification may bind *environment* to the actual canonicalisation/policy
;; identity; when unbound, a DETERMINISTIC committed default is used so golden
;; roots remain stable regardless of process/registry state.
(def ^:dynamic *environment* nil)

(def default-environment
  (environment
   {:canonicalisation/id :prf-canonical-edn.v1
    :canonicalisation/implementation-root "sha256:prf-canonical-edn-v1"}))

(defn current-environment-root
  "The environment root in effect: the bound environment, else the committed
   default."
  []
  (if *environment*
    (:environment/root *environment*)
    (:environment/root default-environment)))
