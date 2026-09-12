(ns resolver-sim.benchmark.researcher-decision-v2-test
  "Tests for the researcher-decision.v2 complete-outcome committing contract
   (ADR-0007 D1 whole-outcome agreement, THREE_MEMBER_RESEARCHER_APPLICATION
   §2/§12). Uses real Ed25519 signatures so hash-level and signature-level
   corruption are distinguished."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as hc])
  (:import [org.bouncycastle.crypto.generators Ed25519KeyPairGenerator]
           [org.bouncycastle.crypto.params Ed25519KeyGenerationParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory SubjectPublicKeyInfoFactory]
           [java.security SecureRandom]
           [java.util Base64]))

;; ── Real Ed25519 key infrastructure ───────────────────────────────────────

(defn- write-key-files!
  "Generate an in-memory Ed25519 keypair, write PKCS8/X509 temp files, and
   return {:private-key-path ... :public-key-path ...}."
  [label]
  (let [encoder (Base64/getMimeEncoder)
        gen (Ed25519KeyPairGenerator.)
        _ (.init gen (Ed25519KeyGenerationParameters. (SecureRandom.)))
        pair (.generateKeyPair gen)
        priv-der (.getEncoded (PrivateKeyInfoFactory/createPrivateKeyInfo (.getPrivate pair)))
        pub-der (.getEncoded (SubjectPublicKeyInfoFactory/createSubjectPublicKeyInfo (.getPublic pair)))
        priv-file (java.io.File/createTempFile (str "dv2-" label "-priv") ".pem")
        pub-file (java.io.File/createTempFile (str "dv2-" label "-pub") ".pem")]
    (spit priv-file (str "-----BEGIN PRIVATE KEY-----\n"
                         (.encodeToString encoder priv-der)
                         "\n-----END PRIVATE KEY-----\n"))
    (spit pub-file (str "-----BEGIN PUBLIC KEY-----\n"
                        (.encodeToString encoder pub-der)
                        "\n-----END PUBLIC KEY-----\n"))
    (.setReadable priv-file true false)
    (.setWritable priv-file false false)
    (.setReadable pub-file true true)
    {:private-key-path (.getPath priv-file)
     :public-key-path (.getPath pub-file)
     :file-a priv-file
     :file-b pub-file}))

(def ^:private keys-a (write-key-files! "a"))
(def ^:private keys-b (write-key-files! "b"))

(defn- sign-v2
  "Build a v2 decision with the real signing path."
  [researcher-id auth-id request-root round-hash outcome-root decision
   & {:keys [dissent-reason]}]
  (rfa/build-signed-decision-v2
   researcher-id auth-id request-root round-hash outcome-root decision
   (:private-key-path (if (= researcher-id "researcher-a") keys-a keys-b))
   :dissent-reason dissent-reason))

;; ── Test data ─────────────────────────────────────────────────────────────

(def ^:private auth-id :authorisation/test-001)
(def ^:private request-root (str "sha256:" (apply str (take 64 (cycle "a1")))))
(def ^:private round-hash  (str "sha256:" (apply str (take 64 (cycle "b2")))))
(def ^:private outcome-a  (str "sha256:" (apply str (take 64 (cycle "c3")))))
(def ^:private outcome-b  (str "sha256:" (apply str (take 64 (cycle "d4")))))

(defn- v2-decision []
  (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a :approve))

(defn- target-with [proposed]
  {:target/kind :benchmark-branch
   :target/baseline-content-root (str "sha256:" (apply str (take 64 (cycle "00"))))
   :target/branch-descriptor-hash (str "sha256:" (apply str (take 64 (cycle "11"))))
   :target/proposed-content-root proposed})

(defn- v2-authorisation [& {:keys [refs target id]}]
  {:authorisation/id (or id auth-id)
   :authorisation/policy {:policy/id :x :policy/version 1
                          :policy/schema-version "force-authorisation-policy.v1"
                          :policy/hash (str "sha256:" (apply str (take 64 (cycle "99"))))}
   :authorisation/review-round {:review-round/id :review-round/test
                                :review-round/hash round-hash}
   :authorisation/request-root request-root
   :authorisation/target (or target (target-with outcome-a))
   :authorisation/decision-references (or refs [])})

