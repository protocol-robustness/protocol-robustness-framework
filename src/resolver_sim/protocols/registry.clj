(ns resolver-sim.protocols.registry
  "Central protocol registry for framework entrypoints.

   NOTE: This namespace is a transition point while decoupling framework code
   from concrete protocols.

   Protocol scenario coverage:
     sew-v1   — in-process invariant registry (protocols_src/.../invariant_scenarios.clj)
     yield-v1 — file-backed suite (scenarios/edn/Y*.edn via :yield-provider-scenarios)"
  (:require [clojure.string :as str]))

(def ^:private core-protocol-symbol-registry
  {"yield-v1" 'resolver-sim.protocols.yield/protocol
   "dummy"    'resolver-sim.protocols.dummy/protocol})

(defonce ^:private extension-protocol-symbol-registry
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
  protocol-id)

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

(defn- bootstrap-extension! [protocol-id]
  (let [extension-name (first (str/split protocol-id #"-"))
        extension-ns (symbol (str "resolver-sim.protocols." extension-name ".extension"))]
    (try
      (require extension-ns)
      (catch java.io.FileNotFoundException _ nil))))

(defn get-protocol
  "Resolve an adapter by ID. If it is not registered by the core, attempt the
   conventional optional bootstrap namespace
   `resolver-sim.protocols.<extension>.extension`; missing extensions return nil."
  [protocol-id]
  (or (resolve-var-value (get (protocol-symbol-registry) protocol-id))
      (do (bootstrap-extension! protocol-id)
          (resolve-var-value (get (protocol-symbol-registry) protocol-id)))))
