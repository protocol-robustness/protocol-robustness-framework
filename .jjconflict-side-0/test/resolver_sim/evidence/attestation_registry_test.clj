(ns resolver-sim.evidence.attestation-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.chain :as chain]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})

(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- claim-subject [] {:type :claim :claim-id :accounting-consistency})

(defn- build-a
  [& {:keys [claim subject attestor signed-at claim-id provenance signing-key-id]
      :or {claim :verified, subject (subject), attestor (attestor)}}]
  (att/build-attestation attestor subject claim
                         (cond-> {}
                           signed-at (assoc :signed-at signed-at)
                           claim-id (assoc :claim-id claim-id)
                           provenance (assoc :provenance provenance)
                           signing-key-id (assoc :signing-key-id signing-key-id))))

;; ── Registration ─────────────────────────────────────────────────────────────

(deftest register-attestation-stores-by-hash
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (is (= a (ar/register-attestation! a))
          "register-attestation! returns the attestation")
      (is (= a (ar/find-attestation id))
          "can look up by :attestation/id"))))

(deftest register-attestation-idempotent
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          id (:attestation/id a)]
      (ar/register-attestation! a)
      (ar/register-attestation! a)
      (is (= a (ar/find-attestation id))
          "registering same attestation twice is idempotent"))))

(deftest register-multiple-attestations
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          a2 (build-a :signed-at "2025-01-02T00:00:00Z" :claim :approved)]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (is (= 2 (count (ar/all-attestations)))))))

;; ── Lookup — not found ───────────────────────────────────────────────────────

(deftest find-attestation-returns-nil-for-unknown
  (ar/with-fresh-registry
    (is (nil? (ar/find-attestation "nonexistent-hash")))
    (is (= [] (ar/all-attestations)))))

;; ── Query by attestor ────────────────────────────────────────────────────────

(deftest find-by-attestor
  (ar/with-fresh-registry
    (let [attestor-a {:type :ci-runner :id :attestor-a}
          attestor-b {:type :ci-runner :id :attestor-b}
          a1 (build-a :attestor attestor-a :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :attestor attestor-a :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :attestor attestor-b :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [from-a (ar/find-attestations-by-attestor :attestor-a)
            from-b (ar/find-attestations-by-attestor :attestor-b)]
        (is (= 2 (count from-a)))
        (is (= #{:verified :approved} (set (map :attestation/claim-result from-a))))
        (is (= 1 (count from-b)))
        (is (= :verified (:attestation/claim-result (first from-b))))))))

(deftest find-by-attestor-returns-empty-for-unknown
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (is (= [] (ar/find-attestations-by-attestor :nonexistent))))))

;; ── Query by subject ─────────────────────────────────────────────────────────

(deftest find-by-subject
  (ar/with-fresh-registry
    (let [subj-a {:type :evidence-node :hash "sha256:aaa"}
          subj-b {:type :evidence-node :hash "sha256:bbb"}
          a1 (build-a :subject subj-a :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :subject subj-a :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :subject subj-b :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [for-a (ar/find-attestations-by-subject "sha256:aaa")
            for-b (ar/find-attestations-by-subject "sha256:bbb")]
        (is (= 2 (count for-a)))
        (is (= 1 (count for-b)))
        (is (= "sha256:bbb" (:attestation/subject-hash (first for-b))))))))

(deftest find-by-subject-returns-empty-for-unknown
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (is (= [] (ar/find-attestations-by-subject "sha256:nonexistent"))))))