;; ── Construction ──────────────────────────────────────────────────────────

(deftest v2-builds-commit-outcome-root
  (let [d (v2-decision)]
    (is (= "researcher-decision.v2" (:schema-version d)))
    (is (= outcome-a (:outcome/root d)))
    (is (= auth-id (:authorisation/id d)))
    (is (some? (:decision/hash d)))
    (is (some? (:signature d)))
    (is (nil? (:dissent/reason d)))))

(deftest v2-dissent-binds-reason
  (let [d (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a
                   :dissent :dissent-reason "derivation unsupported")]
    (is (= :dissent (:decision d)))
    (is (= "derivation unsupported" (:dissent/reason d)))
    (is (= outcome-a (:outcome/root d)))))

(deftest v2-rejects-missing-or-invalid-outcome-root
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outcome/root"
                        (sign-v2 "researcher-a" auth-id request-root round-hash
                                 "not-a-hash" :approve)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outcome/root"
                        (sign-v2 "researcher-a" auth-id request-root round-hash
                                 nil :approve))))

(deftest v2-rejects-missing-or-invalid-request-root
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"request-root"
                        (sign-v2 "researcher-a" auth-id "bogus" round-hash
                                 outcome-a :approve))))

(deftest v2-rejects-invalid-decision
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid decision"
                        (sign-v2 "researcher-a" auth-id request-root round-hash
                                 outcome-a :bogus))))

(deftest v2-rejects-dissent-without-reason
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dissent requires a reason"
                        (sign-v2 "researcher-a" auth-id request-root round-hash
                                 outcome-a :dissent))))

(deftest v2-uses-distinct-domain-separator
  (let [v2 (v2-decision)
        v1-preimage {:researcher/id "researcher-a"
                     :authorisation/id auth-id
                     :authorisation/request-root request-root
                     :review-round/hash round-hash
                     :decision :approve}
        v1-hash (str "sha256:" (hc/domain-hash :researcher-decision v1-preimage))]
    (is (not= v1-hash (:decision/hash v2))
        "v2 must not reuse the RESEARCHER_DECISION_V1 domain separator")))

;; ── Verification ──────────────────────────────────────────────────────────

(deftest v2-verifies
  (let [d (v2-decision)
        r (rfa/verify-signed-decision-v2 d (:public-key-path keys-a))]
    (is (:valid? r))
    (is (nil? (:reason r)))))

(deftest v2-verification-fails-wrong-key
  (let [d (v2-decision)
        r (rfa/verify-signed-decision-v2 d (:public-key-path keys-b))]
    (is (not (:valid? r)))))

