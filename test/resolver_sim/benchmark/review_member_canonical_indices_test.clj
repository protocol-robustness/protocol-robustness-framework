(ns resolver-sim.benchmark.review-member-canonical-indices-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]))

(def keyed-members
  [{:review-member/key 0, :researcher/id :researcher-a, :role :model-steward}
   {:review-member/key 1, :researcher/id :researcher-b, :role :independent-reproducer}
   {:review-member/key 2, :researcher/id :researcher-c, :role :adversarial-reviewer}])

(defn make-keyed-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members keyed-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(defn make-legacy-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members [{:researcher/id :researcher-a :role :model-steward}
                           {:researcher/id :researcher-b :role :independent-reproducer}
                           {:researcher/id :researcher-c :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

;; ── Predicate tests ─────────────────────────────────────────────────────────

(deftest applicable-round-for-keyed
  (let [round (make-keyed-round)]
    (is (ci/applicable-round? round))))

(deftest applicable-round-for-legacy
  (let [round (make-legacy-round)]
    (is (not (ci/applicable-round? round)))))

;; ── Builder tests ───────────────────────────────────────────────────────────

(deftest build-canonical-indices-produces-valid-artifact
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)]
    (is (= "review-member-canonical-indices.v1" (:schema/version artifact)))
    (is (some? (:review-round/id artifact)))
    (is (some? (:review-round/hash artifact)))
    (is (= 3 (:review-member/count artifact)))
    (is (= 3 (count (:review-member/canonical-indices artifact))))
    (is (some? (:review-member-canonical-indices/hash artifact)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:review-member-canonical-indices/hash artifact)))))

(deftest build-canonical-indices-orders-by-key
  (let [shuffled-members [{:review-member/key 2, :researcher/id :researcher-c, :role :adversarial-reviewer}
                          {:review-member/key 0, :researcher/id :researcher-a, :role :model-steward}
                          {:review-member/key 1, :researcher/id :researcher-b, :role :independent-reproducer}]
        round (rr/build-review-round
               {:benchmark/content-root "sha256:abc"
                :review-round/purpose :model-admission
                :review-round/members shuffled-members
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:policy"})
        artifact (ci/build-canonical-indices round)
        indices (:review-member/canonical-indices artifact)]
    (is (= :researcher-a (:review-member/id (nth indices 0))))
    (is (= 0 (:review-member/key (nth indices 0))))
    (is (= 0 (:review-member/index (nth indices 0))))
    (is (= :researcher-b (:review-member/id (nth indices 1))))
    (is (= 1 (:review-member/key (nth indices 1))))
    (is (= 1 (:review-member/index (nth indices 1))))
    (is (= :researcher-c (:review-member/id (nth indices 2))))
    (is (= 2 (:review-member/key (nth indices 2))))
    (is (= 2 (:review-member/index (nth indices 2))))
    (is (= (map :review-member/key indices) (map :review-member/index indices))
        "key/index agreement: canonical index equals dense key")))

(deftest build-canonical-indices-deterministic-from-different-input-order
  (let [round-1 (make-keyed-round)
        alt-members [{:review-member/key 2, :researcher/id :researcher-c, :role :adversarial-reviewer}
                     {:review-member/key 0, :researcher/id :researcher-a, :role :model-steward}
                     {:review-member/key 1, :researcher/id :researcher-b, :role :independent-reproducer}]
        round-2 (rr/build-review-round
                 {:benchmark/content-root "sha256:abc"
                  :review-round/purpose :model-admission
                  :review-round/members alt-members
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})
        a1 (ci/build-canonical-indices round-1)
        a2 (ci/build-canonical-indices round-2)]
    (is (= (:review-member/canonical-indices a1) (:review-member/canonical-indices a2))
        "different input orders must produce identical canonical ordering")
    (is (= (:review-member-canonical-indices/hash a1)
           (:review-member-canonical-indices/hash a2))
        "hashes must be identical for same member set")))

(deftest build-canonical-indices-rejects-legacy-round
  (let [round (make-legacy-round)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Canonical indices require a keyed review round"
                          (ci/build-canonical-indices round)))))

(deftest build-canonical-indices-rejects-duplicate-keys
  (let [bad-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                     {:review-member/key 0, :researcher/id :b, :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (rr/build-review-round
                  {:benchmark/content-root "sha256:abc"
                   :review-round/purpose :model-admission
                   :review-round/members bad-members
                   :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                   :review-round/policy-root "sha256:policy"})))))

(defn- round-from-members [members]
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

;; ── Validator tests ─────────────────────────────────────────────────────────

(deftest validate-canonical-indices-valid
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        result (ci/validate-canonical-indices artifact)]
    (is (:valid? result))
    (is (empty? (:errors result)))))

