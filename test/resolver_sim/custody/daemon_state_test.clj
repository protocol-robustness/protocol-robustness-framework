(ns resolver-sim.custody.daemon-state-test
  "AUTH-K4-S / AUTH-K4-PP / AUTH-STATE-AFTER — independently anchored
   authoritative-state view for the custody daemon.

   Proves the daemon maintains its own accepted head, advanced only by a
   successor that equals the mechanically derived
   derive-governed-successor(T,S0,O), and never trusts a caller-declared head,
   a PRF-exported snapshot, or a caller-selected successor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-signing-request :as k3]
            [resolver-sim.benchmark.governed-authority-transition :as gt]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.configuration-head :as config-head]
            [resolver-sim.custody.daemon-core :as core]
            [resolver-sim.custody.daemon-state :as mirror]
            [resolver-sim.custody.daemon :as daemon]
            [resolver-sim.genesis :as genesis])
  (:import [java.nio.file Files]
           [org.bouncycastle.crypto.params Ed25519PrivateKeyParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory]
           [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]))

(defn- hr [ch] (str "sha256:" (apply str (take 64 (cycle ch)))))
(def ^:private genesis-ref (hr "11"))
(def ^:private s0-root (hr "b0"))

(defn- deterministic-keypair [seed-bytes]
  (let [priv-params (Ed25519PrivateKeyParameters. seed-bytes 0)
        pub-params (.generatePublicKey priv-params)
        priv-info (PrivateKeyInfoFactory/createPrivateKeyInfo priv-params)
        kf (KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.getEncoded priv-info)))]
    {:private-key priv
     :public-key-hex (apply str (map #(format "%02x" (bit-and % 0xff)) (.getEncoded pub-params)))}))

(defn- material [chain-config-root pk]
  (let [ks {:artifact/schema state/signer-key-set-schema
            :signer-key-set/entries
            [{:researcher/id "r1" :signing-key/id "k1"
              :signing-key/algorithm :ed25519 :signing-key/public-key pk}]}
        gov {:schema-version "review-governance.v1"
             :governance/epoch 0 :governance/roles #{:reviewer}
             :governance/principals
             [{:principal/id "r1" :status :active :principal/independence-group "g1"
               :principal/independence-basis-root (hr "ab")
               :principal/keys [{:key/id "k1" :status :active :key/algorithm :ed25519 :key/public-key pk}]}]
             :governance/members
             [{:reviewer/member-id "r1" :principal/id "r1" :status :active :granted-roles #{:reviewer}}]
             :governance/policies
             [{:policy/id "p1" :member-count 3 :threshold 2 :required-roles #{:reviewer}
               :role-cardinality :unique :equivocation-policy :invalid-seat}]}
        round {:artifact/schema rr/governed-schema-version :schema-version rr/governed-schema-version
               :benchmark/content-root (hr "aa")
               :review-round/members [{:researcher/id "r1" :role :reviewer}]
               :review-round/membership-frozen-at 0
               :review-round/policy-root (hr "bb")
               :review-round/purpose :model-admission
               :review-round/chain-configuration-root chain-config-root
               :review-round/governance-root (governance/governance-root gov)
               :review-round/governance-epoch 0
               :review-round/constituted-at 0
               :review-round/policy-id "p1"
               :review-round/policy-hash (hr "dd")}]
    {:chain-instance-genesis/root genesis-ref
     :chain-configuration/root chain-config-root
     :review-governance/root (governance/governance-root gov)
     :review-governance-activation/root (hr "44")
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)
     :review-round/hash (hr "77")
     :position-time-basis/root (hr "ee")
     :position-time-index/root (hr "ff")
     :control-plane-evidence/root (hr "55")
     :review-governance-admissibility/root (hr "66")
     :authority-material/review-governance gov
     :authority-material/signer-key-set ks
     :authority-material/review-round round
     :authority-material/position-time-index {:artifact/schema "x" :entries []}}))

(defn- s0-bundle [env0 mat0 head0]
  (let [parent-config genesis/chain-configuration-v0-fixture]
    {:envelope env0
     :material mat0
     :configuration/head head0
     :config {:verifier-registry/root (:verifier-registry/root parent-config)}
     :config-body parent-config}))

(defn- o-fixture []
  {:proposed-content-root (genesis/chain-configuration-root genesis/chain-configuration-v1-fixture)
   :new-config-body genesis/chain-configuration-v1-fixture})

(defn- envelope [mat head]
  (state/build-envelope-v2
   {:chain-instance-genesis/root genesis-ref
    :execution/state-root s0-root
    :chain-configuration/root (:chain-configuration/root mat)
    :review-governance/root (:review-governance/root mat)
    :review-governance-activation/root (:review-governance-activation/root mat)
    :control-plane-evidence/root (:control-plane-evidence/root mat)
    :position-time-index/root (:position-time-index/root mat)
    :publication/sequence 0
    :publication/predecessor-root nil}
   head))

(def ^:dynamic *ctx* nil)

(defn- ctx-fixture [f]
  (let [kp (deterministic-keypair (byte-array (map byte (apply str (repeat 32 "s")))))
        parent-root (genesis/chain-configuration-root genesis/chain-configuration-v0-fixture)
        mat0 (material parent-root (:public-key-hex kp))
        head0 (config-head/initial-head parent-root 1)
        env0 (envelope mat0 head0)
        s0 (s0-bundle env0 mat0 head0)
        O (o-fixture)
        derived (gt/derive-governed-successor gt/transition-definition-root s0 O)
        keyring {["r1" "k1"] {:private-key (:private-key kp)
                              :public-key-hex (:public-key-hex kp)
                              :enabled? true :generation 1 :custody/handle "h1"}}
        dir (str (Files/createTempDirectory "k4s" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [*ctx* {:kp kp :mat0 mat0 :env0 env0 :s0 s0 :O O :derived derived
                     :keyring keyring :dir dir}]
      (mirror/initialize-mirror! dir genesis-ref env0 mat0)
      (f))))

(use-fixtures :each ctx-fixture)

(defn- submit! [& [candidate-envelope candidate-material]]
  (let [env (or candidate-envelope (:successor-envelope (:derived *ctx*)))
        mat (or candidate-material (:successor-material (:derived *ctx*)))]
    (mirror/submit-candidate-successor!
     (:dir *ctx*) (:authoritative-state-envelope/root (:env0 *ctx*))
     env mat gt/transition-definition-root (:s0 *ctx*) (:O *ctx*))))

(deftest k4s-pinned-genesis-initializes-accepted-head
  (let [m (mirror/read-mirror (:dir *ctx*))]
    (is (= genesis-ref (:genesis/root m)))
    (is (= (:authoritative-state-envelope/root (:env0 *ctx*)) (:accepted/head m)))
    (is (= 0 (:accepted/sequence m)))))

(deftest k4s-derived-successor-advances-head
  (let [r (submit!)]
    (is (:valid? r))
    (is (= (:successor/root (:derived *ctx*)) (:head r)))
    (is (= (:successor/root (:derived *ctx*)) (mirror/accepted-head (:dir *ctx*))))))

(deftest k4s-caller-selected-successor-rejected
  (testing "a caller-selected S1' (different from derived) is rejected at the
            state-after derivation boundary"
    (let [fake (state/build-envelope-v2
                (assoc (:successor-envelope (:derived *ctx*)) :execution/state-root (hr "9a"))
                (:successor-config-head (:derived *ctx*)))
          r (submit! fake (:successor-material (:derived *ctx*)))]
      (is (false? (:valid? r)))
      (is (= :state-after/derivation-mismatch (:reason r))))))

(deftest k4s-no-direct-head-set
  (testing "there is no set-head / trust-snapshot / replace-state API"
    (is (nil? (resolve 'resolver-sim.custody.daemon-state/set-head)))
    (is (nil? (resolve 'resolver-sim.custody.daemon-state/trust-snapshot)))
    (is (nil? (resolve 'resolver-sim.custody.daemon-state/replace-state)))))

(deftest k4s-wrong-predecessor-rejected
  (let [r (mirror/submit-candidate-successor! (:dir *ctx*) (hr "dead")
                                              (:successor-envelope (:derived *ctx*))
                                              (:successor-material (:derived *ctx*))
                                              gt/transition-definition-root (:s0 *ctx*) (:O *ctx*))]
    (is (false? (:valid? r)))
    (is (= :head/predecessor-mismatch (:reason r)))))

(deftest k4s-restart-preserves-accepted-head
  (submit!)
  (let [reloaded (mirror/read-mirror (:dir *ctx*))]
    (is (= (:successor/root (:derived *ctx*)) (:accepted/head reloaded)))
    (is (= genesis-ref (:genesis/root reloaded)))))

(deftest k4s-signing-against-accepted-s1-succeeds
  (submit!)
  (let [request (k3/derive-request (:successor/root (:derived *ctx*))
                                   (:successor-material (:derived *ctx*))
                                   {:researcher/id "r1" :authorisation/id :k4
                                    :authorisation/request-root (hr "a1")
                                    :review-round/hash (hr "a2")
                                    :outcome/root (hr "a3")
                                    :decision :approve :signing-key/id "k1"})
        m {:accepted/head (:successor/root (:derived *ctx*))
           :accepted/material (:successor-material (:derived *ctx*))}
        d (core/signing-decision request m (:keyring *ctx*))]
    (is (= :sign (:action d)))))

(deftest k4s-signing-against-stale-s0-refused-after-advance
  (submit!)
  (let [request (k3/derive-request s0-root (:mat0 *ctx*)
                                   {:researcher/id "r1" :authorisation/id :k4
                                    :authorisation/request-root (hr "a1")
                                    :review-round/hash (hr "a2")
                                    :outcome/root (hr "a3")
                                    :decision :approve :signing-key/id "k1"})
        m {:accepted/head (:successor/root (:derived *ctx*))
           :accepted/material (:successor-material (:derived *ctx*))}
        d (core/signing-decision request m (:keyring *ctx*))]
    (is (= :refuse (:action d)))
    (is (= :authority/state-stale (:reason d)) "historical new signing refused (AUTH-K4-H)")))

(deftest k4s-disablement-survives-state-advance
  (let [ksdir (str (:dir *ctx*) "/keystore")
        key-path (str (Files/createTempDirectory "k4sk" (make-array java.nio.file.attribute.FileAttribute 0)) "/k.pem")]
    (spit key-path (str "-----BEGIN PRIVATE KEY-----\n"
                        (.encodeToString (java.util.Base64/getMimeEncoder)
                                         (.getEncoded (:private-key (:kp *ctx*))))
                        "\n-----END PRIVATE KEY-----\n"))
    (daemon/provision-key! (:dir *ctx*) ksdir key-path "h1" "r1" "k1" (:public-key-hex (:kp *ctx*)))
    (daemon/mark-disabled! (:dir *ctx*) "r1" "k1" :compromise)
    (submit!)
    (is (false? (:enabled? (daemon/registry-entry (:dir *ctx*) "r1" "k1")))
        "disabled state survives state advancement")))

(deftest k4s-prf-fabricated-snapshot-cannot-authorize-signing
  (testing "a request bound to a fabricated alternate state is refused regardless
            of any caller-supplied snapshot/currentness"
    (let [request (k3/derive-request (hr "9") (:mat0 *ctx*)
                                     {:researcher/id "r1" :authorisation/id :k4
                                      :authorisation/request-root (hr "a1")
                                      :review-round/hash (hr "a2")
                                      :outcome/root (hr "a3")
                                      :decision :approve :signing-key/id "k1"})
          m {:accepted/head s0-root :accepted/material (:mat0 *ctx*)}
          d (core/signing-decision request m (:keyring *ctx*))]
      (is (= :refuse (:action d)))
      (is (= :authority/state-stale (:reason d))))))