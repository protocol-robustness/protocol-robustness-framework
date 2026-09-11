(ns resolver-sim.evidence.attestation-emitter-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation-emitter :as ae]
            [resolver-sim.evidence.attestation-registry :as ar]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

;; ── emit-attestation! ────────────────────────────────────────────────────────

(deftest emit-attestation-produces-attestation
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"})]
      (is (map? (:attestation result)))
      (is (some? (:attestation/id (:attestation result)))))))

(deftest emit-attestation-produces-node-result
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"})]
      (is (map? (:node-result result)))
      (is (some? (get-in result [:node-result :node :node-hash]))))))

(deftest emit-attestation-registers-in-registry
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"})
          id (:attestation/id (:attestation result))]
      (is (= (:attestation result) (ar/find-attestation id))))))

(deftest emit-attestation-supports-claim-id
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :reproduced
                                       {:signed-at "2025-01-01T00:00:00Z"
                                        :claim-id :claim/consistency})
          a (:attestation result)]
      (is (= :reproduced (:attestation/claim-result a)))
      (is (= :claim/consistency (:attestation/claim-id a))))))

(deftest emit-attestation-supports-signing
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"
                                        :signing-key-id "key-001"
                                        :signing-fn (fn [_]
                                                      {:algorithm :ed25519
                                                       :public-key-id "key-001"
                                                       :signature-bytes "hex"})})
          a (:attestation result)]
      (is (some? (:attestation/signature a)))
      (is (= "hex" (get-in a [:attestation/signature :signature-bytes]))))))

(deftest emit-attestation-with-provenance
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"
                                        :run-id "run-test"
                                        :scenario-id "S01"
                                        :step 42
                                        :trigger :replay-complete})
          a (:attestation result)
          prov (:attestation/provenance a)]
      (is (some? prov))
      (is (= "run-test" (:provenance/run-id prov)))
      (is (= "S01" (:provenance/scenario-id prov)))
      (is (= 42 (:provenance/step prov)))
      (is (= :replay-complete (:provenance/trigger prov))))))

(deftest emit-attestation-with-custom-provenance
  (ar/with-fresh-registry
    (let [custom {:provenance/schema-version "attestation-provenance.v1"
                  :provenance/trigger :manual
                  :provenance/generated-at "2025-01-01T00:00:00Z"
                  :custom-field "custom-value"}
          result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"
                                        :provenance custom})
          a (:attestation result)]
      (is (= custom (:attestation/provenance a))))))

(deftest emit-attestation-with-metadata
  (ar/with-fresh-registry
    (let [result (ae/emit-attestation! (attestor) (subject) :verified
                                       {:signed-at "2025-01-01T00:00:00Z"
                                        :metadata {:env "test" :source "emitter-test"}})
          a (:attestation result)]
      (is (= {:env "test" :source "emitter-test"} (:attestation/metadata a))))))

;; ── emit-claim-result-attestation! ───────────────────────────────────────────

(defn- sample-claim-result
  [& {:keys [claim-id claim-result-hash holds? status]
      :or {claim-id :conservation
           claim-result-hash "sha256:abc123"
           holds? true
           status :pass}}]
  {:claim-id claim-id
   :claim-definition-hash "sha256:def456"
   :claim-result-hash claim-result-hash
   :holds? holds?
   :status status
   :violations []})

(deftest emit-claim-result-produces-attestation
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"})
          a (:attestation result)]
      (is (map? a))
      (is (= :claim (:attestation/subject-kind a)))
      (is (= "sha256:abc123" (:attestation/subject-hash a))))))

(deftest emit-claim-result-registers-in-registry
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"})
          a (:attestation result)]
      (is (= a (ar/find-attestation (:attestation/id a))))
      ;; Should be findable by claim-id
      (is (= 1 (count (ar/find-attestations-by-claim-id :conservation)))))))