(deftest validate-canonical-indices-rejects-wrong-version
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad (assoc artifact :schema/version "wrong.v1")
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"schema-version" %) (:errors result)))))

(deftest validate-canonical-indices-rejects-missing-round-id
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad (dissoc artifact :review-round/id)
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"review-round/id" %) (:errors result)))))

(deftest validate-canonical-indices-rejects-count-mismatch
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad (assoc artifact :review-member/count 99)
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"count" %) (:errors result)))))

(deftest validate-canonical-indices-rejects-non-dense-indices
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad-entries [{:review-member/id :z :review-member/key 0 :review-member/index 5}
                     {:review-member/id :a :review-member/key 1 :review-member/index 7}
                     {:review-member/id :b :review-member/key 2 :review-member/index 9}]
        bad (assoc artifact :review-member/canonical-indices bad-entries
                   :review-member/count 3
                   :review-member-canonical-indices/hash nil)
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"non-dense" %) (:errors result)))))

(deftest validate-canonical-indices-rejects-hash-corruption
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad (assoc artifact :review-member-canonical-indices/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000")
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"hash mismatch" %) (:errors result)))))

(deftest validate-canonical-indices-detects-duplicate-ids
  (let [duplicate-id-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                              {:review-member/key 1, :researcher/id :a, :role :independent-reproducer}
                              {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}]]
    (let [round (try (round-from-members duplicate-id-members) (catch Exception _ nil))]
      (when round
        (let [artifact (ci/build-canonical-indices round)
              result (ci/validate-canonical-indices artifact)]
          (is (not (:valid? result)))
          (is (some #(re-find #"duplicate" %) (:errors result))))))))

;; ── Verifier tests ──────────────────────────────────────────────────────────

(deftest verify-canonical-indices-valid
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)
        result (ci/verify-canonical-indices artifact round)]
    (is (= :valid (:status result)))
    (is (every? :match? (:checks result)))
    (is (empty? (:errors result)))))

(deftest verify-canonical-indices-rejects-round-mismatch
  (let [round-a (make-keyed-round)
        round-b (round-from-members [{:review-member/key 0, :researcher/id :x, :role :model-steward}
                                     {:review-member/key 1, :researcher/id :y, :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id :z, :role :adversarial-reviewer}])
        artifact (ci/build-canonical-indices round-a)
        result (ci/verify-canonical-indices artifact round-b)]
    (is (= :round-mismatch (:status result))
        (pr-str (:errors result)))
    (is (not (empty? (:errors result))))))

(deftest verify-canonical-indices-detects-member-substitution
  (let [original-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                          {:review-member/key 1, :researcher/id :b, :role :independent-reproducer}
                          {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}]
        substituted-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                             {:review-member/key 1, :researcher/id :evil, :role :independent-reproducer}
                             {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}]
        round-orig (round-from-members original-members)
        round-sub (round-from-members substituted-members)
        artifact (ci/build-canonical-indices round-orig)
        result (ci/verify-canonical-indices artifact round-sub)]
    (is (= :round-mismatch (:status result))
        (str "expected round-mismatch, got " (:status result)))))

(deftest verify-canonical-indices-detects-hash-corruption
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)
        tampered (assoc artifact :review-member-canonical-indices/hash
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        result (ci/verify-canonical-indices tampered round)]
    (is (= :hash-mismatch (:status result))
        (str "expected hash-mismatch, got " (:status result)))))

