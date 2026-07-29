(ns resolver-sim.benchmark.golden-fixture-test
  "Golden fixture tests for review rounds and case sets.
   Each fixture is loaded, rehashed, and compared against its committed hash."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.case-set :as cs]))

(defn- load-fixture [path]
  (edn/read-string (slurp (io/resource path))))

(deftest legacy-unkeyed-round-golden
  (let [fixture (load-fixture "data/fixtures/review/legacy-unkeyed-review-round.edn")
        {:keys [review-round expected/hash]} fixture
        recomputed (rr/build-review-round
                    {:benchmark/content-root (:benchmark/content-root review-round)
                     :review-round/purpose (:review-round/purpose review-round)
                     :review-round/members (:review-round/members review-round)
                     :review-round/membership-frozen-at (:review-round/membership-frozen-at review-round)
                     :review-round/policy-root (:review-round/policy-root review-round)})]
    (is (rr/round-valid? recomputed))
    (is (not (rr/round-uses-member-keys? recomputed))
        "legacy round must not use member keys")
    (is (= hash (:review-round/id recomputed))
        "legacy round hash must match committed fixture")
    (is (= (:review-round/id review-round) (:review-round/id recomputed))
        "recomputed hash must equal original")))

(deftest keyed-round-golden
  (let [fixture (load-fixture "data/fixtures/review/keyed-review-round.edn")
        {:keys [review-round expected/hash]} fixture
        recomputed (rr/build-review-round
                    {:benchmark/content-root (:benchmark/content-root review-round)
                     :review-round/purpose (:review-round/purpose review-round)
                     :review-round/members (:review-round/members review-round)
                     :review-round/membership-frozen-at (:review-round/membership-frozen-at review-round)
                     :review-round/policy-root (:review-round/policy-root review-round)})]
    (is (rr/round-valid? recomputed))
    (is (rr/round-uses-member-keys? recomputed)
        "keyed round must use member keys")
    (is (= hash (:review-round/id recomputed))
        "keyed round hash must match committed fixture")
    (is (= (:review-round/id review-round) (:review-round/id recomputed))
        "recomputed hash must equal original")))

(deftest keyed-and-legacy-hash-same-when-ids-match
  (let [legacy (load-fixture "data/fixtures/review/legacy-unkeyed-review-round.edn")
        keyed (load-fixture "data/fixtures/review/keyed-review-round.edn")]
    (is (= (:expected/hash legacy) (:expected/hash keyed))
        "keyed and legacy rounds with same researcher IDs and same input order must produce the same hash")))

(deftest keyed-round-input-order-invariance
  (let [fixture (load-fixture "data/fixtures/review/keyed-review-round.edn")
        expected-hash (:expected/hash fixture)
        original-members (:review-round/members (:review-round fixture))
        reversed-members (vec (reverse original-members))
        recomputed (rr/build-review-round
                    {:benchmark/content-root "sha256:golden-content"
                     :review-round/purpose :model-admission
                     :review-round/members reversed-members
                     :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                     :review-round/policy-root "sha256:golden-policy"})]
    (is (= expected-hash (:review-round/id recomputed))
        "reversed input order must not change the keyed round hash")))

(deftest canonical-indices-golden
  (let [fixture (load-fixture "data/fixtures/review/keyed-canonical-indices.edn")
        {:keys [canonical-indices expected/hash]} fixture
        round (:review-round (load-fixture "data/fixtures/review/keyed-review-round.edn"))
        recomputed (ci/build-canonical-indices round)]
    (is (= 3 (:review-member/count recomputed)))
    (is (= hash (:review-member-canonical-indices/hash recomputed))
        "canonical-indices hash must match committed fixture")))

(deftest generated-case-set-golden
  (let [fixture (load-fixture "data/fixtures/review/generated-case-set.edn")
        {:keys [expected/root case-set]} fixture
        recomputed-root (cs/compute-case-set-root case-set)]
    (is (= root recomputed-root)
        "case-set root must match committed fixture")
    (is (= [0 1 2] (mapv :case/key case-set))
        "case keys must be 0, 1, 2")
    (is (= 3 (count case-set))
        "three cases expected")))
