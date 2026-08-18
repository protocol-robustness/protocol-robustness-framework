(ns resolver-sim.genesis-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.data.json :as json]
             [resolver-sim.genesis :as g]
             [resolver-sim.hash.canonical :as canonical]
             [resolver-sim.hash.reference :as hash-ref])
  (:import [clojure.lang ExceptionInfo]))

;; ── Golden roots (computed at load) ──────────────────────────────────────

(def ^:private pg-root
  "sha256:d77429dd50bd7fe18d5f41d55e53ccbd4c67d18c4f00b266f86dbaede01f9343")

(def ^:private ci-eth-root
  "sha256:f6c0f226998ebecb1123f78c2b30347d24c6e7ca2131f44f880b8a43c052cf86")

(def ^:private config-root
  "sha256:a3b808fa66ad9b9c4f35de5590ad95213cf0de76190db5980ced743f39f97f57")

(def ^:private direct-transition-root
  "sha256:abb2aa2c77e78f5b5633eb645bd05816c97214ad0a2901df4d3e662ae35b7f22")

(def ^:private set-transition-root
  "sha256:51d907c2cdb63440399bb0a3da075f6849e18b2d8eaba01c31e6ec1c582eca86")

;; ── Protocol genesis (unchanged) ──────────────────────────────────────────

