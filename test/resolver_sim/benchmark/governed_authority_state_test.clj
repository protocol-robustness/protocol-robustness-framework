(ns resolver-sim.benchmark.governed-authority-state-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-result-receipt :as result-receipt]
            [resolver-sim.benchmark.governed-authority-result-receipt-store :as receipt-store]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.benchmark.allocation-entitlement-policy :as entitlement-policy]
            [resolver-sim.benchmark.authority-semantics-policy :as semantics-policy]
            [resolver-sim.benchmark.authority-semantics-state :as semantics-state]
            [resolver-sim.benchmark.configuration-transition-authorization :as c3a]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.benchmark.configuration-activation-publication :as c3b]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-governance-evidence :as evidence]
            [resolver-sim.assurance.governed-authority-consumer :as gac]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.io.content-addressed-store :as cas])
  (:import [java.security KeyPairGenerator]
           [java.util Base64]))

(def ^:private not-rooted-msg "authority material is not rooted and authenticated")
(def ^:private runtime-value-msg "authority material contains runtime or mutable value")

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(def ^:private genesis-ref (hash-ref "11"))
(def ^:private config-ref (hash-ref "22"))
(def ^:private activation-ref (hash-ref "44"))
(def ^:private control-evidence-ref (hash-ref "55"))
(def ^:private admissibility-ref (hash-ref "66"))
(def ^:private round-hash-ref (hash-ref "77"))

(defn- round-body []
  {:artifact/schema rr/governed-schema-version
   :schema-version rr/governed-schema-version
   :benchmark/content-root (hash-ref "aa")
   :review-round/members []
   :review-round/membership-frozen-at 0
   :review-round/policy-root (hash-ref "bb")
   :review-round/purpose :model-admission
   :review-round/chain-configuration-root config-ref
   :review-round/governance-root (hash-ref "cc")
   :review-round/governance-epoch 0
   :review-round/constituted-at 0
   :review-round/policy-id "p1"
   :review-round/policy-hash (hash-ref "dd")})

(def ^:private test-public-key
  "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")

(defn- key-entry
  "Build a single signer-key-set entry with researcher-id r1 and the given key-id."
  [key-id]
  {:researcher/id "r1"
   :signing-key/id key-id
   :signing-key/algorithm :ed25519
   :signing-key/public-key test-public-key})

(defn- key-set
  "Build a governed-authority-signer-key-set.v1 with the given key-ids under
    researcher r1."
  ([key-ids]
   {:artifact/schema state/signer-key-set-schema
    :signer-key-set/entries (mapv key-entry key-ids)})
  ([]
   (key-set ["k1" "k2"])))

(defn- governance-body [key-ids]
  "Build a valid review-governance.v1 body whose active principal r1 owns the
    given key-ids."
  {:schema-version "review-governance.v1"
   :governance/epoch 0
   :governance/roles #{:reviewer}
   :governance/principals [{:principal/id "r1"
                            :status :active
                            :principal/independence-group "g1"
                            :principal/independence-basis-root (hash-ref "ab")
                            :principal/keys (mapv (fn [kid]
                                                    {:key/id kid
                                                     :status :active
                                                     :key/algorithm :ed25519
                                                     :key/public-key test-public-key})
                                                  key-ids)}]
   :governance/members [{:reviewer/member-id "r1"
                         :principal/id "r1"
                         :status :active
                         :granted-roles #{:reviewer}}]
   :governance/policies [{:policy/id "p1"
                          :member-count 3
                          :threshold 2
                          :required-roles #{:reviewer}
                          :role-cardinality :unique
                          :equivocation-policy :invalid-seat}]})

(def ^:private test-ptb-root
  (evidence/position-time-basis-root
   {:schema-version "position-time-basis.v1"
    :position-acceptance-roots []}))

(defn- position-time-index-body [round-root]
  "Build a valid governed-authority-position-time-index.v1 body cross-referencing
    the given round root and an empty position-time-basis root."
  {:artifact/schema state/position-time-index-schema
   :position-time-basis/root test-ptb-root
   :review-round/root round-root
   :position-time-index/entries []})

(defn- authenticated-material
  "Build authenticated authority material with the default key-set, or with the
    given key-set. An optional governance body can be supplied to decouple the
    governance root from the signer-key-set identity (used by interchangeability
    characterization tests)."
  ([]
   (authenticated-material (key-set) nil))
  ([ks]
   (authenticated-material ks nil))
  ([ks gb-override]
   (let [key-ids (map :signing-key/id (:signer-key-set/entries ks))
         rb (round-body)
         gb (or gb-override (governance-body key-ids))
         round-root (state/review-round-material-root rb)
         pti (position-time-index-body round-root)]
     {:chain-instance-genesis/root genesis-ref
      :chain-configuration/root config-ref
      :review-governance/root (governance/governance-root gb)
      :review-governance-activation/root activation-ref
      :control-plane-evidence/root control-evidence-ref
      :review-governance-admissibility/root admissibility-ref
      :review-round/hash round-hash-ref
      :review-round/root round-root
      :position-time-basis/root test-ptb-root
      :position-time-index/root (state/position-time-index-root pti)
      :signer-key-set/root (state/signer-key-set-root ks)
      :authority-material/review-round rb
      :authority-material/review-governance gb
      :authority-material/position-time-index pti
      :authority-material/signer-key-set ks})))

(defn- store-envelope [state-root predecessor sequence material]
  {:chain-instance-genesis/root genesis-ref
   :execution/state-root state-root
   :chain-configuration/root config-ref
   :review-governance/root (or (:review-governance/root material) (hash-ref "00"))
   :review-governance-activation/root activation-ref
   :configuration-head/root (hash-ref "88")
   :control-plane-evidence/root control-evidence-ref
   :position-time-index/root (or (:position-time-index/root material) (hash-ref "ee"))
   :publication/sequence sequence
   :publication/predecessor-root predecessor})

(deftest authoritative-envelope-v2-binds-canonical-head-state-root
  (let [material (authenticated-material)
        head-a (configuration-head/current-head (configuration-head/new-store config-ref 1))
        head-b (configuration-head/initial-head config-ref 2)
        base (store-envelope (hash-ref "a1") nil 0 material)
        envelope (state/build-envelope-v2 base head-a)]
    (is (state/verify-envelope-v2 envelope head-a))
    (is (= (:configuration-head-state/root head-a) (:configuration-head/root envelope)))
    (is (= envelope (state/build-envelope-v2 base head-a)))
    (is (not= (:configuration-head-state/root head-a) (:configuration-head-state/root head-b)))
    (is (false? (state/verify-envelope-v2 envelope head-b)))
    (is (false? (state/verify-envelope-v2 envelope
                                          (assoc head-a :configuration/head-root (hash-ref "fe")))))
    (is (false? (state/verify-envelope-v2
                 (assoc envelope :configuration-head/root (hash-ref "88")) head-a)))
    (is (= state/envelope-schema (:artifact/schema (state/build-envelope base))))))

(deftest authoritative-store-v2-retains-canonical-head-state
  (let [material (authenticated-material)
        head-a (configuration-head/current-head (configuration-head/new-store config-ref 1))
        envelope-a (state/build-envelope-v2 (store-envelope (hash-ref "a2") nil 0 material) head-a)
        store (state/new-store-v2 envelope-a head-a material)
        head-b-base (assoc head-a
                           :configuration/epoch 2
                           :configuration/sequence 1
                           :configuration/predecessor-head-root (:configuration-head-state/root head-a)
                           :configuration/activation-transition-root (hash-ref "ab"))
        head-b (assoc head-b-base :configuration-head-state/root (configuration-head/head-state-root head-b-base))
        envelope-b (state/build-envelope-v2
                    (store-envelope (hash-ref "a3") (:authoritative-state-envelope/root envelope-a) 1 material)
                    head-b)]
    (is (= head-a (get-in @(.state store) [:configuration-head-states (:configuration-head/root envelope-a)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (state/new-store-v2
                  (state/build-envelope (store-envelope (hash-ref "a4") nil 0 material))
                  head-a material)))
    (is (:published? (state/publish-successor-v2! store (:authoritative-state-envelope/root envelope-a) envelope-b head-b material)))
    (is (= head-b (get-in @(.state store) [:configuration-head-states (:configuration-head/root envelope-b)])))))

(defn- fresh-store
  ([] (fresh-store (authenticated-material)))
  ([material]
   (let [state-root (hash-ref "aa00")
         envelope (state/build-envelope (store-envelope state-root nil 0 material))]
     {:store (state/new-store envelope material)
      :state-root state-root
      :envelope envelope
      :head (:authoritative-state-envelope/root envelope)
      :material material})))

(defn- admission-basis
  ([w] (admission-basis w (:state-root w) (:head w)))
  ([_w state-root anchor]
   (resolution/build-resolution-basis-v2
    {:resolution/purpose :current-admission
     :chain-instance-genesis/root genesis-ref
     :resolution/state-before-root state-root
     :resolution/anchor-root anchor
     :review-round/hash round-hash-ref
     :authority-resolver/root (:governed-authority-resolver/root resolution/default-resolver)})))

(defn- audit-basis-v2 [state-root anchor]
  (resolution/build-resolution-basis-v2
   {:resolution/purpose :historical-audit
    :chain-instance-genesis/root genesis-ref
    :resolution/state-before-root state-root
    :resolution/anchor-root anchor
    :review-round/hash round-hash-ref
    :authority-resolver/root (:governed-authority-resolver/root resolution/default-resolver)}))

(defn- v1-basis [purpose state-root anchor]
  (resolution/build-resolution-basis
   {:resolution/purpose purpose
    :chain-instance-genesis/root genesis-ref
    :resolution/state-before-root state-root
    :resolution/anchor-root anchor
    :review-round/hash round-hash-ref}))

(defn- resolve-basis
  "resolve-governed-authority-context with exceptions surfaced as data so a
   broken resolution path fails assertions with its cause instead of erroring."
  [store basis]
  (try (state/resolve-governed-authority-context store basis)
       (catch Exception e
         {:resolved? false :thrown (.getMessage e) :errors (:errors (ex-data e))})))

(defn- resolve*
  ([w] (resolve-basis (:store w) (admission-basis w)))
  ([w state-root anchor]
   (resolve-basis (:store w) (admission-basis w state-root anchor))))

(defn- issue-fence [w]
  (try
    (let [result (state/resolve-authority-material (:store w) (admission-basis w))]
      (if (:resolved? result)
        {:ok? true
         :result (assoc result :fence {:fence/id (get-in result [:resolution-handle :resolution-handle/id])})}
        {:ok? false :reason (:reason result)}))
    (catch Exception e
      {:ok? false :thrown (.getMessage e) :errors (:errors (ex-data e))})))

(defn- transition-binding [context-root pre post transition-ref]
  (resolution/build-transition-binding
   {:resolved-review-authority-context/root context-root
    :transition/root transition-ref
    :transaction/state-before-root pre
    :transaction/state-after-root post
    :authorization/result-root (hash-ref "ff")}))

(defn- fence-record [w fence-id]
  (or (get-in @(.state (:store w)) [:issued-fences fence-id])
      (get-in @(.state (:store w)) [:observed-resolutions fence-id])))

(defn- public-key-hex
  "Extract the raw 32-byte Ed25519 public key from its X.509 encoding."
  [public-key]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (take-last 32 (.getEncoded public-key)))))

(defn- write-private-key-file! [label private-key]
  (let [file (java.io.File/createTempFile (str "governed-authority-" label) ".pem")
        encoded (.encodeToString (Base64/getMimeEncoder) (.getEncoded private-key))]
    (spit file (str "-----BEGIN PRIVATE KEY-----\n" encoded "\n-----END PRIVATE KEY-----\n"))
    (.setReadable file false false)
    (.setWritable file false false)
    (.setReadable file true true)
    (.getPath file)))

(defn- authority-signer [researcher-id]
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        key-id (str researcher-id "-key")]
    {:researcher/id researcher-id
     :signing-key/id key-id
     :signing-key/algorithm :ed25519
     :signing-key/public-key (public-key-hex (.getPublic pair))
     :private-key-path (write-private-key-file! researcher-id (.getPrivate pair))}))

