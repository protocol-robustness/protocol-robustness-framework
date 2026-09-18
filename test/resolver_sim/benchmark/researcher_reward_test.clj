(ns resolver-sim.benchmark.researcher-reward-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.hash.reference :as hash-ref]))

(def root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def policy (reward/build-policy {:accepted-contribution-types #{:approval :dissent :reproduction :adversarial-finding}
                                  :required-verification #{:verified :claimable}
                                  :required-claim-statuses #{:established}
                                  :reward-amount 100
                                  :reward-currency "USD"
                                  :split-rules {:default :full}
                                  :duplicate-rule {:mode :reject}
                                  :conflict-of-interest-rule {:mode :reject}}))

(defn- check [changes]
  (reward/evaluate (merge {:policy policy :researcher "researcher-a"
                           :assignment-root root :contribution-root root
                           :contribution-type :approval
                           :verification {:verified? true}
                           :claim {:claimable? true :research-claim/status :established}
                           :duplicate? false :conflict-of-interest? false}
                          changes)))

(deftest accepted-contributions-are-eligible
  (doseq [type [:approval :dissent :reproduction :adversarial-finding]]
    (let [result (check {:contribution-type type})]
      (is (:reward/eligible? result) type)
      (is (hash-ref/valid-sha256-ref? (:researcher-reward-eligibility/root result)) type))))

(deftest eligibility-rejects-unqualified-or-untrusted-evidence
  (doseq [[changes reason] [[{:claim {:claimable? false :research-claim/status :established}} :claim-not-claimable]
                            [{:claim {:claimable? true :research-claim/status :tentative}} :claim-status-not-accepted]
                            [{:claim {:claimable? true :research-claim/status :qualified}} :claim-status-not-accepted]
                            [{:verification {:verified? false}} :verification-failed]
                            [{:assignment-root "not-a-root"} :invalid-assignment-root]
                            [{:duplicate? true} :duplicate-submission]
                            [{:conflict-of-interest? true} :conflict-of-interest]]]
    (let [result (check changes)]
      (is (false? (:reward/eligible? result)) reason)
      (is (some #{reason} (:reward/reasons result)) reason))))

(deftest proposal-and-obligation-preserve-eligible-policy-bound-terms
  (let [eligibility (check {})
        proposal (reward/build-proposal {:eligibility eligibility :policy policy})
        authority {:authority/id "rewards-committee"
                   :authority/root root
                   :authority/authorized? true}
        obligation (reward/build-obligation {:proposal proposal
                                             :eligibility eligibility
                                             :approval-authority authority})]
    (is (= (:researcher-reward-eligibility/root eligibility)
           (:researcher-reward-eligibility/root proposal)))
    (is (= (:researcher-reward-proposal/root proposal)
           (:researcher-reward-proposal/root obligation)))
    (is (= "rewards-committee" (:approval-authority/id obligation)))
    (is (hash-ref/valid-sha256-ref? (:researcher-reward-obligation/root obligation)))
    (is (not (contains? obligation :payment/status)))
    (is (not (contains? obligation :payment/authority)))))

(deftest identical-proposals-and-approvals-are-idempotent-by-root
  (let [eligibility (check {})
        input {:eligibility eligibility :policy policy}
        authority {:authority/id "rewards-committee" :authority/root root :authority/authorized? true}
        proposal-a (reward/build-proposal input)
        proposal-b (reward/build-proposal input)
        obligation-a (reward/build-obligation {:proposal proposal-a :eligibility eligibility :approval-authority authority})
        obligation-b (reward/build-obligation {:proposal proposal-b :eligibility eligibility :approval-authority authority})]
    (is (= (:reward/key eligibility) (:reward/key proposal-a)))
    (is (= (:researcher-reward-proposal/root proposal-a) (:researcher-reward-proposal/root proposal-b)))
    (is (= (:researcher-reward-obligation/root obligation-a)
           (:researcher-reward-obligation/root obligation-b)))
    (is (reward/valid-obligation? obligation-a))))

(deftest proposal-and-obligation-fail-closed
  (let [ineligible (check {:duplicate? true})
        eligibility (check {})
        proposal (reward/build-proposal {:eligibility eligibility :policy policy})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (reward/build-proposal {:eligibility ineligible :policy policy})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (reward/build-obligation {:proposal proposal :eligibility eligibility
                                           :approval-authority {:authority/id "rewards-committee"
                                                                :authority/root root
                                                                :authority/authorized? false}})))))

(deftest obligation-rejects-substitution-and-replay
  (let [eligibility (check {})
        proposal (reward/build-proposal {:eligibility eligibility :policy policy})
        authority {:authority/id "rewards-committee" :authority/root root :authority/authorized? true}
        substitute (fn [key value]
                     (let [changed (assoc proposal key value)]
                       (assoc changed :researcher-reward-proposal/root (reward/proposal-root changed))))]
    (doseq [[key value] [[:reward/amount 99]
                         [:reward/currency "EUR"]
                         [:contribution/type :dissent]
                         [:researcher/id "researcher-b"]
                         [:research-assignment/root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
                         [:researcher-reward-policy/root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (reward/build-obligation {:proposal (substitute key value)
                                             :eligibility eligibility
                                             :approval-authority authority})) key))
    (let [other (check {:contribution-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (reward/build-obligation {:proposal proposal :eligibility other
                                             :approval-authority authority}))))))
