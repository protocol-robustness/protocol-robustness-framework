(ns resolver-sim.protocols.sew.settlement-custody-attribution-test
  "L4b acceptance tests for settlement-scoped custody attribution.

   Every withdrawal settlement emits one or more held-adjustments carrying the
   settlement's canonical identity (:held-adjustment/settlement-root); the
   settlement artifact commits :settlement/held-adjustment-set-root over its
   attributed set.  check-settlement-custody-attribution? proves the attribution
   is a bijection (existence, binding, no double-attribution, exact economic
   debit, and completeness).  These tests exercise the eight acceptance
   mutations for the P2 contract."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.accounting.held-adjustment :as held-adj]))

(def alice "0xAlice")
(def bob   "0xBob")
(def usdc  :0xUSDC)

(def base-snapshot
  (snap-fix/escrow-snapshot
   {:escrow-fee-bps 50
    :default-auto-release-delay 0
    :default-auto-cancel-delay  0
    :max-dispute-duration       3600
    :appeal-window-duration     1800}))

(def allow-all
  (fn [_ _ _] {:allowed? true :reason-code 0}))

(defn- released-world
  "World with one escrow released to bob."
  []
  (-> (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                        (t/make-escrow-settings {}) base-snapshot)
      :world
      (lc/release 0 alice allow-all)
      :world))

(defn- sole-settlement [w]
  (-> w :sew/settlements vals first))

(defn- attributed-adjustments [w]
  (let [root (:settlement/root (sole-settlement w))]
    (filterv #(= root (:held-adjustment/settlement-root %))
             (:held-adjustments w))))

(defn- attribution-check [w]
  (inv/check-settlement-custody-attribution? w))

(defn- holds-with-kind?
  "True when the attribution check fails and surfaces at least one violation of
   the given namespaced kind."
  [w kind]
  (boolean (some #(= kind (:kind %)) (:violations (attribution-check w)))))

(deftest settlement-custody-attribution-holds
  (testing "a real release settlement is correctly attributed"
    (let [w (released-world)
          s (sole-settlement w)
          [adj] (attributed-adjustments w)]
      (is (some? s))
      (is (= (:settlement/filled s) (:amount adj))
          "the attributed adjustment is the exact settlement debit")
      (is (= (:settlement/root s) (:held-adjustment/settlement-root adj))
          "the adjustment carries the settlement identity")
      (is (:holds? (attribution-check w))))))

(deftest settlement-attribution-detects-removed-adjustment
  (testing "removing one attributed adjustment is detected (existence)"
    (let [w (released-world)
          adj-id (:held-adjustment/id (first (attributed-adjustments w)))
          tampered (update w :held-adjustments
                           (fn [xs] (vec (remove #(= adj-id (:held-adjustment/id %)) xs))))]
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/attributed-adjustment-missing)))))

(deftest settlement-attribution-detects-added-unrelated-adjustment
  (testing "an extra adjustment claiming this settlement root is detected (completeness)"
    (let [w (released-world)
          s (sole-settlement w)
          fake {:held-adjustment/id (str (count (:held-adjustments w)) "-fake")
                :held/direction :out :token usdc :amount 1
                :held/before 0 :held/after -1
                :held/account :escrow
                :held/position-id [:held/position usdc :escrow-principal 0]
                :owner/address alice :held/reason :escrow-settlement-released
                :held/action "finalize-released" :held/workflow-id 0
                :held-adjustment/settlement-root (:settlement/root s)}
          tampered (update w :held-adjustments conj fake)]
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/attribution-incomplete)))))

(deftest settlement-attribution-detects-misattributed-root
  (testing "changing an adjustment's settlement identity is detected"
    (let [w (released-world)
          adj-id (:held-adjustment/id (first (attributed-adjustments w)))
          tampered (update-in w [:held-adjustments]
                              (fn [xs]
                                (mapv (fn [a]
                                        (if (= adj-id (:held-adjustment/id a))
                                          (assoc a :held-adjustment/settlement-root "other-root")
                                          a))
                                      xs)))]
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/adjustment-misattributed)))))

(deftest settlement-attribution-detects-double-claim
  (testing "the same adjustment claimed by two settlements is detected"
    (let [w (released-world)
          s (sole-settlement w)
          adj-id (:held-adjustment/id (first (attributed-adjustments w)))
          second-root (held-adj/settlement-identity
                       {:workflow-id 99 :token usdc :direction :released
                        :filled 1 :recipient alice})
          tampered (assoc-in w [:sew/settlements second-root]
                             {:settlement/root second-root
                              :settlement/workflow-id 99 :settlement/token usdc
                              :settlement/direction :released :settlement/recipient alice
                              :settlement/filled 1
                              :settlement/adjustment-ids [adj-id]})]
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/adjustment-double-attributed)))))

(deftest settlement-attribution-detects-amount-decomposition
  (testing "correct aggregate delta with a wrong decomposition is detected (set-root provenance)"
    (let [root (held-adj/settlement-identity
                {:workflow-id 0 :token usdc :direction :released
                 :filled 1000 :recipient bob})
          mk (fn [id amount]
               {:held-adjustment/id id :held/direction :out :token usdc :amount amount
                :held/before 0 :held/after (- amount)
                :held/account :escrow
                :held/position-id [:held/position usdc :escrow-principal 0]
                :owner/address alice :held/reason :escrow-settlement-released
                :held/action "finalize-released" :held/workflow-id 0
                :held-adjustment/settlement-root root})
          a (mk "held-adjustment-a" 600)
          b (mk "held-adjustment-b" 400)
          world {:sew/settlements
                 {root {:settlement/root root
                        :settlement/workflow-id 0 :settlement/token usdc
                        :settlement/direction :released :settlement/recipient bob
                        :settlement/filled 1000
                        :settlement/adjustment-ids ["held-adjustment-a" "held-adjustment-b"]
                        :settlement/held-adjustment-set-root
                        (held-adj/settlement-held-adjustment-set-root [a b])}}
                 :held-adjustments [a b]}
          ;; keep the aggregate debit at 1000 but split it 700/300
          tampered (-> world
                       (update-in [:held-adjustments 0] assoc :amount 700 :held/after -700)
                       (update-in [:held-adjustments 1] assoc :amount 300 :held/after -300))]
      (is (:holds? (attribution-check world)) "the honest two-adjustment settlement holds")
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/attribution-set-root-mismatch)))))

(deftest settlement-attribution-order-independent
  (testing "ledger ordering is not committed (set-root is canonical by adjustment id)"
    (let [w (released-world)
          shuffled (update w :held-adjustments #(vec (reverse %)))]
      (is (:holds? (attribution-check shuffled))))))

(deftest settlement-attribution-detects-root-mutation
  (testing "mutating the settlement root after adjustments were created is detected"
    (let [w (released-world)
          s (sole-settlement w)
          root (:settlement/root s)
          tampered (-> w
                       (update-in [:sew/settlements root]
                                  (fn [s*] (assoc s* :settlement/root "mutated-root")))
                       (update :sew/settlements (fn [m]
                                                  (into {} (map (fn [[k v]]
                                                                  (if (= k root)
                                                                    ["mutated-root" v]
                                                                    [k v])))
                                                        m))))]
      (is (not (:holds? (attribution-check tampered))))
      (is (holds-with-kind? tampered
                            :resolver-sim.protocols.sew.invariants/settlement-identity-mismatch)))))
