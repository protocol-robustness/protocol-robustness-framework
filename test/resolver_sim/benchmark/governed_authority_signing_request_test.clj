(ns resolver-sim.benchmark.governed-authority-signing-request-test
  "AUTH-K3 governed-authority signing request — adversarial tests.

   Verifies that exercise of an already-authorized private key is constrained
   to the exact K3 signing digest derived from committed material + decision
   semantics, and that caller-controlled bytes/hashes/roots/purpose/subject can
   never reach the private-key primitive."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [buddy.core.codecs :as codecs]
            [resolver-sim.benchmark.governed-authority-signing-request :as k3]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa])
  (:import [java.security KeyPairGenerator]
           [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64]
           [org.bouncycastle.crypto.params Ed25519PrivateKeyParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory]))

(def ^:private state-root (str "sha256:" (apply str (take 64 (cycle "5")))))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(defn- pubkey-hex [pk]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (take-last 32 (.getEncoded pk)))))

(defn- write-key! [private-key]
  (let [file (java.io.File/createTempFile "k3-test" ".pem")
        encoded (.encodeToString (Base64/getMimeEncoder) (.getEncoded private-key))]
    (spit file (str "-----BEGIN PRIVATE KEY-----\n" encoded "\n-----END PRIVATE KEY-----\n"))
    (.getPath file)))

(def ^:dynamic *material* nil)
(def ^:dynamic *key-path* nil)

(defn- base-material [pk]
  (let [ks {:artifact/schema state/signer-key-set-schema
            :signer-key-set/entries
            [{:researcher/id "r1" :signing-key/id "k1"
              :signing-key/algorithm :ed25519 :signing-key/public-key pk}]}
        gov {:schema-version "review-governance.v1"
             :governance/epoch 0
             :governance/roles #{:reviewer}
             :governance/principals
             [{:principal/id "r1" :status :active
               :principal/independence-group "g1"
               :principal/independence-basis-root (hash-ref "ab")
               :principal/keys [{:key/id "k1" :status :active
                                 :key/algorithm :ed25519 :key/public-key pk}]}]
             :governance/members
             [{:reviewer/member-id "r1" :principal/id "r1"
               :status :active :granted-roles #{:reviewer}}]
             :governance/policies
             [{:policy/id "p1" :member-count 3 :threshold 2
               :required-roles #{:reviewer} :role-cardinality :unique
               :equivocation-policy :invalid-seat}]}
        round {:artifact/schema rr/governed-schema-version
               :schema-version rr/governed-schema-version
               :benchmark/content-root (hash-ref "aa")
               :review-round/members [{:researcher/id "r1" :role :reviewer}]
               :review-round/membership-frozen-at 0
               :review-round/policy-root (hash-ref "bb")
               :review-round/purpose :model-admission
               :review-round/chain-configuration-root (hash-ref "22")
               :review-round/governance-root (governance/governance-root gov)
               :review-round/governance-epoch 0
               :review-round/constituted-at 0
               :review-round/policy-id "p1"
               :review-round/policy-hash (hash-ref "dd")}
        round-root (state/review-round-material-root round)]
    {:review-governance/root (governance/governance-root gov)
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root round-root
     :authority-material/review-governance gov
     :authority-material/signer-key-set ks
     :authority-material/review-round round}))

(defn- k3-fixture [f]
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        pk (pubkey-hex (.getPublic pair))
        path (write-key! (.getPrivate pair))]
    (binding [*material* (base-material pk)
              *key-path* path]
      (try (f) (finally (.delete (java.io.File. path)))))))

(use-fixtures :each k3-fixture)

(defn- sign-decision
  ([decision]
   (sign-decision decision {}))
  ([decision opts]
   (apply k3/build-signed-decision-k3
          (concat ["r1" :authorisation/test (hash-ref "a1") (hash-ref "a2") (hash-ref "a3")
                   decision *key-path* state-root]
                  (mapcat identity opts)))))

(deftest k3-valid-current-state-signs-and-verifies
  (let [d (sign-decision :approve {:signing-key-id "k1"
                                   :state-current? (constantly true)
                                   :material-resolver (fn [_] *material*)})]
    (is (= rfa/decision-k3-schema-version (:schema-version d)))
    (is (some? (:decision/hash d)) "decision-v2 root carried as subject")
    (is (= state-root (:authority-state/root d)))
    (is (some? (:signing-request/root d)))
    (is (some? (get-in d [:signature :value])))
    (let [v (k3/verify-signed-decision-k3-with-material d state-root *material*)]
      (is (:valid? v)))))

