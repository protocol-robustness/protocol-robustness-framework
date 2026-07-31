(ns resolver-sim.protocols.sew.accounting-test
  "Tests for contract_model/accounting.clj and invariants.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.assurance.custody        :as custody]
            [resolver-sim.hash.canonical          :as hash]))

(def usdc :0xUSDC)
(def alice "0xAlice")
(def bob   "0xBob")
(def gov   "0xGov")
(def treasury "0xTreasury")

(def snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50}))

(defn- base-world []
  (let [r (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                            (t/make-escrow-settings {}) snap)]
    (:world r)))

(defn- base-world-with-recipient []
  (-> (base-world)
      (assoc-in [:fee-recipients :default] treasury)))

;; ---------------------------------------------------------------------------
;; fee-recipient config
;; ---------------------------------------------------------------------------

(deftest resolve-fee-recipient-defaults-to-zero-address
  (is (= t/zero-address (ac/resolve-fee-recipient (t/empty-world) usdc))))

(deftest resolve-fee-recipient-uses-default
  (let [w (assoc-in (t/empty-world) [:fee-recipients :default] treasury)]
    (is (= treasury (ac/resolve-fee-recipient w usdc)))))

(deftest resolve-fee-recipient-uses-token-override
  (let [w (-> (t/empty-world)
              (assoc-in [:fee-recipients :default] "0xDefault")
              (assoc-in [:fee-recipients :by-token usdc] treasury))]
    (is (= treasury (ac/resolve-fee-recipient w usdc)))))

(deftest set-fee-recipient-sets-default
  (let [w (ac/set-fee-recipient (t/empty-world) :default treasury)]
    (is (= treasury (get-in w [:fee-recipients :default])))))

(deftest set-fee-recipient-sets-token-override
  (let [w (ac/set-fee-recipient (t/empty-world) usdc treasury)]
    (is (= treasury (get-in w [:fee-recipients :by-token usdc])))))

;; ---------------------------------------------------------------------------
;; withdraw-fees
;; ---------------------------------------------------------------------------

(deftest withdraw-fees-happy
  (let [w  (base-world-with-recipient)
        r  (ac/withdraw-fees w usdc treasury gov)]
    (is (true? (:ok r)))
    (is (= 5 (:amount r)) "fee for 1000 @ 50bps = 5")
    (is (= 0 (get-in (:world r) [:total-fees usdc] 0))
        "fees reset to 0 after withdrawal")
    (is (= 5 (get-in (:world r) [:total-withdrawn usdc] 0))
        "total-withdrawn incremented")
    (is (= 5 (get-in (:world r) [:fee-payouts usdc treasury] 0))
        "fee-payouts recorded per recipient")))

(deftest withdraw-fees-nothing-to-withdraw
  (let [r (ac/withdraw-fees (t/empty-world) usdc treasury gov)]
    (is (false? (:ok r)))
    (is (= :no-fees-to-withdraw (:error r)))))

;; ---------------------------------------------------------------------------
;; Escrow creation bonding guard
;; ---------------------------------------------------------------------------

(deftest create-escrow-accepts-zero-stake-resolver-when-bonding-enabled
  (let [resolver "0xUnstakedResolver"
        snapshot (snap-fix/escrow-snapshot {:resolver-bond-bps 10000
                                             :dispute-resolver resolver})
        result (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                                 (t/make-escrow-settings {}) snapshot)]
    (is (true? (:ok result)))
    (is (nil? (:error result)))))

;; ---------------------------------------------------------------------------
;; Held adjustment ledger
;; ---------------------------------------------------------------------------

(deftest add-held-records-custody-adjustment
  (let [auth {:authorization/type :governance
              :authorization/basis :scenario-declared}
        world (ac/add-held (t/empty-world)
                           usdc
                           100
                           {:action "appeal-slash"
                            :reason :appeal-bond-posted
                            :authorization-provenance auth
                            :extra {:held/workflow-id 42
                                    :held/actor alice}})
        adjustment (last (:held-adjustments world))
        artifact (get-in world [:held-artifacts (:held-adjustment/id adjustment)])
        position-id [:held/position usdc :appeal-bond 42 alice]]
    (is (= 100 (get-in world [:total-held usdc])))
    (is (= {:by-token {usdc 100}
            :by-position {position-id 100}
            :by-account {:appeal-bond 100}
            :by-owner {alice 100}
            :by-workflow {42 100}}
           (:held-ledger/index world)))
    (is (= 100 (get-in world [:held/positions position-id])))
    (is (= "held-adjustment-0" (:held-adjustment/id adjustment)))
    (is (= :in (:held/direction adjustment)))
    (is (= usdc (:token adjustment)))
    (is (= 100 (:amount adjustment)))
    (is (= 0 (:held/before adjustment)))
    (is (= 100 (:held/after adjustment)))
    (is (= :appeal-bond (:held/account adjustment)))
    (is (= position-id (:held/position-id adjustment)))
    (is (= alice (:owner/address adjustment)))
    (is (= :appeal-bond-posted (:held/reason adjustment)))
    (is (= "appeal-slash" (:held/action adjustment)))
    (is (= 42 (:held/workflow-id adjustment)))
    (is (= auth (:authorization/provenance adjustment)))
    (is (= "held-custody-adjustment.artifact.v2" (:schema-version artifact)))
    (is (= :held-custody-adjustment (:artifact/kind artifact)))
    (is (= "held-custody-held-adjustment-0" (:artifact/id artifact)))
    (is (string? (:artifact/hash artifact)))
    (is (= "held-adjustment-0" (:held-adjustment/id artifact)))
    (is (= :in (:held/direction artifact)))
    (is (= :appeal-bond-posted (:held/reason artifact)))
    (is (= {:authorization/type :governance
            :authorization/basis :scenario-declared}
           (:authorization/provenance artifact)))))

(deftest sub-held-records-custody-adjustment
  (let [position-id [:held/position usdc :escrow-principal 7]
        world (ac/sub-held {:total-held {usdc 150}
                            :held/positions {position-id 150}
                            :held-ledger/index {:by-token {usdc 150}
                                                :by-position {position-id 150}
                                                :by-account {:escrow-principal 150}
                                                :by-owner {}
                                                :by-workflow {7 150}}}
                           usdc
                           40
                           {:action "release"
                            :reason :escrow-settlement-released
                            :extra {:held/workflow-id 7
                                    :owner/address bob}})
        adjustment (last (:held-adjustments world))]
    (is (= 110 (get-in world [:total-held usdc])))
    (is (= {:by-token {usdc 110}
            :by-position {position-id 110}
            :by-account {:escrow-principal 110}
            :by-owner {bob -40}
            :by-workflow {7 110}}
           (:held-ledger/index world)))
    (is (= 110 (get-in world [:held/positions position-id])))
    (is (= :out (:held/direction adjustment)))
    (is (= 150 (:held/before adjustment)))
    (is (= 110 (:held/after adjustment)))
    (is (= :escrow-principal (:held/account adjustment)))
    (is (= position-id (:held/position-id adjustment)))
    (is (= bob (:owner/address adjustment)))
    (is (= :escrow-settlement-released (:held/reason adjustment)))
    (is (= "release" (:held/action adjustment)))))

