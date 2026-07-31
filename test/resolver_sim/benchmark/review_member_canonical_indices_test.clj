(ns resolver-sim.benchmark.review-member-canonical-indices-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.review-member-canonical-indices :as ci]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review.three-member-certificate :as cert]))

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

(defn make-unkeyed-round []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members [{:researcher/id "researcher-a" :role :model-steward}
                           {:researcher/id "researcher-b" :role :independent-reproducer}
                           {:researcher/id "researcher-c" :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(defn- round-from-members [members]
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

;; ── Canonical-indices works for both keyed and unkeyed rounds ───────────────

(deftest builds-for-keyed-round
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (some? (:review-member-canonical-indices/hash artifact)))))

(deftest builds-for-unkeyed-round
  (let [artifact (ci/build-canonical-indices (make-unkeyed-round))]
    (is (some? (:review-member-canonical-indices/hash artifact)))))

;; ── Key mismatch rejection tests ───────────────────────────────────────────

(deftest key-mismatch-rejected-at-construction
  (testing "same researchers with different dense key assignment must fail"
    (let [bad-members [{:review-member/key 2, :researcher/id "researcher-a", :role :model-steward}
                       {:review-member/key 0, :researcher/id "researcher-b", :role :independent-reproducer}
                       {:review-member/key 1, :researcher/id "researcher-c", :role :adversarial-reviewer}]
          round (round-from-members bad-members)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ci/build-canonical-indices round))
          "key 2 for researcher-a must fail because derived index is 0"))))

(deftest key-matches-derived-index-passes
  (testing "keys that match the derived index must pass"
    (let [artifact (ci/build-canonical-indices (make-keyed-round))]
      (is (some? (:review-member-canonical-indices/hash artifact))))))

;; ── Input permutation invariance tests ──────────────────────────────────────

(deftest permutation-invariance
  (testing "same members in different input orders produce identical results"
    (let [order-a [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
                   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
                   {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}]
          order-b [{:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}
                   {:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
                   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}]
          round-a (round-from-members order-a)
          round-b (round-from-members order-b)]
      ;; Same review-round hash (identity excludes keys, sort by researcher/id)
      (is (= (:review-round/id round-a) (:review-round/id round-b))
          "review-round hashes must be identical for same three researcher IDs")
      ;; Same canonical entries
      (let [ca (ci/build-canonical-indices round-a)
            cb (ci/build-canonical-indices round-b)]
        (is (= (:review-member/canonical-indices ca)
               (:review-member/canonical-indices cb))
            "canonical entries must be identical regardless of input order")
        ;; Same artifact hash
        (is (= (:review-member-canonical-indices/hash ca)
               (:review-member-canonical-indices/hash cb))
            "artifact hashes must be identical for same member set")))))

;; ── Identity hash equivalence tests ─────────────────────────────────────────

(deftest keyed-and-legacy-same-hash-for-same-researchers
  (testing "keyed and unkeyed rounds with same three researcher IDs share a hash"
    (let [keyed (make-keyed-round)
          legacy (make-unkeyed-round)]
      (is (= (:review-round/id keyed) (:review-round/id legacy))
          ":review-member/key must be excluded from the identity projection"))))

;; ── Semantic tampering tests ────────────────────────────────────────────────

