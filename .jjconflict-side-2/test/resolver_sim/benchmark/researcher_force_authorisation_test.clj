(ns resolver-sim.benchmark.researcher-force-authorisation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.review-round :as rr]))

;; ── Mock key infrastructure ──────────────────────────────────────────────
;; Avoids system ssh-keygen dependency. The signing function is redef'd
;; to return a deterministic mock signature.

(def ^:private mock-sig-hex
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(def ^:private mock-request-root "sha256:mock-request")
(def ^:private mock-round-hash "sha256:mock-round-hash")

(defn- mock-decision
  "Build a mock signed decision reference for testing.
   Does not require real keys — uses a mock signature."
  [researcher-id decision & {:keys [dissent-reason]}]
  (with-redefs [signing/sign-hash (fn [_ _ _] mock-sig-hex)]
    (rfa/build-signed-decision researcher-id :authorisation/test-001
                               mock-request-root mock-round-hash
                               decision "/dev/null"
                               :dissent-reason dissent-reason)))

(defn- mock-key-resolver
  "Mock public key resolver for testing."
  [researcher-id]
  "/dev/null")

;; ── Shared test data ─────────────────────────────────────────────────────

(def sample-policy-ref
  {:policy/id :research/three-member-force-authorisation
   :policy/version 1
   :policy/schema-version "force-authorisation-policy.v1"
   :policy/hash "sha256:policy-hash"})

(def sample-round-ref
  {:review-round/id "sha256:round-hash"
   :review-round/hash "sha256:round-hash"})

(def sample-target
  {:target/kind :benchmark-branch
   :target/baseline-content-root "sha256:baseline"
   :target/branch-descriptor-hash "sha256:branch-desc"
   :target/proposed-content-root "sha256:proposed"})

(defn- build-auth
  "Helper to build a force-authorisation artifact for testing."
  [& {:keys [decisions threshold]
      :or {threshold {:required 2 :eligible 3}
           decisions [(mock-decision "researcher-a" :approve)
                      (mock-decision "researcher-b" :approve)]}}]
  (rfa/build-authorisation
   {:authorisation/id :authorisation/test-001
    :authorisation/policy sample-policy-ref
    :authorisation/review-round sample-round-ref
    :authorisation/request-root "sha256:request"
    :authorisation/target sample-target
    :authorisation/decision-references decisions
    :authorisation/threshold threshold}))

;; ── Decision signing ─────────────────────────────────────────────────────

(deftest build-signed-decision-approve
  (let [dec (mock-decision "researcher-a" :approve)]
    (is (= "researcher-a" (:researcher/id dec)))
    (is (= :approve (:decision dec)))
    (is (some? (:decision/hash dec)))
    (is (some? (:signature dec)))
    (is (nil? (:dissent/reason dec)))))

(deftest build-signed-decision-dissent-with-reason
  (let [dec (mock-decision "researcher-c" :dissent :dissent-reason "derivation not supported")]
    (is (= "researcher-c" (:researcher/id dec)))
    (is (= :dissent (:decision dec)))
    (is (= "derivation not supported" (:dissent/reason dec)))))

(deftest build-signed-decision-rejects-invalid-decision
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid decision"
                        (mock-decision "researcher-a" :bogus))))

(deftest build-signed-decision-rejects-dissent-without-reason
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dissent requires a reason"
                        (mock-decision "researcher-a" :dissent))))

;; ── Authorisation building ───────────────────────────────────────────────

(deftest two-of-three-approved
  (let [auth (build-auth)]
    (is (rfa/authorisation-valid? auth))
    (is (rfa/authorisation-approved? auth))
    (is (= :approved (rfa/authorisation-status auth)))
    (is (some? (:authorisation/hash auth)))
    (is (some? (:authorisation/consumption-key auth)))
    (let [th (:authorisation/threshold auth)]
      (is (= 2 (:approved th)))
      (is (= 0 (:dissented th))))))

(deftest two-of-three-with-dissent
  (let [decisions [(mock-decision "researcher-a" :approve)
                   (mock-decision "researcher-b" :approve)
                   (mock-decision "researcher-c" :dissent :dissent-reason "methodology concern")]
        auth (build-auth :decisions decisions)]
    (is (rfa/authorisation-approved? auth))
    (is (= :approved-with-dissent (rfa/authorisation-status auth)))
    (let [th (:authorisation/threshold auth)]
      (is (= 2 (:approved th)))
      (is (= 1 (:dissented th))))))

(deftest one-of-three-declined
  (let [decisions [(mock-decision "researcher-a" :approve)]
        auth (build-auth :decisions decisions :threshold {:required 2 :eligible 3})]
    (is (not (rfa/authorisation-approved? auth)))
    (is (= :declined (rfa/authorisation-status auth)))
    (let [th (:authorisation/threshold auth)]
      (is (= 1 (:approved th)))
      (is (= 0 (:dissented th))))))

