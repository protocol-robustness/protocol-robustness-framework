(ns resolver-sim.protocols.adjudication-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.adjudication :as adjudication]
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
        artifact (ruling-for generic)
        verifier (fn [basis _]
                   {:authenticated? true
                    :authority-root (:adjudication/authority-root basis)
                    :adjudicator-root (:adjudication/adjudicator-root basis)})]
    (is (:valid? (adjudication/verify-ruling-against-basis generic artifact verifier)))
    (is (not (:valid? (adjudication/verify-ruling-against-basis
                       generic (assoc artifact :ruling/basis-root (root :other)) verifier))))
    (is (not (:valid? (adjudication/verify-ruling-against-basis
                       generic artifact (constantly {:authenticated? true})))))))