(deftest tampered-hash-rejected
  (testing "altering the mapping without recomputing hash produces :hash-mismatch"
    (let [round (make-keyed-round)
          artifact (ci/build-canonical-indices round)
          entries (:review-member/canonical-indices artifact)
          tampered (update-in artifact [:review-member/canonical-indices 0]
                              assoc :researcher/id "impostor")
          result (ci/verify-canonical-indices tampered round)]
      ;; A correct verifier must detect that entries changed (derived mapping mismatch)
      ;; AND that the stored hash no longer matches.
      (is (= :canonical-indices-derived-mapping-mismatch (:status result))
          "entries changed but hash not recomputed → derived mapping mismatch")
      (is (some #(re-find #"derived mapping mismatch" %) (:errors result))))))

(deftest semantically-altered-rehashed-rejected
  (testing "altering mapping and recomputing hash must still be rejected"
    (let [round (make-keyed-round)
          artifact (ci/build-canonical-indices round)
          entries (:review-member/canonical-indices artifact)
          ;; Swap two entries
          swapped (vec (assoc (vec (assoc entries 0 (nth entries 1))) 1 (nth entries 0)))
          rehashed (assoc artifact :review-member/canonical-indices swapped
                          :review-member-canonical-indices/hash nil)
          result (ci/verify-canonical-indices rehashed round)]
      ;; The verifier must detect that the entries differ from what the
      ;; review round produces, even though the stored hash may be
      ;; recomputed or absent.
      (is (= :canonical-indices-derived-mapping-mismatch (:status result))
          "rehashed artifact with wrong ordering must be rejected by semantic rederivation"))))

;; ── Researcher-ID type preservation tests ──────────────────────────────────

(deftest researcher-id-type-preservation
  (testing "researcher-id must preserve value and type exactly"
    (let [artifact (ci/build-canonical-indices (make-keyed-round))
          entries (:review-member/canonical-indices artifact)]
      (is (= "researcher-a" (:researcher/id (nth entries 0)))
          "string researcher-id must remain a string")
      (is (= "researcher-b" (:researcher/id (nth entries 1))))
      (is (= "researcher-c" (:researcher/id (nth entries 2)))))))

(deftest keyword-researcher-id-rejected
  (testing "non-string researcher-id must be rejected at canonical-indices build time"
    (let [kw-members [{:researcher/id "a", :role :model-steward}
                      {:researcher/id "b", :role :independent-reproducer}
                      {:researcher/id "c", :role :adversarial-reviewer}]
          round (rr/build-review-round
                 {:benchmark/content-root "sha256:abc"
                  :review-round/purpose :model-admission
                  :review-round/members kw-members
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})
          ;; Tamper the round to have a keyword researcher-id
          tampered-members (update-in (:review-round/members round) [0] assoc :researcher/id :kw-a)
          tampered-round (assoc round :review-round/members tampered-members)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ci/build-canonical-indices tampered-round))
          "keyword researcher-id must be rejected by canonical-indices builder"))))

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

(deftest build-canonical-indices-orders-by-researcher-id
  (let [shuffled-members [{:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}
                          {:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
                          {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}]
        round (round-from-members shuffled-members)
        artifact (ci/build-canonical-indices round)
        indices (:review-member/canonical-indices artifact)]
    (is (= "researcher-a" (:researcher/id (nth indices 0))))
    (is (= 0 (:review-member/index (nth indices 0))))
    (is (= "researcher-b" (:researcher/id (nth indices 1))))
    (is (= 1 (:review-member/index (nth indices 1))))
    (is (= "researcher-c" (:researcher/id (nth indices 2))))
    (is (= 2 (:review-member/index (nth indices 2))))))

(deftest build-canonical-indices-works-for-unkeyed-round
  (let [round (make-unkeyed-round)
        artifact (ci/build-canonical-indices round)]
    (is (some? (:review-member-canonical-indices/hash artifact))
        "canonical-indices must build for unkeyed rounds")))

(deftest build-canonical-indices-rejects-duplicate-keys
  (let [bad-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:review-member/key 0, :researcher/id "b", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (round-from-members bad-members)))))

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
        bad-entries [{:researcher/id "z" :review-member/index 5}
                     {:researcher/id "a" :review-member/index 7}
                     {:researcher/id "b" :review-member/index 9}]
        bad (assoc artifact :review-member/canonical-indices bad-entries
                   :review-member/count 3
                   :review-member-canonical-indices/hash nil)
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"non-dense" %) (:errors result)))))

