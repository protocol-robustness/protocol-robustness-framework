(ns resolver-sim.assurance.held-override-selection
  "Production authoritative extension selection for the held-custody exceptional
   override (Slice C).

   Ties the CURRENT authoritative configuration to the EXACT extension-resolution
   snapshot that Slice B consumes:

     current authoritative configuration/head-root
       -> committed authoritative extension selection (self-rooted)
       -> rooted extension-resolution snapshot (resolve-requested over the
          SELECTED provider package only)
       -> exact physical capability/provider
       -> held override implementation

   The selection is a self-rooted `authoritative-extension-selection.v1` that
   names exactly one override capability and one provider package (id + root),
   and commits the configuration-head-root it was agreed against. Currentness is
   enforced: a selection committed against an OLDER head-root is STALE and fails
   closed, and a merely-installed package that is not selected is never used.

   Installed != selected: derivation resolves over ONLY the selected provider's
   package manifest, so an alternate registered provider (B) can never substitute
   for the selected one (A), even when B is installed.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - prf.extensions.*                   (resolved only at runtime)
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)"
  (:require [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.assurance.held-admission :as held-admission]))

(def selection-schema
  "Schema of the committed authoritative extension selection."
  "authoritative-extension-selection.v1")

(def selection-domain
  "Domain tag for the selection self-root."
  :held-custody-override-selection)

(def override-capability-key
  "The override capability the selection may authorise."
  held-admission/override-capability-key)

(defn selection-root
  "Self-rooted content hash of a committed extension selection."
  [selection]
  (hash-ref/sha256-ref
   (hc/domain-hash selection-domain
                   (dissoc selection :selection/root))))

(defn valid-selection?
  "Structural + self-root validation of a committed selection. The selection must
   be a self-consistent map naming the exact override capability."
  [selection]
  (and (map? selection)
       (= selection-schema (:selection/schema selection))
       (= (:selection/root selection) (selection-root selection))
       (= override-capability-key (:selection/capability-key selection))
       (map? (:selection/provider selection))
       (string? (get-in selection [:selection/provider :package-root]))
       (some? (get-in selection [:selection/provider :package/id]))))

(defn build-selection
  "Construct a committed, self-rooted extension selection naming exactly one
   override provider package, committed against the current configuration-head-root.

     capability-key  — must equal the override capability key.
     provider        — {:package/id <id> :package-root <root>}.
     config-head-root — the configuration-head-state root it was agreed against."
  [capability-key provider config-head-root]
  (let [base {:selection/schema selection-schema
              :selection/capability-key capability-key
              :selection/provider provider
              :selection/config-head-root config-head-root}]
    (assoc base :selection/root (selection-root base))))

(defn current-selection?
  "True when a committed selection is CURRENT for the supplied configuration
   head: the head is a valid self-rooted configuration-head-state AND the
   selection was committed against exactly that head's root. A selection
   committed against an older head-root is stale."
  [configuration-head selection]
  (and (map? configuration-head)
       (configuration-head/valid-head-state? configuration-head)
       (valid-selection? selection)
       (= (:configuration-head-state/root configuration-head)
          (:selection/config-head-root selection))))

(defn derive-selected-resolution
  "Derive the ROOTED extension-resolution snapshot selecting the override
   capability from ONLY the selected provider's package manifest.

   installed-packages — map {package-id package-manifest} of physically
                        installed packages. The selection names one provider.
   configuration-head — current authoritative configuration-head-state.v1.
   selection         — committed authoritative extension selection.

   Enforces:
     - installed but not selected -> capability absent in the derived resolution
       (fail closed).
     - selected but stale (committed against an older head-root) -> reject.
     - exactly the SELECTED provider's package is used for derivation, so an
       alternate installed provider never substitutes.

   Returns {:valid? true :resolution <snapshot>} (resolve-requested result) when
   current and the selected package resolves the override capability, else
   {:valid? false :reason <kw>}."
  [installed-packages configuration-head selection]
  (let [provider (:selection/provider selection)]
    (cond
      (not (current-selection? configuration-head selection))
      {:valid? false :reason :selection-stale-or-absent}

      (nil? (get installed-packages (:package/id provider)))
      {:valid? false :reason :selected-provider-not-installed}

      :else
      (let [manifest (get installed-packages (:package/id provider))
            root (str "sha256:" (apply str (repeat 64 "a")))
            schemas {:prf/held-custody-override-admission-input.v1 root
                     :prf/held-custody-override-admission-result.v1 root
                     :prf/held-custody-override-admission-verification.v1 root}
            extension-map (registry/register-package
                           (registry/empty-extension-map) manifest)
            r (resolution/resolve-requested extension-map [override-capability-key]
                                            {:schemas schemas})]
        (if (:valid? r)
          {:valid? true :resolution (:resolution r)}
          {:valid? false :reason :selected-provider-resolution-failed})))))

(defn classify-under-authoritative-selection
  "Production entry point (Slice C). Composes:
     current configuration -> committed selection -> rooted resolution
     -> core held-mutation admission.

   installed-packages — {package-id package-manifest}.
   configuration-head — current authoritative configuration-head.
   selection         — committed authoritative extension selection.
   opts              — core held-mutation admission opts minus :extension-resolution
                       (:operation-id :scope :permits :consumption-registry
                       :now-ts). :extension-resolution is supplied from the derived
                       rooted resolution.

   Returns the core admission decision. Rejects before mutation when the
   selection is stale/absent or the selected provider is unavailable."
  [installed-packages configuration-head selection opts]
  (let [class (held-admission/semantic-operation-class (:operation-id opts))]
    (if (= :ordinary class)
      ;; ordinary ingress is independent of extension installation/selection
      (held-admission/admit-held-mutation
       (assoc opts :extension-resolution nil))
      (let [derived (derive-selected-resolution installed-packages
                                                configuration-head selection)
            valid? (:valid? derived)]
        (if-not valid?
          {:admission :reject
           :semantic-operation-class class
           :operation-id (:operation-id opts)
           :classification :forbidden
           :blocking-reasons [(:reason derived)]}
          (held-admission/admit-held-mutation
           (assoc opts :extension-resolution
                  {:valid? true :resolution (:resolution derived)})))))))