(ns resolver-sim.sensitivity.sentinel-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.sensitivity.propagation :as prop]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-bundle :as ab]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

;; ── Level ordering ──────────────────────────────────────────────────────────

(deftest levels-ordered-low-to-high
  (is (= 0 (sentinel/level-index :sensitivity/public)))
  (is (= 1 (sentinel/level-index :sensitivity/internal)))
  (is (= 2 (sentinel/level-index :sensitivity/private)))
  (is (= 3 (sentinel/level-index :sensitivity/embargoed)))
  (is (= 4 (sentinel/level-index :sensitivity/critical-private))))

(deftest level-unknown-defaults-to-highest
  (is (= 5 (sentinel/level-index :sensitivity/unknown))))

(deftest level-inequality
  (is (true? (sentinel/level>= :sensitivity/private :sensitivity/public)))
  (is (false? (sentinel/level>= :sensitivity/public :sensitivity/private)))
  (is (true? (sentinel/level>= :sensitivity/critical-private :sensitivity/private))))

;; ── Risk severity ordering ───────────────────────────────────────────────────

(deftest risk-severities-ordered-low-to-high
  (is (false? (sentinel/risk-severity>= :risk-severity/low :risk-severity/critical)))
  (is (true? (sentinel/risk-severity>= :risk-severity/critical :risk-severity/low)))
  (is (true? (sentinel/risk-severity>= :risk-severity/high :risk-severity/medium))))

;; ── Disclosure matrix ───────────────────────────────────────────────────────

(deftest public-allowed-on-all-sinks
  (doseq [sink sentinel/all-sinks]
    (is (true? (sentinel/disclosure-allowed? :sensitivity/public sink))
        (str "public allowed on " sink))))

(deftest internal-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/internal sink))
        (str "internal blocked on " sink))))

(deftest internal-allowed-on-safe-sinks
  (doseq [sink sentinel/safe-sinks]
    (is (true? (sentinel/disclosure-allowed? :sensitivity/internal sink))
        (str "internal allowed on " sink))))

(deftest private-blocked-on-all-non-safe-sinks
  (doseq [sink (clojure.set/difference sentinel/all-sinks sentinel/safe-sinks)]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/private sink))
        (str "private blocked on " sink))))

(deftest embargoed-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/embargoed sink))
        (str "embargoed blocked on " sink))))

(deftest critical-private-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/critical-private sink))
        (str "critical-private blocked on " sink))))

(deftest unknown-level-defaults-to-blocked
  (is (false? (sentinel/disclosure-allowed? :sensitivity/unknown :public-bundle)))
  (is (true? (sentinel/disclosure-allowed? :sensitivity/unknown :local))))

(deftest unknown-sink-defaults-to-blocked
  (is (false? (sentinel/disclosure-allowed? :sensitivity/public :unknown-sink))))

;; ── Authority mode by sink ─────────────────────────────────────────────────

(deftest remote-authority-required-for-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (true? (sentinel/remote-authority-required? sink))
        (str "public sink " sink " must require remote authority"))))

(deftest in-process-authority-for-safe-and-low-risk-sinks
  (doseq [sink (concat sentinel/safe-sinks sentinel/low-risk-sinks)]
    (is (false? (sentinel/remote-authority-required? sink))
        (str "sink " sink " must not require remote authority"))))

(deftest unknown-sink-requires-remote-authority
  (is (true? (sentinel/remote-authority-required? :unknown-sink))
      "unknown sinks must conservatively require remote authority"))

(deftest policy-hash-commits-authority-mode
  (let [ph (sentinel/policy-hash)]
    (is (string? ph))
    (is (pos? (count ph)))
    (is (= ph (sentinel/policy-hash)) "policy hash must be deterministic")))

(deftest git-commit-sink-allows-internal
  (is (true? (sentinel/disclosure-allowed? :sensitivity/internal :git-commit)))
  (is (true? (sentinel/disclosure-allowed? :sensitivity/public :git-commit))))

(deftest public-ci-artifact-sink-blocks-internal
  (is (false? (sentinel/disclosure-allowed? :sensitivity/internal :public-ci-artifact)))
  (is (true? (sentinel/disclosure-allowed? :sensitivity/public :public-ci-artifact))))

;; ── Classification: attestations ────────────────────────────────────────────

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(deftest classify-attestation-is-internal
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :sensitivity/internal (sentinel/classify a)))))

(deftest classify-attestation-with-claim-id-is-private
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :claim-id :claim/consistency})]
    (is (= :sensitivity/private (sentinel/classify a)))))

(deftest classify-attestation-on-claim-subject-is-private
  (let [claim-subject {:type :claim :claim-id :consistency}
        a (att/build-attestation (attestor) claim-subject :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :sensitivity/private (sentinel/classify a)))))

;; ── Classification: evidence nodes ──────────────────────────────────────────

(deftest classify-evidence-node-fail-is-internal
  (let [node {:node-hash "sha256:n1" :result {:status :fail}}]
    (is (= :sensitivity/internal (sentinel/classify node)))))

(deftest classify-evidence-node-fail-with-details-is-private
  (let [node {:node-hash "sha256:n1"
              :result {:status :fail
                       :failure-details [{:message "exploit path found"}]}}]
    (is (= :sensitivity/private (sentinel/classify node)))))

