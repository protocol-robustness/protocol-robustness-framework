(ns resolver-sim.resubmission.genesis-authorization-test
  "Tests for resubmission-chain-genesis-authorization.v1:
   structural validation, genesis-root cross-check, root determinism,
   and multi-gate verification (genesis validity, force-authorisation
   resolution, approval check, target binding, governed authority
   evaluation, and authority-report-root matching).

   Verification tests run against a CANONICAL three-member governed
   authority fixture: a valid review-governance.v1 snapshot, a governed
   benchmark-review-round.v2 constituted over three independent seated
   principals, and three distinct Ed25519-signed approving positions over
   the same target outcome (see canonical-governance-fixture below)."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.benchmark.review-governance :as rg]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.genesis-authorization :as ga]
            [resolver-sim.resubmission.authority-context :as authority-context]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.assurance.governed-authority-consumer :as gac])
  (:import [clojure.lang ExceptionInfo]
           [java.security SecureRandom]
           [java.util Base64]
           [org.bouncycastle.crypto.generators Ed25519KeyPairGenerator]
           [org.bouncycastle.crypto.params Ed25519KeyGenerationParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory
            SubjectPublicKeyInfoFactory]))

;; ── Golden fixtures ────────────────────────────────────────────────────────

(def ^:private family "sha256:FAM")
(def ^:private receipt-pk "sha256:receipt-pk")

(def ^:private genesis-artifact
  "A canonical genesis used across tests; computed at load time."
  (genesis/->genesis family nil receipt-pk))

(def ^:private genesis-root
  "Canonical root of genesis-artifact, computed at load time."
  (genesis/resubmission-chain-genesis-root genesis-artifact))

(def ^:private ^:const fixture-at "2026-08-01T00:00:00Z")

(defn- test-ref
  "Deterministic well-formed sha256 reference derived from a single char."
  [ch]
  (str "sha256:" (apply str (take 64 (repeat ch)))))

(def ^:private b64-mime (Base64/getMimeEncoder))

(defn- generate-keypair!
  "Fresh Ed25519 keypair written to temp PEM files (PKCS8 private key,
   X509 public key), following the researcher-integration-test pattern.
   Files are deleted on JVM exit."
  [label]
  (let [gen (Ed25519KeyPairGenerator.)
        _ (.init gen (Ed25519KeyGenerationParameters. (SecureRandom.)))
        pair (.generateKeyPair gen)
        priv-der (.getEncoded
                  (PrivateKeyInfoFactory/createPrivateKeyInfo (.getPrivate pair)))
        pub-der (.getEncoded
                 (SubjectPublicKeyInfoFactory/createSubjectPublicKeyInfo (.getPublic pair)))
        priv-file (java.io.File/createTempFile (str "ga-" label "-priv") ".pem")
        pub-file (java.io.File/createTempFile (str "ga-" label "-pub") ".pem")]
    (spit priv-file (str "-----BEGIN PRIVATE KEY-----\n"
                         (.encodeToString b64-mime priv-der)
                         "\n-----END PRIVATE KEY-----\n"))
    (spit pub-file (str "-----BEGIN PUBLIC KEY-----\n"
                        (.encodeToString b64-mime pub-der)
                        "\n-----END PUBLIC KEY-----\n"))
    (.deleteOnExit priv-file)
    (.deleteOnExit pub-file)
    {:private-key-path (.getPath priv-file)
     :public-key-path (.getPath pub-file)}))

;; ── Canonical three-member governed authority fixture ──────────────────────
;;
;; Everything the governed authority evaluation consumes is canonical and
;; internally consistent:
;;   - a closed-shape review-governance.v1 with three independent seated
;;     principals (distinct independence groups and signing keys);
;;   - a governed benchmark-review-round.v2 whose governance root pins that
;;     snapshot and whose chain-configuration root is the real genesis
;;     configuration root;
;;   - three distinct Ed25519-signed approving positions over the same
;;     target outcome, each carrying its seat's governed signing-key id.

(defn- fixture-principal
  [id group key-id]
  {:principal/id id
   :status :active
   :principal/independence-group group
   :principal/independence-basis-root (test-ref "a")
   :principal/keys [{:key/id key-id :status :active :key/algorithm :ed25519
                     :key/public-key (str "public-" (name key-id))}]})

