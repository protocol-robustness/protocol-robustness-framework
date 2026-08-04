(ns resolver-sim.conformance.profile
  "Conformance profile descriptor.

   A conformance profile unifies the configuration surfaces that currently live
   separately: fixture contracts, vocabulary registry, actions, roles,
   projections, invariants, comparison policy, required implementation
   components, and verdict policy.

   Descriptors identify REGISTERED EXECUTABLE implementations by id — e.g.
   :decoder/id :sew-finalize-v2 — they never carry code, dynamic namespaces, or
   unconstrained symbols.

   This is the first-profile home for trace equivalence
   (:sew-trace-equivalence.v1); see etc/conformance/profiles/."
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc]))

(def profile-schema-version "conformance-profile.v1")

(defn load-profile
  "Read a conformance profile descriptor from an EDN file."
  [path]
  (edn/read-string (slurp path)))

(defn profile-root
  "Content root of a profile descriptor, excluding its own :profile/root.
   Committed so profile activation is observable: a receipt binds this root
   and enumerates the rules actually applied."
  [profile]
  (hc/domain-hash "conformance.profile.v1" (dissoc profile :profile/root)))

(defn required-component-ids
  "Ordered component ids the profile requires to be present in the
   implementation identity bundle."
  [profile]
  (mapv :component/id (:profile/required-components profile [])))

(defn- required-profile-keys
  []
  [:profile/schema-version :profile/id :profile/version
   :profile/fixture-contract :profile/vocabulary-registry
   :profile/required-components :profile/verdict-policy])

(defn validate-profile
  "Structural validation of a conformance profile descriptor.

   Checks:
     - schema version matches conformance-profile.v1;
     - required top-level keys are present;
     - declared component ids are unique;
     - capability requirements are well-formed ({:capability kind :version n}).

   Descriptors identify executable implementations by id; this validator does
   not resolve or load them."
  [profile]
  (let [v (into []
                (keep (fn [k]
                        (when (nil? (get profile k))
                          {:violation/id :violation/missing-profile-key
                           :details {:key k}})))
                (required-profile-keys))
        v (if (= profile-schema-version (:profile/schema-version profile))
            v
            (conj v {:violation/id :violation/invalid-profile-schema-version
                     :details {:expected profile-schema-version
                               :received (:profile/schema-version profile)}}))
        component-ids (map :component/id (:profile/required-components profile []))
        v (let [dups (->> component-ids frequencies
                          (keep (fn [[id n]] (when (> n 1) id)))
                          vec)]
            (if (seq dups)
              (conj v {:violation/id :violation/duplicate-component
                       :details {:duplicate-ids dups}})
              v))
        v (reduce (fn [vs req]
                    (cond-> vs
                      (or (nil? (:capability req)) (nil? (:version req)))
                      (conj {:violation/id :violation/invalid-capability-requirement
                             :details {:requirement req}})))
                  v
                  (get-in profile [:profile/capabilities :required] []))
        v (reduce (fn [vs b]
                    (if (keyword? b) vs
                        (conj vs {:violation/id :violation/invalid-derivation-boundary
                                  :details {:boundary b}})))
                  v
                  (get-in profile [:profile/verdict-policy :derivation-boundaries] []))]
    {:valid? (empty? v) :violations v}))