(deftest classify-evidence-node-with-attestations-is-internal
  (let [node {:node-hash "sha256:n1"
              :result {:status :pass}
              :attestations ["att-hash-1"]}]
    (is (= :sensitivity/internal (sentinel/classify node)))))

;; ── Classification: claim results ──────────────────────────────────────────

(deftest classify-claim-result-fail-is-internal
  (let [cr {:claim-id :conservation :holds? false :status :fail}]
    (is (= :sensitivity/internal (sentinel/classify cr)))))

(deftest classify-claim-result-pass-is-internal
  (let [cr {:claim-id :conservation :holds? true :status :pass}]
    (is (= :sensitivity/internal (sentinel/classify cr)))))

;; ── Classification: bundles ─────────────────────────────────────────────────

(deftest classify-bundle-is-internal
  (let [bundle (ab/build-attestation-bundle
                {:attestations []
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= :sensitivity/internal (sentinel/classify bundle)))))

;; ── Classification: default ─────────────────────────────────────────────────

(deftest classify-unknown-is-critical-private
  (is (= :sensitivity/critical-private (sentinel/classify {})))
  (is (= :sensitivity/critical-private (sentinel/classify "string")))
  (is (= :sensitivity/critical-private (sentinel/classify nil))))

;; ── Classification: unredacted scenario content ─────────────────────────────

(deftest classify-unredacted-scenario-is-private
  (let [scenario {:scenario-id "s42-attack"
                  :title "Attack scenario"
                  :events [{:seq 0 :time 1000 :agent "attacker" :action "exploit" :params {}}]
                  :agents [{:id "attacker" :address "0xbad" :strategy "dishonest"}]}]
    (is (= :sensitivity/private (sentinel/classify scenario)))))

(deftest classify-scenario-id-only-is-internal
  (let [artifact {:scenario-id "s42"}]
    (is (= :sensitivity/internal (sentinel/classify artifact)))))

;; ── Classification: declared level ───────────────────────────────────────────

(deftest classify-declared-level-raises-floor
  (let [artifact {:scenario-id "bench-result"
                  :sensitivity/level :sensitivity/private}]
    (is (= :sensitivity/private (sentinel/classify artifact))
        "declared :private floor must raise structural :internal to :private")))

(deftest classify-declared-level-cannot-downgrade
  (let [artifact {:node-hash "sha256:n1"
                  :result {:status :fail
                           :failure-details [{:message "exploit"}]}
                  :sensitivity/level :sensitivity/public}]
    (is (= :sensitivity/private (sentinel/classify artifact))
        "structural :private must override declared :public")))

(deftest classify-declared-level-on-public-artifact
  (let [artifact {:scenario-id "bench-results"
                  :sensitivity/level :sensitivity/internal}]
    (is (= :sensitivity/internal (sentinel/classify artifact)))))

;; ── Sentinel report ────────────────────────────────────────────────────────

(deftest sentinel-report-has-required-fields
  (let [r (sentinel/sentinel-report {:attestation/id "test"} :public-bundle)]
    (is (= "sensitivity-sentinel.v1" (:sentinel/version r)))
    (is (string? (:sentinel/policy-hash r)))
    (is (string? (:sentinel/evaluated-at r)))
    (is (some? (:sentinel/decision r)))
    (is (some? (:sentinel/level r)))
    (is (vector? (:sentinel/reasons r)))
    (is (vector? (:sentinel/allowed-sinks r)))))

(deftest sentinel-report-deterministic
  (let [a {:attestation/id "test-hash"}
        r1 (sentinel/sentinel-report a :public-bundle)
        r2 (sentinel/sentinel-report a :public-bundle)]
    (is (= (:sentinel/decision r1) (:sentinel/decision r2)))
    (is (= (:sentinel/level r1) (:sentinel/level r2)))
    (is (= (:sentinel/reasons r1) (:sentinel/reasons r2)))
    (is (= (:sentinel/policy-hash r1) (:sentinel/policy-hash r2)))))

(deftest sentinel-report-hash-included-and-deterministic
  (let [a {:attestation/id "test-hash"}
        r1 (sentinel/sentinel-report a :public-bundle)
        r2 (sentinel/sentinel-report a :public-bundle)]
    (is (string? (:sentinel/report-hash r1)))
    (is (pos? (count (:sentinel/report-hash r1))))
    (is (= (:sentinel/report-hash r1) (:sentinel/report-hash r2)))
    (is (not= (:sentinel/report-hash r1) (:sentinel/input-hash r2))
        "report-hash must differ from input-hash")))

(deftest sentinel-report-blocked-on-public-sink-for-attestation
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        r (sentinel/sentinel-report a :public-bundle)]
    (is (= :blocked (:sentinel/decision r)))
    (is (= :sensitivity/internal (:sentinel/level r)))))

(deftest sentinel-report-allowed-on-safe-sink-for-attestation
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        r (sentinel/sentinel-report a :local)]
    (is (= :allowed (:sentinel/decision r)))))

