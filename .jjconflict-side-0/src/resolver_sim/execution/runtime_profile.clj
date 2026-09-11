(ns resolver-sim.execution.runtime-profile
  "Canonical identity for resolved runtime execution controls.

   Runtime-profile roots describe how execution was requested; they are not
   semantic allocation roots and must never be included in allocation inputs or
   evidence projections."
  (:require [clojure.edn :as edn]
            [resolver-sim.execution.context :as execution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema-version "runtime-profile.v1")
(def claimant-options-type :prf/claimant-options-v1)
(def claimant-option-fields
  #{:execution/claimant-parallelism
    :execution/claimant-parallel-threshold
    :execution/quiescence-timeout-seconds})

(defn root [profile]
  (ref/sha256-ref
   (hc/domain-hash :runtime-profile
                   (dissoc profile :runtime-profile/root))))

(defn validate [profile]
  (let [options (:runtime-profile/requested profile)
        keys* (set (keys profile))
        option-keys (set (keys options))
        errors (cond-> []
                 (not= #{:runtime-profile/type :runtime-profile/version
                         :runtime-profile/requested :runtime-profile/root}
                       keys*)
                 (conj :invalid-runtime-profile-shape)
                 (not= claimant-options-type (:runtime-profile/type profile))
                 (conj :unsupported-runtime-profile-type)
                 (not= 1 (:runtime-profile/version profile))
                 (conj :unsupported-runtime-profile-version)
                 (not= claimant-option-fields option-keys)
                 (conj :invalid-claimant-options-shape)
                 (not= options (try (select-keys (execution/validate-context options)
                                                 claimant-option-fields)
                                    (catch Exception _ ::invalid)))
                 (conj :invalid-claimant-options)
                 (not= (:runtime-profile/root profile) (root profile))
                 (conj :runtime-profile-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build [options]
  (let [resolved (select-keys (execution/validate-context options)
                              claimant-option-fields)
        profile {:runtime-profile/type claimant-options-type
                 :runtime-profile/version 1
                 :runtime-profile/requested resolved}]
    (assoc profile :runtime-profile/root (root profile))))

(defn valid? [profile]
  (:valid? (validate profile)))

(defn resolve-runtime-profile
  "Resolve and canonically identify a requested runtime profile."
  [requested]
  (let [profile (build requested)]
    (when-not (valid? profile)
      (throw (ex-info "Invalid runtime profile" (validate profile))))
    profile))

(defn -main [& args]
  (let [path (first args)]
    (when-not path
      (throw (ex-info "Usage: runtime-profile <request.edn>" {})))
    (prn (resolve-runtime-profile (edn/read-string (slurp path))))))
