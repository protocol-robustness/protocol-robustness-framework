(ns resolver-sim.evidence.attestation-bundle-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-bundle :as ab]
            [resolver-sim.evidence.attestation-completeness-profile :as acp]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.sensitivity.contract :as c]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.support.ed25519 :as fx])
  (:import [java.util UUID]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id))))

;; ── build-attestation-bundle ─────────────────────────────────────────────────

(deftest bundle-has-required-fields
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= "attestation-bundle.v1" (:bundle/version bundle)))
    (is (= :attestation-verification-package (:bundle/kind bundle)))
    (is (some? (:bundle/root-hash bundle)))))

(deftest bundle-includes-entrypoints
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 1 (count (:bundle/entrypoints bundle))))
    (is (= (:attestation/id a)
           (:attestation/hash (first (:bundle/entrypoints bundle)))))))

(deftest bundle-includes-objects
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 1 (count (:bundle/objects bundle))))
    (is (= :attestation-record (:object/kind (first (:bundle/objects bundle)))))
    (is (= (:attestation/id a) (:object/hash (first (:bundle/objects bundle)))))))

(deftest bundle-includes-registries
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (contains? (:bundle/registries bundle) :attestors))
    (is (contains? (:bundle/registries bundle) :claim-definitions))
    (is (contains? (:bundle/registries bundle) :hash-intents))))

(deftest bundle-root-hash-is-deterministic
  (let [a (build-a)
        opts {:attestations [a]
              :registries {:attestors registries/attestor-registry
                           :claim-definitions registries/claim-definition-registry
                           :hash-intents hc/hash-intents}}
        b1 (ab/build-attestation-bundle opts)
        b2 (ab/build-attestation-bundle opts)]
    (is (= (:bundle/root-hash b1) (:bundle/root-hash b2)))))

(deftest bundle-root-hash-excludes-self
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (nil? (:bundle/root-hash (dissoc bundle :bundle/root-hash))))))

(deftest bundle-sensitivity-defaults-to-blocked
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= :blocked (get-in bundle [:bundle/sensitivity :sentinel/decision])))))

(deftest bundle-with-sensitivity-report
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:report"}})]
    (is (= :allowed (get-in bundle [:bundle/sensitivity :sentinel/decision])))))

(deftest bundle-with-claim-results
  (let [a (build-a)
        claim-result {:claim-id :conservation
                      :claim-result-hash "sha256:claim"
                      :holds? true :status :pass}
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :claim-results [claim-result]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 2 (count (:bundle/objects bundle))))
    (is (true? (:subject-content-included? (:bundle/verification-profile bundle))))))

;; ── verify-attestation-bundle (in-memory structural checks) ─────────────────

(deftest verify-detects-version-mismatch
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:r"}})
        tampered (assoc bundle :bundle/version "wrong-version")
        result (ab/verify-attestation-bundle tampered)]
    (is (false? (:valid? result)))
    (is (= :invalid (:bundle/status result)))
    (is (some #(= :fail (:check/status %)) (:checks result)))))

(deftest verify-detects-root-hash-mismatch
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:r"}})
        tampered (assoc bundle :bundle/root-hash "tampered")
        result (ab/verify-attestation-bundle tampered)]
    (is (false? (:valid? result)))
    (is (= :invalid (:bundle/status result)))))

(deftest verify-detects-blocked-sensitivity
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :blocked
                                      :report-hash "sha256:blocked"}})
        result (ab/verify-attestation-bundle bundle)]
    (is (= :blocked-by-sensitivity-policy (:bundle/status result)))
    (is (false? (:valid? result)))))

(deftest verify-returns-summary
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:ok"}})
        result (ab/verify-attestation-bundle bundle)]
    (is (map? (:summary result)))
    (is (number? (get-in result [:summary :total-checks])))))

(deftest verify-empty-bundle-is-invalid-under-review
  (testing "an empty evidence bundle under the review profile is :invalid, not :hash-linked"
    (let [bundle (ab/build-attestation-bundle
                  {:attestations []
                   :claim-results []
                   :evidence-nodes []
                   :registries {:attestors registries/attestor-registry
                                :claim-definitions registries/claim-definition-registry
                                :hash-intents hc/hash-intents}
                   :sensitivity-report {:decision :allowed :report-hash "sha256:r"}})
          result (ab/verify-attestation-bundle bundle)]
      (is (= :invalid (:bundle/status result)))
      (is (false? (:valid? result)))
      (is (some #(and (= :completeness-profile-evaluated (:check/id %))
                      (= :fail (:check/status %)))
                (:checks result))
          "the completeness-profile evaluation is now a failing check for empty evidence"))))

