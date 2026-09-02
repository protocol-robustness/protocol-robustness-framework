(ns resolver-sim.execution.observation
  "Canonical observation of claimant execution realization."
  (:require [resolver-sim.execution.runtime-profile :as runtime-profile]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def observation-type :prf/claimant-execution-observation-v1)
(def reasons #{:parallel :parallel-budget-limited
               :serial-requested :serial-threshold :serial-observer
               :serial-budget-limited :mixed})
(def paths #{:serial :parallel})
(def statuses #{:completed :failed-before-completion})

(defn root [observation]
  (ref/sha256-ref
   (hc/domain-hash :claimant-execution-observation
                   (dissoc observation :execution-observation/root))))

(defn validate [observation]
  (let [effective (:execution-observation/effective observation)
        completion (:execution-observation/completion observation)
        keys* (set (keys observation))
        errors (cond-> []
                 (not= #{:execution-observation/type :execution-observation/version
                         :execution-observation/runtime-profile-root
                         :execution-observation/effective
                         :execution-observation/completion
                         :execution-observation/root} keys*)
                 (conj :invalid-observation-shape)
                 (not= observation-type (:execution-observation/type observation))
                 (conj :unsupported-observation-type)
                 (not= 1 (:execution-observation/version observation))
                 (conj :unsupported-observation-version)
                 (not (contains? paths (:execution/path effective)))
                 (conj :invalid-execution-path)
                 (not (contains? reasons (:execution/reason effective)))
                 (conj :invalid-execution-reason)
                 (not (integer? (:execution/max-claimant-parallelism effective)))
                 (conj :invalid-effective-parallelism)
                 (not (pos? (:execution/max-claimant-parallelism effective)))
                 (conj :invalid-effective-parallelism)
                 (not (boolean? (:execution/parallel-work-observed? effective)))
                 (conj :invalid-parallel-observation)
                 (not (boolean? (:execution/budget-limited? effective)))
                 (conj :invalid-budget-observation)
                 (not (contains? statuses (:status completion)))
                 (conj :invalid-completion-status)
                 (not= (:execution-observation/root observation) (root observation))
                 (conj :observation-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build [{:keys [runtime-profile-root effective completion]}]
  (let [observation {:execution-observation/type observation-type
                     :execution-observation/version 1
                     :execution-observation/runtime-profile-root runtime-profile-root
                     :execution-observation/effective effective
                     :execution-observation/completion completion}]
    (assoc observation :execution-observation/root (root observation))))

(defn verify-against-profile [runtime-profile observation]
  (let [local (:valid? (validate observation))
        profile-valid (:valid? (runtime-profile/validate runtime-profile))
        requested (:runtime-profile/requested runtime-profile)
        effective (get-in observation [:execution-observation/effective :execution/max-claimant-parallelism])
        path (get-in observation [:execution-observation/effective :execution/path])
        errors (cond-> []
                 (not local) (conj :invalid-observation)
                 (not profile-valid) (conj :invalid-runtime-profile)
                 (not= (:runtime-profile/root runtime-profile)
                       (:execution-observation/runtime-profile-root observation))
                 (conj :runtime-profile-root-mismatch)
                 (> effective (:execution/claimant-parallelism requested))
                 (conj :effective-parallelism-exceeds-requested)
                 (and (= path :serial) (not= 1 effective))
                 (conj :serial-parallelism-mismatch)
                 (and (= path :parallel) (not (> effective 1)))
                 (conj :parallel-parallelism-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn valid? [observation]
  (:valid? (validate observation)))