(defn- authorised-material-and-authorisation
  "Build a governed 3-of-2 authority fixture with real Ed25519 approvals."
  ([] (authorised-material-and-authorisation nil))
  ([authorization-target-root]
   (let [signers (mapv authority-signer ["r1" "r2" "r3"])
         entries (mapv #(select-keys % [:researcher/id :signing-key/id
                                        :signing-key/algorithm :signing-key/public-key])
                       signers)
         ks {:artifact/schema state/signer-key-set-schema :signer-key-set/entries entries}
         governance-body {:schema-version "review-governance.v1"
                          :governance/epoch 0
                          :governance/roles #{:reviewer/a :reviewer/b :reviewer/c}
                          :governance/principals
                          (mapv (fn [signer]
                                  (let [researcher-id (:researcher/id signer)
                                        key-id (:signing-key/id signer)]
                                    {:principal/id researcher-id :status :active
                                     :principal/independence-group researcher-id
                                     :principal/independence-basis-root (hash-ref "ab")
                                     :principal/keys [{:key/id key-id :status :active
                                                       :key/algorithm (:signing-key/algorithm signer)
                                                       :key/public-key (:signing-key/public-key signer)}]}))
                                signers)
                          :governance/members
                          (mapv (fn [signer]
                                  (let [researcher-id (:researcher/id signer)]
                                    {:reviewer/member-id researcher-id :principal/id researcher-id
                                     :status :active
                                     :granted-roles #{:reviewer/a :reviewer/b :reviewer/c}}))
                                signers)
                          :governance/policies [{:policy/id "p1" :member-count 3 :threshold 2
                                                 :required-roles #{:reviewer/a :reviewer/b :reviewer/c}
                                                 :role-cardinality :unique
                                                 :equivocation-policy :invalid-seat}]}
         rb (assoc (round-body)
                   :review-round/governance-root (governance/governance-root governance-body)
                   :review-round/members
                   (mapv (fn [signer role]
                           {:researcher/id (:researcher/id signer) :role role})
                         signers [:reviewer/a :reviewer/b :reviewer/c]))
         round-root (state/review-round-material-root rb)
         pti (position-time-index-body round-root)
         material {:chain-instance-genesis/root genesis-ref
                   :chain-configuration/root config-ref
                   :review-governance/root (governance/governance-root governance-body)
                   :review-governance-activation/root activation-ref
                   :control-plane-evidence/root control-evidence-ref
                   :review-governance-admissibility/root admissibility-ref
                   :review-round/hash round-hash-ref
                   :review-round/root round-root
                   :position-time-basis/root test-ptb-root
                   :position-time-index/root (state/position-time-index-root pti)
                   :signer-key-set/root (state/signer-key-set-root ks)
                   :authority-material/review-round rb
                   :authority-material/review-governance governance-body
                   :authority-material/position-time-index pti
                   :authority-material/signer-key-set ks}
         request-root (hash-ref "ab")
         target-root (or authorization-target-root (hash-ref "cd"))
         auth-id :authorisation/authorised-fixture
         approve (fn [signer]
                   (rfa/build-signed-decision-v2
                    (:researcher/id signer) auth-id request-root round-hash-ref target-root
                    :approve (:private-key-path signer)
                    :signing-key-id (:signing-key/id signer)))]
     (try
       {:material material
        :authorisation {:artifact/schema "force-authorisation.v1"
                        :authorisation/id auth-id
                        :authorisation/request-root request-root
                        :authorisation/review-round {:review-round/hash round-hash-ref}
                        :authorisation/target {:target/proposed-content-root target-root}
                        :authorisation/decision-references (mapv approve signers)}}
       (finally
         (doseq [signer signers]
           (.delete (java.io.File. (:private-key-path signer)))))))))

(defn- new-store-error [envelope material]
  (try (state/new-store envelope material) nil
       (catch Exception e (.getMessage e))))

(defn- successor-of [w material]
  (let [pred (:authoritative-state-envelope/root (:envelope w))
        seq+ (inc (:publication/sequence (:envelope w)))
        state-root (hash-ref "aa01")]
    {:state-root state-root
     :envelope (state/build-envelope (store-envelope state-root pred seq+ material))
     :material material}))

(defn- material-for-configuration
  ([configuration-root] (material-for-configuration (authenticated-material) configuration-root))
  ([material configuration-root]
   (let [round (assoc (:authority-material/review-round material)
                      :review-round/chain-configuration-root configuration-root)
         round-root (state/review-round-material-root round)
         index (position-time-index-body round-root)]
     (assoc material
            :chain-configuration/root configuration-root
            :review-round/root round-root
            :position-time-index/root (state/position-time-index-root index)
            :authority-material/review-round round
            :authority-material/position-time-index index))))

(defn- c3-transition []
  {:transition/schema genesis/chain-configuration-transition-schema
   :protocol/genesis-root (hash-ref "aa")
   :target {:target/type :chain-instance :target/root (hash-ref "bb")}
   :configuration/parent-root config-ref
   :configuration/new-root (hash-ref "cc")
   :verifier-registry/root (hash-ref "dd")
   :epoch 1})

(deftest c3a-exact-transition-and-predecessor-state-are-required
  (let [transition (c3-transition)
        transition-root (genesis/chain-configuration-transition-root transition)
        {:keys [material authorisation]} (authorised-material-and-authorisation transition-root)
        w (fresh-store material)
        resolved (state/resolve-authority-material (:store w) (admission-basis w))
        witness {:predecessor-envelope (:envelope w)
                 :predecessor-material material
                 :configuration-transition transition
                 :authorisation authorisation
                 :resolution-basis (admission-basis w)
                 :resolved-review-authority-context-root
                 (get-in resolved [:context :resolved-review-authority-context/root])}
        candidate (c3a/build-evidence-candidate witness)
        evidence (c3a/build-verified-evidence witness)
        transplanted-basis
        (resolution/build-resolution-basis-v2
         (assoc (select-keys (admission-basis w)
                             [:resolution/purpose :chain-instance-genesis/root
                              :resolution/state-before-root :resolution/anchor-root
                              :review-round/hash :authority-resolver/root])
                :resolution/state-before-root (hash-ref "ca")))
        wrong-parent-transition (assoc transition :configuration/parent-root (hash-ref "12"))
        non-authorised {:artifact/schema "force-authorisation.v1"
                        :authorisation/id "c3a-non-authorised"
                        :authorisation/request-root (hash-ref "ab")
                        :authorisation/review-round {:review-round/hash round-hash-ref}
                        :authorisation/target {:target/proposed-content-root transition-root}
                        :authorisation/decision-references []}
        verify? #(-> (c3a/verify-evidence %) :valid?)]
    (is (verify? (assoc witness :evidence candidate)))
    (is (verify? (assoc witness :evidence evidence)))
    (is (false? (verify? (assoc witness :evidence candidate
                                :authorisation (assoc-in authorisation
                                                         [:authorisation/target :target/proposed-content-root]
                                                         (hash-ref "ef"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (c3a/build-verified-evidence
                  (assoc witness :configuration-transition
                         (assoc transition :configuration/new-root (hash-ref "ee"))))))
    (is (false? (verify? (assoc witness :evidence
                                (assoc candidate :authorization-subject/kind :other-kind)))))
    (is (false? (verify? (assoc witness :evidence
                                (assoc candidate :authorization-subject/root (hash-ref "de"))))))
    (is (false? (verify? (assoc witness :evidence
                                (assoc candidate :authority-report/root (hash-ref "aa"))))))
    (is (false? (verify? (assoc witness :evidence
                                (assoc candidate :authority-evaluation-basis/root (hash-ref "bb"))))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :configuration-transition (assoc transition :configuration/new-root (hash-ref "ee"))))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :predecessor-envelope
                                (assoc (:envelope w) :execution/state-root (hash-ref "ff"))))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :configuration-transition wrong-parent-transition))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :predecessor-envelope
                                (assoc (:envelope w) :configuration-head/root (hash-ref "13"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (c3a/build-evidence-candidate
                  (assoc witness :resolution-basis transplanted-basis)))
        "a syntactically valid context root cannot be transplanted under another predecessor basis")
    (is (false? (verify? (assoc witness :evidence evidence
                                :predecessor-material
                                (assoc material :review-governance/root (hash-ref "14"))))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :predecessor-material
                                (assoc-in material [:authority-material/signer-key-set
                                                    :signer-key-set/entries 0 :signing-key/public-key]
                                          (hash-ref "15"))))))
    (is (false? (verify? (assoc witness :evidence evidence
                                :predecessor-material
                                (assoc-in material [:authority-material/position-time-index
                                                    :position-time-index/entries]
                                          [{:position/root (hash-ref "16")
                                            :review-position-acceptance/root (hash-ref "17")
                                            :position-time/accepted-at "0"}])))))
    (is (false? (verify? (assoc witness :evidence evidence :authorisation non-authorised))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (c3a/build-verified-evidence (assoc witness :authorisation non-authorised))))
    (is (nil? (:authority-fence candidate)))))

(deftest c3a-v2-predecessor-binds-retained-canonical-head
  (let [transition (c3-transition)
        transition-root (genesis/chain-configuration-transition-root transition)
        {:keys [material authorisation]} (authorised-material-and-authorisation transition-root)
        head-state (configuration-head/current-head (configuration-head/new-store config-ref 1))
        envelope (state/build-envelope-v2 (store-envelope (hash-ref "a5") nil 0 material) head-state)
        store (state/new-store-v2 envelope head-state material)
        resolved (state/resolve-authority-material store
                                                   (admission-basis {:state-root (:execution/state-root envelope)
                                                                     :head (:authoritative-state-envelope/root envelope)}))
        witness {:predecessor-envelope envelope
                 :predecessor-head-state head-state
                 :predecessor-material material
                 :configuration-transition transition
                 :authorisation authorisation
                 :resolution-basis (admission-basis {:state-root (:execution/state-root envelope)
                                                     :head (:authoritative-state-envelope/root envelope)})
                 :resolved-review-authority-context-root
                 (get-in resolved [:context :resolved-review-authority-context/root])}
        evidence (c3a/build-verified-evidence witness)]
    (is (:resolved? resolved))
    (is (:valid? (c3a/verify-evidence (assoc witness :evidence evidence))))
    (is (= (:configuration-head-state/root head-state)
           (:predecessor-configuration-head/root evidence)))))

