(ns resolver-sim.evidence.attestation-quorum-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-quorum :as aq]
            [resolver-sim.hash.canonical :as hc]))

;; ── Test helpers ─────────────────────────────────────────────────────────────

(defn- attestor [id] {:type :ci-runner :id id})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [attestor-id claim & {:keys [signed-at claim-id]
                        :or {signed-at "2025-01-01T00:00:00Z"}}]
  (att/build-attestation (attestor attestor-id) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id))))

(defn- sample-registry
  []
  {:registry-version 1
   :attestors [{:id :attestor-a :type :ci-runner :status :active
                :key-history [{:key-id "key-a1" :status :active}]}
               {:id :attestor-b :type :ci-runner :status :active
                :key-history [{:key-id "key-b1" :status :active}]}
               {:id :attestor-c :type :ci-runner :status :active
                :key-history [{:key-id "key-c1" :status :active}]}
               {:id :inactive-attestor :type :ci-runner :status :retired
                :key-history []}]})

(defn- k-of-n-policy
  [k n scope]
  {:policy/id :quorum/k-of-n-v1
   :policy/hash "pending"
   :quorum/scope scope
   :quorum/rule {:mode :k-of-n :k k :n n}
   :quorum/independence {:distinct-attestor-id? true
                         :distinct-operator-id? true
                         :exclude-delegates-of-same-root? false}
   :quorum/conflict-policy :fail-closed})

;; ── verify-quorum: basic cases ──────────────────────────────────────────────

(deftest quorum-confirmed-with-sufficient-pass
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        atts [(build-a :attestor-a :verified)
              (build-a :attestor-b :verified)
              (build-a :attestor-c :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/confirmed (:quorum/outcome result)))
    (is (= 3 (get-in result [:quorum/counts :eligible])))
    (is (= 3 (get-in result [:quorum/counts :pass])))))

(deftest quorum-rejected-with-sufficient-fail
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        atts [(build-a :attestor-a :rejected)
              (build-a :attestor-b :rejected)
              (build-a :attestor-c :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/rejected (:quorum/outcome result)))
    (is (= 2 (get-in result [:quorum/counts :fail])))))

(deftest quorum-insufficient-without-enough
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        atts [(build-a :attestor-a :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/insufficient-quorum (:quorum/outcome result)))))

(deftest quorum-invalid-with-no-eligible
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        result (aq/verify-quorum {:policy policy
                                  :attestations []
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/invalid-input (:quorum/outcome result)))))

;; ── Scope matching ──────────────────────────────────────────────────────────

(deftest scope-mismatch-excluded
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:different"}
        policy (k-of-n-policy 1 1 scope)
        atts [(build-a :attestor-a :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/invalid-input (:quorum/outcome result)))
    (is (= 1 (count (:quorum/excluded-attestations result))))))

;; ── Eligibility: inactive attestor ─────────────────────────────────────────

(deftest inactive-attestor-excluded
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 1 1 scope)
        atts [(build-a :inactive-attestor :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/invalid-input (:quorum/outcome result)))
    (is (some #(#{:attestor-not-in-registry :attestor-not-active} (:reason %))
              (:quorum/excluded-attestations result)))))

;; ── Independence: same attestor ─────────────────────────────────────────────

(deftest duplicate-attestor-excluded
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        atts [(build-a :attestor-a :verified)
              (build-a :attestor-a :verified)]  ;; same attestor
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/insufficient-quorum (:quorum/outcome result))
        "duplicate attestor does not count toward quorum")
    (is (some #(= :duplicate-attestor (:reason %))
              (:quorum/excluded-attestations result)))))

(deftest duplicate-attestor-conflict-detected
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 2 3 scope)
        atts [(build-a :attestor-a :verified)
              (build-a :attestor-a :rejected)]  ;; same attestor, different result
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/conflicted (:quorum/outcome result)))
    (is (seq (:quorum/conflicts result)))))

;; ── Quorum report shape ─────────────────────────────────────────────────────

(deftest report-has-required-fields
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 1 1 scope)
        atts [(build-a :attestor-a :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})]
    (is (= "attestation-quorum-report.v1" (:quorum/report-version result)))
    (is (string? (:quorum/policy-hash result)))
    (is (map? (:quorum/scope result)))
    (is (some? (:quorum/outcome result)))
    (is (map? (:quorum/counts result)))
    (is (vector? (:quorum/eligible-attestors result)))
    (is (vector? (:quorum/excluded-attestations result)))
    (is (vector? (:quorum/conflicts result)))
    (is (string? (:quorum/hash result)))))