(deftest validate-canonical-indices-rejects-hash-corruption
  (let [artifact (ci/build-canonical-indices (make-keyed-round))
        bad (assoc artifact :review-member-canonical-indices/hash
                   "sha256:0000000000000000000000000000000000000000000000000000000000000000")
        result (ci/validate-canonical-indices bad)]
    (is (not (:valid? result)))
    (is (some #(re-find #"hash mismatch" %) (:errors result)))))

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
        round-b (round-from-members [{:review-member/key 0, :researcher/id "x", :role :model-steward}
                                     {:review-member/key 1, :researcher/id "y", :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id "z", :role :adversarial-reviewer}])
        artifact (ci/build-canonical-indices round-a)
        result (ci/verify-canonical-indices artifact round-b)]
    (is (= :round-mismatch (:status result)))
    (is (not (empty? (:errors result))))))

(deftest verify-canonical-indices-detects-member-substitution
  (let [original-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                          {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                          {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
        ;; x > c lexically, so sorted order is a(0), b(1), c(2) for original
        ;; and a(0), c(1), x(2) for sub — different review-round identity
        sub-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                     {:review-member/key 1, :researcher/id "c", :role :independent-reproducer}
                     {:review-member/key 2, :researcher/id "x", :role :adversarial-reviewer}]
        round-orig (round-from-members original-members)
        round-sub (round-from-members sub-members)
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
    (is (= :hash-mismatch (:status result)))))

(deftest verify-canonical-indices-across-keyed-and-unkeyed
  (let [unkeyed (make-unkeyed-round)
        keyed (make-keyed-round)
        artifact (ci/build-canonical-indices keyed)
        ;; Both rounds have the same three researchers — verification must succeed
        result (ci/verify-canonical-indices artifact unkeyed)]
    (is (= :valid (:status result))
        "verification must succeed when keyed artifact matches unkeyed round with same researchers")))

(deftest verify-canonical-indices-detects-reordered-entries
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)
        original-entries (:review-member/canonical-indices artifact)
        reordered (vec (reverse original-entries))
        tampered (assoc artifact :review-member/canonical-indices reordered
                        :review-member-canonical-indices/hash nil)
        result (ci/verify-canonical-indices tampered round)]
    (is (= :canonical-indices-derived-mapping-mismatch (:status result)))))

;; ── Derived-index tests ─────────────────────────────────────────────────────

(deftest derived-index-lookup
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= 0 (ci/derived-index artifact "researcher-a")))
    (is (= 1 (ci/derived-index artifact "researcher-b")))
    (is (= 2 (ci/derived-index artifact "researcher-c")))
    (is (nil? (ci/derived-index artifact "nonexistent")))))

(deftest derived-index-equals-review-member-index
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= (ci/derived-index artifact "researcher-a")
           (ci/review-member-index artifact "researcher-a")))
    (is (= (ci/derived-index artifact "researcher-b")
           (ci/review-member-index artifact "researcher-b")))
    (is (= (ci/derived-index artifact "researcher-c")
           (ci/review-member-index artifact "researcher-c")))))

;; ── Indices-hash tests ──────────────────────────────────────────────────────