(deftest test-protocol-genesis-fixture-is-valid
  (testing "protocol-genesis-fixture passes strict closed-shape validation"
    (let [v (g/validate-protocol-genesis g/protocol-genesis-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

(deftest test-protocol-genesis-fixture-root-golden
  (testing "protocol-genesis-fixture root is a stable golden vector"
    (is (= pg-root g/protocol-genesis-fixture-root))))

;; ── chain-instance genesis (unchanged) ───────────────────────────────────

(deftest test-chain-instance-genesis-fixture-is-valid
  (testing "ethereum chain-instance fixture passes validation"
    (let [v (g/validate-chain-instance-genesis g/chain-instance-genesis-ethereum-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

(deftest test-chain-instance-genesis-fixture-root-golden
  (testing "ethereum chain-instance fixture root is a stable golden vector"
    (is (= ci-eth-root g/chain-instance-genesis-ethereum-fixture-root))))

;; ── chain-configuration.v1 tests ─────────────────────────────────────────

(deftest test-chain-configuration-fixture-is-valid
  (testing "chain-configuration-fixture passes validation"
    (let [v (g/validate-chain-configuration g/chain-configuration-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

(deftest test-chain-configuration-fixture-root-golden
  (testing "chain-configuration-fixture root is a stable golden vector"
    (is (= config-root g/chain-configuration-fixture-root))))

(deftest test-chain-configuration-root-mismatch-rejection
  (testing "2-arg root fn rejects mismatched expected value"
    (is (thrown-with-msg?
         ExceptionInfo
         #"caller-supplied chain-configuration root does not match"
         (g/chain-configuration-root
          g/chain-configuration-fixture
          "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))

(deftest test-chain-configuration-rejects-unknown-keys
  (testing "unknown top-level keys are rejected (fail-closed)"
    (let [invalid (assoc g/chain-configuration-fixture :unexpected/key "value")
          v (g/validate-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown top-level keys") (:errors v))))))

(deftest test-chain-configuration-rejects-missing-keys
  (testing "missing required keys are rejected"
    (let [invalid (dissoc g/chain-configuration-fixture :parameter-policy/root)
          v (g/validate-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "missing required keys") (:errors v))))))

(deftest test-chain-configuration-rejects-malformed-sha256
  (testing "malformed sha256 reference is rejected"
    (let [invalid (assoc g/chain-configuration-fixture
                         :governance-policy/root "sha256:notvalid")
          v (g/validate-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "valid sha256 reference") (:errors v))))))

(deftest test-chain-configuration-rejects-nil-root
  (testing "nil root is rejected"
    (let [invalid (assoc g/chain-configuration-fixture :parameter-policy/root nil)
          v (g/validate-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v))))))

(deftest test-chain-configuration-rejects-wrong-schema
  (testing "wrong schema is rejected"
    (let [invalid (assoc g/chain-configuration-fixture :configuration/schema "wrong")
          v (g/validate-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "schema") (:errors v))))))

(deftest test-chain-configuration-map-insertion-order-irrelevant
  (testing "identical content with different key order produces same root"
    (let [base g/chain-configuration-fixture
          shuffled (into {} (reverse (seq base)))
          root-base (g/chain-configuration-root base)
          root-shuffled (g/chain-configuration-root shuffled)]
      (is (= root-base root-shuffled)
          "Root must be insensitive to map insertion order"))))

(deftest test-chain-configuration-same-content-different-instances
  (testing "identical configuration content produces same root regardless of instance"
    (let [root1 (g/chain-configuration-root g/chain-configuration-fixture)
          root2 (g/chain-configuration-root g/chain-configuration-fixture)]
      (is (= root1 root2)
          "Same configuration content must produce the same root"))))

;; ── chain-configuration-transition.v1: direct target tests ──────────────

(deftest test-transition-direct-fixture-is-valid
  (testing "direct-target transition fixture passes validation"
    (let [v (g/validate-chain-configuration-transition g/chain-configuration-transition-direct-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

(deftest test-transition-direct-fixture-root-golden
  (testing "direct transition fixture root is a stable golden vector"
    (is (= direct-transition-root g/chain-configuration-transition-direct-fixture-root))))

(deftest test-transition-direct-root-mismatch-rejection
  (testing "2-arg transition root fn rejects mismatched expected value"
    (is (thrown-with-msg?
         ExceptionInfo
         #"caller-supplied transition root does not match"
         (g/chain-configuration-transition-root
          g/chain-configuration-transition-direct-fixture
          "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))

;; ── chain-configuration-transition.v1: set target tests ─────────────────

(deftest test-transition-set-fixture-is-valid
  (testing "set-target transition fixture passes validation"
    (let [v (g/validate-chain-configuration-transition g/chain-configuration-transition-set-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

(deftest test-transition-set-fixture-root-golden
  (testing "set transition fixture root is a stable golden vector"
    (is (= set-transition-root g/chain-configuration-transition-set-fixture-root))))

;; ── Target type validation ───────────────────────────────────────────────

(deftest test-transition-direct-accepts-sha256-target
  (testing "direct fixture target root is a sha256 reference"
    (is (hash-ref/valid-sha256-ref?
         (-> g/chain-configuration-transition-direct-fixture :target :target/root))))
  (testing ":chain-instance target root validates as sha256"
    (let [root (-> g/chain-configuration-transition-direct-fixture :target :target/root)]
      (is (hash-ref/valid-sha256-ref? root)
          "direct target root must be a valid sha256 reference"))))

(deftest test-transition-direct-rejects-keccak-target
  (testing ":chain-instance target rejects keccak256 reference"
    (let [keccak-root (-> g/chain-configuration-transition-set-fixture :target :target/root)
          invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:target :target/root] keccak-root))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "sha256") (:errors v))))))

(deftest test-transition-set-accepts-keccak-target
  (testing ":chain-instance-set target accepts a keccak256 reference"
    (is (g/valid-keccak256-ref?
         (-> g/chain-configuration-transition-set-fixture :target :target/root)))))

(deftest test-transition-set-rejects-sha256-target
  (testing ":chain-instance-set target rejects sha256 reference"
    (let [keccak-root (-> g/chain-configuration-transition-set-fixture :target :target/root)
          sha-root (str "sha256:" (subs keccak-root 10))
          invalid (-> g/chain-configuration-transition-set-fixture
                      (assoc-in [:target :target/root] sha-root))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "keccak256") (:errors v))))))

(deftest test-transition-rejects-unknown-target-type
  (testing "unknown :target/type is rejected"
    (let [invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:target :target/type] :all-chains))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "target/type") (:errors v))))))

(deftest test-transition-rejects-unknown-target-keys
  (testing "unknown keys in :target are rejected (fail-closed)"
    (let [invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:target :target/extra] "value"))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown target keys") (:errors v))))))

(deftest test-transition-rejects-unknown-top-level-keys
  (testing "unknown top-level keys in transition are rejected (fail-closed)"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :unexpected/key "value")
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown top-level keys") (:errors v))))))

(deftest test-transition-rejects-wrong-schema
  (testing "wrong transition schema is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :transition/schema "wrong")
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v))))))

;; ── Epoch validation ─────────────────────────────────────────────────────

(deftest test-transition-rejects-epoch-zero
  (testing "epoch 0 is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :epoch 0)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "epoch 2^64 is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture
                         :epoch (.pow (biginteger 2) 64))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "negative epoch is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :epoch -1)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "float epoch is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :epoch 1.0)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "valid epoch 1 is accepted"
    (is (g/valid-epoch? 1)))

  (testing "valid epoch 2^64-1 is accepted"
    (is (g/valid-epoch? (dec (.pow (biginteger 2) 64))))))

;; ── Self-transition rejection ─════════════════════════════════════════════

(deftest test-transition-rejects-self-transition
  (testing "parent-root == new-root is rejected"
    (let [parent-root (:configuration/parent-root g/chain-configuration-transition-direct-fixture)
          invalid (assoc g/chain-configuration-transition-direct-fixture
                         :configuration/new-root parent-root)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "self-transition") (:errors v))))))

(deftest test-transition-rejects-missing-verifier-registry-root
  (testing "transition without :verifier-registry/root is rejected"
    (let [invalid (dissoc g/chain-configuration-transition-direct-fixture :verifier-registry/root)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "verifier-registry/root") (:errors v))))))

(deftest test-transition-accepts-verifier-registry-root
  (testing "transition with :verifier-registry/root passes validation"
    (let [v (g/validate-chain-configuration-transition g/chain-configuration-transition-direct-fixture)]
      (is (:valid? v) (str "validation errors: " (:errors v))))))

;; ── Target-set vs direct produces different roots ────────────────────────

(deftest test-transition-direct-vs-set-produce-different-roots
  (testing "direct and set targets with same parent/new/epoch produce different roots"
    (is (not= g/chain-configuration-transition-direct-fixture-root
              g/chain-configuration-transition-set-fixture-root))))

;; ── Transition is deterministic ─══════════════════════════════════════════

(deftest test-transition-deterministic
  (testing "re-computing the same transition yields the same root"
    (let [root1 (g/chain-configuration-transition-root g/chain-configuration-transition-direct-fixture)
          root2 (g/chain-configuration-transition-root g/chain-configuration-transition-direct-fixture)]
      (is (= root1 root2)))))

;; ── Transition root is the Solidity decisionRoot ─═════════════════════════

(deftest test-transition-root-suitable-as-decision-root
  (testing "the canonical transition root is a 64-char hex sha256 ref usable as Solidity bytes32 decisionRoot"
    (is (re-matches #"sha256:[0-9a-f]{64}" direct-transition-root))
    (let [hex (subs direct-transition-root 7)]
      (is (= 64 (count hex))
          "transition root hex portion is 32 bytes (256 bits), matching bytes32"))))

;; ── Solidity field alignment report ─══════════════════════════════════════

(deftest test-solidity-field-alignment
  (let [direct g/chain-configuration-transition-direct-fixture
        set-fix g/chain-configuration-transition-set-fixture]
    (testing "direct transition maps to targetMode=0, set transition maps to targetMode=1"
      (is (= :chain-instance (:target/type (:target direct))))
      (is (= :chain-instance-set (:target/type (:target set-fix)))))
    (testing "direct target root is sha256 (SHA-256 for Solidity bytes32)"
      (is (hash-ref/valid-sha256-ref? (:target/root (:target direct)))))
    (testing "set target root is keccak256 (Keccak for Solidity Merkle root)"
      (is (g/valid-keccak256-ref? (:target/root (:target set-fix)))))
    (testing "parent/new configuration roots are sha256 refs"
      (is (hash-ref/valid-sha256-ref? (:configuration/parent-root direct)))
      (is (hash-ref/valid-sha256-ref? (:configuration/new-root direct))))))

(deftest test-solidity-decision-tuple
  (testing "golden transition root + Solidity decision tuple"
    (let [t g/chain-configuration-transition-direct-fixture
          hex (subs direct-transition-root 7)
          target-mode 0
          target-root (-> t :target :target/root)
          parent-config (:configuration/parent-root t)
          new-config (:configuration/new-root t)
          epoch (:epoch t)]
      (is (= "abb2aa2c77e78f5b5633eb645bd05816c97214ad0a2901df4d3e662ae35b7f22" hex))
      (is (= target-mode 0))
      (is (hash-ref/valid-sha256-ref? target-root))
      (is (hash-ref/valid-sha256-ref? parent-config))
      (is (hash-ref/valid-sha256-ref? new-config))
      (is (= 1 epoch)))))

(deftest test-solidity-projection-includes-verifier-registry-root
  (testing "authorization projection includes verifier-registry-root"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)
          vr-root (-> g/chain-configuration-transition-direct-fixture :verifier-registry/root)]
      (is (contains? auth :verifier-registry-root))
      (is (= (str "0x" (subs vr-root 7))
             (:verifier-registry-root auth))))))

;; ── Solidity authorization projection tests ─────────────────────────────────

(deftest test-solidity-projection-decision-root-matches-canonical
  (testing "decisionRoot equals canonical transition root (sha256: -> 0x conversion)"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)]
      (is (= (str "0x" (subs direct-transition-root 7))
             (:decision-root auth))
          "decisionRoot must match the canonical transition root byte-for-byte"))))

(deftest test-solidity-projection-rejects-invalid-transition
  (testing "projection rejects an unvalidated transition"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture
                         :epoch 0)]
      (is (thrown-with-msg?
           ExceptionInfo
           #"chain-configuration-transition.v1 is invalid"
           (g/chain-configuration-transition->solidity-authorization invalid))))))

(deftest test-solidity-projection-direct-target-mode-zero
  (testing "direct target maps to targetMode 0"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)]
      (is (= 0 (:target-mode auth))))))

(deftest test-solidity-projection-set-target-mode-one
  (testing "set target maps to targetMode 1"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-set-fixture)]
      (is (= 1 (:target-mode auth))))))

(deftest test-solidity-projection-direct-target-root-preserved
  (testing "SHA-256 direct target digest bytes are preserved exactly"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)
          expected (-> g/chain-configuration-transition-direct-fixture :target :target/root)]
      (is (= (str "0x" (subs expected 7))
             (:target-root auth))))))

