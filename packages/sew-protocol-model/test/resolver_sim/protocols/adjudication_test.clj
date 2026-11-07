(ns resolver-sim.protocols.adjudication-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.adjudication :as adjudication]
            [resolver-sim.protocols.adjudication-authentication-verification :as authentication]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]
            [resolver-sim.protocols.sew.resolution-basis :as sew-basis]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- basis []
  (sew-basis/build-resolution-basis
   {:workflow/id 9
    :escrow/root (root :escrow)
    :state-before/root (root :state)
    :dispute/question-root (root :question)
    :dispute/root (root :dispute)
    :dispute/evidence-set-root (root :evidence-set)
    :authority/resolution-basis-root (root :governed-basis)
    :authority/context-root (root :authority)
    :resolver/root (root :resolver)
    :resolver/kind :external
    :resolver/authorization-root (root :authorization)
    :resolution-module/root (root :module)
    :resolution-module/snapshot-root (root :snapshot)
    :configuration/root (root :configuration)
    :resolution/authority-mode :primary}))

(defn- ruling-for [generic]
  (ruling/build-dispute-ruling
   {:ruling/basis-root (:adjudication-basis/root generic)
    :ruling/dispute-root (root :dispute)
    :ruling/authority-root (:adjudication/authority-root generic)
    :ruling/adjudication-instance-root (root :instance)
    :ruling/case-root (root :case)
    :ruling/provider :kleros
    :ruling/external-dispute-id "42"
    :ruling/external-ruling-id "7"
    :ruling/round 0
    :ruling/outcome :refund
    :ruling/status :final
    :ruling/evidence-root (root :evidence)
    :ruling/policy-root (:adjudication/policy-root generic)
    :ruling/issued-at 1000
    :ruling/authentication-root (root :authentication)}))

(deftest sew-projection-is-deterministic-and-outcome-free
  (let [sew (basis)
        generic (sew-basis/project-adjudication-basis sew)]
    (is (:valid? (adjudication/validate-adjudication-basis generic)))
    (is (= generic (sew-basis/project-adjudication-basis sew)))
    (is (= (:sew-resolution-basis/root sew)
           (:adjudication/protocol-basis-root generic)))
    (is (= (:escrow/root sew) (:adjudication/subject-root generic)))
    (is (= (:state-before/root sew) (:adjudication/state-root generic)))))

(deftest projection-commits-all-adjudicatively-relevant-sew-fields
  (let [sew (basis)
        generic (sew-basis/project-adjudication-basis sew)
        changed (sew-basis/build-resolution-basis
                 (assoc (dissoc sew :artifact/schema :resolution/purpose
                                :sew-resolution-basis/root)
                        :resolver/root (root :different-resolver)))
        changed-generic (sew-basis/project-adjudication-basis changed)]
    (is (not= (:adjudication/adjudicator-root generic)
              (:adjudication/adjudicator-root changed-generic)))
    (is (not= (:adjudication/protocol-basis-root generic)
              (:adjudication/protocol-basis-root changed-generic)))
    (is (not= generic changed-generic)
        "a changed SEW adjudicator binding cannot project to the same generic basis")))

(deftest generic-verifier-binds-ruling-to-authenticated-basis
  (let [generic (sew-basis/project-adjudication-basis (basis))
        instance-root (root :instance)
        artifact (ruling-for generic)
        verification (authentication/build-authentication-verification
                      {:authentication-verification/ruling-root (:ruling/root artifact)
                       :authentication-verification/authentication-root (:ruling/authentication-root artifact)
                       :authentication-verification/adjudication-instance-root instance-root
                       :authentication-verification/issuer "0xAdjudicator"
                       :authentication-verification/method :ethereum-signature
                       :authentication-verification/credential-material-root (root :credential)
                       :authentication-verification/external-subject "0xAdjudicator"
                       :authentication-verification/provider-evidence-root (root :provider-evidence)
                       :authentication-verification/observation-root (root :observation)
                       :authentication-verification/observed-at 1000
                       :authentication-verification/result :authenticated})
        verifier (fn [_ _] {:authentication-verification verification})]
    (is (:valid? (adjudication/verify-ruling-against-basis generic artifact instance-root verifier)))
    (is (not (:valid? (adjudication/verify-ruling-against-basis
                       generic artifact (root :other-instance) verifier))))
    (is (not (:valid? (adjudication/verify-ruling-against-basis
                       generic artifact instance-root nil))))))

