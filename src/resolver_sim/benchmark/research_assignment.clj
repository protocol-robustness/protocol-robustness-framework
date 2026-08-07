(ns resolver-sim.benchmark.research-assignment
  "Immutable researcher assignment binding a review task to an exact public
   operation. It is intentionally distinct from the later researcher
   force-authorisation: the assignment supplies the authentic request root and
   scope that decisions must approve."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-assignment.v1")

(def ^:const target-required-fields
  #{:target/kind :target/public-force-authorisation-scope-hash
    :target/workflow-id :target/reason})

(defn- valid-root?
  [value]
  (hash-ref/valid-sha256-ref? value))

(defn- assignment-preimage
  [assignment]
  (dissoc assignment :research-assignment/hash))

(defn assignment-hash
  "Return the canonical domain-separated hash reference for an assignment."
  [assignment]
  (hash-ref/sha256-ref (hc/domain-hash :research-assignment
                                       (assignment-preimage assignment))))

(defn- errors-for
  [assignment]
  (let [target (:research-assignment/target assignment)
        required-roots [:research-assignment/environment-hash
                        :research-assignment/policy-hash
                        :research-assignment/review-round-hash
                        :research-assignment/request-root
                        :research-assignment/command-root
                        :research-assignment/plan-root]]
    (cond-> []
      (not= schema-version (:schema-version assignment))
      (conj :unsupported-schema-version)
      (not (keyword? (:research-assignment/id assignment)))
      (conj :invalid-assignment-id)
      (not (map? target))
      (conj :invalid-target)
      (and (map? target)
           (some #(not (contains? target %)) target-required-fields))
      (conj :incomplete-target)
      (and (map? target)
           (not (valid-root? (:target/public-force-authorisation-scope-hash target))))
      (conj :invalid-target-scope-hash)
      (some #(not (valid-root? (get assignment %))) required-roots)
      (conj :invalid-root-reference)
      (and (contains? assignment :research-assignment/hash)
           (not= (:research-assignment/hash assignment) (assignment-hash assignment)))
      (conj :assignment-hash-mismatch))))

(defn build-assignment
  "Build a hash-bound research-assignment.v1. All roots are canonical SHA-256
   references; assignment issuance is a provenance event, not an approval.
   Rejects a supplied hash that does not equal the canonical preimage hash."
  [fields]
  (let [assignment (assoc fields :schema-version schema-version)
        errors (errors-for assignment)]
    (when (seq (disj (set errors) :assignment-hash-mismatch))
      (throw (ex-info "Research assignment build failed" {:errors errors})))
    (let [computed (assignment-hash assignment)]
      (when (and (:research-assignment/hash fields)
                 (not= (:research-assignment/hash fields) computed))
        (throw (ex-info "Research assignment hash mismatch"
                        {:declared (:research-assignment/hash fields)
                         :computed computed})))
      (assoc assignment :research-assignment/hash computed))))

(defn validate-assignment
  "Validate assignment structure, canonical root references, and self hash."
  [assignment]
  (let [errors (errors-for assignment)]
    {:valid? (empty? errors) :errors errors}))

(defn assignment-valid?
  [assignment]
  (:valid? (validate-assignment assignment)))

(defn assignment-matches-authorisation?
  "Check the immutable assignment/request and target scope against a resolved
   researcher force-authorisation. Does not verify signatures or policy."
  [assignment authorisation]
  (and (assignment-valid? assignment)
       (= (:research-assignment/request-root assignment)
          (:authorisation/request-root authorisation))
       (= (get-in assignment [:research-assignment/target
                              :target/public-force-authorisation-scope-hash])
          (get-in authorisation [:authorisation/target
                                 :target/public-force-authorisation-scope-hash]))))
