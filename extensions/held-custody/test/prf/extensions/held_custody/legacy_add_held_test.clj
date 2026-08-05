(ns prf.extensions.held-custody.legacy-add-held-test
  "Tests for the legacy force-auth-add-held compatibility reader:
   classifications, original-contract validation, and in-memory projection."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.evidence.force-authorisation :as fa-ev]
            [prf.extensions.held-custody.mutation :as mut]
            [prf.extensions.held-custody.legacy-add-held :as legacy]))

(defn- scope [id dir amt]
  {:authorization/id id
   :authorization/type :force-authorisation
   :held/direction dir
   :token "USDC"
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- auth [id dir amt]
  (let [s (scope id dir amt)]
    {:authorization/id id
     :authorization/status :active
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash
                                (fa/normalize-force-authorisation-scope s))
     :authorization/scope (fa/normalize-force-authorisation-scope s)
     :starts-at 0
     :expires-at 1000}))

(defn- legacy-v1 []
  (fa-ev/build-force-auth-add-held
   {:authorization (auth "fa-0" :out 100)
    :scope-map (scope "fa-0" :out 100)
    :adjustment {:held-adjustment/id "adj-1" :token "USDC" :amount 100 :held/direction :out}}))

(defn- legacy-v2 []
  (fa-ev/build-force-auth-add-held-v2
   {:authorization (auth "fa-0" :out 100)
    :scope-map (scope "fa-0" :out 100)
    :adjustment {:held-adjustment/id "adj-1" :token "USDC" :amount 100 :held/direction :out}}))

(defn- new-mutation []
  (mut/build-force-auth-held-mutation
   (auth "fa-0" :out 100)
   {:mutation/id "adj-1"
    :held/action :finalize-released
    :held/direction :out
    :held/amount 100
    :held/token "USDC"
    :held/account :escrow-principal
    :owner/address "0xrecipient"
    :held/reason :force-authorised-release
    :held/workflow-id 0}
   {}))

(deftest legacy-assurance-classifications
  (testing "v1 is direction-unbound: direction/scope is not independently committed"
    (let [v1 (legacy-v1)]
      (is (= :legacy-direction-unbound (legacy/classify-legacy-add-held v1)))))
  (testing "v2 is direction-bound: direction is committed via the body and the
            scope projection and the v2 validator cross-checks them; the action
            string is committed but NOT action↔direction bound"
    (let [v2 (legacy-v2)]
      (is (fa-ev/valid-force-auth-add-held-v2? v2))
      (is (= :legacy-direction-bound (legacy/classify-legacy-add-held v2)))))
  (testing "the new mutation artifact is action-and-direction-bound"
    (is (= :action-and-direction-bound (legacy/classify-legacy-add-held (new-mutation)))))
  (testing "non-artifacts / invalid are not classified as legacy held-custody"
    (is (= :not-force-auth-add-held (legacy/classify-legacy-add-held nil)))
    (is (= :not-force-auth-add-held
           (legacy/classify-legacy-add-held {:schema-version "nonsense"})))))

(deftest v2-direction-binding-cannot-be-broken
  (testing "proving the v2 classification: changing the direction breaks the
            artifact under its own contract (the projection is committed and
            cross-checked)"
    (let [v2 (legacy-v2)
          tampered (assoc v2 :held/direction :in)]
      (is (not (fa-ev/valid-force-auth-add-held-v2? tampered)))
      (is (= :legacy-direction-unbound (legacy/classify-legacy-add-held tampered))))))

(deftest validates-under-original-contract
  (is (:valid? (legacy/validate-legacy-add-held (legacy-v1))))
  (is (:valid? (legacy/validate-legacy-add-held (legacy-v2))))
  (is (= :legacy-direction-unbound (:classification (legacy/validate-legacy-add-held (legacy-v1)))))
  (is (= :legacy-direction-bound (:classification (legacy/validate-legacy-add-held (legacy-v2)))))
  (is (not (:valid? (legacy/validate-legacy-add-held {:schema-version "nonsense"})))))

(deftest projects-to-in-memory-mutation-without-rewriting
  (let [v1 (legacy-v1)
        v2 (legacy-v2)
        p1 (legacy/project-legacy-add-held v1)
        p2 (legacy/project-legacy-add-held v2)]
    (testing "the original artifact and hash are preserved"
      (is (= (:artifact/hash v1) (:artifact/hash (legacy-v1))))
      (is (= (:artifact/hash v2) (:artifact/hash (legacy-v2)))))
    (testing "in-memory projection uses canonical mutation vocabulary"
      (is (= "adj-1" (:mutation/id p1)))
      (is (= "adj-1" (:mutation/id p2)))
      (is (= "0xrecipient" (:owner/address p1)))
      (is (= :out (:held/direction p2))))
    (testing "v1 does not claim direction/scope assurance it cannot provide"
      (is (false? (:legacy/scope-committed? p1)))
      (is (false? (:legacy/action-bound? p1))))
    (testing "v2 carries the committed projection and marks scope committed"
      (is (true? (:legacy/scope-committed? p2)))
      (is (true? (:legacy/action-bound? p2)))
      (is (map? (:authorization-scope/projection p2))))
    (testing "the projection never rewrites the original artifact hash"
      (is (= (:artifact/hash v2)
             (:artifact/hash (fa-ev/build-force-auth-add-held-v2
                              {:authorization (auth "fa-0" :out 100)
                               :scope-map (scope "fa-0" :out 100)
                               :adjustment {:held-adjustment/id "adj-1" :token "USDC" :amount 100 :held/direction :out}})))))))

(deftest legacy-total-is-gross-flow-warning
  (is (= {:reason :legacy-total-is-gross-flow
          :field :total-amount
          :gross-inflow 100
          :gross-outflow 40
          :gross-flow 140
          :net-change 60}
         (legacy/legacy-total-is-gross-flow-warning 100 40))))
