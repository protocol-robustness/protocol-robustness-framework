(ns resolver-sim.resubmission.genesis-authorization-test
  "Tests for resubmission-chain-genesis-authorization.v1:
   structural validation, genesis-root cross-check, root determinism,
   and multi-gate verification (genesis validity, force-authorisation
   resolution, approval check, target binding, governed authority
   evaluation, and authority-report-root matching)."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.genesis-authorization :as ga]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa])
  (:import [clojure.lang ExceptionInfo]))

;; ── Golden fixtures ────────────────────────────────────────────────────────

(def ^:private family "sha256:FAM")
(def ^:private receipt-pk "sha256:receipt-pk")

(def ^:private genesis-artifact
  "A canonical genesis used across tests; computed at load time."
  (genesis/->genesis family nil receipt-pk))

(def ^:private genesis-root
  "Canonical root of genesis-artifact, computed at load time."
  (genesis/resubmission-chain-genesis-root genesis-artifact))

(def ^:private fa-hash
  "A content-addressed hash standing in for a force-authorisation artifact."
  "sha256:0000000000000000000000000000000000000000000000000000000000000001")

(def ^:private governance-root
  "A plausible three-member-authority report root."
  "sha256:0000000000000000000000000000000000000000000000000000000000000002")

(def ^:private round-hash
  "A review-round hash for the mock FA."
  "sha256:0000000000000000000000000000000000000000000000000000000000000003")

(def ^:private valid-authz
  "A structurally valid authorization artifact (no real FA backing in
   package-resolver tests — verification tests supply their own FA)."
  {:authorization/schema "resubmission-chain-genesis-authorization.v1"
   :authorization/genesis-root genesis-root
   :authorization/force-authorisation-hash fa-hash
   :authorization/authority-report-root governance-root})

(def ^:private authz-root
  "Canonical root of valid-authz, computed at load time."
  (ga/genesis-authorization-root valid-authz))

;; ── Structural validation ──────────────────────────────────────────────────

(deftest test-validate-accepts-well-formed-authorization
  (testing "a correctly formed authorization passes structural validation"
    (let [v (ga/validate-genesis-authorization valid-authz)]
      (is (:valid? v) (str "errors: " (:errors v))))))

(deftest test-validate-accepts-with-genesis-cross-check
  (testing "validation succeeds when genesis-root matches the recomputed genesis root"
    (let [v (ga/validate-genesis-authorization valid-authz genesis-artifact)]
      (is (:valid? v) (str "errors: " (:errors v))))))

