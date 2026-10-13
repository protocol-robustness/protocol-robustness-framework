(ns resolver-sim.evidence.attestation-report-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.attestation-report :as arpt]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id signing-key-id]
      :or {claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at (or signed-at "2025-01-01T00:00:00Z")}
                           claim-id (assoc :claim-id claim-id)
                           signing-key-id (assoc :signing-key-id signing-key-id)
                           signing-key-id (assoc :signing-fn (fn [_]
                                                               {:algorithm :ed25519
                                                                :public-key-id signing-key-id
                                                                :signature-bytes "deadbeef"})))))

;; ── find-attestations-by-time-range ─────────────────────────────────────────

(deftest time-range-includes-matching
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-06-01T00:00:00Z")]
      (ar/register-attestation! a)
      (let [result (arpt/find-attestations-by-time-range (ar/all-attestations)
                                                         "2025-01-01T00:00:00Z"
                                                         "2025-12-31T23:59:59Z")]
        (is (= 1 (count result)))
        (is (= (:attestation/id a) (:attestation/id (first result))))))))

(deftest time-range-excludes-outside-range
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (let [result (arpt/find-attestations-by-time-range (ar/all-attestations)
                                                       "2026-01-01T00:00:00Z"
                                                       "2026-12-31T23:59:59Z")]
      (is (= [] result)))))

(deftest time-range-with-unbounded-start
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-06-01T00:00:00Z"))
    (let [result (arpt/find-attestations-by-time-range (ar/all-attestations) nil "2025-12-31T23:59:59Z")]
      (is (= 1 (count result))))))

(deftest time-range-with-unbounded-end
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-06-01T00:00:00Z"))
    (let [result (arpt/find-attestations-by-time-range (ar/all-attestations) "2025-01-01T00:00:00Z" nil)]
      (is (= 1 (count result))))))

(deftest time-range-respects-boundary-inclusive
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-06-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-12-31T23:59:59Z"))
    (testing "start boundary inclusive"
      (is (= 3 (count (arpt/find-attestations-by-time-range (ar/all-attestations)
                                                            "2025-01-01T00:00:00Z"
                                                            "2025-12-31T23:59:59Z")))))
    (testing "exclusive of outside"
      (is (= 2 (count (arpt/find-attestations-by-time-range (ar/all-attestations)
                                                            "2025-06-01T00:00:00Z"
                                                            "2025-12-31T23:59:59Z")))))
    (testing "no matches when range is before"
      (is (= [] (arpt/find-attestations-by-time-range (ar/all-attestations)
                                                      "2024-01-01T00:00:00Z"
                                                      "2024-12-31T23:59:59Z"))))))

;; ── grouped queries ─────────────────────────────────────────────────────────

(deftest group-by-attestor
  (ar/with-fresh-registry
    (let [attestor-a {:type :ci-runner :id :attestor-a}
          attestor-b {:type :ci-runner :id :attestor-b}
          a1 (att/build-attestation attestor-a (subject) :verified {:signed-at "2025-01-01T00:00:00Z"})
          a2 (att/build-attestation attestor-a (subject) :approved {:signed-at "2025-01-02T00:00:00Z"})
          a3 (att/build-attestation attestor-b (subject) :verified {:signed-at "2025-01-03T00:00:00Z"})]
      (ar/register-attestation! a1) (ar/register-attestation! a2) (ar/register-attestation! a3)
      (let [grouped (arpt/attestations-by-attestor-grouped (ar/all-attestations))]
        (is (= 2 (count (get grouped :attestor-a))))
        (is (= 1 (count (get grouped :attestor-b))))))))

(deftest group-by-claim-result
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-03T00:00:00Z" :claim :approved))
    (let [grouped (arpt/attestations-by-claim-result-grouped (ar/all-attestations))]
      (is (= 2 (count (get grouped :verified))))
      (is (= 1 (count (get grouped :approved)))))))

(deftest group-by-subject-kind
  (ar/with-fresh-registry
    (let [evidence-subject {:type :evidence-node :hash "sha256:abc"}
          claim-subject {:type :claim :claim-id :consistency}
          a1 (att/build-attestation (attestor) evidence-subject :verified {:signed-at "2025-01-01T00:00:00Z"})
          a2 (att/build-attestation (attestor) claim-subject :reproduced {:signed-at "2025-01-02T00:00:00Z"})]
      (ar/register-attestation! a1) (ar/register-attestation! a2)
      (let [grouped (arpt/attestations-by-subject-kind-grouped (ar/all-attestations))]
        (is (= 1 (count (get grouped :evidence-node))))
        (is (= 1 (count (get grouped :claim))))))))

(deftest group-by-claim-id
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z" :claim-id :claim/consistency))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim-id :claim/consistency))
    (ar/register-attestation! (build-a :signed-at "2025-01-03T00:00:00Z" :claim-id :claim/completeness))
    (ar/register-attestation! (build-a :signed-at "2025-01-04T00:00:00Z")) ;; no claim-id
    (let [grouped (arpt/attestations-by-claim-id-grouped (ar/all-attestations))]
      (is (= 2 (count (get grouped :claim/consistency))))
      (is (= 1 (count (get grouped :claim/completeness))))
      (is (nil? (get grouped nil)) "nil claim-ids are excluded"))))