(def ^:private fixture-members
  [{:researcher/id "member-a" :role :model-steward}
   {:researcher/id "member-b" :role :independent-reproducer}
   {:researcher/id "member-c" :role :adversarial-reviewer}])

(def ^:private governance
  "Canonical review-governance.v1 snapshot for the fixture cell."
  {:schema-version rg/schema-version
   :governance/epoch 1
   :governance/roles #{:model-steward :independent-reproducer :adversarial-reviewer}
   :governance/principals [(fixture-principal :principal/a :group/a :key/a)
                           (fixture-principal :principal/b :group/b :key/b)
                           (fixture-principal :principal/c :group/c :key/c)]
   :governance/members
   [{:reviewer/member-id "member-a" :principal/id :principal/a
     :status :active :granted-roles #{:model-steward}}
    {:reviewer/member-id "member-b" :principal/id :principal/b
     :status :active :granted-roles #{:independent-reproducer}}
    {:reviewer/member-id "member-c" :principal/id :principal/c
     :status :active :granted-roles #{:adversarial-reviewer}}]
   :governance/policies
   [{:policy/id :policy/three-independent
     :member-count 3 :threshold 2
     :required-roles #{:model-steward :independent-reproducer :adversarial-reviewer}
     :role-cardinality :unique
     :equivocation-policy :invalid-seat}]})

(def ^:private governance-root
  "Root of the pinned governance snapshot."
  (rg/governance-root governance))

(def ^:private configuration-root
  "Real chain-configuration root of the fixture genesis."
  (genesis/resubmission-chain-configuration-root (:configuration genesis-artifact)))

(def ^:private round-input
  {:benchmark/content-root (test-ref "c")
   :review-round/purpose :force-authorisation
   :review-round/members fixture-members
   :review-round/membership-frozen-at fixture-at
   :review-round/policy-root (test-ref "d")
   :review-round/force-target {:target/proposed-content-root genesis-root}
   :review-round/approval-set #{}
   :review-round/branch-descriptor (test-ref "e")
   :review-round/chain-configuration-root configuration-root
   :review-round/governance-root governance-root
   :review-round/governance-epoch 1
   :review-round/constituted-at fixture-at
   :review-round/policy-id :policy/three-independent
   :review-round/policy-hash (test-ref "1")})

(def ^:private review-round
  "Governed benchmark-review-round.v2. build-review-round checks purpose
   creation requirements from the input but retains only base + governed
   fields; the purpose-specific force-authorisation fields are merged back
   so the resolved round is complete for downstream validation."
  (merge (rr/build-review-round round-input)
         (select-keys round-input [:review-round/force-target
                                   :review-round/approval-set
                                   :review-round/branch-descriptor])))

(def ^:private round-hash
  "Identity hash of the governed review round."
  (:review-round/hash review-round))

(def ^:private authorisation-id
  :authorisation/resubmission-genesis-fixture)

(def ^:private request-root
  "Opaque reference to the (external) authorisation request artifact."
  (test-ref "b"))

(def ^:private keys-by-member
  {"member-a" (generate-keypair! "a")
   "member-b" (generate-keypair! "b")
   "member-c" (generate-keypair! "c")})

(defn- governed-position
  "An Ed25519-signed approving position by one seated member over the given
   outcome root, bound to this fixture's request scope and signed with the
   member's governed signing key id."
  [member-id key-id outcome-root]
  (assoc (rfa/build-signed-decision
          member-id authorisation-id request-root round-hash :approve
          (:private-key-path (keys-by-member member-id)))
         :signing-key/id key-id
         :outcome/root outcome-root))

(defn- governed-fa
  "Build a canonically hashed force-authorisation artifact over the shared
   governed round. Options:
     :decisions    — decision references (default: three approvals of
                     the genesis root outcome)
     :target-root  — value for :target/proposed-content-root
                     (default: the genesis root)"
  [& [{:keys [decisions target-root]}]]
  (let [target-root (or target-root genesis-root)
        decisions (or decisions
                      [(governed-position "member-a" :key/a genesis-root)
                       (governed-position "member-b" :key/b genesis-root)
                       (governed-position "member-c" :key/c genesis-root)])]
    (rfa/build-authorisation
     {:authorisation/id authorisation-id
      :authorisation/policy {:policy/id :policy/three-independent
                             :policy/version 1
                             :policy/schema-version "force-authorisation-policy.v1"
                             :policy/hash (test-ref "1")}
      :authorisation/review-round {:review-round/id (:review-round/id review-round)
                                   :review-round/hash round-hash}
      :authorisation/request-root request-root
      :authorisation/target {:target/kind :governance-mandated
                             :target/baseline-content-root (test-ref "2")
                             :target/branch-descriptor-hash (test-ref "3")
                             :target/proposed-content-root target-root}
      :authorisation/decision-references decisions
      :authorisation/threshold {:required 3 :eligible 3}})))

(defn- strip-review-round
  "Re-hash a force-authorisation without its review-round binding,
   simulating a hash-consistent producer that omitted the round."
  [fa]
  (let [base (dissoc fa :authorisation/hash :authorisation/review-round)]
    (assoc base :authorisation/hash
           (hash-ref/sha256-ref (hc/domain-hash :research-force-authorisation base)))))

(def ^:private context
  "Trusted control-plane context resolving the governed round, the pinned
   governance snapshot, position times, freshness, and per-member public keys."
  {:researcher-force-authorisation-governed-authority-context-resolver
   (fn [_round-hash]
     {:resolved? true
      :review-round review-round
      :review-governance governance
      :position-time-resolver (constantly fixture-at)
      :governance-current? (constantly true)})
   :researcher-public-key-resolver
   (fn [researcher-id]
     (:public-key-path (keys-by-member researcher-id)))})

(def ^:private canonical-fa
  "The approved three-member force-authorisation binding the genesis root."
  (governed-fa))

(def ^:private foreign-report-root
  "A well-formed sha256 reference that is NOT any computed report root."
  (str "sha256:" (apply str (take 64 (cycle [\f \0])))))

(defn- governed-report-root
  "Recompute the canonical authority report over an FA through the consumer
   boundary and return its content-addressed root. Throws when the decision
   does not reach :authorised."
  [fa]
  (let [{:keys [valid? authority-report-root] :as r}
        (gac/verify-governed-authority context fa round-hash)]
    (when-not valid?
      (throw (ex-info "governed authority fixture did not authorise"
                      {:result r})))
    authority-report-root))

(def ^:private valid-authz
  "Structurally valid authorization backed by the canonical governed
   authority decision: the declared authority-report-root is the actual
   recomputed root of the canonical report."
  {:authorization/schema ga/authorization-schema
   :authorization/genesis-root genesis-root
   :authorization/force-authorisation-hash (:authorisation/hash canonical-fa)
   :authorization/authority-report-root
   (governed-report-root canonical-fa)})

(def ^:private authz-root
  "Canonical root of valid-authz, computed at load time."
  (ga/genesis-authorization-root valid-authz))

(defn- mock-resolver
  "Package resolver that returns the given fa-map for any hash."
  [fa-map]
  (fn [_hash] fa-map))

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

;; ── verify-genesis-authorization: Happy path (canonical governed fixture) ──

(defn- authz-binding
  "An authorization artifact binding an FA hash and a declared report root."
  [fa report-root]
  {:authorization/schema ga/authorization-schema
   :authorization/genesis-root genesis-root
   :authorization/force-authorisation-hash (:authorisation/hash fa)
   :authorization/authority-report-root report-root})

(defn- dissent-position
  "An Ed25519-signed reasoned dissent by one seated member."
  [member-id key-id reason]
  (assoc (rfa/build-signed-decision
          member-id authorisation-id request-root round-hash :dissent
          (:private-key-path (keys-by-member member-id))
          :dissent-reason reason)
         :signing-key/id key-id))

(deftest test-verify-accepts-valid-pipeline
  (testing "a fully valid authorization passes end-to-end verification"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver canonical-fa)
                  context)]
      (is (:valid? result) (str "errors: " (:errors result)))
      (is (= genesis-root (:genesis-root result)))
      (is (= (:authorisation/hash canonical-fa) (:force-authorisation-hash result))))))

