(ns resolver-sim.protocols.sew.accounting-test
  "Tests for contract_model/accounting.clj and invariants.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol      :as proto]
            [resolver-sim.protocols.sew           :as sew]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.assurance.custody        :as custody]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.yield.modules.adversarial :as adv-yield]
            [resolver-sim.yield.registry             :as yr]
            [resolver-sim.evidence.capture           :as cap]
            [resolver-sim.evidence.finalization      :as finalization]
            [resolver-sim.time.context               :as time-ctx]
            [resolver-sim.hash.canonical            :as hash]))

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

(def gov-addr
  "Shared fixture governance identity for production-shaped force-authorisation
   fixtures. Kept in one place so it cannot drift from the records it stamps."
  "0xGov")

(defn- valid-governance-force-authorisation
  "Attach canonical governance-origin provenance to a synthetic force-authorisation
   record so it models a governance-issued grant (matching the record shape
   produced by grant-force-authorisation). Mechanism fixtures that carry this
   helper represent valid production-shaped grants; fixtures without it are
   explicit lower-level mechanism tests that bypass authentication."
  [record]
  (let [auth-id (:authorization/id record)
        prov {:authorization/type :force-authorisation
              :authorization/id auth-id
              :authorization/source :governance
              :authorization/check :with-governance-actor
              :authorization/assurance :address-bound}]
    (assoc record
           :authorization/source :governance
           :nonce auth-id
           :created-by gov-addr
           :authorization/provenance prov
           :authorization/history
           [{:authorization/action "grant-force-authorisation"
             :authorization/provenance prov}])))

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
    (is (= "held-custody-adjustment.artifact.v3" (:schema-version artifact)))
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
            :held-custody/artifact-schema
            :held-custody/parameter-attribution
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
  (let [w (base-world)
        reconstruction (inv/held-adjustments-reconstruct-total-held? w)
        closed-form (inv/held-custody-closed-form? w)
        artifacts (inv/held-artifacts-derived-from-adjustments? w)]
    (doseq [result [reconstruction closed-form artifacts]]
      (is (false? (:holds? result)))
      (is (= :not-evaluated (:status result)))
      (is (= :not-evaluated-incomplete-history (:classification result)))
      (is (false? (inv/custody-validation-pass? result))))
    (is (= :held-history-not-declared-complete (:reason reconstruction)))))

(deftest reconstruction-invariant-fails-when-declared-history-is-not-zero-origin
  (let [w (-> (base-world)
              (assoc-in [:params :held-adjustments/complete?] true)
              (update :held-adjustments
                      (fn [adjs]
                        (mapv #(assoc % :held/before (+ 100 (:held/before %))
                                        :held/after (+ 100 (:held/after %)))
                              adjs))))
        result (inv/held-adjustments-reconstruct-total-held? w)]
    (is (false? (:holds? result)))
    (is (= :evaluated (:status result)))
    (is (= :evaluated-fail (:classification result)))
    (is (false? (inv/custody-validation-pass? result)))
    (is (= :held-history-invalid-opening-state (:reason result)))))

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

