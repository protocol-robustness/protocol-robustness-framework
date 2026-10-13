(ns resolver-sim.protocols.adjudication-authentication-verification
  "Canonical provider-adapter output for an authenticated adjudication ruling."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "prf/adjudication-authentication-verification.v1")
(def ^:const domain :prf-adjudication-authentication-verification-v1)
(def authentication-methods #{:ethereum-signature :multisig :kleros :signed-review-authority})
(def results #{:authenticated :failed})

(def ^:private root-key :authentication-verification/root)
(def ^:private required-fields
  #{:artifact/schema
    :authentication-verification/ruling-root
    :authentication-verification/authentication-root
    :authentication-verification/adjudication-instance-root
    :authentication-verification/issuer
    :authentication-verification/method
    :authentication-verification/credential-material-root
    :authentication-verification/external-subject
    :authentication-verification/provider-evidence-root
    :authentication-verification/observation-root
    :authentication-verification/observed-at
    :authentication-verification/result})
(def ^:private reference-fields
  #{:authentication-verification/ruling-root
    :authentication-verification/authentication-root
    :authentication-verification/adjudication-instance-root
    :authentication-verification/credential-material-root
    :authentication-verification/provider-evidence-root
    :authentication-verification/observation-root})

(defn authentication-verification-payload [verification]
  (dissoc verification root-key))

(defn authentication-verification-errors [verification]
  (if-not (map? verification)
    [:invalid-authentication-verification]
    (let [have (set (keys verification))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-authentication-verification-field)
        (not= schema (:artifact/schema verification)) (conj :invalid-schema)
        (not (contains? authentication-methods (:authentication-verification/method verification)))
        (conj :invalid-authentication-method)
        (not (contains? results (:authentication-verification/result verification)))
        (conj :invalid-authentication-result)
        (not (and (string? (:authentication-verification/issuer verification))
                  (seq (:authentication-verification/issuer verification))))
        (conj :invalid-authentication-issuer)
        (not (and (string? (:authentication-verification/external-subject verification))
                  (seq (:authentication-verification/external-subject verification))))
        (conj :invalid-external-subject)
        (and (string? (:authentication-verification/issuer verification))
             (string? (:authentication-verification/external-subject verification))
             (not= (:authentication-verification/issuer verification)
                   (:authentication-verification/external-subject verification)))
        (conj :external-subject-issuer-mismatch)
        (not (and (integer? (:authentication-verification/observed-at verification))
                  (not (neg? (:authentication-verification/observed-at verification)))))
        (conj :invalid-observed-at)
        (not (every? #(ref/valid-sha256-ref? (get verification %)) reference-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get verification root-key)))
        (conj :invalid-authentication-verification-root)
        (and (empty? missing)
             (empty? extra)
             (every? #(ref/valid-sha256-ref? (get verification %)) reference-fields)
             (ref/valid-sha256-ref? (get verification root-key))
             (not= (get verification root-key)
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (authentication-verification-payload verification))))))
        (conj :authentication-verification-root-mismatch)))))

(defn validate-authentication-verification [verification]
  (let [errors (authentication-verification-errors verification)]
    {:valid? (empty? errors) :errors errors}))

(defn valid-authentication-verification? [verification]
  (:valid? (validate-authentication-verification verification)))

(defn build-authentication-verification [verification]
  (let [base (assoc verification :artifact/schema schema)
        artifact (assoc base root-key
                        (ref/sha256-ref
                         (hc/domain-hash domain
                                         (hc/project-canonical-safe
                                          (authentication-verification-payload base)))))
        validation (validate-authentication-verification artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid prf/adjudication-authentication-verification.v1"
                      {:type :prf-adjudication-authentication-verification/invalid
                       :errors (:errors validation)})))
    artifact))