(deftest held-adjustment-replay-reconstructs-total-held
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}})
                  (ac/add-held usdc 25 {:action "appeal-slash"
                                        :reason :appeal-bond-posted
                                        :authorization-provenance {:authorization/type :governance
                                                                   :authorization/basis :scenario-declared}
                                        :extra {:held/workflow-id 0
                                                :held/actor alice}})
                  (ac/sub-held usdc 40 {:action "release"
                                        :reason :escrow-settlement-released
                                        :extra {:held/workflow-id 0
                                                :owner/address bob}}))
        replayed-state (custody/replay-held-adjustment-state (:held-adjustments world))]
    (is (= (:held-ledger/index world) (:held-ledger/index replayed-state)))
    (is (= (:total-held world) (:total-held replayed-state)))
    (is (= (:held/positions world) (:held/positions replayed-state)))
    (is (= (set (map :held-adjustment/id (:held-adjustments world)))
           (set (keys (:held-artifacts world)))))
    (is (:holds? (inv/held-adjustments-reconstruct-total-held?
                  (assoc-in world [:params :held-adjustments/complete?] true))))))

(deftest held-custody-closed-form-checks-pass-on-valid-artifacts
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}})
                  (ac/sub-held usdc 40 {:action "release"
                                        :reason :escrow-settlement-released
                                        :extra {:held/workflow-id 0
                                                :owner/address bob}}))
        checks (custody/held-custody-closed-form-checks (vals (:held-artifacts world)))]
    (is (= [:held-custody/hash-integrity
            :held-custody/local-delta
            :held-custody/non-negative-after
            :held-custody/predecessor-continuity
            :held-custody/sequence-replay]
           (mapv :check/id checks)))
    (is (every? #(= :pass (:status %)) checks))))

(deftest complete-held-ledger-allows-create-and-release
  (let [world0 (assoc-in (t/empty-world 1000) [:params :held-adjustments/complete?] true)
        created (lc/create-escrow world0 alice usdc bob 1000
                                  (t/make-escrow-settings {}) snap)
        released (lc/release (:world created) 0 alice (fn [_ _ _] {:allowed? true}))
        world' (:world released)
        adjustments (:held-adjustments world')]
    (is (:ok created))
    (is (:ok released))
    (is (= [:escrow-principal-deposited :escrow-settlement-released]
           (mapv :held/reason adjustments)))
    (is (= ["create-escrow" "finalize-released"]
           (mapv :held/action adjustments)))
    (is (= [[:held/position usdc :escrow-principal 0]
            [:held/position usdc :escrow-principal 0]]
           (mapv :held/position-id adjustments)))
    (is (= (get-in world' [:held-ledger/index :by-token]) (:total-held world')))
    (is (= (get-in world' [:held-ledger/index :by-position]) (:held/positions world')))
    (is (= 0 (get-in world' [:total-held usdc] 0)))
    (is (= 0 (get-in world' [:held/positions [:held/position usdc :escrow-principal 0]] 0)))
    (is (:holds? (inv/held-adjustments-reconstruct-total-held? world')))))

(deftest held-history-zero-origin-predicate
  (let [zero-origin (-> (t/empty-world)
                        (ac/add-held usdc 100 {:action "create-escrow"
                                               :reason :escrow-principal-deposited
                                               :extra {:held/workflow-id 0
                                                       :owner/address alice}})
                        (ac/add-held usdc 25 {:action "post"
                                              :reason :appeal-bond-posted
                                              :authorization-provenance {:authorization/type :governance
                                                                         :authorization/basis :scenario-declared}
                                              :extra {:held/workflow-id 0
                                                      :held/actor alice}}))
        non-zero-origin (assoc (t/empty-world) :held-adjustments
                               [{:held-adjustment/id "held-adjustment-0"
                                 :held/direction :in
                                 :token usdc
                                 :amount 100
                                 :held/before 100
                                 :held/after 200}])]
    (is (custody/held-history-zero-origin? (:held-adjustments zero-origin)))
    (is (not (custody/held-history-zero-origin? (:held-adjustments non-zero-origin))))
    (is (custody/held-history-zero-origin? []))))

(deftest reconstruction-invariant-not-evaluated-without-complete-flag
  (let [w (base-world)]
    (is (:holds? (inv/held-adjustments-reconstruct-total-held? w)))
    (is (= :not-evaluated (:status (inv/held-adjustments-reconstruct-total-held? w))))
    (is (= :held-history-not-declared-complete
           (:reason (inv/held-adjustments-reconstruct-total-held? w))))
    (is (:holds? (inv/held-custody-closed-form? w)))
    (is (= :not-evaluated (:status (inv/held-custody-closed-form? w))))))

(deftest reconstruction-invariant-not-evaluated-when-history-not-zero-origin
  (let [w (-> (base-world)
              (assoc-in [:params :held-adjustments/complete?] true)
              (update :held-adjustments
                      (fn [adjs]
                        (mapv #(assoc % :held/before (+ 100 (:held/before %))
                                        :held/after (+ 100 (:held/after %)))
                              adjs))))]
    (is (:holds? (inv/held-adjustments-reconstruct-total-held? w)))
    (is (= :not-evaluated (:status (inv/held-adjustments-reconstruct-total-held? w))))
    (is (= :held-history-missing-opening-state
           (:reason (inv/held-adjustments-reconstruct-total-held? w))))))

(deftest final-held-summary-reports-missing-opening-state
  (let [non-zero-origin (assoc (t/empty-world) :held-adjustments
                               [{:held-adjustment/id "held-adjustment-0"
                                 :held/direction :in
                                 :token usdc
                                 :amount 100
                                 :held/before 100
                                 :held/after 200}])
        summary (custody/final-held-summary
                 (:held-adjustments non-zero-origin)
                 (:held-ledger/index non-zero-origin)
                 (:total-held non-zero-origin))]
    (is (false? (:reconstruction-valid? summary)))
    (is (= :missing-opening-state (:reconstruction-issue summary)))
    (is (= 1 (:ledger-adjustment-count summary)))))

(deftest held-artifacts-must-match-derived-ledger-view
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}}))
        tampered (assoc-in world
                           [:held-artifacts "held-adjustment-0" :amount]
                           999)]
    (is (:holds? (inv/held-artifacts-derived-from-adjustments? world)))
    (is (false? (:holds? (inv/held-artifacts-derived-from-adjustments? tampered))))))

(deftest add-held-rejects-invalid-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/add-held (t/empty-world) nil 10 {:action "test"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/add-held (t/empty-world) usdc -1 {:action "test"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/add-held (t/empty-world)
                                     usdc
                                     10
                                     {:action "governance-correction"
                                      :reason :governance-authorised-correction}))))

(deftest sub-held-rejects-underflow-and-invalid-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"underflow"
                        (ac/sub-held {:total-held {usdc 5}} usdc 10
                                     {:action "test" :reason :escrow-settlement-released})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/sub-held {:total-held {usdc 5}} nil 1 {:action "test"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/sub-held {:total-held {usdc 5}} usdc -1 {:action "test"}))))

;; ---------------------------------------------------------------------------
;; Force-authorisation consumption
;; ---------------------------------------------------------------------------

(deftest force-authorised-sub-held-succeeds
  (let [auth-id "fa-test-release-a1b2c3d4"
        held 100
        sub-amt 40
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount sub-amt
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-release
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}
         world (ac/sub-held {:total-held {usdc held}
                              :held/positions {[:held/position usdc :escrow-principal 42] held}
                              :held-ledger/index {:by-token {usdc held}
                                                  :by-position {[:held/position usdc :escrow-principal 42] held}}
                              :force-authorisations {auth-id {:authorization/id auth-id
                                                              :authorization/status :active
                                                              :consumed? false
                                                              :starts-at 0
                                                              :authorization/scope scope-map
                                                              :authorization/scope-hash scope-hash}}}
                            usdc sub-amt
                           {:action "finalize-released"
                            :reason :force-authorised-release
                            :authorization-provenance auth-prov
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})
        consumed (get-in world [:force-authorisations/consumed auth-id])]
    (is (= (- held sub-amt) (get-in world [:total-held usdc])))
    (is (true? (:consumed? consumed)))
    (is (= auth-id (:authorization/id consumed)))
    (is (= :force-authorisation (:authorization/type consumed)))
    (is (= scope-hash (:authorization/scope-hash consumed)))
    (is (= sub-amt (:amount consumed)))
    (is (= 42 (:workflow-id consumed)))
    (is (= bob (:owner/address consumed)))
    (is (= :force-authorised-release (:held/reason consumed)))
    (is (= "finalize-released" (:consumed/action consumed)))))

(deftest force-authorised-sub-held-rejects-reuse
  (let [auth-id "fa-test-reuse-a1b2c3d4"
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-release
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}
         world (ac/sub-held {:total-held {usdc 100}
                              :held/positions {[:held/position usdc :escrow-principal 42] 100}
                              :held-ledger/index {:by-token {usdc 100}
                                                  :by-position {[:held/position usdc :escrow-principal 42] 100}}
                              :force-authorisations {auth-id {:authorization/id auth-id
                                                              :authorization/status :active
                                                              :consumed? false
                                                              :starts-at 0
                                                              :authorization/scope scope-map
                                                              :authorization/scope-hash scope-hash}}}
                            usdc 40
                            {:action "finalize-released"
                             :reason :force-authorised-release
                             :authorization-provenance auth-prov
                             :extra {:held/workflow-id 42
                                     :owner/address bob}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"already consumed"
                          (ac/sub-held world usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id 42
                                                :owner/address bob}})))))

(deftest force-authorised-sub-held-rejects-scope-mismatch
  (let [auth-id "fa-test-mismatch-a1b2c3d4"
         ;; Scope hash computed with :force-authorised-refund
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-refund
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"scope differs from grant"
                          (ac/sub-held {:total-held {usdc 100}
                                        :force-authorisations {auth-id {:authorization/id auth-id
                                                                        :authorization/status :active
                                                                        :consumed? false
                                                                        :starts-at 0
                                                                        :authorization/scope scope-map
                                                                        :authorization/scope-hash scope-hash}}} usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id 42
                                                :owner/address bob}})))))