(deftest sentinel-report-redaction-required-for-private
  (let [r (sentinel/sentinel-report {:attestation/id "t" :attestation/claim-id :c} :local)]
    (is (true? (:sentinel/redaction-required? r)))))

(deftest sentinel-report-override-single-for-private
  (let [r (sentinel/sentinel-report {:attestation/id "t"} :public-bundle)]
    (is (= :single (get-in r [:sentinel/override-required? :mode])))))

(deftest sentinel-report-override-multi-party-for-critical-private
  (let [r (sentinel/sentinel-report {} :public-bundle)]
    (is (= :multi-party-approval (get-in r [:sentinel/override-required? :mode])))))

(deftest sentinel-report-override-multi-party-for-critical-risk
  (let [artifact {:scenario-id "s99"
                  :sensitivity/level :sensitivity/private
                  :sensitivity/risk-meta {:risk-severity :risk-severity/critical
                                          :value-at-risk "15,000,000"}}
        r (sentinel/sentinel-report artifact :public-bundle)]
    (is (= :multi-party-approval (get-in r [:sentinel/override-required? :mode]))
        "critical risk escalates override to multi-party even at private level")
    (is (= :sensitivity/private (:sentinel/level r)))))

(deftest sentinel-report-includes-declared-level
  (let [artifact {:scenario-id "bench-result"
                  :sensitivity/level :sensitivity/private}
        r (sentinel/sentinel-report artifact :local)]
    (is (= :sensitivity/private (:sentinel/declared-level r)))
    (is (= :sensitivity/private (:sentinel/level r)))))

(deftest sentinel-report-includes-structural-level
  (let [artifact {:scenario-id "bench-result"
                  :sensitivity/level :sensitivity/private}
        r (sentinel/sentinel-report artifact :local)]
    (is (= :sensitivity/internal (:sentinel/structural-level r))
        "scenario-id without other markers classifies as internal structurally")
    (is (= :sensitivity/private (:sentinel/level r))
        "final level is raised by declared floor from internal to private")))

(deftest sentinel-report-includes-risk-meta
  (let [artifact {:node-hash "sha256:n"
                  :sensitivity/level :sensitivity/private
                  :sensitivity/risk-meta {:risk-severity :risk-severity/high
                                          :value-at-risk "10,000,000"
                                          :risk-vector "Withdrawal race"}}
        r (sentinel/sentinel-report artifact :local)]
    (is (= :risk-severity/high (get-in r [:sentinel/risk-meta :risk-severity])))
    (is (= "10,000,000" (get-in r [:sentinel/risk-meta :value-at-risk])))
    (is (= "Withdrawal race" (get-in r [:sentinel/risk-meta :risk-vector])))))