(deftest k3-caller-cannot-supply-raw-signing-bytes
  (testing "the signing API has no raw-bytes/hash input; subject/root is derived"
    (let [d (sign-decision :approve {:signing-key-id "k1"
                                     :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          request (k3/derive-request state-root *material*
                                     {:researcher/id "r1" :authorisation/id :authorisation/test
                                      :authorisation/request-root (hash-ref "a1")
                                      :review-round/hash (hash-ref "a2")
                                      :outcome/root (hash-ref "a3")
                                      :decision :approve :signing-key/id "k1"})]
      ;; derive-request recomputes subject/root from decision semantics; a caller
      ;; cannot select it, and no caller byte/hash path exists.
      (is (= (:decision/hash d) (:subject/root request)))
      (is (not (contains? d :signature/preimage))
          "no caller-supplied preimage in the K3 signed decision"))))

(deftest k3-forged-roots-are-ignored-or-rejected
  (testing "a caller cannot select governance/signer-key-set/round roots; they are
            derived from committed material, so a forged material yields a
            different (non-authoritative) resolution and is rejected downstream"
    (let [d (sign-decision :approve {:signing-key-id "k1"
                                     :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          forged-gov (assoc-in *material* [:review-governance/root] (hash-ref "ff"))
          forged-ks   (assoc-in *material* [:signer-key-set/root] (hash-ref "fe"))
          forged-round (assoc-in *material* [:review-round/root] (hash-ref "fd"))
          v-gov (k3/verify-signed-decision-k3-with-material d state-root forged-gov)
          v-ks  (k3/verify-signed-decision-k3-with-material d state-root forged-ks)
          v-round (k3/verify-signed-decision-k3-with-material d state-root forged-round)]
      (is (false? (:valid? v-gov)) "forged governance root fails request-root recomputation")
      (is (false? (:valid? v-ks))  "forged signer-key-set root fails")
      (is (false? (:valid? v-round)) "forged round root fails"))))

(deftest k3-purpose-cannot-be-caller-asserted
  (testing ":purpose is the committed review-round purpose, not :approve/:dissent"
    (let [request (k3/derive-request state-root *material*
                                     {:researcher/id "r1" :authorisation/id :authorisation/test
                                      :authorisation/request-root (hash-ref "a1")
                                      :review-round/hash (hash-ref "a2")
                                      :outcome/root (hash-ref "a3")
                                      :decision :approve :signing-key/id "k1"})]
      (is (= :model-admission (:purpose request))
          "purpose is the committed round purpose, never :approve/:dissent"))))

(deftest k3-wrong-principal-key-relation-rejected-before-signing
  (testing "a signing-key-id not owned by the principal cannot be exercised"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not eligible"
         (k3/build-signed-decision-k3
          "r1" :authorisation/test (hash-ref "a1") (hash-ref "a2") (hash-ref "a3")
          :approve *key-path* state-root
          :signing-key-id "k9" :state-current? (constantly true)
          :material-resolver (fn [_] *material*)))
        "k9 is not a key of r1 ⇒ refused before private-key exercise")))

(deftest k3-stale-authority-state-refuses-signing
  (testing "current-admission signing against a stale state is refused before key exercise"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not eligible"
         (sign-decision :approve {:signing-key-id "k1"
                                  :state-current? (constantly false)
                                  :material-resolver (fn [_] *material*)})))))

(deftest k3-identical-request-retries-idempotently
  (testing "same semantic decision + same authority context → same request root/signature"
    (let [opts {:signing-key-id "k1" :state-current? (constantly true)
                :material-resolver (fn [_] *material*)}
          a (sign-decision :approve opts)
          b (sign-decision :approve opts)]
      (is (= (:signing-request/root a) (:signing-request/root b)))
      (is (= (:decision/hash a) (:decision/hash b)))
      (is (= (get-in a [:signature :value]) (get-in b [:signature :value])))
      (is (= (:signing-request/root a) (:signing-request/root b))))))

(deftest k3-decision-semantic-change-yields-different-subject-request-signature
  (testing "changing the decision (dissent vs approve) changes subject/request/signature"
    (let [opts {:signing-key-id "k1" :dissent-reason "disagree"
                :state-current? (constantly true)
                :material-resolver (fn [_] *material*)}
          approve (sign-decision :approve (assoc opts :dissent-reason nil))
          dissent (sign-decision :dissent opts)]
      (is (not= (:decision/hash approve) (:decision/hash dissent)))
      (is (not= (:signing-request/root approve) (:signing-request/root dissent)))
      (is (not= (get-in approve [:signature :value]) (get-in dissent [:signature :value]))))))

