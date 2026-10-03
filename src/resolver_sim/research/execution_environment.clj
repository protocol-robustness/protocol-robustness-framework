(ns resolver-sim.research.execution-environment
  "Canonical execution-environment declarations for research jobs.

   Version 1 freezes the identity inputs needed by a future Anvil executor. It
   is a provenance descriptor, not evidence that a real process was isolated,
   replayed, or cryptographically verified."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "research-execution-environment.v1")
(def supported-kinds #{:anvil})
(def ^:private fields
  #{:artifact/schema :execution-environment/kind :environment/version
    :tool/identity :evm/chain-id :state/source :deployment/code-identities
    :environment/initial-state-root :environment/config :forensic/assurance
    :research-execution-environment/root})

(defn environment-root [environment]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-execution-environment-v1
                   (dissoc environment :research-execution-environment/root))))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn- valid-state-source? [source]
  (and (map? source)
       (case (:state-source/kind source)
         :genesis (= #{:state-source/kind :state-source/root} (set (keys source)))
         :fork (= #{:state-source/kind :state-source/root :fork/block-number}
                  (set (keys source)))
         false)
       (root? (:state-source/root source))
       (or (not= :fork (:state-source/kind source))
           (and (integer? (:fork/block-number source))
                (not (neg? (:fork/block-number source)))))))

(defn- valid-code-identity? [entry]
  (and (map? entry)
       (= #{:deployment/address :deployment/code-root} (set (keys entry)))
       (string? (:deployment/address entry))
       (root? (:deployment/code-root entry))))

(defn- validate-anvil-environment [environment]
  (let [errors
        (cond-> []
          (not (map? environment)) (conj :execution-environment/not-a-map)
          (and (map? environment) (not= schema (:artifact/schema environment)))
          (conj :execution-environment/unsupported-schema)
          (and (map? environment) (not= fields (set (keys environment))))
          (conj :execution-environment/invalid-fields)
          (not (contains? supported-kinds (:execution-environment/kind environment)))
          (conj :execution-environment/unsupported-kind)
          (not= 1 (:environment/version environment))
          (conj :execution-environment/unsupported-version)
          (not (and (= :anvil (get-in environment [:tool/identity :tool/name]))
                    (= #{:tool/name :tool/version}
                       (set (keys (:tool/identity environment))))
                    (string? (get-in environment [:tool/identity :tool/version]))
                    (seq (get-in environment [:tool/identity :tool/version]))))
          (conj :execution-environment/invalid-tool-identity)
          (not (pos-int? (:evm/chain-id environment)))
          (conj :execution-environment/invalid-chain-id)
          (not (valid-state-source? (:state/source environment)))
          (conj :execution-environment/invalid-state-source)
          (not (root? (:environment/initial-state-root environment)))
          (conj :execution-environment/invalid-initial-state-root)
          (not (and (vector? (:deployment/code-identities environment))
                    (= (:deployment/code-identities environment)
                       (vec (sort-by :deployment/address
                                     (:deployment/code-identities environment))))
                    (= (count (:deployment/code-identities environment))
                       (count (set (map :deployment/address
                                        (:deployment/code-identities environment)))))
                    (every? valid-code-identity?
                            (:deployment/code-identities environment))))
          (conj :execution-environment/invalid-code-identities)
          (not (map? (:environment/config environment)))
          (conj :execution-environment/invalid-config)
          (not= :declaration-only (:forensic/assurance environment))
          (conj :execution-environment/invalid-assurance)
          (and (map? environment)
               (not= (:research-execution-environment/root environment)
                     (try (environment-root environment) (catch Exception _ nil))))
          (conj :execution-environment/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(def native-schema "research-native-execution-environment.v1")
(defn native-environment-root [environment]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-native-execution-environment-v1
                   (dissoc environment :research-execution-environment/root))))

(defn validate-environment [environment]
  (if (= native-schema (:artifact/schema environment))
    (let [valid? (and (= #{:artifact/schema :execution-environment/kind :runtime/clojure-version
                          :runtime/jvm-version :environment/arithmetic :forensic/assurance
                          :research-execution-environment/root} (set (keys environment)))
                      (= :clojure-jvm (:execution-environment/kind environment))
                      (every? #(and (string? %) (seq %))
                              (map environment [:runtime/clojure-version :runtime/jvm-version]))
                      (= :clojure-checked-integer (:environment/arithmetic environment))
                      (= :declaration-only (:forensic/assurance environment))
                      (= (:research-execution-environment/root environment)
                         (try (native-environment-root environment) (catch Exception _ nil))))]
      {:valid? (boolean valid?) :errors (if valid? [] [:execution-environment/invalid-native-declaration])})
    (validate-anvil-environment environment)))

(defn build-native-environment
  ([] (build-native-environment {:clojure-version (clojure-version)
                                :jvm-version (System/getProperty "java.version")}))
  ([{:keys [clojure-version jvm-version]}]
   (let [body {:artifact/schema native-schema :execution-environment/kind :clojure-jvm
               :runtime/clojure-version clojure-version :runtime/jvm-version jvm-version
               :environment/arithmetic :clojure-checked-integer :forensic/assurance :declaration-only}
         result (assoc body :research-execution-environment/root (native-environment-root body))]
     (when-not (:valid? (validate-environment result))
       (throw (ex-info "Invalid native environment" {})))
     result)))

(defn build-anvil-environment
  [{:keys [tool-version chain-id state-source code-identities
           initial-state-root config]}]
  (let [body {:artifact/schema schema
              :execution-environment/kind :anvil
              :environment/version 1
              :tool/identity {:tool/name :anvil :tool/version tool-version}
              :evm/chain-id chain-id
              :state/source state-source
              :deployment/code-identities
              (vec (sort-by :deployment/address code-identities))
              :environment/initial-state-root initial-state-root
              :environment/config (or config {})
              :forensic/assurance :declaration-only}
        rooted (assoc body :research-execution-environment/root
                      (environment-root body))
        validation (validate-environment rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid Anvil research environment" validation)))
    rooted))