(defn- canonical-c3b-fixture []
  (let [c0 genesis/chain-configuration-v0-fixture
        c0-root (genesis/chain-configuration-root c0)
        c1 (assoc c0 :verifier-registry/root (hash-ref "ac"))
        c1-root (genesis/chain-configuration-root c1)
        transition {:transition/schema genesis/chain-configuration-transition-schema
                    :protocol/genesis-root genesis/protocol-genesis-fixture-root
                    :target {:target/type :chain-instance :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
                    :configuration/parent-root c0-root :configuration/new-root c1-root
                    :verifier-registry/root (:verifier-registry/root c1) :epoch 2}
        transition-root (genesis/chain-configuration-transition-root transition)
        {:keys [material authorisation]} (authorised-material-and-authorisation transition-root)
        predecessor-material (material-for-configuration material c0-root)
        successor-material (material-for-configuration material c1-root)
        h0 (configuration-head/current-head (configuration-head/new-store c0-root 1))
        e0 (state/build-envelope-v2 (assoc (store-envelope (hash-ref "a6") nil 0 predecessor-material)
                                           :chain-configuration/root c0-root) h0)
        store (state/new-store-v2 e0 h0 predecessor-material)
        resolved (state/resolve-authority-material store (admission-basis {:state-root (:execution/state-root e0)
                                                                           :head (:authoritative-state-envelope/root e0)}))
        witness {:predecessor-envelope e0 :predecessor-head-state h0 :predecessor-material predecessor-material
                 :configuration-transition transition :authorisation authorisation
                 :resolution-basis (admission-basis {:state-root (:execution/state-root e0)
                                                     :head (:authoritative-state-envelope/root e0)})
                 :resolved-review-authority-context-root (get-in resolved [:context :resolved-review-authority-context/root])}
        evidence (c3a/build-verified-evidence witness)
        request {:authorization-evidence evidence :authorization-witness witness :transition transition
                 :parent-configuration c0 :successor-configuration c1
                 :successor-envelope (assoc (store-envelope (hash-ref "a7") (:authoritative-state-envelope/root e0) 1 successor-material)
                                            :chain-configuration/root c1-root)
                 :successor-material successor-material}]
    {:store store :c0 c0 :c1 c1 :c0-root c0-root :c1-root c1-root :e0 e0 :h0 h0
     :transition transition :transition-root transition-root :authorisation authorisation
     :witness witness :evidence evidence :request request}))

(deftest canonical-c3b-happy-path
  (let [{:keys [store c0 c1 c0-root c1-root e0 h0 transition transition-root authorisation witness evidence request]}
        (canonical-c3b-fixture)
        result (c3b/activate-under-verified-transition-authorization! store request)
        e1 (:envelope result) h1 (:head-state result) lineage (:lineage result)
        before-replay @(.state store)
        replay (c3b/activate-under-verified-transition-authorization! store request)]
    (is (= c0-root (:configuration/parent-root transition)))
    (is (= c1-root (:configuration/new-root transition)))
    (is (= c0-root (:configuration/head-root h0)))
    (is (= (:configuration-head-state/root h0) (:configuration-head/root e0)))
    (is (= transition-root (get-in authorisation [:authorisation/target :target/proposed-content-root])))
    (is (= transition-root (:configuration-transition/root evidence)))
    (is (:activated? result))
    (is (= (:authoritative-state-envelope/root e1) (:head @(.state store))))
    (is (= h1 (get-in @(.state store) [:configuration-head-states (:configuration-head/root e1)])))
    (is (= (:configuration-head-state/root h1) (:configuration-head/root e1)))
    (is (:valid? (c3b/verify-activation-lineage
                  {:lineage lineage :predecessor-envelope e0 :predecessor-head-state h0
                   :authorization-evidence evidence :authorization-witness witness
                   :transition transition :parent-configuration c0 :successor-configuration c1
                   :successor-envelope e1 :successor-head-state h1})))
    (is (false? (:activated? replay)))
    (is (= before-replay @(.state store)))
    (doseq [[k replacement] [[:predecessor-authoritative-state/root (hash-ref "b1")]
                             [:predecessor-configuration-head/root (hash-ref "b2")]
                             [:configuration-transition-authorization-evidence/root (hash-ref "b3")]
                             [:configuration-transition/root (hash-ref "b4")]
                             [:successor-configuration-head/root (hash-ref "b5")]
                             [:successor-authoritative-state/root (hash-ref "b6")]
                             [:configuration-activation-lineage/root (hash-ref "b7")]]]
      (is (false? (:valid? (c3b/verify-activation-lineage
                            {:lineage (assoc lineage k replacement)
                             :predecessor-envelope e0 :predecessor-head-state h0
                             :authorization-evidence evidence :authorization-witness witness
                             :transition transition :parent-configuration c0 :successor-configuration c1
                             :successor-envelope e1 :successor-head-state h1})))))))

(deftest c3b-prepublication-rejections-publish-nothing
  (doseq [[label mutate] [[:transition #(assoc % :transition (assoc (:transition %) :epoch 3))]
                          [:c3a-predecessor #(assoc % :authorization-evidence
                                                    (assoc (:authorization-evidence %)
                                                           :predecessor-authoritative-state/root (hash-ref "d1")))]
                          [:wrong-parent #(assoc % :parent-configuration (:successor-configuration %))]
                          [:wrong-successor #(assoc % :successor-configuration (:parent-configuration %))]]]
    (let [{:keys [store request]} (canonical-c3b-fixture)
          before @(.state store)
          result (c3b/activate-under-verified-transition-authorization! store (mutate request))]
      (is (false? (:activated? result)) (str label " rejects"))
      (is (= before @(.state store)) (str label " publishes nothing")))))

(deftest c3b-stale-composed-publication-publishes-no-t1-artifacts
  (let [{:keys [store request]} (canonical-c3b-fixture)
        original c3a/verify-evidence
        advanced? (atom false)
        result (with-redefs [c3a/verify-evidence
                             (fn [witness]
                               (when (compare-and-set! advanced? false true)
                                 (c3b/activate-under-verified-transition-authorization! store request))
                               (original witness))]
                 (c3b/activate-under-verified-transition-authorization! store request))]
    (is @advanced?)
    (is (false? (:activated? result)))
    (is (= 2 (count (:envelopes @(.state store)))))
    (is (= 1 (count (:activation-lineage @(.state store)))))))

(deftest c3b-v1-predecessor-rejects-without-publication
  (let [w (fresh-store)
        before @(.state (:store w))
        result (c3b/activate-under-verified-transition-authorization!
                (:store w) {:authorization-evidence {}
                            :authorization-witness {}
                            :transition {}
                            :parent-configuration {}
                            :successor-configuration {}
                            :successor-envelope {}
                            :successor-material {}})]
    (is (= :authoritative-v2-predecessor-required (:reason result)))
    (is (= before @(.state (:store w))))))

;; ── priority 1: signer-key body/root substitution ─────────────────────────

(deftest signer-key-body-root-substitution-rejected
  (let [{:keys [envelope] :as w} (fresh-store)]
    (testing "positive control: intact body authenticates"
      (is (nil? (new-store-error (:envelope w) (:material w)))))
    (testing "extra rogue signer key appended, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (update-in (:material w)
                         [:authority-material/signer-key-set :signer-key-set/entries]
                         conj (key-entry "rogue"))))))
    (testing "signing key element swapped, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in (:material w)
                        [:authority-material/signer-key-set :signer-key-set/entries 0]
                        (key-entry "evil"))))))
    (testing "entire body replaced by a differently-rooted set, old root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc (:material w)
                     :authority-material/signer-key-set
                     (key-set ["z1" "z2"]))))))
    (testing "body schema tampered"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in (:material w)
                        [:authority-material/signer-key-set :artifact/schema]
                        "governed-authority-signer-key-set.v0")))))))

;; ── priority 2: review-round body/root substitution ───────────────────────

(deftest review-round-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "round purpose tampered inside body, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material [:authority-material/review-round :review-round/purpose]
                        :model-challenge)))))
    (testing "membership content tampered, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material [:authority-material/review-round :benchmark/content-root]
                        (hash-ref "mutated"))))))))

;; ── priority 3: governance body/root substitution ─────────────────────────

(deftest governance-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "governance body swapped for a different canonical projection"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc material
                     :authority-material/review-governance
                     {:schema-version "review-governance.v1-backdoor"})))))))

;; ── priority 4: position-time body/root substitution ──────────────────────

(deftest position-time-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "position-time-basis root swapped inside index body, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material
                        [:authority-material/position-time-index :position-time-basis/root]
                        (hash-ref "phantom"))))))))

;; ── priority 5: missing bodies with correct-looking roots ─────────────────

(deftest missing-authenticated-bodies-with-declared-roots-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (doseq [body-key [:authority-material/review-round
                      :authority-material/review-governance
                      :authority-material/position-time-index
                      :authority-material/signer-key-set]]
      (testing (str "missing " (name body-key) " with root fields retained")
        (is (= not-rooted-msg (new-store-error envelope (dissoc material body-key))))))
    (testing "all bodies stripped, every declared root retained"
      (let [stripped (apply dissoc material [:authority-material/review-round
                                             :authority-material/review-governance
                                             :authority-material/position-time-index
                                             :authority-material/signer-key-set])]
        (is (= not-rooted-msg (new-store-error envelope stripped)))))
    (testing "non-map body standing in for an authenticated body"
      (is (= not-rooted-msg
             (new-store-error envelope (assoc material :authority-material/signer-key-set (hash-ref "x"))))))))

;; ── priority 6: runtime values in authenticated structures ────────────────

(deftest runtime-values-rejected-in-authenticated-material-and-publication
  (let [{:keys [envelope] :as w} (fresh-store)]
    (testing "callback inside the signer-key-set entries vector"
      (is (= runtime-value-msg
             (new-store-error
              envelope
              (assoc-in (:material w) [:authority-material/signer-key-set :signer-key-set/entries 0]
                        (fn [] :rogue))))))
    (testing "callback inside the review-round body"
      (is (= runtime-value-msg
             (new-store-error
              envelope
              (assoc-in (:material w) [:authority-material/review-round :review-round/membership-frozen-at]
                        (fn [] 0))))))
    (testing "callback at the top level of published material"
      (is (= runtime-value-msg
             (new-store-error envelope (assoc (:material w) :extraneous (fn [] :rogue))))))
    (testing "callback in successor publication is rejected and head does not move"
      (let [succ (successor-of w {:chain-instance-genesis/root genesis-ref
                                  :authority-material/signer-key-set (fn [] :rogue)})
            head-before (:head @(.state (:store w)))]
        (is (= runtime-value-msg
               (try
                 (let [r (state/publish-successor! (:store w) (:head w) (:envelope succ) (:material succ))]
                   (str "published: " (:published? r)))
                 (catch Exception e (.getMessage e)))))
        (is (= head-before (:head @(.state (:store w)))))))))

;; ── publication boundary: successors must be authenticated like genesis ───

(deftest successor-publication-requires-authenticated-material
  (let [{:keys [head] :as w} (fresh-store)
        succ (successor-of w {:chain-instance-genesis/root genesis-ref})]
    (testing "body-less, root-less successor material must not enter the store"
      (is (thrown? Exception
                   (state/publish-successor! (:store w) head (:envelope succ) (:material succ)))))
    (testing "head unchanged after rejected publication"
      (is (= head (:head @(.state (:store w))))))))

;; ── prerequisite: authenticated stores must resolve at all ────────────────

(deftest authenticated-store-resolves-current-admission
  (let [w (fresh-store)
        r (resolve* w)]
    (is (:resolved? r)
        "an authenticated store must complete current-admission resolution")
    (when (:resolved? r)
      (let [ctx (:context r)]
        (is (= (:head w) (:authority-state/root ctx)))
        (is (= (:state-root w) (:resolution/state-before-root ctx)))
        (is (some? (:resolved-review-authority-context/root ctx)))))))

;; ── priority 7: fence record proves exact material roots from issuance ────

(deftest fence-record-proves-exact-material-roots-from-issuance
  (let [{:keys [envelope material] :as w} (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            rec (fence-record w (:fence/id fence))]
        (is (some? rec) "issued fence id resolves to a registry record")
        (is (= :observed (:status rec)))
        (is (= :current-admission (:purpose rec)))
        (is (= (:authority-state/root context) (:authority-state-envelope/root rec)))
        (is (= (:resolution/state-before-root context) (:execution/state-root rec)))
        (is (= (:resolved-review-authority-context/root context)
               (:resolved-review-authority-context/root rec)))
        (is (= (:resolution-basis/root context) (:resolution-basis/root rec)))
        (is (= (:publication/sequence envelope) (:publication/sequence rec)))
        (doseq [[k v] [[:review-round/root (:review-round/root material)]
                       [:review-governance/root (:review-governance/root material)]
                       [:position-time-basis/root (:position-time-basis/root material)]
                       [:position-time-index/root (:position-time-index/root material)]
                       [:signer-key-set/root (:signer-key-set/root material)]]]
          (is (= v (get rec k)) (str "fence pins exact issuance value of " k)))
        (is (= (:authority-evaluation-basis/root
                (state/evaluation-basis
                 {:resolved-review-authority-context/root
                  (:resolved-review-authority-context/root context)
                  :review-round/root (:review-round/root material)
                  :review-governance/root (:review-governance/root material)
                  :position-time-basis/root (:position-time-basis/root material)
                  :position-time-index/root (:position-time-index/root material)
                  :signer-key-set/root (:signer-key-set/root material)}))
               (:authority-evaluation-basis/root rec))
            "fence binds the evaluation basis joining the context and key set")
        (is (= material (:authenticated-material (:result issued))))))))

;; ── priority 8 + transplant A: fences are store-instance and key-set bound ─

(deftest fences-not-interchangeable-across-stores-or-key-sets
  (let [shared-governance (governance-body ["k1" "k2"])
        ks-a (key-set ["k1" "k2"])
        ks-b (key-set ["k1"])
        wa (fresh-store (authenticated-material ks-a shared-governance))
        ;; Store B: same envelope content, different authenticated key set
        wb (fresh-store (authenticated-material ks-b shared-governance))
        fa (issue-fence wa)
        fb (issue-fence wb)]
    (is (:ok? fa) "prerequisite: store A issues a fence")
    (is (:ok? fb) "prerequisite: store B issues a fence")
    (when (and (:ok? fa) (:ok? fb))
      (let [fence-a (get-in fa [:result :fence])
            binding-a (transition-binding
                       (get-in fa [:result :context :resolved-review-authority-context/root])
                       (:state-root wa) (hash-ref "ada1") (hash-ref "ede1"))]
        (testing "fence issued by store A is unknown to store B"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store wb) fence-a binding-a nil nil))))
          (is (= (:head wb) (:head @(.state (:store wb))))))
        (testing "the reverse transplant is equally rejected"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store wa) (get-in fb [:result :fence]) nil nil nil)))))
        (testing "fence records pin their distinct issuance key sets"
          (let [rec-a (fence-record wa (:fence/id fence-a))
                rec-b (fence-record wb (:fence/id (get-in fb [:result :fence])))]
            (is (= (state/signer-key-set-root ks-a) (:signer-key-set/root rec-a)))
            (is (= (state/signer-key-set-root ks-b) (:signer-key-set/root rec-b)))
            (is (not= (:signer-key-set/root rec-a) (:signer-key-set/root rec-b)))))
        (testing
         "characterization: resolved contexts do not commit the key set, so the
          issued-fence registry is the barrier against interchange"
          (is (= (get-in fa [:result :context :resolved-review-authority-context/root])
                 (get-in fb [:result :context :resolved-review-authority-context/root]))))
        (testing "evaluation bases prove semantic key-set significance"
          (let [eb-a (get-in fa [:result :evaluation-basis])
                eb-b (get-in fb [:result :evaluation-basis])]
            (is (= state/evaluation-basis-schema (:artifact/schema eb-a)))
            (is (= (:resolved-review-authority-context/root eb-a)
                   (:resolved-review-authority-context/root eb-b))
                "same semantic context on both sides of the join")
            (is (not= (:authority-evaluation-basis/root eb-a)
                      (:authority-evaluation-basis/root eb-b))
                "different key sets yield different evaluation bases")))))))