(deftest report-hash-excludes-self
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 1 1 scope)
        atts [(build-a :attestor-a :verified)]
        result (aq/verify-quorum {:policy policy
                                  :attestations atts
                                  :attestor-registry (sample-registry)})
        recomputed (hc/hash-with-intent {:hash/intent :evidence-record}
                                        (dissoc result :quorum/hash))]
    (is (= recomputed (:quorum/hash result)))))

(deftest report-deterministic
  (let [scope {:subject-kind :evidence-node :subject-hash "sha256:abc"}
        policy (k-of-n-policy 1 1 scope)
        atts [(build-a :attestor-a :verified)]
        opts {:policy policy :attestations atts
              :attestor-registry (sample-registry)}
        r1 (aq/verify-quorum opts)
        r2 (aq/verify-quorum opts)]
    (is (= (:quorum/outcome r1) (:quorum/outcome r2)))
    (is (= (:quorum/hash r1) (:quorum/hash r2)))))

;; ── Grouping ─────────────────────────────────────────────────────────────────

(deftest group-by-scope
  (let [a1 (build-a :attestor-a :verified :signed-at "2025-01-01T00:00:00Z")
        a2 (build-a :attestor-b :verified :signed-at "2025-01-02T00:00:00Z"
                    :claim-id :claim/consistency)
        a3 (build-a :attestor-c :verified :signed-at "2025-01-03T00:00:00Z")
        grouped (aq/group-attestations-by-quorum-scope [a1 a2 a3])]
    (is (= 2 (count grouped)))  ;; two distinct scopes (with/without claim-id)
    (is (some? (get grouped {:subject-kind :evidence-node
                             :subject-hash "sha256:abc"
                             :claim-id nil})))
    (is (some? (get grouped {:subject-kind :evidence-node
                             :subject-hash "sha256:abc"
                             :claim-id :claim/consistency})))))

;; ── Explanation ──────────────────────────────────────────────────────────────

(deftest explain-confirmed
  (let [report {:quorum/outcome :quorum/confirmed
                :quorum/counts {:pass 2 :fail 0 :eligible 3 :excluded 0}
                :quorum/scope {:subject-hash "sha256:abc"}}]
    (is (string? (aq/explain-quorum-report report)))
    (is (.contains (aq/explain-quorum-report report) "CONFIRMED"))))

(deftest explain-rejected
  (let [report {:quorum/outcome :quorum/rejected
                :quorum/counts {:pass 0 :fail 2 :eligible 3 :excluded 0}
                :quorum/scope {:subject-hash "sha256:abc"}}]
    (is (.contains (aq/explain-quorum-report report) "REJECTED"))))

(deftest explain-conflicted
  (let [report {:quorum/outcome :quorum/conflicted
                :quorum/counts {:pass 1 :fail 1 :eligible 2 :excluded 0}
                :quorum/conflicts [{:type :test}]
                :quorum/scope {:subject-hash "sha256:abc"}}]
    (is (.contains (aq/explain-quorum-report report) "CONFLICTED"))))

(deftest explain-insufficient
  (let [report {:quorum/outcome :quorum/insufficient-quorum
                :quorum/counts {:pass 1 :fail 0 :eligible 1 :excluded 0}
                :quorum/scope {:subject-hash "sha256:abc"}}]
    (is (.contains (aq/explain-quorum-report report) "INSUFFICIENT"))))

(deftest explain-invalid
  (let [report {:quorum/outcome :quorum/invalid-input
                :quorum/counts {:pass 0 :fail 0 :eligible 0 :excluded 1}
                :quorum/scope {:subject-hash "sha256:abc"}}]
    (is (.contains (aq/explain-quorum-report report) "INVALID"))))

;; ── Edge: scope with claim-definition-hash ──────────────────────────────────

(deftest scope-with-claim-definition-hash
  (let [scope {:subject-kind :evidence-node
               :subject-hash "sha256:abc"
               :claim-definition-hash :claim/consistency}
        policy (k-of-n-policy 1 1 scope)
        matching (build-a :attestor-a :verified :claim-id :claim/consistency)
        mismatching (build-a :attestor-b :verified :claim-id :claim/other)
        result (aq/verify-quorum {:policy policy
                                  :attestations [matching mismatching]
                                  :attestor-registry (sample-registry)})]
    (is (= :quorum/confirmed (:quorum/outcome result)))
    (is (= 1 (get-in result [:quorum/counts :eligible])))))