(deftest find-by-subject-claim-type
  (ar/with-fresh-registry
    (let [a (build-a :subject (claim-subject) :claim :verified
                     :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (let [found (ar/find-attestations-by-subject :accounting-consistency)]
        (is (= 1 (count found)))
        (is (= :claim (:attestation/subject-kind (first found))))))))

;; ── Query by claim result ────────────────────────────────────────────────────

(deftest find-by-claim-result
  (ar/with-fresh-registry
    (let [a1 (build-a :claim :verified :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :claim :approved :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :claim :verified :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [verified (ar/find-attestations-by-claim-result :verified)
            approved (ar/find-attestations-by-claim-result :approved)]
        (is (= 2 (count verified)))
        (is (= 1 (count approved)))
        (is (= 0 (count (ar/find-attestations-by-claim-result :reproduced))))))))

(deftest find-by-claim-result-returns-empty-for-unused
  (ar/with-fresh-registry
    (is (= [] (ar/find-attestations-by-claim-result :rejected)))))

;; ── Query by claim id ────────────────────────────────────────────────────────

(deftest find-by-claim-id
  (ar/with-fresh-registry
    (let [a1 (build-a :claim-id :claim/consistency :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :claim-id :claim/consistency :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :claim-id :claim/other :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [consistency (ar/find-attestations-by-claim-id :claim/consistency)
            other (ar/find-attestations-by-claim-id :claim/other)]
        (is (= 2 (count consistency)))
        (is (= 1 (count other)))
        (is (= [] (ar/find-attestations-by-claim-id :claim/absent)))))))

;; ── all-attestations sorting ─────────────────────────────────────────────────

(deftest all-attestations-sorted-by-signed-at
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-03-01T00:00:00Z")
          a2 (build-a :signed-at "2025-01-01T00:00:00Z")
          a3 (build-a :signed-at "2025-02-01T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [all (ar/all-attestations)]
        (is (= 3 (count all)))
        (is (= ["2025-01-01T00:00:00Z"
                "2025-02-01T00:00:00Z"
                "2025-03-01T00:00:00Z"]
               (mapv :attestation/signed-at all)))))))

;; ── clear-attestations! ──────────────────────────────────────────────────────

(deftest clear-attestations-empties-registry
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-02-01T00:00:00Z"))
    (is (= 2 (count (ar/all-attestations))))
    (ar/clear-attestations!)
    (is (= [] (ar/all-attestations)))
    (is (nil? (ar/find-attestation (str (java.util.UUID/randomUUID)))))))

;; ── Registry status ──────────────────────────────────────────────────────────

(deftest registry-status-empty
  (ar/with-fresh-registry
    (let [s (ar/registry-status)]
      (is (= 0 (:count s)))
      (is (:empty? s)))))

(deftest registry-status-with-data
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :claim :verified :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :claim :approved :signed-at "2025-02-01T00:00:00Z"
                                       :attestor {:type :ci-runner :id :other-attestor}))
    (let [s (ar/registry-status)]
      (is (= 2 (:count s)))
      (is (false? (:empty? s)))
      (is (= #{:ci-validation :other-attestor} (set (:attestors s))))
      (is (= {:verified 1, :approved 1} (:claim-results s))))))

;; ── Edge: with-fresh-registry restores outer state ──────────────────────────

(deftest with-fresh-registry-restores-outer
  (ar/clear-attestations!)
  (let [outer (build-a :signed-at "2025-01-01T00:00:00Z")]
    (ar/register-attestation! outer)
    (is (= 1 (count (ar/all-attestations))))
    (ar/with-fresh-registry
      (is (= [] (ar/all-attestations)) "within with-fresh-registry, registry is empty")
      (ar/register-attestation! (build-a :signed-at "2025-02-01T00:00:00Z"))
      (is (= 1 (count (ar/all-attestations))) "within with-fresh-registry, can add"))
    (is (= 1 (count (ar/all-attestations)))
        "after with-fresh-registry, outer state is restored")
    (is (= outer (ar/find-attestation (:attestation/id outer)))
        "after with-fresh-registry, outer attestation is still accessible")))

;; ── Idempotency matrix ─────────────────────────────────────────────────────
;; Each test establishes a precondition, performs a registration, and checks
;; the expected outcome. Tests are independent (each uses with-fresh-registry).

(deftest idempotency-no-entry-valid-content-stores-once
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (is (= a (ar/register-attestation! a)))
      (is (= 1 (count (ar/all-attestations))))
      (is (= a (ar/find-attestation id))))))

(deftest idempotency-same-id-identical-content-noop
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (ar/register-attestation! a)
      (let [before (ar/find-attestation id)]
        (is (= a (ar/register-attestation! a)) "second registration returns the stored value")
        (is (= 1 (count (ar/all-attestations))) "count unchanged")
        (is (= before (ar/find-attestation id)) "stored value unchanged")
        (is (= (:attestation/signed-at before) (:attestation/signed-at (ar/find-attestation id)))
            "timestamp not mutated by re-registration")))))

(deftest idempotency-same-id-different-content-rejected
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a1)
          a2 (assoc a1 :attestation/claim-result :approved)]
      (ar/register-attestation! a1)
      (is (thrown? Exception (ar/register-attestation! a2))
          "registering different canonical content under same ID must be rejected")
      (is (= 1 (count (ar/all-attestations))) "registry count unchanged")
      (is (= a1 (ar/find-attestation id)) "original attestation preserved"))))

(deftest idempotency-same-id-extra-metadata-idempotent
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)
          a-with-meta (assoc a :cached-verification {:pass? true} :resolved-at "2025-06-01T00:00:00Z")]
      (is (= a-with-meta (ar/register-attestation! a-with-meta))
          "first registration returns the stored value")
      (is (= a-with-meta (ar/register-attestation! a))
          "re-registration with identical canonical projection returns existing without mutation")
      (is (= 1 (count (ar/all-attestations))) "count unchanged")
      (is (= a-with-meta (ar/find-attestation id)) "stored value unchanged — no overwrite of extra metadata"))))

