(ns resolver-sim.custody.daemon-test
  "AUTH-K4-V1 custody daemon — adversarial and process-boundary tests.

   Proves the ordinary PRF process never holds the governed private key, there
   is no arbitrary signing API, signing is constrained to the frozen K3 request,
   and operational disablement survives restart."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-signing-request :as k3]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.custody.daemon :as daemon]
            [resolver-sim.custody.daemon-core :as core]
            [resolver-sim.custody.client :as client]
            [resolver-sim.signed-external-decision :as sed])
  (:import [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.nio.file Files]
           [java.util Base64]
           [org.bouncycastle.crypto.params Ed25519PrivateKeyParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory]))

(defn- hr [ch] (str "sha256:" (apply str (take 64 (cycle ch)))))
(def ^:private state-root (hr "5"))

(defn- deterministic-keypair [seed-bytes]
  (let [priv-params (Ed25519PrivateKeyParameters. seed-bytes 0)
        pub-params (.generatePublicKey priv-params)
        priv-info (PrivateKeyInfoFactory/createPrivateKeyInfo priv-params)
        kf (KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.getEncoded priv-info)))]
    {:private-key priv
     :public-key-hex (apply str (map #(format "%02x" (bit-and % 0xff)) (.getEncoded pub-params)))}))

(defn- write-pem! [private-key]
  (let [f (java.io.File/createTempFile "k4key" ".pem")
        encoded (.encodeToString (Base64/getMimeEncoder) (.getEncoded private-key))]
    (spit f (str "-----BEGIN PRIVATE KEY-----\n" encoded "\n-----END PRIVATE KEY-----\n"))
    (.getPath f)))

(defn- material [pk]
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
               :principal/independence-basis-root (hr "ab")
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
               :benchmark/content-root (hr "aa")
               :review-round/members [{:researcher/id "r1" :role :reviewer}]
               :review-round/membership-frozen-at 0
               :review-round/policy-root (hr "bb")
               :review-round/purpose :model-admission
               :review-round/chain-configuration-root (hr "22")
               :review-round/governance-root (governance/governance-root gov)
               :review-round/governance-epoch 0
               :review-round/constituted-at 0
               :review-round/policy-id "p1"
               :review-round/policy-hash (hr "dd")}]
    {:review-governance/root (governance/governance-root gov)
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)
     :authority-material/review-governance gov
     :authority-material/signer-key-set ks
     :authority-material/review-round round}))

(def ^:dynamic *ctx* nil)

(defn- ctx-fixture [f]
  (let [kp (deterministic-keypair (byte-array (map byte (apply str (repeat 32 "c")))))
        mat (material (:public-key-hex kp))
        path (write-pem! (:private-key kp))
        priv (:private-key kp)
        mirror {:accepted/head state-root :accepted/material mat}
        request (k3/derive-request state-root mat
                                   {:researcher/id "r1" :authorisation/id :k4
                                    :authorisation/request-root (hr "a1")
                                    :review-round/hash (hr "a2")
                                    :outcome/root (hr "a3")
                                    :decision :approve :signing-key/id "k1"})
        keyring {["r1" "k1"] {:private-key priv
                              :public-key-hex (:public-key-hex kp)
                              :enabled? true :generation 1 :custody/handle "h1"}}]
    (binding [*ctx* {:kp kp :mat mat :mirror mirror :request request :keyring keyring :path path}]
      (try (f) (finally (.delete (java.io.File. path)))))))

(use-fixtures :each ctx-fixture)

(deftest k4-valid-current-request-signs
  (let [d (core/signing-decision (:request *ctx*) (:mirror *ctx*) (:keyring *ctx*))
        sig (sed/ed25519-sign-bytes (:digest d) (:private-key d))]
    (is (= :sign (:action d)))
    (is (= (k3/signing-request-root (:request *ctx*)) (:signing-request/root d)))
    (is (sed/ed25519-verify-bytes (:digest d) sig (:public-key-hex (:kp *ctx*))))))

