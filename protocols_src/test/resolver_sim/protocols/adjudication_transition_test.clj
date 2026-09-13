(ns resolver-sim.protocols.adjudication-transition-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.adjudication-transition :as sut]))

(defn- root [x] (str "sha256:" (hc/domain-hash :evidence-record {:x x})))
(defn- fixture []
  (let [subject (root :subject) predecessor-adjudication (root :previous-adjudication)
        predecessor-adjudicator (root :previous-adjudicator)
        successor-adjudication (root :next-adjudication) successor-adjudicator (root :next-adjudicator)
        decision (sut/build-decision {:decision/subject-root subject :decision/case-root (root :case)
                                      :decision/adjudication-root predecessor-adjudication
                                      :decision/adjudicator-root predecessor-adjudicator
                                      :decision/outcome :release :decision/temporal-basis-root (root :time)})
        eligibility (sut/build-eligibility {:transition/type :appeal
                                            :transition/predecessor-adjudication-root predecessor-adjudication
                                            :transition/predecessor-decision-root (:decision/root decision)
                                            :transition/state-root (root :state)
                                            :transition/temporal-basis-root (root :time)
                                            :transition/eligibility :eligible})
        basis (sut/build-selection-basis {:transition/subject-root subject
                                          :transition/predecessor-adjudication-root predecessor-adjudication
                                          :transition/predecessor-decision-root (:decision/root decision)
                                          :transition/eligibility-root (:transition/eligibility-root eligibility)
                                          :transition/module-binding-root (root :module)
                                          :transition/protocol-instance-root (root :protocol)
                                          :transition/state-root (root :state)})
        result (sut/build-selection-result {:transition/selection-basis-root (:transition/selection-basis-root basis)
                                            :transition/successor-adjudication-root successor-adjudication
                                            :transition/successor-adjudicator-root successor-adjudicator
                                            :transition/selection :selected})
        transition (sut/build-authority-transition {:transition/subject-root subject
                                                     :transition/predecessor-adjudication-root predecessor-adjudication
                                                     :transition/predecessor-decision-root (:decision/root decision)
                                                     :transition/predecessor-adjudicator-root predecessor-adjudicator
                                                     :transition/successor-adjudication-root successor-adjudication
                                                     :transition/successor-adjudicator-root successor-adjudicator
                                                     :transition/eligibility-root (:transition/eligibility-root eligibility)
                                                     :transition/selection-basis-root (:transition/selection-basis-root basis)
                                                     :transition/selection-result-root (:transition/selection-result-root result)
                                                     :transition/module-binding-root (root :module)
                                                     :transition/protocol-instance-root (root :protocol)})]
    {:decision decision :eligibility eligibility :basis basis :result result :transition transition}))

(deftest authority-transition-reconstructs-and-rejects-substitution
  (let [{:keys [decision eligibility basis result transition]} (fixture)
        valid (sut/validate-authority-transition transition decision eligibility basis result)
        wrong-decision (assoc decision :decision/root (root :other-decision))
        stale-result (assoc result :transition/successor-adjudicator-root (root :other-resolver))]
    (is (:valid? valid))
    (is (some #{:predecessor-decision-mismatch}
              (:errors (sut/validate-authority-transition transition wrong-decision eligibility basis result))))
    (is (some #{:successor-adjudicator-mismatch}
              (:errors (sut/validate-authority-transition transition decision eligibility basis stale-result))))
    (is (not (:valid? (sut/validate-eligibility
                        (assoc eligibility :transition/eligibility :ineligible)))))))