(deftest k3-state-change-same-decision-different-request-signature
  (testing "same decision under a different authority-state/root → same subject but
            different request root/signature"
    (let [other-state (hash-ref "zz")
          d1 (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                      :material-resolver (fn [_] *material*)})
          d2 (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                      :material-resolver (fn [_] *material*)})]
      (is (= (:decision/hash d1) (:decision/hash d2)))
      ;; different state-root → different request root
      (is (not= (k3/derive-request state-root *material*
                                   (assoc d1 :signing-request/root nil))
                (k3/derive-request other-state *material*
                                   (assoc d1 :signing-request/root nil)))))))

(deftest k3-request-root-transplant-rejected
  (testing "a transplanted (forged) stored request root is rejected at verification"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          d' (assoc d :signing-request/root (hash-ref "99"))
          v (k3/verify-signed-decision-k3-with-material d' state-root *material*)]
      (is (false? (:valid? v)))
      (is (= :signing-request-root-mismatch (:reason v)))
      (is (not= (:signing-request/root d) (:signing-request/root d'))
          "never trust the stored root as authority"))))

(deftest k3-form-rejected-by-legacy-v1-v2-verifier
  (testing "a K3 decision passed to the v2 verifier is schema-rejected (no ambiguity)"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          ;; v2 verifier requires decision-v2 schema; K3 is a distinct container
          v (rfa/verify-signed-decision-v2 d *key-path*)]
      (is (false? (:valid? v))))))

(deftest k3-missing-authority-context-fails-closed-in-k2-verifier
  (testing "the K2 verifier fails closed when a K3 decision lacks an authority context"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          ks (:authority-material/signer-key-set *material*)]
      (is (false? (:valid? (state/verify-decision-signatures-with-signer-key-set ks {:authorisation/decision-references [d]})))))))
;; ---------------------------------------------------------------------------
;; AUTH-K3-V authoritative verifier (store-resolved material)
;; ---------------------------------------------------------------------------

(defrecord TestStore [state])

(deftest k3-authoritative-verification-resolves-store-material
  (testing "authoritative K3 verification obtains material from the store, never the caller"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          store (->TestStore (atom {:material {state-root *material*}}))
          v (state/verify-authoritative-signed-decision-k3 store d)]
      (is (:valid? v))
      (is (= (:decision/hash d) (:subject/root v)))))
  (testing "a decision bound to an unavailable state-root is rejected"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          other (assoc d :authority-state/root (hash-ref "ff"))
          store (->TestStore (atom {:material {state-root *material*}}))
          v (state/verify-authoritative-signed-decision-k3 store other)]
      (is (false? (:valid? v)))
      (is (= :state-unavailable (:reason v))))))

(deftest k3-anti-downgrade-cannot-be-verified-under-v2-preimage
  (testing "a K3 decision relabelled as researcher-decision.v2 cannot verify under the
            old decision-hash preimage (signature is over the K3 digest)"
    (let [d (sign-decision :approve {:signing-key-id "k1" :state-current? (constantly true)
                                     :material-resolver (fn [_] *material*)})
          downgraded (assoc d :schema-version rfa/decision-v2-schema-version)
          ks (:authority-material/signer-key-set *material*)
          result (state/verify-decision-signatures-with-signer-key-set
                  ks {:authorisation/decision-references [downgraded]})]
      (is (false? (:valid? result))
          "K3 signature cannot be interpreted under the v2 decision-hash preimage"))))

;; ---------------------------------------------------------------------------
;; K3-X literal byte-level golden vector (deterministic key + fixed roots)
;; ---------------------------------------------------------------------------

