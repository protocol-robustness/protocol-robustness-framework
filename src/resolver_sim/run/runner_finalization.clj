(ns resolver-sim.run.runner-finalization
  "Immutable runner-finalization artifacts.

   The artifact binds a resolved runner selection and the local runtime identity
   to the completed execution projection. It is deliberately separate from the
   evidence finalization so neither object is mutated after hashing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.run.criteria :as criteria]))

(def schema-version "runner-finalization.v1")

(defn local-runtime-identity []
  {:runtime/kind :runner-local
   :runtime/clojure-version (clojure-version)
   :runtime/java-version (System/getProperty "java.version")
   :runtime/java-vendor (System/getProperty "java.vendor")
   :runtime/os-name (System/getProperty "os.name")})

(defn build
  "Construct an immutable runner-finalization. `execution-result` must be a
   replay-result projection only; it must not contain worlds or traces."
  [{:keys [run-id runner-selection source-provenance execution-result]}]
  (when-not (criteria/valid-runner-selection? runner-selection)
    (throw (ex-info "Runner finalization requires a valid runner selection"
                    {:runner-selection runner-selection})))
  (when-not (and (string? run-id) (seq run-id))
    (throw (ex-info "Runner finalization requires a run id" {:run-id run-id})))
  (let [base {:runner-finalization/schema-version schema-version
              :run/id run-id
              :runner/selection (select-keys runner-selection [:mode :runner-id])
              :runner/local (local-runtime-identity)
              :runner/implementation-hash (:source/hash source-provenance)
              :execution/result execution-result}
        hash (hc/hash-with-intent {:hash/intent :runner-finalization} base)]
    (assoc base :runner-finalization/hash hash)))

(defn valid? [artifact]
  (let [base (dissoc artifact :runner-finalization/hash)
        expected (hc/hash-with-intent {:hash/intent :runner-finalization} base)
        errors (cond-> []
                 (not= schema-version (:runner-finalization/schema-version artifact))
                 (conj :unsupported-schema-version)
                 (not (criteria/valid-runner-selection? (:runner/selection artifact)))
                 (conj :invalid-runner-selection)
                 (not= :runner-local (get-in artifact [:runner/local :runtime/kind]))
                 (conj :not-local-runner)
                 (not= expected (:runner-finalization/hash artifact))
                 (conj :hash-mismatch))]
    {:valid? (empty? errors) :errors errors :expected-hash expected}))

(defn runnable?
  "A runner finalization is runnable when its immutable identity validates and
   it commits a completed or aborted execution with a non-nil result hash."
  [artifact]
  (let [validation (valid? artifact)
        termination (get-in artifact [:execution/result :execution/termination])
        bundle-hash (get-in artifact [:execution/result :bundle/root-hash])
        errors (cond-> (:errors validation)
                 (not (contains? #{:completed :aborted} termination))
                 (conj :invalid-execution-termination)
                 (not (string? bundle-hash))
                 (conj :missing-bundle-root-hash))]
    {:runnable? (empty? errors) :errors errors}))

(defn- json-key [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn write! [path artifact]
  (let [validation (valid? artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid runner finalization" validation)))
    (io/make-parents path)
    (spit path (json/write-str artifact :key-fn json-key :indent true))
    {:path path :finalization artifact :validation validation}))
