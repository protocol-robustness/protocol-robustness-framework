(ns resolver-sim.benchmark.content-registry-entry
  "Benchmark content registry entry.
   
   Primary semantic identifier for one version of a research-benchmark model.
   
   model-root is mandatory. The content-root is derived from the full model
   package — not from manually selected scenarios — so that two research
   cells evaluating the same model share a content-root regardless of
   authorship, status or review-round membership.
   
   Component roots use explicit status maps:
     {:status :modelled :root \"sha256:...\"}
     {:status :not-modelled :root nil}
     {:status :not-applicable :root nil}
     {:status :externally-defined :root \"sha256:...\"}
     {:status :deferred :root nil}   — with :reason-code and :expected-version
     {:status :provisional :root \"sha256:...\"}
   
   Status/root validation rules:
     :modelled            root must be non-nil string
     :not-modelled        root must be nil
     :not-applicable      root must be nil
     :externally-defined  root must be non-nil string
     :deferred            root should be nil; rationale recommended
   
   The content-root policy is versioned — a future change in included
   components or ordering must create a new policy version."
  (:require [clojure.set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "benchmark-content-registry-entry.v1")

(def ^:const content-root-policy "benchmark-model-package-content-root.v1")

(def ^:private valid-statuses
  "Controlled vocabulary for component status with root validation rules.
   
   Status          Root         Notes
   :modelled       required     component hash is present and final
   :externally-defined required  externally supplied, root is the external reference
   :not-modelled   prohibited   component is intentionally absent
   :not-applicable prohibited   component does not apply to this benchmark
   :deferred       prohibited   component deferred; must carry :reason-code and :expected-version
   :provisional    required     provisional component with a working hash"
  {:modelled           {:root-required? true  :root-type string?  :extra-fields #{}}
   :externally-defined {:root-required? true  :root-type string?  :extra-fields #{}}
   :not-modelled       {:root-required? false :root-type nil?     :extra-fields #{}}
   :not-applicable     {:root-required? false :root-type nil?     :extra-fields #{}}
   :deferred           {:root-required? false :root-type nil?     :extra-fields #{:reason-code :expected-version}}
   :provisional        {:root-required? true  :root-type string?  :extra-fields #{}}})

(defn valid-component-status?
  "True when the status keyword is in the controlled vocabulary."
  [status]
  (contains? valid-statuses status))

(defn valid-component-map?
  "Validate a structured component map against status/root rules.
   Returns {:valid? bool :errors [string]}."
  [m]
  (let [status (:status m)
        root (:root m)
        reason-code (:reason-code m)
        expected-version (:expected-version m)]
    (if-not (contains? valid-statuses status)
      {:valid? false :errors [(str "invalid status: " status)]}
      (let [rule (get valid-statuses status)
            errors (cond-> []
                     (and (:root-required? rule) (nil? root))
                     (conj (str "status " status " requires non-nil root"))
                     (and (not (:root-required? rule)) (some? root))
                     (conj (str "status " status " requires nil root, got " (pr-str root)))
                     (and (some? root) (not ((:root-type rule) root)))
                     (conj (str "root must be a string for status " status))
                     (and (= status :deferred) (nil? reason-code))
                     (conj (str "status :deferred requires :reason-code"))
                     (and (= status :deferred) (nil? expected-version))
                     (conj (str "status :deferred requires :expected-version")))
            extra-keys (set (keys m))
            allowed-keys (into #{:status :root} (:extra-fields rule))
            unexpected (clojure.set/difference extra-keys allowed-keys)]
        (cond-> {:valid? (empty? errors) :errors errors}
          (seq unexpected)
          (update :errors concat (mapv #(str "unexpected field: " (name %)) unexpected)))))))

(defn- normalise-component
  "Normalise a model component value to a canonical status/root map."
  [v]
  (cond
    (string? v) {:status :modelled :root v}
    (map? v) (let [status (or (:status v) :modelled)
                   root (:root v)
                   reason-code (:reason-code v)
                   expected-version (:expected-version v)]
               (if (valid-component-status? status)
                 (cond-> {:status status :root root}
                   reason-code (assoc :reason-code reason-code)
                   expected-version (assoc :expected-version expected-version))
                 {:status :not-modelled :root nil}))
    (nil? v) {:status :not-modelled :root nil}
    :else {:status :not-modelled :root nil}))

(defn- extract-root
  "Get the root hash string from a component value, or nil."
  [v]
  (:root (normalise-component v)))

(defn- compute-content-root
  "Compute the semantic content root hash from the 12 model-package components."
  [{:keys [benchmark/research-question
           benchmark/model-root
           benchmark/incentive-model-root
           benchmark/adversary-model-root
           benchmark/parameter-domain-root
           benchmark/generator-root
           benchmark/case-selection-policy-root
           benchmark/fixed-regression-case-root
           benchmark/claims-root
           benchmark/falsifier-root
           benchmark/evaluation-policy-root
           benchmark/evidence-contract-root]}]
  (let [package {:content-root-policy content-root-policy
                 :research-question research-question
                 :model-root model-root
                 :incentive-model-root (extract-root incentive-model-root)
                 :adversary-model-root (extract-root adversary-model-root)
                 :parameter-domain-root (extract-root parameter-domain-root)
                 :generator-root (extract-root generator-root)
                 :case-selection-policy-root (extract-root case-selection-policy-root)
                 :fixed-regression-case-root (extract-root fixed-regression-case-root)
                 :claims-root (extract-root claims-root)
                 :falsifier-root (extract-root falsifier-root)
                 :evaluation-policy-root (extract-root evaluation-policy-root)
                 :evidence-contract-root (extract-root evidence-contract-root)}]
    (hc/domain-hash :benchmark-semantic-content package)))

(defn build-entry
  "Build a benchmark content registry entry.
   
   model-root is required — the benchmark is the model, not selected scenarios.
   content-root is derived from the 12 model-package components.
   
   If an explicit :benchmark/content-root is supplied, it is verified
   against the computed value and rejected on mismatch.
   
   Component values may be:
     - a string (treated as {:status :modelled :root string})
     - a map with :status and :root (validated against status/root rules)
     - nil (treated as {:status :not-modelled :root nil})"
  [{:keys [benchmark/id
           benchmark/version
           benchmark/research-question
           benchmark/model-root
           benchmark/model-schema
           benchmark/incentive-model-root
           benchmark/adversary-model-root
           benchmark/parameter-domain-root
           benchmark/generator-root
           benchmark/case-selection-policy-root
           benchmark/fixed-regression-case-root
           benchmark/claims-root
           benchmark/falsifier-root
           benchmark/evaluation-policy-root
           benchmark/evidence-contract-root
           benchmark/content-root
           benchmark/status
           benchmark/provenance
           benchmark/supersedes]}]
  (when (nil? model-root)
    (throw (ex-info "model-root is required for content-registry-entry"
                    {:benchmark/id id})))
  (let [semantic-components
        {:benchmark/research-question research-question
         :benchmark/model-root model-root
         :benchmark/incentive-model-root incentive-model-root
         :benchmark/adversary-model-root adversary-model-root
         :benchmark/parameter-domain-root parameter-domain-root
         :benchmark/generator-root generator-root
         :benchmark/case-selection-policy-root case-selection-policy-root
         :benchmark/fixed-regression-case-root fixed-regression-case-root
         :benchmark/claims-root claims-root
         :benchmark/falsifier-root falsifier-root
         :benchmark/evaluation-policy-root evaluation-policy-root
         :benchmark/evidence-contract-root evidence-contract-root}
        computed-root (str "sha256:" (compute-content-root semantic-components))]
    (when (and content-root (not= content-root computed-root))
      (throw (ex-info "Declared content-root does not match computed value"
                      {:declared content-root
                       :computed computed-root
                       :benchmark/id id})))
    (let [normalise-and-validate
          (fn [v label]
            (let [m (normalise-component v)
                  validation (valid-component-map? m)]
              (when-not (:valid? validation)
                (throw (ex-info (str "Invalid component state for " label ": " (:errors validation))
                                {:component label :errors (:errors validation)})))
              m))
          registry-record
          {:schema-version schema-version
           :benchmark/content-root-policy content-root-policy
           :benchmark/id id
           :benchmark/version (or version 1)
           :benchmark/research-question research-question
           :benchmark/model-root model-root
           :benchmark/model-schema (or model-schema "research-benchmark-model.v1")
           :benchmark/content-root computed-root
           :benchmark/incentive-model-root (normalise-and-validate incentive-model-root "incentive-model-root")
           :benchmark/adversary-model-root (normalise-and-validate adversary-model-root "adversary-model-root")
           :benchmark/parameter-domain-root (normalise-and-validate parameter-domain-root "parameter-domain-root")
           :benchmark/generator-root (normalise-and-validate generator-root "generator-root")
           :benchmark/case-selection-policy-root (normalise-and-validate case-selection-policy-root "case-selection-policy-root")
           :benchmark/fixed-regression-case-root (normalise-and-validate fixed-regression-case-root "fixed-regression-case-root")
           :benchmark/claims-root (normalise-and-validate claims-root "claims-root")
           :benchmark/falsifier-root (normalise-and-validate falsifier-root "falsifier-root")
           :benchmark/evaluation-policy-root (normalise-and-validate evaluation-policy-root "evaluation-policy-root")
           :benchmark/evidence-contract-root (normalise-and-validate evidence-contract-root "evidence-contract-root")
           :benchmark/status (or status :draft)
           :benchmark/provenance provenance
           :benchmark/supersedes supersedes}
          registry-hash (hc/domain-hash :benchmark-registry-entry registry-record)]
      (assoc registry-record
             :benchmark/registry-entry-hash (str "sha256:" registry-hash)))))

(defn content-root
  "Return the semantic content root hash.
   Two entries with the same content-root represent the same model package."
  [entry]
  (:benchmark/content-root entry))

(defn registry-entry-hash
  "Return the full registry entry hash including authorship and provenance."
  [entry]
  (:benchmark/registry-entry-hash entry))

(defn model-root
  "Return the model root hash."
  [entry]
  (:benchmark/model-root entry))

(defn same-content?
  "True when two entries represent the same benchmark model package."
  [a b]
  (= (:benchmark/content-root a) (:benchmark/content-root b)))

(defn entry-valid?
  "Quick structural check for builder-produced entries.
   Prefer validate-entry for externally loaded artifacts."
  [entry]
  (and (= schema-version (:schema-version entry))
       (some? (:benchmark/id entry))
       (some? (:benchmark/model-root entry))
       (some? (:benchmark/content-root entry))
       (some? (:benchmark/registry-entry-hash entry))))

(defn validate-entry
  "Standalone validator for a loaded registry entry.
   
   Recomputes the content-root, validates all component status/root
   combinations, and checks structural integrity.
   
   Returns {:valid? bool :errors [string] :warnings [string]}.
   
   This is safe to call on externally loaded JSON or EDN — does not
   assume the artifact was produced through build-entry."
  [entry]
  (let [errors (atom [])
        warnings (atom [])]
    ;; Schema version
    (when-not (= schema-version (:schema-version entry))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version entry))))
    ;; Required top-level fields
    (when-not (some? (:benchmark/id entry))
      (swap! errors conj "missing :benchmark/id"))
    (when-not (some? (:benchmark/model-root entry))
      (swap! errors conj "missing :benchmark/model-root"))
    (let [mr (:benchmark/model-root entry)]
      (when (and (some? mr) (not (re-matches #"sha256:[0-9a-f]{64}" mr)))
        (swap! errors conj (str ":benchmark/model-root is not a valid sha256: hash: " mr))))
    (let [ms (:benchmark/model-schema entry)]
      (when (and (some? ms) (not= ms "research-benchmark-model.v1"))
        (swap! errors conj (str ":benchmark/model-schema \"" ms "\" — expected \"research-benchmark-model.v1\""))))
    ;; Validate each component
    (doseq [component-key [:benchmark/model-root
                           :benchmark/incentive-model-root
                           :benchmark/adversary-model-root
                           :benchmark/parameter-domain-root
                           :benchmark/generator-root
                           :benchmark/case-selection-policy-root
                           :benchmark/fixed-regression-case-root
                           :benchmark/claims-root
                           :benchmark/falsifier-root
                           :benchmark/evaluation-policy-root
                           :benchmark/evidence-contract-root]
            :let [value (get entry component-key)]]
      (when (map? value)
        (let [result (valid-component-map? value)]
          (when-not (:valid? result)
            (doseq [e (:errors result)]
              (swap! errors conj (str (name component-key) ": " e)))))))
    ;; Content-root recompute
    (let [computed-components
          {:benchmark/research-question (:benchmark/research-question entry)
           :benchmark/model-root (:benchmark/model-root entry)
           :benchmark/incentive-model-root (:benchmark/incentive-model-root entry)
           :benchmark/adversary-model-root (:benchmark/adversary-model-root entry)
           :benchmark/parameter-domain-root (:benchmark/parameter-domain-root entry)
           :benchmark/generator-root (:benchmark/generator-root entry)
           :benchmark/case-selection-policy-root (:benchmark/case-selection-policy-root entry)
           :benchmark/fixed-regression-case-root (:benchmark/fixed-regression-case-root entry)
           :benchmark/claims-root (:benchmark/claims-root entry)
           :benchmark/falsifier-root (:benchmark/falsifier-root entry)
           :benchmark/evaluation-policy-root (:benchmark/evaluation-policy-root entry)
           :benchmark/evidence-contract-root (:benchmark/evidence-contract-root entry)}
          computed-root (str "sha256:" (compute-content-root computed-components))]
      (when-not (= computed-root (:benchmark/content-root entry))
        (swap! errors conj (str "content-root mismatch: declared "
                                (:benchmark/content-root entry)
                                " computed " computed-root))))
    (let [reg-hash (:benchmark/registry-entry-hash entry)]
      (when reg-hash
        (when (nil? (:benchmark/content-root entry))
          (swap! warnings conj "registry-entry-hash present without content-root"))
        (let [registry-record (dissoc entry :benchmark/registry-entry-hash)
              computed (str "sha256:" (hc/domain-hash :benchmark-registry-entry registry-record))]
          (when-not (= computed reg-hash)
            (swap! errors conj (str "registry-entry-hash mismatch: declared "
                                    reg-hash " computed " computed))))))
    {:valid? (empty? @errors)
     :errors @errors
     :warnings @warnings}))
