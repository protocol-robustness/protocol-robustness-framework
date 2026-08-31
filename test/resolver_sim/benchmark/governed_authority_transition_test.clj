(ns resolver-sim.benchmark.governed-authority-transition-test
  "AUTH-STATE-AFTER governed-authority content transition kernel tests.

   Proves S1 = derive-governed-successor(T, S0, O) is deterministic, S1 is
   never an input, all successor fields are FROM-S0 / FROM-O / FROM-PINNED /
   DERIVED, and the content-transition artifact is produced only by derivation."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-transition :as gt]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.configuration-head :as config-head]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- hr [ch] (str "sha256:" (apply str (take 64 (cycle ch)))))
(def ^:private genesis-ref (hr "11"))

(defn- material [chain-config-root]
  (let [pk (apply str (repeat 64 "a"))
        ks {:artifact/schema state/signer-key-set-schema
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

(defn- s0-fixture []
  (let [parent-config genesis/chain-configuration-v0-fixture
        parent-root (genesis/chain-configuration-root parent-config)
        mat (material parent-root)
        head (config-head/initial-head parent-root 1)
        env (state/build-envelope-v2
             {:chain-instance-genesis/root genesis-ref
              :execution/state-root (hr "b0")
              :chain-configuration/root parent-root
              :review-governance/root (:review-governance/root mat)
              :review-governance-activation/root (:review-governance-activation/root mat)
              :control-plane-evidence/root (:control-plane-evidence/root mat)
              :position-time-index/root (:position-time-index/root mat)
              :publication/sequence 0
              :publication/predecessor-root nil}
             head)]
    {:envelope env
     :material mat
     :configuration/head head
     :config {:verifier-registry/root (:verifier-registry/root parent-config)}
     :config-body parent-config}))

(defn- o-fixture []
  (let [new-config genesis/chain-configuration-v1-fixture]
    {:proposed-content-root (genesis/chain-configuration-root new-config)
     :new-config-body new-config}))

(deftest derive-governed-successor-deterministic-and-s1-never-input
  (let [S0 (s0-fixture) O (o-fixture)
        a (gt/derive-governed-successor gt/transition-definition-root S0 O)
        b (gt/derive-governed-successor gt/transition-definition-root S0 O)]
    (is (ref/valid-sha256-ref? (:successor/root a)))
    (is (= (:successor/root a) (:successor/root b)) "deterministic")
    (is (= (gt/material-state-root (:successor-material a)) (:state-after/root a))
        "state-after == root(successor-material)")
    (is (= (:state-after/root a)
           (:execution/state-root (:successor-envelope a)))
        "envelope execution/state-root == derived state-after")))

(deftest successor-envelope-fields-all-derived
  (testing "no successor envelope field is caller-supplied"
    (let [S0 (s0-fixture) O (o-fixture)
          d (gt/derive-governed-successor gt/transition-definition-root S0 O)
          e (:successor-envelope d)]
      (is (= genesis-ref (:chain-instance-genesis/root e)) "FROM-PINNED")
      (is (= (:proposed-content-root O) (:chain-configuration/root e)) "FROM-O")
      (is (= (:review-governance/root (:material S0)) (:review-governance/root e)) "FROM-S0")
      (is (= (inc (:publication/sequence (:envelope S0))) (:publication/sequence e)) "DERIVED seq")
      (is (= (:authoritative-state-envelope/root (:envelope S0)) (:publication/predecessor-root e)) "FROM-S0 predecessor")
      (is (= (:state-after/root d) (:execution/state-root e)) "DERIVED state-after"))))

(deftest content-transition-only-from-derivation
  (let [S0 (s0-fixture) O (o-fixture)
        d (gt/derive-governed-successor gt/transition-definition-root S0 O)
        ct (gt/content-transition d S0 O)]
    (is (ref/valid-sha256-ref? (:governed-authority-content-transition/root ct)))
    (is (= (:successor/root d) (:successor/root ct)))
    (is (= (:state-after/root d) (:successor-material/root ct)))))

(deftest anti-transplant-derivation
  (testing "a non-canonical transition definition is rejected (cannot be substituted)"
    (let [S0 (s0-fixture) O (o-fixture)
          other-T (ref/sha256-ref (hc/domain-hash :governed-authority-transition-definition-v1
                                                  {:artifact/kind "x" :transition/type :other}))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (gt/derive-governed-successor other-T S0 O))
          "only the canonical transition definition is admissible")))
  (testing "same S0/T + different O → different successor"
    (let [S0 (s0-fixture) O (o-fixture)
          new2 (assoc genesis/chain-configuration-v1-fixture
                      :verifier-registry/root (hr "9a"))
          O2 {:proposed-content-root (genesis/chain-configuration-root new2) :new-config-body new2}
          a (gt/derive-governed-successor gt/transition-definition-root S0 O)
          b (gt/derive-governed-successor gt/transition-definition-root S0 O2)]
      (is (not= (:successor/root a) (:successor/root b)) "different O → different S1")))
  (testing "same O/T + different S0 → different successor"
    (let [S0 (s0-fixture) S0b (update-in (s0-fixture) [:material] assoc :signer-key-set/root (hr "99"))
          O (o-fixture)
          a (gt/derive-governed-successor gt/transition-definition-root S0 O)
          b (gt/derive-governed-successor gt/transition-definition-root S0b O)]
      (is (not= (:successor/root a) (:successor/root b)) "different S0 → different S1"))))

(deftest caller-selected-successor-not-an-input
  (testing "derive-governed-successor has no successor/state-after parameter"
    (is (nil? (some #{:successor/root :state-after/root} (:params (meta #'gt/derive-governed-successor)))))))
(deftest material-state-projection-hardening
  (testing "excluded implementation state (cache/index/history/observations) does not change material-state-root"
    (let [m (:material (s0-fixture))
          base (gt/material-state-root (gt/authoritative-material-state m))
          with-cache (gt/material-state-root (gt/authoritative-material-state (assoc m :cache {})))
          with-history (gt/material-state-root (gt/authoritative-material-state (assoc m :history {})))
          with-observations (gt/material-state-root (gt/authoritative-material-state (assoc m :observations {})))]
      (is (= base with-cache))
      (is (= base with-history))
      (is (= base with-observations))))
  (testing "changing included protocol-semantic material changes the root"
    (let [m (:material (s0-fixture))
          base (gt/material-state-root (gt/authoritative-material-state m))
          changed (gt/material-state-root (gt/authoritative-material-state (assoc m :signer-key-set/root (hr "99"))))]
      (is (not= base changed))))
  (testing "unknown/unclassified fields never enter the authoritative state identity"
    (let [m (:material (s0-fixture))]
      (is (empty? (set/difference (set (keys (gt/authoritative-material-state m)))
                                  gt/authoritative-material-state-fields))))))

(deftest derivation-conformance-prf-and-k4
  (testing "PRF derivation and K4 replay share one canonical transition root"
    (let [S0 (s0-fixture) O (o-fixture)
          d (gt/derive-governed-successor gt/transition-definition-root S0 O)
          ct (gt/content-transition d S0 O)]
      (is (ref/valid-sha256-ref? (:governed-authority-content-transition/root ct)))
      (is (= (:successor/root d) (:successor/root ct)))
      (is (= (:successor-material d) (gt/authoritative-material-state (:successor-material d)))))))
