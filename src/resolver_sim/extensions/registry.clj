(ns resolver-sim.extensions.registry
  "The extension-map registry.

   The extension-map is the explicit, data-driven configuration that maps
   extension identifiers ([capability-kind capability-id]) to their resolved
   implementations. It is the inspectable and validateable source of truth for
   discovering, selecting, and composing extensions.

   Registration semantics (per ADR-0005):
   - registration under an existing key is idempotent only when the descriptor
     root is identical;
   - same key with a different descriptor root is a hard collision
     (:extensions/error-capability-collision);
   - built-in capabilities (:prf/core-economics) cannot be replaced
     (:extensions/error-replace-builtin);
   - the registry is frozen before execution; registration after freeze fails
     (:extensions/error-registry-frozen).

   Pure functions (`register-package`, `register-capability`, `lookup`, ...)
   operate on immutable extension-map values so an explicit registry snapshot
   can be passed into execution instead of consulting global state. The atom
   (`*extension-map*`) is a development convenience pre-seeded with the core
   package."
  (:require [resolver-sim.extensions.core :as core]
            [resolver-sim.extensions.manifest :as em]))

;; ── pure extension-map operations ─────────────────────────────────────────

(defn empty-extension-map
  "An empty extension-map (no capabilities)."
  []
  {})

(defn- validate-package!
  "Throw :extensions/error-invalid-package unless the package manifest passes
   the same validation required by register-package. Shared by every
   registration entry point so no capability can enter a registry under a
   package identity that the canonical package path would reject."
  [package]
  (let [validation (em/validate-package package)]
    (when-not (:valid? validation)
      (throw (ex-info "extension: invalid package manifest"
                      {:error :extensions/error-invalid-package
                       :violations (:violations validation)}))))
  package)

(defn- provider
  [package]
  {:package/id (:extension/id package)
   :package/version (:extension/version package)
   :package-root (em/package-root package)
   :sealed (em/sealed-classification package)})

(defn- entry
  [package capability]
  {:capability capability
   :descriptor-root (em/capability-descriptor-root capability)
   :builtin? (= (:extension/id package) core/core-package-id)
   :providers [(provider package)]})

(defn register-capability
  "Pure: register a capability (from a package manifest) into an
   extension-map. Throws on collision or built-in replacement; idempotent when
   the descriptor root is identical (adding a provider if the package differs).
   Validates the package manifest and the capability descriptor first and
   stores the canonical (normalised) form, so the content-addressed root
   commits only the canonical vocabulary."
  [extension-map package capability]
  (validate-package! package)
  (let [validation (em/validate-capability capability)]
    (when-not (:valid? validation)
      (throw (ex-info "extension: invalid capability descriptor"
                      {:error :extensions/error-invalid-capability
                       :violations (:violations validation)})))
    (let [capability (:normalized (em/normalize-capability-descriptor capability))
          key (em/capability-key capability)
          descriptor-root (em/capability-descriptor-root capability)]
      (if-let [existing (get extension-map key)]
        (if (= descriptor-root (:descriptor-root existing))
          (let [providers (:providers existing)
                new-provider (provider package)]
            (if (some #(= (:package-root %) (:package-root new-provider)) providers)
              extension-map
              (assoc extension-map key (update existing :providers conj new-provider))))
          (throw (ex-info (if (:builtin? existing)
                            "extension: cannot replace built-in capability"
                            "extension: capability collision")
                          {:error (if (:builtin? existing)
                                    :extensions/error-replace-builtin
                                    :extensions/error-capability-collision)
                           :capability key
                           :existing-root (:descriptor-root existing)
                           :incoming-root descriptor-root})))
        (assoc extension-map key (entry package capability))))))

(defn register-package
  "Pure: register every capability of a package manifest into an
   extension-map. Validates the package manifest first. Returns the updated
   extension-map."
  [extension-map package]
  (validate-package! package)
  (reduce (fn [emap capability]
            (register-capability emap package capability))
          extension-map
          (:extension/capabilities package [])))

(defn lookup-capability
  "Return the resolved entry for [capability-kind capability-id], or nil."
  [extension-map capability-kind capability-id]
  (get extension-map [capability-kind capability-id]))

(defn capability-descriptor
  "Return the capability descriptor for a key, or nil."
  [extension-map capability-kind capability-id]
  (:capability (lookup-capability extension-map capability-kind capability-id)))

(defn known-capability-keys
  "Return all registered [capability-kind capability-id] keys."
  [extension-map]
  (keys extension-map))

(defn providers-of
  "Return the provider package records for a capability key."
  [extension-map capability-kind capability-id]
  (:providers (lookup-capability extension-map capability-kind capability-id) []))

;; ── atom-backed development registry ──────────────────────────────────────

(defonce ^:private state
  (atom {:frozen? false
         :entries (register-package (empty-extension-map) core/core-economics-package)}))

(defn extension-map
  "Inspectable current extension-map (capability keys to resolved entries)."
  []
  (:entries @state))

(defn frozen?
  "True once the registry has been frozen."
  []
  (:frozen? @state))

(defn- checked-swap!
  [f]
  (swap! state
         (fn [{:keys [frozen? entries] :as s}]
           (when frozen?
             (throw (ex-info "extension: registry is frozen"
                             {:error :extensions/error-registry-frozen})))
           (assoc s :entries (f entries)))))

(defn register-capability!
  "Register a capability into the live registry (throws on invalid descriptor,
   collision, built-in replacement, or a frozen registry). Returns the
   capability key."
  [package capability]
  (checked-swap! (fn [entries] (register-capability entries package capability)))
  (em/capability-key capability))