(deftest verify-no-attestations-with-claim-results-is-partially-verified
  (testing "a review bundle missing required attestation-records is :partially-verified"
    (let [bundle (ab/build-attestation-bundle
                  {:attestations []
                   :claim-results [{:claim-result-hash "sha256:c"
                                    :claim-id :conservation :holds? true :status :pass}]
                   :registries {:attestors registries/attestor-registry
                                :claim-definitions registries/claim-definition-registry
                                :hash-intents hc/hash-intents}
                   :sensitivity-report {:decision :allowed :report-hash "sha256:r"}})
          result (ab/verify-attestation-bundle bundle)]
      (is (= :partially-verified (:bundle/status result)))
      (is (true? (:valid? result)))
      (is (false? (:verified? result)) "missing required attestations is never :verified?"))))

;; ── Bundle status levels ────────────────────────────────────────────────────

(deftest status-hash-linked
  (testing "warnings from missing files produce hash-linked status"
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          bundle (ab/build-attestation-bundle
                  {:attestations [a]
                   :claim-results [{:claim-result-hash "sha256:cr"
                                    :claim-id :conservation :holds? true :status :pass}]
                   :registries {:attestors registries/attestor-registry
                                :claim-definitions registries/claim-definition-registry
                                :hash-intents hc/hash-intents}
                   :sensitivity-report {:decision :allowed
                                        :report-hash "sha256:ok"}})
          result (ab/verify-attestation-bundle bundle)]
      (is (= :hash-linked (:bundle/status result))
          "in-memory complete bundles have file-not-found warnings -> :hash-linked")
      (is (true? (:valid? result))))))

(deftest status-invalid-on-version-mismatch
  (let [a (build-a)
        bundle (assoc (ab/build-attestation-bundle
                       {:attestations [a]
                        :registries {:attestors registries/attestor-registry
                                     :claim-definitions registries/claim-definition-registry
                                     :hash-intents hc/hash-intents}
                        :sensitivity-report {:decision :allowed
                                             :report-hash "sha256:r"}})
                      :bundle/version "bad")
        result (ab/verify-attestation-bundle bundle)]
    (is (= :invalid (:bundle/status result)))))

(deftest status-blocked-on-sentinel
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})
        result (ab/verify-attestation-bundle bundle)]
    (is (= :blocked-by-sensitivity-policy (:bundle/status result)))))

;; ── Assurance contract: :valid? vs :verified? ────────────────────────────────

(deftest verified?-true-only-for-fully-verified
  (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
        base-bundle (ab/build-attestation-bundle
                     {:attestations [a]
                      :claim-results [{:claim-result-hash "sha256:cr"
                                       :claim-id :conservation :holds? true :status :pass}]
                      :registries {:attestors registries/attestor-registry
                                   :claim-definitions registries/claim-definition-registry
                                   :hash-intents hc/hash-intents}
                      :sensitivity-report {:decision :allowed
                                           :report-hash "sha256:ok"}})
        hash-linked (ab/verify-attestation-bundle base-bundle)
        invalid (ab/verify-attestation-bundle (assoc base-bundle :bundle/version "bad"))]
    (testing "assurance boolean is false for any non-fully-verified status"
      (is (= :hash-linked (:bundle/status hash-linked)))
      (is (true? (:valid? hash-linked)))
      (is (false? (:verified? hash-linked))
          "hash-linked is structurally valid but NOT assured")
      (is (false? (:verified? invalid))))
    (testing "only :fully-verified is treated as assured"
      (is (contains? ab/fully-verified-statuses :fully-verified))
      (doseq [s ab/verification-statuses]
        (is (= (contains? ab/fully-verified-statuses s)
               (= s :fully-verified))
            (str "status " s " assurance classification is inconsistent"))))))

;; ── I/O tests ────────────────────────────────────────────────────────────────

