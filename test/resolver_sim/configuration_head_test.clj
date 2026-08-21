(ns resolver-sim.configuration-head-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.configuration-head :as head]
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
