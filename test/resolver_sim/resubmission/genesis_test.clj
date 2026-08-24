(ns resolver-sim.resubmission.genesis-test
  "Tests for the canonical resubmission-chain-genesis.v1 artifact:
   configuration validation/root, genesis construction, validation, root
   determinism, chain-id derivation, and store realization."
  (:require [clojure.edn :as edn]
            [clojure.test :refer :all]
            [clojure.string :as str]
            [resolver-sim.genesis :as deployment-genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition])
  (:import [clojure.lang ExceptionInfo]))

;; ── Golden roots (computed at load) ─────────────────────────────────────

(def ^:private family "sha256:FAM")
(def ^:private receipt-pk "sha256:receipt-pk")

(def ^:private v2-family
  "sha256:e8f55f3c6ba772bef28ba78a5f3e301324b8d9f967870208eac9746ed01c460e")

(def ^:private disposition-pub
  "f1e4bbc6a6b0078ec0c02f504ff0cf5a1bebb152b7ff1b363444213294a07f97")

(def ^:private receipt-pub
  "ace4a2be7289e3cffd2a890b4aa2bbeabe2d2856c1a2f541825db06c87b5485d")

(def ^:private chain-id-no-keys
  "sha256:f0d365514efd6be9d2a563a35d0e456affb7e1210f9dc8af115c99c72b0926f4")

(def ^:private initial-state-root-no-keys
  "sha256:53e5ae09087f3733a54110c9a00f4cb227894f18f1384b7a8d88a929e5b66ffb")

(def ^:private cfg-root-no-keys
  "sha256:199e84173c0013469e63e9195e60c3d04658933c2546f8ffa059039d0b36a1f7")

(def ^:private genesis-root-no-keys
  "sha256:90ca7e21825c000d05ba2219811db56475891fdb8814acfb6ed479e89d24229c")

(def ^:private chain-id-with-receipt
  "sha256:698daf4a93cf348eaff54b872a0980480488a70fda2af8feb761ddc65fe8d26c")

(def ^:private cfg-root-with-receipt
  "sha256:66da0dc2f9c0b5c327d4ddcc94f9cfc245041453c27496687769489b6e30e58b")

(def ^:private genesis-root-with-receipt
  "sha256:99c7df9627be39c6a22edaf0ec8084614ecf8bf96f918c30b3fb3cc72623919f")

;; ── Golden fixtures ─────────────────────────────────────────────────────

(def genesis-no-keys
  (genesis/->genesis family))

(def genesis-with-receipt
  (genesis/->genesis family nil receipt-pk))

(def config-no-keys
  (:configuration genesis-no-keys))

(def config-with-receipt
  (:configuration genesis-with-receipt))

(def ^:private v2-conformance-fixture
  (delay
    (edn/read-string
     (slurp "etc/conformance/fixtures/resubmission-chain-genesis-v2.edn"))))

(defn- sha256-fixture-ref
  [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record label)))

;; ── Configuration validation ──────────────────────────────────────────────

(deftest test-configuration-is-valid
  (testing "a properly constructed configuration passes strict validation"
    (let [v (genesis/validate-resubmission-chain-configuration config-no-keys)]
      (is (:valid? v) (str "errors: " (:errors v))))))

(deftest test-configuration-accepts-nil-keys
  (testing "nil authority keys are explicit absence (accepted)"
    (let [v (genesis/validate-resubmission-chain-configuration config-no-keys)]
      (is (:valid? v))
      (is (nil? (:disposition-authority/public-key config-no-keys)))
      (is (nil? (:receipt-authority/public-key config-no-keys)))))
  (testing "string public keys are accepted"
    (let [cfg {:configuration/schema genesis/resubmission-chain-configuration-schema
               :disposition-authority/public-key "sha256:disp"
               :receipt-authority/public-key "sha256:recv"}
          v (genesis/validate-resubmission-chain-configuration cfg)]
      (is (:valid? v) (str "errors: " (:errors v))))))

