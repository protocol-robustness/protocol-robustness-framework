(ns resolver-sim.use-cases.registry
  "Explicit, fail-closed loading for externally supplied use-case registries.

   Use-case content is never discovered from the classpath, current directory,
   or a default location. Callers must provide a registry path explicitly."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.concepts.registry :as concepts]))

(def registry-schema :prf/use-case-registry.v1)
(def sequence-schema :prf/sequence.v1)
(def sequence-step-statuses #{:not-implemented})

(declare fail)

(defn- valid-keyword-collection? [value]
  (and (or (set? value) (vector? value))
       (every? keyword? value)))

(defn- sequence-error! [registry-file use-case-id message data]
  (fail (str "Use-case :use-case/sequence " message)
        (merge {:registry/path (.getPath registry-file)
                :use-case/id use-case-id}
               data)))

(defn- validate-sequence! [definition registry-file use-case-id]
  (when-let [sequence (:use-case/sequence definition)]
    (when-not (map? sequence)
      (sequence-error! registry-file use-case-id "must be a map"
                       {:use-case/sequence sequence}))
    (when-not (= sequence-schema (:sequence/schema sequence))
      (sequence-error! registry-file use-case-id "has an unsupported schema"
                       {:expected sequence-schema
                        :actual (:sequence/schema sequence)}))
    (when-not (keyword? (:sequence/id sequence))
      (sequence-error! registry-file use-case-id "must have a keyword :sequence/id"
                       {:sequence/id (:sequence/id sequence)}))
    (when-not (pos-int? (:sequence/version sequence))
      (sequence-error! registry-file use-case-id "must have a positive integer :sequence/version"
                       {:sequence/version (:sequence/version sequence)}))
    (let [steps (:sequence/steps sequence)]
      (when-not (and (vector? steps) (seq steps))
        (sequence-error! registry-file use-case-id "must have a non-empty vector :sequence/steps"
                         {:sequence/steps steps}))
      (let [step-ids (mapv :sequence.step/id steps)]
        (when-not (every? keyword? step-ids)
          (sequence-error! registry-file use-case-id "steps must have keyword :sequence.step/id values"
                           {:sequence.step/ids step-ids}))
        (when-not (= (count step-ids) (count (set step-ids)))
          (sequence-error! registry-file use-case-id "step IDs must be unique"
                           {:sequence.step/ids step-ids}))
        (doseq [[index step] (map-indexed vector steps)]
          (when-not (map? step)
            (sequence-error! registry-file use-case-id "steps must be maps"
                             {:sequence.step/index index :sequence.step step}))
          (when-not (keyword? (:sequence.step/examination step))
            (sequence-error! registry-file use-case-id "steps must have keyword :sequence.step/examination values"
                             {:sequence.step/id (:sequence.step/id step)}))
          (when (contains? step :sequence.step/evidence)
            (when-not (valid-keyword-collection? (:sequence.step/evidence step))
              (sequence-error! registry-file use-case-id "step evidence must be a set or vector of keywords"
                               {:sequence.step/id (:sequence.step/id step)
                                :sequence.step/evidence (:sequence.step/evidence step)})))
          (when (contains? step :sequence.step/status)
            (when-not (contains? sequence-step-statuses (:sequence.step/status step))
              (sequence-error! registry-file use-case-id "step status is unsupported"
                               {:sequence.step/id (:sequence.step/id step)
                                :sequence.step/status (:sequence.step/status step)
                                :supported-statuses sequence-step-statuses})))
          (let [after (:sequence.step/after step)
                preceding-ids (set (take index step-ids))]
            (when (contains? step :sequence.step/after)
              (when-not (valid-keyword-collection? after)
                (sequence-error! registry-file use-case-id "step dependencies must be a set or vector of keywords"
                                 {:sequence.step/id (:sequence.step/id step)
                                  :sequence.step/after after}))
              (when-not (every? preceding-ids after)
                (sequence-error! registry-file use-case-id "step dependencies must reference preceding steps"
                                 {:sequence.step/id (:sequence.step/id step)
                                  :sequence.step/after after
                                  :preceding-step-ids preceding-ids})))))))))

(defn implemented-step-ids
  "Ordered IDs of use-case steps that V1 applications must realize."
  [definition]
  (->> (get-in definition [:use-case/sequence :sequence/steps])
       (remove #(= :not-implemented (:sequence.step/status %)))
       (mapv :sequence.step/id)))

(defn- capability-identity? [capability]
  (and (map? capability)
       (keyword? (:capability/kind capability))
       (keyword? (:capability/id capability))
       (pos-int? (:capability/version capability))))

(defn- step-capabilities! [definition registry-file use-case-id]
  (when (:use-case/sequence definition)
    (let [requirements (:use-case/step-capabilities definition)
          expected (implemented-step-ids definition)
          actual (mapv :sequence.step/id requirements)]
      (when-not (and (vector? requirements) (= expected actual))
        (fail "Use-case step capabilities must exactly cover implemented steps in sequence order"
              {:registry/path (.getPath registry-file) :use-case/id use-case-id
               :expected-step-ids expected :actual-step-ids actual}))
      (doseq [requirement requirements]
        (when-not (capability-identity? (:capability requirement))
          (fail "Use-case step capability requires a declarative capability identity"
                {:registry/path (.getPath registry-file) :use-case/id use-case-id
                 :step-capability requirement}))))))

(defn- allowed-capabilities! [definition registry-file use-case-id]
  (when-let [capabilities (:use-case/allowed-capabilities definition)]
    (when-not (vector? capabilities)
      (fail "Use-case :use-case/allowed-capabilities must be a vector"
            {:registry/path (.getPath registry-file)
             :use-case/id use-case-id
             :use-case/allowed-capabilities capabilities}))
    (doseq [capability capabilities]
      (when-not (and (map? capability)
                     (keyword? (:capability/kind capability))
                     (keyword? (:capability/id capability))
                     (pos-int? (:capability/version capability)))
        (fail "Use-case allowed capabilities require kind, ID, and positive version"
              {:registry/path (.getPath registry-file)
               :use-case/id use-case-id
               :use-case/allowed-capability capability})))
    (when-not (= (count capabilities)
                 (count (set (map #(select-keys % [:capability/kind :capability/id]) capabilities))))
      (fail "Use-case allowed capability identities must be unique"
            {:registry/path (.getPath registry-file)
             :use-case/id use-case-id
             :use-case/allowed-capabilities capabilities}))))

(defn- required-curiosities! [definition registry-file use-case-id]
  (let [curiosities (:concept/required-curiosities definition)]
    (when (some? curiosities)
      (when-not (and (set? curiosities) (every? keyword? curiosities))
        (fail "Use-case :concept/required-curiosities must be a set of keywords"
              {:registry/path (.getPath registry-file)
               :use-case/id use-case-id
               :concept/required-curiosities curiosities})))
    (or curiosities #{})))

(defn- fail [message data]
  (throw (ex-info message (assoc data :error :use-cases/invalid-registry))))

(defn- canonical-file [path]
  (.getCanonicalFile (io/file path)))

(defn- read-edn-file! [file kind]
  (when-not (.isFile file)
    (fail (str (name kind) " file not found") {:kind kind :path (.getPath file)}))
  (try
    (edn/read-string (slurp file))
    (catch Exception e
      (fail (str "Unable to read " (name kind) " EDN")
            {:kind kind :path (.getPath file) :cause (.getMessage e)}))))

(defn- contained-file! [registry-file ref]
  (when-not (and (string? ref) (not (.isAbsolute (io/file ref))))
    (fail "Use-case :definition/ref must be a relative string"
          {:registry/path (.getPath registry-file) :definition/ref ref}))
  (let [base (.getCanonicalFile (.getParentFile registry-file))
        definition (canonical-file (io/file base ref))
        base-path (.getPath base)
        definition-path (.getPath definition)]
    (when-not (or (= base-path definition-path)
                  (.startsWith definition-path (str base-path java.io.File/separator)))
      (fail "Use-case definition escapes its registry directory"
            {:registry/path (.getPath registry-file) :definition/ref ref}))
    definition))

(defn- canonical-root-value
  "Project EDN collection types into the canonical hash domain without changing
   the loaded definition. Sets are unordered in EDN, so their members become a
   deterministically ordered vector in the root preimage."
  [value]
  (cond
    (set? value) (->> value (map canonical-root-value) (sort-by pr-str) vec)
    (map? value) (into {} (map (fn [[k v]] [k (canonical-root-value v)])) value)
    (sequential? value) (mapv canonical-root-value value)
    :else value))

(defn definition-root
  "Authoritative root of one canonical use-case definition."
  [definition]
  (hash-ref/sha256-ref
   (canonical/domain-hash :use-case-definition-v1
                          (canonical-root-value definition))))

(defn- validate-registry! [registry registry-file]
  (when-not (= registry-schema (:schema/id registry))
    (fail "Unsupported use-case registry schema"
          {:registry/path (.getPath registry-file) :expected registry-schema :actual (:schema/id registry)}))
  (doseq [key [:registry/id :registry/version :use-cases]]
    (when-not (contains? registry key)
      (fail "Use-case registry is missing required key" {:registry/path (.getPath registry-file) :missing key})))
  (when-not (and (string? (:registry/id registry)) (not-empty (:registry/id registry)))
    (fail "Use-case registry :registry/id must be a non-empty string" {:registry/path (.getPath registry-file)}))
  (when-not (and (string? (:registry/version registry)) (not-empty (:registry/version registry)))
    (fail "Use-case registry :registry/version must be a non-empty string" {:registry/path (.getPath registry-file)}))
  (when-not (vector? (:use-cases registry))
    (fail "Use-case registry :use-cases must be a vector" {:registry/path (.getPath registry-file)})))

(defn load-use-case-registry
  "Load one externally supplied :prf/use-case-registry.v1 registry.

   `registry-path` is required and must identify an existing filesystem file.
   Definitions resolve only relative to that file and must remain inside its
   directory. The returned root commits registry metadata and definitions."
  [registry-path]
  (when-not (and (string? registry-path) (not-empty registry-path))
    (fail "An explicit use-case registry path is required" {:registry/path registry-path}))
  (let [registry-file (canonical-file registry-path)
        registry (read-edn-file! registry-file :registry)
        _ (validate-registry! registry registry-file)
        loaded (mapv (fn [{:keys [use-case/id definition/ref] :as entry}]
                       (when-not (keyword? id)
                         (fail "Use-case entry :use-case/id must be a keyword" {:registry/path (.getPath registry-file) :entry entry}))
                       (let [definition-file (contained-file! registry-file ref)
                             definition (read-edn-file! definition-file :definition)]
                         (when-not (= id (:concept/id definition))
                           (fail "Use-case entry ID does not match definition" {:registry/path (.getPath registry-file) :use-case/id id :concept/id (:concept/id definition)}))
                         (when-not (= :use-case (:concept/type definition))
                           (fail "Use-case definition must declare :concept/type :use-case" {:registry/path (.getPath registry-file) :use-case/id id :concept/type (:concept/type definition)}))
                         (validate-sequence! definition registry-file id)
                         (allowed-capabilities! definition registry-file id)
                         (step-capabilities! definition registry-file id)
                         {:use-case/id id
                          :definition/ref ref
                          :definition definition
                          :required-curiosities (required-curiosities! definition registry-file id)}))
                     (:use-cases registry))
        ids (mapv :use-case/id loaded)
        integrity (concepts/registry-integrity-violations
                   (mapv (fn [{:keys [use-case/id definition/ref]}]
                           {:concept/id id :concept/file ref})
                         loaded)
                   (mapv :definition loaded))]
    (when-not (= (count ids) (count (set ids)))
      (fail "Duplicate :use-case/id in registry" {:registry/path (.getPath registry-file) :ids ids}))
    (when (seq integrity)
      (fail "Use-case definitions failed schema validation"
            {:registry/path (.getPath registry-file) :violations integrity}))
    (let [root-input {:schema/id (:schema/id registry) :registry/id (:registry/id registry)
                      :registry/version (:registry/version registry)
                      :use-cases (mapv #(select-keys % [:use-case/id :definition/ref :definition]) loaded)}
          root-input (canonical-root-value root-input)]
      {:use-case-registry/id (:registry/id registry)
       :use-case-registry/version (:registry/version registry)
       :use-case-registry/schema (:schema/id registry)
       :use-case-registry/source :external
       :use-case-registry/path (.getPath registry-file)
       :use-case-registry/root (hash-ref/sha256-ref (canonical/domain-hash :use-case-registry-v1 root-input))
       :use-case-registry/count (count loaded)
       :use-case-registry/required-curiosities
       (into {} (map (juxt :use-case/id :required-curiosities) loaded))
       :use-cases (mapv :definition loaded)})))

(defn required-curiosities
  "Return the explicitly declared curiosity IDs for one loaded use case.
   Missing declarations resolve to the empty set; no curiosity is inferred."
  [loaded use-case-id]
  (get-in loaded [:use-case-registry/required-curiosities use-case-id] #{}))

(defn use-case-index [loaded]
  (into {} (map (juxt :concept/id identity) (:use-cases loaded))))