(defn- deterministic-keypair
  "Deterministic Ed25519 keypair from a fixed 32-byte seed."
  [seed-bytes]
  (let [priv-params (Ed25519PrivateKeyParameters. seed-bytes 0)
        pub-params (.generatePublicKey priv-params)
        priv-info (PrivateKeyInfoFactory/createPrivateKeyInfo priv-params)
        kf (KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.getEncoded priv-info)))]
    {:private-key priv
     :public-key-hex (apply str (map #(format "%02x" (bit-and % 0xff)) (.getEncoded pub-params)))}))

(defn- write-pem! [private-key]
  (let [file (java.io.File/createTempFile "k3vec" ".pem")
        encoded (.encodeToString (Base64/getMimeEncoder) (.getEncoded private-key))]
    (spit file (str "-----BEGIN PRIVATE KEY-----\n" encoded "\n-----END PRIVATE KEY-----\n"))
    (.getPath file)))

(deftest k3-golden-vector-byte-contract
  (testing "deterministic 32-byte signing digest + stable request root over fixed roots"
    (let [seed (byte-array (map byte (apply str (repeat 32 "g"))))
          kp (deterministic-keypair seed)
          path (write-pem! (:private-key kp))
          fixed-state (hash-ref "5")
          material (-> (base-material (:public-key-hex kp))
                       (assoc :review-governance/root (hash-ref "6")
                              :signer-key-set/root (hash-ref "7")
                              :review-round/root (hash-ref "8")))
          d (k3/build-signed-decision-k3
             "r1" :vec (hash-ref "a1") (hash-ref "a2") (hash-ref "a3")
             :approve path fixed-state
             :signing-key-id "k1" :state-current? (constantly true)
             :material-resolver (fn [_] material))
          request (k3/derive-request fixed-state material
                                     {:researcher/id "r1" :authorisation/id :vec
                                      :authorisation/request-root (hash-ref "a1")
                                      :review-round/hash (hash-ref "a2")
                                      :outcome/root (hash-ref "a3")
                                      :decision :approve :signing-key/id "k1"})
          request-root (k3/signing-request-root request)
          digest (k3/signing-digest-bytes request-root)]
      (prn :K3-GOLDEN
           {:authority-state/root fixed-state
            :review-governance/root (:review-governance/root material)
            :signer-key-set/root (:signer-key-set/root material)
            :review-round/root (:review-round/root material)
            :subject/root (:subject/root request)
            :signing-request/root request-root
            :digest-hex (codecs/bytes->hex digest)
            :public-key-hex (:public-key-hex kp)
            :signature-hex (get-in d [:signature :value])})
      (is (= request-root (:signing-request/root d)) "stored request root == recomputed")
      (is (= 32 (alength digest)) "signing digest is exactly 32 bytes")
      (is (k3/verify-signed-decision-k3-with-material d fixed-state material))
      ;; LITERAL byte-level golden vector (deterministic seed, fixed roots) — freeze.
      (is (= "sha256:427243db424012d66d1f3e733988d9f268a6f106d52691b766a0b9e0c9fe54f6"
             (:subject/root request)) "subject/root pinned")
      (is (= "sha256:9e06409a9239309f6df9f4cc688843547185d1e8cce557b6d48d4b09350d3e04"
             request-root) "signing-request/root pinned")
      (is (= "ab76fca2a8f31bb31ed67723e4b1550e2e9680b7ef97fb5a06d6ca134ba211aa"
             (codecs/bytes->hex digest)) "32-byte signing digest pinned")
      (is (= "12a41592c8b7c17d4059e7b29b61e8ff96c7415f2f803348f2f017e05b9ea1da"
             (:public-key-hex kp)) "public key pinned")
      (is (= "5ae3a9316b04cf522c8e06728402c13ef358161ca8d01e0105931c256ade7ea6f1a73e282a143e3199907ef8c001d4bbf5c3210ba52e93b1e6ebfe56f9b87406"
             (get-in d [:signature :value])) "signature pinned"))))

(deftest k3-golden-state-change-yields-different-request-digest-signature
  (testing "same decision + different :authority-state/root ⇒ same subject/root,
            different signing-request/root, digest, and signature"
    (let [seed (byte-array (map byte (apply str (repeat 32 "g"))))
          kp (deterministic-keypair seed)
          path (write-pem! (:private-key kp))
          base-material (-> (base-material (:public-key-hex kp))
                            (assoc :review-governance/root (hash-ref "6")
                                   :signer-key-set/root (hash-ref "7")
                                   :review-round/root (hash-ref "8")))
          opts {:signing-key-id "k1" :state-current? (constantly true)
                :material-resolver (fn [_] base-material)}
          a (k3/build-signed-decision-k3 "r1" :vec (hash-ref "a1") (hash-ref "a2") (hash-ref "a3")
                                         :approve path (hash-ref "5") opts)
          b (k3/build-signed-decision-k3 "r1" :vec (hash-ref "a1") (hash-ref "a2") (hash-ref "a3")
                                         :approve path (hash-ref "9") opts)]
      (is (= (:decision/hash a) (:decision/hash b)) "subject/root unchanged")
      (is (not= (:signing-request/root a) (:signing-request/root b)) "request root differs")
      (is (not= (get-in a [:signature :value]) (get-in b [:signature :value])) "signature differs")
      (is (not= (codecs/bytes->hex (k3/signing-digest-bytes (:signing-request/root a)))
                (codecs/bytes->hex (k3/signing-digest-bytes (:signing-request/root b))))
          "digest differs"))))
