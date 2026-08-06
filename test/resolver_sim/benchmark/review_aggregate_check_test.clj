(ns resolver-sim.benchmark.review-aggregate-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-aggregate-check :as rac]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]))

(def keyed-members
  [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}])

(defn make-keyed-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members keyed-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(def sample-members
  [{:researcher/id "researcher-a" :role :model-steward}
   {:researcher/id "researcher-b" :role :independent-reproducer}
   {:researcher/id "researcher-c" :role :adversarial-reviewer}])

(defn make-legacy-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members sample-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

;; ── check-aggregate-member-bit-width tests ──────────────────────────────────

(deftest aggregate-member-bit-width-holds-for-keyed-round
  (let [round (make-keyed-round)
        result (rac/check-aggregate-member-bit-width round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-bit-width-holds-for-keyed-round-with-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        result (rac/check-aggregate-member-bit-width round indices)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-bit-width-detects-mismatch
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        tampered (assoc indices :review-member/count 7)
        result (rac/check-aggregate-member-bit-width round tampered)]
    (is (not (:holds? result))
        "tampering the artifact count changes the derived bit-width (round 2 vs artifact 3), so the aggregate check detects the mismatch")
    (is (= :resolver-sim.benchmark.review-aggregate-check/member-bit-width-mismatch
           (:kind (first (:violations result))))
        "violation kind must identify the bit-width mismatch")))

(deftest aggregate-member-bit-width-holds-for-legacy-round
  (let [round (make-legacy-round)
        result (rac/check-aggregate-member-bit-width round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

;; ── check-aggregate-member-key-density tests ────────────────────────────────

(deftest aggregate-member-key-density-holds-for-keyed-round
  (let [round (make-keyed-round)
        result (rac/check-aggregate-member-key-density round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-holds-with-canonical-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        result (rac/check-aggregate-member-key-density round indices)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-holds-for-legacy-round
  (let [round (make-legacy-round)
        result (rac/check-aggregate-member-key-density round)]
    (is (:holds? result))
    (is (empty? (:violations result)))))

(deftest aggregate-member-key-density-detects-count-mismatch-with-indices
  (let [round (make-keyed-round)
        indices (ci/build-canonical-indices round)
        bad-indices (assoc indices :review-member/count 99)
        result (rac/check-aggregate-member-key-density round bad-indices)]
    (is (not (:holds? result)))
    (is (some #(= ::rac/member-count-mismatch (:kind %)) (:violations result)))))