(deftest test-configuration-rejects-unknown-keys
  (testing "unknown keys are rejected (fail-closed)"
    (let [invalid (assoc config-no-keys :unexpected/key "value")
          v (genesis/validate-resubmission-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown") (:errors v))))))

(deftest test-configuration-rejects-missing-keys
  (testing "missing required keys are rejected"
    (let [invalid (dissoc config-no-keys :configuration/schema)
          v (genesis/validate-resubmission-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "missing") (:errors v))))))

(deftest test-configuration-rejects-non-string-keys
  (testing "authority keys must be string or nil, not keywords"
    (let [invalid (assoc config-no-keys :disposition-authority/public-key :not-a-string)
          v (genesis/validate-resubmission-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a string or nil") (:errors v))))))

(deftest test-configuration-rejects-wrong-schema
  (testing "wrong configuration/schema is rejected"
    (let [invalid (assoc config-no-keys :configuration/schema "wrong")
          v (genesis/validate-resubmission-chain-configuration invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "schema") (:errors v))))))

(deftest test-configuration-rejects-non-map
  (testing "non-map input fails closed"
    (let [v (genesis/validate-resubmission-chain-configuration "not-a-map")]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a map") (:errors v))))))

;; ── Configuration root ───────────────────────────────────────────────────

(deftest test-configuration-root-golden
  (testing "configuration root is a stable golden vector"
    (is (= cfg-root-no-keys
           (genesis/resubmission-chain-configuration-root config-no-keys)))))

(deftest test-configuration-root-with-receipt-golden
  (testing "configuration root with a receipt key"
    (is (= cfg-root-with-receipt
           (genesis/resubmission-chain-configuration-root config-with-receipt)))))

(deftest test-configuration-root-mismatch-rejection
  (testing "2-arg root fn rejects mismatched expected value"
    (is (thrown-with-msg?
         ExceptionInfo
         #"caller-supplied configuration root does not match"
         (genesis/resubmission-chain-configuration-root
          config-no-keys
          "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))

(deftest test-configuration-root-rejects-invalid
  (testing "root fn throws on invalid configuration"
    (is (thrown-with-msg?
         ExceptionInfo
         #"is invalid"
         (genesis/resubmission-chain-configuration-root
          (assoc config-no-keys :unexpected/key "value"))))))

(deftest test-configuration-root-insists-on-closed-shape
  (testing "extra keys cause validation failure before root computation"
    (let [extra (assoc config-no-keys :extra/key "value")]
      (is (thrown-with-msg?
           ExceptionInfo
           #"is invalid"
           (genesis/resubmission-chain-configuration-root extra))))))

;; ── Genesis construction ─────────────────────────────────────────────────

(deftest test-genesis-construction-arity-1
  (testing "1-arity constructs genesis with nil authority keys"
    (let [g (genesis/->genesis family)]
      (is (= "resubmission-chain-genesis.v1" (:genesis/schema g)))
      (is (= family (:family/id g)))
      (is (nil? (get-in g [:configuration :disposition-authority/public-key])))
      (is (nil? (get-in g [:configuration :receipt-authority/public-key])))
      (is (hash-ref/valid-sha256-ref? (:chain/id g)))
      (is (hash-ref/valid-sha256-ref? (:initial-state/root g))))))

(deftest test-genesis-construction-arity-3
  (testing "3-arity incorporates authority keys"
    (let [g (genesis/->genesis family nil receipt-pk)]
      (is (= nil (get-in g [:configuration :disposition-authority/public-key])))
      (is (= receipt-pk (get-in g [:configuration :receipt-authority/public-key])))
      (is (= chain-id-with-receipt (:chain/id g)))
      (is (hash-ref/valid-sha256-ref? (:initial-state/root g))))))

(deftest test-genesis-is-self-contained
  (testing "a genesis is realizable with no out-of-band information"
    (let [g (genesis/->genesis family nil receipt-pk)
          cfg (:configuration g)
          family-id (:family/id g)]
      (is (= family-id family))
      (is (= (:disposition-authority/public-key cfg) nil))
      (is (= (:receipt-authority/public-key cfg) receipt-pk))
      (is (hash-ref/valid-sha256-ref? (:chain/id g)))
      (is (hash-ref/valid-sha256-ref? (:initial-state/root g))))))

;; ── Chain-id derivation ──────────────────────────────────────────────────

(deftest test-chain-id-golden-no-keys
  (testing "chain-id for nil-key configuration is a stable golden value"
    (is (= chain-id-no-keys (:chain/id genesis-no-keys)))))

(deftest test-chain-id-golden-with-receipt
  (testing "chain-id changes when receipt key is set"
    (is (= chain-id-with-receipt (:chain/id genesis-with-receipt)))))

(deftest test-chain-id-distinct-from-genesis-root
  (testing "chain-id is derived from identity basis, not the genesis root"
    (is (not= chain-id-no-keys genesis-root-no-keys))
    (is (not= chain-id-with-receipt genesis-root-with-receipt))))

(deftest test-chain-id-distinct-from-initial-state-root
  (testing "chain-id is distinct from initial-state/root"
    (is (not= chain-id-no-keys initial-state-root-no-keys))
    (is (not= chain-id-with-receipt initial-state-root-no-keys))))

(deftest test-chain-id-deterministic
  (testing "same family + keys always produce same chain-id"
    (let [g1 (genesis/->genesis family nil nil)
          g2 (genesis/->genesis family nil nil)]
      (is (= (:chain/id g1) (:chain/id g2))))))

;; ── Initial-state/root ───────────────────────────────────────────────────

(deftest test-initial-state-root-golden
  (testing "initial-state/root matches transition/empty-state root"
    (is (= initial-state-root-no-keys
           (transition/state-root
            (transition/empty-state family nil))))))

(deftest test-initial-state-root-matches-declared
  (testing "the genesis declares the computed empty-state root"
    (is (= initial-state-root-no-keys
           (:initial-state/root genesis-no-keys)))))

;; ── Genesis root ─────────────────────────────────────────────────────────

(deftest test-genesis-root-golden-no-keys
  (testing "genesis root is a stable golden vector"
    (is (= genesis-root-no-keys
           (genesis/resubmission-chain-genesis-root genesis-no-keys)))))

(deftest test-genesis-root-golden-with-receipt
  (testing "genesis root with a receipt key"
    (is (= genesis-root-with-receipt
           (genesis/resubmission-chain-genesis-root genesis-with-receipt)))))

(deftest test-genesis-root-mismatch-rejection
  (testing "2-arg root fn rejects mismatched expected value"
    (is (thrown-with-msg?
         ExceptionInfo
         #"caller-supplied genesis root does not match"
         (genesis/resubmission-chain-genesis-root
          genesis-no-keys
          "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))

;; ── Genesis validation ───────────────────────────────────────────────────

(deftest test-genesis-is-valid
  (testing "a properly constructed genesis passes strict closed-shape validation"
    (let [v (genesis/validate-resubmission-chain-genesis genesis-no-keys)]
      (is (:valid? v) (str "errors: " (:errors v))))))

(deftest test-genesis-valid-?-delegate
  (testing "resubmission-chain-genesis-valid? agrees with validate"
    (is (true? (genesis/resubmission-chain-genesis-valid? genesis-no-keys)))
    (is (false? (genesis/resubmission-chain-genesis-valid?
                 (assoc genesis-no-keys :genesis/schema "wrong"))))))

(deftest test-genesis-rejects-unknown-keys
  (testing "unknown top-level keys are rejected"
    (let [invalid (assoc genesis-no-keys :unexpected/key "value")
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown") (:errors v))))))

(deftest test-genesis-rejects-missing-keys
  (testing "missing required keys are rejected"
    (let [invalid (dissoc genesis-no-keys :chain/id)
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "missing") (:errors v))))))

(deftest test-genesis-rejects-nil-root-fields
  (testing "nil chain/id is rejected"
    (let [invalid (assoc genesis-no-keys :chain/id nil)
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v)))))
  (testing "nil initial-state/root is rejected"
    (let [invalid (assoc genesis-no-keys :initial-state/root nil)
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v))))))

(deftest test-genesis-rejects-wrong-schema
  (testing "wrong genesis/schema is rejected"
    (let [invalid (assoc genesis-no-keys :genesis/schema "wrong")
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "schema") (:errors v))))))

(deftest test-genesis-rejects-bad-chain-id
  (testing "non-sha256 chain-id is rejected"
    (let [invalid (assoc genesis-no-keys :chain/id "not-a-hash")
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "chain/id") (:errors v))))))

(deftest test-genesis-rejects-nil-family-id
  (testing "nil family/id is rejected"
    (let [invalid (assoc genesis-no-keys :family/id nil)
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v))))))

(deftest test-genesis-rejects-chain-id-mismatch
  (testing "chain-id that doesn't match derivation is rejected"
    (let [g (genesis/->genesis family)
          invalid (assoc g :chain/id "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "chain/id does not match") (:errors v))))))

(deftest test-genesis-rejects-initial-state-mismatch
  (testing "initial-state/root that doesn't match empty-state is rejected"
    (let [g (genesis/->genesis family)
          invalid (assoc g :initial-state/root "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "initial-state/root does not match") (:errors v))))))

(deftest test-genesis-rejects-invalid-configuration
  (testing "a genesis with an invalid configuration is rejected"
    (let [g (genesis/->genesis family)
          invalid (assoc-in g [:configuration :disposition-authority/public-key] :not-a-string)
          v (genesis/validate-resubmission-chain-genesis invalid)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "configuration") (:errors v))))))

(deftest test-genesis-rejects-non-map
  (testing "non-map input fails closed"
    (let [v (genesis/validate-resubmission-chain-genesis "not-a-map")]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a map") (:errors v))))))

(deftest test-genesis-root-rejects-invalid
  (testing "genesis-root fn throws on invalid genesis"
    (is (thrown-with-msg?
         ExceptionInfo
         #"is invalid"
         (genesis/resubmission-chain-genesis-root
          (assoc genesis-no-keys :genesis/schema "wrong"))))))

;; ── Genesis root determinism ─────────────────────────────────────────────

(deftest test-genesis-root-deterministic
  (testing "same genesis content produces same root"
    (let [g1 (genesis/->genesis family)
          g2 (genesis/->genesis family)]
      (is (= (genesis/resubmission-chain-genesis-root g1)
             (genesis/resubmission-chain-genesis-root g2)))))
  (testing "different receipt key produces different root"
    (let [root-nok (genesis/resubmission-chain-genesis-root (genesis/->genesis family))
          root-ok (genesis/resubmission-chain-genesis-root genesis-with-receipt)]
      (is (not= root-nok root-ok)))))

;; ── Deployment-scoped genesis v2 ─────────────────────────────────────────

(deftest test-genesis-v2-conformance-vectors
  (testing "non-authoritative and production V2 vectors reproduce canonical roots"
    (let [vectors (:vectors @v2-conformance-fixture)
          instances {deployment-genesis/chain-instance-genesis-ethereum-fixture-root
                     deployment-genesis/chain-instance-genesis-ethereum-fixture
                     deployment-genesis/chain-instance-genesis-eez-fixture-root
                     deployment-genesis/chain-instance-genesis-eez-fixture}]
      (is (= "resubmission-chain-genesis-v2-fixture.v1"
             (:fixture/schema @v2-conformance-fixture)))
      (is (false? (:fixture/authoritative? @v2-conformance-fixture)))
      (doseq [vector vectors]
        (let [artifact (:genesis vector)]
          (testing (:fixture/id vector)
            (is (:valid? (genesis/validate-resubmission-chain-genesis-v2 artifact)))
            (is (= (:expected/genesis-root vector)
                   (genesis/resubmission-chain-genesis-v2-root artifact)))
            (is (= (:configuration/root artifact)
                   (genesis/resubmission-chain-configuration-root
                    (:configuration artifact))))
            (is (= (:chain/id artifact)
                   (genesis/resubmission-chain-identity-v2-root
                    (:protocol-genesis/root artifact)
                    (:chain-instance-genesis/root artifact)
                    (:family/id artifact)
                    (:configuration/root artifact))))
            (when (:expected/identity-canonical-bytes vector)
              (let [identity-basis {:protocol-genesis/root (:protocol-genesis/root artifact)
                                    :chain-instance-genesis/root (:chain-instance-genesis/root artifact)
                                    :family/id (:family/id artifact)
                                    :initial-configuration/root (:configuration/root artifact)}
                    proj (hc/project-resubmission-chain-identity-v2
                          identity-basis :prf-resubmission-chain-identity-v2)]
                (is (= (:expected/identity-canonical-bytes vector)
                       (hc/canonical-bytes-hex proj)))
                (is (= (:expected/identity-domain-hash vector)
                       (hash-ref/sha256-ref
                        (hc/domain-hash :prf-resubmission-chain-identity-v2 proj))))))
            (when (:expected/genesis-canonical-bytes vector)
              (let [proj (hc/project-resubmission-chain-genesis-v2
                          artifact :prf-resubmission-chain-genesis-v2)]
                (is (= (:expected/genesis-canonical-bytes vector)
                       (hc/canonical-bytes-hex proj)))
                (is (= (:expected/genesis-domain-hash vector)
                       (hash-ref/sha256-ref
                        (hc/domain-hash :prf-resubmission-chain-genesis-v2 proj))))))
            (is (:valid?
                 (genesis/validate-resubmission-chain-genesis-v2-for-deployment
                  artifact
                  deployment-genesis/protocol-genesis-fixture
                  (instances (:chain-instance-genesis/root artifact)))))))))))

(deftest test-genesis-v2-deployment-scoping
  (let [protocol-root deployment-genesis/protocol-genesis-fixture-root
        ethereum-root deployment-genesis/chain-instance-genesis-ethereum-fixture-root
        eez-root deployment-genesis/chain-instance-genesis-eez-fixture-root
        ethereum (genesis/->genesis-v2 protocol-root ethereum-root v2-family nil receipt-pk)
        eez (genesis/->genesis-v2 protocol-root eez-root v2-family nil receipt-pk)
        alternate-protocol
        (assoc deployment-genesis/protocol-genesis-fixture
               :canonicalisation/root (sha256-fixture-ref "resubmission-v2.alternate-protocol"))
        alternate-protocol-root
        (deployment-genesis/protocol-genesis-root alternate-protocol)
        alternate-instance
        (assoc deployment-genesis/chain-instance-genesis-ethereum-fixture
               :protocol/genesis-root alternate-protocol-root)
        alternate-instance-root
        (deployment-genesis/chain-instance-genesis-root alternate-instance)
        alternate
        (genesis/->genesis-v2 alternate-protocol-root alternate-instance-root v2-family nil receipt-pk)]
    (testing "identical family and configuration on distinct chain instances have distinct identities"
      (is (= (:configuration/root ethereum) (:configuration/root eez)))
      (is (not= (:chain/id ethereum) (:chain/id eez))))
    (testing "a distinct protocol/deployment pair has a distinct identity"
      (is (not= (:chain/id ethereum) (:chain/id alternate)))
      (is (:valid?
           (genesis/validate-resubmission-chain-genesis-v2-for-deployment
            alternate alternate-protocol alternate-instance))))
    (testing "V2 retains V1-compatible family identifiers"
      (doseq [family-id [family :workbench]]
        (is (:valid?
             (genesis/validate-resubmission-chain-genesis-v2
              (genesis/->genesis-v2 protocol-root ethereum-root family-id nil receipt-pk))))))))

(deftest test-genesis-v2-trusted-deployment-binding
  (let [base (genesis/->genesis-v2
              deployment-genesis/protocol-genesis-fixture-root
              deployment-genesis/chain-instance-genesis-ethereum-fixture-root
              v2-family nil receipt-pk)
        syntactic-valid-untrusted-root
        (sha256-fixture-ref "resubmission-v2.untrusted-deployment")
        untrusted (genesis/->genesis-v2
                   syntactic-valid-untrusted-root
                   deployment-genesis/chain-instance-genesis-ethereum-fixture-root
                   v2-family nil receipt-pk)
        inconsistent-instance
        (assoc deployment-genesis/chain-instance-genesis-ethereum-fixture
               :protocol/genesis-root (sha256-fixture-ref "resubmission-v2.inconsistent-protocol"))]
    (testing "the declared V2 roots must equal independently trusted deployment artifacts"
      (is (:valid? (genesis/validate-resubmission-chain-genesis-v2 untrusted)))
      (is (not (:valid?
                (genesis/validate-resubmission-chain-genesis-v2-for-deployment
                 untrusted
                 deployment-genesis/protocol-genesis-fixture
                 deployment-genesis/chain-instance-genesis-ethereum-fixture)))))
    (testing "the trusted chain-instance artifact must belong to the trusted protocol artifact"
      (is (not (:valid?
                (genesis/validate-resubmission-chain-genesis-v2-for-deployment
                 base deployment-genesis/protocol-genesis-fixture
                 inconsistent-instance)))))))

(deftest test-genesis-v2-rejects-modified-commitments
  (let [base (genesis/->genesis-v2
              deployment-genesis/protocol-genesis-fixture-root
              deployment-genesis/chain-instance-genesis-ethereum-fixture-root
              v2-family nil receipt-pk)
        invalids
        [(assoc-in base [:configuration :receipt-authority/public-key] "sha256:modified")
         (assoc base :family/id "sha256:modified-family")
         (assoc base :protocol-genesis/root (sha256-fixture-ref "resubmission-v2.modified-protocol"))
         (assoc base :initial-state/root (sha256-fixture-ref "resubmission-v2.modified-state"))
         (dissoc base :configuration/root)
         (assoc base :unexpected/key true)]]
    (doseq [invalid invalids]
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis-v2 invalid)))))))

(deftest test-genesis-v2-is-closed-and-domain-separated-from-v1
  (let [v1 (genesis/->genesis family nil receipt-pk)
        v2 (genesis/->genesis-v2
            deployment-genesis/protocol-genesis-fixture-root
            deployment-genesis/chain-instance-genesis-ethereum-fixture-root
            v2-family nil receipt-pk)]
    (testing "V2 rejects missing and unknown fields"
      (is (not (:valid?
                (genesis/validate-resubmission-chain-genesis-v2
                 (dissoc v2 :chain-instance-genesis/root)))))
      (is (not (:valid?
                (genesis/validate-resubmission-chain-genesis-v2
                 (assoc v2 :unexpected/key true))))))
    (testing "V1 and V2 validators and root functions are non-interchangeable"
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis v2))))
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis-v2 v1))))
      (is (thrown? ExceptionInfo
                   (genesis/resubmission-chain-genesis-root v2)))
      (is (thrown? ExceptionInfo
                   (genesis/resubmission-chain-genesis-v2-root v1))))
    (testing "V1 projections and roots remain unchanged"
      (is (= genesis-root-with-receipt
             (genesis/resubmission-chain-genesis-root v1)))
      (is (= chain-id-with-receipt (:chain/id v1)))
      (is (contains? hc/domain-tags :prf-resubmission-chain-identity-v2))
      (is (contains? hc/domain-tags :prf-resubmission-chain-genesis-v2))
      (is (not= (:chain/id v1) (:chain/id v2)))
      (is (not= (genesis/resubmission-chain-genesis-root v1)
                (genesis/resubmission-chain-genesis-v2-root v2))))))

;; ── Store realization ────────────────────────────────────────────────────

(deftest test-new-chain-from-genesis-validates
  (testing "new-chain-from-genesis rejects invalid genesis"
    (is (thrown-with-msg?
         ExceptionInfo
         #"invalid resubmission-chain-genesis"
         (chain/new-chain-from-genesis
          (assoc (genesis/->genesis family) :genesis/schema "wrong"))))))

(deftest test-new-chain-from-genesis-derives-store-fields
  (testing "store fields are derived from the genesis"
    (let [g genesis-with-receipt
          c (chain/new-chain-from-genesis g)]
      (is (= family (store/family-id-of c)))
      (is (= receipt-pk (.receipt-public-hex c)))
      (is (nil? (.disposition-public-hex c))))))

(deftest test-genesis-g0-is-realization-initial-state
  (testing "genesis initial-state/root is the actual store initial state root"
    (let [g genesis-with-receipt
          c (chain/new-chain-from-genesis g)
          s0 (store/state-of c)]
      (is (= (:initial-state/root g)
             (transition/state-root s0))
          "store initial state must be the G0 committed in genesis")))
  (testing "first transition successor of genesis-declared G0"
    (let [g genesis-no-keys
          c (chain/new-chain-from-genesis g)
          s0 (store/state-of c)]
      (is (= (:initial-state/root g) (transition/state-root s0))
          "G0 is the transactional predecessor, not merely a matching root"))))

(deftest test-genesis-of-returns-stored-genesis
  (testing "stores created from genesis expose it via genesis-of"
    (let [g genesis-with-receipt
          c (chain/new-chain-from-genesis g)]
      (is (= g (store/genesis-of c)))))
  (testing "stores created via new-chain have a non-nil genesis"
    (let [c (chain/new-chain family nil receipt-pk)]
      (is (some? (store/genesis-of c)))))
  (testing "legacy constructors (direct store/new-resubmission-store) have nil genesis"
    (let [s (store/new-resubmission-store family)]
      (is (nil? (store/genesis-of s))))))

;; ── Backward compatibility ───────────────────────────────────────────────

(deftest test-new-chain-arity-1
  (testing "1-arity new-chain still works"
    (let [c (chain/new-chain family)]
      (is (some? c))
      (is (= family (store/family-id-of c)))
      (is (nil? (.receipt-public-hex c))))))

(deftest test-new-chain-arity-3-stores-receipt-key
  (testing "3-arity new-chain stores receipt public key"
    (let [c (chain/new-chain family nil receipt-pk)]
      (is (= receipt-pk (.receipt-public-hex c))))))

(deftest test-new-chain-arity-3-constructs-genesis
  (testing "3-arity new-chain constructs a genesis internally"
    (let [c (chain/new-chain family nil receipt-pk)
          g (store/genesis-of c)]
      (is (some? g))
      (is (= "resubmission-chain-genesis.v1" (:genesis/schema g)))
      (is (= family (:family/id g)))
      (is (hash-ref/valid-sha256-ref? (:chain/id g))))))

(deftest test-new-chain-with-keyword-family-id
  (testing "keyword family-id (notebook compatibility) works without validation"
    (let [c (chain/new-chain :workbench :disposition-key)]
      (is (some? c))
      (is (= :workbench (store/family-id-of c))))))

;; ── V2 adversarial tests ──

(deftest test-v2-sort-crash-mixed-keys
  (testing "validator does not crash on mixed-type keys in configuration"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family nil receipt-pub)
          invalid-cfg (assoc (:configuration g)
                             "string-key" "bad"
                             :disposition-authority/public-key "f1e4bbc6a6b0078ec0c02f504ff0cf5a1bebb152b7ff1b363444213294a07f97")
          invalid (assoc g :configuration invalid-cfg)]
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis-v2 invalid)))
          "mixed-type keys should be reported, not crash"))))

(deftest test-v2-sort-crash-top-level-mixed-keys
  (testing "validator does not crash on mixed-type top-level keys"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family nil receipt-pub)
          invalid (assoc g "extra-key" true)]
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis-v2 invalid)))
          "mixed-type top-level keys should be reported, not crash"))))

