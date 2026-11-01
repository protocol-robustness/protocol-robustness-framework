(ns resolver-sim.protocols.registry
  "Central protocol registry for framework entrypoints.

   NOTE: This namespace is a transition point while decoupling framework code
   from concrete protocols.

   Protocol scenario coverage:
     sew-v1   — in-process invariant registry (protocols_src/.../invariant_scenarios.clj)
     yield-v1 — file-backed suite (scenarios/edn/Y*.edn via :yield-provider-scenarios)"
  (:require [clojure.string :as str]))

(def ^:private core-protocol-symbol-registry
  {"sew-v1"   'resolver-sim.protocols.sew/protocol
   "yield-v1" 'resolver-sim.protocols.yield/protocol
   "dummy"    'resolver-sim.protocols.dummy/protocol})

(defonce ^:private extension-protocol-symbol-registry
  (atom {}))

(defonce ^:private extension-capability-registry
  (atom {}))

(defonce ^:private capability-provider-registry
  (atom {}))

(defn register-extension!
  "Register an optional protocol adapter without loading its implementation.
   Extension namespaces call this during their explicit :with-sew bootstrap."
  [protocol-id adapter-symbol]
  (when-not (and (string? protocol-id) (symbol? adapter-symbol))
    (throw (ex-info "Protocol extension registration requires a string ID and symbol"
                    {:protocol-id protocol-id :adapter-symbol adapter-symbol})))
  (swap! extension-protocol-symbol-registry assoc protocol-id adapter-symbol)
  protocol-id)

(defn unregister-extension!
  "Remove an optional adapter registration. Intended for isolated tests."
  [protocol-id]
  (swap! extension-protocol-symbol-registry dissoc protocol-id)
  (swap! extension-capability-registry dissoc protocol-id)
  protocol-id)

(defn register-capability-provider!
  "Register a reloadable capability group bootstrap for an already-loaded extension."
  [protocol-id group bootstrap-fn]
  (when-not (and (string? protocol-id) (keyword? group) (ifn? bootstrap-fn))
    (throw (ex-info "Capability provider requires protocol ID, group keyword, and function"
                    {:protocol-id protocol-id :group group})))
  (swap! capability-provider-registry assoc-in [protocol-id group] bootstrap-fn)
  group)

(defn- protocol-symbol-registry []
  (merge core-protocol-symbol-registry @extension-protocol-symbol-registry))

(defn- resolve-var-value
  [sym]
  (when sym
    (try
      (require (symbol (namespace sym)))
      (when-let [v (resolve sym)]
        @v)
      (catch java.io.FileNotFoundException _ nil))))

(def default-protocol-id
  "sew-v1")

(defn known-protocol-ids []
  (keys (protocol-symbol-registry)))

(defn known-protocol-namespaces
  "Return the list of protocol implementation namespace symbols (without the -protocol var suffix).
   Used to pre-load protocol namespaces before parallel execution."
  []
  (->> (vals (protocol-symbol-registry))
       (map (comp symbol namespace))
       (distinct)
       (sort)))

(defn- restore-capability-group! [protocol-id group requested-capability]
  (let [registered? (and requested-capability
                         (some? (get-in @extension-capability-registry
                                        [protocol-id requested-capability])))]
    (when (or (nil? requested-capability) (not registered?))
      (when-let [bootstrap (get-in @capability-provider-registry [protocol-id group])]
        (bootstrap)))))

(defn- bootstrap-extension! [protocol-id requested-capability]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name ".extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))
    (when (or (nil? requested-capability)
              (contains? #{"reference-validation" "replay"}
                         (namespace requested-capability)))
      (restore-capability-group! protocol-id :core requested-capability))))

(defn- bootstrap-runner-extension! [protocol-id requested-capability]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name ".runner-extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))
    (restore-capability-group! protocol-id :runner requested-capability)))

(defn- bootstrap-force-authorisation-extension! [protocol-id requested-capability]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name
                                  ".force-authorisation-extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))
    (restore-capability-group! protocol-id :force-authorisation requested-capability)))

(defn- bootstrap-analysis-extension! [protocol-id requested-capability]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name
                                  ".analysis-extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))
    (restore-capability-group! protocol-id :analysis requested-capability)))

(defn- bootstrap-yield-extension! [protocol-id requested-capability]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name
                                  ".yield-extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))
    (restore-capability-group! protocol-id :yield requested-capability)))

(defn- bootstrap-sew-core-extension! [protocol-id requested-capability]
  (when (= "sew-v1" protocol-id)
    (try
      (require 'prf.extensions.sew-core-model.adapter)
      (catch java.io.FileNotFoundException _ nil))
    (restore-capability-group! protocol-id :sew-core requested-capability)))

(defn register-capability!
  "Register an optional protocol-owned capability without adding a core import.
   Capability IDs are keywords; values are protocol-defined data or functions."
  [protocol-id capability-id value]
  (when-not (and (string? protocol-id) (keyword? capability-id))
    (throw (ex-info "Protocol capability registration requires a string ID and keyword capability"
                    {:protocol-id protocol-id :capability-id capability-id})))
  (when (nil? value)
    (throw (ex-info "Protocol capability value cannot be nil"
                    {:protocol-id protocol-id :capability-id capability-id})))
  (swap! extension-capability-registry assoc-in [protocol-id capability-id] value)
  capability-id)

(defn get-capability
  "Resolve an optional protocol capability, loading its conventional extension
   bootstrap and optional runner bootstrap first. Returns nil when the protocol
   or capability is unavailable. Runner capabilities are loaded only when
   requested, so model-only profiles do not acquire runner dependencies."
  [protocol-id capability-id]
  (bootstrap-extension! protocol-id capability-id)
  (when (= "runner" (namespace capability-id))
    (bootstrap-runner-extension! protocol-id capability-id))
  (when (= "force-authorisation" (namespace capability-id))
    (bootstrap-force-authorisation-extension! protocol-id capability-id))
  (when (= "analysis" (namespace capability-id))
    (bootstrap-analysis-extension! protocol-id capability-id))
  (when (= "yield" (namespace capability-id))
    (bootstrap-yield-extension! protocol-id capability-id))
  (when (= "sew-core" (namespace capability-id))
    (bootstrap-sew-core-extension! protocol-id capability-id))
  (get-in @extension-capability-registry [protocol-id capability-id]))

(defn get-protocol
  "Resolve an adapter by ID. An explicitly registered extension takes
   precedence; otherwise load the conventional optional bootstrap namespace
   `resolver-sim.protocols.<extension>.extension` before resolving the core
   adapter. Missing optional bootstraps fall back to the core registry."
  [protocol-id]
  (if-let [extension (get @extension-protocol-symbol-registry protocol-id)]
    (resolve-var-value extension)
    (do
      (bootstrap-extension! protocol-id nil)
      (resolve-var-value (get (protocol-symbol-registry) protocol-id)))))