(deftest v2-rejects-non-v2-schema
  (let [d (v2-decision)
        wrong (assoc d :schema-version "researcher-decision.v1")
        r (rfa/verify-signed-decision-v2 wrong (:public-key-path keys-a))]
    (is (not (:valid? r)))
    (is (re-find #"schema-version" (:reason r)))))

;; ── Corruption: tampered-without-rehash → hash mismatch ──────────────────

(deftest v2-corruption-outcome-root-changes-hash
  (testing "changing any material outcome field breaks verification"
    (let [d (v2-decision)]
      (doseq [[label ref]
              {"outcome/root swapped" (assoc d :outcome/root outcome-b)
               "decision flipped"     (assoc d :decision :dissent
                                             :dissent/reason "tampered")
               "review-round/hash"    (assoc d :review-round/hash
                                             (str "sha256:" (apply str (take 64 (cycle "e5")))))
               "request-root"         (assoc d :authorisation/request-root
                                             (str "sha256:" (apply str (take 64 (cycle "f6")))))
               "authorisation/id"     (assoc d :authorisation/id :authorisation/other)
               "researcher/id"        (assoc d :researcher/id "researcher-b")}]
        (let [r (rfa/verify-signed-decision-v2 ref (:public-key-path keys-a))]
          (is (not (:valid? r)) (str label " must fail verification"))
          (is (= "decision/hash mismatch" (:reason r)) label))))))

;; ── Corruption: modified+rehashed → signature mismatch ───────────────────

(defn- rehash-v2
  "Recompute the decision/hash over the current fields of a v2 ref, simulating
   an attacker who rehashes a tampered artifact without re-signing. Uses the
   public RESEARCHER_DECISION_V2 domain hash."
  [ref]
  (let [preimage (cond-> {:researcher/id (:researcher/id ref)
                          :authorisation/id (:authorisation/id ref)
                          :authorisation/request-root (:authorisation/request-root ref)
                          :review-round/hash (:review-round/hash ref)
                          :outcome/root (:outcome/root ref)
                          :decision (:decision ref)}
                   (= :dissent (:decision ref)) (assoc :dissent/reason (:dissent/reason ref)))]
    (assoc ref :decision/hash
           (str "sha256:" (hc/domain-hash :researcher-decision-v2 preimage)))))

(deftest v2-rehashed-without-resigning-fails-signature
  (testing "modified + rehashed artifact fails signature verification"
    (let [d (v2-decision)
          tampered (rehash-v2 (assoc d :outcome/root outcome-b))
          r (rfa/verify-signed-decision-v2 tampered (:public-key-path keys-a))]
      (is (not (:valid? r)))
      (is (= "signature does not verify" (:reason r))))))

(deftest v2-rehashed-and-resigned-is-a-new-valid-position
  (testing "modified + rehashed + re-signed by a valid member is a new position"
    (let [d (v2-decision)
          original-hash (:decision/hash d)
          fresh (sign-v2 "researcher-a" auth-id request-root round-hash
                         outcome-b :approve)]
      (is (not= original-hash (:decision/hash fresh)))
      (is (:valid? (rfa/verify-signed-decision-v2 fresh (:public-key-path keys-a)))))))

;; ── Version and outcome-binding classification ────────────────────────────

(def ^:private v1-style-ref
  {:researcher/id "researcher-a"
   :authorisation/request-root request-root
   :review-round/hash round-hash
   :decision :approve
   :decision/hash (str "sha256:" (apply str (take 64 (cycle "77"))))
   :signature {:value "x"}})

(deftest classify-decision-version-test
  (is (= :v2-complete-outcome (rfa/classify-decision-version (v2-decision))))
  (is (= :v1-legacy (rfa/classify-decision-version v1-style-ref)))
  (is (= :v1-legacy (rfa/classify-decision-version
                     (assoc v1-style-ref :schema-version "researcher-decision.v1"))))
  (is (= :unknown (rfa/classify-decision-version
                   (assoc v1-style-ref :schema-version "researcher-decision.v9"))))
  (is (= :unknown (rfa/classify-decision-version nil))))

(deftest decision-outcome-binding-test
  (is (= :outcome-committed (rfa/decision-outcome-binding (v2-decision))))
  (is (= :outcome-unavailable (rfa/decision-outcome-binding v1-style-ref)))
  (is (= :invalid (rfa/decision-outcome-binding
                   (assoc (v2-decision) :outcome/root "bogus"))))
  (is (= :invalid (rfa/decision-outcome-binding nil)))
  (is (true? (rfa/complete-outcome-verified? (v2-decision))))
  (is (false? (rfa/complete-outcome-verified? v1-style-ref))))

(deftest position-outcome-root-test
  (is (= outcome-a (rfa/position-outcome-root (v2-decision))))
  (is (nil? (rfa/position-outcome-root v1-style-ref))))

;; ── Cross-version compatibility ───────────────────────────────────────────

(deftest v1-legacy-still-verifies-through-v1-path
  (testing "v1 artifacts remain verifiable through the legacy v1 verifier"
    (let [;; Build an equivalent v1 decision with the same fields.
          preimage {:researcher/id "researcher-a"
                    :authorisation/id auth-id
                    :authorisation/request-root request-root
                    :review-round/hash round-hash
                    :decision :approve}
          v1-hash (str "sha256:" (hc/domain-hash :researcher-decision preimage))
          sig (signing/sign-hash (subs v1-hash (count "sha256:"))
                                 (:private-key-path keys-a) nil)
          v1-ref {:researcher/id "researcher-a"
                  :authorisation/request-root request-root
                  :review-round/hash round-hash
                  :decision :approve
                  :decision/hash v1-hash
                  :signature {:algorithm :ed25519 :value sig}}]
      (is (:valid? (rfa/verify-signed-decision v1-ref auth-id
                                               (:public-key-path keys-a))))
      (is (= :v1-legacy (rfa/classify-decision-version v1-ref)))
      (is (= :outcome-unavailable (rfa/decision-outcome-binding v1-ref)))
      (is (not (rfa/complete-outcome-verified? v1-ref))
          "legacy v1 must never be classified as complete-outcome verified"))))

(deftest v2-not-accepted-by-v1-verifier
  (testing "a v2 artifact passed to the v1 verifier fails (preimage differs)"
    (let [d (v2-decision)]
      (is (not (:valid? (rfa/verify-signed-decision d auth-id
                                                    (:public-key-path keys-a))))))))

;; ── Replay / substitution across authorisations ──────────────────────────

(deftest v2-replay-into-different-authorisation-rejected
  (testing "authorisation-outcome-consistency rejects a ref substituted from another authorisation"
    (let [d (sign-v2 "researcher-a" :authorisation/other request-root round-hash
                     outcome-a :approve)
          auth (v2-authorisation :refs [d])]
      (is (not (:consistent? (rfa/authorisation-outcome-consistency auth))))
      (is (some #(re-find #"different authorisation/id" %)
                (:errors (rfa/authorisation-outcome-consistency auth)))))))

;; ── authorisation-outcome-consistency ─────────────────────────────────────

(deftest consistency-committed-single-outcome
  (let [d1 (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a :approve)
        d2 (sign-v2 "researcher-b" auth-id request-root round-hash outcome-a :approve)
        auth (v2-authorisation :refs [d1 d2])
        r (rfa/authorisation-outcome-consistency auth)]
    (is (:consistent? r))
    (is (= outcome-a (:outcome/root r)))
    (is (= :outcome-committed (:binding r)))
    (is (empty? (:errors r)))))

(deftest consistency-distinct-outcome-roots-rejected
  (let [d1 (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a :approve)
        d2 (sign-v2 "researcher-b" auth-id request-root round-hash outcome-b :approve)
        auth (v2-authorisation :refs [d1 d2])
        r (rfa/authorisation-outcome-consistency auth)]
    (is (not (:consistent? r)))
    (is (some #(re-find #"distinct outcome roots" %) (:errors r)))))

(deftest consistency-outcome-root-vs-target-mismatch
  (let [d1 (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a :approve)
        auth (v2-authorisation :refs [d1] :target (target-with outcome-b))
        r (rfa/authorisation-outcome-consistency auth)]
    (is (not (:consistent? r)))
    (is (some #(re-find #"does not match target" %) (:errors r)))))

(deftest consistency-v1-honestly-unavailable
  (let [auth (v2-authorisation :refs [v1-style-ref])
        r (rfa/authorisation-outcome-consistency auth)]
    (is (:consistent? r) "a coherent pure-v1 set is internally consistent")
    (is (= :outcome-unavailable (:binding r))
        "v1 is honest about NOT committing the complete outcome")
    (is (nil? (:outcome/root r)))))

(deftest consistency-mixed-v1-v2
  (let [d (sign-v2 "researcher-a" auth-id request-root round-hash outcome-a :approve)
        auth (v2-authorisation :refs [d v1-style-ref])
        r (rfa/authorisation-outcome-consistency auth)]
    (is (= :mixed (:binding r)))))

(deftest consistency-empty-refs-invalid
  (let [r (rfa/authorisation-outcome-consistency (v2-authorisation :refs []))]
    (is (= :invalid (:binding r)))
    (is (not (:consistent? r)))))