(deftest scope-mismatch-rejection-does-not-mutate-ledger-or-index
  (testing "a rejected force-authorisation leaves primary ledger and derived index unchanged"
    (let [auth-id "fa-test-nomut-a1b2c3d4"
          granted-scope {:authorization/id auth-id
                         :authorization/type :force-authorisation
                         :held/direction :out
                         :token usdc
                         :amount 40
                         :held/account :escrow-principal
                         :owner/address bob
                         :held/reason :force-authorised-refund
                         :held/workflow-id 42}
          scope-hash (hash/domain-hash "force-authorisation-scope" granted-scope)
          auth-prov {:authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-hash scope-hash}
          world {:total-held {usdc 100}
                 :held/positions {[:held/position usdc :escrow-principal 42] 100}
                 :held-ledger/index {:by-token {usdc 100}
                                     :by-position {[:held/position usdc :escrow-principal 42] 100}}
                 :force-authorisations {auth-id {:authorization/id auth-id
                                                 :authorization/status :active
                                                 :consumed? false
                                                 :starts-at 0
                                                 :authorization/scope granted-scope
                                                 :authorization/scope-hash scope-hash}}}
          before {:total-held (:total-held world)
                  :index (:held-ledger/index world)
                  :positions (:held/positions world)}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"scope differs from grant"
                            (ac/sub-held world usdc 40
                                         {:action "finalize-released"
                                          :reason :force-authorised-release
                                          :authorization-provenance auth-prov
                                          :extra {:held/workflow-id 42
                                                  :owner/address bob}})))
      (is (= before {:total-held (:total-held world)
                     :index (:held-ledger/index world)
                     :positions (:held/positions world)})
          "rejection must not alter the primary ledger, positions, or derived index"))))

(deftest force-authorised-sub-held-rejects-forged-provenance
  (let [auth-id "fa-forged-a1b2c3d4"
        scope-map {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out
                   :token usdc
                   :amount 40
                   :held/account :escrow-principal
                   :owner/address bob
                   :held/reason :force-authorised-release
                   :held/workflow-id 42}
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"record not found"
                          (ac/sub-held {:total-held {usdc 100}}
                                       usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id 42
                                                :owner/address bob}})))))

;; ── Related-claims force-authorisation consumption ────────────────────────────

(deftest force-authorised-sub-held-related-claims-member-consumed
  (let [auth-id "fa-rel-test-a1b2c3d4"
        wf-0 42 wf-1 43
        sub-0 60 sub-1 40
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out
                 :token usdc :amount sub-0
                 :held/account :escrow-principal
                 :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-0}
        scope-1 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out
                 :token usdc :amount sub-1
                 :held/account :escrow-principal
                 :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-1}
        hash-0 (hash/domain-hash "force-authorisation-scope" scope-0)
        hash-1 (hash/domain-hash "force-authorisation-scope" scope-1)
        rel-id 99
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-kind :related-claims
                   :authorization/scope-hash hash-0
                   :relationship/id rel-id
                   :relationship/hash "rel-hash"
                   :member-scope-hashes [hash-0 hash-1]}
        world (ac/sub-held {:total-held {usdc 200}
                            :held/positions
                            {[:held/position usdc :escrow-principal wf-0] 200
                             [:held/position usdc :escrow-principal wf-1] 200}
                            :held-ledger/index
                            {:by-token {usdc 200}
                             :by-position
                             {[:held/position usdc :escrow-principal wf-0] 200
                              [:held/position usdc :escrow-principal wf-1] 200}
                             :by-account {:escrow-principal 200}
                             :by-workflow {wf-0 200 wf-1 200}}
                            :force-authorisations
                            {auth-id {:authorization/id auth-id
                                      :authorization/status :active
                                      :consumed? false
                                      :starts-at 0
                                                                            :authorization/scope-kind :related-claims
                                                                            :relationship/id rel-id
                                                                            :relationship/hash "rel-hash"
                                                                            :member-scope-hashes [hash-0 hash-1]
                                                                            :authorization/scope scope-0
                                                                            :authorization/scope-hash hash-0}}
                             :related-claims
                             {rel-id {:relationship/id rel-id
                                      :relationship/status :active
                                      :relationship/hash "rel-hash"
                                      :relationship/members
                                      [{:claim/kind :sew/workflow :workflow/id wf-0}
                                       {:claim/kind :sew/workflow :workflow/id wf-1}]}}}
                          usdc sub-0
                          {:action "finalize-released"
                           :reason :force-authorised-release
                           :authorization-provenance auth-prov
                           :extra {:held/workflow-id wf-0
                                   :owner/address bob}})
        consumed (get-in world [:force-authorisations/consumed auth-id])]
    (is (= 140 (get-in world [:total-held usdc])))
    (is (true? (:consumed? consumed)))
    (is (= auth-id (:authorization/id consumed)))
    (is (= :force-authorisation (:authorization/type consumed)))
    (is (= :related-claims (:authorization/scope-kind consumed)))
    (is (= rel-id (:relationship/id consumed)))
    (is (contains? (:consumed-members consumed) hash-0))
    (is (not (contains? (:consumed-members consumed) hash-1)))
    (is (= 1 (:member-count consumed)))))

