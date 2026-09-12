(ns resolver-sim.protocols.sew.dispute-ruling
  "Canonical external-adjudicator ruling artifact.

   This artifact records the ruling SEW is asked to enforce. Authentication is
   deliberately verified by a configured adjudicator adapter, not inferred from
   the artifact's self-root."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "prf/dispute-ruling.v1")
(def ^:const domain :prf-dispute-ruling-v1)
(def providers #{:kleros :governed :centralized})
(def outcomes #{:release :refund :refuse})
(def statuses #{:appealable :final})

(def ^:private root-key :ruling/root)
(def ^:private required-fields
  #{:artifact/schema :ruling/basis-root :ruling/dispute-root :ruling/authority-root
    :ruling/provider :ruling/external-dispute-id :ruling/external-ruling-id
    :ruling/round :ruling/outcome :ruling/status :ruling/evidence-root
    :ruling/policy-root :ruling/issued-at :ruling/authentication-root})
(def ^:private reference-fields
  #{:ruling/basis-root :ruling/dispute-root :ruling/authority-root
    :ruling/evidence-root :ruling/policy-root :ruling/authentication-root})

(defn ruling-payload [ruling]
  (dissoc ruling root-key))

(defn ruling-errors [ruling]
  (if-not (map? ruling)
    [:invalid-ruling]
    (let [have (set (keys ruling))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-ruling-field)
        (not= schema (:artifact/schema ruling)) (conj :invalid-schema)
        (not (contains? providers (:ruling/provider ruling))) (conj :invalid-provider)
        (not (and (string? (:ruling/external-dispute-id ruling))
                  (seq (:ruling/external-dispute-id ruling)))) (conj :invalid-external-dispute-id)
        (not (and (string? (:ruling/external-ruling-id ruling))
                  (seq (:ruling/external-ruling-id ruling)))) (conj :invalid-external-ruling-id)
        (not (and (integer? (:ruling/round ruling))
                  (not (neg? (:ruling/round ruling))))) (conj :invalid-round)
        (not (contains? outcomes (:ruling/outcome ruling))) (conj :invalid-outcome)
        (not (contains? statuses (:ruling/status ruling))) (conj :invalid-status)
        (not (and (integer? (:ruling/issued-at ruling))
                  (not (neg? (:ruling/issued-at ruling))))) (conj :invalid-issued-at)
        (not (every? #(ref/valid-sha256-ref? (get ruling %)) reference-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get ruling root-key))) (conj :invalid-ruling-root)
        (and (empty? missing)
             (empty? extra)
             (every? #(ref/valid-sha256-ref? (get ruling %)) reference-fields)
             (ref/valid-sha256-ref? (get ruling root-key))
             (not= (get ruling root-key)
                   (ref/sha256-ref (hc/domain-hash domain
                                                   (hc/project-canonical-safe
                                                    (ruling-payload ruling))))))
        (conj :ruling-root-mismatch)))))

(defn validate-dispute-ruling [ruling]
  (let [errors (ruling-errors ruling)]
    {:valid? (empty? errors) :errors errors}))

(defn valid-dispute-ruling? [ruling]
  (:valid? (validate-dispute-ruling ruling)))

(defn build-dispute-ruling [ruling]
  (let [base (assoc ruling :artifact/schema schema)
        artifact (assoc base root-key
                        (ref/sha256-ref
                         (hc/domain-hash domain
                                         (hc/project-canonical-safe
                                          (ruling-payload base)))))
        validation (validate-dispute-ruling artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid prf/dispute-ruling.v1"
                      {:type :prf-dispute-ruling/invalid
                       :errors (:errors validation)})))
    artifact))