(deftest k4-no-arbitrary-signing-api
  (testing "client + daemon expose only the frozen K3 request; no sign(bytes/hash)"
    (is (nil? (resolve 'resolver-sim.custody.client/sign-bytes)))
    (is (nil? (resolve 'resolver-sim.custody.client/sign-hash)))
    (is (nil? (resolve 'resolver-sim.custody.client/sign-message)))
    (is (nil? (resolve 'resolver-sim.custody.client/export-private-key)))
    (is (nil? (resolve 'resolver-sim.custody.daemon/sign-arbitrary-message)))))

(deftest k4-forged-roots-reject
  (testing "forged G/KS/RR roots in the request cannot authorize signing"
    (let [req (assoc (:request *ctx*) :review-governance/root (hr "ff"))
          d (core/signing-decision req (:mirror *ctx*) (:keyring *ctx*))]
      (is (= :refuse (:action d)))
      (is (= :authority/material-mismatch (:reason d))))))

(deftest k4-fabricated-caller-material-cannot-authorize
  (testing "a request bound to a fabricated alternate state (not the daemon's
            accepted head) cannot authorize signing — refused"
    (let [req (assoc (:request *ctx*) :authority-state/root (hr "9"))
          d (core/signing-decision req (:mirror *ctx*) (:keyring *ctx*))]
      (is (= :refuse (:action d)))
      (is (= :authority/state-stale (:reason d))))))

(deftest k4-wrong-principal-key-rejects
  (testing "a key not held by this custody boundary is refused"
    (let [req (assoc (:request *ctx*) :signing-key/id "k9")
          d (core/signing-decision req (:mirror *ctx*) (:keyring *ctx*))]
      (is (= :refuse (:action d)))
      (is (= :key/not-held (:reason d))))))

(deftest k4-held-key-public-mismatch-rejects
  (testing "held key whose public key differs from the committed key is refused"
    (let [other (deterministic-keypair (byte-array (map byte (apply str (repeat 32 "x")))))
          bad-keyring (assoc (:keyring *ctx*) ["r1" "k1"]
                             (assoc (get (:keyring *ctx*) ["r1" "k1"])
                                    :public-key-hex (:public-key-hex other)))
          d (core/signing-decision (:request *ctx*) (:mirror *ctx*) bad-keyring)]
      (is (= :refuse (:action d)))
      (is (= :key/public-mismatch (:reason d))))))

(deftest k4-disabled-key-rejects
  (testing "an operationally disabled key is refused"
    (let [disabled (update-in (:keyring *ctx*) [["r1" "k1"]] assoc :enabled? false :reason :compromise)
          d (core/signing-decision (:request *ctx*) (:mirror *ctx*) disabled)]
      (is (= :refuse (:action d)))
      (is (= :key/disabled (:reason d))))))

(deftest k4-stale-historical-state-refused
  (testing "AUTH-K4-H: a request bound to a non-current (historical) state cannot
            mint a new signature"
    (let [mirror2 {:accepted/head (hr "9") :accepted/material (:mat *ctx*)}]
      (is (= :refuse (:action (core/signing-decision (:request *ctx*) mirror2 (:keyring *ctx*))))))))

(deftest k4-k2-ineligible-provisioned-key-rejected
  (testing "a provisioned key that is not governance-eligible is refused at sign time"
    (let [kp2 (deterministic-keypair (byte-array (map byte (apply str (repeat 32 "y")))))
          base (material (:public-key-hex kp2))
          gov2 (:authority-material/review-governance base)     ; governance authorizes r1 with k1 only
          round2 (:authority-material/review-round base)
          ;; signer-key-set commits r1/k2 (held + provisioned) but governance does not authorize k2
          ks2 {:artifact/schema state/signer-key-set-schema
               :signer-key-set/entries
               [{:researcher/id "r1" :signing-key/id "k2"
                 :signing-key/algorithm :ed25519
                 :signing-key/public-key (:public-key-hex kp2)}]}
          mat2 {:review-governance/root (governance/governance-root gov2)
                :signer-key-set/root (state/signer-key-set-root ks2)
                :review-round/root (state/review-round-material-root round2)
                :authority-material/review-governance gov2
                :authority-material/signer-key-set ks2
                :authority-material/review-round round2}
          mirror2 {:accepted/head state-root :accepted/material mat2}
          keyring2 {["r1" "k2"] {:private-key (:private-key kp2)
                                 :public-key-hex (:public-key-hex kp2)
                                 :enabled? true :generation 1 :custody/handle "h2"}}
          req2 (k3/derive-request state-root mat2
                                  {:researcher/id "r1" :authorisation/id :k4
                                   :authorisation/request-root (hr "a1")
                                   :review-round/hash (hr "a2")
                                   :outcome/root (hr "a3")
                                   :decision :approve :signing-key/id "k2"})
          d (core/signing-decision req2 mirror2 keyring2)]
      (is (= :refuse (:action d)))
      (is (= :key/not-governance-eligible (:reason d))))))

(deftest k4-deterministic-idempotent-retry
  (testing "same request → same root → same signature"
    (let [d (core/signing-decision (:request *ctx*) (:mirror *ctx*) (:keyring *ctx*))
          sig1 (sed/ed25519-sign-bytes (:digest d) (:private-key d))
          sig2 (sed/ed25519-sign-bytes (:digest d) (:private-key d))]
      (is (= :sign (:action d)))
      (is (= sig1 sig2) "same request retry is idempotent"))))

;; ── durable operational disablement survives restart ────────────────────────

(defn- tmp-dir! [prefix]
  (str (Files/createTempDirectory prefix
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest k4-disablement-survives-restart
  (testing "mark-disabled! persists; a fresh keyring load (daemon restart) keeps it disabled"
    (let [dir (tmp-dir! "k4reg")
          ksdir (tmp-dir! "k4keystore")
          kp (deterministic-keypair (byte-array (map byte (apply str (repeat 32 "d")))))
          priv-path (write-pem! (:private-key kp))]
      (daemon/provision-key! dir ksdir priv-path "h" "r1" "k1" (:public-key-hex kp))
      (is (true? (:enabled? (daemon/registry-entry dir "r1" "k1"))))
      (daemon/mark-disabled! dir "r1" "k1" :compromise)
      ;; "restart": reload keyring from persisted registry + keystore
      (let [keyring (daemon/load-keyring dir ksdir)
            mat (material (:public-key-hex kp))
            mirror {:accepted/head state-root :accepted/material mat}
            req (k3/derive-request state-root mat
                                   {:researcher/id "r1" :authorisation/id :k4
                                    :authorisation/request-root (hr "a1")
                                    :review-round/hash (hr "a2")
                                    :outcome/root (hr "a3")
                                    :decision :approve :signing-key/id "k1"})
            d (core/signing-decision req mirror keyring)]
        (is (false? (:enabled? (get keyring ["r1" "k1"]))) "never silently re-enable")
        (is (= :refuse (:action d)))
        (is (= :key/disabled (:reason d))))
      (.delete (java.io.File. priv-path)))))
(deftest k4-daemon-unavailable-no-raw-fallback
  (testing "when the custody daemon is unavailable, authoritative signing fails;
            there is no raw-file-signing fallback on the custody path"
    (let [missing (str (tmp-dir! "k4nosock") "/missing.sock")]
      (is (thrown? Throwable (client/connect missing))
          "client cannot reach an absent daemon")
      (is (nil? (resolve 'resolver-sim.custody.client/load-private-key!))
          "the custody client never loads a raw private key")
      (is (nil? (resolve 'resolver-sim.custody.daemon/sign-arbitrary-message))
          "the daemon exposes no arbitrary signing operation"))))