(deftest test-v2-identity-v2-root-retains-supported-family-ids
  (testing "resubmission-chain-identity-v2-root retains V1-compatible family identifiers"
    (let [cfg-root
          (:configuration/root
           (genesis/->genesis-v2
            deployment-genesis/protocol-genesis-fixture-root
            deployment-genesis/chain-instance-genesis-ethereum-fixture-root
            v2-family disposition-pub receipt-pub))]
      (is (hash-ref/valid-sha256-ref?
           (genesis/resubmission-chain-identity-v2-root
            deployment-genesis/protocol-genesis-fixture-root
            deployment-genesis/chain-instance-genesis-ethereum-fixture-root
            "sha256:FAM"
            cfg-root)))
      (is (hash-ref/valid-sha256-ref?
           (genesis/resubmission-chain-identity-v2-root
            deployment-genesis/protocol-genesis-fixture-root
            deployment-genesis/chain-instance-genesis-ethereum-fixture-root
            :workbench
            cfg-root)))
      (is (thrown? ExceptionInfo
                   (genesis/resubmission-chain-identity-v2-root
                    nil
                    deployment-genesis/chain-instance-genesis-ethereum-fixture-root
                    v2-family
                    cfg-root))))))

(deftest test-v2-strict-root-verifier-rejects-nil-expected
  (testing "verify-resubmission-chain-genesis-v2-root! rejects nil expected"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family disposition-pub receipt-pub)]
      (is (thrown-with-msg?
           ExceptionInfo
           #"root must not be nil"
           (genesis/verify-resubmission-chain-genesis-v2-root! g nil))))))

