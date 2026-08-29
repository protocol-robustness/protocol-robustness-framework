(ns resolver-sim.benchmark.governed-authority-authorisation
  "Closed production input for governed-authority evaluation. Legacy raw maps
  remain replay-only through legacy-authorisation-input-capture."
  (:require [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "governed-authority-authorisation.v1")
(def ^:const domain "GOVERNED_AUTHORITY_AUTHORISATION_V1")

(def fields
  #{:artifact/schema :authorisation/id :authorisation/request-root
    :authorisation/review-round :authorisation/target
    :authorisation/decision-references})

(def review-round-fields #{:review-round/id :review-round/hash})
(def target-fields rfa/target-required-fields)
(def signature-fields rfa/signature-fields)
(def decision-v1-fields
  #{:researcher/id :authorisation/request-root :review-round/hash
    :decision :decision/hash :signature})
(def decision-v2-fields
  #{:schema-version :researcher/id :authorisation/id
    :authorisation/request-root :review-round/hash :outcome/root
    :decision :decision/hash :signature})

(defn authorisation-root [authorisation]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc authorisation :governed-authority-authorisation/root)))))

(defn- nonblank-string? [value]
  (and (string? value) (not-empty value)))

(defn- exact-fields? [value required optional]
  (and (map? value)
       (every? #(contains? value %) required)
       (every? #(contains? (into required optional) %) (keys value))))

(defn- valid-signature? [signature]
  (and (exact-fields? signature signature-fields #{})
       (= :ed25519 (:algorithm signature))
       (nonblank-string? (:value signature))
       (nonblank-string? (:signed-at signature))))

(defn- valid-decision-shape? [decision]
  (let [version (rfa/classify-decision-version decision)
        dissent? (= :dissent (:decision decision))
        optional (case version
                   :v1-legacy #{:schema-version :dissent/reason}
                   :v2-complete-outcome #{:dissent/reason :signing-key/id}
                   #{})
        required (case version
                   :v1-legacy decision-v1-fields
                   :v2-complete-outcome decision-v2-fields
                   #{})]
    (and (contains? #{:v1-legacy :v2-complete-outcome} version)
         (exact-fields? decision required optional)
         (or (not= version :v1-legacy)
             (not (contains? decision :schema-version))
             (= rfa/decision-schema-version (:schema-version decision)))
         (or (not dissent?) (nonblank-string? (:dissent/reason decision)))
         (or dissent? (not (contains? decision :dissent/reason)))
         (nonblank-string? (:researcher/id decision))
         (rfa/valid-decision? (:decision decision))
         (ref/valid-sha256-ref? (:authorisation/request-root decision))
         (ref/valid-sha256-ref? (:review-round/hash decision))
         (ref/valid-sha256-ref? (:decision/hash decision))
         (or (not= version :v2-complete-outcome)
             (and (= rfa/decision-v2-schema-version (:schema-version decision))
                  (ref/valid-sha256-ref? (:outcome/root decision))))
         (valid-signature? (:signature decision)))))

(defn- decision-errors [authorisation decision]
  (cond-> []
    (not (valid-decision-shape? decision))
    (conj "decision has an invalid V1/V2 shape")

    (and (valid-decision-shape? decision)
         (not= (:authorisation/request-root authorisation)
               (:authorisation/request-root decision)))
    (conj "decision request-root does not match authorisation")

    (and (valid-decision-shape? decision)
         (not= (get-in authorisation [:authorisation/review-round :review-round/hash])
               (:review-round/hash decision)))
    (conj "decision review-round hash does not match authorisation")

    (and (= :v2-complete-outcome (rfa/classify-decision-version decision))
         (not= (:authorisation/id authorisation) (:authorisation/id decision)))
    (conj "V2 decision authorisation id does not match authorisation")

    (and (valid-decision-shape? decision)
         (not (rfa/decision-hash-valid? decision (:authorisation/id authorisation))))
    (conj "decision hash mismatch")))

(defn validate-authorisation [authorisation]
  (let [decisions (:authorisation/decision-references authorisation)
        roots [(:authorisation/request-root authorisation)
               (get-in authorisation [:authorisation/review-round :review-round/hash])
               (get-in authorisation [:authorisation/target :target/baseline-content-root])
               (get-in authorisation [:authorisation/target :target/branch-descriptor-hash])
               (get-in authorisation [:authorisation/target :target/proposed-content-root])]
        errors (cond-> []
                 (not (map? authorisation)) (conj "authorisation must be a map")
                 (and (map? authorisation) (not= schema (:artifact/schema authorisation)))
                 (conj "invalid authorisation schema")
                 (and (map? authorisation)
                      (not= (conj fields :governed-authority-authorisation/root)
                            (set (keys authorisation))))
                 (conj "authorisation has missing or unknown keys")
                 (not (some? (:authorisation/id authorisation)))
                 (conj "missing authorisation id")
                 (not (exact-fields? (:authorisation/review-round authorisation)
                                     review-round-fields #{}))
                 (conj "review-round has invalid shape")
                 (not= (get-in authorisation [:authorisation/review-round :review-round/id])
                       (get-in authorisation [:authorisation/review-round :review-round/hash]))
                 (conj "review-round id must equal review-round hash")
                 (not (and (exact-fields? (:authorisation/target authorisation) target-fields #{})
                           (rfa/valid-target-kind?
                            (get-in authorisation [:authorisation/target :target/kind]))))
                 (conj "target has invalid shape")
                 (not (every? ref/valid-sha256-ref? roots))
                 (conj "authorisation contains an invalid semantic root")
                 (not (vector? decisions))
                 (conj "decision references must be a vector")
                 (and (vector? decisions) (empty? decisions))
                 (conj "decision references must not be empty")
                 (and (vector? decisions)
                      (not= (count decisions) (count (set (map :researcher/id decisions)))))
                 (conj "decision references contain duplicate researcher ids")
                 (and (vector? decisions)
                      (not= (:governed-authority-authorisation/root authorisation)
                            (authorisation-root authorisation)))
                 (conj "authorisation root mismatch"))]
    {:valid? (and (empty? errors)
                  (vector? decisions)
                  (every? #(empty? (decision-errors authorisation %)) decisions))
     :errors (vec (concat errors
                          (mapcat #(decision-errors authorisation %) (or decisions []))))}))

(defn build-authorisation [authorisation]
  (let [candidate (assoc authorisation :artifact/schema schema)
        result (assoc candidate :governed-authority-authorisation/root
                      (authorisation-root candidate))
        validation (validate-authorisation result)]
    (when-not (:valid? validation)
      (throw (ex-info "governed-authority authorisation is invalid" validation)))
    result))