(deftest force-authorised-sub-held-related-claims-all-members-consumed
  (let [auth-id "fa-rel-all-a1b2c3d4"
        wf-0 42 wf-1 43
        sub-0 60 sub-1 40
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-0
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release :held/workflow-id wf-0}
        scope-1 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-1
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release :held/workflow-id wf-1}
        hash-0 (hash/domain-hash "force-authorisation-scope" scope-0)
        hash-1 (hash/domain-hash "force-authorisation-scope" scope-1)
        rel-id 99
        base {:total-held {usdc 200}
              :held/positions
              {[:held/position usdc :escrow-principal wf-0] 200
               [:held/position usdc :escrow-principal wf-1] 200}
              :held-ledger/index
              {:by-token {usdc 200}
               :by-position
               {[:held/position usdc :escrow-principal wf-0] 200
                [:held/position usdc :escrow-principal wf-1] 200}
               :by-account {:escrow-principal 200}
               :by-workflow {wf-0 200 wf-1 200}}
              :force-authorisations
              {auth-id {:authorization/id auth-id
                        :authorization/status :active
                        :consumed? false :starts-at 0
                        :authorization/scope-kind :related-claims
                        :relationship/id rel-id
                        :relationship/hash "rel-hash"
                        :member-scope-hashes [hash-0 hash-1]
                        :authorization/scope scope-0
                        :authorization/scope-hash hash-0}}
              :related-claims
              {rel-id {:relationship/id rel-id
                       :relationship/status :active
                       :relationship/hash "rel-hash"
                       :relationship/members
                       [{:claim/kind :sew/workflow :workflow/id wf-0}
                        {:claim/kind :sew/workflow :workflow/id wf-1}]}}}
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-kind :related-claims
                    :authorization/scope-hash hash-0
                    :relationship/id rel-id
                    :relationship/hash "rel-hash"
                    :member-scope-hashes [hash-0 hash-1]}
         w1 (ac/sub-held base usdc sub-0
                        {:action "finalize-released"
                         :reason :force-authorised-release
                         :authorization-provenance auth-prov
                         :extra {:held/workflow-id wf-0 :owner/address bob}})
        w2 (ac/sub-held w1 usdc sub-1
                        {:action "finalize-released"
                         :reason :force-authorised-release
                         :authorization-provenance auth-prov
                         :extra {:held/workflow-id wf-1 :owner/address bob}})
        consumed (get-in w2 [:force-authorisations/consumed auth-id])]
    (is (= 100 (get-in w2 [:total-held usdc])))
    (is (contains? (:consumed-members consumed) hash-0))
    (is (contains? (:consumed-members consumed) hash-1))
    (is (= 2 (:member-count consumed)))))

(deftest force-authorised-sub-held-related-claims-rejects-member-reuse
  (let [auth-id "fa-rel-reuse-a1b2c3d4"
        wf-0 42 held 100 sub-0 40
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-0
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-0}
        hash-0 (hash/domain-hash "force-authorisation-scope" scope-0)
        rel-id 99
        base {:total-held {usdc held}
              :held/positions
              {[:held/position usdc :escrow-principal wf-0] held}
              :held-ledger/index
              {:by-token {usdc held}
               :by-position
               {[:held/position usdc :escrow-principal wf-0] held}
               :by-account {:escrow-principal held}
               :by-workflow {wf-0 held}}
              :force-authorisations
              {auth-id {:authorization/id auth-id
                        :authorization/status :active
                        :consumed? false :starts-at 0
                        :authorization/scope-kind :related-claims
                        :relationship/id rel-id
                        :relationship/hash "rel-hash"
                        :member-scope-hashes [hash-0]
                        :authorization/scope scope-0
                        :authorization/scope-hash hash-0}}
              :related-claims
              {rel-id {:relationship/id rel-id
                       :relationship/status :active
                       :relationship/hash "rel-hash"
                       :relationship/members
                       [{:claim/kind :sew/workflow :workflow/id wf-0}
                        {:claim/kind :sew/workflow :workflow/id 43}]}}}
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-kind :related-claims
                    :authorization/scope-hash hash-0
                    :relationship/id rel-id
                    :relationship/hash "rel-hash"
                    :member-scope-hashes [hash-0]}
        w1 (ac/sub-held base usdc sub-0
                        {:action "finalize-released"
                         :reason :force-authorised-release
                         :authorization-provenance auth-prov
                         :extra {:held/workflow-id wf-0
                                 :owner/address bob}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"already consumed"
                          (ac/sub-held w1 usdc sub-0
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id wf-0
                                                :owner/address bob}})))))

(deftest force-authorised-sub-held-related-claims-rejects-unauthorized-member
  (let [auth-id "fa-rel-unauth-a1b2c3d4"
        wf-0 42 wf-x 99 held 100 sub-0 40
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-0
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-0}
        scope-x {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-0
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-x}
        hash-0 (hash/domain-hash "force-authorisation-scope" scope-0)
        hash-x (hash/domain-hash "force-authorisation-scope" scope-x)
        rel-id 99
        base {:total-held {usdc held}
              :held/positions
              {[:held/position usdc :escrow-principal wf-x] held}
              :held-ledger/index
              {:by-token {usdc held}
               :by-position
               {[:held/position usdc :escrow-principal wf-x] held}
               :by-account {:escrow-principal held}
               :by-workflow {wf-x held}}
              :force-authorisations
              {auth-id {:authorization/id auth-id
                        :authorization/status :active
                        :consumed? false :starts-at 0
                        :authorization/scope-kind :related-claims
                        :relationship/id rel-id
                        :relationship/hash "rel-hash"
                        :member-scope-hashes [hash-0]
                        :authorization/scope scope-0
                        :authorization/scope-hash hash-0}}
               :related-claims
               {rel-id {:relationship/id rel-id
                        :relationship/status :active
                        :relationship/hash "rel-hash"
                        :relationship/members
                        [{:claim/kind :sew/workflow :workflow/id wf-0}]}}}
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-kind :related-claims
                    :authorization/scope-hash hash-0
                    :relationship/id rel-id
                    :relationship/hash "rel-hash"
                    :member-scope-hashes [hash-0]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not in referenced related-claims relationship"
                          (ac/sub-held base usdc sub-0
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id wf-x
                                                :owner/address bob}})))))

(deftest force-authorised-sub-held-related-claims-rejects-inactive-relationship
  (let [auth-id "fa-rel-inact-a1b2c3d4"
        wf-0 42 held 100 sub-0 40
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out :token usdc :amount sub-0
                 :held/account :escrow-principal :owner/address bob
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-0}
        hash-0 (hash/domain-hash "force-authorisation-scope" scope-0)
        rel-id 99
        base {:total-held {usdc held}
              :held/positions
              {[:held/position usdc :escrow-principal wf-0] held}
              :held-ledger/index
              {:by-token {usdc held}
               :by-position
               {[:held/position usdc :escrow-principal wf-0] held}
               :by-account {:escrow-principal held}
               :by-workflow {wf-0 held}}
              :force-authorisations
              {auth-id {:authorization/id auth-id
                        :authorization/status :active
                        :consumed? false :starts-at 0
                        :authorization/scope-kind :related-claims
                        :relationship/id rel-id
                        :relationship/hash "rel-hash"
                        :member-scope-hashes [hash-0]
                        :authorization/scope scope-0
                        :authorization/scope-hash hash-0}}
               :related-claims
               {rel-id {:relationship/id rel-id
                        :relationship/status :resolved
                        :relationship/hash "rel-hash"
                        :relationship/members
                        [{:claim/kind :sew/workflow :workflow/id wf-0}]}}}
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-kind :related-claims
                   :authorization/scope-hash hash-0
                   :relationship/id rel-id
                   :relationship/hash "rel-hash"
                   :member-scope-hashes [hash-0]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"related-claims relationship not active"
                          (ac/sub-held base usdc sub-0
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id wf-0
                                                :owner/address bob}})))))