(deftest unanimous-approved
  (let [decisions [(mock-decision "researcher-a" :approve)
                   (mock-decision "researcher-b" :approve)
                   (mock-decision "researcher-c" :approve)]
        auth (build-auth :decisions decisions :threshold {:required 3 :eligible 3})]
    (is (rfa/authorisation-approved? auth))
    (is (= :approved (rfa/authorisation-status auth)))
    (is (= 3 (get-in auth [:authorisation/threshold :approved])))))

(deftest pre-checks-reject-missing-policy
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pre-conditions"
                        (rfa/build-authorisation
                         {:authorisation/id :authorisation/test-001
                          :authorisation/review-round sample-round-ref
                          :authorisation/request-root "sha256:r"
                          :authorisation/target sample-target
                          :authorisation/decision-references [(mock-decision "a" :approve)]
                          :authorisation/threshold {:required 2 :eligible 3}}))))

(deftest pre-checks-reject-missing-target
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pre-conditions"
                        (rfa/build-authorisation
                         {:authorisation/id :authorisation/test-001
                          :authorisation/policy sample-policy-ref
                          :authorisation/review-round sample-round-ref
                          :authorisation/request-root "sha256:r"
                          :authorisation/decision-references [(mock-decision "a" :approve)]
                          :authorisation/threshold {:required 2 :eligible 3}}))))

(deftest pre-checks-reject-duplicate-decision
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pre-conditions"
                        (rfa/build-authorisation
                         {:authorisation/id :authorisation/test-001
                          :authorisation/policy sample-policy-ref
                          :authorisation/review-round sample-round-ref
                          :authorisation/request-root "sha256:r"
                          :authorisation/target sample-target
                          :authorisation/decision-references [(mock-decision "a" :approve)
                                                              (mock-decision "a" :approve)]
                          :authorisation/threshold {:required 2 :eligible 3}}))))

;; ── Cross-artifact verification ──────────────────────────────────────────

(deftest verify-against-round-valid
  (let [round {:review-round/id "sha256:round-hash"
               :review-round/hash "sha256:round-hash"
               :review-round/members [{:researcher/id "researcher-a" :role :model-steward}
                                      {:researcher/id "researcher-b" :role :independent-reproducer}
                                      {:researcher/id "researcher-c" :role :adversarial-reviewer}]}
        decisions [(mock-decision "researcher-a" :approve)
                   (mock-decision "researcher-b" :approve)]
        auth (build-auth :decisions decisions)
        result (rfa/verify-against-round round auth)]
    (is (:valid? result))))

(deftest verify-against-round-rejects-non-member
  (let [round {:review-round/id "sha256:round-hash"
               :review-round/hash "sha256:round-hash"
               :review-round/members [{:researcher/id "researcher-a" :role :model-steward}
                                      {:researcher/id "researcher-b" :role :independent-reproducer}]}
        decisions [(mock-decision "researcher-a" :approve)
                   (mock-decision "non-member" :approve)]
        auth (build-auth :decisions decisions)
        result (rfa/verify-against-round round auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"not a member" %) (:errors result)))))

;; ── Authorisation valid? ─────────────────────────────────────────────────

(deftest authorisation-valid-detects-missing-hash
  (let [auth (build-auth)
        bad (dissoc auth :authorisation/hash)]
    (is (not (rfa/authorisation-valid? bad)))))

(deftest authorisation-valid-detects-wrong-status
  (let [auth (build-auth)
        bad (assoc auth :authorisation/decision-status :bogus)]
    (is (not (rfa/authorisation-valid? bad)))))

(deftest validate-authorisation-rejects-tampered-hash
  (let [auth (build-auth)
        bad (assoc auth :authorisation/hash "sha256:fake")
        result (rfa/validate-authorisation bad)]
    (is (not (:valid? result)))))

(deftest validate-authorisation-valid
  (let [auth (build-auth)
        result (rfa/validate-authorisation auth)]
    (is (:valid? result))))

;; ── verify-decision-signatures ───────────────────────────────────────────

(deftest decision-signatures-verify
  (let [decisions [(mock-decision "researcher-a" :approve)
                   (mock-decision "researcher-b" :approve)]
        auth (build-auth :decisions decisions)
        resolver (fn [id] "/dev/null")]
    (with-redefs [signing/verify-signature (fn [hash sig _] (= sig mock-sig-hex))]
      (let [result (rfa/verify-decision-signatures resolver auth)]
        (is (:valid? result))
        (is (every? :valid? (:results result)))))))

