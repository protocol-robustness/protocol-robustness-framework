(ns resolver-sim.research.runner-participation
  "Rooted runner opt-in and capability declarations for research scheduling.

   Disclosure mode, authentication, qualifications, availability, capability,
   and opt-in are deliberately independent. This artifact issues no credential
   and establishes no reputation or real-world identity."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "runner-participation.v1")
(def disclosure-modes #{:identified :pseudonymous :anonymous})
(def ^:private required-fields
  #{:artifact/schema :runner/reference :runner/disclosure-mode :runner/site-id
    :participation/opted-in? :participation/available? :runner/capabilities
    :participation/job-classes :runner/environment-kinds
    :runner/qualification-references :runner-participation/root})

(defn participation-root [participation]
  (hash-ref/sha256-ref
   (hc/domain-hash :runner-participation-v1
                   (dissoc participation :runner-participation/root))))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn- canonical-distinct-vector? [values]
  (and (vector? values)
       (= values (vec (sort values)))
       (= (count values) (count (set values)))))

(defn validate-participation [participation]
  (let [errors
        (cond-> []
          (not (map? participation)) (conj :runner-participation/not-a-map)
          (and (map? participation) (not= schema (:artifact/schema participation)))
          (conj :runner-participation/unsupported-schema)
          (and (map? participation)
               (not (contains? #{required-fields
                                 (conj required-fields :runner/authentication-reference)}
                               (set (keys participation)))))
          (conj :runner-participation/invalid-fields)
          (not (root? (:runner/reference participation)))
          (conj :runner-participation/invalid-runner-reference)
          (not (contains? disclosure-modes (:runner/disclosure-mode participation)))
          (conj :runner-participation/invalid-disclosure-mode)
          (not (keyword? (:runner/site-id participation)))
          (conj :runner-participation/invalid-site)
          (not (boolean? (:participation/opted-in? participation)))
          (conj :runner-participation/invalid-opt-in)
          (not (boolean? (:participation/available? participation)))
          (conj :runner-participation/invalid-availability)
          (not (and (canonical-distinct-vector? (:runner/capabilities participation))
                    (every? keyword? (:runner/capabilities participation))))
          (conj :runner-participation/invalid-capabilities)
          (not (and (canonical-distinct-vector? (:participation/job-classes participation))
                    (every? keyword? (:participation/job-classes participation))))
          (conj :runner-participation/invalid-job-classes)
          (not (and (canonical-distinct-vector? (:runner/environment-kinds participation))
                    (every? keyword? (:runner/environment-kinds participation))))
          (conj :runner-participation/invalid-environments)
          (not (and (canonical-distinct-vector? (:runner/qualification-references participation))
                    (every? root? (:runner/qualification-references participation))))
          (conj :runner-participation/invalid-qualification-references)
          (and (contains? participation :runner/authentication-reference)
               (not (root? (:runner/authentication-reference participation))))
          (conj :runner-participation/invalid-authentication-reference)
          (and (map? participation)
               (not= (:runner-participation/root participation)
                     (try (participation-root participation) (catch Exception _ nil))))
          (conj :runner-participation/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-participation
  [{:keys [runner-reference disclosure-mode site-id opted-in? available?
           capabilities job-classes environment-kinds qualification-references
           authentication-reference]}]
  (let [body (cond->
              {:artifact/schema schema
               :runner/reference runner-reference
               :runner/disclosure-mode disclosure-mode
               :runner/site-id site-id
               :participation/opted-in? opted-in?
               :participation/available? available?
               :runner/capabilities (vec (sort capabilities))
               :participation/job-classes (vec (sort job-classes))
               :runner/environment-kinds (vec (sort environment-kinds))
               :runner/qualification-references
               (vec (sort qualification-references))}
               authentication-reference
               (assoc :runner/authentication-reference authentication-reference))
        rooted (assoc body :runner-participation/root (participation-root body))
        validation (validate-participation rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid runner participation" validation)))
    rooted))