;; ── transplant B: rotation after issuance stales outstanding fences ───────

(deftest key-set-rotation-rejects-finalisation-of-pre-rotation-fence
  (let [ks-1 (key-set ["k1" "k2"])
        ks-2 (key-set ["k2a" "k2b"])
        w (fresh-store (authenticated-material ks-1))
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issued while K1 applies")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            pre-basis-root (get-in issued [:result :evaluation-basis :authority-evaluation-basis/root])
            succ (successor-of w (authenticated-material ks-2))]
        (testing "rotating to K2 moves the head"
          (is (:published? (state/publish-successor!
                            (:store w) (:head w) (:envelope succ) (:material succ)))))
        (testing "pre-rotation fence cannot finalise against post-rotation store"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding
                            (:resolved-review-authority-context/root context)
                            (:state-root w) (:state-root succ) (hash-ref "ede1"))
                           (:envelope succ) (:material succ))))))
        (testing "the rotated store records K2, never K1"
          (let [reissued (issue-fence (assoc w :state-root (:state-root succ)
                                             :head (:authoritative-state-envelope/root (:envelope succ))
                                             :envelope (:envelope succ)))]
            (is (:ok? reissued))
            (when (:ok? reissued)
              (let [rec-post (fence-record w (get-in reissued [:result :fence :fence/id]))]
                (is (= (state/signer-key-set-root ks-2) (:signer-key-set/root rec-post))
                    "rotated store records K2, never K1")
                (is (not= pre-basis-root (:authority-evaluation-basis/root rec-post))
                    "post-rotation fences bind the K2 evaluation basis")))))))))

;; ── priority 9: forged / stale / consumed / context / pre-state fences ────

(deftest forged-stale-consumed-context-prestate-fences-rejected
  (let [w (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            pre (:state-root w)
            post (hash-ref "aa99")
            good-binding (transition-binding
                          (:resolved-review-authority-context/root context)
                          pre post (hash-ref "ede1"))]
        (testing "forged fence id"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) {:fence/id (str (java.util.UUID/randomUUID))}
                           good-binding nil nil)))))
        (testing "structurally invalid binding"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence (dissoc good-binding :artifact/schema) nil nil)))))
        (testing "pre-state mismatch"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding
                            (:resolved-review-authority-context/root context)
                            (hash-ref "e5e5e5") post (hash-ref "ede1"))
                           nil nil)))))
        (testing "foreign authority context in binding"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding (hash-ref "fcfcfc")
                                               pre post (hash-ref "ede1"))
                           nil nil)))))
        (testing "stale fence after unrelated successor publication"
          (let [succ (successor-of w (authenticated-material))]
            (state/publish-successor! (:store w) (:head w) (:envelope succ) (:material succ))
            (is (= :unknown-fence
                   (:reason (state/finalise-under-authority-fence!
                             (:store w) fence good-binding (:envelope succ) (:material succ)))))))))))

;; ── priority 10: atomic successor + binding + fence terminalization ───────

(deftest atomic-successor-binding-terminalization
  (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
        w (fresh-store material)
        issued (gac/verify-governed-authority-current
                (:store w) (admission-basis w) authorisation)]
    (is (:valid? issued) "prerequisite: authorised report issues a finalizable fence")
    (when (:valid? issued)
      (let [context (:resolved-review-authority-context issued)
            fence (:authority-fence issued)
            succ (successor-of w material)
            binding (transition-binding
                     (:resolved-review-authority-context/root context)
                     (:state-root w) (:state-root succ) (hash-ref "ede1"))
            store (:store w)
            result (state/finalise-under-authority-fence!
                    store fence binding (:envelope succ) (:material succ))
            snap @(.state store)
            rec (get-in snap [:issued-fences (:fence/id fence)])
            succ-root (:authoritative-state-envelope/root (:envelope succ))]
        (testing "finalisation succeeds and returns the terminal result"
          (is (:finalised? result))
          (is (= succ-root (:authoritative-state-envelope/root (:envelope result))))
          (is (= binding (:authority-binding result))))
        (testing "head, envelope index, material, and binding commit together"
          (is (= succ-root (:head snap)))
          (is (= succ-root (get-in snap [:by-state (:state-root succ)])))
          (is (= (:material succ) (get-in snap [:material (:state-root succ)])))
          (is (= binding (get-in snap [:authority-bindings succ-root]))))
        (testing "fence terminalized with binding and successor identity"
          (is (= :consumed (:status rec)))
          (is (= (:governed-authority-transition-binding/root binding)
                 (:transition-binding/root rec)))
          (is (= succ-root (:successor-envelope/root rec)))
          (is (= result (:result rec))))
        (testing "self-rooted governed-authority result receipt is indexed and verifies"
          (let [receipt (:governed-authority-result-receipt result)
                receipt-root (:governed-authority-result-receipt/root receipt)]
            (is (result-receipt/verify-receipt receipt))
            (is (= receipt (get-in snap [:governed-authority-result-receipts receipt-root])))
            (is (= receipt-root
                   (get-in snap [:governed-authority-result-receipt-by-binding
                                 (:governed-authority-transition-binding/root binding)])))
            (is (= (:authoritative-state-envelope/root (:envelope w))
                   (:pre-authoritative-state-envelope/root receipt)))
            (is (= succ-root (:post-authoritative-state-envelope/root receipt)))))
        (testing "exact retry replays the original terminal result"
          (is (= result (state/finalise-under-authority-fence!
                         store fence binding (:envelope succ) (:material succ)))))
        (testing "conflicting reuse of the consumed fence rejects"
          (let [conflicting (transition-binding
                             (:resolved-review-authority-context/root context)
                             (:state-root w) (:state-root succ) (hash-ref "dfd1"))]
            (is (= :fence-already-consumed
                   (:reason (state/finalise-under-authority-fence!
                             store fence conflicting (:envelope succ) (:material succ)))))))
        (testing "post-transition state resolves as the new head"
          (let [r (resolve* (assoc w :state-root (:state-root succ)
                                   :head succ-root :envelope (:envelope succ)))]
            (is (:resolved? r))
            (when (:resolved? r)
              (is (= succ-root (get-in r [:context :authority-state/root]))))))))))

;; ── regression: existing resolution semantics on authenticated fixtures ───

(deftest authenticated-state-resolution
  (let [s0 (hash-ref "b0")
        mat0 (authenticated-material)
        e0 (state/build-envelope (store-envelope s0 nil 0 mat0))
        store (state/new-store e0 mat0)
        w {:store store :state-root s0 :envelope e0
           :head (:authoritative-state-envelope/root e0)}
        b0 (admission-basis w)]
    (is (:resolved? (state/resolve-governed-authority-context store b0)))
    (let [s1 (hash-ref "c1")
          mat1 (authenticated-material)
          e1 (state/build-envelope
              (store-envelope s1 (:authoritative-state-envelope/root e0) 1 mat1))]
      (is (:published?
           (state/publish-successor!
            store (:authoritative-state-envelope/root e0) e1 mat1)))
      (is (= :state-not-at-required-head
             (:reason (state/resolve-governed-authority-context store b0))))
      (is (:resolved?
           (state/resolve-governed-authority-context
            store (v1-basis :transition-replay s0 (:authoritative-state-envelope/root e1)))))
      (is (:resolved?
           (state/resolve-governed-authority-context
            store (audit-basis-v2 s0 (:authoritative-state-envelope/root e1))))))))

(deftest current-admission-rejects-v1-basis
  (testing "V1 basis is rejected for live current-admission (live downgrade blocked)"
    (let [{:keys [store] :as w} (fresh-store)
          v1-admission (v1-basis :current-admission (:state-root w) (:head w))]
      (is (not (:valid? (resolution/validate-resolution-basis-any v1-admission)))
          "validate-resolution-basis-any rejects V1 for current-admission")
      (is (some #(re-find #"current-admission requires" %)
                (:errors (resolution/validate-resolution-basis-any v1-admission)))
          "rejection cites the current-admission v2 requirement")
      (is (= :resolution-basis-invalid
             (:reason (resolve-basis store v1-admission)))
          "state resolution rejects V1 current-admission at the basis gate")
      (is (:valid? (resolution/validate-resolution-basis-any
                    (v1-basis :transition-replay (:state-root w) (:head w))))
          "V1 remains accepted for transition-replay (historical compatibility)")
      (is (:valid? (resolution/validate-resolution-basis-any
                    (v1-basis :historical-audit (:state-root w) (:head w))))
          "V1 remains accepted for historical-audit (historical compatibility)"))))

(deftest rejects-state-and-round-substitution
  (let [w (fresh-store)]
    (is (= :state-unavailable
           (:reason (resolve* w (hash-ref "ffff") (:head w)))))
    (let [different-round-store
          (state/new-store (:envelope w)
                           (assoc (:material w) :review-round/hash (hash-ref "deadbeef")))]
      (is (= :round-not-found-at-state
             (:reason
              (try
                (state/resolve-governed-authority-context
                 different-round-store
                 (admission-basis (assoc w :store different-round-store)))
                (catch Exception e
                  {:reason ::unexpected-throw :throw (.getMessage e)}))))))))

(deftest finalisation-rejects-successor-fence-substitution-without-mutation
  (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
        w (fresh-store material)
        issued (gac/verify-governed-authority-current
                (:store w) (admission-basis w) authorisation)]
    (is (:valid? issued) "prerequisite: authorised report issues a finalizable fence")
    (when (:valid? issued)
      (let [context (:resolved-review-authority-context issued)
            fence (:authority-fence issued)
            successor (successor-of w material)
            binding (transition-binding
                     (:resolved-review-authority-context/root context)
                     (:state-root w) (:state-root successor) (hash-ref "ede1"))]
        (testing "successor execution state substitution is rejected without mutation"
          (let [before @(.state (:store w))
                substituted (assoc (:envelope successor)
                                   :execution/state-root (hash-ref "ab"))
                result (state/finalise-under-authority-fence!
                        (:store w) fence binding substituted (:material successor))]
            (is (= :fence-post-state-mismatch (:reason result)))
            (is (= before @(.state (:store w))))))
        (testing "invalid predecessor continuity is rejected without mutation"
          (let [before @(.state (:store w))
                substituted (assoc (:envelope successor)
                                   :publication/predecessor-root (hash-ref "foreign"))
                result (state/finalise-under-authority-fence!
                        (:store w) fence binding substituted (:material successor))]
            (is (= :fence-predecessor-mismatch (:reason result)))
            (is (= before @(.state (:store w))))))))))

;; ── B3: position-time-index as 5th material body ──────────────────────────

(deftest position-time-index-root-in-envelope
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "envelope carries position-time-index/root"
      (is (contains? envelope :position-time-index/root)))
    (testing "envelope root matches material position-time-index root"
      (is (= (:position-time-index/root material)
             (:position-time-index/root envelope))))
    (testing "envelope includes position-time-index/root in closed fields"
      (is (contains? state/envelope-fields :position-time-index/root)))))

(deftest resolved-context-includes-position-time-index
  (let [w (fresh-store)
        r (resolve* w)]
    (is (:resolved? r) "prerequisite: authenticated store resolves")
    (when (:resolved? r)
      (let [ctx (:context r)]
        (is (contains? ctx :position-time-index/root)
            "resolved context includes position-time-index/root")
        (is (= (:position-time-index/root (:material w))
               (:position-time-index/root ctx))
            "context position-time-index/root matches material")))))

(deftest fence-record-pins-position-time-index-root
  (let [w (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [fence (:fence (:result issued))
            rec (fence-record w (:fence/id fence))]
        (is (= (:position-time-index/root (:material w))
               (:position-time-index/root rec))
            "fence record pins position-time-index/root from issuance")))))

;; ── B3: rich signer-key-set entries ──────────────────────────────────────────