(defn register-package!
  "Register all capabilities of a package manifest into the live registry.
   Returns the package id."
  [package]
  (checked-swap! (fn [entries] (register-package entries package)))
  (:extension/id package))

(defn unregister-capability!
  "Remove a non-built-in capability from the live registry. Built-ins cannot be
   removed. Returns the removed entry, or nil."
  [capability-kind capability-id]
  (let [key [capability-kind capability-id]
        removed (atom nil)]
    (swap! state
           (fn [{:keys [frozen? entries] :as s}]
             (when frozen?
               (throw (ex-info "extension: registry is frozen"
                               {:error :extensions/error-registry-frozen})))
             (if-let [existing (get entries key)]
               (if (:builtin? existing)
                 (throw (ex-info "extension: cannot remove built-in capability"
                                 {:error :extensions/error-replace-builtin
                                  :capability key}))
                 (do (reset! removed existing)
                     (assoc s :entries (dissoc entries key))))
               s)))
    @removed))

(defn unregister-package
  "Pure: remove exactly the capabilities owned by the package from an
   extension-map. A capability shared with another package keeps its remaining
   providers; an entry whose providers become empty is removed entirely.
   Built-in capabilities cannot be removed. Returns the updated extension-map."
  [extension-map package]
  (let [pid (:extension/id package)]
    (reduce (fn [acc [key entry]]
              (let [providers (:providers entry)
                    remaining (vec (remove #(= pid (:package/id %)) providers))]
                (if (= (count providers) (count remaining))
                  acc
                  (if (:builtin? entry)
                    (throw (ex-info "extension: cannot remove built-in capability"
                                    {:error :extensions/error-replace-builtin
                                     :capability key
                                     :package pid}))
                    (if (seq remaining)
                      (assoc acc key (assoc entry :providers remaining))
                      (dissoc acc key))))))
            extension-map
            extension-map)))

(defn unregister-package!
  "Remove every capability owned by the package from the live registry.
   Exactly the providers carrying this package identity are removed: a
   capability shared with another package keeps its remaining providers, and a
   capability whose providers become empty is removed entirely. Built-in
   capabilities cannot be removed. Atomic (single swap). Returns the package
   id."
  [package]
  (let [pid (:extension/id package)]
    (swap! state
           (fn [{:keys [frozen? entries] :as s}]
             (when frozen?
               (throw (ex-info "extension: registry is frozen"
                               {:error :extensions/error-registry-frozen})))
             (assoc s :entries (unregister-package entries package))))
    pid))

(defn freeze!
  "Freeze the registry and return the immutable extension-map snapshot.
   Registration and unregistration fail after this point. Freezing again is
   idempotent and returns the same snapshot."
  []
  (swap! state assoc :frozen? true)
  (extension-map))

(defn clear-extensions!
  "Reset the registry to the core-seeded, unfrozen state. Intended for test
   isolation."
  []
  (reset! state {:frozen? false
                 :entries (register-package (empty-extension-map) core/core-economics-package)})
  nil)

;; ── extension-backed disclosure ───────────────────────────────────────────

(defn extension-backed?
  "True when a resolved entry's implementation is supplied by an extension
   (a non-core package). An extension-backed result is never treated as
   equivalent to a built-in merely because both have the same outer map shape:
   compatibility depends on extension identity, version, declared contract,
   applied configuration, and implementation root."
  [entry]
  (boolean (and entry (not (:builtin? entry)))))

(defn extension-backed-provenance
  "Disclosure map for an extension-backed result: enough provenance to
   reproduce and assess it without invoking the implementation."
  [entry]
  {:kind :extension-backed
   :extension/id (:capability/id (:capability entry))
   :extension/version (:capability/version (:capability entry))
   :extension/implementation-hash (:descriptor-root entry)})