(deftest ordinary-sew-run-reaches-evaluated-strong-replay
  (testing "An ordinary non-test-style Sew run (init-world + create + release) declares
            held-adjustment completeness, so strong replay is evaluated/pass, reconstructs
            the final :total-held, and satisfies the zero-origin contract."
    (let [world0  (proto/init-world sew/protocol {:initial-block-time 1000})
          snap    (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :appeal-window-duration 0})
          created (lc/create-escrow world0 alice usdc bob 1000
                                    (t/make-escrow-settings {}) snap)
          released (lc/release (:world created) 0 alice (fn [_ _ _] {:allowed? true}))
          world'  (:world released)
          rec     (inv/held-adjustments-reconstruct-total-held? world')]
      (is (:ok created))
      (is (:ok released))
      (is (true? (get-in world' [:params :held-adjustments/complete?]))
          "canonical init-world declares held-adjustment completeness")
      (is (= :evaluated (:status rec))
          "ordinary run reaches evaluated/pass, not :not-evaluated")
      (is (inv/custody-validation-pass? rec) "strong replay reconstructs final :total-held")
      (is (= 0 (get-in world' [:total-held usdc] 0)))
      (is (custody/held-history-zero-origin? (:held-adjustments world'))
          "zero-origin contract holds"))))

(deftest custody-classification-distinguishes-incomplete-from-evaluated-pass
  (testing "Incomplete history is never positive assurance, while the same
            complete world has an explicit evaluated-pass classification."
    (let [world0  (proto/init-world sew/protocol {:initial-block-time 1000})
          snap    (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :appeal-window-duration 0})
          created (lc/create-escrow world0 alice usdc bob 1000
                                    (t/make-escrow-settings {}) snap)
          world'  (:world created)
          incomplete (update-in world' [:params] dissoc :held-adjustments/complete?)
          incomplete-rec (inv/held-adjustments-reconstruct-total-held? incomplete)
          evaluated-rec  (inv/held-adjustments-reconstruct-total-held? world')]
      (is (= :not-evaluated-incomplete-history (:classification incomplete-rec)))
      (is (= :evaluated-pass (:classification evaluated-rec)))
      (is (false? (:holds? incomplete-rec)))
      (is (inv/custody-validation-pass? evaluated-rec))
      (is (false? (:all-hold? (inv/check-all incomplete)))))))

(deftest canonical-held-mutation-preserves-completeness
  (testing "The canonical accounting mutation path (acct/add-held / acct/sub-held) does
            not invalidate :held-adjustments/complete?: the ledger stays reconstructable."
    (let [world (-> (proto/init-world sew/protocol {:initial-block-time 1000})
                    (ac/add-held usdc 100 {:action "create-escrow"
                                           :reason :escrow-principal-deposited
                                           :extra {:held/workflow-id 0
                                                   :owner/address alice
                                                   :held/from alice
                                                   :held/to bob}})
                    (ac/sub-held usdc 40 {:action "release"
                                          :reason :escrow-settlement-released
                                          :extra {:held/workflow-id 0
                                                  :owner/address bob}}))]
      (is (true? (get-in world [:params :held-adjustments/complete?]))
          "canonical mutations preserve the completeness declaration")
      (is (= :evaluated (:status (inv/held-adjustments-reconstruct-total-held? world))))
      (is (inv/custody-validation-pass?
           (inv/held-adjustments-reconstruct-total-held? world))))))

(deftest adversarial-yield-direct-total-held-write-invalidates-completeness
  (testing "The adversarial yield module (:drain/:bloat) writes :total-held directly
            (bypassing acct/adjust-held); it must explicitly invalidate
            :held-adjustments/complete? so strong replay stays :not-evaluated."
    (doseq [strategy [:drain :bloat]]
      (let [module (adv-yield/make-adversarial-module :adversarial)
            world  {:yield/indices {:adversarial {:USDC 1.0}}
                    :yield/positions {"p" {:owner/id "p" :module/id :adversarial
                                           :token :USDC :status :active
                                           :principal 100 :shares 100 :entry-index 1.0
                                           :unrealized-yield 0 :realized-yield 0}}
                    :yield/adversary {:adversarial {:strategy strategy}}
                    :total-held {:USDC 100}
                    :params {:held-adjustments/complete? true}}
            world' (adv-yield/adversarial-accrue world module {:token :USDC :dt 1})]
        (is (false? (get-in world' [:params :held-adjustments/complete?]))
            (str strategy " direct write invalidates completeness"))))))

(deftest direct-total-held-write-without-clear-fails-reconstruction
  (testing "A direct :total-held write that bypasses adjust-held but does NOT clear
            :held-adjustments/complete? is caught by strong replay: reconstruction
            must fail. (This is the invariant-level consequence that the liquid-lending
            pro-rata direct write clears the flag to avoid.)"
    (let [world  (proto/init-world sew/protocol {:initial-block-time 1000})
          created (lc/create-escrow world alice usdc bob 1000
                                    (t/make-escrow-settings {}) snap)
          base   (:world created)
          ;; Simulate a pro-rata-style direct write: assoc :total-held to a value the
          ;; ledger does not reflect, leaving complete? true.
          bypassed (assoc-in base [:total-held usdc]
                             (+ (get-in base [:total-held usdc] 0) 123))
          rec (inv/held-adjustments-reconstruct-total-held? bypassed)]
      (is (:holds? (inv/held-adjustments-reconstruct-total-held? base))
          "a properly-mutated world reconstructs")
      (is (false? (:holds? rec))
          "an un-declared direct :total-held write breaks reconstruction")
      (is (= :evaluated (:status rec))))))

(deftest settlement-evidence-surfaces-write-down
  (testing "A negative-yield (mark-to-market) principal write-down is surfaced on the
            settlement evidence as :finalize/write-down, so the reconciliation
            `owed (afa) - claimable == write-down` is visible at settlement without
            replaying the held ledger."
    (let [captured (atom [])
          recorder (fn [& args] (swap! captured conj args) nil)
          scenario {:initial-block-time 1000
                    :yield-config {:modules {:fixed-rate {:tokens {"USDC" {:apy -0.5
                                                                           :failure-modes #{:negative-yield}}}}}}}
          world0   (proto/init-world sew/protocol scenario)
          snap     (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate
                                              :escrow-fee-bps 50
                                              :yield-protocol-fee-bps 0
                                              :appeal-window-duration 0})
          created  (lc/create-escrow world0 alice "USDC" bob 10000
                                     (t/make-escrow-settings {:yield-preset :to-recipient}) snap)
          world1   (:world created)
          world2   (time-ctx/advance-time world1 {:seconds 31536000})
          world3   (lc/accrue-yield world2 0)
          afa      (get-in world3 [:escrow-transfers 0 :amount-after-fee])
          release  (with-redefs [resolver-sim.evidence.capture/capture-event-evidence! recorder]
                     (lc/release world3 0 alice (fn [_ _ _] {:allowed? true})))
          released (filter #(= :escrow-released (first %)) @captured)
          inputs   (some-> (first released) (nth 3))]
      (is (:ok release))
      (is (seq released) "escrow-released evidence was captured")
      (is (map? inputs))
      (let [write-down (:finalize/write-down inputs)
            claimable (get-in (:world release) [:claimable-v2 0 :settlement/principal bob] 0)]
        (is (pos? write-down) "negative-yield principal write-down is surfaced")
        (is (= (- afa claimable) write-down)
            "reconciliation: owed (afa) - claimable == write-down")))))

(defn- capture-terminal-settle
  "Run f (which must settle an escrow), capturing the emitted terminal-settlement
   evidence as finalized artifacts. Returns {:result result :artifacts [...]}."
  [f]
  (let [captured (atom [])
        recorder (fn [& args] (swap! captured conj args) nil)
        result (with-redefs [resolver-sim.evidence.capture/capture-event-evidence! recorder]
                 (f))
        artifacts (into []
                        (keep (fn [args]
                                (when (and (sequential? args) (seq args))
                                  (let [[reason pre post inputs] args]
                                    (when (contains? #{:escrow-released :escrow-refunded} reason)
                                      (-> (cap/evidence-base {:type reason :importance :core})
                                          (assoc :inputs inputs :pre-state pre :post-state post)
                                          (cap/finalize-evidence)))))))
                        @captured)]
    {:result result :artifacts artifacts}))

(deftest settlement-reconciliation-normal
  (testing "Normal one-shot release: write-down 0, evidence fidelity holds, and the
            settlement equation owed = claimable + write-down + deferred + haircut holds."
    (let [w0 (proto/init-world sew/protocol {:initial-block-time 1000})
          snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :appeal-window-duration 0})
          created (lc/create-escrow w0 alice "USDC" bob 10000
                                    (t/make-escrow-settings {}) snap)
          w1 (:world created)
          {:keys [result artifacts]} (capture-terminal-settle
                                      #(lc/release w1 0 alice (fn [_ _ _] {:allowed? true})))
          w2 (:world result)
          rec (inv/settlement-reconciliation? w1 w2)
          fid (custody/verify-settlement-evidence-fidelity w2 artifacts)
          reported (get-in (first artifacts) [:inputs :finalize/write-down])]
      (is (:ok result))
      (is (zero? reported) "normal settlement has zero write-down")
      (is (= 0 (custody/ledger-workflow-write-down w2 0)))
      (is (:holds? rec) "owed = claimable + write-down + deferred + haircut")
      (is (:holds? fid) "evidence write-down equals ledger write-down"))))

(deftest settlement-reconciliation-negative-yield
  (testing "Negative-yield (mark-to-market) settlement: write-down positive, evidence
            reports the correct value, and the settlement equation holds."
    (let [scenario {:initial-block-time 1000
                    :yield-config {:modules {:fixed-rate {:tokens {"USDC" {:apy -0.5
                                                                           :failure-modes #{:negative-yield}}}}}}}
          w0 (proto/init-world sew/protocol scenario)
          snap (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate
                                          :escrow-fee-bps 50
                                          :yield-protocol-fee-bps 0
                                          :appeal-window-duration 0})
          created (lc/create-escrow w0 alice "USDC" bob 10000
                                    (t/make-escrow-settings {:yield-preset :to-recipient}) snap)
          w1 (:world created)
          w2 (-> w1 (time-ctx/advance-time {:seconds 31536000}) (lc/accrue-yield 0))
          {:keys [result artifacts]} (capture-terminal-settle
                                      #(lc/release w2 0 alice (fn [_ _ _] {:allowed? true})))
          w3 (:world result)
          rec (inv/settlement-reconciliation? w2 w3)
          fid (custody/verify-settlement-evidence-fidelity w3 artifacts)
          reported (get-in (first artifacts) [:inputs :finalize/write-down])
          ledger (custody/ledger-workflow-write-down w3 0)]
      (is (:ok result))
      (is (pos? reported) "negative-yield write-down is positive")
      (is (= ledger reported) "evidence reports the ledger-derived write-down")
      (is (:holds? rec) "owed = claimable + write-down + deferred + haircut")
      (is (:holds? fid) "evidence fidelity: :finalize/write-down == ledger write-down"))))

(deftest settlement-reconciliation-liquidity-shortfall
  (testing "Liquidity-shortfall settlement: write-down 0 (deferred/haircut carry the
            shortfall), no reclassification to :yield-negative-excess, and the
            settlement equation holds against the canonical shortfall basis."
    (let [cfg {:modules {:aave-v3 {:tokens {"USDC" {:initial-index 1.0 :apy 0.08
                                                    :loss-mode :none
                                                    :failure-modes #{:partial-liquidity}
                                                    :shortfall {:available-ratio 0.5
                                                                :reason :liquidity-shortfall}}}}}}
          w0 (-> (proto/init-world sew/protocol {:initial-block-time 1000})
                 (yr/apply-yield-config cfg))
          snap (snap-fix/escrow-snapshot {:yield-generation-module :yield.provider/liquid-lending
                                          :yield-protocol-fee-bps 1000
                                          :appeal-window-duration 0})
          created (lc/create-escrow w0 alice "USDC" bob 100000
                                    (t/make-escrow-settings {:yield-preset :to-recipient}) snap)
          w1 (:world created)
          w2 (-> w1 (time-ctx/advance-time {:seconds 315360000}))
          {:keys [result artifacts]} (capture-terminal-settle
                                      #(lc/release w2 0 alice (fn [_ _ _] {:allowed? true})))
          w3 (:world result)
          pos (get-in w3 [:yield/positions (t/escrow-yield-owner-id 0)])
          rec (inv/settlement-reconciliation? w2 w3)
          fid (custody/verify-settlement-evidence-fidelity w3 artifacts)
          wd (custody/ledger-workflow-write-down w3 0)
          yield-excess-adj (filter #(= :yield-negative-excess (:held/reason %))
                                   (filter #(= 0 (:held/workflow-id %)) (:held-adjustments w3 [])))]
      (is (:ok result))
      (is (some? (:shortfall pos)) "liquidity shortfall recorded")
      (is (zero? wd) "shortfall contributes zero write-down")
      (is (zero? (get-in (first artifacts) [:inputs :finalize/write-down])))
      (is (empty? yield-excess-adj)
          "a liquidity shortfall is not reclassified as :yield-negative-excess")
      (is (:holds? rec) "owed = claimable + write-down + deferred + haircut (shortfall basis)")
      (is (:holds? fid) "evidence fidelity: write-down 0 == ledger 0"))))

(deftest settlement-write-down-tamper-detected
  (testing "Tampering with or removing a positive :finalize/write-down is detected by
            artifact content-hash verification, and a recomputed leaf hash is no
            longer covered by the committed evidence-hash-set root."
    (let [scenario {:initial-block-time 1000
                    :yield-config {:modules {:fixed-rate {:tokens {"USDC" {:apy -0.5
                                                                           :failure-modes #{:negative-yield}}}}}}}
          w0 (proto/init-world sew/protocol scenario)
          snap (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate
                                          :escrow-fee-bps 50
                                          :yield-protocol-fee-bps 0
                                          :appeal-window-duration 0})
          created (lc/create-escrow w0 alice "USDC" bob 10000
                                    (t/make-escrow-settings {:yield-preset :to-recipient}) snap)
          w1 (:world created)
          w2 (-> w1 (time-ctx/advance-time {:seconds 31536000}) (lc/accrue-yield 0))
          {:keys [artifacts]} (capture-terminal-settle
                               #(lc/release w2 0 alice (fn [_ _ _] {:allowed? true})))
          artifact (first artifacts)
          committed (finalization/build-hash-set [(:evidence/hash artifact)])
          mutated (assoc-in artifact [:inputs :finalize/write-down] 0)
          removed (update-in artifact [:inputs] dissoc :finalize/write-down)
          recomputed (cap/finalize-evidence
                      (dissoc mutated :evidence/hash :evidence/timestamp))]
      (is (seq artifacts))
      (is (pos? (get-in artifact [:inputs :finalize/write-down])))
      (is (custody/artifact-content-hash-valid? artifact) "untampered artifact verifies")
      (is (not (custody/artifact-content-hash-valid? mutated))
          "mutating write-down fails artifact content verification")
      (is (not (custody/artifact-content-hash-valid? removed))
          "removing write-down fails artifact content verification")
      (is (not (contains? (set (:hashes committed)) (:evidence/hash recomputed)))
          "recomputed leaf hash is not covered by the committed evidence-hash-set root"))))

(deftest held-artifacts-must-match-derived-ledger-view
  (let [world (-> (assoc-in (t/empty-world) [:params :held-adjustments/complete?] true)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}}))
        tampered (assoc-in world
                           [:held-artifacts "held-adjustment-0" :amount]
                           999)]
    (is (inv/custody-validation-pass?
         (inv/held-artifacts-derived-from-adjustments? world)))
    (is (= :evaluated-fail
           (:classification (inv/held-artifacts-derived-from-adjustments? tampered))))
    (is (false? (:holds? (inv/held-artifacts-derived-from-adjustments? tampered))))))

(deftest held-adjustments-record-and-verify-parameter-attribution
  (let [parameter-root (str "sha256:" (apply str (repeat 64 "a")))
        context {:parameter-context/type :protocol-parameters
                 :parameter-context/root parameter-root
                 :parameter-context/version 1
                 :parameter-context/scope-id 42}
        address {:parameter/id :sew/escrow-principal}
        world (ac/add-held (t/empty-world) usdc 100
                            {:action "create-escrow"
                             :reason :escrow-principal-deposited
                             :parameter/context context
                             :parameter/address address
                             :extra {:held/workflow-id 42
                                     :owner/address alice}})
        adjustment (last (:held-adjustments world))
        artifact (get-in world [:held-artifacts (:held-adjustment/id adjustment)])
        replayed (custody/replay-held-adjustment-state (:held-adjustments world))]
    (is (= context (:parameter/context adjustment)))
    (is (= address (:parameter/address adjustment)))
    (is (= context (:parameter/context artifact)))
    (is (= address (:parameter/address artifact)))
    (is (= (:total-held world) (:total-held replayed)))
    (is (every? #(= :pass (:status %))
                (custody/held-custody-closed-form-checks [artifact])))))

(deftest held-adjustments-reject-incomplete-or-invalid-parameter-attribution
  (let [parameter-root (str "sha256:" (apply str (repeat 64 "a")))
        context {:parameter-context/type :protocol-parameters
                 :parameter-context/root parameter-root
                 :parameter-context/version 1}
        address {:parameter/id :sew/escrow-principal}
        reason-of (fn [f]
                    (try (f) nil
                         (catch clojure.lang.ExceptionInfo e
                           (:reason (ex-data e)))))]
    (is (= :parameter-context-without-address
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context context}))))
    (is (= :parameter-address-without-context
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/address address}))))
    (is (= :invalid-parameter-context
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context {:parameter-context/type :protocol-parameters}
                                     :parameter/address address}))))
    (is (= :invalid-parameter-address
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context context
                                     :parameter/address {:parameter/id nil}})))
        "empty parameter addresses are rejected")
    (is (= :invalid-parameter-context
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context (assoc context :parameter-context/id :sew/default)
                                     :parameter/address address})))
        "root/version and interim id forms are mutually exclusive")
    (is (= :invalid-parameter-address
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context context
                                     :parameter/address {:parameter/id :sew/escrow-principal
                                                         :parameter/path [:escrow :principal]}})))
        "semantic-id and path forms are mutually exclusive")
    (is (= :invalid-parameter-context
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context {:parameter-context/type :world-params
                                                         :parameter-context/id :sew/default
                                                         :parameter-context/version 1}
                                     :parameter/address address})))
        "interim contexts reject root-form-only fields")
    (doseq [address' [{:parameter/id :sew/escrow-principal :parameter/path []}
                      {:parameter/id :sew/escrow-principal
                       :parameter/path [{:runtime "map"}]}]]
      (is (= :invalid-parameter-address
             (reason-of #(ac/add-held (t/empty-world) usdc 1
                                      {:parameter/context context
                                       :parameter/address address'})))
          "semantic IDs cannot mask malformed path-form fields"))
    (is (= :invalid-parameter-context
           (reason-of #(ac/add-held (t/empty-world) usdc 1
                                    {:parameter/context (assoc context :parameter-context/root "not-really-a-root")
                                     :parameter/address address})))
        "roots must be canonical sha256 references")
    (doseq [path [[] [[:unexpected "nested"]] [{:runtime "map"}]]]
      (is (= :invalid-parameter-address
             (reason-of #(ac/add-held (t/empty-world) usdc 1
                                      {:parameter/context context
                                       :parameter/address {:parameter/path path}})))
          (str "path is limited to canonical scalar segments: " (pr-str path))))))

(deftest held-adjustment-primitive-rejects-invalid-provenance-projections
  (let [malformed {:held/direction :in :token usdc :amount 1
                   :parameter/context {:parameter-context/type :protocol-parameters
                                       :parameter-context/root (str "sha256:" (apply str (repeat 64 "a")))
                                       :parameter-context/version 1}
                   :parameter/address nil}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot project invalid held adjustment scope"
                          (held-adjustment/project-held-adjustment-scope malformed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot build invalid held adjustment"
                          (held-adjustment/build-held-adjustment malformed)))))

(deftest held-adjustments-reject-parameter-attribution-in-extra
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        context {:parameter-context/type :protocol-parameters
                 :parameter-context/root root :parameter-context/version 1}
        address {:parameter/id :sew/escrow-principal}
        base {:total-held {usdc 100}
              :held/positions {[:held/position usdc :escrow-principal 42] 100}
              :held-ledger/index {:by-token {usdc 100}
                                  :by-position {[:held/position usdc :escrow-principal 42] 100}}}
        rejected? (fn [f]
                    (try (f) false
                         (catch clojure.lang.ExceptionInfo e
                           (= :reserved-parameter-attribution-in-extra
                              (:reason (ex-data e))))))]
    (is (rejected? #(ac/add-held (t/empty-world) usdc 1
                                 {:extra {:parameter/context context
                                          :parameter/address address}})))
    (is (rejected? #(ac/sub-held base usdc 1
                                 {:reason :escrow-settlement-released
                                  :extra {:held/workflow-id 42 :owner/address bob
                                          :parameter/context context}})))
    (is (= 100 (get-in base [:total-held usdc]))
        "rejected nested provenance leaves the caller world unchanged")))

(deftest sub-held-parameter-attribution-round-trip-and-tamper
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        context {:parameter-context/type :protocol-parameters
                 :parameter-context/root root :parameter-context/version 1}
        address {:parameter/id :sew/escrow-principal}
        position [:held/position usdc :escrow-principal 42]
        world (ac/sub-held {:total-held {usdc 100}
                             :held/positions {position 100}
                             :held-ledger/index {:by-token {usdc 100}
                                                 :by-position {position 100}}}
                           usdc 40
                           {:action "release" :reason :escrow-settlement-released
                            :parameter/context context :parameter/address address
                            :extra {:held/workflow-id 42 :owner/address bob}})
        adjustment (last (:held-adjustments world))
        artifact (get-in world [:held-artifacts (:held-adjustment/id adjustment)])]
    (is (= context (:parameter/context adjustment)))
    (is (= address (:parameter/address adjustment)))
    (is (= (:total-held world)
           (:total-held (custody/replay-held-adjustment-state {usdc 100}
                                                              (:held-adjustments world)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed-form checks failed"
                          (custody/held-custody-closed-form-checks
                           [(assoc-in artifact [:parameter/context :parameter-context/root]
                                      (str "sha256:" (apply str (repeat 64 "b"))))])))))

(deftest parameter-attribution-is-committed-to-artifact-hash
  (let [root-a (str "sha256:" (apply str (repeat 64 "a")))
        root-b (str "sha256:" (apply str (repeat 64 "b")))
        context-a {:parameter-context/type :protocol-parameters
                   :parameter-context/root root-a
                   :parameter-context/version 1}
        context-b (assoc context-a :parameter-context/root root-b)
        address-a {:parameter/id :sew/escrow-principal}
        address-b {:parameter/id :sew/escrow-fee}
        adjustment {:held-adjustment/id "held-adjustment-0"
                    :held/direction :in :token usdc :amount 100
                    :held/before 0 :held/after 100
                    :held/reason :escrow-principal-deposited :held/action "create-escrow"}
        artifact-a (custody/build-held-custody-artifact
                    (assoc adjustment :parameter/context context-a :parameter/address address-a))
        artifact-b (custody/build-held-custody-artifact
                    (assoc adjustment :parameter/context context-a :parameter/address address-b))
        artifact-c (custody/build-held-custody-artifact
                    (assoc adjustment :parameter/context context-b :parameter/address address-a))]
    (is (not= (:artifact/hash artifact-a) (:artifact/hash artifact-b)))
    (is (not= (:artifact/hash artifact-a) (:artifact/hash artifact-c)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed-form checks failed"
                          (custody/held-custody-closed-form-checks
                           [(assoc artifact-a :parameter/address address-b)])))))

(deftest held-custody-artifact-version-contract
  (let [adjustment {:held-adjustment/id "held-adjustment-0"
                    :held/direction :in :token usdc :amount 100
                    :held/before 0 :held/after 100
                    :held/reason :held/unspecified :held/action "add-held"}
        v3 (custody/build-held-custody-artifact adjustment)
        v2-body (assoc (custody/held-custody-artifact-payload
                        (assoc v3 :schema-version "held-custody-adjustment.artifact.v2"))
                       :schema-version "held-custody-adjustment.artifact.v2")
        v2 (assoc v3 :schema-version "held-custody-adjustment.artifact.v2"
                   :artifact/hash (str "sha256:" (hash/hash-with-intent
                                                   {:hash/intent :evidence-record} v2-body)))
        v2-with-provenance (assoc v2 :parameter/context {:parameter-context/type :protocol-parameters
                                                          :parameter-context/id :sew/default}
                                    :parameter/address {:parameter/id :sew/escrow-principal})
        v3-with-v2-hash (assoc v3 :artifact/hash (:artifact/hash v2))
        attribution-status (fn [artifact]
                             (->> (custody/held-custody-closed-form-checks [artifact])
                                  (filter #(= :held-custody/parameter-attribution (:check/id %)))
                                  first :details :attributions first))
        attributed-v3 (custody/build-held-custody-artifact
                       (assoc adjustment
                              :parameter/context {:parameter-context/type :world-params
                                                  :parameter-context/id :sew/default}
                              :parameter/address {:parameter/id :sew/escrow-principal}))]
    (is (= :legacy-v2 (:parameter-attribution/classification (attribution-status v2))))
    (is (= :unattributed-v3 (:parameter-attribution/classification (attribution-status v3))))
    (is (= :attributed-v3 (:parameter-attribution/classification (attribution-status attributed-v3))))
    (is (every? #(= :pass (:status %)) (custody/held-custody-closed-form-checks [v2])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed-form checks failed"
                          (custody/held-custody-closed-form-checks [v2-with-provenance])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed-form checks failed"
                          (custody/held-custody-closed-form-checks [v3-with-v2-hash])))))

(deftest force-authorisation-scope-binds-parameter-attribution
  (let [auth-id "fa-parameter-scope-a1b2c3d4"
        parameter-root (str "sha256:" (apply str (repeat 64 "a")))
        context {:parameter-context/type :protocol-parameters
                 :parameter-context/root parameter-root
                 :parameter-context/version 1}
        granted-address {:parameter/id :sew/escrow-principal}
        attempted-address {:parameter/id :sew/escrow-fee}
        scope {:authorization/id auth-id
               :authorization/type :force-authorisation
               :held/direction :out :token usdc :amount 40
               :held/account :escrow-principal
               :held/position-id [:held/position usdc :escrow-principal 42]
               :owner/address bob
               :held/reason :force-authorised-release :held/workflow-id 42
               :parameter/context context :parameter/address granted-address}
        scope-hash (hash/domain-hash "force-authorisation-scope" scope)
        world {:total-held {usdc 100}
               :held/positions {[:held/position usdc :escrow-principal 42] 100}
               :held-ledger/index {:by-token {usdc 100}
                                   :by-position {[:held/position usdc :escrow-principal 42] 100}}
               :force-authorisations {auth-id {:authorization/id auth-id
                                               :authorization/status :active :consumed? false :starts-at 0
                                               :authorization/scope scope
                                               :authorization/scope-hash scope-hash}}}
        provenance {:authorization/type :force-authorisation
                    :authorization/id auth-id :authorization/scope-hash scope-hash}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scope differs from grant"
                          (ac/sub-held world usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance provenance
                                        :parameter/context context
                                        :parameter/address attempted-address
                                        :extra {:held/workflow-id 42 :owner/address bob}})))))

(deftest force-authorisation-parameter-provenance-transition-matrix
  (let [root-a (str "sha256:" (apply str (repeat 64 "a")))
        root-b (str "sha256:" (apply str (repeat 64 "b")))
        context-a {:parameter-context/type :protocol-parameters
                   :parameter-context/root root-a :parameter-context/version 1}
        context-b (assoc context-a :parameter-context/root root-b)
        address-a {:parameter/id :sew/escrow-principal}
        address-instance-a (assoc address-a :parameter/instance 1)
        address-instance-b (assoc address-a :parameter/instance 2)
        attempt (fn [granted-context granted-address execution-context execution-address]
                  (let [auth-id "fa-parameter-transition"
                        scope (cond-> {:authorization/id auth-id
                                       :authorization/type :force-authorisation
                                       :held/direction :out :token usdc :amount 40
                                       :held/account :escrow-principal :owner/address bob
                                       :held/reason :force-authorised-release :held/workflow-id 42}
                                granted-context (assoc :parameter/context granted-context)
                                granted-address (assoc :parameter/address granted-address))
                        scope-hash (hash/domain-hash "force-authorisation-scope" scope)
                        world {:total-held {usdc 100}
                               :held/positions {[:held/position usdc :escrow-principal 42] 100}
                               :held-ledger/index {:by-token {usdc 100}
                                                   :by-position {[:held/position usdc :escrow-principal 42] 100}}
                               :force-authorisations {auth-id {:authorization/id auth-id
                                                               :authorization/status :active
                                                               :consumed? false :starts-at 0
                                                               :authorization/scope scope
                                                               :authorization/scope-hash scope-hash}}}
                        opts (cond-> {:action "finalize-released"
                                      :reason :force-authorised-release
                                      :authorization-provenance {:authorization/type :force-authorisation
                                                                 :authorization/id auth-id
                                                                 :authorization/scope-hash scope-hash}
                                      :extra {:held/workflow-id 42 :owner/address bob}}
                               execution-context (assoc :parameter/context execution-context)
                               execution-address (assoc :parameter/address execution-address))]
                    (try
                      (ac/sub-held world usdc 40 opts)
                      :pass
                      (catch clojure.lang.ExceptionInfo e
                        (:type (ex-data e))))))]
    (is (= :pass (attempt nil nil nil nil)) "legacy scope hashes remain valid")
    (is (= :pass (attempt context-a address-a context-a address-a)))
    (doseq [[label granted-context granted-address execution-context execution-address]
            [["address changed" context-a address-a context-a {:parameter/id :sew/escrow-fee}]
             ["provenance removed" context-a address-a nil nil]
             ["provenance inserted" nil nil context-a address-a]
             ["context root changed" context-a address-a context-b address-a]
             ["instance changed" context-a address-instance-a context-a address-instance-b]]]
      (is (= :authorization/grant-scope-mismatch
             (attempt granted-context granted-address execution-context execution-address))
          label))))

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
                              :force-authorisations {auth-id (valid-governance-force-authorisation
                                                              {:authorization/id auth-id
                                                               :authorization/status :active
                                                               :consumed? false
                                                               :starts-at 0
                                                               :authorization/scope scope-map
                                                               :authorization/scope-hash scope-hash})}}
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
                            {auth-id (valid-governance-force-authorisation
                                      {:authorization/id auth-id
                                       :authorization/status :active
                                       :consumed? false
                                       :starts-at 0
                                       :authorization/scope-kind :related-claims
                                       :relationship/id rel-id
                                       :relationship/hash "rel-hash"
                                       :member-scope-hashes [hash-0 hash-1]
                                       :authorization/scope scope-0
                                       :authorization/scope-hash hash-0})}
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
              {auth-id (valid-governance-force-authorisation
                        {:authorization/id auth-id
                         :authorization/status :active
                         :consumed? false :starts-at 0
                         :authorization/scope-kind :related-claims
                         :relationship/id rel-id
                         :relationship/hash "rel-hash"
                         :member-scope-hashes [hash-0 hash-1]
                         :authorization/scope scope-0
                         :authorization/scope-hash hash-0})}
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

;; ── Mechanism / unit fixtures (bypass authentication) ────────────────────────
;;
;; The rejection fixtures below deliberately construct force-authorisation
;; records WITHOUT governance provenance. They isolate lower-level mechanism
;; behavior (ensure-force-authorisation-usable!, scope matching, member
;; rejection, consumption) and bypass the governance-gated grant path; they are
;; not production-shaped grants. They are NOT run through check-all, so the
;; force-authorisations-governance-origin invariant is not applied to them.

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
  (let [world (assoc-in (base-world) [:params :held-adjustments/complete?] true)
        result (inv/check-all world)]
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
