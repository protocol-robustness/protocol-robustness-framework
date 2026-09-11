(ns resolver-sim.evidence.attestation-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-lifecycle :as alc]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.revocation :as rev]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim]}]
  (att/build-attestation (attestor) (subject) (or claim :verified)
                         {:signed-at (or signed-at "2025-01-01T00:00:00Z")}))

(deftest with-fresh-registry-isolates-lifecycle
  (alc/activate-attestation! "id-1")
  (is (some? (alc/lifecycle-record "id-1")))
  (alc/with-fresh-registry
    (is (nil? (alc/lifecycle-record "id-1")))
    (alc/activate-attestation! "id-2")
    (is (some? (alc/lifecycle-record "id-2"))))
  (is (some? (alc/lifecycle-record "id-1")))
  (is (nil? (alc/lifecycle-record "id-2"))))

(deftest activate-sets-state-to-active
  (alc/with-fresh-registry
    (let [rec (alc/activate-attestation! "test-id")]
      (is (= :active (:state rec)))
      (is (string? (:updated-at rec))))))

(deftest activate-records-supersedes
  (alc/with-fresh-registry
    (alc/activate-attestation! "old-id")
    (let [rec (alc/activate-attestation! "new-id" {:supersedes ["old-id"]})]
      (is (= ["old-id"] (:supersedes rec))))))

(deftest activate-overwrites-existing
  (alc/with-fresh-registry
    (alc/activate-attestation! "id-1")
    (alc/activate-attestation! "id-1" {:reason "Updated"})
    (is (= "Updated" (:reason (alc/lifecycle-record "id-1"))))))

(deftest supersede-marks-old-as-superseded
  (alc/with-fresh-registry
    (alc/activate-attestation! "old")
    (alc/activate-attestation! "new")
    (let [result (alc/supersede-attestation! "old" "new" "Updated findings")]
      (is (= :superseded (:state (:old-state result))))
      (is (= "new" (:superseded-by (:old-state result))))
      (is (= :active (:state (:new-state result))))
      (is (= ["old"] (:supersedes (:new-state result)))))))

(deftest supersede-works-without-prior-activation
  (alc/with-fresh-registry
    (let [result (alc/supersede-attestation! "old" "new" "Direct supersession")]
      (is (= :superseded (:state (:old-state result))))
      (is (= :active (:state (:new-state result)))))))

