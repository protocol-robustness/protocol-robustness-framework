(ns resolver-sim.research.dependency-assurance
  "Canonical declaration, policy, and resolution of research dependencies.

   Resolution is identity/provenance binding only. This namespace downloads no
   artifact, authenticates no principal, and performs no confidential
   execution or attestation."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const manifest-schema "research-dependency-manifest.v1")
(def ^:const policy-schema "research-dependency-policy.v1")
(def ^:const resolved-schema "resolved-research-dependency-set.v1")

(def dependency-classes
  #{:prf-framework :prf-extension :registry-snapshot :clojure-model
    :scenario-corpus :solidity-source-build :deployed-evm-contract
    :anvil-foundry :proof-program-verifier :runtime-toolchain
    :local-ai-model :dataset-fixture :hermetic-environment})

(def visibility-classes
  #{:public :runner-readable-private :sealed-to-execution-environment})

(def acquisition-modes
  #{:public-by-root :authenticated-private :attestation-gated-sealed})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn- canonical-roots? [values]
  (and (vector? values) (= values (vec (sort values)))
       (= (count values) (count (set values))) (every? root? values)))

(defn- private-material-key? [key]
  (let [name* (name key)]
    (or (str/includes? name* "plaintext")
        (str/includes? name* "public-uri")
        (str/includes? name* "download-uri"))))

(defn valid-dependency? [dependency]
  (and (map? dependency)
       (= #{:dependency/id :dependency/class :dependency/visibility
            :dependency/acquisition-mode :dependency/artifact-root
            :dependency/provenance-roots :dependency/metadata}
          (set (keys dependency)))
       (qualified-keyword? (:dependency/id dependency))
       (contains? dependency-classes (:dependency/class dependency))
       (contains? visibility-classes (:dependency/visibility dependency))
       (contains? acquisition-modes (:dependency/acquisition-mode dependency))
       (= (case (:dependency/visibility dependency)
            :public :public-by-root
            :runner-readable-private :authenticated-private
            :sealed-to-execution-environment :attestation-gated-sealed)
          (:dependency/acquisition-mode dependency))
       (root? (:dependency/artifact-root dependency))
       (canonical-roots? (:dependency/provenance-roots dependency))
       (map? (:dependency/metadata dependency))
       (or (= :public (:dependency/visibility dependency))
           (not-any? private-material-key? (keys (:dependency/metadata dependency))))))

(defn manifest-root [manifest]
  (root :research-dependency-manifest-v1
        (dissoc manifest :research-dependency-manifest/root)))

(defn validate-manifest [manifest]
  (let [dependencies (:dependencies manifest)
        valid? (and (map? manifest)
                    (= manifest-schema (:artifact/schema manifest))
                    (= #{:artifact/schema :dependencies
                         :research-dependency-manifest/root}
                       (set (keys manifest)))
                    (vector? dependencies)
                    (seq dependencies)
                    (= dependencies (vec (sort-by :dependency/id dependencies)))
                    (= (count dependencies)
                       (count (set (map :dependency/id dependencies))))
                    (every? valid-dependency? dependencies)
                    (= (:research-dependency-manifest/root manifest)
                       (try (manifest-root manifest) (catch Exception _ nil))))]
    {:valid? (boolean valid?)
     :errors (if valid? [] [:research-dependency-manifest/invalid])}))

