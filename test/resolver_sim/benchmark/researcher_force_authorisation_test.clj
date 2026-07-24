(ns resolver-sim.benchmark.researcher-force-authorisation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]))

(def sample-round
  {:benchmark/content-root "sha256:content"
   :review-round/id "review-round:test"
   :review-round/members
   [{:researcher/id "researcher-a" :role :model-steward}
    {:researcher/id "researcher-b" :role :independent-reproducer}
    {:researcher/id "researcher-c" :role :adversarial-reviewer}]})

(deftest two-of-three-authorised
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}
                      {:researcher/id "researcher-b" :signed-content-hash "sha256:sc2"}]
          :dissents []})]
    (is (= :authorised-unanimous status))
    (is (rfa/authorisation-approved? authorisation))
    (is (rfa/authorisation-valid? authorisation))))

(deftest two-of-three-with-dissent
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}
                      {:researcher/id "researcher-b" :signed-content-hash "sha256:sc2"}]
          :dissents [{:researcher/id "researcher-c" :reason "derivation not supported"}]})]
    (is (= :authorised-with-dissent status))
    (is (rfa/authorisation-approved? authorisation))
    (is (= 1 (count (rfa/authorisation-dissents authorisation))))))

(deftest one-of-three-blocked
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}]
          :dissents []})]
    (is (= :blocked status))
    (is (not (rfa/authorisation-approved? authorisation)))))

(deftest unanimous-authorised
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}
                      {:researcher/id "researcher-b" :signed-content-hash "sha256:sc2"}
                      {:researcher/id "researcher-c" :signed-content-hash "sha256:sc3"}]
          :dissents []})]
    (is (= :authorised-unanimous status))
    (is (rfa/authorisation-approved? authorisation))
    (is (empty? (rfa/authorisation-dissents authorisation)))))

(deftest dissent-preserved-but-does-not-block
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}
                      {:researcher/id "researcher-b" :signed-content-hash "sha256:sc2"}]
          :dissents [{:researcher/id "researcher-c" :reason "methodology concern"}]})]
    (is (= :authorised-with-dissent status))
    (let [d (rfa/authorisation-dissents authorisation)]
      (is (= 1 (count d)))
      (is (= "researcher-c" (:researcher/id (first d)))))))

(deftest non-member-approval-ignored
  (let [{:keys [status authorisation]}
        (rfa/build-authorisation
         {:review-round sample-round
          :target-case-hash "sha256:target"
          :approvals [{:researcher/id "researcher-a" :signed-content-hash "sha256:sc1"}
                      {:researcher/id "researcher-d" :signed-content-hash "sha256:sc4"}]
          :dissents []})]
    (is (= :blocked status) "non-member approval should not count toward threshold")))

(deftest policy-valid
  (is (rfa/policy-valid? rfa/default-policy))
  (is (rfa/policy-valid? {:force-authorisation-policy/schema-version
                          "three-member-force-authorisation-policy.v1"
                          :member-count 5 :threshold 3}))
  (is (not (rfa/policy-valid? {:member-count 1 :threshold 5})))
  (is (not (rfa/policy-valid? {:member-count 3 :threshold 0}))))