(deftest sub-held-rejects-cross-position-drawdown
  (let [position-a [:held/position usdc :escrow-principal 1]
        position-b [:held/position usdc :escrow-principal 2]
        world {:total-held {usdc 100}
               :held/positions {position-a 100 position-b 0}
               :held-ledger/index {:by-token {usdc 100}
                                   :by-position {position-a 100 position-b 0}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"position underflow"
                          (ac/sub-held world usdc 40
                                       {:action "release"
                                        :reason :escrow-settlement-released
                                        :extra {:held/workflow-id 2
                                                :owner/address bob}})))))

(deftest held-adjustments-reject-position-policy-override
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"position conflicts with reason policy"
                        (ac/sub-held {:total-held {usdc 100}
                                      :held/positions {[:held/position usdc :escrow-principal 42] 100}}
                                     usdc 40
                                     {:action "release"
                                      :reason :escrow-settlement-released
                                      :extra {:held/workflow-id 42
                                              :owner/address bob
                                              :held/position-id [:held/position usdc :escrow-principal 7]}}))))

(deftest address-scoped-held-adjustments-require-owner
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit owner address"
                        (ac/sub-held {:total-held {usdc 100}
                                      :held/positions {[:held/position usdc :escrow-principal 42] 100}}
                                     usdc 40
                                     {:action "release"
                                      :reason :escrow-settlement-released
                                      :extra {:held/workflow-id 42}}))))

(deftest exceptional-held-reason-requires-auth
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/sub-held {:total-held {usdc 100}} usdc 40
                                     {:action "finalize-released"
                                      :reason :force-authorised-release
                                      :extra {:held/workflow-id 42
                                              :owner/address bob}}))))

(deftest normal-held-reason-no-auth-ok
  (let [position-id [:held/position usdc :escrow-principal 42]
        world (ac/sub-held {:total-held {usdc 100}
                             :held/positions {position-id 100}
                             :held-ledger/index {:by-token {usdc 100}
                                                 :by-position {position-id 100}}}
                           usdc 40
                           {:action "finalize-released"
                            :reason :escrow-settlement-released
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})]
    (is (= 60 (get-in world [:total-held usdc])))))

;; ---------------------------------------------------------------------------
;; Terminal custody closure and force-authorisation lifecycle invariants
;; ---------------------------------------------------------------------------

(deftest terminal-workflow-custody-closure-detects-residual-principal
  (let [position-id [:held/position usdc :escrow-principal 42]
        world {:escrow-transfers {42 {:token usdc :escrow-state :released}}
               :held/positions {position-id 1}}]
    (is (false? (:holds? (inv/terminal-workflow-custody-closed? world))))))

(deftest force-authorisation-lifecycle-detects-unlinked-consumption
  (let [scope {:authorization/id "fa-corrupt"
               :authorization/type :force-authorisation
               :held/direction :out
               :token usdc
               :amount 40
               :held/account :escrow-principal
               :owner/address bob
               :held/reason :force-authorised-release
               :held/workflow-id 42}
        world {:force-authorisations
               {"fa-corrupt" {:authorization/id "fa-corrupt"
                              :authorization/type :force-authorisation
                              :authorization/status :consumed
                              :consumed? true
                              :authorization/scope scope
                              :authorization/scope-hash (hash/domain-hash "force-authorisation-scope" scope)}}
               :force-authorisations/consumed
               {"fa-corrupt" {:held-adjustment/id "held-adjustment-missing"}}}]
    (is (false? (:holds? (inv/force-authorisations-lifecycle-consistent? world))))))

;; ---------------------------------------------------------------------------
;; Transition-level held-adjustment delta invariant
;; ---------------------------------------------------------------------------

(deftest held-adjustments-cover-total-held-delta-passes
  (let [position-id [:held/position usdc :escrow-principal 7]
        world-before {:total-held {usdc 100}
                      :held/positions {position-id 100}
                      :held-ledger/index {:by-token {usdc 100}
                                          :by-position {position-id 100}}
                      :held-adjustments []}
        world-after  (ac/sub-held world-before usdc 40
                                  {:action "release"
                                   :reason :escrow-settlement-released
                                   :extra {:held/workflow-id 7
                                           :owner/address bob}})
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (:holds? result))))

(deftest held-adjustments-cover-total-held-delta-detects-mismatch
  (let [world-before {:total-held {usdc 100} :held-adjustments []}
        world-after  (assoc-in world-before [:total-held usdc] 50)
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (false? (:holds? result)))
    (is (= -50 (-> result :violations first :delta-held)))
    (is (= 0 (-> result :violations first :held-adjustment-delta)))))

(deftest held-adjustments-cover-total-held-delta-supports-allowlist
  (let [world-before {:total-held {usdc 100}
                      :held-adjustments []
                      :params {:held-adjustments/allow-transition-mismatch true}}
        world-after  (assoc-in world-before [:total-held usdc] 50)
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (:holds? result))))

;; ---------------------------------------------------------------------------
;; Partial-fill settlement adapter
;; ---------------------------------------------------------------------------

(deftest partial-fill-settlement-updates-position-and-custody-ledger
  (let [owner-id "yield-owner"
        position {:owner/id owner-id
                  :token usdc
                  :principal 100
                  :realized-yield 20
                  :deferred-yield 0}
        decision {:filled {:principal 40 :realized-yield 10 :deferred-yield 0}
                  :deferred {:principal 60 :realized-yield 10 :deferred-yield 0}
                  :haircut {}
                  :policy {:post-partial-fill-accrual :accrue-residual-as-unrealized}}
        world (-> (t/empty-world 1000)
                  (ac/add-held usdc 100
                               {:action "seed-principal"
                                :reason :escrow-principal-deposited
                                :extra {:held/workflow-id 42 :owner/address alice}})
                  (ac/add-held usdc 20
                               {:action "seed-yield"
                                :reason :yield-accrued
                                :extra {:held/workflow-id 42}})
                  (assoc-in [:yield/positions owner-id] position))
        settled (lc/apply-partial-fill-settlement
                 world position decision {:workflow-id 42 :recipient bob})
        principal-position [:held/position usdc :escrow-principal 42]
        yield-position [:held/position usdc :yield-custody 42]]
    (is (= 70 (get-in settled [:total-held usdc])))
    (is (= 60 (get-in settled [:held/positions principal-position])))
    (is (= 10 (get-in settled [:held/positions yield-position])))
    (is (= 0 (get-in settled [:yield/positions owner-id :principal])))
    (is (= 0 (get-in settled [:yield/positions owner-id :realized-yield])))
    (is (= 70 (get-in settled [:yield/positions owner-id :unrealized-yield])))
    (is (= 4 (count (:held-adjustments settled))))))

(deftest partial-fill-settlement-rejects-position-bucket-underflow
  (let [position {:owner/id "yield-owner" :token usdc :principal 10
                  :realized-yield 0 :deferred-yield 0}
        world (assoc-in (t/empty-world 1000) [:yield/positions "yield-owner"] position)
        decision {:filled {:principal 11} :deferred {} :haircut {} :policy {}}
        error (try
                (lc/apply-partial-fill-settlement world position decision
                                                   {:workflow-id 42 :recipient bob})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :partial-fill-position-underflow (:type (ex-data error))))))

(deftest partial-fill-settlement-rejects-held-custody-shortfall
  (let [position {:owner/id "yield-owner" :token usdc :principal 100
                  :realized-yield 0 :deferred-yield 0}
        world (-> (t/empty-world 1000)
                  (ac/add-held usdc 50
                               {:action "seed-principal"
                                :reason :escrow-principal-deposited
                                :extra {:held/workflow-id 42 :owner/address alice}})
                  (assoc-in [:yield/positions "yield-owner"] position))
        decision {:filled {:principal 100} :deferred {} :haircut {} :policy {}}
        error (try
                (lc/apply-partial-fill-settlement world position decision
                                                   {:workflow-id 42 :recipient bob})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :sub-held-underflow (:type (ex-data error))))))