(defn build-manifest [dependencies]
  (let [body {:artifact/schema manifest-schema
              :dependencies (vec (sort-by :dependency/id dependencies))}
        rooted (assoc body :research-dependency-manifest/root (manifest-root body))
        validation (validate-manifest rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid research dependency manifest" validation)))
    rooted))

(defn policy-root [policy]
  (root :research-dependency-policy-v1
        (dissoc policy :research-dependency-policy/root)))

(defn validate-policy [policy]
  (let [valid? (and (map? policy)
                    (= policy-schema (:artifact/schema policy))
                    (= #{:artifact/schema :dependency-policy/required-classes
                         :dependency-policy/allowed-visibility
                         :dependency-policy/allowed-acquisition-modes
                         :dependency-policy/sealed-environment-root
                         :research-dependency-policy/root}
                       (set (keys policy)))
                    (set/subset? (set (:dependency-policy/required-classes policy))
                                 dependency-classes)
                    (= (:dependency-policy/required-classes policy)
                       (vec (sort (:dependency-policy/required-classes policy))))
                    (set/subset? (set (:dependency-policy/allowed-visibility policy))
                                 visibility-classes)
                    (= (:dependency-policy/allowed-visibility policy)
                       (vec (sort (:dependency-policy/allowed-visibility policy))))
                    (set/subset? (set (:dependency-policy/allowed-acquisition-modes policy))
                                 acquisition-modes)
                    (= (:dependency-policy/allowed-acquisition-modes policy)
                       (vec (sort (:dependency-policy/allowed-acquisition-modes policy))))
                    (or (nil? (:dependency-policy/sealed-environment-root policy))
                        (root? (:dependency-policy/sealed-environment-root policy)))
                    (= (:research-dependency-policy/root policy)
                       (try (policy-root policy) (catch Exception _ nil))))]
    {:valid? (boolean valid?) :errors (if valid? [] [:research-dependency-policy/invalid])}))

(defn build-policy
  [{:keys [required-classes allowed-visibility allowed-acquisition-modes
           sealed-environment-root]}]
  (let [body {:artifact/schema policy-schema
              :dependency-policy/required-classes (vec (sort required-classes))
              :dependency-policy/allowed-visibility (vec (sort allowed-visibility))
              :dependency-policy/allowed-acquisition-modes
              (vec (sort allowed-acquisition-modes))
              :dependency-policy/sealed-environment-root sealed-environment-root}
        rooted (assoc body :research-dependency-policy/root (policy-root body))
        validation (validate-policy rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid research dependency policy" validation)))
    rooted))

(defn resolved-root [resolved]
  (root :resolved-research-dependency-set-v1
        (dissoc resolved :resolved-research-dependency-set/root)))

(defn- resolution-valid-for-dependency? [policy dependency resolution]
  (and (= (:dependency/id dependency) (:dependency/id resolution))
       (= (:dependency/artifact-root dependency) (:dependency/artifact-root resolution))
       (= (:dependency/acquisition-mode dependency) (:resolution/acquisition-mode resolution))
       (= :resolved (:resolution/status resolution))
       (root? (:resolution/evidence-root resolution))
       (if (= :sealed-to-execution-environment (:dependency/visibility dependency))
         (and (= :attestation-gated-sealed (:resolution/acquisition-mode resolution))
              (root? (:resolution/environment-root resolution))
              (= (:dependency-policy/sealed-environment-root policy)
                 (:resolution/environment-root resolution)))
         (nil? (:resolution/environment-root resolution)))))

(defn resolve-dependencies
  "Resolve an immutable manifest from explicit acquisition evidence.

   Sealed resolution is declaration-only: an evidence root and exact target
   environment are bound, but no attestation or key release occurs here."
  [manifest policy resolutions]
  (when-not (and (:valid? (validate-manifest manifest))
                 (:valid? (validate-policy policy)))
    (throw (ex-info "Invalid dependency manifest or policy" {})))
  (let [dependencies (:dependencies manifest)
        by-id (into {} (map (juxt :dependency/id identity) resolutions))
        classes (set (map :dependency/class dependencies))
        allowed-visibility (set (:dependency-policy/allowed-visibility policy))
        allowed-modes (set (:dependency-policy/allowed-acquisition-modes policy))]
    (when-not (and (= (set (map :dependency/id dependencies)) (set (keys by-id)))
                   (set/subset? (set (:dependency-policy/required-classes policy)) classes)
                   (every? #(contains? allowed-visibility (:dependency/visibility %)) dependencies)
                   (every? #(contains? allowed-modes (:dependency/acquisition-mode %)) dependencies)
                   (every? (fn [dependency]
                             (resolution-valid-for-dependency?
                              policy dependency (get by-id (:dependency/id dependency))))
                           dependencies))
      (throw (ex-info "Dependency resolution does not satisfy its manifest and policy" {})))
    (let [body {:artifact/schema resolved-schema
                :research-dependency-manifest/root
                (:research-dependency-manifest/root manifest)
                :research-dependency-policy/root
                (:research-dependency-policy/root policy)
                :resolved/dependencies (vec (sort-by :dependency/id resolutions))
                :resolution/assurance :declared-acquisition-evidence}
          rooted (assoc body :resolved-research-dependency-set/root
                        (resolved-root body))]
      rooted)))

(defn valid-resolved-set? [resolved]
  (and (map? resolved)
       (= resolved-schema (:artifact/schema resolved))
       (= :declared-acquisition-evidence (:resolution/assurance resolved))
       (root? (:research-dependency-manifest/root resolved))
       (root? (:research-dependency-policy/root resolved))
       (vector? (:resolved/dependencies resolved))
       (= (:resolved/dependencies resolved)
          (vec (sort-by :dependency/id (:resolved/dependencies resolved))))
       (= (:resolved-research-dependency-set/root resolved)
          (try (resolved-root resolved) (catch Exception _ nil)))))
