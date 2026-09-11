(ns resolver-sim.resubmission.authoritative-admission-test
  "Tests for Phase 0 authoritative admission:
    - authority-context.v1 (construction, root, validation)
    - authoritative-checkpoint.v1 (initial/successor, root, validation)
    - attempt-disposition-authoritative.v2 (hash, sign, verify)
    - transition dispatch for apply-authoritative-disposition
    - authoritative store CAS on checkpoint head
    - disjoint result types (:authoritative vs :local-replay)"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.authority-context :as authority-context]
            [resolver-sim.resubmission.authoritative-checkpoint :as auth-ckpt]
            [resolver-sim.resubmission.authoritative-store :as auth-store]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.genesis-authorization :as genesis-authz]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.support.ed25519 :as ed25519])
  (:import [clojure.lang ExceptionInfo]))

;; ── Golden fixtures ────────────────────────────────────────────────────────

(def ^:private family "sha256:FAM")
(def ^:private receipt-pk "sha256:receipt-pk")

(def ^:private authority-keypair
  "Ed25519 keypair for the disposition authority key."
  (ed25519/keypair :test-authority-key))

(def ^:private authority-public-hex (:public-hex authority-keypair))
(def ^:private authority-private-key (:private-key authority-keypair))

(def ^:private genesis-with-auth-key
  "Genesis with a configured disposition authority public key."
  (genesis/->genesis family authority-public-hex receipt-pk))

(def ^:private genesis-root-with-key
  (genesis/resubmission-chain-genesis-root genesis-with-auth-key))

(def ^:private config-root-with-key
  (genesis/resubmission-chain-configuration-root (:configuration genesis-with-auth-key)))

(def ^:private fa-hash
  "A content-addressed hash standing in for a force-authorisation artifact."
  "sha256:0000000000000000000000000000000000000000000000000000000000000001")

(def ^:private governance-root
  "A plausible three-member-authority report root."
  "sha256:0000000000000000000000000000000000000000000000000000000000000002")

(def ^:private valid-authz
  "A structurally valid authorization artifact binding genesis-with-auth-key."
  {:authorization/schema "resubmission-chain-genesis-authorization.v1"
   :authorization/genesis-root genesis-root-with-key
   :authorization/force-authorisation-hash fa-hash
   :authorization/authority-report-root governance-root})

(def ^:private authz-root
  "Canonical root of valid-authz."
  (genesis-authz/genesis-authorization-root valid-authz))

(def ^:private authority-context-ctx
  "The canonical authority context, computed at load time."
  (authority-context/build-authority-context genesis-with-auth-key authz-root))

(def ^:private ctx-root
  (:authority/context-root authority-context-ctx))

(def ^:private ckpt0
  "The genesis authoritative checkpoint C0."
  (auth-ckpt/build-initial-checkpoint genesis-with-auth-key valid-authz authority-context-ctx))

(def ^:private ckpt0-root
  (:checkpoint/root ckpt0))

(def ^:private sample-receipt-hash
  "A sample receipt hash for disposition tests."
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-attempt-disposition-v1
                   {:test "sample-receipt"})))

;; ── Authority context tests ────────────────────────────────────────────────

(deftest test-authority-context-construction
  (testing "build-authority-context produces a well-formed context"
    (let [ctx (authority-context/build-authority-context genesis-with-auth-key authz-root)]
      (is (= authority-context/authority-context-schema
             (:authority/context-schema ctx)))
      (is (= genesis-root-with-key (:authority/genesis-root ctx)))
      (is (= config-root-with-key (:authority/configuration-root ctx)))
      (is (= authz-root (:authority/authorization-root ctx)))
      (is (= authority-public-hex (:authority/public-key ctx)))
      (is (= 0 (:authority/epoch ctx)))
      (is (= [:prf.resubmission/apply-disposition]
             (:authority/permitted-actions ctx)))
      (is (hash-ref/valid-sha256-ref? (:authority/context-root ctx)))))

  (testing "context root is deterministic (same inputs -> same root)"
    (let [ctx2 (authority-context/build-authority-context genesis-with-auth-key authz-root)]
      (is (= ctx-root (:authority/context-root ctx2)))))

  (testing "context root changes when public key changes"
    (let [alt-key (ed25519/keypair :alt-key)
          genesis-alt (genesis/->genesis family (:public-hex alt-key) receipt-pk)
          authz-alt {:authorization/schema "resubmission-chain-genesis-authorization.v1"
                     :authorization/genesis-root (genesis/resubmission-chain-genesis-root genesis-alt)
                     :authorization/force-authorisation-hash fa-hash
                     :authorization/authority-report-root governance-root}
          authz-alt-root (genesis-authz/genesis-authorization-root authz-alt)
          ctx-alt (authority-context/build-authority-context genesis-alt authz-alt-root)]
      (is (not= ctx-root (:authority/context-root ctx-alt)))))

  (testing "context root is a valid sha256 reference"
    (is (hash-ref/valid-sha256-ref? ctx-root))))

