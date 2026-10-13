(ns resolver-sim.configuration-head-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.configuration-head :as head]
            [resolver-sim.benchmark.authority-semantics-policy :as policy]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.genesis :as genesis]))

(defn- successor-config []
  (assoc genesis/chain-configuration-v0-fixture
         :verifier-registry/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

(defn- transition [parent new epoch]
  {:transition/schema genesis/chain-configuration-transition-schema
   :protocol/genesis-root genesis/protocol-genesis-fixture-root
   :target {:target/type :chain-instance
            :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
   :configuration/parent-root (genesis/chain-configuration-root parent)
   :configuration/new-root (genesis/chain-configuration-root new)
   :verifier-registry/root (:verifier-registry/root new)
   :epoch epoch})

(defn- v2-config [base]
  (assoc base
         :configuration/schema genesis/chain-configuration-v2-schema
         :authority-semantics-policy/root
         (:authority-semantics-policy/root
          (policy/build-policy
           {:authority-semantics/root
            (:governed-authority-semantics/root semantics/default-semantics)}))))

(deftest versioned-configuration-roots-flow-through-pure-head-derivation
  (let [c0 genesis/chain-configuration-v0-fixture
        c1 (v2-config (successor-config))
        t (transition c0 c1 2)
        h0 (head/current-head (head/new-store (genesis/chain-configuration-root c0) 1))
        derived (head/derive-successor-head h0 t c0 c1)]
    (is (= :committed (:status derived)))
    (is (= (genesis/chain-configuration-root c1)
           (get-in derived [:configuration/head :configuration/head-root])))
    (is (= derived (head/derive-successor-head h0 t c0 c1))))
  (testing "unknown schemas fail through the existing parent/new mismatch path"
    (let [c0 genesis/chain-configuration-v0-fixture
          unknown (assoc (successor-config) :configuration/schema "chain-configuration.v3")
          t (transition c0 (successor-config) 2)
          h0 (head/current-head (head/new-store (genesis/chain-configuration-root c0) 1))]
      (is (= :transition-new-configuration-mismatch
             (:reason (head/derive-successor-head h0 t c0 unknown)))))))

(deftest activation-is-fenced-to-one-current-head
  (let [c0 genesis/chain-configuration-v0-fixture
        c1 (successor-config)
        t (transition c0 c1 2)
        store (head/new-store (genesis/chain-configuration-root c0) 1)
        prior (head/current-head store)
        command {:transition t :parent-configuration c0 :new-configuration c1
                 :expected-head-root (:configuration-head-state/root prior)
                 :authorized-transition-root (genesis/chain-configuration-transition-root t)}
        committed (head/activate! store command)
        activated (get-in committed [:public-result :configuration/head])
        stale (head/activate! store command)]
    (testing "the activation receipt canonically binds the successor and its transition"
      (is (= :committed (:status committed)))
      (is (head/valid-head-state? activated))
      (is (= (:configuration-head-state/root prior)
             (:configuration/predecessor-head-root activated)))
      (is (= (genesis/chain-configuration-transition-root t)
             (:configuration/activation-transition-root activated)))
      (is (= (:configuration/head-root activated)
             (:configuration/root (get-in committed [:public-result :configuration/activation])))))
    (testing "a competing/stale CAS cannot install a second successor"
      (is (= :rejected (:status stale)))
      (is (= :configuration-head-mismatch (:reason stale))))))

(deftest pure-successor-derivation-matches-legacy-activation
  (let [c0 genesis/chain-configuration-v0-fixture
        c1 (successor-config)
        t (transition c0 c1 2)
        store (head/new-store (genesis/chain-configuration-root c0) 1)
        prior (head/current-head store)
        derived (head/derive-successor-head prior t c0 c1)
        committed (head/activate! store {:transition t :parent-configuration c0 :new-configuration c1
                                         :expected-head-root (:configuration-head-state/root prior)
                                         :authorized-transition-root (genesis/chain-configuration-transition-root t)})]
    (is (= :committed (:status derived)))
    (is (= (:configuration/head derived) (get-in committed [:public-result :configuration/head])))
    (is (= (:configuration-head-state/root (:configuration/head derived))
           (:configuration-head-state/root (get-in committed [:public-result :configuration/head]))))
    (is (= derived (head/derive-successor-head prior t c0 c1)))))

(deftest activation-rejects-parent-epoch-and-authorization-substitution
  (let [c0 genesis/chain-configuration-v0-fixture
        c1 (successor-config)
        t (transition c0 c1 2)
        store (head/new-store (genesis/chain-configuration-root c0) 1)
        base {:transition t :parent-configuration c0 :new-configuration c1
              :expected-head-root (:configuration-head-state/root (head/current-head store))
              :authorized-transition-root (genesis/chain-configuration-transition-root t)}]
    (is (= :transition-not-authorized
           (:reason (head/activate! store (assoc base :authorized-transition-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))))
    (is (= :configuration-epoch-not-successor
           (:reason (head/activate! store (assoc base :transition (assoc t :epoch 3)
                                                 :authorized-transition-root (genesis/chain-configuration-transition-root (assoc t :epoch 3)))))))))