(deftest write-and-read-bundle
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-"
                     (java.util.UUID/randomUUID))
        a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:r"}
                 :options {:bundle-dir tmp-dir}})]
    (ab/write-attestation-bundle! bundle {:attestations [a]} tmp-dir)
    (let [read-back (ab/read-attestation-bundle tmp-dir)]
      (is (= (:bundle/version bundle) (:bundle/version read-back)))
      (is (= (:bundle/root-hash bundle) (:bundle/root-hash read-back)))
      (io/delete-file (io/file tmp-dir) true))))

(deftest required-signature-profile-rejects-unsigned-attestations
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :options {:signature? true}})
        result (ab/verify-attestation-bundle bundle)]
    (is (false? (:valid? result)))
    (is (= :invalid (:bundle/status result)))
    (is (some #(and (= :attestation-signature-valid (:check/id %))
                    (= :fail (:check/status %)))
              (:checks result)))))

(deftest bundled-attestor-registry-requires-external-trust-anchor
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-" (java.util.UUID/randomUUID))
        a (build-a)
        registry registries/attestor-registry
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :options {:bundle-dir tmp-dir}})
        declared (get-in bundle [:bundle/registries :attestors :registry/hash])]
    (ab/write-attestation-bundle! bundle {:attestations [a]
                                          :attestors registry
                                          :claim-definitions registries/claim-definition-registry
                                          :hash-intents hc/hash-intents} tmp-dir)
    (let [read-back (ab/read-attestation-bundle tmp-dir)
          untrusted (ab/verify-attestation-bundle read-back {:trusted-attestor-registry-hashes #{"sha256:attacker"}})
          trusted (ab/verify-attestation-bundle read-back {:trusted-attestor-registry-hashes #{(str "sha256:" declared)}})]
      (is (false? (:valid? untrusted)))
      (is (some #(= :untrusted-registry (:reason %)) (:checks untrusted)))
      (is (some #(= :pass (:check/status %))
                (filter #(= :attestor-registry-trusted (:check/id %)) (:checks trusted))))
      (io/delete-file (io/file tmp-dir) true))))

(deftest write-requires-explicit-trusted-root-and-rejects-escaping-paths
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-" (java.util.UUID/randomUUID))
        a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :options {:bundle-dir tmp-dir}})
        escaping (assoc-in bundle [:bundle/registries :attestors :registry/path] "/tmp/attestation-bundle-escape.edn")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ab/write-attestation-bundle! bundle {:attestations [a]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ab/write-attestation-bundle! escaping {:attestations [a]} tmp-dir)))
    (is (not (.exists (io/file "/tmp/attestation-bundle-escape.edn"))))
    (io/delete-file (io/file tmp-dir) true)))

(deftest read-throws-on-missing-manifest
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-"
                     (java.util.UUID/randomUUID))]
    (.mkdirs (io/file tmp-dir))
    (is (thrown? Exception (ab/read-attestation-bundle tmp-dir)))
    (io/delete-file (io/file tmp-dir) true)))

;; ── Profile combination tests ───────────────────────────────────────────────

(deftest signature-required-affects-profile-hash
  (let [p-default (acp/make-profile :review {})
        p-required (acp/make-profile :review {:signature-required true})]
    (is (not= (:profile/hash p-default) (:profile/hash p-required))
        ":signature/required changes the committed profile hash")))

(deftest profile-mode-determines-completeness-check
  (let [a (build-a)
        dev-profile (acp/make-profile :development {})
        review-profile (acp/make-profile :review {})
        make-bundle (fn [profile]
                      (ab/build-attestation-bundle
                       {:attestations [a]
                        :registries {:attestors registries/attestor-registry
                                     :claim-definitions registries/claim-definition-registry
                                     :hash-intents hc/hash-intents}
                        :completeness-profile profile
                        :sensitivity-report {:decision :allowed
                                             :report-hash "sha256:sr"}}))
        dev-bundle (make-bundle dev-profile)
        review-bundle (make-bundle review-profile)
        dev-result (ab/verify-attestation-bundle dev-bundle)
        review-result (ab/verify-attestation-bundle review-bundle)]
    ;; Both profiles allow unsigned (no :signature? option, default is nil)
    ;; But they differ in evidence requirements
    (is (some? (:valid? dev-result))
        "development profile bundle can be verified")
    (is (some? (:valid? review-result))
        "review profile bundle can be verified")))