(deftest idempotency-extra-incidental-keys-in-first-registration
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)
          a-with-cache (assoc a :cached-verification {:pass? true})]
      (ar/register-attestation! a-with-cache)
      (is (= a-with-cache (ar/register-attestation! a))
          "re-registration with identical canonical projection returns existing without mutation")
      (is (= 1 (count (ar/all-attestations))) "count unchanged")
      (is (= a-with-cache (ar/find-attestation id)) "stored value unchanged — incidental keys preserved"))))

(deftest idempotency-nil-id-rejected-before-mutation
  (ar/with-fresh-registry
    (let [a (-> (build-a :signed-at "2025-01-01T00:00:00Z")
                (dissoc :attestation/id))]
      (is (thrown? Exception (ar/register-attestation! a))
          "nil :attestation/id must be rejected before any atom mutation"))
    (is (= 0 (count (ar/all-attestations))) "registry remains empty")))

(deftest idempotency-malformed-content-rejected
  (ar/with-fresh-registry
    (let [not-an-attestation {:foo "bar"}]
      (is (thrown? Exception (ar/register-attestation! not-an-attestation))
          "non-attestation maps without :attestation/id must be rejected"))
    (is (= 0 (count (ar/all-attestations))) "registry remains empty")))

(deftest idempotency-same-subject-different-attestation-permitted
  (ar/with-fresh-registry
    (let [subj (subject)
          a1 (build-a :subject subj :claim :verified :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :subject subj :claim :approved :signed-at "2025-01-02T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (is (= 2 (count (ar/all-attestations)))
          "different attestations about the same subject are permitted")
      (is (= 2 (count (ar/find-attestations-by-subject (:hash subj))))))))

(deftest idempotency-repeated-registration-preserves-order
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (ar/register-attestation! a)
      (ar/register-attestation! a)
      (ar/register-attestation! a)
      (let [all (ar/all-attestations)]
        (is (= 1 (count all)) "triple registration produces one entry")
        (is (= id (:attestation/id (first all))) "content unchanged")
        (is (= a (first all)) "full equality preserved")))))

(deftest idempotency-different-content-derived-id-permitted
  (ar/with-fresh-registry
    (let [subj (subject)
          a1 (build-a :subject subj :claim :verified :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :subject subj :claim :reproduced :signed-at "2025-01-02T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (is (= 2 (count (ar/all-attestations)))
          "different content-derived IDs are independent entries")
      (is (not= (:attestation/id a1) (:attestation/id a2))
          "content-derived IDs differ when content differs"))))

;; ── Mutation fault-injection matrix ─────────────────────────────────────────
;; Each test documents a mutation boundary, the injected fault, the exact
;; pre-state and post-state, and proves the invariant.
;;
;;   | # | Mutation point       | Fault injected            | Pre-state      | Post-state           | Pass |
;;   |---|----------------------|---------------------------|----------------|----------------------|------|
;;   | 1 | nil-id guard         | nil :attestation/id       | 0 entries      | 0 entries            | ✓    |
;;   | 2 | conflict guard       | different canonical hash  | 1 entry (orig) | 1 entry (orig)       | ✓    |
;;   | 3 | registry swap (new)  | first registration        | 0 entries      | 1 entry (new)        | ✓    |
;;   | 4 | registry swap (dup)  | identical canonical       | 1 entry (orig) | 1 entry (orig)       | ✓    |
;;   | 5 | order preservation   | multi-attestation order   | 0 entries      | sorted by signed-at  | ✓    |
;;   | 6 | chain registration   | register-in-chain? true   | 0 artifacts    | 1 artifact           | ✓    |

(deftest fault-boundary-nil-id-no-mutation
  (ar/with-fresh-registry
    (let [pre-state (ar/all-attestations)
          pre-count (count pre-state)
          a (-> (build-a :signed-at "2025-01-01T00:00:00Z")
                (dissoc :attestation/id))]
      (is (thrown? Exception (ar/register-attestation! a))
          "nil :attestation/id throws")
      (let [post-state (ar/all-attestations)]
        (is (= pre-count (count post-state))
            "registry count unchanged after nil-id rejection")
        (is (= pre-state post-state)
            "registry content unchanged after nil-id rejection")))))

(deftest fault-boundary-conflict-no-mutation
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          id (:attestation/id a1)
          a2 (assoc a1 :attestation/claim-result :approved)]
      (ar/register-attestation! a1)
      (let [pre-state (ar/all-attestations)
            pre-count (count pre-state)]
        (is (thrown? Exception (ar/register-attestation! a2))
            "conflicting canonical content throws")
        (let [post-state (ar/all-attestations)]
          (is (= pre-count (count post-state))
              "registry count unchanged after conflict rejection")
          (is (= pre-state post-state)
              "registry content unchanged after conflict rejection")
          (is (= a1 (ar/find-attestation id))
              "original attestation preserved after conflict rejection"))))))