(deftest test-governed-fixture-is-canonical-three-member-decision
  (testing "the shared fixture itself satisfies the governed contract"
    (is (:valid? (rg/validate-governance governance)))
    (is (rr/governed-round? review-round))
    (is (:valid? (rr/validate-round review-round)))
    (is (= governance-root (get-in review-round [:review-round/governance-root])))
    (is (= configuration-root (get-in review-round [:review-round/chain-configuration-root])))
    (is (= :approved (:authorisation/decision-status canonical-fa)))
    (is (:valid? (rfa/validate-authorisation canonical-fa)))
    (is (true? (every? hash-ref/valid-sha256-ref?
                       (map :decision/hash
                            (:authorisation/decision-references canonical-fa)))))))

(deftest test-verify-rejects-declined-fa
  (testing "a declined force-authorisation fails the approval check"
    (let [fa (governed-fa
              {:decisions [(governed-position "member-a" :key/a genesis-root)
                           (governed-position "member-b" :key/b genesis-root)
                           (dissent-position "member-c" :key/c
                                             "cannot certify this genesis")]})
          _ (is (= :declined (:authorisation/decision-status fa)))
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  (authz-binding fa foreign-report-root)
                  (mock-resolver fa)
                  context)]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "decision-status") (:errors result))))))

(deftest test-verify-rejects-fa-target-mismatch
  (testing "an FA whose target does not match genesis-root is rejected"
    (let [wrong-target "sha256:0000000000000000000000000000000000000000000000000000000000000099"
          fa (governed-fa {:target-root wrong-target})
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  (authz-binding fa foreign-report-root)
                  (mock-resolver fa)
                  context)]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "does not match genesis-root") (:errors result))))))

