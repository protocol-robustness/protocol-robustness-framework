(ns resolver-sim.extensions.force-authorisation
  "Facade for the force-authorisation extension package.

  When the :extension/force-authorisation alias is on the classpath, this
  namespace delegates all capability and package definitions to the physical
  extension manifest at
  extensions/force-authorisation/src/prf/extensions/force_authorisation/manifest.clj,
  preserving a single content-addressed identity across the physical and
  in-tree representation.

  When the extension is NOT on the classpath, this namespace retains the
  legacy in-tree definitions so that Sew's transitional adapter behaviour
  remains available without the physical package."
  (:require [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.extensions.registry :as registry]))

(def ^:private physical-available?
  (try
    (require '[prf.extensions.force-authorisation.manifest :as physical])
    true
    (catch Throwable _
      false)))

;; ── legacy identity (used when physical extension is absent) ─────────────────

(def extension-id :sew/force-authorisation)
(def extension-version "1.0.0")
(def governed-authority-kind :assurance/governed-authority)
(def governed-authority-id :resolver-sim/three-member-v1)

(def ^:private legacy-capability
  {:capability/kind :assurance/exceptional-force-authorisation
   :capability/id :sew/force-authorisation-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'resolver-sim.protocols.sew/grant-consensus-force-authorisation
   :input-schema :sew/force-authorisation-command.v2
   :output-schema :sew/force-authorisation-record.v2
   :declared-dependencies
   [{:capability/kind governed-authority-kind
     :capability/id governed-authority-id
     :requirement {:capability/version 1
                   :capability/contract-version 1
                   :capability/profile :production-governed}}]
   :verification/contract :sew/force-authorisation-governed-provenance.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :sew/force-authorisation-command.v2}
                          :composition/output {:schema-ref :sew/force-authorisation-record.v2}}})

