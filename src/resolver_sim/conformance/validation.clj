(ns resolver-sim.conformance.validation
  "Generic validation contract.

   A validation result has a stable shape regardless of domain:

     {:validation/id                <keyword validator id>
      :validation/kind              :schema | :semantic | :capability | :integrity
      :validation/version           <int>
      :validation/status            :pass | :rejected
      :validation/issues            [<issue-map> ...]
      :validation/subject-root      <sha256 of the validated subject>
      :validation/implementation-root <sha256 of the validator implementation>}

   Validators are resolved by id from a CLOSED registry — resolution never
   loads code, it looks up a registered executable implementation:

     (resolve-validator :trace-fixture-v2-semantics)

   Shared rules enforced here:
     - every required validator declared by a profile must resolve;
     - every result identifies the validator implementation version;
     - duplicate validation layers are rejected;
     - required layers cannot be skipped;
     - validation results bind the subject root.

   Domain adapters (e.g. resolver-sim.trace.conformance.*) register their
   validators into this registry; the generic package never references them."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Validator registry (closed)
;; ---------------------------------------------------------------------------

(defonce ^:private validators (atom {}))

(def validation-kinds #{:schema :semantic :capability :integrity})

(def ^:const validation-receipt-schema-version "conformance.validation-receipt/v1")
(def ^:const default-validator-version 1)

(defn register-validator!
  "Register a validator implementation.  spec keys:
     :validator/id, :validator/kind, :validator/input-contract,
     :validator/version, :validator/implementation-root,
     :validator/run (fn [subject] -> validation-result-map)

   Also mirrors the validator into the closed implementation registry so the
   committed registry root reflects the full validator implementation surface
   and receipts can bind the registry root used."
  [spec]
  (let [id (:validator/id spec)]
    (when-not id
      (throw (ex-info "validator requires :validator/id" {:spec spec})))
    (swap! validators assoc id spec)
    (registry/register!
     {:implementation/id id
      :implementation/kind :validator
      :implementation/domain (or (:validator/domain spec) :generic)
       :implementation/version (or (:validator/version spec) default-validator-version)
      :implementation/source-root (or (:validator/implementation-root spec) "sha256:none")
      :implementation/status :active})
    id))

(defn resolve-validator
  "Resolve a registered validator implementation by id (or nil when absent)."
  [id]
  (get @validators id))

(defn registered-validator-ids
  []
  (keys @validators))

(defn require-validator-resolvable!
  "Throw unless every required validator id resolves."
  [required-ids]
  (let [missing (remove resolve-validator required-ids)]
    (when (seq missing)
      (throw (ex-info "required validators are not resolvable"
                      {:missing (vec missing)
                       :registered (vec (registered-validator-ids))})))))

;; ---------------------------------------------------------------------------
;; Result shape
;; ---------------------------------------------------------------------------

(defn subject-root
  "Content root of a validation subject (deterministic, canonical)."
  [subject]
  (hc/domain-hash :conformance-validation-subject-v1 subject))

(defn validation-issue
  "Build a single validation issue.  code is a keyword; details is an optional map."
  [code & [details]]
  (cond-> {:issue/code code}
    details (assoc :issue/details details)))

(defn- base-result
  [validator-id kind version implementation-root subject status issues]
   {:schema-version validation-receipt-schema-version
   :validation/id validator-id
   :validation/kind kind
   :validation/version version
   :validation/status status
   :validation/issues (vec issues)
   :validation/subject-root (subject-root subject)
   :validation/implementation-root implementation-root
   :implementation-registry/root (registry/registry-root)})

(defn pass-result
  "Produce a :pass validation result for a subject."
  [{:keys [validator/id validator/kind validator/version
           validator/implementation-root]} subject]
  (base-result id kind version implementation-root subject :pass []))

(defn reject-result
  "Produce a :rejected validation result for a subject, with issues."
  [validator-spec subject issues]
  (base-result (:validator/id validator-spec)
               (:validator/kind validator-spec)
               (:validator/version validator-spec)
               (:validator/implementation-root validator-spec)
               subject
               :rejected
               issues))

(defn valid?
  [{:keys [validation/status]}]
  (= :pass status))

;; ---------------------------------------------------------------------------
;; Layered execution
;; ---------------------------------------------------------------------------

(defn validate-layers
  "Run validator ids in order over a subject.

   Enforces the shared rules:
     - every id resolves;
     - the same validator is not registered twice (duplicate validator ids
       rejected — two DISTINCT validators of the same kind, e.g. two semantic
       validators in one profile, are legitimate);
     - every kind in required-kinds is present (required layers cannot be
       skipped).

   Returns {:results [<validation-result>...]
            :valid? bool
            :issues [<issue>...]  ; top-level enforcement issues}."
  [validator-ids required-kinds subject]
  (let [specs (mapv resolve-validator validator-ids)
        missing (keep-indexed (fn [i s] (when (nil? s) (nth validator-ids i))) specs)
        dup-ids (->> validator-ids frequencies
                     (keep (fn [[id n]] (when (> n 1) id)))
                     vec)
        kinds (keep :validator/kind specs)
        absent-kinds (remove (set kinds) required-kinds)
        enforcement (into []
                          (concat
                           (map (fn [id] {:issue/code :validator-not-resolved
                                          :issue/details {:validator/id id}})
                                missing)
                           (map (fn [id] {:issue/code :duplicate-validator-id
                                          :issue/details {:validator/id id}})
                                dup-ids)
                           (map (fn [k] {:issue/code :required-layer-skipped
                                         :issue/details {:kind k}})
                                absent-kinds)))
        results (when (empty? enforcement)
                  (mapv (fn [spec] ((:validator/run spec) subject)) specs))]
    {:results results
     :valid? (and (empty? enforcement)
                  (every? valid? results))
     :issues enforcement}))
