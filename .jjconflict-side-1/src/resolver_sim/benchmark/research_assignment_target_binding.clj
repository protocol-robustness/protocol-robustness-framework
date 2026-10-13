(ns resolver-sim.benchmark.research-assignment-target-binding
  "Binds a generic research assignment to one exact Solidity assurance subject.

   The binding reuses deployment, release, and configuration identities already
   owned by their respective contracts. It introduces no target registry or
   Solidity configuration model."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-assignment-target-binding.v1")

(defn- canonical-root?
  [value]
  (or (hash-ref/valid-sha256-ref? value)
      (and (string? value) (re-matches #"[0-9a-f]{64}" value))))

(defn target-root
  "Derive the exact Solidity subject identity from existing target roots."
  [binding]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-assignment-solidity-target
                   (select-keys binding [:solidity-target/deployment-root
                                         :solidity-target/release-root
                                         :solidity-target/configuration-root]))))

(defn binding-root
  [binding]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-assignment-target-binding
                   (dissoc binding :research-assignment-target-binding/root))))

(defn validate-binding
  [binding]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version binding))
                 (conj :unsupported-binding-schema)
                 (not (hash-ref/valid-sha256-ref?
                       (:research-assignment-target-binding/assignment-root binding)))
                 (conj :invalid-assignment-root)
                 (not (canonical-root? (:solidity-target/deployment-root binding)))
                 (conj :invalid-deployment-root)
                 (not (canonical-root? (:solidity-target/release-root binding)))
                 (conj :invalid-release-root)
                 (not (hash-ref/valid-sha256-ref?
                       (:solidity-target/configuration-root binding)))
                 (conj :invalid-configuration-root)
                 (not= (:solidity-target/root binding) (target-root binding))
                 (conj :target-root-mismatch)
                 (not= (:research-assignment-target-binding/subject-root binding)
                       (:solidity-target/root binding))
                 (conj :subject-target-mismatch)
                 (and (:research-assignment-target-binding/root binding)
                      (not= (:research-assignment-target-binding/root binding)
                            (binding-root binding)))
                 (conj :binding-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-binding
  "Build an immutable assignment-to-exact-Solidity-subject binding.
   The research subject is always the derived target root; callers cannot
   select it independently."
  [{:keys [research-assignment-target-binding/assignment-root
           solidity-target/deployment-root
           solidity-target/release-root
           solidity-target/configuration-root]}]
  (let [base {:schema-version schema-version
              :research-assignment-target-binding/assignment-root assignment-root
              :solidity-target/deployment-root deployment-root
              :solidity-target/release-root release-root
              :solidity-target/configuration-root configuration-root}
        target (target-root base)
        binding (assoc base
                       :solidity-target/root target
                       :research-assignment-target-binding/subject-root target)
        validation (validate-binding binding)]
    (when-not (:valid? validation)
      (throw (ex-info "Research assignment target binding is invalid" validation)))
    (assoc binding :research-assignment-target-binding/root (binding-root binding))))