(deftest indices-hash-present-in-artifact
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (some? (:review-member/indices-hash artifact))
        "artifact must carry :review-member/indices-hash")
    (is (re-matches #"sha256:[0-9a-f]{64}" (:review-member/indices-hash artifact)))))

(deftest indices-hash-stable-for-identical-entries
  (let [a (ci/build-canonical-indices (make-keyed-round))
        b (ci/build-canonical-indices (make-keyed-round))]
    (is (= (:review-member/indices-hash a) (:review-member/indices-hash b)))))

(deftest indices-hash-changes-when-entries-change
  (let [round-a (round-from-members [{:researcher/id "a", :role :model-steward}
                                     {:researcher/id "b", :role :independent-reproducer}
                                     {:researcher/id "c", :role :adversarial-reviewer}])
        round-b (round-from-members [{:researcher/id "a", :role :model-steward}
                                     {:researcher/id "x", :role :independent-reproducer}
                                     {:researcher/id "c", :role :adversarial-reviewer}])]
    (is (not= (:review-member/indices-hash (ci/build-canonical-indices round-a))
              (:review-member/indices-hash (ci/build-canonical-indices round-b))))))

(deftest indices-hash-empty-returns-nil
  (is (nil? (ci/indices-hash [])) "empty entries must return nil for indices-hash"))

;; ── Indices-hash hardening tests ───────────────────────────────────────────

(deftest indices-hash-domain-separated-from-full-artifact-tag
  (testing "indices-hash must use its own domain tag, not the full-artifact tag"
    (let [round (make-keyed-round)
          entries (:review-member/canonical-indices (ci/build-canonical-indices round))]
      (is (not= (ci/indices-hash entries)
                (str "sha256:" (hc/domain-hash :review-member-canonical-indices entries)))
          "hashing entries under the entries domain tag must differ from the full-artifact tag"))))

(deftest tampered-indices-hash-detected
  (testing "a tampered :review-member/indices-hash (not recomputed) must be detected"
    (let [round (make-keyed-round)
          artifact (ci/build-canonical-indices round)
          tampered (assoc artifact :review-member/indices-hash
                          "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
          result (ci/verify-canonical-indices tampered round)]
      (is (false? (:indices-hash-valid? result))
          ":indices-hash-valid? must be false for a tampered indices-hash")
      (is (= :hash-mismatch (:status result))
          "tampered indices-hash is part of the full preimage, so status is :hash-mismatch"))))

(deftest recomputed-self-consistent-wrong-mapping-rejected
  (testing "a self-consistent rehashed artifact with wrong mapping must still be rejected"
    (let [round (make-keyed-round)
          artifact (ci/build-canonical-indices round)
          entries (:review-member/canonical-indices artifact)
          ;; Swap two entries
          swapped (vec (assoc (vec (assoc entries 0 (nth entries 1))) 1 (nth entries 0)))
          ;; Recompute BOTH the indented indices-hash and the full artifact hash
          ;; so the artifact is internally self-consistent (passes its own hash checks).
          ;; The full-hash preimage must already carry the recomputed indices-hash.
          ihash (ci/indices-hash swapped)
          preimage (-> artifact
                       (assoc :review-member/canonical-indices swapped)
                       (assoc :review-member/indices-hash ihash)
                       (dissoc :review-member-canonical-indices/hash))
          self-consistent (assoc preimage :review-member-canonical-indices/hash
                                 (str "sha256:" (hc/domain-hash
                                                 :review-member-canonical-indices preimage)))
          result (ci/verify-canonical-indices self-consistent round)]
      ;; Even though the artifact is internally self-consistent, the verifier
      ;; must rederive the mapping from the round and reject the wrong ordering.
      (is (= :canonical-indices-derived-mapping-mismatch (:status result))
          "self-consistent but wrong mapping must be rejected by semantic rederivation")
      (is (false? (:derived-mapping-valid? result))
          "derived-mapping-valid? must be false")
      (is (:artifact-hash-valid? result)
          "artifact self-hash must be valid (it was recomputed)")
      (is (:indices-hash-valid? result)
          "indices-hash must be valid (it was recomputed)"))))

;; ── Lookup tests ────────────────────────────────────────────────────────────

(deftest review-member-index-lookup
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= 0 (ci/review-member-index artifact "researcher-a")))
    (is (= 1 (ci/review-member-index artifact "researcher-b")))
    (is (= 2 (ci/review-member-index artifact "researcher-c")))
    (is (nil? (ci/review-member-index artifact "nonexistent")))))