(deftest signer-key-set-uses-rich-entries
  (let [w (fresh-store)
        ks (:authority-material/signer-key-set (:material w))]
    (testing "entries vector is populated, not the legacy keys vector"
      (is (vector? (:signer-key-set/entries ks)))
      (is (seq (:signer-key-set/entries ks)))
      (is (not (contains? ks :signer-key-set/keys))))
    (testing "each entry has closed fields"
      (doseq [entry (:signer-key-set/entries ks)]
        (is (= #{:researcher/id :signing-key/id :signing-key/algorithm
                 :signing-key/public-key}
               (set (keys entry))))
        (is (= :ed25519 (:signing-key/algorithm entry)))
        (is (re-matches #"[0-9a-f]{64}" (:signing-key/public-key entry)))
        (is (= "r1" (:researcher/id entry)))))
    (testing "root is computed over the entries body"
      (is (= (state/signer-key-set-root ks)
             (:signer-key-set/root (:material w)))))))

(deftest signer-key-entry-requires-researcher-and-key-ids
  (let [entry {:researcher/id "r1"
               :signing-key/id "k1"
               :signing-key/algorithm :ed25519
               :signing-key/public-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}]
    (is (:valid? (state/validate-signer-key-set
                  {:artifact/schema state/signer-key-set-schema
                   :signer-key-set/entries [entry]})))))

(deftest signer-key-entry-rejects-unknown-fields
  (let [bad-entry {:researcher/id "r1"
                   :signing-key/id "k1"
                   :signing-key/algorithm :ed25519
                   :signing-key/public-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                   :signing-key/evil true}]
    (is (false? (:valid? (state/validate-signer-key-set
                          {:artifact/schema state/signer-key-set-schema
                           :signer-key-set/entries [bad-entry]}))))))

(deftest signer-key-entry-rejects-bad-public-key
  (let [bad-entry {:researcher/id "r1"
                   :signing-key/id "k1"
                   :signing-key/algorithm :ed25519
                   :signing-key/public-key "not-hex"}]
    (is (false? (:valid? (state/validate-signer-key-set
                          {:artifact/schema state/signer-key-set-schema
                           :signer-key-set/entries [bad-entry]}))))))

(deftest signer-key-set-rejects-duplicate-researcher-key-pairs
  (let [entry {:researcher/id "r1"
               :signing-key/id "k1"
               :signing-key/algorithm :ed25519
               :signing-key/public-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}
        ks {:artifact/schema state/signer-key-set-schema
            :signer-key-set/entries [entry entry]}]
    (is (false? (:valid? (state/validate-signer-key-set ks)))))
  (testing "same key-id under different researchers is allowed"
    (let [e1 {:researcher/id "r1"
              :signing-key/id "k1"
              :signing-key/algorithm :ed25519
              :signing-key/public-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}
          e2 {:researcher/id "r2"
              :signing-key/id "k1"
              :signing-key/algorithm :ed25519
              :signing-key/public-key "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}
          ks {:artifact/schema state/signer-key-set-schema
              :signer-key-set/entries [e1 e2]}]
      (is (:valid? (state/validate-signer-key-set ks))))))

;; ── B3: eligibility cross-check ──────────────────────────────────────────────

(deftest ineligible-signer-key-rejected-at-construction
  (let [gb (governance-body ["k1"])
        ks (key-set ["k1" "unknown-key"])]
    (testing "key-set references a key not in the governance body"
      (is (false? (:eligible? (state/signer-key-eligible-in-governance? ks gb)))
          "key 'unknown-key' is not eligible")),
    (let [material (assoc (authenticated-material (key-set ["k1"]))
                          :authority-material/signer-key-set ks
                          :signer-key-set/root (state/signer-key-set-root ks))]
      (is (= not-rooted-msg
             (new-store-error (:envelope (fresh-store)) material))
          "store construction rejects material with ineligible signer key"))))

(deftest eligible-key-lookup
  (let [gb (governance-body ["k1" "k2"])]
    (is (true? (boolean (governance/position-key-valid? gb "r1" "k1"))))
    (is (true? (boolean (governance/position-key-valid? gb "r1" "k2"))))
    (is (false? (boolean (governance/position-key-valid? gb "r1" "k3"))))))

;; ── B3: store-derived lookups ────────────────────────────────────────────────

(deftest store-derived-public-key-lookup
  (let [w (fresh-store)
        ks (:authority-material/signer-key-set (:material w))]
    (testing "lookup returns the public key for a known researcher/key pair"
      (is (= test-public-key
             (state/lookup-signing-public-key ks "r1" "k1"))))
    (testing "lookup returns nil for an unknown key-id"
      (is (nil? (state/lookup-signing-public-key ks "r1" "unknown"))))))

(deftest store-derived-position-acceptance-time-lookup
  (let [w (fresh-store)
        pti (:authority-material/position-time-index (:material w))]
    (testing "lookup returns nil for an empty index"
      (is (nil? (state/lookup-position-acceptance-time pti (hash-ref "aa")))))))

;; ── B3: closed-shape rejection of unknown material keys ──────────────────────

(deftest unknown-top-level-material-keys-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "extra top-level key in material is rejected"
      (is (= not-rooted-msg
             (new-store-error envelope (assoc material :extraneous :value)))))
    (testing "extra top-level key with runtime value is rejected by freeze"
      (is (= runtime-value-msg
             (new-store-error envelope
                              (assoc material :extraneous (fn [] :rogue)))))))
  (testing "extra nested key in signer-key-set body is rejected"
    (let [w (fresh-store)]
      (is (= not-rooted-msg
             (new-store-error (:envelope w)
                              (assoc-in (:material w)
                                        [:authority-material/signer-key-set :signer-key-set/entries 0 :rogue]
                                        true))))))
  (testing "extra nested key in position-time-index body is rejected"
    (let [w (fresh-store)]
      (is (= not-rooted-msg
             (new-store-error (:envelope w)
                              (assoc-in (:material w)
                                        [:authority-material/position-time-index :rogue]
                                        true)))))))

(deftest evaluation-basis-includes-position-time-index
  (let [w (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance succeeds")
    (when (:ok? issued)
      (let [eb (:evaluation-basis (:result issued))]
        (is (= state/evaluation-basis-schema (:artifact/schema eb)))
        (is (contains? eb :position-time-index/root))
        (is (= (:position-time-index/root (:material w))
               (:position-time-index/root eb)))))))

;; ── C4d/C4e: retained configuration semantics ─────────────────────────────

(defn- c4-configuration []
  (let [policy (semantics-policy/build-policy
                {:authority-semantics/root
                 (:governed-authority-semantics/root semantics/default-semantics)})]
    {:configuration (assoc genesis/chain-configuration-fixture
                           :configuration/schema genesis/chain-configuration-v2-schema
                           :authority-semantics-policy/root
                           (:authority-semantics-policy/root policy))
     :policy policy
     :semantics semantics/default-semantics}))

(defn- canonical-c4-c3b-fixture []
  (let [{c0 :configuration p0 :policy s0 :semantics} (c4-configuration)
        c0-root (genesis/chain-configuration-root c0)
        ;; The only currently supported semantics descriptor is S0; P1 therefore
        ;; selects the same descriptor while C1 changes a canonical sub-root.
        p1 (semantics-policy/build-policy
            {:authority-semantics/root (:governed-authority-semantics/root s0)})
        s1 s0
        c1 (assoc c0
                  :verifier-registry/root (hash-ref "c4c1")
                  :authority-semantics-policy/root (:authority-semantics-policy/root p1))
        c1-root (genesis/chain-configuration-root c1)
        transition {:transition/schema genesis/chain-configuration-transition-schema
                    :protocol/genesis-root genesis/protocol-genesis-fixture-root
                    :target {:target/type :chain-instance
                             :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
                    :configuration/parent-root c0-root
                    :configuration/new-root c1-root
                    :verifier-registry/root (:verifier-registry/root c1)
                    :epoch 2}
        transition-root (genesis/chain-configuration-transition-root transition)
        {:keys [material authorisation]} (authorised-material-and-authorisation transition-root)
        predecessor-material (material-for-configuration material c0-root)
        successor-material (material-for-configuration material c1-root)
        h0 (configuration-head/current-head (configuration-head/new-store c0-root 1))
        e0 (state/build-envelope-v2
            (assoc (store-envelope (hash-ref "c4e0") nil 0 predecessor-material)
                   :chain-configuration/root c0-root)
            h0)
        store (semantics-state/new-store-v2-with-authority-semantics
               e0 h0 predecessor-material c0 p0 s0)
        w {:store store
           :state-root (:execution/state-root e0)
           :head (:authoritative-state-envelope/root e0)}
        resolved (state/resolve-authority-material store (admission-basis w))
        witness {:predecessor-envelope e0
                 :predecessor-head-state h0
                 :predecessor-material predecessor-material
                 :configuration-transition transition
                 :authorisation authorisation
                 :resolution-basis (admission-basis w)
                 :resolved-review-authority-context-root
                 (get-in resolved [:context :resolved-review-authority-context/root])}
        evidence (c3a/build-verified-evidence witness)
        request {:authorization-evidence evidence
                 :authorization-witness witness
                 :transition transition
                 :parent-configuration c0
                 :successor-configuration c1
                 :successor-envelope
                 (assoc (store-envelope (hash-ref "c4e1")
                                        (:authoritative-state-envelope/root e0)
                                        1
                                        successor-material)
                        :chain-configuration/root c1-root)
                 :successor-material successor-material
                 :successor-semantics-policy p1
                 :successor-semantics s1}]
    {:store store :c0 c0 :p0 p0 :s0 s0 :c1 c1 :p1 p1 :s1 s1
     :c0-root c0-root :c1-root c1-root :e0 e0 :h0 h0
     :transition transition :witness witness :evidence evidence :request request}))

(defn- canonical-v3-c3b-fixture []
  (let [{c0-v2 :configuration p0 :policy s0 :semantics} (c4-configuration)
        entitlement-0 (entitlement-policy/build-policy
                       {:allocation-policy/root (hash-ref "a")
                        :asset/root (hash-ref "b")
                        :protocol-instance/root (hash-ref "c")
                        :custody-subject/root (hash-ref "d")
                        :custody-scope/root (hash-ref "e")
                        :allocation-entitlement/profile :allocation-entitlement/fixed-domain-v1})
        c0 (assoc c0-v2
                  :configuration/schema genesis/chain-configuration-v3-schema
                  :allocation-entitlement-policy/root
                  (:allocation-entitlement-policy/root entitlement-0))
        c0-root (genesis/chain-configuration-root c0)
        p1 (semantics-policy/build-policy
            {:authority-semantics/root (:governed-authority-semantics/root s0)})
        entitlement-1 (entitlement-policy/build-policy
                       {:allocation-policy/root (hash-ref "1")
                        :asset/root (hash-ref "2")
                        :protocol-instance/root (hash-ref "3")
                        :custody-subject/root (hash-ref "4")
                        :custody-scope/root (hash-ref "5")
                        :allocation-entitlement/profile :allocation-entitlement/fixed-domain-v1})
        c1 (assoc c0
                  :verifier-registry/root (hash-ref "6")
                  :authority-semantics-policy/root (:authority-semantics-policy/root p1)
                  :allocation-entitlement-policy/root
                  (:allocation-entitlement-policy/root entitlement-1))
        c1-root (genesis/chain-configuration-root c1)
        transition {:transition/schema genesis/chain-configuration-transition-schema
                    :protocol/genesis-root genesis/protocol-genesis-fixture-root
                    :target {:target/type :chain-instance
                             :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
                    :configuration/parent-root c0-root
                    :configuration/new-root c1-root
                    :verifier-registry/root (:verifier-registry/root c1)
                    :epoch 2}
        transition-root (genesis/chain-configuration-transition-root transition)
        {:keys [material authorisation]} (authorised-material-and-authorisation transition-root)
        predecessor-material (material-for-configuration material c0-root)
        successor-material (material-for-configuration material c1-root)
        h0 (configuration-head/current-head (configuration-head/new-store c0-root 1))
        e0 (state/build-envelope-v2
            (assoc (store-envelope (hash-ref "7") nil 0 predecessor-material)
                   :chain-configuration/root c0-root)
            h0)
        store (semantics-state/new-store-v3-with-authority-semantics-and-allocation-entitlement
               e0 h0 predecessor-material c0 p0 s0 entitlement-0)
        resolved (state/resolve-authority-material
                  store
                  (admission-basis {:state-root (:execution/state-root e0)
                                    :head (:authoritative-state-envelope/root e0)}))
        witness {:predecessor-envelope e0
                 :predecessor-head-state h0
                 :predecessor-material predecessor-material
                 :configuration-transition transition
                 :authorisation authorisation
                 :resolved-review-authority-context-root
                 (get-in resolved [:context :resolved-review-authority-context/root])}
        evidence (c3a/build-verified-evidence witness)
        request {:authorization-evidence evidence
                 :authorization-witness witness
                 :transition transition
                 :parent-configuration c0
                 :successor-configuration c1
                 :successor-envelope
                 (assoc (store-envelope (hash-ref "8")
                                        (:authoritative-state-envelope/root e0)
                                        1
                                        successor-material)
                        :chain-configuration/root c1-root)
                 :successor-material successor-material
                 :successor-semantics-policy p1
                 :successor-semantics s0
                 :successor-allocation-entitlement-policy entitlement-1}]
    {:store store :c0 c0 :c1 c1 :entitlement-0 entitlement-0
     :entitlement-1 entitlement-1 :c0-root c0-root :c1-root c1-root
     :request request}))

(deftest c3b-v3-activation-retains-governed-allocation-entitlement-policy
  (let [{:keys [store c0 entitlement-0 entitlement-1 c1 c1-root request]}
        (canonical-v3-c3b-fixture)
        result (c3b/activate-under-verified-transition-authorization! store request)
        snapshot @(.state store)
        resolved (semantics-state/resolve-current-authority-semantics-and-allocation-entitlement store)
        replay (c3b/activate-under-verified-transition-authorization! store request)]
    (is (:activated? result))
    (is (= c1-root (:chain-configuration/root (:envelope result))))
    (is (= c1 (:configuration resolved)))
    (is (= entitlement-1 (:allocation-entitlement-policy resolved)))
    (is (= entitlement-1 (get-in snapshot [:allocation-entitlement-policies
                                           (:allocation-entitlement-policy/root entitlement-1)])))
    ;; Historical C0/E0 bodies remain retained; current resolution is not used to
    ;; reinterpret prior configuration facts.
    (is (= c0 (get-in snapshot [:chain-configurations
                                (genesis/chain-configuration-root c0)])))
    (is (= entitlement-0 (get-in snapshot [:allocation-entitlement-policies
                                           (:allocation-entitlement-policy/root entitlement-0)])))
    (is (false? (:activated? replay)))
    (is (contains? #{:configuration-transition-authorization-invalid
                     :state-not-at-required-head}
                   (:reason replay)))))

(deftest c3b-v3-requires-and-verifies-entitlement-policy-body
  (doseq [[label mutate expected-reason]
          [[:missing #(dissoc % :successor-allocation-entitlement-policy)
            :successor-allocation-entitlement-incomplete]
           [:policy #(assoc % :successor-allocation-entitlement-policy
                            (assoc (:successor-allocation-entitlement-policy %)
                                   :asset/root (hash-ref "9")))
            :successor-allocation-entitlement-invalid]
           [:configuration #(assoc % :successor-configuration
                                   (assoc (:successor-configuration %)
                                          :allocation-entitlement-policy/root
                                          (hash-ref "f")))
            nil]]]
    (let [{:keys [store request]} (canonical-v3-c3b-fixture)
          before @(.state store)
          result (c3b/activate-under-verified-transition-authorization! store (mutate request))]
      (is (false? (:activated? result)) (str label " rejects"))
      (when expected-reason
        (is (= expected-reason (:reason result)) (str label " reason")))
      (is (= before @(.state store)) (str label " publishes nothing")))))

(deftest canonical-c4-c3b-retains-successor-authority-semantics-chain
  (let [{:keys [store c0 c1 p1 s1 c1-root e0 h0 transition witness evidence request]}
        (canonical-c4-c3b-fixture)
        result (c3b/activate-under-verified-transition-authorization! store request)
        e1 (:envelope result)
        h1 (:head-state result)
        current @(.state store)]
    (is (:activated? result))
    (is (= (:authoritative-state-envelope/root e1) (:head current)))
    (is (= h1 (get-in current [:configuration-head-states (:configuration-head/root e1)])))
    (is (= c1-root (:chain-configuration/root e1)))
    (is (= c1 (get-in current [:chain-configurations c1-root])))
    (is (= p1 (get-in current [:authority-semantics-policies
                               (:authority-semantics-policy/root c1)])))
    (is (= s1 (get-in current [:governed-authority-semantics
                               (:governed-authority-semantics/root s1)])))
    (is (:valid? (c3b/verify-activation-lineage
                  {:lineage (:lineage result)
                   :predecessor-envelope e0
                   :predecessor-head-state h0
                   :authorization-evidence evidence
                   :authorization-witness witness
                   :transition transition
                   :parent-configuration c0
                   :successor-configuration c1
                   :successor-envelope e1
                   :successor-head-state h1})))
    (is (= c1 (-> (semantics-state/resolve-current-authority-semantics store)
                  :configuration)))))

(deftest c4-c3b-successor-body-substitutions-publish-nothing
  (doseq [[label mutate]
          [[:configuration #(assoc % :successor-configuration
                                   (assoc (:successor-configuration %)
                                          :verifier-registry/root (hash-ref "c4c-sub")))]
           [:policy #(assoc % :successor-semantics-policy
                            (assoc (:successor-semantics-policy %)
                                   :authority-semantics-policy/root (hash-ref "c4p-sub")))]
           [:semantics #(assoc % :successor-semantics
                               (assoc (:successor-semantics %)
                                      :governed-authority-semantics/root (hash-ref "c4s-sub")))]]]
    (let [{:keys [store request]} (canonical-c4-c3b-fixture)
          before @(.state store)
          result (c3b/activate-under-verified-transition-authorization! store (mutate request))]
      (is (false? (:activated? result)) (str label " rejects"))
      (when (not= label :configuration)
        (is (= :successor-authority-semantics-invalid (:reason result))
            (str label " reports invalid C/P/S join")))
      (is (= before @(.state store)) (str label " publishes nothing")))))