(deftest test-authority-context-validation
  (testing "rejects non-map input"
    (let [v (authority-context/validate-authority-context "not-a-map")]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must be a map") (:errors v)))))

  (testing "rejects wrong schema"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/context-schema "wrong"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "schema") (:errors v)))))

  (testing "rejects unknown keys (closed shape)"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/unknown "value"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown") (:errors v)))))

  (testing "rejects mismatched context-root"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/context-root
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000")
             ctx-root)]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "does not match") (:errors v)))))

  (testing "rejects unsupported action"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/permitted-actions
                    [:prf.resubmission/nonexistent]))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unsupported action") (:errors v)))))

  (testing "rejects negative epoch"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/epoch -1))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "epoch") (:errors v)))))

  (testing "rejects duplicate permitted actions"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/permitted-actions
                    [:prf.resubmission/apply-disposition
                     :prf.resubmission/apply-disposition]))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "duplicate") (:errors v)))))

  (testing "rejects nil genesis-root"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/genesis-root nil))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v)))))

  (testing "rejects nil configuration-root"
    (let [v (authority-context/validate-authority-context
             (assoc authority-context-ctx :authority/configuration-root nil))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "must not be nil") (:errors v)))))

  (testing "valid context passes validation with expected-root"
    (let [v (authority-context/validate-authority-context
             authority-context-ctx
             ctx-root)]
      (is (:valid? v)))))

;; ── Authoritative checkpoint tests ─────────────────────────────────────────

(deftest test-checkpoint-construction
  (testing "initial checkpoint C0 has correct bindings"
    (is (= auth-ckpt/checkpoint-schema (:checkpoint/schema ckpt0)))
    (is (= (:chain/id genesis-with-auth-key) (:checkpoint/chain-id ckpt0)))
    (is (= genesis-root-with-key (:checkpoint/genesis-root ckpt0)))
    (is (= (:initial-state/root genesis-with-auth-key)
           (:checkpoint/state-root ckpt0)))
    (is (= config-root-with-key (:checkpoint/configuration-root ckpt0)))
    (is (= ctx-root (:checkpoint/authority-context-root ckpt0)))
    (is (= 0 (:checkpoint/epoch ckpt0)))
    (is (= 0 (:checkpoint/sequence ckpt0)))
    (is (nil? (:checkpoint/predecessor-root ckpt0)))
    (is (= :authoritative (:checkpoint/authorization-mode ckpt0)))
    (is (hash-ref/valid-sha256-ref? (:checkpoint/root ckpt0))))

  (testing "C0 root is deterministic"
    (let [c0-again (auth-ckpt/build-initial-checkpoint genesis-with-auth-key
                                                       valid-authz
                                                       authority-context-ctx)]
      (is (= ckpt0-root (:checkpoint/root c0-again)))))

  (testing "C0 excludes self-hash from projection"
    (let [without-root (dissoc ckpt0 :checkpoint/root)
          recomputed (auth-ckpt/checkpoint-root without-root)]
      (is (= ckpt0-root recomputed)))))