(deftest test-v2-strict-root-verifier-rejects-malformed-expected
  (testing "verify-resubmission-chain-genesis-v2-root! rejects malformed expected"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family disposition-pub receipt-pub)]
      (is (thrown-with-msg?
           ExceptionInfo
           #"not a valid sha256 reference"
           (genesis/verify-resubmission-chain-genesis-v2-root! g "not-a-root")))
      (is (thrown-with-msg?
           ExceptionInfo
           #"not a valid sha256 reference"
           (genesis/verify-resubmission-chain-genesis-v2-root! g 12345))))))

(deftest test-v2-strict-root-verifier-accepts-matching
  (testing "verify-resubmission-chain-genesis-v2-root! accepts a matching expected root"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family disposition-pub receipt-pub)
          root (genesis/resubmission-chain-genesis-v2-root g)]
      (is (= root (genesis/verify-resubmission-chain-genesis-v2-root! g root))))))

(deftest test-v2-strict-root-verifier-rejects-mismatch
  (testing "verify-resubmission-chain-genesis-v2-root! rejects mismatched root"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family disposition-pub receipt-pub)]
      (is (thrown-with-msg?
           ExceptionInfo
           #"does not match computed root"
           (genesis/verify-resubmission-chain-genesis-v2-root!
            g "sha256:0000000000000000000000000000000000000000000000000000000000000000"))))))