(deftest c4-c3b-stale-cas-preserves-winner-snapshot
  (let [{:keys [store request]} (canonical-c4-c3b-fixture)
        original c3a/verify-evidence
        advanced? (atom false)
        result (with-redefs [c3a/verify-evidence
                             (fn [witness]
                               (when (compare-and-set! advanced? false true)
                                 (c3b/activate-under-verified-transition-authorization! store request))
                               (original witness))]
                 (c3b/activate-under-verified-transition-authorization! store request))
        winner-snapshot @(.state store)]
    (is @advanced?)
    (is (false? (:activated? result)))
    (is (= :state-not-at-required-head (:reason result)))
    (is (= winner-snapshot @(.state store)))
    (is (= 2 (count (:envelopes winner-snapshot))))
    (is (= 1 (count (:activation-lineage winner-snapshot))))))

(deftest c4f-rejects-t1-after-real-c3b-advances-the-authoritative-head
  (let [{:keys [store c0 p0 s0 c1-root e0 request]} (canonical-c4-c3b-fixture)
        t1-semantics (semantics-state/resolve-current-authority-semantics store)
        t1-basis (admission-basis {:store store
                                   :state-root (:execution/state-root e0)
                                   :head (:authoritative-state-envelope/root e0)})
        t2 (c3b/activate-under-verified-transition-authorization! store request)
        before-resume @(.state store)
        resumed (semantics-state/evaluate-and-issue-current-authority-fence!
                 store t1-basis (:authorisation (:authorization-witness request)))]
    (is (:resolved? t1-semantics))
    (is (= c0 (:configuration t1-semantics)))
    (is (= p0 (:policy t1-semantics)))
    (is (= s0 (:semantics t1-semantics)))
    (is (:activated? t2))
    (is (= c1-root (:chain-configuration/root (:envelope t2))))
    (is (= :state-not-at-required-head (:reason resumed)))
    (is (false? (:valid? resumed)))
    (is (= before-resume @(.state store)))
    (is (empty? (:issued-fences @(.state store))))))

(deftest c4-store-retains-and-resolves-current-configuration-semantics
  (let [{:keys [configuration policy semantics]} (c4-configuration)
        configuration-root (genesis/chain-configuration-root configuration)
        material (assoc (authenticated-material) :chain-configuration/root configuration-root)
        h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
        envelope (state/build-envelope-v2 (assoc (store-envelope (hash-ref "c4e") nil 0 material)
                                                 :chain-configuration/root configuration-root)
                                          h0)
        store (semantics-state/new-store-v2-with-authority-semantics
               envelope h0 material configuration policy semantics)
        resolved (semantics-state/resolve-current-authority-semantics store)]
    (is (:resolved? resolved))
    (is (= configuration (:configuration resolved)))
    (is (= policy (:policy resolved)))
    (is (= semantics (:semantics resolved)))
    (is (= configuration-root (:configuration/root resolved)))
    (is (= (:governed-authority-semantics/root semantics) (:semantics/root resolved)))))

(deftest direct-cps-substitution-cannot-issue-a-fence
  (let [{:keys [store c0 p0 s0 e0 request]} (canonical-c4-c3b-fixture)
        w {:store store
           :state-root (:execution/state-root e0)
           :head (:authoritative-state-envelope/root e0)}
        authorisation (:authorisation (:authorization-witness request))]
    (doseq [[label supplied-semantics supplied-provenance]
            [[:semantics
              (assoc s0 :governed-authority-semantics/root (hash-ref "substituted-s"))
              {:chain-configuration/root (genesis/chain-configuration-root c0)
               :authority-semantics-policy/root (:authority-semantics-policy/root p0)}]
             [:policy s0
              {:chain-configuration/root (genesis/chain-configuration-root c0)
               :authority-semantics-policy/root (hash-ref "substituted-p")}]
             [:configuration s0
              {:chain-configuration/root (hash-ref "substituted-c")
               :authority-semantics-policy/root (:authority-semantics-policy/root p0)}]]]
      (let [before @(.state store)
            result (state/evaluate-and-issue-finalizable-authority-fence!
                    store (admission-basis w) authorisation supplied-semantics
                    supplied-provenance)]
        (is (false? (:valid? result)) (str label " is rejected"))
        (is (= :authority-semantics-provenance-invalid (:reason result))
            (str label " reports provenance failure"))
        (is (nil? (:authority-fence result)) (str label " issues no fence"))
        (is (= before @(.state store)) (str label " mutates nothing"))))))

(deftest c4f-issued-fence-commits-store-derived-semantics
  (let [{:keys [configuration policy semantics]} (c4-configuration)
        configuration-root (genesis/chain-configuration-root configuration)
        {:keys [material authorisation]} (authorised-material-and-authorisation)
        material (assoc material :chain-configuration/root configuration-root)
        h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
        envelope (state/build-envelope-v2 (assoc (store-envelope (hash-ref "c4f") nil 0 material)
                                                 :chain-configuration/root configuration-root)
                                          h0)
        store (semantics-state/new-store-v2-with-authority-semantics
               envelope h0 material configuration policy semantics)
        w {:store store :state-root (:execution/state-root envelope)
           :head (:authoritative-state-envelope/root envelope)}
        issued (semantics-state/evaluate-and-issue-current-authority-fence!
                store (admission-basis w) authorisation)
        record (fence-record w (get-in issued [:authority-fence :fence/id]))]
    (is (:valid? issued))
    (is (= (:governed-authority-semantics/root semantics)
           (:authority-semantics/root issued)))
    (is (= (:authority-semantics/root issued)
           (:authority-semantics/root record)))
    (is (= configuration-root (:chain-configuration/root record)))
    (is (= (:authority-semantics-policy/root policy)
           (:authority-semantics-policy/root record)))))

(declare sample-authorisation)