(deftest test-checkpoint-successor
  (testing "successor checkpoint inherits correct fields and increments sequence"
    (let [state-root (:checkpoint/state-root ckpt0)
          ckpt1 (auth-ckpt/build-successor-checkpoint ckpt0 state-root)]
      (is (= ckpt0-root (:checkpoint/predecessor-root ckpt1)))
      (is (= 1 (:checkpoint/sequence ckpt1)))
      (is (= state-root (:checkpoint/state-root ckpt1)))
      (is (= (:chain/id genesis-with-auth-key) (:checkpoint/chain-id ckpt1)))
      (is (= genesis-root-with-key (:checkpoint/genesis-root ckpt1)))
      (is (= config-root-with-key (:checkpoint/configuration-root ckpt1)))
      (is (= ctx-root (:checkpoint/authority-context-root ckpt1)))
      (is (hash-ref/valid-sha256-ref? (:checkpoint/root ckpt1)))
      (is (not= ckpt0-root (:checkpoint/root ckpt1)))))

  (testing "successor with different state-root produces different root"
    (let [new-state-root (hash-ref/sha256-ref
                          (hc/domain-hash :prf-resubmission-chain-state-v1
                                          {:chain/family-id family
                                           :chain/version 1
                                           :transaction/commit-index 0
                                           :chain/head nil
                                           :chain/successor-by-parent {}
                                           :chain/effective-disposition-by-receipt {}
                                           :chain/disposition-head-by-receipt {}
                                           :chain/idempotency-index {}
                                           :chain/content-index {}}))

          ckpt1 (auth-ckpt/build-successor-checkpoint ckpt0 new-state-root)]
      (is (not= ckpt0-root (:checkpoint/root ckpt1)))))

  (testing "successor root is deterministic"
    (let [ckpt1a (auth-ckpt/build-successor-checkpoint
                  ckpt0 (:checkpoint/state-root ckpt0))
          ckpt1b (auth-ckpt/build-successor-checkpoint
                  ckpt0 (:checkpoint/state-root ckpt0))]
      (is (= (:checkpoint/root ckpt1a)
             (:checkpoint/root ckpt1b))))))

(deftest test-checkpoint-validation
  (testing "valid checkpoint passes validation"
    (let [v (auth-ckpt/validate-checkpoint ckpt0)]
      (is (:valid? v))))

  (testing "rejects wrong schema"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/schema "wrong"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "schema") (:errors v)))))

  (testing "rejects unknown keys"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/unknown "value"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "unknown") (:errors v)))))

  (testing "rejects missing required keys"
    (let [v (auth-ckpt/validate-checkpoint (dissoc ckpt0 :checkpoint/chain-id))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "missing") (:errors v)))))

  (testing "rejects wrong authorization-mode"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/authorization-mode :local-replay))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "authorization-mode") (:errors v)))))

  (testing "rejects mismatched root"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/root
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000")
             ckpt0-root)]
      (is (not (:valid? v)))))

  (testing "rejects non-sha256 predecessor-root"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/predecessor-root "not-a-hash"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "predecessor") (:errors v)))))

  (testing "rejects non-sha256 chain-id"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/chain-id "not-a-hash"))]
      (is (not (:valid? v)))
      (is (some #(str/includes? % "chain-id") (:errors v)))))

  (testing "rejects negative epoch"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/epoch -1))]
      (is (not (:valid? v)))))

  (testing "rejects negative sequence"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/sequence -1))]
      (is (not (:valid? v)))))

  (testing "rejects nil chain-id"
    (let [v (auth-ckpt/validate-checkpoint
             (assoc ckpt0 :checkpoint/chain-id nil))]
      (is (not (:valid? v)))))

  (testing "checkpoint-root throws on invalid checkpoint"
    (is (thrown? ExceptionInfo
                 (auth-ckpt/checkpoint-root (dissoc ckpt0 :checkpoint/chain-id)))))

  (testing "2-arg checkpoint-root* rejects mismatch with valid checkpoint"
    (is (thrown-with-msg?
         ExceptionInfo
         #"does not match"
         (auth-ckpt/checkpoint-root*
          ckpt0
          "sha256:0000000000000000000000000000000000000000000000000000000000000000")))))

;; ── v2 disposition tests ─────────────────────────────────────────────────

