(ns resolver-sim.benchmark.review-round-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-round :as rr]))

(def sample-members
  [{:researcher/id "researcher-a" :role :model-steward}
   {:researcher/id "researcher-b" :role :independent-reproducer}
   {:researcher/id "researcher-c" :role :adversarial-reviewer}])

(deftest build-model-admission-valid
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (is (rr/round-valid? round))
    (is (= :model-admission (:review-round/purpose round)))))

(deftest build-model-admission-missing-policy-root-fails
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"creation requirements"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:abc"
                          :review-round/purpose :model-admission
                          :review-round/members sample-members
                          :review-round/membership-frozen-at "2026-07-01T00:00:00Z"}))))

(deftest build-model-replication-does-not-require-run-reports-at-creation
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-replication
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (is (rr/round-valid? round))
    (is (= :model-replication (:review-round/purpose round)))))

(deftest build-model-replication-with-run-reports-valid
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-replication
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"
                :review-round/run-report-refs ["sha256:r1" "sha256:r2" "sha256:r3"]})]
    (is (rr/round-valid? round))
    (is (= :model-replication (:review-round/purpose round)))))

(deftest build-model-challenge-requires-target
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"creation requirements"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:abc"
                          :review-round/purpose :model-challenge
                          :review-round/members sample-members
                          :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                          :review-round/policy-root "sha256:policy"}))))

(deftest build-model-challenge-valid
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-challenge
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"
                :review-round/challenge-target :position/current-amount-precedence
                :review-round/challenge-reason-code :authority-predicate-too-narrow
                :review-round/challenge-evidence-ref "sha256:evidence"})]
    (is (rr/round-valid? round))))

(deftest build-model-revision-requires-roots
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"creation requirements"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:abc"
                          :review-round/purpose :model-revision
                          :review-round/members sample-members
                          :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                          :review-round/policy-root "sha256:policy"}))))

(deftest build-model-revision-valid
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-revision
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"
                :review-round/parent-content-root "sha256:parent"
                :review-round/proposed-content-root "sha256:proposed"
                :review-round/change-set-root "sha256:changes"})]
    (is (rr/round-valid? round))))

(deftest purpose-requirements-met-check
  (is (:valid? (rr/check-creation-requirements
                :model-admission
                {:benchmark/content-root "sha256:c"
                 :review-round/policy-root "sha256:p"})))
  (is (not (:valid? (rr/check-creation-requirements
                     :model-admission
                     {:benchmark/content-root "sha256:c"})))))

(deftest round-rejects-invalid-purpose
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"review-round purpose"
                        (rr/build-review-round
                         {:benchmark/content-root "sha256:abc"
                          :review-round/purpose :invalid-purpose
                          :review-round/members sample-members
                          :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                          :review-round/policy-root "sha256:policy"}))))