(deftest d1-authoritative-configuration-consumer-issues-only-c4f-fences
  (testing "a valid C4 store issues a fence bound to retained C/P/S"
    (let [{:keys [configuration policy semantics]} (c4-configuration)
          configuration-root (genesis/chain-configuration-root configuration)
          {:keys [material authorisation]} (authorised-material-and-authorisation)
          material (assoc material :chain-configuration/root configuration-root)
          h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
          envelope (state/build-envelope-v2
                    (assoc (store-envelope (hash-ref "d1c4") nil 0 material)
                           :chain-configuration/root configuration-root)
                    h0)
          store (semantics-state/new-store-v2-with-authority-semantics
                 envelope h0 material configuration policy semantics)
          w {:store store
             :state-root (:execution/state-root envelope)
             :head (:authoritative-state-envelope/root envelope)}
          issued (gac/verify-governed-authority-current-under-authoritative-configuration
                  store (admission-basis w) authorisation)
          record (fence-record w (get-in issued [:authority-fence :fence/id]))]
      (is (:valid? issued))
      (is (some? (:authority-fence issued)))
      (is (= configuration-root (:chain-configuration/root record)))
      (is (= (:authority-semantics-policy/root policy)
             (:authority-semantics-policy/root record)))
      (is (= (:governed-authority-semantics/root semantics)
             (:authority-semantics/root record)))))
  (testing "legacy stores fail closed without issuing a fence"
    (let [w (fresh-store)
          result (gac/verify-governed-authority-current-under-authoritative-configuration
                  (:store w) (admission-basis w) (sample-authorisation))]
      (is (false? (:valid? result)))
      (is (= :authority-semantics-unavailable (:reason result)))
      (is (nil? (:authority-fence result)))
      (is (empty? (:issued-fences @(.state (:store w)))))))
  (testing "C4 stores with unavailable retained semantics fail closed without a fence"
    (let [{:keys [configuration policy semantics]} (c4-configuration)
          configuration-root (genesis/chain-configuration-root configuration)
          material (assoc (authenticated-material) :chain-configuration/root configuration-root)
          h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
          envelope (state/build-envelope-v2
                    (assoc (store-envelope (hash-ref "d1aa") nil 0 material)
                           :chain-configuration/root configuration-root)
                    h0)
          store (semantics-state/new-store-v2-with-authority-semantics
                 envelope h0 material configuration policy semantics)
          _ (swap! (.state store) assoc :governed-authority-semantics {})
          result (gac/verify-governed-authority-current-under-authoritative-configuration
                  store
                  (admission-basis {:store store
                                    :state-root (:execution/state-root envelope)
                                    :head (:authoritative-state-envelope/root envelope)})
                  (sample-authorisation))]
      (is (false? (:valid? result)))
      (is (= :authority-semantics-unavailable (:reason result)))
      (is (nil? (:authority-fence result)))
      (is (empty? (:issued-fences @(.state store))))))
  (testing "the public entry point delegates directly to C4f, not legacy consumers or activation"
    (let [calls (atom [])
          sentinel {:valid? true :authority-fence {:fence/id "d1-sentinel"}}
          store ::store
          basis ::basis
          authorisation ::authorisation]
      (with-redefs [semantics-state/evaluate-and-issue-current-authority-fence!
                    (fn [& args] (reset! calls args) sentinel)
                    gac/verify-governed-authority-current
                    (fn [& _] (throw (ex-info "legacy consumer invoked" {})))
                    c3b/activate-under-verified-transition-authorization!
                    (fn [& _] (throw (ex-info "control-plane activation invoked" {})))]
        (is (= sentinel
               (gac/verify-governed-authority-current-under-authoritative-configuration
                store basis authorisation)))
        (is (= [store basis authorisation] @calls))))))

(deftest c4f-rejects-legacy-or-unavailable-semantics-without-a-fence
  (let [w (fresh-store)
        result (semantics-state/evaluate-and-issue-current-authority-fence!
                (:store w) (admission-basis w) (sample-authorisation))]
    (is (false? (:valid? result)))
    (is (= :authority-semantics-unavailable (:reason result)))
    (is (nil? (:authority-fence result))))
  (let [{:keys [configuration policy semantics]} (c4-configuration)
        configuration-root (genesis/chain-configuration-root configuration)
        material (assoc (authenticated-material) :chain-configuration/root configuration-root)
        h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
        envelope (state/build-envelope-v2 (assoc (store-envelope (hash-ref "c4d") nil 0 material)
                                                 :chain-configuration/root configuration-root)
                                          h0)
        store (semantics-state/new-store-v2-with-authority-semantics envelope h0 material configuration policy semantics)
        _ (swap! (.state store) assoc :governed-authority-semantics {})
        result (semantics-state/evaluate-and-issue-current-authority-fence!
                store (admission-basis {:store store :state-root (:execution/state-root envelope)
                                        :head (:authoritative-state-envelope/root envelope)})
                (sample-authorisation))]
    (is (false? (:valid? result)))
    (is (= :authority-semantics-unavailable (:reason result)))
    (is (empty? (:issued-fences @(.state store)))))
  (testing "retained configuration or policy substitution also fails closed"
    (let [{:keys [configuration policy semantics]} (c4-configuration)
          configuration-root (genesis/chain-configuration-root configuration)
          material (assoc (authenticated-material) :chain-configuration/root configuration-root)
          h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
          envelope (state/build-envelope-v2 (assoc (store-envelope (hash-ref "c4d") nil 0 material)
                                                   :chain-configuration/root configuration-root) h0)
          store (semantics-state/new-store-v2-with-authority-semantics envelope h0 material configuration policy semantics)]
      (swap! (.state store) assoc :authority-semantics-policies {})
      (is (false? (:resolved? (semantics-state/resolve-current-authority-semantics store))))
      (is (empty? (:issued-fences @(.state store)))))))

(deftest v3-resolution-uses-current-retained-configuration-and-policy-bodies
  (let [authority-policy (semantics-policy/build-policy
                          {:authority-semantics/root
                           (:governed-authority-semantics/root semantics/default-semantics)})
        entitlement (entitlement-policy/build-policy
                     {:allocation-policy/root (hash-ref "a")
                      :asset/root (hash-ref "b")
                      :protocol-instance/root (hash-ref "c")
                      :custody-subject/root (hash-ref "d")
                      :custody-scope/root (hash-ref "e")
                      :allocation-entitlement/profile :allocation-entitlement/fixed-domain-v1})
        configuration (assoc genesis/chain-configuration-fixture
                             :configuration/schema genesis/chain-configuration-v3-schema
                             :authority-semantics-policy/root
                             (:authority-semantics-policy/root authority-policy)
                             :allocation-entitlement-policy/root
                             (:allocation-entitlement-policy/root entitlement))
        configuration-root (genesis/chain-configuration-root configuration)
        material (assoc (authenticated-material) :chain-configuration/root configuration-root)
        h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
        envelope (state/build-envelope-v2
                  (assoc (store-envelope (hash-ref "f") nil 0 material)
                         :chain-configuration/root configuration-root)
                  h0)
        store (semantics-state/new-store-v3-with-authority-semantics-and-allocation-entitlement
               envelope h0 material configuration authority-policy semantics/default-semantics entitlement)]
    (is (= entitlement
           (:allocation-entitlement-policy
            (semantics-state/resolve-current-authority-semantics-and-allocation-entitlement store))))
    (swap! (.state store)
           assoc-in [:chain-configurations configuration-root]
           (assoc configuration :allocation-entitlement-policy/root (hash-ref "substituted")))
    (is (= :allocation-entitlement-unavailable
           (:reason (semantics-state/resolve-current-authority-semantics-and-allocation-entitlement store))))
    (swap! (.state store)
           assoc-in [:chain-configurations configuration-root] configuration)
    (swap! (.state store)
           assoc-in [:allocation-entitlement-policies
                     (:allocation-entitlement-policy/root entitlement)]
           (assoc entitlement :allocation-entitlement-policy/root (hash-ref "mismatch")))
    (is (= :allocation-entitlement-unavailable
           (:reason (semantics-state/resolve-current-authority-semantics-and-allocation-entitlement store))))))

(deftest c4-store-rejects-transplanted-configuration-policy-or-semantics
  (let [{:keys [configuration policy semantics]} (c4-configuration)
        configuration-root (genesis/chain-configuration-root configuration)
        material (assoc (authenticated-material) :chain-configuration/root configuration-root)
        h0 (configuration-head/current-head (configuration-head/new-store configuration-root 1))
        envelope (state/build-envelope-v2 (assoc (store-envelope (hash-ref "c4f") nil 0 material)
                                                 :chain-configuration/root configuration-root)
                                          h0)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (semantics-state/new-store-v2-with-authority-semantics
                  envelope h0 material
                  (assoc configuration :authority-semantics-policy/root (hash-ref "other-policy"))
                  policy semantics)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (semantics-state/new-store-v2-with-authority-semantics
                  envelope h0 material configuration policy
                  (assoc semantics :governed-authority-semantics/root (hash-ref "other-semantics")))))))

;; ── C1: strict authoritative consumer ────────────────────────────────────────

(defn- sample-authorisation []
  "Build a minimal force-authorisation artifact for testing the
   evaluate-authority-with-frozen-material entry point. The report will show
   :not-authorised because positions are not actually valid, but the
   evaluator must run without error on B3 material only."
  {:artifact/schema "force-authorisation.v1"
   :authorisation/id "auth-1"
   :authorisation/request-root (hash-ref "req1")
   :authorisation/review-round {:review-round/hash round-hash-ref}
   :authorisation/target {:target/proposed-content-root (hash-ref "tgt1")}
   :authorisation/decision-references []})

(deftest c1-v1-basis-rejected-by-authoritative-consumer
  (testing "V1 resolution basis is rejected — V2 basis required"
    (let [w (fresh-store)
          v1-basis (v1-basis :current-admission (:state-root w) (:head w))
          result (gac/verify-governed-authority-current
                  (:store w) v1-basis (sample-authorisation))]
      (is (false? (:valid? result))
          "V1 basis does not yield a resolved material store"))))

(deftest c25-state-api-issues-finalizable-fence-only-for-authorised-report
  (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
        w (fresh-store material)
        result (state/evaluate-and-issue-finalizable-authority-fence!
                (:store w) (admission-basis w) authorisation)
        fence (:authority-fence result)
        rec (when fence (fence-record w (:fence/id fence)))]
    (is (= :authorised (get-in result [:authority-report :authority-status])))
    (is (some? fence))
    (is (= (:authority-report-root result) (:authority-report/root rec)))))

(deftest c1-authorised-report-gets-finalizable-fence
  (testing "C1 emits a fence only after the frozen-material report is authorised"
    (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
          w (fresh-store material)
          result (gac/verify-governed-authority-current
                  (:store w) (admission-basis w) authorisation)
          fence (:authority-fence result)
          rec (when fence (fence-record w (:fence/id fence)))]
      (is (:valid? result))
      (is (= :authorised (get-in result [:authority-report :authority-status])))
      (is (some? fence) "only an authorised report receives a finalizable fence")
      (is (= (:authority-report-root result) (:authority-report/root rec))
          "the issued-fence record commits the evaluated authority report root"))))

(deftest c1-v2-basis-consumes-frozen-material-only
  (testing "evaluate-authority-with-frozen-material derives lookups from the
            material bodies, not from external callbacks"
    (let [w (fresh-store)
          material (:material w)
          result (state/evaluate-authority-with-frozen-material
                  {:authorisation (sample-authorisation)
                   :review-round (:authority-material/review-round material)
                   :review-governance (:authority-material/review-governance material)
                   :position-time-index (:authority-material/position-time-index material)
                   :signer-key-set (:authority-material/signer-key-set material)})]
      (is (map? result) "report is a map")
      (is (contains? result :authority-status)
          "report contains authority-status"))))

(defn- frozen-evaluation-inputs [material authorisation]
  {:authorisation authorisation
   :review-round (:authority-material/review-round material)
   :review-governance (:authority-material/review-governance material)
   :position-time-index (:authority-material/position-time-index material)
   :signer-key-set (:authority-material/signer-key-set material)})

(deftest c4a-v1-semantics-dispatch-conforms-to-frozen-material-evaluator
  (testing "the closed V1 descriptor dispatches exactly the legacy C1 evaluator"
    (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
          inputs (frozen-evaluation-inputs material authorisation)
          direct (state/evaluate-authority-with-frozen-material inputs)
          dispatched (semantics/evaluate-authority-with-semantics
                      semantics/default-semantics inputs)]
      (is (= :authorised (:authority-status direct)))
      (is (= direct (dissoc dispatched :authority-semantics/root
                            :authority-semantics-valid?)))
      (is (= (:governed-authority-semantics/root semantics/default-semantics)
             (:authority-semantics/root dispatched)))
      (is (true? (:authority-semantics-valid? dispatched)))))
  (testing "not-authorised evaluations retain their existing semantics"
    (let [w (fresh-store)
          inputs (frozen-evaluation-inputs (:material w) (sample-authorisation))
          direct (state/evaluate-authority-with-frozen-material inputs)
          dispatched (semantics/evaluate-authority-with-semantics
                      semantics/default-semantics inputs)]
      (is (= :not-authorised (:authority-status direct)))
      (is (= direct (dissoc dispatched :authority-semantics/root
                            :authority-semantics-valid?)))))
  (testing "tampered signed evidence stays on the existing invalid-evidence path"
    (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
          tampered (update authorisation :authorisation/decision-references
                           (fn [positions]
                             (mapv #(assoc % :decision/hash
                                           (hash-ref "c4a-tampered-decision"))
                                   positions)))
          inputs (frozen-evaluation-inputs material tampered)
          direct (state/evaluate-authority-with-frozen-material inputs)
          dispatched (semantics/evaluate-authority-with-semantics
                      semantics/default-semantics inputs)]
      (is (= :not-authorised (:authority-status direct)))
      (is (= direct (dissoc dispatched :authority-semantics/root
                            :authority-semantics-valid?))))))