(deftest test-v2-validation-derivation-guarded-on-invalid-config
  (testing "chain-id and initial-state derivation skipped when configuration is invalid"
    (let [g (genesis/->genesis-v2
             deployment-genesis/protocol-genesis-fixture-root
             deployment-genesis/chain-instance-genesis-ethereum-fixture-root
             v2-family nil receipt-pub)
          invalid-g (assoc-in g [:configuration :disposition-authority/public-key] :not-a-string)
          errors (:errors (genesis/validate-resubmission-chain-genesis-v2 invalid-g))]
      (is (not (:valid? (genesis/validate-resubmission-chain-genesis-v2 invalid-g))))
      (is (some #(str/includes? % "must be a string or nil") errors)
          "configuration errors are reported")
      (is (not-any? #(str/includes? % "does not match") errors)
          "no spurious mismatch errors when configuration is invalid"))

    (deftest test-v2-permissive-root-accepts-nil-expected
      (testing "2-arg resubmission-chain-genesis-v2-root still accepts nil (backward compat)"
        (let [g (genesis/->genesis-v2
                 deployment-genesis/protocol-genesis-fixture-root
                 deployment-genesis/chain-instance-genesis-ethereum-fixture-root
                 v2-family disposition-pub receipt-pub)]
          (is (= (genesis/resubmission-chain-genesis-v2-root g)
                 (genesis/resubmission-chain-genesis-v2-root g nil))))))

    (deftest test-v2-production-fixture-uses-real-keypairs
      (testing "production fixture vectors use real Ed25519 keys, not synthetic placeholders"
        (let [vectors (:vectors @v2-conformance-fixture)
              prod (filter #(:fixture/authoritative? %) vectors)]
          (is (seq prod) "at least one production vector exists")
          (doseq [vector prod]
            (let [cfg (:configuration (:genesis vector))]
              (is (some? (get-in cfg [:disposition-authority/public-key]))
                  "disposition key must be present in production vectors")
              (is (not= "sha256:receipt-pk"
                        (get-in cfg [:receipt-authority/public-key]))
                  "receipt key must not be the synthetic placeholder")
              (is (re-find #"^[0-9a-f]{64}$"
                           (get-in cfg [:disposition-authority/public-key]))
                  "disposition key must be 64 lowercase hex chars")
              (is (re-find #"^[0-9a-f]{64}$"
                           (get-in cfg [:receipt-authority/public-key]))
                  "receipt key must be 64 lowercase hex chars")))))))

  (deftest test-v2-fixture-key-distinction
    (testing "non-authoritative vectors use synthetic keys; production use real keys"
      (let [vectors (:vectors @v2-conformance-fixture)]
        (doseq [vector vectors]
          (let [cfg (-> vector :genesis :configuration)
                disp-key (:disposition-authority/public-key cfg)
                recv-key (:receipt-authority/public-key cfg)]
            (if (:fixture/authoritative? vector)
              (do (is (some? disp-key)
                      "production vectors have real disposition keys")
                  (is (not= "sha256:receipt-pk" recv-key)
                      "production vectors have real receipt keys"))
              (do (is (nil? disp-key)
                      "non-authoritative vectors have nil disposition")
                  (is (= "sha256:receipt-pk" recv-key)
                      "non-authoritative vectors use synthetic receipt placeholder")))))))))
