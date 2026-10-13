(ns resolver-sim.benchmark.dimension-support
  "Evidence-to-dimension provenance artifact.

   Binds content-addressed evidence roots to the consensus dimensions
   they support, completing the chain:

     command → outcome evidence → dimension-support → researcher position
                                                                → certificate

   Each entry declares which evidence root supports a given consensus
   dimension, and how that evidence was produced (execution or derivation).
   Validation reconciles evidence roots against the referenced outcome
   manifest, not just structural syntax — dimension provenance is never
   decorative metadata.

   Schema: dimension-support.v1"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]))

(def ^:const schema-version "dimension-support.v1")
(def ^:const domain-tag :dimension-support-v1)

(def ^:const valid-source-kinds
  "Controlled vocabulary for :source/kinds in dimension-support entries."
  #{:execution :derivation})

(defn- valid-evidence-root?
  "True when v is a sha256: prefixed hash reference."
  [v]
  (and (string? v) (re-matches #"sha256:[0-9a-f]{64}" v)))

(defn- dimension-support-entry
  "Validate and normalise a single dimension-support entry.

   Structure:
     {:dimension <keyword>           — must be a known consensus dimension
      :source {:kind :execution|:derivation
               :command-root <sha256>    ;; :execution only
               :outcome-manifest/root <sha256>  ;; :execution only, recommended
               :basis-roots [<sha256>...]}  ;; :derivation only
      :evidence-root <sha256>}       — the evidence root supporting the dimension

   The proposition committed by an :execution entry is:
     \"dimension D is supported by evidence E as published in outcome
      manifest M, produced by command C\".

   :outcome-manifest/root binds the manifest identity directly, so the
   intermediate provenance object (the manifest) is explicit rather than
   only implied by reconciled evidence/command roots.

   Returns {:entry <normalised>} or {:violation <structured>}."
  [entry]
  (cond
    (not (map? entry))
    {:violation {:code :support/not-a-map :entry entry}}

    (not (contains? tmc/consensus-dimensions (:dimension entry)))
    {:violation {:code :support/unknown-dimension
                 :dimension (:dimension entry)
                 :known-dimensions tmc/consensus-dimensions}}

    (not (valid-evidence-root? (:evidence-root entry)))
    {:violation {:code :support/invalid-evidence-root
                 :evidence-root (:evidence-root entry)}}

    (not (contains? valid-source-kinds (get-in entry [:source :kind])))
    {:violation {:code :support/invalid-source-kind
                 :source (:source entry)}}

    :else
    (let [{:keys [kind command-root basis-roots]} (:source entry)
          manifest-root (:outcome-manifest/root (:source entry))]
      (cond
        (and (= :execution kind) (not (valid-evidence-root? command-root)))
        {:violation {:code :support/invalid-command-root
                     :command-root command-root}}

        (and (= :execution kind)
             (some? manifest-root)
             (not (valid-evidence-root? manifest-root)))
        {:violation {:code :support/invalid-manifest-root
                     :outcome-manifest/root manifest-root}}

        (and (= :derivation kind)
             (not (and (vector? basis-roots)
                       (every? valid-evidence-root? basis-roots))))
        {:violation {:code :support/invalid-basis-roots
                     :basis-roots basis-roots}}

        :else
        {:entry {:dimension (:dimension entry)
                 :source {:kind kind
                          :command-root command-root
                          :outcome-manifest/root manifest-root
                          :basis-roots (when (= :derivation kind)
                                         (vec basis-roots))}
                 :evidence-root (:evidence-root entry)}}))))

(defn validate-entry-against-manifest
  "Cross-artifact reconciliation: verify that an entry's evidence-root
   actually appears in the referenced outcome manifest.

   For :execution sources:
     - the command-root must match the manifest's :execution/command-root,
     - the evidence-root must appear as one of the manifest's outcome root
       values,
     - when :outcome-manifest/root is declared it must equal the provided
       manifest's :benchmark-outcome/hash, binding the manifest identity.

   For :derivation sources: structural validation only (basis-roots are
   checked for sha256: format in dimension-support-entry).

   Returns {:reconciled? bool :violations [<structured>]}."
  [entry outcome-manifest]
  (let [source-kind (get-in entry [:source :kind])]
    (case source-kind
      :execution
      (let [command-root (get-in entry [:source :command-root])
            manifest-cmd (:execution/command-root outcome-manifest)
            manifest-root (get-in entry [:source :outcome-manifest/root])
            actual-manifest-root (:benchmark-outcome/hash outcome-manifest)
            evidence-root (:evidence-root entry)]
        (cond
          (not= command-root manifest-cmd)
          {:reconciled? false
           :violations [{:code :support/command-root-mismatch
                         :declared command-root
                         :manifest-command-root manifest-cmd}]}

          (and (some? manifest-root)
               (not= manifest-root actual-manifest-root))
          {:reconciled? false
           :violations [{:code :support/manifest-root-mismatch
                         :declared manifest-root
                         :actual-manifest-root actual-manifest-root}]}

          (not (valid-evidence-root? evidence-root))
          {:reconciled? false
           :violations [{:code :support/invalid-evidence-root
                         :evidence-root evidence-root}]}

          (not (some #(= evidence-root %)
                     (vals (select-keys outcome-manifest
                                        [:outcomes/operational-root
                                         :outcomes/incentive-root
                                         :outcomes/incentive-compatibility-root
                                         :outcomes/incentives-strategies-root
                                         :outcomes/incentives-coalitions-root]))))
          {:reconciled? false
           :violations [{:code :support/evidence-root-not-in-manifest
                         :evidence-root evidence-root
                         :command-root command-root}]}

          :else
          {:reconciled? true :violations []}))

      :derivation
      {:reconciled? true :violations []}

      {:reconciled? false
       :violations [{:code :support/invalid-source-kind
                     :source-kind source-kind}]})))

(defn build-dimension-support
  "Build a dimension-support.v1 artifact.

   required:
     :dimensions — vector of dimension-support entries

   optional:
     :outcome-manifest/root — sha256 hash of the outcome manifest that
                              produced the execution evidence.  When
                              supplied, it is bound into every
                              :execution-source entry that does not already
                              declare one, making the manifest identity
                              explicit in the committed support artifact.

   Each entry is validated structurally.  Cross-artifact reconciliation
   (against an outcome manifest) is performed separately via
   validate-entry-against-manifest, not enforced at build time.

   Returns {:schema-version ...
            :dimension-support/hash ...
            :dimensions [...]}"
  [{:keys [dimensions outcome-manifest/root]}]
  (when (empty? dimensions)
    (throw (ex-info "Dimension-support requires at least one dimension entry"
                    {:error :support/empty-dimensions})))
  (when (and (some? root)
             (not (valid-evidence-root? root)))
    (throw (ex-info "Dimension-support :outcome-manifest/root must be a sha256 reference"
                    {:error :support/invalid-manifest-root
                     :outcome-manifest/root root})))
  (let [rooted (mapv (fn [entry]
                       (if (and (= :execution (get-in entry [:source :kind]))
                                (nil? (get-in entry [:source :outcome-manifest/root]))
                                (some? root))
                         (assoc-in entry [:source :outcome-manifest/root] root)
                         entry))
                     dimensions)
        entries (mapv dimension-support-entry rooted)
        violations (keep :violation entries)]
    (when (seq violations)
      (throw (ex-info "Dimension-support entries have validation errors"
                      {:violations violations})))
    (let [normalised (mapv :entry entries)
          body {:schema-version schema-version
                :dimensions normalised}
          support-hash (hash-ref/sha256-ref
                        (hc/domain-hash :dimension-support-v1 body))]
      (assoc body :dimension-support/hash support-hash))))

(defn verify-dimension-support
  "Verify a loaded dimension-support artifact.

   Recomputes the hash and checks structural integrity of every entry.

   Returns {:valid? bool :errors [string]}."
  [support]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version support))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version support))))
    (when-not (vector? (:dimensions support))
      (swap! errors conj ":dimensions must be a vector"))
    (when (and (vector? (:dimensions support)) (empty? (:dimensions support)))
      (swap! errors conj ":dimensions must contain at least one entry"))
    (doseq [[i entry] (map-indexed vector (:dimensions support))]
      (let [result (dimension-support-entry entry)]
        (when-let [v (:violation result)]
          (swap! errors conj (str "dimensions[" i "]: " (pr-str v))))))
    (when (some? (:dimension-support/hash support))
      (let [without-hash (dissoc support :dimension-support/hash)
            computed (hash-ref/sha256-ref
                      (hc/domain-hash :dimension-support-v1 without-hash))]
        (when-not (= computed (:dimension-support/hash support))
          (swap! errors conj (str "dimension-support/hash mismatch: declared "
                                  (:dimension-support/hash support)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn reconcile-against-manifest
  "Reconcile all entries in a dimension-support artifact against an
   outcome manifest.  Every :execution-source entry must have its
   evidence-root verified against the manifest's actual outcome roots.

   Returns {:reconciled? bool :entry-results [{:reconciled? ... :violations ...} ...]}."
  [support outcome-manifest]
  (let [results (mapv #(validate-entry-against-manifest % outcome-manifest)
                      (:dimensions support))]
    {:reconciled? (every? :reconciled? results)
     :entry-results results}))