;; ---------------------------------------------------------------------------
;; Partial-fill principal loss reason
;; ---------------------------------------------------------------------------

(deftest partial-fill-principal-loss-requires-auth
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/sub-held {:total-held {usdc 100}} usdc 40
                                     {:action "impair"
                                      :reason :partial-fill-principal-loss
                                      :extra {:held/workflow-id 42}}))))

(deftest partial-fill-principal-loss-with-auth-succeeds
  (let [auth-id "fa-pl-test"
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :partial-fill-principal-loss
                    :held/workflow-id 42}
        scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-hash scope-hash}
        world (ac/sub-held {:total-held {usdc 100}
                             :held/positions {[:held/position usdc :escrow-principal 42] 100}
                             :held-ledger/index {:by-token {usdc 100}
                                                 :by-position {[:held/position usdc :escrow-principal 42] 100}}
                             :force-authorisations {auth-id {:authorization/id auth-id
                                                             :authorization/status :active
                                                             :consumed? false
                                                             :starts-at 0
                                                             :authorization/scope scope-map
                                                             :authorization/scope-hash scope-hash}}}
                           usdc 40
                           {:action "impair"
                            :reason :partial-fill-principal-loss
                            :authorization-provenance auth-prov
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})]
    (is (= 60 (get-in world [:total-held usdc])))
    (is (true? (get-in world [:force-authorisations/consumed auth-id :consumed?])))
    (is (= :partial-fill-principal-loss
           (get-in world [:force-authorisations/consumed auth-id :held/reason])))))

;; ---------------------------------------------------------------------------
;; claimable balances
;; ---------------------------------------------------------------------------

(deftest record-and-withdraw-claimable
  (let [;; Manually put a terminal escrow in place
        w0 (base-world)
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
        w2 (ac/record-claimable-v2 w1 0 :settlement/principal bob 995)
        r  (ac/withdraw-escrow w2 0 bob)]
    (is (true? (:ok r)))
    (is (= 995 (:amount r)))
    (is (= 0 (get-in (:world r) [:claimable-v2 0 :settlement/principal bob] 0))
        "claimable cleared after withdrawal")))

(deftest withdraw-claimable-pending-no-claimable
  (let [w (base-world)   ; state = :pending, no claimable balance
        r (ac/withdraw-escrow w 0 bob)]
    (is (false? (:ok r)))
    (is (= :no-claimable-balance (:error r)))))

(deftest withdraw-claimable-pending-with-claimable
  (let [w (-> (base-world)
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 500))
        r (ac/withdraw-escrow w 0 bob)]
    (is (true? (:ok r)))
    (is (= 500 (:amount r)))
    (is (= 0 (get-in (:world r) [:claimable-v2 0 :settlement/principal bob] 0)))))

(deftest withdraw-claimable-nothing-to-claim
  (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :released)
        r (ac/withdraw-escrow w 0 alice)]
    (is (false? (:ok r)))
    (is (= :no-claimable-balance (:error r)))))

;; ---------------------------------------------------------------------------
;; BondCollector
;; ---------------------------------------------------------------------------

