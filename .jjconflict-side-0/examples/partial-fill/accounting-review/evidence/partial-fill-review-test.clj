(ns resolver-sim.accounting.partial-fill-review-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.accounting.partial-fill-review :as a]
            [resolver-sim.assurance.subject-decisions :as d]
            [resolver-sim.assurance.subject-decisions-test :as helpers]))

(def manifest-pin "sha256:48afd9aa8553076ba24b017344d07dc3cb7fa2756a5e591caf4f2881feb05798")
(def evidence (delay (a/load-evidence! "examples/partial-fill/evidence/v3-live-context" manifest-pin)))

(defn setup [phase]
  (let [reviewer (helpers/keypair) ratifier (helpers/keypair)
        p (d/policy {:domain a/domain :purpose (a/purposes phase) :scope a/scope :subject-schema a/schema
                     :validation-root a/validation-root
                     :reviewer {:id "fixture-accounting-reviewer" :public-key (helpers/public-pem reviewer)}
                     :ratifier {:id "fixture-accounting-ratifier" :public-key (helpers/public-pem ratifier)}})
        subject (a/statement @evidence phase)]
    {:p p :reviewer reviewer :ratifier ratifier :subject subject
     :subjects {(:artifact/root subject) subject} :verifier (a/verifier @evidence)}))

(deftest actual-v3-statements-are-purpose-separated
  (let [allocation (a/statement @evidence :allocation)
        realized (a/statement @evidence :realized)]
    (is (nil? (:settlement allocation)))
    (is (not= (:purpose allocation) (:purpose realized)))
    (is (= [35 21 14] (mapv :allocated (:rows allocation))))
    (is (= [35 21 14] (mapv :paid (get-in realized [:settlement :withdrawals]))))
    (is (every? #(not (contains? % :allocated)) (get-in realized [:settlement :withdrawals])))
    (is (= 10 (get-in realized [:settlement :restricted-refund :amount])))
    (doseq [s [allocation realized]]
      (is (:verified? (a/verify-statement @evidence s)))
      (is (= a/limitations (:limitations s))))))

(deftest conservation-is-not-policy-compliance
  (let [candidate (a/statement @evidence :allocation true nil)
        report (a/verify-statement @evidence candidate)]
    (is (:synthetic? candidate))
    (is (nil? (:settlement candidate)))
    (is (= [40 24 16] (mapv :allocated (:rows candidate))))
    (is (true? (get-in report [:checks :conservation])))
    (is (false? (get-in report [:checks :restricted-capital])))
    (is (false? (get-in report [:checks :recipient-policy])))
    (is (false? (:eligible? report)))))

(deftest independent-review-and-adoption-of-both-accounting-cutoffs
  (doseq [phase [:allocation :realized]]
    (let [{:keys [p verifier subjects subject] :as f} (setup phase)
          a (helpers/request f [] :accept)
          accepted (d/reconstruct p verifier subjects [a])
          ratification (helpers/request f [a] :ratify)
          state (d/reconstruct p verifier subjects [a ratification])]
      (is (nil? (:authoritative accepted)))
      (is (= (:artifact/root subject) (get-in state [:authoritative :subject/root])))
      (is (= (:artifact/root a) (get-in state [:authoritative :acceptance/root])))
      (is (false? (:capital-authority (verifier subject)))))))

(deftest invalid-interpretation-rejected-by-an-authenticated-reviewer
  (let [{:keys [p] :as f} (setup :allocation)
        bad (a/statement @evidence :allocation true nil)
        f (assoc f :subject bad :subjects {(:artifact/root bad) bad})
        {:keys [verifier subjects]} f
        review (helpers/request f [] :reject)]
    (is (thrown? Exception (d/reconstruct p verifier subjects [(helpers/request f [] :accept)])))
    (is (= :reject (get-in (d/reconstruct p verifier subjects [review]) [:decisions (:artifact/root bad) :decision])))
    (is (thrown? Exception (d/reconstruct p verifier subjects [review (helpers/request f [review] :ratify)])))))

(deftest accounting-bindings-and-coverage-cannot-be-silently-expanded
  (let [original (a/statement @evidence :realized)]
    (doseq [altered [(assoc original :economic-policy/root "0xdead")
                     (assoc original :evidence/root (d/root "other-evidence" {}))
                     (assoc original :source/root "0xother")
                     (assoc original :limitations [])
                     (update original :rows pop)
                     (assoc-in original [:settlement :restricted-refund :beneficiary] "0xwrong")
                     (assoc-in original [:settlement :remaining-liability] 1)
                     (assoc-in original [:rows 0 :allocated] 40)]]
      (is (false? (:verified? (a/verify-statement @evidence
                                                (assoc altered :artifact/root (d/artifact-root altered)))))))))

(deftest altered-primary-evidence-is-rejected
  (let [bad (assoc-in @evidence [:files "transfers.json"] (.getBytes "[]" "UTF-8"))]
    (is (thrown? Exception (a/evidence-material bad))))
  (is (thrown? Exception (a/load-evidence! "examples/partial-fill/evidence/v3-live-context"
                                          (d/root "wrong-manifest" {})))))

(deftest realized-statement-cannot-enter-allocation-review
  (let [{:keys [p verifier]} (setup :allocation)]
    (is (thrown? Exception (d/verify-subject p verifier (a/statement @evidence :realized))))))
