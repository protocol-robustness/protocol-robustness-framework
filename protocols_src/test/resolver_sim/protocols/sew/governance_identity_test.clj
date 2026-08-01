(ns resolver-sim.protocols.sew.governance-identity-test
  "Tests for the address-bound governance authentication model and its
   restricted/open/legacy semantics."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.testing.scenario-builder :as sb]))

(def gov-agent {:id "gov" :address "0xGov" :role "governance"})
(def mallory {:id "mallory" :address "0xMallory" :role "governance"})
(def honest {:id "honest" :address "0xGov" :type "honest"})
(def gov-index {"gov" gov-agent "mallory" mallory "honest" honest})

(defn- paused-event [agent]
  {:seq 0 :time 1000 :agent agent :action "set_paused" :params {:paused? true}})

(defn- apply-set-paused [ctx agent]
  (sew/apply-action ctx (t/empty-world 1000) (paused-event agent)))

(deftest restricted-missing-identity-fails-closed
  (testing "restricted mode with no configured governance identity fails closed"
    (let [ctx {:agent-index gov-index}
          result (apply-set-paused ctx "gov")]
      (is (= :governance-identity-not-configured (:error result))))))

(deftest restricted-wrong-address-rejected
  (testing "governance role with the wrong address is rejected"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov"}
          result (apply-set-paused ctx "mallory")]
      (is (= :not-governance (:error result))))))

(deftest restricted-correct-address-no-role-rejected
  (testing "correct address without governance role is rejected"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov"}
          result (apply-set-paused ctx "honest")]
      (is (= :not-governance (:error result))))))

(deftest restricted-correct-role-and-address-succeeds
  (testing "correct role and configured address succeeds with address-bound provenance"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov"}
          result (apply-set-paused ctx "gov")
          prov (get-in result [:extra :authorization/provenance])]
      (is (:ok result))
      (is (= :scenario-configured-address-binding (:authorization/basis prov)))
      (is (= :address-bound (:authorization/authentication-mode prov)))
      (is (true? (:authorization/address-bound? prov)))
      (is (= "0xGov" (:authorization/configured-governance-address prov)))
      (is (= "0xGov" (:authorization/actor-address prov)))
      (is (false? (:authorization/registry-verified? prov))))))

(deftest explicit-legacy-mode-records-weak-basis
  (testing "explicit :legacy mode allows role-only governance and records the weaker basis"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov" :governance-mode :legacy}
          result (apply-set-paused ctx "mallory")  ; governance role, wrong address → role-only allows
          prov (get-in result [:extra :authorization/provenance])]
      (is (:ok result))
      (is (= :scenario-declared-role (:authorization/basis prov)))
      (is (= :role-declared (:authorization/authentication-mode prov)))
      (is (= :role-declared (:authorization/assurance prov)))
      (is (false? (:authorization/address-bound? prov)))))
  (testing "legacy mode still rejects a non-governance role"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov" :governance-mode :legacy}
          result (apply-set-paused ctx "honest")]
      (is (= :not-governance (:error result))))))

(deftest explicit-open-mode-allows-any-actor
  (testing "explicit :open mode allows any resolved actor and records the open basis"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov" :governance-mode :open}
          result (apply-set-paused ctx "honest")
          prov (get-in result [:extra :authorization/provenance])]
      (is (:ok result))
      (is (= :scenario-declared-open (:authorization/basis prov)))
      (is (= :open (:authorization/authentication-mode prov))))))

(deftest unsupported-governance-mode-fails-closed
  (testing "an unsupported :governance-mode value fails closed rather than behaving as restricted/legacy/open"
    (let [ctx {:agent-index gov-index :governance-identity "0xGov" :governance-mode :bogus}
          result (apply-set-paused ctx "gov")]
      (is (= :unsupported-governance-mode (:error result))))))

(deftest malformed-governance-identity-fails-during-configuration
  (testing "malformed :governance/identity fails during configuration/context validation, before any action"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"malformed :governance/identity"
                          (sew/normalize-governance-identity {:governance/address ""})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"malformed :governance/identity"
                          (sew/normalize-governance-identity 42)))
    (is (= {:governance/address "0xGov"}
           (sew/normalize-governance-identity "0xGov")))))

(deftest execute-force-authorised-action-is-not-governance-gated
  (testing "force-authorised execution is NOT governance-only: a non-governance resolved actor can execute a valid, scope-bound grant"
    (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50
                                          :max-dispute-duration 3600
                                          :appeal-window-duration 0})
          cr (lc/create-escrow (assoc (t/empty-world 1000) :params {:max-dispute-level 0})
                               "0xAlice" "0xUSDC" "0xBob" 10000
                               (t/make-escrow-settings {}) snap)
          disputed (-> (:world cr)
                       (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                       (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                       (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xExecutor")
                       (assoc-in [:dispute-timestamps 0] 1000))
          gov-ctx {:agent-index {"gov" gov-agent} :governance-identity "0xGov"}
          grant (sew/apply-action gov-ctx disputed
                                  {:seq 0 :time 1000 :agent "gov" :action "grant-force-authorisation"
                                   :params {:workflow-id 0 :reason :resolver-overcapacity}})
          auth-id (get-in grant [:extra :authorization/id])
          exec-ctx {:agent-index {"exec" {:address "0xExecutor" :type "honest"}}}
          exec (sew/apply-action exec-ctx (:world grant)
                                 {:seq 1 :time 1000 :agent "exec" :action "execute-force-authorised-action"
                                  :params {:workflow-id 0 :authorization-id auth-id :is-release true}})
          w2 (:world exec)]
      (is (:ok exec) "a non-governance resolved actor executes the grant")
      (is (= :consumed (get-in w2 [:force-authorisations auth-id :authorization/status])))
      (is (= :released (t/escrow-state w2 0))))))

(deftest default-scenario-governance-action-does-not-inherit-legacy
  (testing "a freshly constructed scenario invoking a governance action does NOT silently inherit legacy/role-only mode (restricted default fails closed)"
    (let [scenario (sb/sc :agents [{:id "alice" :address "0xAlice" :type "honest"}
                                   {:id "bob" :address "0xBob" :type "honest"}
                                   {:id "resolver" :address "0xResolver" :type "resolver"}
                                   {:id "gov" :address "0xGov" :role "governance"}]
                          :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                                   {:seq 1 :time 1000 :agent "gov" :action "set_paused"
                                    :params {:paused? true}}])
          r (sew/replay-with-sew-protocol scenario)]
      (is (= :rejected (get-in r [:trace 1 :result]))
          "the governance action is rejected, not admitted by a silent role-only fallback")
      (is (= :governance-identity-not-configured (get-in r [:trace 1 :error]))
          "restricted default with no configured governance identity fails closed"))))