(deftest verify-canonical-indices-rejects-legacy-round
  (let [legacy-round (make-legacy-round)
        keyed-round (make-keyed-round)
        artifact (ci/build-canonical-indices keyed-round)
        result (ci/verify-canonical-indices artifact legacy-round)]
    (is (= :not-applicable (:status result)))))

(deftest verify-canonical-indices-detects-reordered-entries
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)
        original-entries (:review-member/canonical-indices artifact)
        reordered (vec (reverse original-entries))
        tampered (assoc artifact :review-member/canonical-indices reordered
                        :review-member-canonical-indices/hash nil)
        result (ci/verify-canonical-indices tampered round)]
    (is (= :ordering-mismatch (:status result))
        (str "expected ordering-mismatch, got " (:status result)))))

;; ── Lookup tests ────────────────────────────────────────────────────────────

(deftest review-member-index-lookup
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= 0 (ci/review-member-index artifact :researcher-a)))
    (is (= 1 (ci/review-member-index artifact :researcher-b)))
    (is (= 2 (ci/review-member-index artifact :researcher-c)))
    (is (nil? (ci/review-member-index artifact :nonexistent)))))

(deftest review-member-at-index-lookup
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= :researcher-a (:review-member/id (ci/review-member-at-index artifact 0))))
    (is (= :researcher-b (:review-member/id (ci/review-member-at-index artifact 1))))
    (is (= :researcher-c (:review-member/id (ci/review-member-at-index artifact 2))))
    (is (nil? (ci/review-member-at-index artifact -1)))
    (is (nil? (ci/review-member-at-index artifact 99)))))

;; ── Round-trip tests ────────────────────────────────────────────────────────

(deftest builder-validator-verifier-round-trip
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)]
    (is (:valid? (:valid? (ci/validate-canonical-indices artifact))))
    (let [v-result (ci/verify-canonical-indices artifact round)]
      (is (= :valid (:status v-result)))
      (is (every? :match? (:checks v-result))))))

;; ── Hash stability tests ────────────────────────────────────────────────────

(deftest hash-stable-for-identical-round
  (let [round (make-keyed-round)]
    (is (= (:review-member-canonical-indices/hash (ci/build-canonical-indices round))
           (:review-member-canonical-indices/hash (ci/build-canonical-indices round)))
        "same round must produce same hash each time")))

(deftest hash-changes-when-member-identity-changes
  (let [round-a (round-from-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                                     {:review-member/key 1, :researcher/id :b, :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}])
        round-b (round-from-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                                     {:review-member/key 1, :researcher/id :different, :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}])]
    (is (not= (:review-member-canonical-indices/hash (ci/build-canonical-indices round-a))
              (:review-member-canonical-indices/hash (ci/build-canonical-indices round-b)))
        "changing a member identity must change the hash")))

(deftest hash-changes-when-key-assignment-changes
  (let [round-a (round-from-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                                     {:review-member/key 1, :researcher/id :b, :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id :c, :role :adversarial-reviewer}])
        round-b (round-from-members [{:review-member/key 0, :researcher/id :a, :role :model-steward}
                                     {:review-member/key 2, :researcher/id :b, :role :independent-reproducer}
                                     {:review-member/key 1, :researcher/id :c, :role :adversarial-reviewer}])]
    (is (not= (:review-member-canonical-indices/hash (ci/build-canonical-indices round-a))
              (:review-member-canonical-indices/hash (ci/build-canonical-indices round-b)))
        "reassigning keys must change the hash")))