(deftest fault-boundary-successful-registration
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (is (= [] (ar/all-attestations))
          "pre-state: registry empty")
      (is (= a (ar/register-attestation! a))
          "returns the attestation")
      (let [post-state (ar/all-attestations)]
        (is (= 1 (count post-state))
            "post-state: 1 entry")
        (is (= a (first post-state))
            "post-state: entry is the attestation")
        (is (= a (ar/find-attestation id))
            "post-state: lookup by id returns the attestation")))))

(deftest fault-boundary-no-op-re-registration
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          id (:attestation/id a1)
          a2 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)]
      (ar/register-attestation! a1)
      (let [pre-state (ar/all-attestations)]
        (is (= a1 (first pre-state))
            "pre-state: existing attestation")
        (let [returned (ar/register-attestation! a2)]
          (is (identical? a1 returned)
              "returns existing record without mutation")
          (is (= 1 (count (ar/all-attestations)))
              "count unchanged after idempotent re-registration")
          (is (= a1 (ar/find-attestation id))
              "stored value unchanged after idempotent re-registration")
          (is (= (map :attestation/signed-at pre-state)
                 (map :attestation/signed-at (ar/all-attestations)))
              "order and timestamps unchanged after idempotent re-registration"))))))

(deftest fault-boundary-order-preserved
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-03-01T00:00:00Z")
          a2 (build-a :signed-at "2025-01-01T00:00:00Z")
          a3 (build-a :signed-at "2025-02-01T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [all (ar/all-attestations)]
        (is (= ["2025-01-01T00:00:00Z"
                "2025-02-01T00:00:00Z"
                "2025-03-01T00:00:00Z"]
               (mapv :attestation/signed-at all))
            "all-attestations returns entries sorted by signed-at")
        (is (= 3 (count all))
            "exactly 3 entries in the correct order")))))

(deftest idempotency-proof-no-side-effects
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          id (:attestation/id a)
          a2 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :approved)]
      ;; Register two distinct attestations
      (ar/register-attestation! a)
      (ar/register-attestation! a2)
      (let [pre-count (count (ar/all-attestations))
            pre-order (mapv :attestation/signed-at (ar/all-attestations))
            pre-ids (set (map :attestation/id (ar/all-attestations)))
            pre-attestors (set (map :attestation/attestor-id (ar/all-attestations)))
            pre-subjects (set (map :attestation/subject-hash (ar/all-attestations)))
            pre-first (first (ar/all-attestations))]
        ;; Idempotent re-registration of a (identical canonical)
        (let [returned (ar/register-attestation! a)]
          (is (identical? a returned)
              "returns existing record without copy — no mutation")
          (is (= pre-count (count (ar/all-attestations)))
              "count unchanged — no duplicate entry added")
          (is (= pre-order (mapv :attestation/signed-at (ar/all-attestations)))
              "order unchanged — no re-sort")
          (is (= pre-ids (set (map :attestation/id (ar/all-attestations))))
              "attestation ID set unchanged")
          (is (= pre-attestors (set (map :attestation/attestor-id (ar/all-attestations))))
              "attestors unchanged")
          (is (= pre-subjects (set (map :attestation/subject-hash (ar/all-attestations))))
              "subjects unchanged")
          (is (identical? pre-first (first (ar/all-attestations)))
              "first entry pointer unchanged — no mutation at all")
          (is (identical? a (ar/find-attestation id))
              "lookup by id returns identical object — no copy"))))))

(deftest fault-boundary-chain-registration-auxiliary-only
  (chain/with-fresh-registry
    (ar/with-fresh-registry
      (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
            id (:attestation/id a)]
        (is (= a (ar/register-attestation! a {:register-in-chain? true}))
            "registration succeeds with register-in-chain? true")
        (is (= 1 (count (ar/all-attestations)))
            "registry has exactly 1 entry")
        (is (= a (ar/find-attestation id))
            "attestation is in registry")
        ;; Chain registration is auxiliary — registry is authoritative.
        (let [chain-artifacts (get-in (chain/registry-snapshot) [:artifacts])]
          (is (= 1 (count chain-artifacts))
              "chain has 1 artifact entry")
          (is (= (:id (first chain-artifacts))
                 (str "attestation-" (subs id 0 (min 12 (count id)))))
              "chain artifact matches the attestation"))))))