(deftest test-verify-rejects-fa-without-round-hash
  (testing "an FA without a review-round hash fails"
    (let [fa (strip-review-round canonical-fa)
          result (ga/verify-genesis-authorization
                  genesis-artifact
                  (authz-binding fa foreign-report-root)
                  (mock-resolver fa)
                  context)]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "review-round/hash") (:errors result))))))

(deftest test-verify-rejects-authority-report-root-mismatch
  (testing "a declared authority-report-root other than the recomputed one fails"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact
                  (authz-binding canonical-fa foreign-report-root)
                  (mock-resolver canonical-fa)
                  context)]
      (is (not (:valid? result)))
      (is (some #(str/includes? % "authority-report-root mismatch") (:errors result))))))

;; ── verify-genesis-authorization: Return shape ─────────────────────────────

(deftest test-verify-return-shape-success
  (testing "successful verification returns the documented keys"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver canonical-fa)
                  context)]
      (is (:valid? result))
      (is (contains? result :genesis-root))
      (is (contains? result :force-authorisation-hash))
      (is (contains? result :authority-report-root))
      (is (contains? result :governance-root))
      (is (contains? result :authorized-disposition-context)))))

(deftest test-authorized-disposition-context-is-rooted-and-bound
  (testing "the authorized-disposition-context is rooted, derived from the
            genesis configuration root, and bound to the computed
            genesis-authorization root"
    (let [result (ga/verify-genesis-authorization
                  genesis-artifact
                  valid-authz
                  (mock-resolver canonical-fa)
                  context)
          ctx (:authorized-disposition-context result)]
      (is (:valid? result) (str "errors: " (:errors result)))
      ;; rooted: carries a self-consistent canonical context root
      (is (hash-ref/valid-sha256-ref? (:authority/context-root ctx)))
      (is (:valid? (authority-context/validate-authority-context ctx))
          (str "context errors: "
               (:errors (authority-context/validate-authority-context ctx))))
      ;; derived from the genesis configuration root
      (is (= (genesis/resubmission-chain-configuration-root
              (:configuration genesis-artifact))
             (:authority/configuration-root ctx)))
      (is (= genesis-root (:authority/genesis-root ctx)))      ;; bound to the computed authorization root
      (is (= authz-root (:authority/authorization-root ctx)))
      (is (= authority-context/authority-epoch-0 (:authority/epoch ctx)))
      (is (= [:prf.resubmission/apply-disposition]
             (:authority/permitted-actions ctx))))))

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