(deftest supersede-appends-supersedes
  (alc/with-fresh-registry
    (alc/supersede-attestation! "old-1" "new" "first")
    (alc/supersede-attestation! "old-2" "new" "second")
    (is (= #{"old-1" "old-2"} (set (:supersedes (alc/lifecycle-record "new")))))))

(deftest revoke-sets-state-to-revoked
  (alc/with-fresh-registry
    (alc/activate-attestation! "test-id")
    (let [rec (alc/revoke-attestation! "test-id" "Key compromised")]
      (is (= :revoked (:state rec)))
      (is (= "Key compromised" (:reason rec))))))

(deftest revoke-works-without-prior-activation
  (alc/with-fresh-registry
    (let [rec (alc/revoke-attestation! "unknown-id" "Direct revocation")]
      (is (= :revoked (:state rec))))))

(deftest state-returns-active-by-default
  (alc/with-fresh-registry
    (let [s (alc/attestation-state "never-seen")]
      (is (= :active (:state s)))
      (is (false? (:revoked? s))))))

(deftest state-returns-active-after-activation
  (alc/with-fresh-registry
    (alc/activate-attestation! "id-1")
    (is (= :active (:state (alc/attestation-state "id-1"))))))

(deftest state-returns-superseded
  (alc/with-fresh-registry
    (alc/supersede-attestation! "old" "new" "test")
    (is (= :superseded (:state (alc/attestation-state "old"))))))

(deftest state-returns-revoked
  (alc/with-fresh-registry
    (alc/revoke-attestation! "id-1" "test")
    (is (= :revoked (:state (alc/attestation-state "id-1"))))))

(deftest state-detects-revocation-from-revocation-registry
  (rev/with-fresh-registry
    (rev/register-revocation! (rev/build-revocation "id-1" :key-compromised))
    (let [s (alc/attestation-state "id-1" {:revocation-resolver rev/attestation-revoked?})]
      (is (= :revoked (:state s)))
      (is (true? (:revoked? s))))))

(deftest state-uses-custom-default
  (alc/with-fresh-registry
    (let [s (alc/attestation-state "unknown" {:default-state :unknown})]
      (is (= :unknown (:state s))))))

(deftest active-returns-true-for-active
  (alc/with-fresh-registry
    (alc/activate-attestation! "id-1")
    (is (true? (alc/attestation-active? "id-1")))))

(deftest active-returns-false-for-superseded
  (alc/with-fresh-registry
    (alc/supersede-attestation! "old" "new" "test")
    (is (false? (alc/attestation-active? "old")))))

(deftest active-returns-false-for-revoked
  (alc/with-fresh-registry
    (alc/revoke-attestation! "id-1" "test")
    (is (false? (alc/attestation-active? "id-1")))))

(deftest chain-returns-single-for-standalone
  (alc/with-fresh-registry
    (alc/activate-attestation! "id-1")
    (let [chain (alc/attestation-supersession-chain "id-1")]
      (is (= "id-1" (:id chain)))
      (is (= [] (:predecessors chain)))
      (is (= [] (:successors chain))))))

(deftest chain-traces-predecessors
  (alc/with-fresh-registry
    (alc/supersede-attestation! "v1" "v2" "Updated")
    (alc/supersede-attestation! "v2" "v3" "Refined")
    (let [chain (alc/attestation-supersession-chain "v3")]
      (is (= 2 (count (:predecessors chain))))
      (is (= "v2" (:attestation-id (first (:predecessors chain))))))))

(deftest chain-traces-successors
  (alc/with-fresh-registry
    (alc/supersede-attestation! "v1" "v2" "Updated")
    (let [chain (alc/attestation-supersession-chain "v1")]
      (is (= 1 (count (:successors chain))))
      (is (= "v2" (:attestation-id (first (:successors chain))))))))

(deftest chain-traces-full-path
  (alc/with-fresh-registry
    (alc/supersede-attestation! "v1" "v2" "First update")
    (alc/supersede-attestation! "v2" "v3" "Second update")
    (let [chain (alc/attestation-supersession-chain "v2")]
      (is (= :superseded (:state chain)))
      (is (= 1 (count (:predecessors chain))))
      (is (= 1 (count (:successors chain)))))))

(deftest find-superseded-returns-ids
  (alc/with-fresh-registry
    (alc/supersede-attestation! "old-1" "new" "test")
    (alc/supersede-attestation! "old-2" "new" "test")
    (let [result (alc/find-superseded-by "new")]
      (is (= #{"old-1" "old-2"} (set result))))))

(deftest find-superseded-returns-empty
  (alc/with-fresh-registry
    (is (= [] (alc/find-superseded-by "unknown-id")))))

(deftest cascade-supersession-same-subject
  (alc/with-fresh-registry
    (ar/with-fresh-registry
      (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z")
            a2 (build-a :signed-at "2025-01-02T00:00:00Z" :claim :approved)
            a3 (build-a :signed-at "2025-01-03T00:00:00Z" :claim :reproduced)]
        (ar/register-attestation! a1)
        (ar/register-attestation! a2)
        (ar/register-attestation! a3)
        (alc/activate-attestation! (:attestation/id a1))
        (alc/activate-attestation! (:attestation/id a2))
        (let [finder (fn [id] (ar/find-attestation id))
              all [(ar/find-attestation (:attestation/id a1))
                   (ar/find-attestation (:attestation/id a2))
                   (ar/find-attestation (:attestation/id a3))]
              result (alc/cascade-supersession!
                      (:attestation/id a1) "new-id" finder "Cascade test"
                      {:all-attestations (remove nil? all)})]
          (is (= 2 (:cascaded-count result)))
          (is (= #{(:attestation/id a2) (:attestation/id a3)}
                 (set (:cascaded result)))))))))

(deftest cascade-supersession-handles-unknown-attestation
  (alc/with-fresh-registry
    (let [result (alc/cascade-supersession!
                  "unknown-id" "new-id" (fn [_] nil) "test"
                  {:all-attestations []})]
      (is (= 0 (:cascaded-count result)))
      (is (= 1 (count (:errors result)))))))