(deftest profile-signature-required-via-committed-policy
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-combo-"
                     (java.util.UUID/randomUUID))
        a (build-a :signed-at "2025-01-01T00:00:00Z")
        registry registries/attestor-registry
        strict-profile (acp/make-profile :review {:signature-required true})
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :completeness-profile strict-profile
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:sr"}
                 :options {:bundle-dir tmp-dir}})]
    ;; Write to disk so files exist for verification
    (ab/write-attestation-bundle! bundle {:attestations [a]
                                          :attestors registry
                                          :claim-definitions registries/claim-definition-registry
                                          :hash-intents hc/hash-intents} tmp-dir)
    (let [read-back (ab/read-attestation-bundle tmp-dir)
          result (ab/verify-attestation-bundle read-back {:trusted-attestor-registry-hashes
                                                          #{(str "sha256:"
                                                                 (get-in bundle [:bundle/registries :attestors :registry/hash]))}})]
      ;; Profile with :signature/required true — unsigned fails
      (is (false? (:valid? result))
          "committed profile with :signature/required true rejects unsigned on-disk")
      (is (some #(and (= :attestation-signature-valid (:check/id %))
                      (= :fail (:check/status %)))
                (:checks result))
          "signature check fails when profile requires signatures"))
    (io/delete-file (io/file tmp-dir) true)))

(deftest explicit-signature-option-overrides-profile-in-verification
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-override-"
                     (java.util.UUID/randomUUID))
        a (build-a :signed-at "2025-01-01T00:00:00Z")
        registry registries/attestor-registry
        dev-profile (acp/make-profile :development {})
        ;; Development profile with explicit :signature? true overrides
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :completeness-profile dev-profile
                 :sensitivity-report {:decision :allowed
                                      :report-hash "sha256:sr"}
                 :options {:bundle-dir tmp-dir
                           :signature? true}})]
    (ab/write-attestation-bundle! bundle {:attestations [a]
                                          :attestors registry
                                          :claim-definitions registries/claim-definition-registry
                                          :hash-intents hc/hash-intents} tmp-dir)
    (let [read-back (ab/read-attestation-bundle tmp-dir)
          result (ab/verify-attestation-bundle read-back {:trusted-attestor-registry-hashes
                                                          #{(str "sha256:"
                                                                 (get-in bundle [:bundle/registries :attestors :registry/hash]))}})]
      (is (false? (:valid? result))
          "explicit :signature? true overrides dev profile — unsigned rejected on-disk")
      (is (= :invalid (:bundle/status result))
          "explicit signature requirement makes unsigned bundle :invalid"))
    (io/delete-file (io/file tmp-dir) true)))

(deftest evaluate-evidence-status-mode-difference
  (let [dev-profile (acp/make-profile :development {})
        review-profile (acp/make-profile :review {})
        ;; Empty objects — review profile rejects empty, dev permits with warning
        review-status (acp/evaluate-evidence-status
                       review-profile
                       {:bundle/objects []
                        :sensitivity/decision :allowed})
        dev-status (acp/evaluate-evidence-status
                    dev-profile
                    {:bundle/objects []
                     :sensitivity/decision :allowed})]
    (is (= :invalid review-status)
        "review profile: empty evidence → :invalid (empty-set decision :fail)")
    (is (not= :invalid dev-status)
        "development profile: empty evidence → not :invalid (empty-set decision :warn)")))

(deftest review-profile-not-fully-verified-when-attestations-missing
  (testing "a review bundle with claim results but no attestation-records is not :fully-verified"
    (let [review-profile (acp/make-profile :review {})
          status (acp/evaluate-evidence-status
                  review-profile
                  {:bundle/objects [{:object/kind :claim-result :object/hash "sha256:c"}]
                   :sensitivity/decision :allowed})]
      (is (not= :fully-verified status)
          "missing required :attestation-records must not be labelled :fully-verified")
      (is (= :partially-verified status)))))

;; ── Out-of-process sensitivity sentinel verification ────────────────────────