(deftest test-authoritative-disposition-hash
  (testing "disposition hash is stable (deterministic)"
    (let [d {:attempt-disposition/schema disposition/authoritative-disposition-schema
             :attempt-disposition/action :prf.resubmission/admit
             :attempt-disposition/chain-id family
             :attempt-disposition/genesis-root genesis-root-with-key
             :attempt-disposition/configuration-root config-root-with-key
             :attempt-disposition/authority-epoch 0
             :attempt-disposition/parent-checkpoint-root ckpt0-root
             :attempt-disposition/attempt-receipt-hash sample-receipt-hash
             :attempt-disposition/previous-disposition-hash nil
             :attempt-disposition/status :pending-review}
          h1 (disposition/authoritative-disposition-hash d)
          h2 (disposition/authoritative-disposition-hash d)]
      (is (= h1 h2))))

  (testing "hash is a valid sha256 reference"
    (let [d {:attempt-disposition/schema disposition/authoritative-disposition-schema
             :attempt-disposition/action :prf.resubmission/admit
             :attempt-disposition/chain-id family
             :attempt-disposition/genesis-root genesis-root-with-key
             :attempt-disposition/configuration-root config-root-with-key
             :attempt-disposition/authority-epoch 0
             :attempt-disposition/parent-checkpoint-root ckpt0-root
             :attempt-disposition/attempt-receipt-hash sample-receipt-hash
             :attempt-disposition/previous-disposition-hash nil
             :attempt-disposition/status :pending-review}]
      (is (hash-ref/valid-sha256-ref?
           (disposition/authoritative-disposition-hash d)))))

  (testing "hash excludes signature"
    (let [d {:attempt-disposition/schema disposition/authoritative-disposition-schema
             :attempt-disposition/action :prf.resubmission/admit
             :attempt-disposition/chain-id family
             :attempt-disposition/genesis-root genesis-root-with-key
             :attempt-disposition/configuration-root config-root-with-key
             :attempt-disposition/authority-epoch 0
             :attempt-disposition/parent-checkpoint-root ckpt0-root
             :attempt-disposition/attempt-receipt-hash sample-receipt-hash
             :attempt-disposition/previous-disposition-hash nil
             :attempt-disposition/status :pending-review}
          h-without-sig (disposition/authoritative-disposition-hash d)
          h-with-sig (disposition/authoritative-disposition-hash
                      (assoc d :attempt-disposition/signature {:signature "fake"}))]
      (is (= h-without-sig h-with-sig))))

  (testing "hash includes all identity fields"
    (let [base {:attempt-disposition/schema disposition/authoritative-disposition-schema
                :attempt-disposition/action :prf.resubmission/admit
                :attempt-disposition/chain-id family
                :attempt-disposition/genesis-root genesis-root-with-key
                :attempt-disposition/configuration-root config-root-with-key
                :attempt-disposition/authority-epoch 0
                :attempt-disposition/parent-checkpoint-root ckpt0-root
                :attempt-disposition/attempt-receipt-hash sample-receipt-hash
                :attempt-disposition/previous-disposition-hash nil
                :attempt-disposition/status :pending-review}]
      (is (not= (disposition/authoritative-disposition-hash base)
                (disposition/authoritative-disposition-hash
                 (assoc base :attempt-disposition/status :final))))
      (is (not= (disposition/authoritative-disposition-hash base)
                (disposition/authoritative-disposition-hash
                 (assoc base :attempt-disposition/action :prf.resubmission/reject))))
      (is (not= (disposition/authoritative-disposition-hash base)
                (disposition/authoritative-disposition-hash
                 (assoc base :attempt-disposition/parent-checkpoint-root
                        "sha256:0000000000000000000000000000000000000000000000000000000000000099")))))))