;; ── attestation-summary ─────────────────────────────────────────────────────

(deftest summary-empty
  (let [s (arpt/attestation-summary [])]
    (is (= 0 (:total-count s)))
    (is (nil? (:signed-ratio s)))
    (is (nil? (:time-range s)))))

(deftest summary-counts
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim :approved :signing-key-id "key-001"))
    (ar/register-attestation! (build-a :signed-at "2025-01-03T00:00:00Z" :claim :reproduced))
    (let [s (arpt/attestation-summary (ar/all-attestations))]
      (is (= 3 (:total-count s)))
      (is (= 1 (:signed-count s)))
      (is (= 2 (:unsigned-count s))))))

(deftest summary-attestors
  (ar/with-fresh-registry
    (let [attestor-x {:type :ci-runner :id :attestor-x}
          attestor-y {:type :ci-runner :id :attestor-y}]
      (ar/register-attestation! (att/build-attestation attestor-x (subject) :verified {:signed-at "2025-01-01T00:00:00Z"}))
      (ar/register-attestation! (att/build-attestation attestor-x (subject) :approved {:signed-at "2025-01-02T00:00:00Z"}))
      (ar/register-attestation! (att/build-attestation attestor-y (subject) :verified {:signed-at "2025-01-03T00:00:00Z"}))
      (let [s (arpt/attestation-summary (ar/all-attestations))]
        (is (= 2 (get (:attestors s) :attestor-x)))
        (is (= 1 (get (:attestors s) :attestor-y)))))))

(deftest summary-claim-results
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-03T00:00:00Z" :claim :approved))
    (let [s (arpt/attestation-summary (ar/all-attestations))]
      (is (= 2 (get (:claim-results s) :verified)))
      (is (= 1 (get (:claim-results s) :approved))))))

(deftest summary-subject-hash-count
  (ar/with-fresh-registry
    (let [subj-a {:type :evidence-node :hash "sha256:aaa"}
          subj-b {:type :evidence-node :hash "sha256:bbb"}]
      (ar/register-attestation! (att/build-attestation (attestor) subj-a :verified {:signed-at "2025-01-01T00:00:00Z"}))
      (ar/register-attestation! (att/build-attestation (attestor) subj-a :approved {:signed-at "2025-01-02T00:00:00Z"}))
      (ar/register-attestation! (att/build-attestation (attestor) subj-b :verified {:signed-at "2025-01-03T00:00:00Z"}))
      (let [s (arpt/attestation-summary (ar/all-attestations))]
        (is (= 2 (:subject-hash-count s)))))))

(deftest summary-time-range
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-06-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-12-31T00:00:00Z"))
    (let [s (arpt/attestation-summary (ar/all-attestations))]
      (is (= "2025-01-01T00:00:00Z" (:earliest (:time-range s))))
      (is (= "2025-12-31T00:00:00Z" (:latest (:time-range s)))))))

(deftest summary-signed-ratio
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified))
    (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim :verified :signing-key-id "key-001"))
    (ar/register-attestation! (build-a :signed-at "2025-01-03T00:00:00Z" :claim :verified :signing-key-id "key-002"))
    (let [s (arpt/attestation-summary (ar/all-attestations))]
      (is (= 3 (:total-count s)))
      (is (= 2 (:signed-count s)))
      (is (= 1 (:unsigned-count s)))
      (is (float? (:signed-ratio s)))
      (is (< 0.66 (:signed-ratio s) 0.67)))))

;; ── attestation-report ──────────────────────────────────────────────────────

(deftest report-includes-generated-at
  (let [r (arpt/attestation-report [])]
    (is (string? (:generated-at r)))))

(deftest report-includes-summary
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :claim :verified))
    (let [r (arpt/attestation-report (ar/all-attestations))]
      (is (= 1 (:attestation-count r)))
      (is (map? (:summary r)))
      (is (= 1 (get-in r [:summary :total-count]))))))

(deftest report-includes-by-attestor-breakdown
  (ar/with-fresh-registry
    (let [attestor-x {:type :ci-runner :id :attestor-x}
          attestor-y {:type :ci-runner :id :attestor-y}]
      (ar/register-attestation! (att/build-attestation attestor-x (subject) :verified {:signed-at "2025-01-01T00:00:00Z"}))
      (ar/register-attestation! (att/build-attestation attestor-y (subject) :verified {:signed-at "2025-01-02T00:00:00Z"}))
      (let [r (arpt/attestation-report (ar/all-attestations))]
        (is (= 1 (get-in r [:by-attestor :attestor-x :count])))
        (is (= 1 (get-in r [:by-attestor :attestor-y :count])))))))

(deftest report-includes-by-claim-result-breakdown
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :claim :verified))
    (ar/register-attestation! (build-a :claim :approved))
    (let [r (arpt/attestation-report (ar/all-attestations))]
      (is (= 1 (get-in r [:by-claim-result :verified :count])))
      (is (= 1 (get-in r [:by-claim-result :approved :count]))))))