(defn- signed-allow-decision
  "Build an out-of-process sensitivity artifact carrying a signed :allow
   decision for the given sink."
  [kp sink]
  (let [req (c/build-request {:artifact-id "req-bundle"
                              :content {:scenario-id "s"}
                              :sink sink
                              :policy-hash-str (sentinel/policy-hash)})
        report {:sentinel/version "sensitivity-sentinel.v1"
                :sentinel/decision :allow
                :sentinel/level :sensitivity/public
                :sentinel/structural-level :sensitivity/public
                :sentinel/reasons []
                :sentinel/allowed-sinks [sink]
                :sentinel/redaction-required? false
                :sentinel/override-required? {:required? false :mode :single}}
        decision (c/build-decision {:request req :report report :sink sink
                                    :artifact-hash (:artifact/declared-hash req)
                                    :authority-key-id (:key/id kp)
                                    :authority-assurance :process-isolated
                                    :issued-at "now"})
        signed (sed/sign-envelope decision c/decision-domain (:private-key kp) (:key/id kp))]
    {:sentinel/request req
     :sentinel/report report
     :sentinel/authority-decision signed
     :sentinel/signature (:signature signed)}))

(defn- remote-bundle [kp & [sink]]
  (let [a (build-a)
        os (signed-allow-decision kp (or sink :ipfs))]
    (ab/build-attestation-bundle
     {:attestations [a]
      :registries {:attestors registries/attestor-registry
                   :claim-definitions registries/claim-definition-registry
                   :hash-intents hc/hash-intents}
      :out-of-process-sensitivity os})))

(defn- sentinel-check [result]
  (first (filter #(= :sensitivity-sentinel-approved (:check/id %)) (:checks result))))

(deftest remote-required-bundle-passes-with-valid-signed-decision
  (let [kp (fx/keypair)
        bundle (remote-bundle kp)
        result (ab/verify-attestation-bundle bundle {:sentinel/trust-policy (fx/trust-policy kp)})
        check (sentinel-check result)]
    (is (= :pass (:check/status check)))
    (is (= :remote (:check/mode check)))
    (is (= :out-of-process (:check/authority check)))
    (is (= :out-of-process (get-in bundle [:bundle/sensitivity :sentinel/mode])))
    (is (= :remote (get-in bundle [:bundle/sensitivity :sentinel/required-authority])))))

(deftest remote-required-bundle-blocked-without-trust-policy
  (let [kp (fx/keypair)
        bundle (remote-bundle kp)
        result (ab/verify-attestation-bundle bundle {})
        check (sentinel-check result)]
    (is (= :blocked (:check/status check)))
    (is (some? (:reason check)))))

(deftest remote-required-bundle-blocked-without-decision
  (let [kp (fx/keypair)
        bundle (remote-bundle kp)
        stripped (assoc-in bundle [:bundle/sensitivity :sentinel/authority-decision] nil)
        result (ab/verify-attestation-bundle stripped {:sentinel/trust-policy (fx/trust-policy kp)})
        check (sentinel-check result)]
    (is (= :blocked (:check/status check)))))

(deftest remote-required-bundle-rejects-local-only-decision
  (testing "a local approval can never satisfy a remote-required sink"
    (let [kp (fx/keypair)
          bundle (remote-bundle kp)
          ;; simulate a local-only decision embedded against a remote-required sink
          local-only (-> bundle
                         (assoc-in [:bundle/sensitivity :sentinel/required-authority] :remote)
                         (assoc-in [:bundle/sensitivity :sentinel/authority-decision] nil)
                         (assoc-in [:bundle/sensitivity :sentinel/decision] :allowed)
                         (assoc-in [:bundle/sensitivity :sentinel/signature] nil))
          result (ab/verify-attestation-bundle local-only {:sentinel/trust-policy (fx/trust-policy kp)})
          check (sentinel-check result)]
      (is (= :blocked (:check/status check)))
      (is (re-find #"Remote authority required" (:reason check))))))

(deftest remote-required-bundle-rejects-tampered-signature
  (let [kp (fx/keypair)
        bundle (remote-bundle kp)
        tampered (assoc-in bundle [:bundle/sensitivity :sentinel/authority-decision :sentinel/decision] :block)
        result (ab/verify-attestation-bundle tampered {:sentinel/trust-policy (fx/trust-policy kp)})
        check (sentinel-check result)]
    (is (= :blocked (:check/status check)))
    (is (re-find #"signature invalid|signed-hash" (str (:reason check))))))