(deftest c4a-semantics-dispatch-fails-closed-for-unrecognized-or-unsupported-descriptors
  (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
        inputs (frozen-evaluation-inputs material authorisation)
        unavailable (fn [descriptor]
                      (semantics/evaluate-authority-with-semantics descriptor inputs))
        with-root (fn [descriptor]
                    (assoc descriptor
                           :governed-authority-semantics/root
                           (semantics/semantics-root descriptor)))]
    (testing "unknown resolver roots cannot select an implementation"
      (let [result (unavailable
                    (with-root (assoc semantics/default-semantics
                                      :authority-resolver/root (hash-ref "c4a-unknown"))))]
        (is (= :not-authorised (:authority-status result)))
        (is (false? (:authority-semantics-valid? result)))
        (is (= [:authority-semantics-unavailable] (:authority/reasons result)))))
    (testing "a rooted but unsupported evaluator profile cannot dispatch"
      (let [result (unavailable
                    (with-root (assoc semantics/default-semantics
                                      :evaluator/profile :governed-authority/other-v1)))]
        (is (false? (:authority-semantics-valid? result)))))
    (testing "a mismatched committed root cannot transplant a valid profile"
      (let [result (unavailable (assoc semantics/default-semantics
                                       :governed-authority-semantics/root (hash-ref "c4a-root-mismatch")))]
        (is (false? (:authority-semantics-valid? result)))))
    (testing "an unrooted candidate is not dispatchable"
      (let [result (unavailable (dissoc semantics/default-semantics
                                        :governed-authority-semantics/root))]
        (is (false? (:authority-semantics-valid? result)))))))

(deftest c1-invalid-report-gets-no-fence-and-cannot-finalise
  (testing "C1 does not expose the resolution fence for a non-authorised report"
    (let [w (fresh-store)
          basis (admission-basis w)
          result (gac/verify-governed-authority-current
                  (:store w) basis (sample-authorisation))]
      (is (false? (:valid? result)))
      (is (= :not-authorised (get-in result [:authority-report :authority-status])))
      (is (nil? (:authority-fence result)))
      (is (= :authority-not-authorised
             (:reason (gac/finalise-governed-authority-current!
                       (:store w) result nil nil nil)))))))

;; ── C2: fenced finalization entry point ────────────────────────────────────────

(deftest c2-invalid-result-rejected
  (testing "finalise-governed-authority-current rejects a non-authorised C1 result"
    (let [w (fresh-store)
          result (gac/verify-governed-authority-current
                  (:store w) (admission-basis w) (sample-authorisation))]
      (is (= :authority-not-authorised
             (:reason (gac/finalise-governed-authority-current!
                       (:store w) result nil nil nil)))))))

(deftest c2-authorised-fence-finalises-successor
  (testing "the finalizable fence from an authorised C1 report finalises"
    (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
          w (fresh-store material)
          result (gac/verify-governed-authority-current
                  (:store w) (admission-basis w) authorisation)
          context (:resolved-review-authority-context result)
          succ (successor-of w material)
          binding (transition-binding
                   (:resolved-review-authority-context/root context)
                   (:state-root w) (:state-root succ) (hash-ref "ede1"))
          finalised (gac/finalise-governed-authority-current!
                     (:store w) result binding (:envelope succ) (:material succ))]
      (is (:valid? result))
      (is (:finalised? finalised)))))

(deftest c25-observation-handles-are-not-authority-fences
  (let [w (fresh-store)
        resolved (state/resolve-authority-material (:store w) (admission-basis w))]
    (is (:resolved? resolved))
    (is (some? (:resolution-handle resolved)))
    (is (nil? (:authority-fence resolved)))
    (is (empty? (:issued-fences @(.state (:store w)))))
    (is (= 1 (count (:observed-resolutions @(.state (:store w))))))))

(deftest c25-stale-evaluation-cannot-issue-finalizable-fence
  (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
        w (fresh-store material)
        successor (successor-of w material)
        original state/evaluate-authority-with-frozen-material
        published? (atom false)
        result (with-redefs [state/evaluate-authority-with-frozen-material
                             (fn [inputs]
                               (reset! published?
                                       (:published?
                                        (state/publish-successor!
                                         (:store w) (:head w)
                                         (:envelope successor) (:material successor))))
                               (original inputs))]
                 (state/evaluate-and-issue-finalizable-authority-fence!
                  (:store w) (admission-basis w) authorisation))]
    (is @published?)
    (is (false? (:valid? result)))
    (is (= :state-not-at-required-head (:reason result)))
    (is (nil? (:authority-fence result)))
    (is (empty? (:issued-fences @(.state (:store w)))))
    (is (= 1 (count (:observed-resolutions @(.state (:store w))))))))

(deftest c2-exact-retry-versus-conflicting-reuse
  (testing "an authorised C1 fence replays exactly but rejects conflicting reuse"
    (let [{:keys [material authorisation]} (authorised-material-and-authorisation)
          w (fresh-store material)
          result (gac/verify-governed-authority-current
                  (:store w) (admission-basis w) authorisation)
          context (:resolved-review-authority-context result)
          succ (successor-of w material)
          binding (transition-binding
                   (:resolved-review-authority-context/root context)
                   (:state-root w) (:state-root succ) (hash-ref "ede1"))]
      (testing "exact retry returns the original result"
        (let [r1 (gac/finalise-governed-authority-current!
                  (:store w) result binding (:envelope succ) (:material succ))
              r2 (gac/finalise-governed-authority-current!
                  (:store w) result binding (:envelope succ) (:material succ))]
          (is (= r1 r2))))
      (testing "conflicting reuse is rejected"
        (let [succ2 (successor-of w material)
              conflicting (transition-binding
                           (:resolved-review-authority-context/root context)
                           (:state-root w) (:state-root succ2) (hash-ref "dfd1"))]
          (is (= :fence-already-consumed
                 (:reason (gac/finalise-governed-authority-current!
                           (:store w) result conflicting
                           (:envelope succ2) (:material succ2))))))))))

(defn- d4-successor [fixture]
  (let [store (:store fixture)
        e1 (get-in @(.state store) [:envelopes (:head @(.state store))])
        material (get-in @(.state store) [:material (:execution/state-root e1)])]
    {:envelope (assoc (store-envelope (hash-ref "d4e2")
                                      (:authoritative-state-envelope/root e1)
                                      (inc (:publication/sequence e1))
                                      material)
                      :artifact/schema state/envelope-v2-schema
                      :chain-configuration/root (:chain-configuration/root e1))
     :material material}))

(deftest d4-v2-stale-finalisation-cas-leaves-no-t1-residue
  (let [{:keys [store request]} (canonical-c4-c3b-fixture)
        activation (c3b/activate-under-verified-transition-authorization! store request)
        e1 (:envelope activation)
        w {:store store
           :state-root (:execution/state-root e1)
           :head (:authoritative-state-envelope/root e1)}
        authorisation (:authorisation (:authorization-witness request))
        t1-issued
        (gac/verify-governed-authority-current-under-authoritative-configuration
         store (admission-basis w) authorisation)
        t2-issued
        (gac/verify-governed-authority-current-under-authoritative-configuration
         store (admission-basis w) authorisation)
        t1-successor (d4-successor {:store store})
        t2-successor (update (d4-successor {:store store})
                             :envelope assoc :execution/state-root (hash-ref "d4a2"))
        t1-binding
        (transition-binding
         (get-in t1-issued [:resolved-review-authority-context
                            :resolved-review-authority-context/root])
         (:execution/state-root e1) (:execution/state-root (:envelope t1-successor))
         (hash-ref "d4a1"))
        t2-binding
        (transition-binding
         (get-in t2-issued [:resolved-review-authority-context
                            :resolved-review-authority-context/root])
         (:execution/state-root e1) (:execution/state-root (:envelope t2-successor))
         (hash-ref "d4b2"))
        original-build state/build-envelope-v2
        advanced? (atom false)
        after-t2 (atom nil)
        t1-result
        (with-redefs [state/build-envelope-v2
                      (fn [envelope head-state]
                        (when (compare-and-set! advanced? false true)
                          (let [t2-result
                                (gac/finalise-governed-authority-current-under-authoritative-configuration!
                                 store t2-issued t2-binding
                                 (:envelope t2-successor) (:material t2-successor))]
                            (is (:finalised? t2-result) "T2 advances authoritatively")
                            (reset! after-t2 @(.state store))))
                        (original-build envelope head-state))]
          (gac/finalise-governed-authority-current-under-authoritative-configuration!
           store t1-issued t1-binding
           (:envelope t1-successor) (:material t1-successor)))]
    (is (:valid? t1-issued) "T1 has an E1-issued fence")
    (is (:valid? t2-issued) "T2 has an E1-issued fence")
    (is @advanced? "T2 interleaves immediately before T1's final CAS")
    (is (= :state-not-at-required-head (:reason t1-result)))
    (is (= @after-t2 @(.state store)) "T1 leaves the post-T2 store unchanged")
    (is (nil? (get-in @after-t2 [:envelopes
                                 (:authoritative-state-envelope/root
                                  (original-build (:envelope t1-successor)
                                                  (get-in @after-t2
                                                          [:configuration-head-states
                                                           (:configuration-head/root e1)])))])))
    (is (nil? (get-in @after-t2 [:material (:execution/state-root (:envelope t1-successor))])))
    (is (nil? (get-in @after-t2 [:governed-authority-result-receipt-by-binding
                                 (:governed-authority-transition-binding/root t1-binding)])))
    (is (= :issued
           (get-in @after-t2 [:issued-fences
                              (get-in t1-issued [:authority-fence :fence/id]) :status])))))

(deftest d4-finalisation-retains-current-authoritative-configuration
  (let [{:keys [store c1 p1 s1 request]} (canonical-c4-c3b-fixture)
        activation (c3b/activate-under-verified-transition-authorization! store request)
        e1 (:envelope activation)
        w {:store store :state-root (:execution/state-root e1)
           :head (:authoritative-state-envelope/root e1)}
        issued (gac/verify-governed-authority-current-under-authoritative-configuration
                store (admission-basis w) (:authorisation (:authorization-witness request)))
        successor (d4-successor {:store store})
        binding (transition-binding
                 (get-in issued [:resolved-review-authority-context
                                 :resolved-review-authority-context/root])
                 (:execution/state-root e1) (:execution/state-root (:envelope successor))
                 (hash-ref "d4"))
        result (gac/finalise-governed-authority-current-under-authoritative-configuration!
                store issued binding (:envelope successor) (:material successor))
        receipt (:governed-authority-result-receipt result)
        backend (cas/create-store (str (java.nio.file.Files/createTempDirectory
                                        "d4-receipt-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))))
        dependencies {(genesis/chain-configuration-root c1) c1
                      (:authority-semantics-policy/root p1) p1
                      (:governed-authority-semantics/root s1) s1}]
    (is (:valid? issued))
    (is (:finalised? result))
    (is (= state/envelope-v2-schema (get-in result [:envelope :artifact/schema])))
    (is (= (:configuration-head/root e1) (get-in result [:envelope :configuration-head/root])))
    (is (= (:chain-configuration/root e1) (get-in result [:envelope :chain-configuration/root])))
    (is (result-receipt/verify-receipt receipt))
    (receipt-store/persist-receipt! backend receipt dependencies)
    (is (= receipt (receipt-store/read-receipt!
                    (cas/create-store (:root backend))
                    (:governed-authority-result-receipt/root receipt) dependencies)))
    (is (= result
           (gac/finalise-governed-authority-current-under-authoritative-configuration!
            store issued binding (:envelope successor) (:material successor))))
    (doseq [[label mutate reason]
            [[:different-config #(assoc % :chain-configuration/root (hash-ref "da"))
              :successor-configuration-mismatch]
             [:v1-successor #(assoc % :artifact/schema state/envelope-schema)
              :authoritative-v2-successor-required]
             [:post-substitution #(assoc % :execution/state-root (hash-ref "db"))
              :fence-post-state-mismatch]
             [:predecessor-substitution #(assoc % :publication/predecessor-root (hash-ref "dc"))
              :fence-predecessor-mismatch]]]
      (let [{:keys [store request]} (canonical-c4-c3b-fixture)
            activation (c3b/activate-under-verified-transition-authorization! store request)
            e1 (:envelope activation)
            issued (gac/verify-governed-authority-current-under-authoritative-configuration
                    store (admission-basis {:store store :state-root (:execution/state-root e1)
                                            :head (:authoritative-state-envelope/root e1)})
                    (:authorisation (:authorization-witness request)))
            successor (d4-successor {:store store})
            binding (transition-binding
                     (get-in issued [:resolved-review-authority-context
                                     :resolved-review-authority-context/root])
                     (:execution/state-root e1) (:execution/state-root (:envelope successor))
                     (hash-ref "d5"))
            before @(.state store)
            rejected (gac/finalise-governed-authority-current-under-authoritative-configuration!
                      store issued binding (mutate (:envelope successor)) (:material successor))]
        (is (= reason (:reason rejected)) (name label))
        (is (= before @(.state store)) (str label " has no mutation"))))))