(deftest authentication-verification-cannot-transfer-between-rulings
  (let [generic (sew-basis/project-adjudication-basis (basis))
        instance-root (root :instance)
        ruling-a (ruling-for generic)
        ruling-b (ruling/build-dispute-ruling
                  (assoc (dissoc ruling-a :ruling/root)
                         :ruling/external-ruling-id "8"))
        verification-a (authentication/build-authentication-verification
                        {:authentication-verification/ruling-root (:ruling/root ruling-a)
                         :authentication-verification/authentication-root (:ruling/authentication-root ruling-a)
                         :authentication-verification/adjudication-instance-root instance-root
                         :authentication-verification/issuer "0xAdjudicator"
                         :authentication-verification/method :ethereum-signature
                         :authentication-verification/credential-material-root (root :credential)
                         :authentication-verification/external-subject "0xAdjudicator"
                         :authentication-verification/provider-evidence-root (root :provider-evidence)
                         :authentication-verification/observation-root (root :observation)
                         :authentication-verification/observed-at 1000
                         :authentication-verification/result :authenticated})
        result (adjudication/verify-ruling-against-basis
                generic ruling-b instance-root
                (fn [_ _] {:authentication-verification verification-a}))]
    (is (ruling/valid-dispute-ruling? ruling-a))
    (is (ruling/valid-dispute-ruling? ruling-b))
    (is (not= (:ruling/root ruling-a) (:ruling/root ruling-b)))
    (is (authentication/valid-authentication-verification? verification-a))
    (is (contains? (set (:errors result)) :authentication-verification-ruling-mismatch))))

(deftest generic-verifier-fails-closed-at-the-provider-boundary
  (let [generic (sew-basis/project-adjudication-basis (basis))
        instance-root (root :instance)
        artifact (ruling-for generic)
        verification (authentication/build-authentication-verification
                      {:authentication-verification/ruling-root (:ruling/root artifact)
                       :authentication-verification/authentication-root (:ruling/authentication-root artifact)
                       :authentication-verification/adjudication-instance-root instance-root
                       :authentication-verification/issuer "0xAdjudicator"
                       :authentication-verification/method :ethereum-signature
                       :authentication-verification/credential-material-root (root :credential)
                       :authentication-verification/external-subject "0xAdjudicator"
                       :authentication-verification/provider-evidence-root (root :provider-evidence)
                       :authentication-verification/observation-root (root :observation)
                       :authentication-verification/observed-at 1000
                       :authentication-verification/result :authenticated})
        rebuild #(authentication/build-authentication-verification
                  (dissoc % :artifact/schema :authentication-verification/root))
        verify #(adjudication/verify-ruling-against-basis generic artifact instance-root %)]
    (is (:valid? (verify (fn [_ _] {:authentication-verification verification}))))
    (is (contains? (set (:errors (verify nil))) :missing-ruling-verifier)
        "a manually constructed valid artifact has no admission path without the configured verifier")
    (is (contains? (set (:errors (verify (fn [_ _] nil)))) :provider-verification-failed))
    (is (contains? (set (:errors (verify (fn [_ _] {})))) :authentication-verification-missing))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (assoc verification :artifact/schema "wrong")}))))
                   :invalid-authentication-verification))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (assoc verification :authentication-verification/method :unknown)}))))
                   :unsupported-authentication-method))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (rebuild (assoc verification :authentication-verification/ruling-root (root :other-ruling)))}))))
                   :authentication-verification-ruling-mismatch))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (rebuild (assoc verification :authentication-verification/adjudication-instance-root (root :other-instance)))}))))
                   :authentication-verification-instance-mismatch))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (rebuild (assoc verification :authentication-verification/authentication-root (root :other-authentication)))}))))
                   :authentication-verification-authentication-mismatch))
    (is (contains? (set (:errors (verify (fn [_ _] {:authentication-verification (rebuild (assoc verification :authentication-verification/result :failed))}))))
                   :authentication-not-affirmative))))