(deftest review-member-at-index-lookup
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= "researcher-a" (:researcher/id (ci/review-member-at-index artifact 0))))
    (is (= "researcher-b" (:researcher/id (ci/review-member-at-index artifact 1))))
    (is (= "researcher-c" (:researcher/id (ci/review-member-at-index artifact 2))))
    (is (nil? (ci/review-member-at-index artifact -1)))
    (is (nil? (ci/review-member-at-index artifact 99)))))

;; ── Round-trip tests ────────────────────────────────────────────────────────

(deftest builder-validator-verifier-round-trip
  (let [round (make-keyed-round)
        artifact (ci/build-canonical-indices round)]
    (is (:valid? (ci/validate-canonical-indices artifact)))
    (let [v-result (ci/verify-canonical-indices artifact round)]
      (is (= :valid (:status v-result)))
      (is (every? :match? (:checks v-result))))))

;; ── Hash stability tests ────────────────────────────────────────────────────

;; ── Researcher-ID type and ordering tests ──────────────────────────────────

(deftest empty-string-researcher-id-rejected
  (testing "empty string researcher-id must be rejected"
    (let [bad-members [{:review-member/key 0, :researcher/id "", :role :model-steward}
                       {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                       {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ci/build-canonical-indices (round-from-members bad-members)))
          "empty string researcher-id must be rejected"))))

(deftest lexical-ordering-case-sensitive
  (testing "case-sensitive lexicographic ordering"
    (let [members [{:review-member/key 0, :researcher/id "B", :role :model-steward}
                   {:review-member/key 1, :researcher/id "a", :role :independent-reproducer}
                   {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
          round (round-from-members members)
          artifact (ci/build-canonical-indices round)
          entries (:review-member/canonical-indices artifact)]
      ;; "B" < "a" in Unicode code-point order (uppercase before lowercase)
      (is (= "B" (:researcher/id (nth entries 0))))
      (is (= "a" (:researcher/id (nth entries 1))))
      (is (= "c" (:researcher/id (nth entries 2)))))))

(deftest lexical-ordering-unicode
  (testing "Unicode code-point lexicographic ordering"
    (let [members [{:review-member/key 0, :researcher/id "e", :role :model-steward}
                   {:review-member/key 1, :researcher/id "z", :role :independent-reproducer}
                   {:review-member/key 2, :researcher/id "é", :role :adversarial-reviewer}]
          round (round-from-members members)
          artifact (ci/build-canonical-indices round)
          entries (:review-member/canonical-indices artifact)]
      ;; "e" (U+0065) < "z" (U+007A) < "é" (U+00E9) in Unicode code-point order
      (is (= "e" (:researcher/id (nth entries 0))))
      (is (= "z" (:researcher/id (nth entries 1))))
      (is (= "é" (:researcher/id (nth entries 2)))))))

(deftest hash-stable-for-identical-round
  (let [round (make-keyed-round)]
    (is (= (:review-member-canonical-indices/hash (ci/build-canonical-indices round))
           (:review-member-canonical-indices/hash (ci/build-canonical-indices round))))))

(deftest hash-changes-when-member-identity-changes
  (let [round-a (round-from-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                                     {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}])
        ;; "different" > "c" lexically → sorted order: a(0), c(1), different(2)
        round-b (round-from-members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                                     {:review-member/key 1, :researcher/id "c", :role :independent-reproducer}
                                     {:review-member/key 2, :researcher/id "different", :role :adversarial-reviewer}])]
    (is (not= (:review-member-canonical-indices/hash (ci/build-canonical-indices round-a))
              (:review-member-canonical-indices/hash (ci/build-canonical-indices round-b))))))

;; ── Production lifecycle integration test ───────────────────────────────────

;; ── Representation-equivalence tests ────────────────────────────────────────

(deftest keyed-and-unkeyed-representations-equivalent
  (testing "keyed and unkeyed representations of the same round produce identical outputs"
    (let [keyed-round (make-keyed-round)
          unkeyed-round (make-unkeyed-round)]
      ;; Same review-round hash (identity excludes keys)
      (is (= (:review-round/id keyed-round) (:review-round/id unkeyed-round))
          "keyed and unkeyed review-round hashes must be equal")
      ;; Same canonical-indices artifact
      (let [ci-keyed (ci/build-canonical-indices keyed-round)
            ci-unkeyed (ci/build-canonical-indices unkeyed-round)]
        (is (= (:review-member/canonical-indices ci-keyed)
               (:review-member/canonical-indices ci-unkeyed))
            "canonical entries must be identical regardless of key presence")
        (is (= (:review-member-canonical-indices/hash ci-keyed)
               (:review-member-canonical-indices/hash ci-unkeyed))
            "artifact hashes must be identical"))
      ;; Same certificate structure (no mode selection by key presence)
      (let [reports [{:researcher/id "researcher-a" :researcher-run-report/outcome-hash "h1"
                      :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh1"}
                     {:researcher/id "researcher-b" :researcher-run-report/outcome-hash "h2"
                      :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh2"}
                     {:researcher/id "researcher-c" :researcher-run-report/outcome-hash "h3"
                      :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh3"}]
            positions [{:researcher/id "researcher-a" :position/hash "ph1" :position/outcome-hash "h1"
                        :benchmark/content-root "sha256:abc"}
                       {:researcher/id "researcher-b" :position/hash "ph2" :position/outcome-hash "h2"
                        :benchmark/content-root "sha256:abc"}
                       {:researcher/id "researcher-c" :position/hash "ph3" :position/outcome-hash "h3"
                        :benchmark/content-root "sha256:abc"}]
            cert-keyed (cert/build-certificate {:review-round keyed-round
                                                :reports reports :positions positions})
            cert-unkeyed (cert/build-certificate {:review-round unkeyed-round
                                                  :reports reports :positions positions})]
        ;; Both must have same structure (both include canonical-indices)
        (is (contains? cert-keyed :review-member-canonical-indices/hash))
        (is (contains? cert-unkeyed :review-member-canonical-indices/hash))
        ;; Every member position must have :review-member/index
        (doseq [mp (:member-positions cert-keyed)]
          (is (contains? mp :review-member/index)))
        (doseq [mp (:member-positions cert-unkeyed)]
          (is (contains? mp :review-member/index)))
        ;; Both certificates have the same hash (same inputs)
        (let [fk (cert/finalise-certificate! cert-keyed)
              fu (cert/finalise-certificate! cert-unkeyed)]
          (is (= (:certificate/hash fk) (:certificate/hash fu))
              "keyed and unkeyed representations must produce identical certificate hashes"))))))

;; ── Member-bit-width tests ───────────────────────────────────────────────────

(deftest member-bit-width-three-member-artifact
  (let [artifact (ci/build-canonical-indices (make-keyed-round))]
    (is (= 2 (ci/member-bit-width artifact))
        "3-member artifact must have bit-width 2")))

(deftest member-bit-width-nil-for-empty-artifact
  (let [artifact {:review-member/count 0 :review-member/canonical-indices []}]
    (is (nil? (ci/member-bit-width artifact))
        "empty artifact must return nil")))

;; ── Full lifecycle: round → CI → certificate → finalise → validate → resolve → verify ──

(deftest full-lifecycle-round-to-verified-certificate
  (testing "complete production lifecycle with non-canonical input order and valid key assertions"
    (let [;; 1. Researchers in deliberately non-canonical input order.
          ;;    Keys match DERIVED indices (researcher-id sort), not input position.
          ;;    Sorted by id: "a"(0), "m"(1), "z"(2).
          members [{:researcher/id "z", :review-member/key 2, :role :model-steward}
                   {:researcher/id "a", :review-member/key 0, :role :independent-reproducer}
                   {:researcher/id "m", :review-member/key 1, :role :adversarial-reviewer}]
          round (rr/build-review-round
                 {:benchmark/content-root "sha256:abc"
                  :review-round/purpose :model-admission
                  :review-round/members members
                  :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                  :review-round/policy-root "sha256:policy"})

          ;; 2. Build canonical-indices artifact
          artifact (ci/build-canonical-indices round)
          artifact-hash (:review-member-canonical-indices/hash artifact)

          ;; 3. Validate artifact
          validation (ci/validate-canonical-indices artifact)

          ;; Assert derived-index and review-member-index agree for all members
          da (ci/derived-index artifact "a")
          db (ci/derived-index artifact "m")
          dz (ci/derived-index artifact "z")
          ra (ci/review-member-index artifact "a")
          rm (ci/review-member-index artifact "m")
          rz (ci/review-member-index artifact "z")

          ;; 4. Build certificate (commits artifact hash)
          reports [{:researcher/id "a" :researcher-run-report/outcome-hash "h1"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh1"}
                   {:researcher/id "m" :researcher-run-report/outcome-hash "h2"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh2"}
                   {:researcher/id "z" :researcher-run-report/outcome-hash "h3"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh3"}]
          positions [{:researcher/id "a" :position/hash "ph1" :position/outcome-hash "h1"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "m" :position/hash "ph2" :position/outcome-hash "h2"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "z" :position/hash "ph3" :position/outcome-hash "h3"
                      :benchmark/content-root "sha256:abc"}]
          certificate (cert/build-certificate
                       {:review-round round :canonical-indices artifact
                        :reports reports :positions positions})
          cert-committed-hash (:review-member-canonical-indices/hash certificate)

          ;; 5. Finalise certificate
          finalised (cert/finalise-certificate! certificate)

          ;; 6. Validate finalised certificate
          cert-validation (cert/validate-certificate finalised)

          ;; 7. Resolve artifact by committed hash (local test store)
          test-store {artifact-hash artifact}
          resolved (get test-store (:review-member-canonical-indices/hash finalised))

          ;; 8. Verify resolved artifact against original round
          verification (ci/verify-canonical-indices resolved round)]

      ;; === Assertions ===

      ;; 3a. Canonical-indices validation succeeds
      (is (:valid? validation)
          (str "canonical-indices validation failed: " (:errors validation)))

      ;; 3b. derived-index and review-member-index agree for all members
      (is (= da ra) "derived-index and review-member-index must agree for researcher a")
      (is (= db rm) "derived-index and review-member-index must agree for researcher m")
      (is (= dz rz) "derived-index and review-member-index must agree for researcher z")

      ;; 3c. Entry order is by researcher-id, not input order
      (let [entries (:review-member/canonical-indices artifact)]
        (is (= "a" (:researcher/id (nth entries 0))))
        (is (= "m" (:researcher/id (nth entries 1))))
        (is (= "z" (:researcher/id (nth entries 2)))))

      ;; 4. Certificate commits the exact artifact hash
      (is (= artifact-hash cert-committed-hash)
          "certificate must commit the exact artifact hash")

      ;; 5. Finalised certificate retains the same canonical-indices hash
      (is (= (:review-member-canonical-indices/hash finalised) artifact-hash)
          "finalised certificate must retain the canonical-indices hash")

      ;; 5b. Finalised certificate has a computed hash
      (is (some? (:certificate/hash finalised))
          "finalised certificate must have :certificate/hash")
      (is (re-matches #"sha256:[0-9a-f]{64}" (:certificate/hash finalised))
          "certificate hash must be a valid sha256 reference")

      ;; 6. Finalised certificate validation succeeds
      (is (:valid? cert-validation)
          (str "finalised certificate validation failed: " (:errors cert-validation)))

      ;; 7. Resolved artifact matches original
      (is (some? resolved) "must resolve artifact by committed hash")
      (is (= (:review-member-canonical-indices/hash resolved) artifact-hash)
          "resolved artifact must have the same hash")

      ;; 8. Artifact verification succeeds with all check dimensions
      (is (= :valid (:status verification))
          (str "artifact verification failed: " (:errors verification)))
      (is (:round-binding-valid? verification))
      (is (:derived-mapping-valid? verification))
      (is (:artifact-hash-valid? verification))
      (is (:indices-hash-valid? verification)))))

;; ── End-to-end lifecycle: round → artifact → certificate → verification ─────

(deftest verify-from-packaged-artifact
  (testing "a packaged artifact resolved by hash must verify against the source round"
    (let [round (make-keyed-round)
          artifact (ci/build-canonical-indices round)
          artifact-hash (:review-member-canonical-indices/hash artifact)
          ;; Simulate content-addressed storage: store artifact by hash
          store {artifact-hash artifact}
          ;; Certificate commits only the artifact hash
          reports [{:researcher/id "researcher-a" :researcher-run-report/outcome-hash "h1"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh1"}
                   {:researcher/id "researcher-b" :researcher-run-report/outcome-hash "h2"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh2"}
                   {:researcher/id "researcher-c" :researcher-run-report/outcome-hash "h3"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh3"}]
          positions [{:researcher/id "researcher-a" :position/hash "ph1" :position/outcome-hash "h1"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "researcher-b" :position/hash "ph2" :position/outcome-hash "h2"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "researcher-c" :position/hash "ph3" :position/outcome-hash "h3"
                      :benchmark/content-root "sha256:abc"}]
          cert (cert/build-certificate {:review-round round :reports reports :positions positions})
          committed-hash (:review-member-canonical-indices/hash cert)]
      ;; The committed hash must match the artifact
      (is (= artifact-hash committed-hash))
      ;; Resolve the artifact from content-addressed store by committed hash
      (let [resolved (get store committed-hash)
            verification (ci/verify-canonical-indices resolved round)]
        (is (= :valid (:status verification))
            "artifacts resolved from store must verify against source round")))))

(deftest round-to-canonical-indices-to-certificate-lifecycle
  (testing "full lifecycle: round creation → canonical indices → certificate verification"
    (let [round (make-keyed-round)
          _ (is (rr/round-valid? round))
          artifact (ci/build-canonical-indices round)
          _ (is (:valid? (ci/validate-canonical-indices artifact)))
          ;; Verify artifact against round
          verification (ci/verify-canonical-indices artifact round)
          _ (is (= :valid (:status verification)))
          ;; Build mock reports and positions for certificate test
          reports [{:researcher/id "researcher-a" :researcher-run-report/outcome-hash "h1"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh1"}
                   {:researcher/id "researcher-b" :researcher-run-report/outcome-hash "h2"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh2"}
                   {:researcher/id "researcher-c" :researcher-run-report/outcome-hash "h3"
                    :benchmark/content-root "sha256:abc" :researcher-run-report/hash "rh3"}]
          positions [{:researcher/id "researcher-a" :position/hash "ph1" :position/outcome-hash "h1"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "researcher-b" :position/hash "ph2" :position/outcome-hash "h2"
                      :benchmark/content-root "sha256:abc"}
                     {:researcher/id "researcher-c" :position/hash "ph3" :position/outcome-hash "h3"
                      :benchmark/content-root "sha256:abc"}]
          certificate (cert/build-certificate
                       {:review-round round :canonical-indices artifact
                        :reports reports :positions positions})]
      (is (some? certificate))
      (is (= (:review-round/id round) (:review-round/id certificate)))
      (is (= 3 (count (:member-positions certificate))))
      ;; Each member position must have a :review-member/index field
      (doseq [mp (:member-positions certificate)]
        (is (contains? mp :review-member/index)
            (str "member position for " (:researcher/id mp) " must have :review-member/index"))
        (is (integer? (:review-member/index mp))
            (str ":review-member/index must be an integer for " (:researcher/id mp)))))))