(deftest sentinel-report-includes-declared-reason-codes
  (let [artifact {:node-hash "sha256:n"
                  :sensitivity/level :sensitivity/private
                  :sensitivity/risk-meta {:reason-codes [:contains-live-vulnerability
                                                         :contains-protocol-identifier]}}
        r (sentinel/sentinel-report artifact :local)]
    (is (some #{:contains-live-vulnerability} (:sentinel/reasons r))
        "declared reason codes must appear in reasons list")
    (is (some #{:contains-protocol-identifier} (:sentinel/reasons r))
        "declared reason codes must appear in reasons list")
    (is (some #{:contains-unpublished-evidence} (:sentinel/reasons r))
        "structural reasons must still be present")))

;; ── Propagation helpers ────────────────────────────────────────────────────

(deftest scenario-sensitivity-extracts-block
  (let [scenario {:scenario-id "s99"
                  :scenario/sensitivity {:level :sensitivity/private
                                         :risk-meta {:value-at-risk "15,000,000"
                                                     :risk-severity :risk-severity/critical}}}
        result (prop/scenario-sensitivity scenario)]
    (is (= :sensitivity/private (:level result)))
    (is (= "15,000,000" (get-in result [:risk-meta :value-at-risk])))))

(deftest scenario-sensitivity-returns-nil-for-no-block
  (let [scenario {:scenario-id "s01"}]
    (is (nil? (prop/scenario-sensitivity scenario)))))

(deftest attach-sensitivity-adds-keys
  (let [artifact {:node-hash "sha256:n"}
        sens {:sentinel/effective-level :sensitivity/private
              :sentinel/risk-meta {:value-at-risk "5M"}}
        result (prop/attach-sensitivity artifact sens)]
    (is (= :sensitivity/private (:sensitivity/level result)))
    (is (= "5M" (get-in result [:sensitivity/risk-meta :value-at-risk])))))

(deftest attach-sensitivity-uses-policy-output-for-evidence-nodes
  (let [artifact {:node-hash "sha256:n" :policy-output {:visible {} :excluded-classes #{}}}
        sens {:sentinel/effective-level :sensitivity/private
              :sentinel/risk-meta {:value-at-risk "5M"}}
        result (prop/attach-sensitivity artifact sens)]
    (is (= :sensitivity/private (get-in result [:policy-output :sensitivity :level])))
    (is (= "5M" (get-in result [:policy-output :sensitivity :risk-meta :value-at-risk])))))

(deftest attach-sensitivity-ignores-malformed-sensitivity
  (testing "an empty sensitivity map must not be attached as provenance without a level"
    (let [artifact {:node-hash "sha256:n"}]
      (is (= artifact (prop/attach-sensitivity artifact {}))
          "empty map attaches nothing")
      (is (= artifact (prop/attach-sensitivity artifact {:sentinel/effective-level nil}))
          "nil level attaches nothing")
      (is (= artifact (prop/attach-sensitivity artifact {:sentinel/decision :allowed}))
          "a map without :sentinel/effective-level attaches nothing")
      (is (= artifact (prop/attach-sensitivity artifact :not-a-map))
          "a non-map sensitivity attaches nothing"))))

(deftest attach-sensitivity-attaches-attestation-metadata
  (let [artifact {:attestation/id "sha256:a"}
        sens {:sentinel/effective-level :sensitivity/private}
        result (prop/attach-sensitivity artifact sens)]
    (is (= sens (get-in result [:attestation/metadata :sensitivity])))))

(deftest attach-sensitivity-attestation-roundtrip-is-readable
  (testing "a sensitivity attached to an attestation is extractable by artifact-sensitivity"
    (let [artifact {:attestation/id "sha256:a"}
          sens {:sentinel/effective-level :sensitivity/private
                :sentinel/risk-meta {:value-at-risk "1M"}}
          attached (prop/attach-sensitivity artifact sens)
          extracted (prop/artifact-sensitivity attached)]
      (is (= :sensitivity/private (:level extracted))
          "nested :attestation/metadata :sensitivity provenance is readable")
      (is (= {:value-at-risk "1M"} (:risk-meta extracted))))))

(deftest merge-sensitivity-picks-highest
  (let [sensitivities [{:level :sensitivity/internal}
                       {:level :sensitivity/private}
                       {:level :sensitivity/public}]
        result (prop/merge-sensitivity sensitivities)]
    (is (= :sensitivity/private (:level result)))))

(deftest merge-sensitivity-handles-nils
  (let [sensitivities [nil {:level :sensitivity/internal} nil]
        result (prop/merge-sensitivity sensitivities)]
    (is (= :sensitivity/internal (:level result)))))

(deftest merge-sensitivity-returns-nil-for-empty
  (is (nil? (prop/merge-sensitivity [])))
  (is (nil? (prop/merge-sensitivity [nil nil]))))

(deftest artifact-sensitivity-extracts-from-artifact
  (let [artifact {:node-hash "sha256:n"
                  :sensitivity/level :sensitivity/private
                  :sensitivity/risk-meta {:value-at-risk "1M"}}
        result (prop/artifact-sensitivity artifact)]
    (is (= :sensitivity/private (:level result)))
    (is (= "1M" (get-in result [:risk-meta :value-at-risk])))))

;; ── Validation tests ────────────────────────────────────────────────────────

(deftest validate-sensitivity-valid-minimal
  (is (nil? (prop/validate-sensitivity {:level :sensitivity/private}))))

(deftest validate-sensitivity-valid-full
  (let [sens {:level :sensitivity/private
              :risk-meta {:value-at-risk "15,000,000"
                          :risk-severity :risk-severity/critical
                          :risk-vector "Withdrawal race"
                          :reason-codes [:contains-live-vulnerability]}}]
    (is (nil? (prop/validate-sensitivity sens)))))

(deftest validate-sensitivity-invalid-level
  (let [errors (prop/validate-sensitivity {:level :sensitivity/nonexistent})]
    (is (vector? errors))
    (is (some #(= :level (first (:path %))) errors))))

(deftest validate-sensitivity-invalid-risk-severity
  (let [errors (prop/validate-sensitivity {:level :sensitivity/private
                                           :risk-meta {:risk-severity :extreme}})]
    (is (vector? errors))
    (is (some #(= :risk-severity (last (:path %))) errors))))

(deftest validate-sensitivity-malformed-risk-meta
  (let [errors (prop/validate-sensitivity {:level :sensitivity/private
                                           :risk-meta "not-a-map"})]
    (is (vector? errors))
    (is (some #(= :risk-meta (first (:path %))) errors))))

(deftest validate-sensitivity-malformed-reason-codes
  (let [errors (prop/validate-sensitivity {:level :sensitivity/private
                                           :risk-meta {:reason-codes "not-a-vector"}})]
    (is (vector? errors))
    (is (some #(= :reason-codes (last (:path %))) errors))))

(deftest validate-sensitivity-non-keyword-reason-codes
  (let [errors (prop/validate-sensitivity {:level :sensitivity/private
                                           :risk-meta {:reason-codes ["string-not-keyword"]}})]
    (is (vector? errors))
    (is (some #(= :reason-codes (last (:path %))) errors))))

;; ── Effective sensitivity tests ─────────────────────────────────────────────

(deftest effective-sensitivity-no-declaration
  (let [artifact {:scenario-id "s42"}
        result (prop/effective-sensitivity artifact nil)]
    (is (= :sensitivity/internal (:sentinel/effective-level result)))
    (is (nil? (:sentinel/declared-level result)))
    (is (= :sensitivity/internal (:sentinel/structural-level result)))))

(deftest effective-sensitivity-declared-floor
  (let [artifact {:scenario-id "s42"}
        scenario-sens {:level :sensitivity/private}
        result (prop/effective-sensitivity artifact scenario-sens)]
    (is (= :sensitivity/private (:sentinel/effective-level result))
        "declared private must raise effective level from internal to private")
    (is (= :sensitivity/internal (:sentinel/structural-level result)))
    (is (= :sensitivity/private (:sentinel/declared-level result)))))

(deftest effective-sensitivity-cannot-downgrade
  (let [artifact {:node-hash "sha256:n1"
                  :result {:status :fail
                           :failure-details [{:message "exploit"}]}}
        scenario-sens {:level :sensitivity/public}
        result (prop/effective-sensitivity artifact scenario-sens)]
    (is (= :sensitivity/private (:sentinel/effective-level result))
        "structural private must override declared public")))

(deftest effective-sensitivity-includes-declared-reasons
  (let [artifact {:scenario-id "s42"}
        scenario-sens {:level :sensitivity/private
                       :risk-meta {:reason-codes [:contains-live-vulnerability]}}
        result (prop/effective-sensitivity artifact scenario-sens)]
    (is (some #{:contains-live-vulnerability} (:sentinel/reasons result)))
    (is (some #{:contains-unpublished-evidence} (:sentinel/reasons result)))))

(deftest effective-sensitivity-sources
  (let [artifact {:scenario-id "s42"}
        scenario-sens {:level :sensitivity/private}
        result (prop/effective-sensitivity artifact scenario-sens)]
    (is (some #(re-find #"scenario:" %) (:sentinel/sources result)))
    (is (some #(re-find #"declared-floor" %) (:sentinel/sources result)))))

;; ── Evidence-backed classification ──────────────────────────────────────────

(def ^:private sample-finding-private-key
  {:finding/id "finding-1" :finding/path-token "path-abc"
   :rule/id :secret-scanner/private-key :rule/version "v2"})

(def ^:private sample-finding-credential
  {:finding/id "finding-2" :finding/path-token "path-abc"
   :rule/id :secret-scanner/credential-assignment :rule/version "v2"})

(def ^:private sample-finding-jwt
  {:finding/id "finding-3" :finding/path-token "path-def"
   :rule/id :secret-scanner/jwt-token :rule/version "v2"})

(def ^:private sample-finding-github-token
  {:finding/id "finding-4" :finding/path-token "path-def"
   :rule/id :secret-scanner/github-token :rule/version "v2"})

(deftest classify-from-findings-private-key
  (let [result (sentinel/classify-from-findings [sample-finding-private-key])]
    (is (= :sensitivity/private (:level result)))
    (is (= [:contains-live-vulnerability] (:reasons result)))
    (is (= ["finding-1"] (:findings result)))))

(deftest classify-from-findings-credential
  (let [result (sentinel/classify-from-findings [sample-finding-credential])]
    (is (= :sensitivity/private (:level result)))
    (is (= [:contains-unpublished-evidence] (:reasons result)))))

(deftest classify-from-findings-jwt-token
  (let [result (sentinel/classify-from-findings [sample-finding-jwt])]
    (is (= :sensitivity/internal (:level result)))
    (is (= [:contains-protocol-identifier] (:reasons result)))))

(deftest classify-from-findings-github-token
  (let [result (sentinel/classify-from-findings [sample-finding-github-token])]
    (is (= :sensitivity/internal (:level result)))
    (is (= [:contains-linkable-subject-hash] (:reasons result)))))

(deftest classify-from-findings-picks-highest-level
  (let [result (sentinel/classify-from-findings [sample-finding-jwt
                                                 sample-finding-private-key])]
    (is (= :sensitivity/private (:level result))
        "private-key finding must dominate jwt-token finding")
    (is (contains? (set (:reasons result)) :contains-live-vulnerability))
    (is (contains? (set (:reasons result)) :contains-protocol-identifier))))

(deftest classify-from-findings-nil-for-empty
  (is (nil? (sentinel/classify-from-findings [])))
  (is (nil? (sentinel/classify-from-findings nil))))

(deftest classify-from-findings-unknown-rule-defaults-critical-private
  (let [result (sentinel/classify-from-findings
                [{:finding/id "finding-x" :finding/path-token "path-x"
                  :rule/id :unknown/rule :rule/version "v1"}])]
    (is (= :sensitivity/critical-private (:level result))
        "unknown rule id must default to critical-private")
    (is (= [:contains-unpublished-evidence] (:reasons result)))))

(deftest evidence-backed-classification-via-sensitivity-findings
  (let [artifact {:sensitivity/findings [sample-finding-private-key]}
        result (sentinel/classify-structural artifact)]
    (is (= :sensitivity/private result)
        "classify-structural must use sensitivity-findings for evidence-backed level")))

(deftest evidence-backed-classification-via-safety-findings
  (let [artifact {:safety/findings [sample-finding-jwt]}
        result (sentinel/classify-structural artifact)]
    (is (= :sensitivity/internal result)
        "classify-structural must use safety-findings for evidence-backed level")))

(deftest classify-structural-uses-evidence-before-heuristics
  (let [artifact {:scenario-id "bench-result"
                  :sensitivity/findings [sample-finding-private-key]}]
    (is (= :sensitivity/private (sentinel/classify-structural artifact))
        "evidence-backed private must take precedence over heuristic internal")))

(deftest classify-structural-falls-back-to-heuristics-without-findings
  (let [artifact {:scenario-id "bench-result"}]
    (is (= :sensitivity/internal (sentinel/classify-structural artifact))
        "without findings, classify-structural must fall back to heuristics")))

(deftest classify-structural-nil-returns-critical-private
  (is (= :sensitivity/critical-private (sentinel/classify-structural nil))
      "nil artifact must default to critical-private")
  (is (= :sensitivity/critical-private (sentinel/classify-structural {}))
      "empty artifact must default to critical-private")
  (is (= :sensitivity/internal (sentinel/classify-structural {:scenario-id "s42"}))
      "artifact with scenario-id must get heuristic internal, proving nil vs artifact differs"))

(deftest sentinel-report-includes-finding-reasons
  (let [artifact {:scenario-id "s42"
                  :sensitivity/findings [sample-finding-private-key]}
        r (sentinel/sentinel-report artifact :local)]
    (is (some #{:contains-live-vulnerability} (:sentinel/reasons r))
        "finding reason codes must appear in sentinel report reasons")
    (is (= :sensitivity/private (:sentinel/level r))
        "evidence-backed level must be used in sentinel report")))

(deftest sentinel-report-includes-evidence-findings
  (let [artifact {:scenario-id "s42"
                  :sensitivity/findings [sample-finding-private-key]}
        r (sentinel/sentinel-report artifact :local)]
    (is (some? (:sentinel/evidence-findings r))
        "sentinel report must include :sentinel/evidence-findings when findings present")
    (is (= 1 (count (:sentinel/evidence-findings r))))))

(deftest sentinel-report-includes-evidence-findings-via-safety
  (let [artifact {:scenario-id "s42"
                  :safety/findings [sample-finding-jwt]}
        r (sentinel/sentinel-report artifact :local)]
    (is (some? (:sentinel/evidence-findings r))
        "sentinel report must include :sentinel/evidence-findings when safety/findings present")
    (is (= 1 (count (:sentinel/evidence-findings r))))))

(deftest sentinel-report-omits-evidence-findings-without-findings
  (let [artifact {:scenario-id "s42"}
        r (sentinel/sentinel-report artifact :local)]
    (is (nil? (:sentinel/evidence-findings r))
        "sentinel report must not include :sentinel/evidence-findings when no findings")))

(deftest effective-sensitivity-uses-evidence-reasons
  (let [artifact {:scenario-id "s42"
                  :sensitivity/findings [sample-finding-jwt]}
        scenario-sens {:level :sensitivity/public}
        result (prop/effective-sensitivity artifact scenario-sens)]
    (is (some #{:contains-protocol-identifier} (:sentinel/reasons result))
        "evidence finding reasons must appear in effective-sensitivity reasons")))

(deftest effective-sensitivity-evidence-source-included
  (let [artifact {:scenario-id "s42"
                  :sensitivity/findings [sample-finding-jwt]}
        result (prop/effective-sensitivity artifact nil)]
    (is (some #{"evidence/safety-findings"} (:sentinel/sources result))
        "evidence/safety-findings source must be present when findings influence level")))

(deftest effective-sensitivity-evidence-level-overrides-structural
  (let [artifact {:node-hash "sha256:n1"
                  :result {:status :pass}
                  :sensitivity/findings [sample-finding-private-key]}
        result (prop/effective-sensitivity artifact nil)]
    (is (= :sensitivity/private (:sentinel/effective-level result))
        "evidence-backed private must override structural pass default")))

;; ── Override enforcement tests ──────────────────────────────────────────────

(deftest override-check-multi-party-required-with-critical-risk
  (let [level :sensitivity/private
        risk-meta {:risk-severity :risk-severity/critical}
        approvals [{:approved-by "attestor-1" :approved-at "2025-01-01" :reason "fix deployed"}
                   {:approved-by "attestor-2" :approved-at "2025-01-02" :reason "window opened"}]]
    (is (true? (:satisfied? (sentinel/check-override-requirements! level risk-meta approvals))))))

(deftest override-check-multi-party-fails-with-insufficient-approvals
  (let [level :sensitivity/private
        risk-meta {:risk-severity :risk-severity/critical}
        approvals [{:approved-by "attestor-1" :approved-at "2025-01-01" :reason "fix deployed"}]]
    (is (thrown? Exception (sentinel/check-override-requirements! level risk-meta approvals)))))

(deftest override-check-no-approvals-required-for-public
  (let [level :sensitivity/public
        risk-meta nil
        approvals []]
    (is (true? (:satisfied? (sentinel/check-override-requirements! level risk-meta approvals))))))

(deftest override-check-single-approval-for-private
  (let [level :sensitivity/private
        risk-meta nil
        approvals [{:approved-by "attestor-1" :approved-at "2025-01-01" :reason "embargo lifted"}]]
    (is (true? (:satisfied? (sentinel/check-override-requirements! level risk-meta approvals))))))

(deftest override-check-single-fails-without-approval-for-private
  (let [level :sensitivity/private
        risk-meta nil
        approvals []]
    (is (thrown? Exception (sentinel/check-override-requirements! level risk-meta approvals)))))

;; ── Downgrade prevention tests ──────────────────────────────────────────────

(deftest no-downgrade-passes-for-same-level
  (let [artifact {:sensitivity/level :sensitivity/private}]
    (is (nil? (prop/assert-no-downgrade! artifact :sensitivity/private "test")))))

(deftest no-downgrade-throws-for-lower-level
  (let [artifact {:sensitivity/level :sensitivity/private}]
    (is (thrown? Exception (prop/assert-no-downgrade! artifact :sensitivity/public "test")))))

(deftest no-downgrade-passes-for-higher-level
  (let [artifact {:sensitivity/level :sensitivity/internal}]
    (is (nil? (prop/assert-no-downgrade! artifact :sensitivity/private "test")))))

(deftest no-downgrade-checks-extensions
  (let [artifact {:extensions {:sensitivity/level :sensitivity/private}}]
    (is (thrown? Exception (prop/assert-no-downgrade! artifact :sensitivity/public "test")))))

(deftest merge-no-downgrade-passes
  (let [merged {:level :sensitivity/private}
        inputs [{:level :sensitivity/internal} {:level :sensitivity/public}]]
    (is (nil? (prop/assert-merge-no-downgrade! merged inputs)))))

(deftest merge-no-downgrade-throws
  (let [merged {:level :sensitivity/internal}
        inputs [{:level :sensitivity/private}]]
    (is (thrown? Exception (prop/assert-merge-no-downgrade! merged inputs)))))

(deftest serialization-preserves-essential-fields
  (let [original {:sentinel/structural-level :sensitivity/internal
                  :sentinel/declared-level :sensitivity/private
                  :sentinel/effective-level :sensitivity/private
                  :sentinel/reasons [:contains-unpublished-evidence]}]
    (is (nil? (prop/assert-no-serialization-loss! original original "json-roundtrip")))))

(deftest serialization-detects-changes
  (let [original {:sentinel/structural-level :sensitivity/internal
                  :sentinel/effective-level :sensitivity/private}
        readback {:sentinel/structural-level :sensitivity/public
                  :sentinel/effective-level :sensitivity/public}]
    (is (thrown? Exception (prop/assert-no-serialization-loss! original readback "test")))))

;; ── Attach sensitivity to evidence nodes ────────────────────────────────────

(deftest attach-sensitivity-to-evidence-node-uses-policy-output
  (let [artifact {:node-hash "sha256:n" :policy-output {:visible {} :excluded-classes #{}}}
        sensitivity {:sentinel/effective-level :sensitivity/private
                     :sentinel/risk-meta {:value-at-risk "10M"}}
        result (prop/attach-sensitivity artifact sensitivity)]
    (is (= :sensitivity/private (get-in result [:policy-output :sensitivity :level])))
    (is (= "10M" (get-in result [:policy-output :sensitivity :risk-meta :value-at-risk])))))

(deftest attach-sensitivity-to-attestation-uses-metadata
  (let [artifact {:attestation/id "sha256:att1"}
        sensitivity {:sentinel/effective-level :sensitivity/private
                     :sentinel/structural-level :sensitivity/internal}
        result (prop/attach-sensitivity artifact sensitivity)]
    (is (= :sensitivity/private (get-in result [:attestation/metadata :sensitivity :sentinel/effective-level]))
        "attestation sensitivity must use :attestation/metadata")))

;; ── Build provenance tests ─────────────────────────────────────────────────

(deftest build-provenance-from-effective
  (let [effective {:sentinel/structural-level :sensitivity/internal
                   :sentinel/declared-level :sensitivity/private
                   :sentinel/effective-level :sensitivity/private
                   :sentinel/reasons [:contains-unpublished-evidence]
                   :sentinel/sources ["scenario:s99"]}
        provenance (prop/build-sensitivity-derivation effective)]
    (is (= :sensitivity/private (:sentinel/effective-level provenance)))
    (is (= :sensitivity/internal (:sentinel/structural-level provenance)))
    (is (= :sensitivity/private (:sentinel/declared-level provenance)))))

(deftest build-provenance-appends-extra-sources
  (let [effective {:sentinel/effective-level :sensitivity/private
                   :sentinel/reasons []}
        provenance (prop/build-sensitivity-derivation effective "extra-context" "s99")]
    (is (some #(re-find #"extra-context" %) (:sentinel/sources provenance)))
    (is (some #(re-find #"s99" %) (:sentinel/sources provenance)))))

;; ── Aggregation tests ──────────────────────────────────────────────────────

(deftest merge-sensitivity-orders-correctly
  (let [sensitivities [{:level :sensitivity/public}
                       {:level :sensitivity/internal}
                       {:level :sensitivity/private}
                       {:level :sensitivity/embargoed}
                       {:level :sensitivity/critical-private}]
        result (prop/merge-sensitivity sensitivities)]
    (is (= :sensitivity/critical-private (:level result)))))

(deftest merge-sensitivity-picks-highest-risk-severity
  (let [sensitivities [{:level :sensitivity/private
                        :risk-meta {:risk-severity :risk-severity/high}}
                       {:level :sensitivity/internal
                        :risk-meta {:risk-severity :risk-severity/critical}}]
        result (prop/merge-sensitivity sensitivities)]
    (is (= :sensitivity/private (:level result)))
    (is (= :risk-severity/critical (get-in result [:risk-meta :risk-severity]))
        "must pick critical risk-severity over high")))

(deftest merge-sensitivity-picks-first-risk-meta
  (let [sensitivities [{:level :sensitivity/private
                        :risk-meta {:value-at-risk "5M"}}
                       {:level :sensitivity/internal
                        :risk-meta {:value-at-risk "10M"}}]
        result (prop/merge-sensitivity sensitivities)]
    (is (= :sensitivity/private (:level result)))))

;; ── Override mode tests ─────────────────────────────────────────────────────

(deftest override-mode-single-for-default
  (is (= :single (sentinel/effective-override-mode :sensitivity/private nil))))

(deftest override-mode-single-for-internal
  (is (= :single (sentinel/effective-override-mode :sensitivity/internal nil))))

(deftest override-mode-multi-for-critical-private
  (is (= :multi-party-approval (sentinel/effective-override-mode :sensitivity/critical-private nil))))

(deftest override-mode-single-for-embargoed
  (is (= :single (sentinel/effective-override-mode :sensitivity/embargoed nil))))

(deftest override-mode-multi-for-critical-risk-severity
  (is (= :multi-party-approval (sentinel/effective-override-mode :sensitivity/private {:risk-severity :risk-severity/critical})))
  (is (= :multi-party-approval (sentinel/effective-override-mode :sensitivity/public {:risk-severity :risk-severity/critical}))))

(deftest override-mode-single-for-high-or-lower-risk
  (is (= :single (sentinel/effective-override-mode :sensitivity/private {:risk-severity :risk-severity/high})))
  (is (= :single (sentinel/effective-override-mode :sensitivity/private {:risk-severity :risk-severity/medium})))
  (is (= :single (sentinel/effective-override-mode :sensitivity/private {:risk-severity :risk-severity/low}))))

;; ── Assertion functions ─────────────────────────────────────────────────────

(deftest assert-allowed-passes
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        result (sentinel/assert-disclosure-allowed! a {:sink :local})]
    (is (map? result))
    (is (= :allowed (:sentinel/decision result)))))

(deftest assert-blocked-throws
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-disclosure-allowed! a {:sink :public-bundle})))))

(deftest assert-export-allowed
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-export-allowed! a {:sink :ipfs})))
    (is (map? (sentinel/assert-export-allowed! a {:sink :local})))))

(deftest assert-publish-allowed
  (let [node {:node-hash "sha256:n" :result {:status :pass}}]
    (is (thrown? Exception
                 (sentinel/assert-publish-allowed! node {:sink :nostr-public-relay})))))

(deftest assert-relay-allowed
  (let [sealed-event {:node-hash "sha256:sealed" :result {:status :pass}}]
    (is (thrown? Exception
                 (sentinel/assert-relay-allowed! sealed-event {:sink :ipfs})))
    (is (map? (sentinel/assert-relay-allowed! sealed-event {:sink :local})))))

(deftest assert-attestation-allowed
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-attestation-allowed! a {:sink :on-chain-registry})))))

;; ── remote authority required for force-auth add-held evidence ──────────────

(deftest remote-authority-required-for-add-held-artifacts
  (testing "force-auth add-held member and summary artifacts require remote
            authority regardless of sink"
    (is (true? (sentinel/remote-authority-required-artifact?
                {:artifact/kind :force-auth-add-held})))
    (is (true? (sentinel/remote-authority-required-artifact?
                {:artifact/kind :force-auth-add-held-summary})))
    (is (true? (sentinel/remote-authority-required-artifact?
                {:artifact/kind :force-auth-add-held :held/action :add-held})))
    (is (true? (sentinel/remote-authority-required-artifact?
                {:held/action "add-held"}))))
  (testing "non add-held artifacts do not require artifact-level remote authority"
    (is (false? (sentinel/remote-authority-required-artifact?
                 {:artifact/kind :evidence-node})))
    (is (false? (sentinel/remote-authority-required-artifact? {})))))

(deftest force-auth-add-held-classified-private
  (is (= :sensitivity/private (sentinel/classify {:artifact/kind :force-auth-add-held})))
  (is (= :sensitivity/private (sentinel/classify {:artifact/kind :force-auth-add-held-summary}))))

(deftest force-auth-add-held-reasons-surfaced-in-report
  (let [r (sentinel/sentinel-report {:artifact/kind :force-auth-add-held} :local)]
    (is (some #{:contains-force-auth-add-held} (:sentinel/reasons r))))
  (let [r (sentinel/sentinel-report {:artifact/kind :force-auth-add-held-summary} :local)]
    (is (some #{:contains-force-auth-add-held-summary} (:sentinel/reasons r)))))

(deftest remote-authority-required-artifact-kinds-committed-in-policy
  (is (= #{:force-auth-add-held :force-auth-add-held-summary}
         sentinel/remote-authority-required-artifact-kinds)))