(deftest test-solidity-projection-set-target-root-preserved
  (testing "Keccak set target digest bytes are preserved exactly"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-set-fixture)
          expected (-> g/chain-configuration-transition-set-fixture :target :target/root)]
      (is (= (str "0x" (subs expected 10))
             (:target-root auth))))))

(deftest test-solidity-projection-parent-root-preserved
  (testing "parent-configuration-root bytes are preserved exactly"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)
          expected (:configuration/parent-root g/chain-configuration-transition-direct-fixture)]
      (is (= (str "0x" (subs expected 7))
             (:parent-configuration-root auth))))))

(deftest test-solidity-projection-new-root-preserved
  (testing "new-configuration-root bytes are preserved exactly"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)
          expected (:configuration/new-root g/chain-configuration-transition-direct-fixture)]
      (is (= (str "0x" (subs expected 7))
             (:new-configuration-root auth))))))

(deftest test-solidity-projection-epoch-preserved
  (testing "epoch is preserved exactly"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)]
      (is (= 1 (:epoch auth))))))

(deftest test-solidity-projection-deterministic
  (testing "projection is deterministic"
    (let [auth1 (g/chain-configuration-transition->solidity-authorization
                 g/chain-configuration-transition-direct-fixture)
          auth2 (g/chain-configuration-transition->solidity-authorization
                 g/chain-configuration-transition-direct-fixture)]
      (is (= auth1 auth2)))))