;; ── verify-authorisation-usable ──────────────────────────────────────────

(deftest usable-checks-pass
  (let [auth (build-auth)
        result (rfa/verify-authorisation-usable auth)]
    (is (:usable? result))
    (is (empty? (:blocking-reasons result)))))

(deftest usable-detects-expired
  (let [auth (build-auth)
        expired (assoc auth :authorisation/expires-at "2020-01-01T00:00:00Z")
        result (rfa/verify-authorisation-usable expired)]
    (is (not (:usable? result)))
    (is (some #(re-find #"expired" %) (:blocking-reasons result)))))

(deftest usable-detects-consumed
  (let [auth (build-auth)
        result (rfa/verify-authorisation-usable
                auth :consumption-checker (constantly true))]
    (is (not (:usable? result)))
    (is (some #(re-find #"consumed" %) (:blocking-reasons result)))))

;; ── Consumption key ──────────────────────────────────────────────────────

(deftest consumption-key-present
  (let [auth (build-auth)]
    (is (some? (rfa/consumption-key auth)))
    (is (re-find #"^sha256:" (rfa/consumption-key auth)))))

(deftest consumption-key-deterministic
  (let [a (build-auth)
        b (build-auth)]
    (is (= (rfa/consumption-key a) (rfa/consumption-key b))
        "same inputs must produce same consumption-key")))

;; ── verify-against-policy ────────────────────────────────────────────────

(deftest verify-against-policy-passes
  (let [auth (build-auth)
        policy {"member_count" 3
                "threshold" 2
                "single_use?" true
                "preserve_dissent?" true
                "scope_required?" true}
        result (rfa/verify-against-policy policy auth)]
    (is (:valid? result))))

(deftest verify-against-policy-detects-insufficient-members
  (let [auth (build-auth :threshold {:required 2 :eligible 5})
        policy {"member_count" 3, "threshold" 2}
        result (rfa/verify-against-policy policy auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"member-count" %) (:errors result)))))

(deftest verify-against-policy-detects-below-threshold
  (let [auth (build-auth :threshold {:required 1 :eligible 3})
        policy {"member_count" 3, "threshold" 2}
        result (rfa/verify-against-policy policy auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"threshold" %) (:errors result)))))

;; ── Member-key cross-check tests ───────────────────────────────────────────

(def ^:private unkeyed-round
  {:review-round/id "sha256:unkeyed-round-hash"
   :review-round/hash "sha256:unkeyed-round-hash"
   :benchmark/content-root "sha256:cr"
   :review-round/purpose :model-admission
   :review-round/members
   [{:researcher/id "researcher-a" :role :model-steward}
    {:researcher/id "researcher-b" :role :independent-reproducer}
    {:researcher/id "researcher-c" :role :adversarial-reviewer}]
   :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
   :review-round/policy-root "sha256:policy"})

(def ^:private keyed-round
  {:review-round/id "sha256:keyed-round-hash"
   :review-round/hash "sha256:keyed-round-hash"
   :benchmark/content-root "sha256:cr"
   :review-round/purpose :model-admission
   :review-round/members
   [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
    {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
    {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}]
   :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
   :review-round/policy-root "sha256:policy"})

(deftest verify-against-round-keyed-round-derives-key-from-id
  (let [auth (build-auth :decisions [(mock-decision "researcher-a" :approve)
                                     (mock-decision "researcher-b" :approve)])
        result (rfa/verify-against-round keyed-round auth)]
    (is (:valid? result))
    (is (= [0 1] (:approval-member-keys result)))
    (is (empty? (:dissent-member-keys result)))))

(deftest verify-against-round-keyed-round-cross-checks-provided-key
  (let [decision-with-correct-key
        (assoc (mock-decision "researcher-a" :approve) :review-member/key 0)
        auth (build-auth :decisions [decision-with-correct-key
                                     (mock-decision "researcher-b" :approve)])
        result (rfa/verify-against-round keyed-round auth)]
    (is (:valid? result))
    (is (= [0 1] (:approval-member-keys result)))))

(deftest verify-against-round-key-mismatch-detected
  (let [decision-with-wrong-key
        (assoc (mock-decision "researcher-a" :approve) :review-member/key 2)
        auth (build-auth :decisions [decision-with-wrong-key
                                     (mock-decision "researcher-b" :approve)])
        result (rfa/verify-against-round keyed-round auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"member key mismatch.*researcher-a" %) (:errors result)))))

(deftest verify-against-round-keyed-ref-rejected-against-unkeyed-round
  (let [decision-with-key
        (assoc (mock-decision "researcher-a" :approve) :review-member/key 0)
        auth (build-auth :decisions [decision-with-key
                                     (mock-decision "researcher-b" :approve)])
        result (rfa/verify-against-round unkeyed-round auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #":member-key-unresolvable.*researcher-a" %)
              (:errors result)))))

(deftest verify-against-round-unkeyed-ref-passes-against-unkeyed-round
  (let [auth (build-auth :decisions [(mock-decision "researcher-a" :approve)
                                     (mock-decision "researcher-b" :approve)])
        result (rfa/verify-against-round unkeyed-round auth)]
    (is (:valid? result))
    (is (not (contains? result :approval-member-keys))
        "unkeyed round should not emit key vectors")))

;; ── Round reference reconciliation ───────────────────────────────────────────

(deftest verify-round-reference-matching
  (let [reference {:review-round/id "sha256:round"
                   :review-round/hash "sha256:round"}
        resolved {:review-round/id "sha256:round"
                  :review-round/hash "sha256:round"}]
    (let [result (rfa/verify-round-reference reference resolved)]
      (is (:valid? result)))))

(deftest verify-round-reference-rejects-substituted-id
  (let [reference {:review-round/id "sha256:round"
                   :review-round/hash "sha256:round"}
        resolved {:review-round/id "sha256:other-round"
                  :review-round/hash "sha256:round"}]
    (let [result (rfa/verify-round-reference reference resolved)]
      (is (not (:valid? result)))
      (is (some #(re-find #"review-round/id mismatch" %) (:errors result))))))

(deftest verify-round-reference-rejects-substituted-hash
  (let [reference {:review-round/id "sha256:round"
                   :review-round/hash "sha256:round"}
        resolved {:review-round/id "sha256:round"
                  :review-round/hash "sha256:other-hash"}]
    (let [result (rfa/verify-round-reference reference resolved)]
      (is (not (:valid? result)))
      (is (some #(re-find #"review-round/hash mismatch" %) (:errors result))))))

(deftest verify-round-reference-rejects-id-not-equal-to-hash
  (let [reference {:review-round/id "sha256:round"
                   :review-round/hash "sha256:round"}
        resolved {:review-round/id :round-keyword
                  :review-round/hash "sha256:round"}]
    (let [result (rfa/verify-round-reference reference resolved)]
      (is (not (:valid? result)))
      (is (some #(re-find #"id does not equal hash" %) (:errors result))))))

(deftest verify-against-round-rejects-substituted-round-id
  (let [round {:review-round/id "sha256:round-hash"
               :review-round/hash "sha256:round-hash"
               :review-round/members [{:researcher/id "researcher-a" :role :model-steward}
                                      {:researcher/id "researcher-b" :role :independent-reproducer}]}
        auth (assoc-in (build-auth) [:authorisation/review-round :review-round/id]
                       "sha256:substituted-id")
        result (rfa/verify-against-round round auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"round reference mismatch" %) (:errors result)))))

(deftest verify-against-round-rejects-substituted-round-hash
  (let [round {:review-round/id "sha256:round-hash"
               :review-round/hash "sha256:round-hash"
               :review-round/members [{:researcher/id "researcher-a" :role :model-steward}
                                      {:researcher/id "researcher-b" :role :independent-reproducer}]}
        auth (assoc-in (build-auth) [:authorisation/review-round :review-round/hash]
                       "sha256:substituted-hash")
        result (rfa/verify-against-round round auth)]
    (is (not (:valid? result)))
    (is (some #(re-find #"round reference mismatch" %) (:errors result)))))

(deftest force-authorisation-summary-key-vectors
  (let [auth (build-auth :decisions [(mock-decision "researcher-a" :approve)
                                     (mock-decision "researcher-b" :dissent
                                                    :dissent-reason "scope")
                                     (mock-decision "researcher-c" :approve)])
        reservation {:reservation/execution-attempt-id :execution/test
                     :reservation/authorisation-hash (:authorisation/hash auth)}
        manifest {:execution/force-authorisation
                  {:authorisation-hash (:authorisation/hash auth)}
                  :benchmark-outcome/hash "sha256:o"}
        receipt {:consumption/status :consumed
                 :consumption/hash "sha256:receipt"
                 :consumption/resulting-outcome-hash "sha256:o"}
        profile {:evidence-profile/hash "sha256:profile"}
        summary (rfa/force-authorisation-summary
                 auth reservation manifest receipt profile keyed-round)]
    (is (= [0 2] (get-in summary [:decision :approval-member-keys])))
    (is (= [1] (get-in summary [:decision :dissent-member-keys])))
    (is (= ["researcher-a" "researcher-c"] (get-in summary [:decision :approvals])))
    (is (= ["researcher-b"] (get-in summary [:decision :dissents])))))