(def ^:private legacy-package
  {:extension/id extension-id
   :extension/version extension-version
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [legacy-capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :core
   :extension/funding-status :core
   :extension/status {:lifecycle :active :distribution :core
                      :conformance :conformant :reproduction :artifact-replayable
                      :verification :replayed :maintenance :supported
                      :adoption :multi-adapter}})

(def ^:private legacy-governed-authority-capability
  {:capability/kind governed-authority-kind
   :capability/id governed-authority-id
   :capability/version 1
   :capability/contract-version 1
   :capability/profile :production-governed
   :entrypoint 'resolver-sim.assurance.governed-authority-consumer/verify-governed-authority
   :input-schema :resolver-sim/governed-authority-input.v1
   :output-schema :resolver-sim/governed-authority-report.v1
   :composition-contract {:composition-contract/version 1
                          :composition/input {:schema-ref :resolver-sim/governed-authority-input.v1}
                          :composition/output {:schema-ref :resolver-sim/governed-authority-report.v1}}})

(def ^:private legacy-governed-authority-package
  (assoc legacy-package :extension/id :resolver-sim/governed-authority
         :extension/capabilities [legacy-governed-authority-capability]))

;; ── public resolution ──────────────────────────────────────────────────────

(defn- resolve-var
  "Resolve a var from the physical manifest namespace; fall back to legacy.
   `sym` is a symbol naming the public def in the physical namespace."
  [sym]
  (when physical-available?
    (ns-resolve 'prf.extensions.force-authorisation.manifest sym)))

(defn- resolved-capability-kind
  "Return the force-authorisation capability kind from physical manifest or legacy."
  []
  (if-let [cap (resolve-var 'custody-execution-capability)]
    (get (var-get cap) :capability/kind)
    :assurance/exceptional-force-authorisation))

(defn- resolved-capability-id
  "Return the force-authorisation capability id from physical manifest or legacy."
  []
  (if-let [cap (resolve-var 'custody-execution-capability)]
    (get (var-get cap) :capability/id)
    :sew/force-authorisation-v1))

(defn capability-kind
  "The force-authorisation capability kind (physical or legacy)."
  []
  (resolved-capability-kind))

(defn capability-id
  "The force-authorisation capability id (physical or legacy)."
  []
  (resolved-capability-id))

(defn- resolved-package
  "Return the physical package when available, otherwise the legacy package."
  []
  (if physical-available?
    (let [pkg-var (ns-resolve 'prf.extensions.force-authorisation.manifest 'package)]
      (var-get pkg-var))
    legacy-package))

(defn- resolved-governed-authority-package
  "Return the physical governed-authority package when available.
   The physical governed-permit-capability is rewritten to present the legacy
   governed-authority identity so that dependency resolution aligns."
  []
  (if physical-available?
    (let [cap (if-let [c (resolve-var 'governed-permit-capability)]
                (-> (var-get c)
                    (assoc :capability/kind governed-authority-kind
                           :capability/id governed-authority-id)
                    (dissoc :declared-dependencies))
                legacy-governed-authority-capability)]
      {:extension/id :resolver-sim/governed-authority
       :extension/version "1.0.0"
       :extension/api-version 1
       :extension/manifest-version 1
       :extension/capabilities [cap]
       :extension/license "Apache-2.0"
       :extension/maintainers ["PRF core"]
       :extension/support-policy :core
       :extension/funding-status :core
       :extension/status {:lifecycle :active :distribution :core
                          :conformance :conformant :reproduction :artifact-replayable
                          :verification :replayed :maintenance :supported
                          :adoption :multi-adapter}})
    legacy-governed-authority-package))

(def capability
  "The primary force-authorisation capability descriptor.

  When the physical extension is on the classpath, resolves to the physical
  custody-execution-capability (the canonical COMPL-1 identity). Otherwise
  returns the legacy in-tree capability."
  (delay
    (if-let [cap (resolve-var 'custody-execution-capability)]
      (var-get cap)
      legacy-capability)))

(def package
  "The force-authorisation extension package.

  Delegates to the physical manifest when available; otherwise returns the
  legacy in-tree package descriptor."
  (delay
    (resolved-package)))

(def governed-authority-capability
  "The governed-authority capability descriptor that force-authorisation
   depends on. When the physical extension is available, this is the physical
   governed-permit-capability rewritten with the legacy identity."
  (delay
    (if-let [cap (resolve-var 'governed-permit-capability)]
      (-> (var-get cap)
          (assoc :capability/kind governed-authority-kind
                 :capability/id governed-authority-id)
          (dissoc :declared-dependencies))
      legacy-governed-authority-capability)))

(def governed-authority-package
  "The governed-authority package."
  (delay
    (resolved-governed-authority-package)))

(defn scope-verification-capability
  "The scope-verification capability from the physical manifest (returns nil
   when the physical extension is not on the classpath)."
  []
  (when-let [cap (resolve-var 'scope-verification-capability)]
    (var-get cap)))

(defn governed-permit-capability
  "The governed-permit capability from the physical manifest (returns nil
   when the physical extension is not on the classpath)."
  []
  (when-let [cap (resolve-var 'governed-permit-capability)]
    (var-get cap)))

(defn custody-execution-capability
  "The custody-execution capability from the physical manifest (returns nil
   when the physical extension is not on the classpath)."
  []
  (when-let [cap (resolve-var 'custody-execution-capability)]
    (var-get cap)))

;; ── installation ────────────────────────────────────────────────────────────

(defn install-governed-authority
  "Install the governed-authority package into the extension-map."
  [extension-map]
  (registry/register-package extension-map @governed-authority-package))

(defn- governed-provider?
  "Check whether a compatible governed-authority provider is registered."
  [extension-map]
  (let [provider (registry/capability-descriptor extension-map governed-authority-kind governed-authority-id)]
    (when provider
      (and (= 1 (:capability/version provider))
           (= 1 (:capability/contract-version provider))
           (= :production-governed (:capability/profile provider))))))

(defn install
  "Install only into a composition that has resolved the production governed
   authority provider. Local simulation must use a separately identified
   compatibility component rather than claiming this production capability."
  [extension-map]
  (when-not (governed-provider? extension-map)
    (throw (ex-info "governed authority dependency is unavailable or incompatible"
                    {:error :extensions/error-missing-governed-authority-dependency
                     :required [governed-authority-kind governed-authority-id]})))
  (registry/register-package extension-map @package))

(defn installed?
  "True only when the explicit composition snapshot contains this exact
   extension capability. Absent context never implies availability."
  [extension-map]
  (let [kind (resolved-capability-kind)
        id (resolved-capability-id)]
    (boolean (registry/capability-descriptor extension-map kind id))))

(defn component-root
  "Content-addressed root of the package manifest."
  []
  (manifest/package-root @package))
