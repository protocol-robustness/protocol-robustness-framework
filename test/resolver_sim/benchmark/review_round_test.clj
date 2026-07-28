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

;; ── Member-key tests ───────────────────────────────────────────────────────

(def keyed-members
  [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}])

(deftest valid-keyed-round
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members keyed-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (is (rr/round-valid? round))
    (is (rr/round-uses-member-keys? round))
    (is (= #{0 1 2} (set (map :review-member/key (:review-round/members round)))))))

(deftest duplicate-member-keys-rejected
  (let [bad-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:review-member/key 0, :researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate review-member keys"
                          (rr/build-review-round
                           {:benchmark/content-root "sha256:abc"
                            :review-round/purpose :model-admission
                            :review-round/members bad-members
                            :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                            :review-round/policy-root "sha256:policy"})))))

(deftest mixed-keyed-and-unkeyed-members-rejected
  (let [bad-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Mixed keyed and unkeyed"
                          (rr/build-review-round
                           {:benchmark/content-root "sha256:abc"
                            :review-round/purpose :model-admission
                            :review-round/members bad-members
                            :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                            :review-round/policy-root "sha256:policy"})))))

(deftest negative-key-rejected
  (let [bad-members [{:review-member/key -1, :researcher/id "a", :role :model-steward}
                     {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid member key: -1"
                          (rr/build-review-round
                           {:benchmark/content-root "sha256:abc"
                            :review-round/purpose :model-admission
                            :review-round/members bad-members
                            :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                            :review-round/policy-root "sha256:policy"})))))

(deftest non-integer-key-rejected
  (let [bad-members [{:review-member/key "0", :researcher/id "a", :role :model-steward}
                     {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid member key: 0"
                          (rr/build-review-round
                           {:benchmark/content-root "sha256:abc"
                            :review-round/purpose :model-admission
                            :review-round/members bad-members
                            :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                            :review-round/policy-root "sha256:policy"})))))

(deftest non-dense-keys-rejected
  (let [bad-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:review-member/key 2, :researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 3, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Non-dense review-member keys"
                          (rr/build-review-round
                           {:benchmark/content-root "sha256:abc"
                            :review-round/purpose :model-admission
                            :review-round/members bad-members
                            :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                            :review-round/policy-root "sha256:policy"})))))

(deftest duplicate-researcher-id-under-different-keys-rejected-via-validate
  (let [bad-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:review-member/key 1, :researcher/id "a", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
        round {:schema-version "benchmark-review-round.v1"
               :review-round/id "rr:test"
               :benchmark/content-root "sha256:abc"
               :review-round/purpose :model-admission
               :review-round/members bad-members
               :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
               :review-round/policy-root "sha256:policy"
               :review-round/status :open}]
    (is (not (:valid? (rr/validate-round round))))
    (is (some #(re-find #"duplicate researcher/id" %)
              (:errors (rr/validate-round round))))))

(deftest assign-consecutive-member-keys-preserves-order
  (let [members [{:researcher/id "z" :role :model-steward}
                 {:researcher/id "a" :role :independent-reproducer}
                 {:researcher/id "m" :role :adversarial-reviewer}]
        keyed (rr/assign-consecutive-member-keys members)]
    (is (= 0 (:review-member/key (first keyed))))
    (is (= "z" (:researcher/id (first keyed))))
    (is (= 1 (:review-member/key (second keyed))))
    (is (= 2 (:review-member/key (nth keyed 2))))))

(deftest legacy-round-without-keys-validates
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members sample-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (is (rr/round-valid? round))
    (is (not (rr/round-uses-member-keys? round)))))

(deftest member-key-lookup-round-trip
  (let [round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members keyed-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (testing "member-by-key returns correct researcher"
      (is (= "researcher-a" (:researcher/id (rr/member-by-key round 0))))
      (is (= "researcher-b" (:researcher/id (rr/member-by-key round 1))))
      (is (= "researcher-c" (:researcher/id (rr/member-by-key round 2))))
      (is (nil? (rr/member-by-key round 99))))
    (testing "member-key-for-researcher returns correct key"
      (is (= 0 (rr/member-key-for-researcher round "researcher-a")))
      (is (= 1 (rr/member-key-for-researcher round "researcher-b")))
      (is (nil? (rr/member-key-for-researcher round "nonexistent"))))
    (testing "researcher-id-for-member-key round-trip"
      (is (= "researcher-a" (rr/researcher-id-for-member-key round 0)))
      (is (= "researcher-b" (rr/researcher-id-for-member-key round 1))))))

(deftest keyed-round-hash-differs-from-legacy
  (let [legacy (rr/build-review-round
                {:benchmark/content-root "sha256:abc"
                 :review-round/purpose :model-admission
                 :review-round/members sample-members
                 :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                 :review-round/policy-root "sha256:policy"})
        keyed (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members keyed-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})]
    (is (not= (:review-round/id legacy) (:review-round/id keyed))
        "keyed and legacy rounds should have different hashes (keys are committed)")))