(deftest test-solidity-projection-does-not-change-canonical-roots
  (testing "generating Solidity projection does not alter canonical roots"
    (let [before-direct g/chain-configuration-transition-direct-fixture-root
          before-set g/chain-configuration-transition-set-fixture-root
          before-config g/chain-configuration-fixture-root
          before-genesis g/chain-instance-genesis-ethereum-fixture-root
          _ (g/chain-configuration-transition->solidity-authorization
             g/chain-configuration-transition-direct-fixture)
          _ (g/chain-configuration-transition->solidity-authorization
             g/chain-configuration-transition-set-fixture)
          after-direct g/chain-configuration-transition-direct-fixture-root
          after-set g/chain-configuration-transition-set-fixture-root
          after-config g/chain-configuration-fixture-root
          after-genesis g/chain-instance-genesis-ethereum-fixture-root]
      (is (= before-direct after-direct))
      (is (= before-set after-set))
      (is (= before-config after-config))
      (is (= before-genesis after-genesis)))))

(deftest test-solidity-projection-exports-correct-tuple
  (testing "direct transition exports the expected golden tuple"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)]
      (is (= "0xabb2aa2c77e78f5b5633eb645bd05816c97214ad0a2901df4d3e662ae35b7f22"
             (:decision-root auth)))
      (is (= 0 (:target-mode auth)))
      (is (= "0xf6c0f226998ebecb1123f78c2b30347d24c6e7ca2131f44f880b8a43c052cf86"
             (:target-root auth)))
      (is (= "0x12025a7e7948edfa9319ca31c2a8c81b57b8ad066c05aa2e79b5db9497e53035"
             (:parent-configuration-root auth)))
      (is (= "0xeeabcdeca12cc86143032758cdf069751b47546dd69a94efeb008f4f2ae2b39c"
             (:new-configuration-root auth)))
      (is (= "0x2e728266afeb4b7dc7f1251f18bd4e3b6a93d72d06e8841a9b5d2f60c672fdfe"
             (:verifier-registry-root auth)))
      (is (= 1 (:epoch auth))))))