(deftest test-authoritative-disposition-sign-and-verify
  (let [base-disposition
        {:attempt-disposition/schema disposition/authoritative-disposition-schema
         :attempt-disposition/action :prf.resubmission/admit
         :attempt-disposition/chain-id family
         :attempt-disposition/genesis-root genesis-root-with-key
         :attempt-disposition/configuration-root config-root-with-key
         :attempt-disposition/authority-epoch 0
         :attempt-disposition/parent-checkpoint-root ckpt0-root
         :attempt-disposition/attempt-receipt-hash sample-receipt-hash
         :attempt-disposition/previous-disposition-hash nil
         :attempt-disposition/status :pending-review}
        signed (disposition/sign-authoritative-disposition
                base-disposition authority-private-key authority-public-hex)]
    (testing "signed disposition carries signature and public key"
      (is (some? (:attempt-disposition/signature signed)))
      (is (= authority-public-hex
             (get-in signed [:attempt-disposition/signature :signature/public-key]))))

    (testing "verification succeeds with correct public key"
      (let [v (disposition/verify-authoritative-disposition signed authority-public-hex)]
        (is (:valid? v))
        (is (= :ok (:reason v)))))

    (testing "verification fails with mismatched public key"
      (let [alt-key (ed25519/keypair :alt)
            v (disposition/verify-authoritative-disposition signed (:public-hex alt-key))]
        (is (not (:valid? v)))
        (is (= :unauthorized-signer-key (:reason v)))))

    (testing "verification rejects wrong schema"
      (let [v (disposition/verify-authoritative-disposition
               (assoc signed :attempt-disposition/schema "wrong")
               authority-public-hex)]
        (is (not (:valid? v)))
        (is (= :invalid-disposition-schema (:reason v)))))

    (testing "verification rejects wrong action"
      (let [v (disposition/verify-authoritative-disposition
               (assoc signed :attempt-disposition/action :prf.resubmission/nonexistent)
               authority-public-hex)]
        (is (not (:valid? v)))
        (is (= :invalid-disposition-action (:reason v)))))

    (testing "verification rejects missing signature"
      (let [v (disposition/verify-authoritative-disposition
               (dissoc signed :attempt-disposition/signature)
               authority-public-hex)]
        (is (not (:valid? v)))
        (is (= :missing-disposition-signature (:reason v)))))

    (testing "verification rejects unauthorized signer key"
      (let [v (disposition/verify-authoritative-disposition
               (assoc-in signed [:attempt-disposition/signature :signature/public-key]
                         "00:11:22:33")
               authority-public-hex)]
        (is (not (:valid? v)))
        (is (= :unauthorized-signer-key (:reason v)))))

    (testing "verification rejects nil public key"
      (let [v (disposition/verify-authoritative-disposition signed nil)]
        (is (not (:valid? v)))
        (is (= :invalid-public-key (:reason v)))))

    (testing "hash is stable after signing (signature excluded)"
      (let [h-before (disposition/authoritative-disposition-hash base-disposition)
            h-after (disposition/authoritative-disposition-hash signed)]
        (is (= h-before h-after))))))

;; ── Transition dispatch tests ───────────────────────────────────────────

(deftest test-apply-authoritative-disposition-dispatch
  (testing "unknown action is rejected"
    (let [state (transition/empty-state family authority-public-hex)
          cmd {:transaction/action :prf.resubmission/unknown
               :transaction/input {}}]
      (let [result (transition/apply-action state cmd)]
        (is (= :rejected (:status result)))
        (is (= :unknown-action (:reason result))))))

  (testing "v2 action present in action vocabulary"
    (is (contains? transition/actions :prf.resubmission/apply-authoritative-disposition)))

  (testing "apply-authoritative-disposition rejects without authority context"
    (let [state (transition/empty-state family authority-public-hex)
          base-disposition
          {:attempt-disposition/schema disposition/authoritative-disposition-schema
           :attempt-disposition/action :prf.resubmission/admit
           :attempt-disposition/chain-id family
           :attempt-disposition/genesis-root genesis-root-with-key
           :attempt-disposition/configuration-root config-root-with-key
           :attempt-disposition/authority-epoch 0
           :attempt-disposition/parent-checkpoint-root ckpt0-root
           :attempt-disposition/attempt-receipt-hash sample-receipt-hash
           :attempt-disposition/previous-disposition-hash nil
           :attempt-disposition/status :pending-review}
          input {:disposition-artifact base-disposition
                 :expected-checkpoint-root ckpt0-root}
          result (transition/apply-action state
                                          {:transaction/action :prf.resubmission/apply-authoritative-disposition
                                           :transaction/input input})]
      (is (= :rejected (:status result)))
      (is (= :no-authority-context (:reason result))))))

;; ── Authoritative store tests ───────────────────────────────────────────

(deftest test-authoritative-store-construction
  (testing "store is created with initial checkpoint C0"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)]
      (is (auth-store/is-authoritative? s))
      (is (hash-ref/valid-sha256-ref? (auth-store/current-checkpoint-root s)))
      (is (= ckpt0-root (auth-store/current-checkpoint-root s)))
      (is (= family (auth-store/family-id-of s)))))

  (testing "store carries the admitted authority context"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)]
      (is (= authority-public-hex
             (:authority/public-key (auth-store/authority-context-of s))))))

  (testing "store carries the genesis artifact"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)]
      (is (= genesis-with-auth-key (auth-store/genesis-of s))))))

