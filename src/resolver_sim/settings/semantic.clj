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
  "Return semantic identity root. Resolution metadata is intentionally excluded."
  [setting]
  (hash-ref/sha256-ref
   (hc/domain-hash domain
                   (select-keys setting required-fields))))

(defn validate-setting
  "Validate a rooted semantic setting. `:setting/resolution`, when present, is
   non-semantic locator metadata and does not affect the setting root."
  [setting]
  (let [allowed-fields (conj required-fields :setting/root :setting/resolution)
        errors (cond-> []
                 (not (map? setting)) (conj :setting/not-a-map)
                 (and (map? setting)
                      (not= (set (keys setting))
                            (set (filter allowed-fields (keys setting)))))
                 (conj :setting/invalid-shape)
                 (and (map? setting) (contains? setting :setting/resolution)
                      (not (map? (:setting/resolution setting))))
                 (conj :setting/invalid-resolution)
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
  "Build a rooted semantic setting. Resolution metadata is not root-committed."
  [{:setting/keys [id value owner-id resolution]}]
  (let [base {:setting/schema schema
              :setting/id id
              :setting/value value
              :setting/owner-id owner-id}
        setting (cond-> (assoc base :setting/root (setting-root base))
                  resolution (assoc :setting/resolution resolution))
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
