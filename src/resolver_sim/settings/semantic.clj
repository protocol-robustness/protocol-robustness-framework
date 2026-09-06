(ns resolver-sim.settings.semantic
  "Rooted application-semantic setting artifacts.

  This namespace validates ownership structure only. It deliberately does not
  interpret setting IDs or values; their semantic meaning remains owned by the
  application, protocol, capability, or module named by :setting/owner-id."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "semantic-setting.v1")
(def ^:const domain "PRF_SEMANTIC_SETTING_V1")

(def ^:private required-fields
  #{:setting/schema :setting/id :setting/value :setting/owner-id})

(defn setting-root
  "Return the semantic identity root. Owner identity is committed so the same
  setting ID and value cannot be silently reinterpreted by another owner."
  [setting]
  (hash-ref/sha256-ref
   (hc/domain-hash domain
                   (select-keys setting required-fields))))

(defn validate-setting
  "Validate a closed semantic-setting.v1 artifact. `:setting/value` must be in
  the canonical hash domain, but is otherwise application-defined."
  [setting]
  (let [errors (cond-> []
                 (not (map? setting)) (conj :setting/not-a-map)
                 (and (map? setting)
                      (not= required-fields
                            (set (keys (dissoc setting :setting/root)))))
                 (conj :setting/invalid-shape)
                 (and (map? setting) (not= schema (:setting/schema setting)))
                 (conj :setting/unsupported-schema)
                 (and (map? setting) (not (qualified-keyword? (:setting/id setting))))
                 (conj :setting/invalid-id)
                 (and (map? setting) (not (qualified-keyword? (:setting/owner-id setting))))
                 (conj :setting/missing-or-invalid-owner)
                 (and (map? setting)
                      (not (hash-ref/valid-sha256-ref? (:setting/root setting))))
                 (conj :setting/invalid-root))]
    (let [errors (try
                   (when (map? setting)
                     (hc/validate-canonical-value! (:setting/value setting)))
                   errors
                   (catch Exception _
                     (conj errors :setting/non-canonical-value)))
          errors (if (and (map? setting)
                          (not= (:setting/root setting) (setting-root setting)))
                   (conj errors :setting/root-mismatch)
                   errors)]
      {:valid? (empty? errors) :errors (vec errors)})))

(defn build-setting
  "Build a rooted semantic setting. Values and IDs are application-defined."
  [{:setting/keys [id value owner-id]}]
  (let [base {:setting/schema schema
              :setting/id id
              :setting/value value
              :setting/owner-id owner-id}
        setting (assoc base :setting/root (setting-root base))
        validation (validate-setting setting)]
    (when-not (:valid? validation)
      (throw (ex-info "invalid semantic setting" validation)))
    setting))

(defn owner-bound-to-application?
  "True when setting ownership is one of the capability identities bound into
  an application. The application owns this closure check; framework code does
  not interpret setting IDs."
  [setting capability-descriptors]
  (contains? (set (map :capability/id capability-descriptors))
             (:setting/owner-id setting)))