(deftest test-solidity-projection-rejects-malformed-ref
  (testing "malformed sha256 reference in transition is rejected by validator"
    (let [invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:configuration/new-root] "sha256:notvalid"))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))
  (testing "malformed keccak256 reference in set transition is rejected"
    (let [invalid (-> g/chain-configuration-transition-set-fixture
                      (assoc-in [:target :target/root] "keccak256:notvalid"))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))
  (testing "keccak ref in direct target is rejected"
    (let [invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:target :target/root]
                                (:target/root (:target g/chain-configuration-transition-set-fixture))))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))
  (testing "sha256 ref in set target is rejected"
    (let [invalid (-> g/chain-configuration-transition-set-fixture
                      (assoc-in [:target :target/root]
                                (:target/root (:target g/chain-configuration-transition-direct-fixture))))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "malformed target type is rejected"
    (let [invalid (-> g/chain-configuration-transition-direct-fixture
                      (assoc-in [:target :target/type] :unknown))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "unknown top-level key in transition is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :bogus/key "x")
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "self-transition is rejected"
    (let [parent (:configuration/parent-root g/chain-configuration-transition-direct-fixture)
          invalid (assoc g/chain-configuration-transition-direct-fixture
                         :configuration/new-root parent)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "missing verifier-registry-root is rejected"
    (let [invalid (dissoc g/chain-configuration-transition-direct-fixture :verifier-registry/root)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "epoch 0 is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :epoch 0)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "epoch 2^64 is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture
                         :epoch (.pow (biginteger 2) 64))
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v)))))

  (testing "negative epoch is rejected"
    (let [invalid (assoc g/chain-configuration-transition-direct-fixture :epoch -1)
          v (g/validate-chain-configuration-transition invalid)]
      (is (not (:valid? v))))))

(deftest test-solidity-projection-json-fixture-matches
  (testing "JSON fixture matches the projection output"
    (let [auth (g/chain-configuration-transition->solidity-authorization
                g/chain-configuration-transition-direct-fixture)
          json (json/read-str (slurp "etc/conformance/fixtures/solidity-authorization/direct-transition-ethereum.json") :key-fn keyword)]
      (is (= (:decision-root auth) (:decisionRoot json)))
      (is (= (:target-mode auth) (:targetMode json)))
      (is (= (:target-root auth) (:targetRoot json)))
      (is (= (:parent-configuration-root auth) (:parentConfigurationRoot json)))
      (is (= (:new-configuration-root auth) (:newConfigurationRoot json)))
      (is (= (:verifier-registry-root auth) (:verifierRegistryRoot json)))
      (is (= (:epoch auth) (:epoch json))))))

;; ── Solidity initialization projection tests ─────────────────────────────────

(deftest test-solidity-initialization-derives-configuration-root-internally
  (testing "initialization projection derives configuration root from validated config"
    (let [init (g/chain-configuration->solidity-initialization g/chain-configuration-fixture)]
      (is (= (:configuration-root init)
             (str "0x" (subs g/chain-configuration-fixture-root 7)))))))

(deftest test-solidity-initialization-derives-verifier-registry-root
  (testing "initialization projection derives verifier-registry root from config :verifier-registry/root"
    (let [init (g/chain-configuration->solidity-initialization g/chain-configuration-fixture)
          expected-vr (:verifier-registry/root g/chain-configuration-fixture)]
      (is (= (:verifier-registry-root init)
             (str "0x" (subs expected-vr 7)))))))

(deftest test-solidity-initialization-rejects-invalid-configuration
  (testing "initialization projection rejects an invalid configuration"
    (let [invalid (assoc g/chain-configuration-fixture :configuration/schema "wrong")]
      (is (thrown-with-msg?
           ExceptionInfo
           #"chain-configuration.v1 is invalid"
           (g/chain-configuration->solidity-initialization invalid))))))

(deftest test-solidity-initialization-deterministic
  (testing "initialization projection is deterministic"
    (let [init1 (g/chain-configuration->solidity-initialization g/chain-configuration-fixture)
          init2 (g/chain-configuration->solidity-initialization g/chain-configuration-fixture)]
      (is (= init1 init2)))))

(deftest test-solidity-initialization-json-fixture-matches
  (testing "JSON fixture matches the initialization projection output"
    (let [init (g/chain-configuration->solidity-initialization g/chain-configuration-fixture)
          json (json/read-str (slurp "etc/conformance/fixtures/solidity-authorization/initialization-v0.json") :key-fn keyword)]
      (is (= (:configuration-root init) (:configurationRoot json)))
      (is (= (:verifier-registry-root init) (:verifierRegistryRoot json))))))

;; ── Solidity application plan projection tests ───────────────────────────────

(deftest test-solidity-application-plan-derives-parent-config-root-internally
  (testing "application plan derives parent configuration root from parent config"
    (let [plan (g/chain-configuration-transition->solidity-application-plan
                g/chain-configuration-transition-v0-to-v1-fixture
                g/chain-configuration-v0-fixture
                g/chain-configuration-v1-fixture)]
      (is (= (:parent-configuration-root plan)
             (str "0x" (subs g/chain-configuration-v0-fixture-root 7)))))))

(deftest test-solidity-application-plan-derives-new-config-root-internally
  (testing "application plan derives new configuration root from new config"
    (let [plan (g/chain-configuration-transition->solidity-application-plan
                g/chain-configuration-transition-v0-to-v1-fixture
                g/chain-configuration-v0-fixture
                g/chain-configuration-v1-fixture)]
       (is (= (:new-configuration-root plan)
              (str "0x" (subs g/chain-configuration-v1-fixture-root 7)))))))

(deftest test-solidity-application-plan-derives-parent-verifier-registry-root
  (testing "application plan derives parent verifier-registry root from parent config"
    (let [plan (g/chain-configuration-transition->solidity-application-plan
                g/chain-configuration-transition-v0-to-v1-fixture
                g/chain-configuration-v0-fixture
                g/chain-configuration-v1-fixture)
          expected-vr (:verifier-registry/root g/chain-configuration-v0-fixture)]
       (is (= (:parent-verifier-registry-root plan)
              (str "0x" (subs expected-vr 7)))))))


(deftest test-solidity-application-plan-derives-new-verifier-registry-root
  (testing "application plan derives new verifier-registry root from new config"
    (let [plan (g/chain-configuration-transition->solidity-application-plan
                g/chain-configuration-transition-v0-to-v1-fixture
                g/chain-configuration-v0-fixture
                g/chain-configuration-v1-fixture)
          expected-vr (:verifier-registry/root g/chain-configuration-v1-fixture)]
       (is (= (:new-verifier-registry-root plan)
              (str "0x" (subs expected-vr 7)))))))


(deftest test-solidity-application-plan-rejects-parent-mismatch
  (testing "application plan rejects when parent config root doesn't match transition parent root"
    (let [wrong-parent (assoc g/chain-configuration-v0-fixture
                              :module-registry/root
                              "sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")]
      (is (thrown-with-msg?
           ExceptionInfo
           #"parent configuration root does not match"
           (g/chain-configuration-transition->solidity-application-plan
            g/chain-configuration-transition-v0-to-v1-fixture
            wrong-parent
            g/chain-configuration-v1-fixture))))))

(deftest test-solidity-application-plan-rejects-new-mismatch
  (testing "application plan rejects when new config root doesn't match transition new root"
    (let [wrong-new (assoc g/chain-configuration-v1-fixture
                           :module-registry/root
                           "sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")]
      (is (thrown-with-msg?
           ExceptionInfo
           #"new configuration root does not match"
           (g/chain-configuration-transition->solidity-application-plan
            g/chain-configuration-transition-v0-to-v1-fixture
            g/chain-configuration-v0-fixture
            wrong-new))))))

(deftest test-solidity-application-plan-deterministic
  (testing "application plan projection is deterministic"
    (let [plan1 (g/chain-configuration-transition->solidity-application-plan
                 g/chain-configuration-transition-v0-to-v1-fixture
                 g/chain-configuration-v0-fixture
                 g/chain-configuration-v1-fixture)
          plan2 (g/chain-configuration-transition->solidity-application-plan
                 g/chain-configuration-transition-v0-to-v1-fixture
                 g/chain-configuration-v0-fixture
                 g/chain-configuration-v1-fixture)]
      (is (= plan1 plan2)))))

(deftest test-solidity-application-plan-json-fixture-matches
  (testing "JSON fixture matches the application plan projection output"
    (let [plan (g/chain-configuration-transition->solidity-application-plan
                g/chain-configuration-transition-v0-to-v1-fixture
                g/chain-configuration-v0-fixture
                g/chain-configuration-v1-fixture)
          json (json/read-str (slurp "etc/conformance/fixtures/solidity-authorization/application-plan-v0-to-v1.json") :key-fn keyword)]
      (is (= (:decision-root plan) (:decisionRoot json)))
      (is (= (:target-mode plan) (:targetMode json)))
      (is (= (:target-root plan) (:targetRoot json)))
      (is (= (:parent-configuration-root plan) (:parentConfigurationRoot json)))
      (is (= (:new-configuration-root plan) (:newConfigurationRoot json)))
      (is (= (:parent-verifier-registry-root plan) (:parentVerifierRegistryRoot json)))
      (is (= (:new-verifier-registry-root plan) (:newVerifierRegistryRoot json)))
      (is (= (:epoch plan) (:epoch json))))))

(deftest test-no-new-hash-intent-added
  (testing "no new domain tag or hash intent was added for application plan"
    (is (not (contains? canonical/domain-tags :prf-solidity-application-plan)))
    (is (not (contains? canonical/domain-tags :prf-solidity-initialization)))))
