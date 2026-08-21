(ns resolver-sim.resubmission.genesis-test
  "Tests for the canonical resubmission-chain-genesis.v1 artifact:
   configuration validation/root, genesis construction, validation, root
   determinism, chain-id derivation, and store realization."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
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
