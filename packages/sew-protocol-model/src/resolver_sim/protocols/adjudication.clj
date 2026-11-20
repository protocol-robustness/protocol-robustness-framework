(ns resolver-sim.protocols.adjudication
  "Protocol-neutral rooted adjudication context and ruling admission.

   This namespace validates identity and adapter attestations only. It does not
   select an outcome or implement any protocol's settlement transition."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]
            [resolver-sim.protocols.adjudication-authentication-verification :as authentication-verification]))

(def ^:const basis-schema "prf/adjudication-basis.v1")
(def ^:const basis-domain :prf-adjudication-basis-v1)
(def authority-modes #{:primary :fallback})

(def ^:private basis-root-key :adjudication-basis/root)
(def ^:private basis-required-fields
  #{:artifact/schema
    :adjudication/subject-root
    :adjudication/state-root
    :adjudication/question-root
    :adjudication/evidence-set-root
    :adjudication/authority-root
    :adjudication/adjudicator-root
    :adjudication/policy-root
    :adjudication/authority-mode
    :adjudication/protocol-basis-root})
(def ^:private basis-reference-fields
  (disj basis-required-fields :artifact/schema :adjudication/authority-mode))

(defn adjudication-basis-payload [basis]
  (dissoc basis basis-root-key))

(defn adjudication-basis-errors [basis]
  (if-not (map? basis)
    [:invalid-adjudication-basis]
    (let [have (set (keys basis))
          missing (set/difference basis-required-fields have)
          extra (set/difference have (conj basis-required-fields basis-root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-adjudication-basis-field)
        (not= basis-schema (:artifact/schema basis)) (conj :invalid-schema)
        (not (contains? authority-modes (:adjudication/authority-mode basis)))
        (conj :invalid-authority-mode)
        (not (every? #(ref/valid-sha256-ref? (get basis %)) basis-reference-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get basis basis-root-key)))
        (conj :invalid-adjudication-basis-root)
        (and (empty? missing) (empty? extra)
             (every? #(ref/valid-sha256-ref? (get basis %)) basis-reference-fields)
             (ref/valid-sha256-ref? (get basis basis-root-key))
             (not= (get basis basis-root-key)
                   (ref/sha256-ref
                    (hc/domain-hash basis-domain
                                    (hc/project-canonical-safe
                                     (adjudication-basis-payload basis))))))
        (conj :adjudication-basis-root-mismatch)))))

(defn validate-adjudication-basis [basis]
  (let [errors (adjudication-basis-errors basis)]
    {:valid? (empty? errors) :errors errors}))

(defn build-adjudication-basis [basis]
  (let [base (assoc basis :artifact/schema basis-schema)
        artifact (assoc base basis-root-key
                        (ref/sha256-ref
                         (hc/domain-hash basis-domain
                                         (hc/project-canonical-safe
                                          (adjudication-basis-payload base)))))
        validation (validate-adjudication-basis artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid prf/adjudication-basis.v1"
                      {:type :prf-adjudication-basis/invalid
                       :errors (:errors validation)})))
    artifact))

(defn verify-ruling-against-basis
  "Pure generic ruling admission. The configured provider adapter receives the
   exact ruling and returns a canonical authentication-verification artifact only
   after verifying its provider-specific evidence. Generic PRF checks canonical
   integrity, admitted method, affirmative result, exact ruling, authoritative
   instance, and ruling authentication commitment. The adapter establishes
   credential, external-subject, and provider-evidence semantics. A valid artifact
   alone is not authoritative because it must arrive through this adapter boundary."
  [adjudication-basis dispute-ruling adjudication-instance-root adjudicator-verifier
   & {:keys [require-final-status?] :or {require-final-status? true}}]
  (let [basis-validation (validate-adjudication-basis adjudication-basis)
        ruling-validation (ruling/validate-dispute-ruling dispute-ruling)
        adapter-output (when (and (:valid? basis-validation)
                                  (:valid? ruling-validation)
                                  adjudicator-verifier)
                         (try
                           (adjudicator-verifier adjudication-basis dispute-ruling)
                           (catch Exception _ nil)))
        verification (:authentication-verification adapter-output)
        verification-validation
        (authentication-verification/validate-authentication-verification verification)
        verification-errors (set (:errors verification-validation))
        errors (cond-> (vec (concat (:errors basis-validation)
                                    (:errors ruling-validation)))
                 (and (:valid? basis-validation)
                      (:valid? ruling-validation)
                      (nil? adjudicator-verifier))
                 (conj :missing-ruling-verifier)
                 (and (:valid? basis-validation)
                      (:valid? ruling-validation)
                      adjudicator-verifier
                      (nil? adapter-output))
                 (conj :provider-verification-failed)
                 (and (:valid? basis-validation)
                      (:valid? ruling-validation)
                      adapter-output
                      (nil? verification))
                 (conj :authentication-verification-missing)
                 (and verification
                      (not (:valid? verification-validation)))
                 (conj (cond
                         (contains? verification-errors :invalid-authentication-method)
                         :unsupported-authentication-method
                         :else :invalid-authentication-verification))
                 (and (:valid? basis-validation)
                      (:valid? ruling-validation)
                      (not= (:adjudication-basis/root adjudication-basis)
                            (:ruling/basis-root dispute-ruling)))
                 (conj :ruling-basis-mismatch)
                 (and (:valid? verification-validation)
                      (not= (:ruling/root dispute-ruling)
                            (:authentication-verification/ruling-root verification)))
                 (conj :authentication-verification-ruling-mismatch)
                 (and (:valid? verification-validation)
                      (not= (:ruling/authentication-root dispute-ruling)
                            (:authentication-verification/authentication-root verification)))
                 (conj :authentication-verification-authentication-mismatch)
                 (and (:valid? verification-validation)
                      (not= adjudication-instance-root
                            (:authentication-verification/adjudication-instance-root verification)))
                 (conj :authentication-verification-instance-mismatch)
                 (and (:valid? verification-validation)
                      (not= :authenticated (:authentication-verification/result verification)))
                 (conj :authentication-not-affirmative)
                  (and require-final-status?
                       (:valid? ruling-validation)
                       (not= :final (:ruling/status dispute-ruling)))
                 (conj :ruling-not-final))]
    (if (empty? errors)
      {:valid? true
       :basis-root (:adjudication-basis/root adjudication-basis)
       :ruling-root (:ruling/root dispute-ruling)
        :authentication-verification-root (:authentication-verification/root verification)
        :authentication-verification verification
        :authentication-root (:ruling/authentication-root dispute-ruling)
       :adjudicator-root (:adjudication/adjudicator-root adjudication-basis)
       :outcome (:ruling/outcome dispute-ruling)
       :status (:ruling/status dispute-ruling)}
      {:valid? false :errors errors})))