(deftest test-authoritative-store-cas-fence
  (testing "disposition with mismatched checkpoint-root returns contention"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)
          base-disposition
          {:attempt-disposition/schema disposition/authoritative-disposition-schema
           :attempt-disposition/action :prf.resubmission/admit
           :attempt-disposition/chain-id family
           :attempt-disposition/genesis-root genesis-root-with-key
           :attempt-disposition/configuration-root config-root-with-key
           :attempt-disposition/authority-epoch 0
           :attempt-disposition/parent-checkpoint-root ckpt0-root
           :attempt-disposition/attempt-receipt-hash sample-receipt-hash
           :attempt-disposition/previous-disposition-hash nil
           :attempt-disposition/status :pending-review}
          wrong-ckpt "sha256:0000000000000000000000000000000000000000000000000000000000000099"
          input {:disposition-artifact (assoc base-disposition
                                              :attempt-disposition/parent-checkpoint-root wrong-ckpt)
                 :expected-checkpoint-root wrong-ckpt}
          result (auth-store/apply-authoritative-disposition! s input)]
      (is (= :authoritative (:mode result)))
      (is (= :contention (:status result)))
      (is (= :checkpoint-root-mismatch (:reason result)))))

  (testing "missing checkpoint-root cannot downgrade authoritative admission"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)
          before-root (auth-store/current-checkpoint-root s)
          before-state (auth-store/state-of s)
          result (auth-store/apply-authoritative-disposition!
                  s {:disposition-artifact
                     {:attempt-disposition/schema disposition/authoritative-disposition-schema
                      :attempt-disposition/action :prf.resubmission/admit
                      :attempt-disposition/chain-id family
                      :attempt-disposition/genesis-root genesis-root-with-key
                      :attempt-disposition/configuration-root config-root-with-key
                      :attempt-disposition/authority-epoch 0
                      :attempt-disposition/attempt-receipt-hash sample-receipt-hash
                      :attempt-disposition/status :pending-review}})]
      (is (= :rejected (:status result)))
      (is (= :missing-checkpoint-root (:reason result)))
      (is (= before-root (auth-store/current-checkpoint-root s)))
      (is (= before-state (auth-store/state-of s))))))

(deftest test-authoritative-vs-local-replay-result-types
  (testing "apply-authoritative-disposition! returns :authoritative mode"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)
          base-disposition
          {:attempt-disposition/schema disposition/authoritative-disposition-schema
           :attempt-disposition/action :prf.resubmission/admit
           :attempt-disposition/chain-id family
           :attempt-disposition/genesis-root genesis-root-with-key
           :attempt-disposition/configuration-root config-root-with-key
           :attempt-disposition/authority-epoch 0
           :attempt-disposition/parent-checkpoint-root ckpt0-root
           :attempt-disposition/attempt-receipt-hash sample-receipt-hash
           :attempt-disposition/previous-disposition-hash nil
           :attempt-disposition/status :pending-review}
          signed (disposition/sign-authoritative-disposition
                  base-disposition authority-private-key authority-public-hex)
          input {:disposition-artifact signed
                 :expected-checkpoint-root ckpt0-root}]
      (let [result (auth-store/apply-authoritative-disposition! s input)]
        (is (= :authoritative (:mode result)))
        ;; No receipt in chain, so should be rejected (not committed)
        (is (= :rejected (:status result))))))

  (testing "apply-local-replay-disposition! returns :local-replay mode"
    (let [s (auth-store/new-test-authoritative-store genesis-with-auth-key
                                                     valid-authz
                                                     authority-context-ctx)
          v1-disposition
          {:attempt-disposition/schema disposition/disposition-schema
           :attempt-disposition/action :prf.resubmission/admit
           :attempt-disposition/attempt-receipt-hash sample-receipt-hash
           :attempt-disposition/previous-disposition-hash nil
           :attempt-disposition/status :pending-review}
          signed-v1 (disposition/sign-disposition
                     v1-disposition authority-private-key)
          input {:disposition-artifact signed-v1
                 :expected-disposition-head nil}
          result (auth-store/apply-local-replay-disposition! s input)]
      (is (= :local-replay (:mode result))))))