(deftest test-validate-rejects-non-map
  (testing "non-map input is rejected"
    (let [v (ga/validate-genesis-authorization "not-a-map")]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a map") (:errors v))))))

(deftest test-validate-rejects-unknown-keys
  (testing "extra keys are rejected (closed shape)"
    (let [invalid (assoc valid-authz :authorization/unknown "value")
          v (ga/validate-genesis-authorization invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown authorization keys") (:errors v))))))

(deftest test-validate-rejects-missing-keys
  (testing "missing required keys are rejected"
    (let [invalid (dissoc valid-authz :authorization/genesis-root)
          v (ga/validate-genesis-authorization invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "missing authorization keys") (:errors v))))))

(deftest test-validate-rejects-wrong-schema
  (testing "wrong authorization/schema is rejected"
    (let [invalid (assoc valid-authz :authorization/schema "wrong")
          v (ga/validate-genesis-authorization invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "authorization/schema must be") (:errors v))))))

(deftest test-validate-rejects-nil-sha256-fields
  (testing "nil sha256 reference fields are rejected"
    (let [invalid (assoc valid-authz :authorization/genesis-root nil)
          v (ga/validate-genesis-authorization invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v))))))

(deftest test-validate-rejects-malformed-sha256
  (testing "malformed sha256 reference fields are rejected"
    (let [invalid (assoc valid-authz :authorization/genesis-root "not-a-ref")
          v (ga/validate-genesis-authorization invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a valid sha256 reference") (:errors v))))))

(deftest test-validate-rejects-genesis-root-mismatch
  (testing "when genesis is supplied, declared root must match computed root"
    (let [invalid (assoc valid-authz
                         :authorization/genesis-root
                         "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          v (ga/validate-genesis-authorization invalid genesis-artifact)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "does not match recomputed genesis root") (:errors v))))))

(deftest test-validate-accepts-invalid-genesis-without-genesis-arg
  (testing "genesis-root cross-check only runs when genesis is supplied"
    (let [invalid (assoc valid-authz
                         :authorization/genesis-root
                         "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          v (ga/validate-genesis-authorization invalid)]
      (is (:valid? v)
          "structural validity should not require genesis cross-check"))))

(deftest test-genesis-authorization-valid-predicate
  (testing "genesis-authorization-valid? returns true for well-formed artifacts"
    (is (true? (ga/genesis-authorization-valid? valid-authz))))
  (testing "genesis-authorization-valid? returns false for malformed artifacts"
    (is (false? (ga/genesis-authorization-valid?
                 (assoc valid-authz :authorization/schema "wrong"))))))

;; ── Root computation ───────────────────────────────────────────────────────

(deftest test-genesis-authorization-root-deterministic
  (testing "root computation is deterministic"
    (let [root1 (ga/genesis-authorization-root valid-authz)
          root2 (ga/genesis-authorization-root valid-authz)]
      (is (= root1 root2))))
  (testing "root is a valid sha256 reference"
    (is (hash-ref/valid-sha256-ref? authz-root))))

(deftest test-genesis-authorization-root-recomputed-at-load
  (testing "the computed authz-root matches the root computed at load time"
    (is (hash-ref/valid-sha256-ref? authz-root))))

(deftest test-genesis-authorization-root-rejects-invalid
  (testing "root computation throws on structurally invalid authorization"
    (is (thrown-with-msg?
         ExceptionInfo
         #"resubmission-chain-genesis-authorization\.v1 is invalid"
         (ga/genesis-authorization-root
          (assoc valid-authz :authorization/schema "wrong"))))))

;; ── verify-genesis-authorization: Gate 1 — structural validation ───────────

(deftest test-verify-rejects-invalid-authorization-structure
  (testing "verification short-circuits on structurally invalid authorization"
    (let [invalid (assoc valid-authz :authorization/genesis-root nil)
          result (ga/verify-genesis-authorization
                  genesis-artifact invalid (constantly nil) {})]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "must not be nil") (:errors result))))))

(deftest test-verify-rejects-genesis-root-mismatch
  (testing "verification fails when authz genesis-root does not match genesis"
    (let [invalid (assoc valid-authz
                         :authorization/genesis-root
                         "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          result (ga/verify-genesis-authorization
                  genesis-artifact invalid (constantly nil) {})]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "does not match") (:errors result))))))

;; ── verify-genesis-authorization: Gate 2 — genesis validity ────────────────

(deftest test-verify-rejects-invalid-genesis
  (testing "verification fails when the genesis itself is invalid"
    (let [invalid-genesis {:genesis/schema "wrong"}
          result (ga/verify-genesis-authorization
                  invalid-genesis valid-authz (constantly nil) {})]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "genesis") (:errors result))))))

;; ── verify-genesis-authorization: Gate 3 — force-authorisation resolution ───

(deftest test-verify-rejects-fa-not-found
  (testing "verification fails when the force-authorisation artifact is not found"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact valid-authz (constantly nil) {})]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "not found by hash") (:errors result))))))

;; ── verify-genesis-authorization: Happy path with mock FA ─────────────────

(defn- mock-fa
  "Build a minimal force-authorisation map with the given decision status,
   target root, and round hash. The :authorisation/hash is computed
   canonically so that rfa/validate-authorisation passes.
   Pass nil for round-hash-str to omit the review-round entirely."
  ([decision-status target-root round-hash-str]
   (let [fa-base (cond-> {:schema-version rfa/schema-version
                          :authorisation/id "test-fa"
                          :authorisation/decision-status decision-status
                          :authorisation/target {:target/proposed-content-root target-root}
                          :authorisation/policy-reference {:policy-hash "sha256:policy-hash"}
                          :authorisation/threshold {:required 3 :eligible 3 :approved 3 :dissented 0}
                          :authorisation/decision-references [{:decision-status :approve}]}
                   (some? round-hash-str)
                   (assoc :authorisation/review-round
                          {:review-round/hash round-hash-str}))
         fa-hash (hash-ref/sha256-ref
                   (hc/domain-hash :research-force-authorisation fa-base))]
     (assoc fa-base :authorisation/hash fa-hash))))

(defn- mock-resolver
  "Package resolver that returns the given fa-map for any hash."
  [fa-map]
  (fn [_hash] fa-map))

(defn- mock-context
  "Context that resolves to a valid governance context for the given round."
  []
  {:researcher-force-authorisation-governed-authority-context-resolver
   (fn [_round-hash]
     {:resolved? true
      :review-round/hash round-hash
      :review-round {:review-round/hash round-hash}
      :review-governance {:governance/root "sha256:0000000000000000000000000000000000000000000000000000000000000042"}
      :position-time-resolver (fn [_] (System/currentTimeMillis))
      :governance-current? (constantly true)})
   :researcher-public-key-resolver
   (fn [_pk] {:valid? true})})

(deftest test-verify-accepts-valid-pipeline
  (testing "a fully valid authorization passes end-to-end verification"
    (let [fa (mock-fa :approved genesis-root round-hash)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver fa)
                  (mock-context))]
      (is (:valid? result) (str "errors: " (:errors result)))
      (is (= genesis-root (:genesis-root result)))
      (is (= fa-hash (:force-authorisation-hash result))))))