(deftest emit-claim-result-produces-node
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"})]
      (is (map? (:node-result result)))
      (is (some? (get-in result [:node-result :node :node-hash]))))))

(deftest emit-claim-result-supports-claim-override
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"
                   :claim :reproduced})
          a (:attestation result)]
      (is (= :reproduced (:attestation/claim-result a))))))

(deftest emit-claim-result-supports-signing
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"
                   :signing-key-id "key-001"
                   :signing-fn (fn [_]
                                 {:algorithm :ed25519
                                  :public-key-id "key-001"
                                  :signature-bytes "claim-sig"})})
          a (:attestation result)]
      (is (some? (:attestation/signature a))))))

(deftest emit-claim-result-with-provenance
  (ar/with-fresh-registry
    (let [result (ae/emit-claim-result-attestation!
                  (attestor) (sample-claim-result)
                  {:signed-at "2025-01-01T00:00:00Z"
                   :run-id "run-claim"
                   :scenario-id "S01"
                   :step 10
                   :vcs-sha "abc123"})
          a (:attestation result)
          prov (:attestation/provenance a)]
      (is (some? prov))
      (is (= "run-claim" (:provenance/run-id prov)))
      (is (= "S01" (:provenance/scenario-id prov)))
      (is (= 10 (:provenance/step prov)))
      (is (= "abc123" (:provenance/vcs-sha prov))))))

(deftest emit-claim-result-different-results-different-attestations
  (ar/with-fresh-registry
    (let [result-a (ae/emit-claim-result-attestation!
                    (attestor) (sample-claim-result :claim-result-hash "sha256:aaa")
                    {:signed-at "2025-01-01T00:00:00Z"})
          result-b (ae/emit-claim-result-attestation!
                    (attestor) (sample-claim-result :claim-result-hash "sha256:bbb")
                    {:signed-at "2025-01-01T00:00:00Z"})
          a1 (first (ar/find-attestations-by-subject "sha256:aaa"))
          a2 (first (ar/find-attestations-by-subject "sha256:bbb"))]
      (is (some? a1))
      (is (some? a2))
      (is (not= (:attestation/id a1) (:attestation/id a2))))))

;; ── Full pipeline integration ───────────────────────────────────────────────

(deftest full-pipeline-registers-and-finds
  (ar/with-fresh-registry
    (ae/emit-attestation! (attestor) (subject) :verified
                          {:signed-at "2025-01-01T00:00:00Z"
                           :run-id "pipeline-test"
                           :claim-id :claim/consistency})
    (let [by-claim (ar/find-attestations-by-claim-id :claim/consistency)
          by-subject (ar/find-attestations-by-subject "sha256:abc")
          by-attestor (ar/find-attestations-by-attestor :ci-validation)]
      (is (= 1 (count by-claim)))
      (is (= 1 (count by-subject)))
      (is (= 1 (count by-attestor))))))

(deftest full-pipeline-claim-result-flow
  (ar/with-fresh-registry
    (ae/emit-claim-result-attestation!
     (attestor) (sample-claim-result :claim-id :conservation
                                     :claim-result-hash "sha256:flow-test")
     {:signed-at "2025-01-01T00:00:00Z"
      :run-id "full-flow"
      :scenario-id "S01"
      :claim :reproduced
      :signing-key-id "key-001"
      :signing-fn (fn [_] {:algorithm :ed25519
                           :public-key-id "key-001"
                           :signature-bytes "flow-sig"})})
    (let [by-claim (ar/find-attestations-by-claim-id :conservation)
          by-subject (ar/find-attestations-by-subject "sha256:flow-test")
          by-attestor (ar/find-attestations-by-attestor :ci-validation)]
      (is (= 1 (count by-claim)))
      (is (= 1 (count by-subject)))
      (is (= 1 (count by-attestor)))
      (is (= :reproduced (:attestation/claim-result (first by-claim))))
      (is (some? (:attestation/signature (first by-claim)))))))