(deftest post-appeal-bond-deducts-fee
  (let [w    (assoc-in (t/empty-world) [:escrow-transfers 0]
                       {:token usdc :escrow-state :disputed})
        snap (snap-fix/escrow-snapshot {:appeal-bond-protocol-fee-bps 200}) ; 2%
        w'   (ac/post-appeal-bond w 0 alice snap usdc 1000)
        adjustment (last (:held-adjustments w'))]
    (is (= 980  (get-in w' [:bond-balances 0 alice] 0)) "net after 2% fee")
    (is (= 20   (get-in w' [:bond-fees usdc] 0))        "protocol fee recorded")
    (is (= :appeal-bond-posted (:held/reason adjustment)))
    (is (= "post-appeal-bond" (:held/action adjustment)))
    (is (= 0 (:held/workflow-id adjustment)))
    (is (= alice (:held/actor adjustment)))))

(deftest post-appeal-bond-rejects-workflow-token-mismatch
  (let [world (assoc-in (t/empty-world) [:escrow-transfers 0]
                        {:token usdc :escrow-state :disputed})
        error (try
                (ac/post-appeal-bond world 0 alice {} :DAI 100)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :bond-token-mismatch (:type (ex-data error))))
    (is (= usdc (:expected (ex-data error))))))

(deftest deferred-yield-claim-settlement-moves-custody-to-claimable
  (let [owner-id [:sew/escrow 0]
        world (-> (t/empty-world)
                  (assoc-in [:escrow-transfers 0]
                            {:token usdc :escrow-state :released :to bob :from alice})
                  (assoc-in [:yield/positions owner-id]
                            {:owner/id owner-id :token usdc :status :withdrawn
                             :reclaimed-amount 40})
                  (ac/add-held usdc 40
                               {:action "reserve-deferred-yield"
                                :reason :deferred-yield-reserved
                                :extra {:held/workflow-id 0}}))
        settled (lc/apply-deferred-yield-claim-settlement world 0 owner-id bob 40)
        position-id [:held/position usdc :yield-custody 0]]
    (is (= 0 (get-in settled [:total-held usdc])))
    (is (= 0 (get-in settled [:held/positions position-id])))
    (is (= 40 (get-in settled [:claimable-v2 0 :settlement/yield bob])))
    (is (= :deferred-yield-claimed
           (:held/reason (last (:held-adjustments settled)))))))

(deftest slash-bond-happy
  (let [w  (-> (t/empty-world)
               (assoc-in [:escrow-transfers 0] {:token usdc
                                                :escrow-state :disputed
                                                :to bob
                                                :from alice
                                                :amount-after-fee 0})
               (assoc-in [:bond-balances 0 alice] 980)
               (assoc-in [:total-held usdc] 980)
               (assoc-in [:held/positions [:held/position usdc :appeal-bond "0-0xAlice" 0 alice]] 980)
               (assoc-in [:held-ledger/index :by-token usdc] 980)
               (assoc-in [:held-ledger/index :by-position [:held/position usdc :appeal-bond "0-0xAlice" 0 alice]] 980))
        r  (ac/slash-bond w 0 alice)]
    (is (true? (:ok r)))
    (is (= 980 (:slashed r)))
    (is (= 0 (get-in (:world r) [:bond-balances 0 alice] 0)))
    (is (= 980 (get-in (:world r) [:bond-slashed 0] 0)))
    (is (= :appeal-bond-slashed
           (:held/reason (last (:held-adjustments (:world r))))))))

(deftest slash-bond-nothing-to-slash
  (let [r (ac/slash-bond (t/empty-world) 0 alice)]
    (is (false? (:ok r)))
    (is (= :no-bond-to-slash (:error r)))))

(deftest return-bond-happy
  (let [w (-> (t/empty-world)
              (assoc-in [:escrow-transfers 0] {:token usdc
                                               :escrow-state :disputed
                                               :to bob
                                               :from alice
                                               :amount-after-fee 0})
              (assoc-in [:bond-balances 0 alice] 980)
              (assoc-in [:total-held usdc] 980)
              (assoc-in [:held/positions [:held/position usdc :appeal-bond "0-0xAlice" 0 alice]] 980)
              (assoc-in [:held-ledger/index :by-token usdc] 980)
              (assoc-in [:held-ledger/index :by-position [:held/position usdc :appeal-bond "0-0xAlice" 0 alice]] 980))
        r (ac/return-bond w 0 alice)]
    (is (true? (:ok r)))
    (is (= 980 (:returned r)))
    (is (= 0 (get-in (:world r) [:bond-balances 0 alice] 0)))
    (is (= 980 (get-in (:world r) [:claimable-v2 0 :settlement/principal alice] 0)))
    (is (= :appeal-bond-returned
           (:held/reason (last (:held-adjustments (:world r))))))))

;; ---------------------------------------------------------------------------
;; Invariants
;; ---------------------------------------------------------------------------

(deftest solvency-holds-after-create
  (let [w (base-world)]
    (is (:holds? (inv/solvency-holds? w nil)))))

(deftest solvency-fails-when-held-exceeds-live
  "Manually corrupt total-held to exceed live sum — invariant should catch it."
  (let [w    (base-world)
        bad  (assoc-in w [:total-held usdc] -1)]
    ;; live sum = 995 (one pending escrow), held = -1 → violation
    (is (not (:holds? (inv/solvency-holds? bad nil))))))

(deftest contract-payout-solvency-is-unverified-without-chain-balance-evidence
  (let [result (inv/contract-payout-solvency? (base-world))]
    (is (:holds? result))
    (is (= :unverified (:coverage result)))))

(deftest contract-payout-solvency-includes-held-claims-and-fees
  (let [world (-> (t/empty-world)
                  (assoc :total-held {usdc 100})
                  (assoc :total-fees {usdc 20})
                  (assoc-in [:escrow-transfers 7] {:token usdc})
                  (assoc-in [:claimable-v2 7 :settlement/principal bob] 30)
                  (assoc :solvency/contract-balances {[:escrow-vault usdc] 149}))
        result (inv/contract-payout-solvency? world)
        violation (first (:violations result))]
    (is (false? (:holds? result)))
    (is (= :external-balance-snapshot (:coverage result)))
    (is (= :contract-payout-shortfall (:type violation)))
    (is (= 150 (:liabilities violation)))
    (is (= 1 (:shortfall violation)))))

(deftest contract-payout-solvency-requires-every-observed-token-balance
  (let [world (-> (t/empty-world)
                  (assoc :total-held {usdc 100})
                  (assoc :solvency/contract-balances {}))
        result (inv/contract-payout-solvency? world)]
    (is (false? (:holds? result)))
    (is (= :missing-contract-balance
           (get-in result [:violations 0 :type])))))

(deftest fees-non-negative-holds
  (let [w (base-world)]
    (is (:holds? (inv/fees-non-negative? w)))))

(deftest fee-monotonicity-holds-after-create
  (let [w0 (t/empty-world 1000)
        w1 (:world (lc/create-escrow w0 alice usdc bob 1000
                                     (t/make-escrow-settings {}) snap))]
    (is (:holds? (inv/fee-increased-or-equal? w0 w1))
        "fees after create >= fees before create")))

(deftest terminal-states-unchanged-invariant
  (let [w0 (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :released)
        ;; Attempt to change state (simulating a bug):
        w1  (assoc-in w0 [:escrow-transfers 0 :escrow-state] :pending)]
    (is (:holds? (inv/terminal-states-unchanged? w0 w0)) "unchanged is fine")
    (is (not (:holds? (inv/terminal-states-unchanged? w0 w1)))
        "changed terminal state detected")))

(deftest check-all-healthy-world
  (let [result (inv/check-all (base-world))]
    (is (:all-hold? result))))

(deftest single-resolution-payout-consistency-detects-dual-claimable
  (let [w0 (base-world)
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
        w2 (assoc-in w1 [:claimable-v2 0 :settlement/principal bob] 995)
        ;; corruption: both sides become claimable for same finalized workflow
        bad (assoc-in w2 [:claimable-v2 0 :settlement/principal alice] 995)
        r (inv/single-resolution-payout-consistent? bad)]
    (is (false? (:holds? r)))
    (is (= 0 (-> r :violations first :workflow-id)))))

(deftest fraud-slash-executions-accounted-detects-missing-stake-debit
  (let [resolver "0xResolver"
        world (-> (t/empty-world 1000)
                  (assoc-in [:pending-fraud-slashes "wf0"]
                            {:resolver resolver
                             :amount 200
                             :reason :fraud
                             :status :executed
                             :proposed-at 1000
                             :appeal-deadline 1100
                             :appeal-bond-held 0
                             :contest-deadline 0})
                  ;; corruption: executed slash not reflected in slash totals
                  (assoc-in [:resolver-slash-total resolver] 0))
        r (inv/fraud-slash-executions-accounted? world)]
    (is (false? (:holds? r)))
    (is (= resolver (-> r :violations first :resolver)))))

;; ═════════════════════════════════════════════════════════════════════════
;; Boundaries: test environment and add-held
;; ═════════════════════════════════════════════════════════════════════════

(deftest empty-world-has-expected-structure
  (testing "empty-world produces a valid initial Sew world state"
    (let [w (t/empty-world 1000)]
      (is (map? w))
      (is (= {} (:escrow-transfers w)))
      (is (= {} (:resolver-stakes w)))
      (is (= {:insurance 0 :protocol 0 :burned 0} (:bond-distribution w)))
      (is (= nil (:reentrancy-guard w))))
    (let [w (t/empty-world)]
      (is (map? w))
      (is (nil? (:current-block w))))))

(deftest empty-world-supports-held-operations
  (testing "add-held on an empty world produces correct initial state"
    (let [w (ac/add-held (t/empty-world 1000) usdc 100
              {:action "test" :reason :escrow-created
               :extra {:held/workflow-id 0 :held/actor alice}})
          idx (:held-ledger/index w)]
      (is (= 100 (get-in w [:total-held usdc])))
      (is (= {usdc 100} (:by-token idx)))
      (is (= 1 (count (:held-adjustments w))))
      (is (string? (get-in w [:held-artifacts "held-adjustment-0" :artifact/hash]))))))

(deftest add-held-zero-amount
  (testing "add-held with zero amount is valid"
    (let [w (ac/add-held (t/empty-world 1000) usdc 0
              {:action "test" :reason :escrow-created
               :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= 0 (get-in w [:total-held usdc]))))))

(deftest add-held-repeated-calls-sum
  (testing "multiple add-held calls accumulate correctly"
    (let [w0 (t/empty-world 1000)
          w1 (ac/add-held w0 usdc 50 {:action "test-1" :reason :escrow-created
                                       :extra {:held/workflow-id 0 :held/actor alice}})
          w2 (ac/add-held w1 usdc 30 {:action "test-2" :reason :appeal-bond-posted
                                       :extra {:held/workflow-id 0 :held/actor alice}})
          w3 (ac/add-held w2 usdc 20 {:action "test-3" :reason :escrow-created
                                       :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= 100 (get-in w3 [:total-held usdc])))
      (is (= 3 (count (:held-adjustments w3)))))))

(deftest add-held-large-amount
  (testing "add-held with very large amount works"
    (let [large (long 1e15)
          w (ac/add-held (t/empty-world 1000) usdc large
              {:action "test" :reason :escrow-created
               :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= large (get-in w [:total-held usdc]))))))

(deftest add-held-multiple-tokens
  (testing "add-held tracks multiple token types independently"
    (let [w0 (t/empty-world 1000)
          w1 (ac/add-held w0 usdc 100 {:action "usdc-test" :reason :escrow-created
                                        :extra {:held/workflow-id 0 :held/actor alice}})
          w2 (ac/add-held w1 :0xETH 50 {:action "eth-test" :reason :escrow-created
                                         :extra {:held/workflow-id 0 :held/actor alice}})
          w3 (ac/add-held w2 usdc 25 {:action "usdc-extra" :reason :appeal-bond-posted
                                       :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= 125 (get-in w3 [:total-held usdc])))
      (is (= 50 (get-in w3 [:total-held :0xETH])))
      (is (= 3 (count (:held-adjustments w3)))))))

(deftest add-held-regular-reason-no-auth-required
  (testing "regular (non-exceptional) reasons do not require authorization provenance"
    (let [w (ac/add-held (t/empty-world 1000) usdc 100
              {:action "test" :reason :escrow-created
               :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= 100 (get-in w [:total-held usdc]))))))

(deftest add-held-exceptional-reason-requires-auth
  (testing "exceptional reasons require authorization provenance"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires authorization provenance"
                          (ac/add-held (t/empty-world 1000) usdc 100
                            {:action "governance-correction"
                             :reason :governance-authorised-correction
                             :extra {:held/workflow-id 0 :held/actor alice}})))))

(deftest sub-held-consumes-held-balance
  (testing "sub-held after add-held produces correct net state"
    (let [w0 (t/empty-world 1000)
          w1 (ac/add-held w0 usdc 100 {:action "add" :reason :escrow-created
                                        :extra {:held/workflow-id 0 :held/actor alice}})
          w2 (ac/sub-held w1 usdc 40 {:action "sub" :reason :escrow-created
                                       :extra {:held/workflow-id 0 :held/actor alice}})]
      (is (= 60 (get-in w2 [:total-held usdc])))
      (is (= 2 (count (:held-adjustments w2)))))))

;; ---------------------------------------------------------------------------
;; Related-claims: membership and scope dimensions are independent
;; ---------------------------------------------------------------------------

(defn- related-claims-two-member-world
  "Minimal world with two held workflows (wf-a, wf-b), an active related-claims
   relationship over `relationship-workflow-ids`, and a force-authorisation
   granting exactly `granted-member-scope-hashes` (kept identical between the
   persisted grant and the caller's auth-provenance)."
  [auth-id wf-a wf-b granted-member-scope-hashes relationship-workflow-ids]
  (let [rel-id 99]
    {:total-held {usdc 200}
     :held/positions
     {[:held/position usdc :escrow-principal wf-a] 100
      [:held/position usdc :escrow-principal wf-b] 100}
     :held-ledger/index
     {:by-token {usdc 200}
      :by-position
      {[:held/position usdc :escrow-principal wf-a] 100
       [:held/position usdc :escrow-principal wf-b] 100}
      :by-account {:escrow-principal 200}
      :by-workflow {wf-a 100 wf-b 100}}
     :force-authorisations
     {auth-id {:authorization/id auth-id
               :authorization/status :active
               :consumed? false :starts-at 0
               :authorization/scope-kind :related-claims
               :relationship/id rel-id
               :relationship/hash "rel-hash"
               :member-scope-hashes granted-member-scope-hashes}}
     :related-claims
     {rel-id {:relationship/id rel-id
              :relationship/status :active
              :relationship/hash "rel-hash"
              :relationship/members
              (mapv (fn [wf-id] {:claim/kind :sew/workflow :workflow/id wf-id})
                    relationship-workflow-ids)}}}))

(deftest related-claims-valid-member-valid-scope-succeeds
  (testing "authorized member + authorized scope is accepted"
    (let [auth-id "fa-indep-ok"
          wf-a 10 wf-b 11
          scope-a {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 40
                   :held/account :escrow-principal :owner/address bob
                   :held/reason :force-authorised-release :held/workflow-id wf-a}
          hash-a (hash/domain-hash "force-authorisation-scope" scope-a)
          base (related-claims-two-member-world auth-id wf-a wf-b [hash-a] [wf-a wf-b])
          auth-prov {:authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-kind :related-claims
                     :authorization/scope-hash hash-a
                     :relationship/id 99
                     :relationship/hash "rel-hash"
                     :member-scope-hashes [hash-a]}
          w (ac/sub-held base usdc 40
                         {:action "finalize-released"
                          :reason :force-authorised-release
                          :authorization-provenance auth-prov
                          :extra {:held/workflow-id wf-a :owner/address bob}})]
      (is (= 160 (get-in w [:total-held usdc])))
      (is (true? (get-in w [:force-authorisations/consumed auth-id :consumed?]))))))

(deftest related-claims-valid-member-wrong-scope-fails
  (testing "authorized member but wrong adjustment scope is rejected (scope dimension)"
    (let [auth-id "fa-indep-scope"
          wf-a 10 wf-b 11
          scope-a {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 40
                   :held/account :escrow-principal :owner/address bob
                   :held/reason :force-authorised-release :held/workflow-id wf-a}
          scope-b {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 40
                   :held/account :escrow-principal :owner/address bob
                   :held/reason :force-authorised-release :held/workflow-id wf-b}
          hash-a (hash/domain-hash "force-authorisation-scope" scope-a)
          hash-b (hash/domain-hash "force-authorisation-scope" scope-b)
          base (related-claims-two-member-world auth-id wf-a wf-b [hash-a] [wf-a wf-b])
          auth-prov {:authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-kind :related-claims
                     :authorization/scope-hash hash-a
                     :relationship/id 99
                     :relationship/hash "rel-hash"
                     :member-scope-hashes [hash-a]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"member scope not in authorized set"
                            (ac/sub-held base usdc 40
                                         {:action "finalize-released"
                                          :reason :force-authorised-release
                                          :authorization-provenance auth-prov
                                           :extra {:held/workflow-id wf-b
                                                   :owner/address bob}}))))))

(deftest related-claims-valid-scope-non-member-wf-fails
  (testing "authorized scope but non-member workflow is rejected (membership dimension)"
    (let [auth-id "fa-indep-member"
          wf-a 10 wf-b 11
          scope-b {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 40
                   :held/account :escrow-principal :owner/address bob
                   :held/reason :force-authorised-release :held/workflow-id wf-b}
          hash-b (hash/domain-hash "force-authorisation-scope" scope-b)
          base (related-claims-two-member-world auth-id wf-a wf-b [hash-b] [wf-a])
          auth-prov {:authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-kind :related-claims
                     :authorization/scope-hash hash-b
                     :relationship/id 99
                     :relationship/hash "rel-hash"
                     :member-scope-hashes [hash-b]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not in referenced related-claims relationship"
                            (ac/sub-held base usdc 40
                                         {:action "finalize-released"
                                          :reason :force-authorised-release
                                          :authorization-provenance auth-prov
                                          :extra {:held/workflow-id wf-b
                                                  :owner/address bob}}))))))

(deftest related-claims-failed-membership-leaves-state-unchanged
  (testing "a rejected non-member adjustment does not mutate ledger or consumption state"
    (let [auth-id "fa-indep-unchanged"
          wf-a 10 wf-b 11
          scope-b {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 40
                   :held/account :escrow-principal :owner/address bob
                   :held/reason :force-authorised-release :held/workflow-id wf-b}
          hash-b (hash/domain-hash "force-authorisation-scope" scope-b)
          base (related-claims-two-member-world auth-id wf-a wf-b [hash-b] [wf-a])
          auth-prov {:authorization/type :force-authorisation
                     :authorization/id auth-id
                     :authorization/scope-kind :related-claims
                     :authorization/scope-hash hash-b
                     :relationship/id 99
                     :relationship/hash "rel-hash"
                     :member-scope-hashes [hash-b]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ac/sub-held base usdc 40
                                {:action "finalize-released"
                                 :reason :force-authorised-release
                                 :authorization-provenance auth-prov
                                 :extra {:held/workflow-id wf-b
                                         :owner/address bob}})))
      (is (= 200 (get-in base [:total-held usdc])) "ledger unchanged after rejection")
      (is (nil? (get-in base [:force-authorisations/consumed auth-id]))
          "no consumption record written after rejection"))))