(deftest test-verify-rejects-declined-fa
  (testing "a declined force-authorisation fails the approval check"
    (let [fa (mock-fa :declined genesis-root round-hash)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver fa)
                  (mock-context))]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "decision-status") (:errors result))))))

(deftest test-verify-rejects-fa-target-mismatch
  (testing "an FA whose target does not match genesis-root is rejected"
    (let [wrong-target "sha256:0000000000000000000000000000000000000000000000000000000000000099"
          fa (mock-fa :approved wrong-target round-hash)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver fa)
                  (mock-context))]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "does not match genesis-root") (:errors result))))))

(deftest test-verify-rejects-fa-without-round-hash
  (testing "an FA without a review-round hash fails"
    (let [fa (mock-fa :approved genesis-root nil)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver fa)
                  (mock-context))]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "review-round/hash") (:errors result))))))

;; ── verify-genesis-authorization: Return shape ─────────────────────────────

(deftest test-verify-return-shape-success
  (testing "successful verification returns the documented keys"
    (let [fa (mock-fa :approved genesis-root round-hash)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver fa)
                  (mock-context))]
      (is (:valid? result))
      (is (contains? result :genesis-root))
      (is (contains? result :force-authorisation-hash))
      (is (contains? result :authority-report-root))
      (is (contains? result :governance-root)))))

(deftest test-verify-return-shape-failure
  (testing "failed verification returns :valid? false with errors"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (constantly nil)
                  {})]
      (is (not (:valid? result)))
      (is (contains? result :errors))
      (is (vector? (:errors result)))
      (is (seq (:errors result))))))

;; ── Flat-structure regression ──────────────────────────────────────────────

(deftest test-verify-gates-are-sequential-not-nested
  (testing "each gate contributes its own error independently"
    (let [;; Invalid authorization structure (nil genesis-root) + nil resolver
          invalid-fa-auth (assoc valid-authz
                                 :authorization/genesis-root nil)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  invalid-fa-auth
                  (constantly nil)
                  {})]
      (is (not (:valid? result)))
      ;; Should have at least the "must not be nil" error from Gate 1
      (is (some #(str/includes? % "must not be nil") (:errors result))))))

;; ── Edge cases ─────────────────────────────────────────────────────────────

(deftest test-verify-rejects-nil-authz
  (testing "nil authorization is rejected"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact nil (constantly nil) {})]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "must be a map") (:errors result))))))

(deftest test-verify-rejects-nil-genesis
  (testing "nil genesis is rejected in genesis validity gate"
    (let [result (ga/verify-genesis-authorization
                  nil valid-authz (constantly nil) {})]
      (is (not (:valid? result))))))
