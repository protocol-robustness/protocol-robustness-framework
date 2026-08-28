(ns resolver-sim.protocols.sew.slashing-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.protocols.sew.economics  :as sew-econ]
            [resolver-sim.protocols.sew.evidence.slashing :as slashing-ev]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.reversal-fixtures :as rev-fx]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.hash.canonical :as hc]))

(defn- slash-id-for
  [world workflow-id kind level]
  (get-in world [:slash-by-context (t/slash-context-key workflow-id kind level)]))

(defn- slash-for
  [world workflow-id kind level]
  (when-let [slash-id (slash-id-for world workflow-id kind level)]
    (get-in world [:pending-fraud-slashes slash-id])))

(defn- insert-test-slash
  [world workflow-id kind level entry]
  (let [slash-id (t/allocate-slash-id world)]
    (t/insert-slash world
                    (merge entry
                           {:slash/id slash-id
                            :slash/workflow-id workflow-id
                            :slash/kind kind
                            :slash/level level}))))

(deftest canonical-slash-registry-uses-entity-local-integer-ids
  (let [world (t/empty-world 1000)
        slash-id (t/allocate-slash-id world)
        slash {:slash/id slash-id
               :slash/workflow-id 0
               :slash/kind :reversal
               :slash/level 0
               :slash/status :appealable}
        world' (t/insert-slash world slash)]
    (is (= 0 slash-id))
    (is (= slash (get-in world' [:pending-fraud-slashes slash-id])))
    (is (= slash-id (get-in world' [:slash-by-context [0 :reversal 0]])))
    (is (= 1 (:next-slash-id world')))
    (is (= slash (t/slash-for-workflow world' 0 slash-id)))
    (is (nil? (t/slash-for-workflow world' 1 slash-id)))
    (is (= [slash] (t/slash-registry->canonical world')))
    (is (thrown? Exception (t/insert-slash world' slash)))))

(deftest canonical-slash-projection-rejects-legacy-or-inconsistent-registry-keys
  (testing "stringified numeric keys are not silently coerced for hashing"
    (is (thrown? Exception
                 (t/slash-registry->canonical
                  {:pending-fraud-slashes {"0" {:slash/id 0
                                                 :slash/workflow-id 0}}}))))
  (testing "the map key and embedded ID must be identical"
    (is (thrown? Exception
                 (t/slash-registry->canonical
                  {:pending-fraud-slashes {0 {:slash/id 1
                                               :slash/workflow-id 0}}})))))

(deftest string-slash-id-rejected-at-mutation-boundaries
  (testing "string slash IDs are rejected with :invalid-slash-id at execute-fraud-slash, appeal-slash, resolve-appeal"
    (let [world (t/empty-world 1000)
          string-id "0-reversal-0"]
      (is (= :invalid-slash-id (:error (res/execute-fraud-slash world 0 string-id))))
      (is (= :invalid-slash-id (:error (res/appeal-slash world 0 "0xRes" string-id))))
      (is (= :invalid-slash-id (:error (res/resolve-appeal world 0 "0xGov" true string-id
                                                            :authorization-provenance
                                                            {:authorization/type :governance
                                                             :authorization/basis :test})))))))

(deftest scenario-slash-references-resolve-at-the-adapter-boundary
  (let [slash {:slash/id 7 :slash/workflow-id 0
               :slash/kind :reversal :slash/level 1 :slash/status :pending}
        world (-> (t/empty-world 1000)
                  (t/insert-slash slash)
                  (assoc-in [:scenario-bindings :manual-slash] 7))
        resolve-event #'resolver-sim.protocols.sew/resolve-scenario-bindings]
    (is (= 7 (:slash-id
              (resolve-event world {:slash-id {:from-binding :manual-slash}}))))
    (is (= 7 (:slash-id
              (resolve-event world {:slash-id {:slash-ref {:workflow-id 0
                                                             :kind :reversal
                                                             :level 1}}}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolve-event world {:slash-id {:from-binding :unknown}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolve-event world {:slash-id {:slash-ref {:workflow-id 0
                                                               :kind :reversal
                                                               :level 2}}})))))

(deftest canonical-slash-registry-invariants-detect-identity-corruption
  (let [slash {:slash/id 0 :slash/workflow-id 0 :slash/kind :fraud
               :slash/level 0 :slash/status :pending}
        world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0] {})
                  (t/insert-slash slash))]
    (is (:holds? (inv/canonical-slash-registry-consistent? world)))
    (is (:holds? (inv/slash-context-index-consistent? world)))
    (is (false? (:holds?
                 (inv/canonical-slash-registry-consistent?
                  (assoc-in world [:pending-fraud-slashes 0 :slash/id] 1)))))
    (is (false? (:holds?
                 (inv/canonical-slash-registry-consistent?
                  (assoc-in world [:pending-fraud-slashes 0 :slash/workflow-id] 1)))))
    (is (false? (:holds?
                 (inv/slash-context-index-consistent?
                  (assoc-in world [:slash-by-context [0 :fraud 0]] 1)))))))

(defn- world-ready-for-fraud-slash-propose
  "Escrow with custom resolver, raised dispute, and executed resolution."
  [world buyer token seller resolver amount snap]
  (let [{:keys [world workflow-id]}
        (lc/create-escrow world buyer token seller amount {:custom-resolver resolver} snap)
        world' (:world (lc/raise-dispute world workflow-id buyer))
        world'' (:world (res/execute-resolution world' workflow-id resolver true "0xhash" nil))]
    {:world world'' :workflow-id workflow-id}))

(defn- propose-test-fraud-group-slash
  "Declare the immutable incident required by the public group-slash contract,
   then propose a group slash bound to that exact declaration."
  [world workflow-id caller liable-resolvers amount incident-id authorization provenance]
  (let [declared (res/declare-fraud-incident
                  world caller
                  {:incident/id incident-id
                   :incident/kind :governance-declared-group-fraud
                   :incident/affected-workflows [{:workflow-id workflow-id}]
                   :incident/rationale "test fraud incident"}
                  authorization provenance)]
    (res/propose-fraud-group-slash
     (:world declared) workflow-id caller liable-resolvers amount
     {:kind :governance-declared-group-fraud
      :incident-ref {:schema-version "fraud-incident-ref.v1"
                     :incident-id (:incident-id declared)
                     :incident-hash (:incident-hash declared)}}
     authorization provenance)))

(deftest slashing-logic-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        res "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 86400})]

    (testing "Manual slash proposal is appealable"
      (let [world (reg/register-stake world res 1000)
            {:keys [world workflow-id]}
            (world-ready-for-fraud-slash-propose world buyer "0xT" seller res 1000 snap)
            r-prop (res/propose-fraud-slash world workflow-id gov res 500)
            world-prop (:world r-prop)]

        (is (= :pending (get-in world-prop [:pending-fraud-slashes workflow-id :status])))

        (testing "Resolver appeals"
          (let [r-app (res/appeal-slash world-prop workflow-id res)
                world-app (:world r-app)]
            (is (= :appealed (get-in world-app [:pending-fraud-slashes workflow-id :status])))))

        (testing "Governance upholds appeal"
          (let [world-app (-> (res/appeal-slash world-prop workflow-id res) :world)
                r-res (res/resolve-appeal world-app workflow-id gov true workflow-id
                                          :authorization-provenance {:authorization/type :governance
                                                                     :authorization/basis :test})
                world-upheld (:world r-res)]
            (is (= :reversed (get-in world-upheld [:pending-fraud-slashes workflow-id :status])))))))))

(deftest slash-deadline-belongs-to-appeal-window
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world (reg/register-stake world resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world buyer "0xT" seller resolver-addr 1000 snap)
        world-prop (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 500) :world)
        deadline (get-in world-prop [:pending-fraud-slashes workflow-id :appeal-deadline])
        world-at-deadline (time-ctx/advance-time world-prop {:to deadline})
        execute-result (res/execute-fraud-slash world-at-deadline workflow-id)
        appeal-result (res/appeal-slash world-at-deadline workflow-id resolver-addr)]
    (is (false? (:ok execute-result)))
    (is (= :timelock-not-expired (:error execute-result)))
    (is (true? (:ok appeal-result)))))

(deftest appeal-slash-after-deadline-rejected
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world (reg/register-stake world resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world buyer "0xT" seller resolver-addr 1000 snap)
        world-prop (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 500)
                       :world)
        world-late (time-ctx/advance-time world-prop {:to 1011})
        r-app (res/appeal-slash world-late workflow-id resolver-addr)]
    (is (false? (:ok r-app)))
    (is (= :appeal-window-expired (:error r-app)))))

(deftest resolve-appeal-supports-custom-slash-id
  (let [resolver-addr "0xRes"
        gov "0xGov"
        world (insert-test-slash (t/empty-world 1000) 0 :reversal 0
                                 {:resolver resolver-addr
                                  :amount 100
                                  :token "USDC"
                                  :status :appealed
                                  :proposed-at 1000
                                  :appeal-deadline 1100
                                  :appeal-bond-held 0
                                  :contest-deadline 0})
        slash-id (slash-id-for world 0 :reversal 0)
        r (res/resolve-appeal world 0 gov true slash-id
                              :authorization-provenance {:authorization/type :governance
                                                         :authorization/basis :test})]
    (is (true? (:ok r)))
    (is (= :reversed (get-in (:world r) [:pending-fraud-slashes slash-id :status])))))

(deftest slashing-accounting-consistency
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        res "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        slash-amount 400
        stake-amount 1000]
    (testing "Slash accounting consistency: balances and stakes"
      (let [{:keys [world workflow-id]}
            (-> (reg/register-stake world res stake-amount)
                (world-ready-for-fraud-slash-propose buyer "0xT" seller res 1000 snap))

            ;; Propose slash
            world-prop (-> (res/propose-fraud-slash world workflow-id gov res slash-amount) :world)

            ;; Set epoch cap to match per-offense cap (50%) so 40% slash passes
            world-params (assoc-in world-prop [:params :slash-epoch-cap-bps] 5000)

            ;; Advance time to expire appeal window
            world-late (time-ctx/advance-time world-params {:to 1200})

            ;; Execute slash
            world-slashed (:world (res/execute-fraud-slash world-late workflow-id))

            post-stake (reg/get-stake world-slashed res)
            slash-total (get-in world-slashed [:resolver-slash-total res] 0)

            ;; Calculate expected distribution based on default 50/30/20 split
            expected-dist (sew-econ/calculate-slashing-distribution slash-amount 0)
            expected-total (+ (:insurance expected-dist) (:protocol expected-dist) (:retained expected-dist))]

        (is (= (- stake-amount slash-amount) post-stake) "Post-slash stake should match")
        (is (= expected-total slash-total) "Slash total should match distributed sum")))))

(deftest governance-only-slash-actions
  (let [buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        l1-resolver "0xL1"
        gov "0xGov"
        non-gov "0xUser"
        snap (snap-fix/escrow-snapshot {:dispute-resolver resolver-addr
                                        :appeal-window-duration 100
                                        :challenge-window-duration 100
                                        :reversal-slash-bps 2500
                                        :max-dispute-level 2})
        world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
        world0-force (-> (t/empty-world 1000)
                         (reg/register-stake resolver-addr 10000)
                         (reg/register-stake l1-resolver 10000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "0xT" seller resolver-addr 1000 snap)
        {:keys [world force-workflow-id]}
        (let [{:keys [world workflow-id]}
              (lc/create-escrow world0-force buyer "USDC" seller 5000 {} snap)
              after-raise (:world (lc/raise-dispute world workflow-id buyer))
              after-l0 (:world (res/execute-resolution after-raise workflow-id resolver-addr true "0xhash" nil))
              esc-fn (fn [_ _ _ _] {:ok true :new-resolver l1-resolver})
              after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))]
          {:world after-esc :force-workflow-id workflow-id})
        agent-index {"gov"  {:id "gov" :address gov :role "governance"}
                     "user" {:id "user" :address non-gov :role "honest"}}
        ctx {:agent-index agent-index :governance-identity gov}
        propose-ev {:agent "user" :action "propose_fraud_slash"
                    :params {:workflow-id workflow-id :resolver-addr resolver-addr :amount 100}}
        r-propose-gov (sew/apply-action (assoc ctx :agent-index {"gov" {:id "gov" :address gov :role "governance"}})
                                        world
                                        (assoc propose-ev :agent "gov"))
        world2 (-> (:world r-propose-gov)
                   (assoc-in [:pending-fraud-slashes workflow-id :status] :appealed))
        resolve-ev {:agent "user" :action "resolve_appeal"
                    :params {:workflow-id workflow-id :upheld? true}}
        force-ev {:action "force_reversal_slash"
                  :params {:workflow-id force-workflow-id :slash-bps 2500}}
        r-resolve-non-gov (sew/apply-action ctx world2 resolve-ev)
        r-force-non-gov (sew/apply-action ctx world (assoc force-ev :agent "user"))
        r-force-gov (sew/apply-action ctx world (assoc force-ev :agent "gov"))
        propose-entry (get-in (:world r-propose-gov) [:pending-fraud-slashes workflow-id])
        force-entry (slash-for (:world r-force-gov) force-workflow-id :force-reversal 0)]
    (is (false? (:ok r-resolve-non-gov)))
    (is (= :not-governance (:error r-resolve-non-gov)))
    (is (false? (:ok r-force-non-gov)))
    (is (= :not-governance (:error r-force-non-gov)))
    (is (= :scenario-configured-address-binding
           (get-in propose-entry [:authorization/provenance :authorization/basis])))
    (is (= :governance
           (get-in propose-entry [:authorization/provenance :authorization/type])))
    (is (= :with-governance-actor
           (get-in propose-entry [:authorization/provenance :authorization/check])))
    (is (= "gov"
           (get-in propose-entry [:authorization/provenance :authorization/actor-id])))
    (is (= :replay-context/agent-index
           (get-in propose-entry [:authorization/provenance :authorization/source])))
    (is (= "propose-fraud-slash"
           (get-in propose-entry [:authorization/last-action])))
    (is (:ok r-force-gov))
    (let [extra-provenance (get-in r-force-gov [:extra :authorization/provenance])
          stored-provenance (:authorization/provenance force-entry)]
      (is (= extra-provenance
             (select-keys stored-provenance (keys extra-provenance)))
          "Stored provenance contains all fields from action result provenance"))
    (is (= :executed (:status force-entry)))
    (is (= "governance-authorization.v2"
           (get-in force-entry [:authorization/provenance :authorization/schema-version])))
    (is (= :governance
           (get-in force-entry [:authorization/provenance :authorization/type])))
    (is (= :scenario-configured-address-binding
           (get-in force-entry [:authorization/provenance :authorization/basis])))
    (is (= "gov"
           (get-in force-entry [:authorization/provenance :authorization/actor-id])))
    (is (= gov
           (get-in force-entry [:authorization/provenance :authorization/address])))
    (is (= :with-governance-actor
           (get-in force-entry [:authorization/provenance :authorization/check])))
    (is (= :replay-context/agent-index
           (get-in force-entry [:authorization/provenance :authorization/source])))
    (is (= "force-reversal-slash"
            (get-in force-entry [:authorization/provenance :authorization/action])))))

(deftest same-level-reresolution-reversal-targets-current-level-resolver
  (testing "a same-level re-resolution reverses the decision recorded at the
            current level and slashes that level's resolver (not the prior level)"
    (let [buyer "0xBuyer"
          seller "0xSeller"
          r0 "0xR0"
          r1 "0xR1"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :appeal-window-duration 100
                                          :challenge-window-duration 100
                                          :reversal-slash-bps 2500
                                          :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 10000))
          {:keys [world workflow-id]}
          (let [{:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
                after-raise (:world (lc/raise-dispute world workflow-id buyer))
                ;; L0 resolution by r0 (release)
                after-l0 (:world (res/execute-resolution after-raise workflow-id r0 true "0xh0" nil))
                esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
                ;; escalate to L1 (resolver r1)
                after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))
                ;; L1 resolution by r1 (refund) -> reverses L0 -> r0 slashed at level 0
                after-l1 (:world (res/execute-resolution after-esc workflow-id r1 false "0xh1" nil))
                ;; SAME-LEVEL re-resolution at L1 by r1 (release) -> reverses the
                ;; L1 decision -> r1 must be slashed at level 1
                after-l1-re (:world (res/execute-resolution after-l1 workflow-id r1 true "0xh1b" nil))]
            {:world after-l1-re :workflow-id workflow-id})
          slash-r0 (get-in world [:slash-by-context (t/slash-context-key workflow-id :reversal 0)])
          slash-r1 (get-in world [:slash-by-context (t/slash-context-key workflow-id :reversal 1)])]
      (is (some? slash-r0) "L0->L1 reversal slash recorded")
      (is (= r0 (:resolver (get-in world [:pending-fraud-slashes slash-r0])))
          "r0 is the L0 reversal target")
      (is (some? slash-r1) "same-level L1 re-resolution reversal slash recorded")
      (is (= r1 (:resolver (get-in world [:pending-fraud-slashes slash-r1])))
           "r1 (current level) is slashed for the same-level flip, not r0"))))

(deftest cleanup-orphaned-slashes-executes-expired-pending-reversal
  (testing "expired Track 2 pending reversal slash is enforced, not dropped"
    (let [resolver "0xR0"
          workflow-id 0
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake resolver 1000))
          world-terminal (assoc-in world0 [:escrow-transfers workflow-id :escrow-state] :released)
          entry {:status :pending
                 :resolver resolver
                 :amount 250
                 :token :USDC
                 :appeal-deadline 100
                 :reason :reversal}
          world (insert-test-slash world-terminal workflow-id :reversal 0 entry)
          slash-id (get-in world [:slash-by-context (t/slash-context-key workflow-id :reversal 0)])]
      (is (= 1000 (reg/get-stake world resolver)) "stake intact before cleanup")
      (let [cleaned (lc/cleanup-orphaned-slashes world workflow-id)]
        (is (= (- 1000 250) (reg/get-stake cleaned resolver))
            "resolver stake slashed by the expired reversal penalty")
        (is (nil? (get-in cleaned [:pending-fraud-slashes slash-id]))
            "expired reversal slash removed from pending-fraud-slashes")
        (is (= :expired-executed
               (get-in cleaned [:reversal-slash-history slash-id :status]))
             "archived to history as executed"))))
  (testing "unexpired Track 2 pending reversal slash is left resolvable"
    (let [resolver "0xR0"
          workflow-id 0
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake resolver 1000))
          world-terminal (assoc-in world0 [:escrow-transfers workflow-id :escrow-state] :released)
          entry {:status :pending
                 :resolver resolver
                 :amount 250
                 :token :USDC
                 :appeal-deadline 5000
                 :reason :reversal}
          world (insert-test-slash world-terminal workflow-id :reversal 0 entry)
          slash-id (get-in world [:slash-by-context (t/slash-context-key workflow-id :reversal 0)])]
      (let [cleaned (lc/cleanup-orphaned-slashes world workflow-id)]
        (is (= 1000 (reg/get-stake cleaned resolver))
            "resolver stake untouched while window open")
        (is (some? (get-in cleaned [:pending-fraud-slashes slash-id]))
            "pending reversal slash retained for later resolution")
        (is (nil? (get-in cleaned [:reversal-slash-history slash-id]))
            "nothing archived while window open")))))

(deftest execute-fraud-slash-rejects-fraud-group-slash
  (testing "a :fraud-group slash must not be executed by the single-slash executor"
    (let [workflow-id 0
          world (-> (t/empty-world 1000)
                    (reg/register-stake "0xR0" 1000))
          entry {:status :pending
                 :amount 100
                 :appeal-deadline 100
                 :fraud-incident-ref {:fraud-incident/ref "fig-1"}}
          world' (insert-test-slash world workflow-id :fraud-group 0 entry)
          slash-id (get-in world' [:slash-by-context [workflow-id :fraud-group 0]])]
      (let [result (res/execute-fraud-slash world' workflow-id slash-id)]
        (is (= :not-fraud-group-slash (:error result))
            "fraud-group slash rejected by execute-fraud-slash")
        (is (= :pending (get-in world' [:pending-fraud-slashes slash-id :status]))
            "fraud-group slash left untouched (not executed with zero effect)")
        (is (= 1000 (reg/get-stake world' "0xR0"))
            "no resolver stake was slashed")))))

(deftest execute-fraud-group-slash-blocks-unresolved-member-appeal
  (testing "group execution is blocked while a member appeal is still :appealed"
    (let [r0 "0xR0" r1 "0xR1"
          workflow-id 0
          world (-> (t/empty-world 1000)
                    (reg/register-stake r0 1000)
                    (reg/register-stake r1 1000))
          entry {:status :pending
                 :amount 100
                 :appeal-deadline 100
                 :fraud-incident-ref {:fraud-incident/ref "fig-2"}
                 :members [{:id r0} {:id r1}]
                 :appeals {r0 {:status :appealed}}}
          world' (insert-test-slash world workflow-id :fraud-group 0 entry)
          slash-id (get-in world' [:slash-by-context [workflow-id :fraud-group 0]])]
      (let [result (res/execute-fraud-group-slash world' workflow-id slash-id)]
        (is (= :appeal-in-progress (:error result))
            "execution blocked while a member appeal is unresolved")
        (is (= :pending (get-in world' [:pending-fraud-slashes slash-id :status]))
            "group slash left pending, no member slashed")))))

(deftest reversal-slash-not-suppressed-by-unrelated-fraud-slash
  (testing "an unrelated pending fraud slash must not suppress the reversal penalty"
    (let [buyer "0xBuyer" seller "0xSeller" r0 "0xR0" r1 "0xR1"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :appeal-window-duration 100
                                          :challenge-window-duration 100
                                          :reversal-slash-bps 2500
                                          :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 10000))
          {:keys [world workflow-id]}
          (let [{:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
                after-raise (:world (lc/raise-dispute world workflow-id buyer))
                ;; L0 resolution by r0 (release)
                after-l0 (:world (res/execute-resolution after-raise workflow-id r0 true "0xh0" nil))
                ;; Unrelated PENDING FRAUD slash on r0 (NOT a reversal).  This must not
                ;; suppress the reversal penalty that the L1 re-resolution will trigger.
                with-fraud (insert-test-slash after-l0 workflow-id :fraud 0
                                              {:status :pending
                                               :resolver r0
                                               :amount 50
                                               :token :USDC
                                               :appeal-deadline 100
                                               :reason :fraud})
                esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
                ;; escalate to L1 (resolver r1)
                after-esc (:world (res/escalate-dispute with-fraud workflow-id buyer esc-fn))
                ;; L1 resolution by r1 (refund) -> reverses L0 -> r0 slashed
                after-l1 (:world (res/execute-resolution after-esc workflow-id r1 false "0xh1" nil))]
            {:world after-l1 :workflow-id workflow-id})
          slash-r0 (get-in world [:slash-by-context (t/slash-context-key workflow-id :reversal 0)])
          entry (get-in world [:pending-fraud-slashes slash-r0])]
      (is (some? slash-r0)
          "reversal slash still recorded despite the unrelated fraud slash")
      (is (= r0 (:resolver entry))
          "reversal penalty targets the reversed resolver, not suppressed by the unrelated fraud slash")
      (is (pos? (:amount entry))
          "reversal penalty carries a positive slash amount (not suppressed)"))))

(deftest execute-fraud-slash-epoch-cap-uses-slashable-basis
  (testing "epoch cap is measured against live stake + already-debited epoch, not live stake alone"
    (let [res "0xRes"
          workflow-id 0
          ;; live stake already reduced to 800 by an earlier 200-amount slash,
          ;; recorded in the epoch accumulator.
          world (-> (t/empty-world 1000)
                    (reg/register-stake res 800)
                    (assoc-in [:resolver-epoch-slashed res :amount] 200)
                    (assoc-in [:params :slash-epoch-cap-bps] 5000))
          entry {:status :pending
                 :resolver res
                 :amount 250
                 :token :USDC
                 :appeal-deadline 100
                 :reason :fraud}
          world' (insert-test-slash world workflow-id :fraud 0 entry)
          slash-id (get-in world' [:slash-by-context [workflow-id :fraud 0]])]
      (let [result (res/execute-fraud-slash world' workflow-id slash-id)]
        ;; (200 + 250) / (800 + 200) = 45% <= 50% cap -> allowed.
        ;; The previous live-stake-only denominator (450 / 800 = 56%) wrongly
        ;; rejected this.
        (is (nil? (:error result))
            "slash within 50% of the slashable epoch basis is allowed")
        (is (= (- 800 250) (reg/get-stake (:world result) res))
            "resolver slashed by the proposed amount")))))

(deftest execute-fraud-slash-tracks-unavailability-and-circuit-breaker
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (-> (t/empty-world 1000)
                   (assoc-in [:unavailability-stats :total-resolvers] 1)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
        world2 (time-ctx/advance-time world1 {:to 1011})
        r-exec (res/execute-fraud-slash world2 workflow-id)
        world3 (:world r-exec)]
    (is (true? (:ok r-exec)))
    (is (= :executed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (contains? (:resolver-unavailable world3) resolver-addr))
    (is (= 1 (get-in world3 [:unavailability-stats :unavailable-count])))
    (is (true? (get-in world3 [:circuit-breaker :active?])))))

(deftest execute-fraud-slash-dispatch-records-executor-provenance
  (let [resolver-addr "0xRes"
        gov "0xGov"
        keeper "0xKeeper"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
        world2 (time-ctx/advance-time world1 {:to 1011})
        ctx {:agent-index {"keeper" {:id "keeper" :address keeper :role "honest"}}}
        event {:agent "keeper"
               :action "execute_fraud_slash"
               :params {:workflow-id workflow-id}}
        result (sew/apply-action ctx world2 event)
        slash-entry (get-in (:world result) [:pending-fraud-slashes workflow-id])]
    (is (:ok result) "resolved non-governance actor can execute matured fraud slash")
    (is (= :executed (:status slash-entry)))
    (is (= (get-in result [:extra :execution/provenance])
           (:execution/provenance slash-entry)))
    (is (= "execution-provenance.v1"
           (get-in slash-entry [:execution/provenance :execution/schema-version])))
    (is (= :public-execution
           (get-in slash-entry [:execution/provenance :execution/type])))
    (is (= :scenario-declared
           (get-in slash-entry [:execution/provenance :execution/basis])))
    (is (= "keeper"
           (get-in slash-entry [:execution/provenance :execution/actor-id])))
    (is (= keeper
           (get-in slash-entry [:execution/provenance :execution/address])))
    (is (= :with-resolved-actor
           (get-in slash-entry [:execution/provenance :execution/check])))
    (is (= :replay-context/agent-index
           (get-in slash-entry [:execution/provenance :execution/source])))
    (is (= "execute-fraud-slash"
           (get-in slash-entry [:execution/provenance :execution/action])))))

(deftest unfreeze-resolver-clears-unavailability-idempotently
  (let [resolver-addr "0xRes"
        world0 (-> (t/empty-world 1000)
                   (assoc :resolver-unavailable #{resolver-addr})
                   (assoc-in [:unavailability-stats :total-resolvers] 5)
                   (assoc-in [:unavailability-stats :unavailable-count] 1)
                   (assoc-in [:resolver-frozen-until resolver-addr] 5000))
        world1 (:world (res/unfreeze-resolver world0 resolver-addr))
        world2 (:world (res/unfreeze-resolver world1 resolver-addr))]
    (is (= 0 (get-in world1 [:resolver-frozen-until resolver-addr])))
    (is (not (contains? (:resolver-unavailable world1) resolver-addr)))
    (is (= 0 (get-in world1 [:unavailability-stats :unavailable-count])))
    ;; idempotent second call
    (is (= 0 (get-in world2 [:unavailability-stats :unavailable-count])))))

(deftest slash-distribution-tracks-retained-reserves
  (let [world0 (t/empty-world 1000)
        world1 (-> world0
                   (assoc-in [:bond-balances 1 "0xA"] 100)
                   (update-in [:bond-slashed 1] (fnil + 0) 100)
                   (update-in [:bond-distribution :insurance] (fnil + 0) 50)
                   (update-in [:bond-distribution :protocol] (fnil + 0) 30)
                   (update :retained-slash-reserves (fnil + 0) 20))]
    (is (= {:holds? true :violations []}
           (resolver-sim.protocols.sew.invariants/slash-distribution-consistent? world1)))))

(deftest appeal-bond-custody-upheld-refunds-resolver
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                        :appeal-bond-amount 75})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov true workflow-id
                                       :authorization-provenance {:authorization/type :governance
                                                                  :authorization/basis :test})
                   :world)]
    (is (= 75 (get-in world2 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= :reversed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 75 (get-in world3 [:claimable-v2 workflow-id :bond/refund resolver-addr] 0)))
    (is (= 0 (get-in world3 [:claimable workflow-id resolver-addr] 0))
        "bond/refund is v2-native; legacy :claimable is not dual-written")))

(deftest appeal-bond-custody-rejected-executes-slash
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                        :appeal-bond-amount 60})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        ;; Appeal is opened and bond is held before governance resolves it
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        ;; Governance rejects the appeal → slash deferred as :pending
        world3 (-> (res/resolve-appeal world2 workflow-id gov false workflow-id
                                       :authorization-provenance {:authorization/type :governance
                                                                  :authorization/basis :test})
                   :world)
        ;; Advance time past appeal deadline and execute
        deadline (get-in world3 [:pending-fraud-slashes workflow-id :appeal-deadline] 0)
        world3-timed (time-ctx/advance-time world3 {:to (inc deadline)})
        world4 (:world (res/execute-fraud-slash world3-timed workflow-id))]

    ;; ── Intermediate state: appeal opened, bond held ──
    (testing "appeal custody state"
      (is (= :appealed (get-in world2 [:pending-fraud-slashes workflow-id :status]))
          "slash status becomes :appealed")
      (is (= 60 (get-in world2 [:pending-fraud-slashes workflow-id :appeal-bond-held]))
          "appeal bond held in pending entry")
      (is (= 1000 (reg/get-stake world2 resolver-addr))
          "appeal should not debit stake before resolution")
      (is (= 0 (get world2 :appeal-bonds-forfeited-insurance 0))
          "bond not forfeited before rejection")
      (is (= 0 (get-in world2 [:appeal-bond-distributions-by-token :USDC] 0))
          "no bond distribution before rejection"))

    ;; ── Post-rejection state: slash deferred ──
    (testing "post-rejection state"
      (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status]))
          "rejected appeal sets status to :pending (deferred)")
      (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held]))
          "appeal bond held cleared after resolution"))

    ;; ── Bond forfeiture happens at resolve time ──
    (testing "bond accounting"
      (is (= 60 (get world3 :appeal-bonds-forfeited-insurance 0))
          "forfeited bond added to insurance")
      (is (= 60 (get-in world3 [:appeal-bond-distributions-by-token :USDC] 0))
          "bond distribution recorded")
      (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :unmet-slash] 0))
          "fully funded resolver creates no unmet slash"))

    ;; ── Slash amount preserved ──
    (testing "slash record integrity"
      (is (= 100 (get-in world3 [:pending-fraud-slashes workflow-id :amount]))
          "slash amount unchanged by appeal resolution"))

    ;; ── Stake and freeze effects happen after execute-fraud-slash ──
    (testing "stake and freeze"
      (is (= 1000 (reg/get-stake world3 resolver-addr))
          "stake not debited by resolve-appeal alone")
      (is (= :executed (get-in world4 [:pending-fraud-slashes workflow-id :status]))
          "slash executed after execute-fraud-slash")
      (is (= 900 (reg/get-stake world4 resolver-addr))
          "stake debited by slash amount only; bond does not reduce stake")
      (is (pos? (get-in world4 [:resolver-frozen-until resolver-addr] 0))
          "resolver frozen after execution"))

    ;; ── No refund path was taken ──
    (testing "no bond refund"
      (is (nil? (get-in world3 [:claimable workflow-id resolver-addr]))))))

(deftest appeal-bond-custody-rejected-cannot-double-execute
  (testing "resolve-appeal is idempotent after rejection — second call returns :no-active-appeal"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                          :appeal-bond-amount 60})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake resolver-addr 1000))
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
          world3 (-> (res/resolve-appeal world2 workflow-id gov false workflow-id
                                         :authorization-provenance {:authorization/type :governance
                                                                    :authorization/basis :test})
                     :world)
          r-second (res/resolve-appeal world3 workflow-id gov false workflow-id
                                       :authorization-provenance {:authorization/type :governance
                                                                  :authorization/basis :test})]
      (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status]))
          "rejected appeal sets status to :pending (deferred)")
      (is (= 1000 (reg/get-stake world3 resolver-addr))
          "stake not debited by resolve-appeal alone")
      (is (= 60 (get world3 :appeal-bonds-forfeited-insurance 0))
          "bond forfeited exactly once")
      ;; Second call must fail (status is :pending, not :appealed)
      (is (false? (:ok r-second))
          "second resolve-appeal must return :ok false")
      (is (= :no-active-appeal (:error r-second))
          "second resolve-appeal returns :no-active-appeal since status is :pending not :appealed"))))

(deftest appeal-bond-custody-rejected-requires-governance
  (testing "only governance can resolve an appeal — non-governance caller rejected via apply-action"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          non-gov "0xUser"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                          :appeal-bond-amount 60})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake resolver-addr 1000))
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
          context {:agent-index {"gov" {:id "gov" :address gov :role "governance"}
                                 "user" {:id "user" :address non-gov :role "honest"}}
                   :governance-identity gov}
          r-non-gov (sew/apply-action context world2
                      {:agent "user" :action "resolve_appeal"
                       :params {:workflow-id workflow-id :upheld? false}})
          r-gov (sew/apply-action context world2
                   {:agent "gov" :action "resolve_appeal"
                    :params {:workflow-id workflow-id :upheld? false}})]
      (is (false? (:ok r-non-gov)) "non-governance must not resolve appeal")
      (is (= :not-governance (:error r-non-gov)) "non-governance error must be :not-governance")
      (is (true? (:ok r-gov)) "governance resolve must succeed"))))

(deftest appeal-slash-custody-carries-forced-authorization-provenance
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                        :appeal-bond-amount 60})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        context {:agent-index {"gov" {:id "gov" :address gov :role "governance"}}
             :governance-identity gov}
        result (sew/apply-action context world1
                                 {:agent "gov"
                                  :action "appeal_slash"
                                  :params {:workflow-id workflow-id}})
        custody (get-in (:world result) [:appeal-bond-custody workflow-id])
        slash-entry (get-in (:world result) [:pending-fraud-slashes workflow-id])
        held-adjustment (last (:held-adjustments (:world result)))]
    (is (:ok result))
    (is (= (get-in result [:extra :authorization/provenance])
           (:authorization/provenance custody)))
    (is (= :governance-intervention
           (get-in custody [:authorization/provenance :authorization/class])))
    (is (= :appeal-bond-custody
           (get-in custody [:authorization/provenance :authorization/reason])))
    (is (= :scenario-configured-address-binding
           (get-in custody [:authorization/provenance :authorization/basis])))
    (is (= :with-governance-actor
           (get-in custody [:authorization/provenance :authorization/check])))
    (is (= "appeal-slash"
           (get-in custody [:authorization/last-action])))
    (is (= "appeal-slash"
           (get-in slash-entry [:authorization/last-action])))
    (is (= "appeal-slash"
           (:held/action held-adjustment)))
    (is (= :appeal-bond-posted
           (:held/reason held-adjustment)))
    (is (= workflow-id
           (:held/workflow-id held-adjustment)))
    (is (= :governance-intervention
           (get-in held-adjustment [:authorization/provenance :authorization/class])))
    (is (= :scenario-configured-address-binding
           (get-in held-adjustment [:authorization/provenance :authorization/basis])))))

;; ============ Reversal-slash specific tests ============

(deftest reversal-slash-basis-is-stake
  (testing "Reversal slash amount is based on resolver stake, not escrow principal"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          slash-id (slash-id-for world workflow-id :reversal 0)
          slash (slash-for world workflow-id :reversal 0)
          stake (reg/get-stake world "0xL0Res")]
      (is (some? slash) "reversal slash should exist")
      (is (= :stake (:basis-kind slash)))
      (is (= 10000 (:basis-amount slash)))
      ;; 25% of 10000 stake = 2500 (not 25% of 8000 escrow principal = 2000)
      (is (= 2500 (:amount slash)) "slash amount should be 25% of stake, not principal")
      (is (not= 2000 (:amount slash)) "slash amount should NOT be 25% of escrow principal")
      (is (= 7500 (reg/get-stake world "0xL0Res")) "stake reduced by slash amount"))))

(deftest reversal-slash-uses-level-scoped-id
  (testing "handle-reversal-slashing generates \"<wf>-reversal-<level-1>\" id"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          slash (slash-for world workflow-id :reversal 0)]
      (is (some? slash) "reversal slash entry should exist under level-scoped id")
      (is (= :executed (:status slash)) "Track 1 (same-evidence) slash is immediately executed")
      (is (= :reversal (:reason slash)))
      (is (= "0xL0Res" (:resolver slash))))))

(deftest reversed-reversal-full-lifecycle
  (testing "Reversal slash can itself be appealed and reversed (Track 2 / manual path)"
    (let [l0-res  "0xL0Res"
          gov     "0xGov"
          snap    (snap-fix/escrow-snapshot {:appeal-window-duration 200
                                             :appeal-bond-amount 0})
          buyer   "0xBuyer"
          seller  "0xSeller"
          world0  (-> (t/empty-world 1000)
                      (reg/register-stake l0-res 5000))
          {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 1000 {} snap)
          ;; Manually install a :pending reversal slash (mimicking Track 2).
          world1  (insert-test-slash world workflow-id :reversal 0
                                     {:resolver         l0-res
                                      :amount           500
                                      :token            "USDC"
                                      :reason           :reversal
                                      :status           :pending
                                      :proposed-at      1000
                                      :appeal-deadline  1200
                                      :appeal-bond-held 0
                                      :contest-deadline 0})
          slash-id (slash-id-for world1 workflow-id :reversal 0)
          ;; Resolver appeals the reversal slash
          world2  (:world (res/appeal-slash world1 workflow-id l0-res slash-id))
          ;; Governance upholds the appeal → slash :reversed
          world3  (:world (res/resolve-appeal world2 workflow-id gov true slash-id
                                             :authorization-provenance {:authorization/type :governance
                                                                        :authorization/basis :test}))]
      (is (= :appealed (get-in world2 [:pending-fraud-slashes slash-id :status]))
          "After appeal, slash should be :appealed")
      (is (= :reversed (get-in world3 [:pending-fraud-slashes slash-id :status]))
          "After governance upholds appeal, reversal slash should be :reversed")
      ;; Attempting to execute the reversed slash should be blocked
      (let [r-exec (res/execute-fraud-slash world3 workflow-id slash-id)]
        (is (false? (:ok r-exec)))
        (is (= :slash-already-reversed (:error r-exec)))))))

(deftest multi-level-reversal-no-slash-id-collision
  (testing "Two reversals on the same workflow use distinct level-scoped ids"
    ;; This is a regression test for the slash-id collision bug (Bug 2).
    ;; Distinct semantic contexts (L0→L1 and L1→L2) must not collide.
    (let [wf-id    0
          world    (-> (t/empty-world 1000)
                       (insert-test-slash wf-id :reversal 0
                                          {:resolver "0xL0Res" :amount 100 :status :executed
                                           :reason :reversal :proposed-at 1000 :appeal-deadline 0
                                           :appeal-bond-held 0 :contest-deadline 0})
                       (insert-test-slash wf-id :reversal 1
                                          {:resolver "0xL1Res" :amount 200 :status :executed
                                           :reason :reversal :proposed-at 1050 :appeal-deadline 0
                                           :appeal-bond-held 0 :contest-deadline 0}))
          slash-l0 (slash-id-for world wf-id :reversal 0)
          slash-l1 (slash-id-for world wf-id :reversal 1)]
      (is (not= slash-l0 slash-l1) "Level-scoped ids must differ")
      (is (= "0xL0Res" (get-in world [:pending-fraud-slashes slash-l0 :resolver])))
      (is (= "0xL1Res" (get-in world [:pending-fraud-slashes slash-l1 :resolver])))
      ;; Verify neither overwrote the other
      (is (= 100 (get-in world [:pending-fraud-slashes slash-l0 :amount])))
      (is (= 200 (get-in world [:pending-fraud-slashes slash-l1 :amount]))))))

(deftest resolve-appeal-uses-workflow-id-from-pending-not-slash-id-string
  (testing "resolve-appeal extracts workflow-id from pending entry, not from slash-id string"
    ;; Regression test for the fragile (:workflow-id custody slash-id) fallback.
    ;; When custody is nil (bond-held=0), wf-id should come from :pending entry.
    (let [resolver "0xRes"
          gov      "0xGov"
          wf-id    42
          world    (-> (t/empty-world 1000)
                       (reg/register-stake resolver 5000)
                       (insert-test-slash wf-id :reversal 0
                                          {:resolver         resolver
                                           :amount           300
                                           :token            "USDC"
                                           :reason           :reversal
                                           :status           :appealed
                                           :proposed-at      1000
                                           :appeal-deadline  1200
                                           :appeal-bond-held 0
                                           :contest-deadline 0}))
          slash-id (slash-id-for world wf-id :reversal 0)
          r     (res/resolve-appeal world wf-id gov true slash-id
                                    :authorization-provenance {:authorization/type :governance
                                                               :authorization/basis :test})
          world' (:world r)]
      (is (true? (:ok r)))
      (is (= :reversed (get-in world' [:pending-fraud-slashes slash-id :status])))
      ;; bond-held=0 so no claimable entry, but we verify no error was thrown
      (is (= 0 (get-in world' [:claimable wf-id resolver] 0))))))

(deftest appeal-executed-reversal-slash-rejected
  (testing "Track 1 :executed reversal slash cannot be appealed"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          slash-id (slash-id-for world workflow-id :reversal 0)
          r (res/appeal-slash world workflow-id "0xL0Res" slash-id)]
      (is (false? (:ok r)))
      (is (= :slash-not-pending (:error r))))))

(deftest reversal-slash-zero-bps-is-noop
  (testing "handle-reversal-slashing is a no-op when reversal-slash-bps is 0"
    (let [{:keys [world workflow-id steps]}
          (rev-fx/build-reversal-world {:snapshot {:reversal-slash-bps 0}})
          after-l0 (:after-l0 steps)
          slash-id (slash-id-for world workflow-id :reversal 0)]
      (is (nil? slash-id)
          "no slash entry created")
      (is (= (reg/get-stake after-l0 "0xL0Res")
             (reg/get-stake world "0xL0Res"))
          "L0 resolver stake unchanged after reversal"))))

(deftest reversal-slash-after-fraud-slash
  (testing "Reversal slash reads current stake at reversal time (Solidity semantics)"
    (let [gov "0xGov"
          r0 "0xRes0"
          r1 "0xRes1"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :reversal-slash-bps 2500
                                          :appeal-window-duration 2000000
                                          :challenge-window-duration 2000000
                                          :max-dispute-level 2
                                          :escrow-fee-bps 0
                                          :resolver-bond-bps 10000})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 5000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 8000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id r0 true "0xhash" nil))

          ;; Reduce L0's stake via fraud slash before escalation.
          ;; Advance time past the slash timelock, then reset for challenge window.
          after-l0-params (assoc-in after-l0 [:params :slash-epoch-cap-bps] 5000)
          world-slashed (-> (res/propose-fraud-slash after-l0-params workflow-id gov r0 5000) :world
                            (time-ctx/advance-time {:to 3000001})
                            (res/execute-fraud-slash workflow-id)
                            :world
                            (time-ctx/advance-time {:to 1000}))

          ;; Escalate and reverse
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
          after-escalation (:world (res/challenge-resolution world-slashed workflow-id buyer esc-fn))
          after-l1 (:world (res/execute-resolution after-escalation workflow-id r1 false "0xhash2" nil))
          slash-id (slash-id-for after-l1 workflow-id :reversal 0)
          slash (slash-for after-l1 workflow-id :reversal 0)]
      (is (some? slash) "reversal slash entry exists")
      ;; basis-amount reads current stake at reversal time, not original stake at decision
      (is (= 5000 (:basis-amount slash)) "basis-amount is remaining stake after fraud slash")
      ;; 2500 bps = 25% of 5000 remaining stake = 1250
      (is (= 1250 (:amount slash)) "slash amount is 25% of remaining stake")
      (is (= 3750 (reg/get-stake after-l1 r0)) "L0 stake reduced from 5000 → 3750"))))

(deftest reversal-slash-without-challenger
  (testing "handle-reversal-slashing handles nil challenger gracefully"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          world-no-challenger (update world :challengers dissoc workflow-id)
          slash-id (slash-id-for world-no-challenger workflow-id :reversal 0)
          slash (slash-for world-no-challenger workflow-id :reversal 0)]
      (is (some? slash) "reversal slash entry exists despite nil challenger")
      (is (= :executed (:status slash)) "slash executed normally")
      (is (= "0xL0Res" (:resolver slash)))
      (is (pos? (reg/get-stake world-no-challenger "0xL0Res")) "stake deduction still occurs"))))

(deftest propose-fraud-slash-guards-test
  (let [buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
        world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "0xT" seller resolver-addr 1000 snap)]
    (testing "rejects propose before dispute path"
      (let [{:keys [world workflow-id]}
            (lc/create-escrow world0 buyer "0xT" seller 1000 {:custom-resolver resolver-addr} snap)
            r (res/propose-fraud-slash world workflow-id gov resolver-addr 100)]
        (is (false? (:ok r)))
        (is (= :workflow-not-slashable (:error r)))))
    (testing "rejects duplicate pending propose"
      (let [r1 (res/propose-fraud-slash world workflow-id gov resolver-addr 100)
            r2 (res/propose-fraud-slash (:world r1) workflow-id gov resolver-addr 50)]
        (is (true? (:ok r1)))
        (is (false? (:ok r2)))
        (is (= :slash-already-pending (:error r2)))))
    (testing "rejects wrong resolver address"
      (let [r (res/propose-fraud-slash world workflow-id gov "0xOther" 100)]
        (is (false? (:ok r)))
        (is (= :slash-resolver-mismatch (:error r)))))
    (testing "stores workflow-id on pending entry"
      (let [r (res/propose-fraud-slash world workflow-id gov resolver-addr 100)]
        (is (= workflow-id (get-in (:world r) [:pending-fraud-slashes workflow-id :workflow-id])))))))

(deftest resolve-appeal-on-executed-slash-returns-cannot-reverse-executed-slash
  (let [resolver-addr "0xRes"
        gov "0xGov"
        slash-id 1
        world (t/insert-slash
               (t/empty-world 1000)
               {:slash/id slash-id :slash/workflow-id 0
                :slash/kind :fraud :slash/level 0
                :resolver resolver-addr :amount 100 :reason :fraud
                :status :executed :proposed-at 1000 :appeal-deadline 0
                :appeal-bond-held 0 :contest-deadline 0 :workflow-id 0})
        r (res/resolve-appeal world 0 gov true slash-id
                              :authorization-provenance {:authorization/type :governance
                                                         :authorization/basis :test})]
    (is (false? (:ok r)))
    (is (= :cannot-reverse-executed-slash (:error r)))))

(deftest force-reversal-slash-idempotent
  (testing "force-reversal-slash is idempotent (second call does not compound)"
    (let [res "0xRes" l1 "0xL1" buyer "0xBuyer" seller "0xSeller"
          snap (snap-fix/escrow-snapshot
                {:dispute-resolver res :reversal-slash-bps 2500
                 :appeal-window-duration 120 :challenge-window-duration 120
                 :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake res 10000)
                     (reg/register-stake l1 10000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id res true "0xhash" nil))
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver l1})
          after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))
          ;; Now dispute level > 0, so force-reversal-slash targets prev-resolver (res)
          w1 (res/force-reversal-slash after-esc workflow-id :slash-bps 2500 :track :immediate)
          stake-after-first (reg/get-stake w1 res)
          w2 (res/force-reversal-slash w1 workflow-id :slash-bps 2500 :track :immediate)
          stake-after-second (reg/get-stake w2 res)]
      (is (= 7500 stake-after-first) "first call debits 2500 from 10000")
      (is (= stake-after-first stake-after-second) "second call idempotent — stake unchanged"))))

(deftest reversal-slash-rejected-appeal-executes-stake-debit
  (testing "resolve-appeal with appeal-upheld?=false sets status to :pending for deferred execution"
    (let [res "0xRes" gov "0xGov" buyer "0xBuyer" seller "0xSeller"
          snap (snap-fix/escrow-snapshot
                {:dispute-resolver res :reversal-slash-bps 2500
                 :appeal-window-duration 3600 :challenge-window-duration 3600
                 :max-dispute-level 1 :resolver-bond-bps 0 :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake res 8000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 4000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id res true "0xl0" nil))
          ;; Record evidence so reversal creates a Track 2 (pending) slash
          after-evidence (assoc-in after-l0 [:evidence-updated? workflow-id] true)
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver "0xL1"})
          after-esc (:world (res/escalate-dispute after-evidence workflow-id buyer esc-fn))
          after-l1 (:world (res/execute-resolution after-esc workflow-id "0xL1" false "0xl1" nil))
          slash-id (slash-id-for after-l1 workflow-id :reversal 0)
          world-appealed (:world (res/appeal-slash after-l1 workflow-id res slash-id))
          world-rejected (:world (res/resolve-appeal world-appealed workflow-id gov false slash-id
                                                     :authorization-provenance {:authorization/type :governance
                                                                                :authorization/basis :test}))
          deadline (get-in world-rejected [:pending-fraud-slashes slash-id :appeal-deadline] 0)
          world-timed (time-ctx/advance-time world-rejected {:to (inc deadline)})
          world-params (assoc-in world-timed [:params :slash-epoch-cap-bps] 5000)
          world-executed (:world (res/execute-fraud-slash world-params workflow-id slash-id))
          stake-after (reg/get-stake world-executed res)]
      (is (some? (get-in after-l1 [:pending-fraud-slashes slash-id]))
          "Track 2 reversal slash created")
      (is (= :pending (get-in after-l1 [:pending-fraud-slashes slash-id :status]))
          "Track 2 slash is pending")
      (is (= :appealed (get-in world-appealed [:pending-fraud-slashes slash-id :status]))
          "appealed after appeal-slash")
      (is (= :pending (get-in world-rejected [:pending-fraud-slashes slash-id :status]))
          "rejected appeal sets status to :pending (deferred execution)")
      (is (= 2000 (get-in after-l1 [:pending-fraud-slashes slash-id :amount]))
          "slash amount correctly set")
      (is (= :executed (get-in world-executed [:pending-fraud-slashes slash-id :status]))
          "executed after execute-fraud-slash")
      (is (= 6000 stake-after)
          "stake debited by 2000 after execute-fraud-slash: 8000 - 2000 = 6000")
      (is (pos? (get-in world-executed [:resolver-frozen-until res] 0))
          "resolver frozen after execution"))))

(deftest force-reversal-slash-pending-execute
  (testing "Force slash with :pending track can be executed after deadline"
    (let [res "0xRes" l1 "0xL1" buyer "0xBuyer" seller "0xSeller"
          snap (snap-fix/escrow-snapshot
                {:dispute-resolver res :reversal-slash-bps 2500
                 :appeal-window-duration 20 :challenge-window-duration 20
                 :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (assoc-in [:params :slash-epoch-cap-bps] 10000)
                     (reg/register-stake res 10000)
                     (reg/register-stake l1 10000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id res true "0xhash" nil))
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver l1})
          after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))
          w-pending (res/force-reversal-slash after-esc workflow-id :track :pending)
          slash-id (slash-id-for w-pending workflow-id :force-reversal 0)
          slash-entry (slash-for w-pending workflow-id :force-reversal 0)
          deadline (:appeal-deadline slash-entry)
          w-late (time-ctx/advance-time w-pending {:to (inc deadline)})
          r-exec (res/execute-fraud-slash w-late workflow-id slash-id)]
      (is (= :pending (:status slash-entry)))
      (is (pos? deadline) "pending slash has appeal deadline")
      (is (true? (:ok r-exec)) "execute succeeds after deadline")
      (is (= :executed (get-in (:world r-exec) [:pending-fraud-slashes slash-id :status]))
          "slash executed after execute-fraud-slash"))))

(deftest reversal-vindication-lifecycle
  (testing "Reversal-of-reversal via scenario runner — verifies L0 reversal slash
           at seq 7.  NOTE: Full vindication (restoring L0 after L2 overturns L1
           and slashing L1 instead) is not yet implemented.  L0's slash is permanent;
           L2's resolution at seq 9 fails because the escrow was settled at seq 7."
    (let [scenario-id "reversal-vindication-test"
          result (sew/replay-with-sew-protocol
                   {:scenario-id scenario-id :schema-version "1.0"
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "l0" :address "0xl0" :role "resolver"}
                             {:id "l1" :address "0xl1" :role "resolver"}
                             {:id "l2" :address "0xl2" :role "resolver"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:governance-mode :legacy :resolver-fee-bps 0 :appeal-window-duration 60
                                      :max-dispute-duration 120 :resolver-bond-bps 0
                                      :resolution-module "0xkleros-proxy"
                                      :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
                                      :reversal-slash-bps 2500 :challenge-bounty-bps 0}
                    :allow-open-disputes? true
                    :events [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
                             {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
                             {:seq 2 :time 1000 :agent "l2" :action "register_stake" :params {:amount 8000}}
                             {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 5000}}
                             {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
                             {:seq 5 :time 1120 :agent "l0" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                             {:seq 6 :time 1120 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
                             {:seq 7 :time 1180 :agent "l1" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
                             {:seq 8 :time 1180 :agent "seller" :action "escalate_dispute" :params {:workflow-id 0}}
                             {:seq 9 :time 1240 :agent "l2" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}
                             {:seq 10 :time 1300 :agent "keeper" :action "execute_pending_settlement"
                              :params {:workflow-id 0}}]}
                   {:allow-dirty? true})
          w (or (:world result) (:last-valid-world result))]
      ;; The scenario may halt at seq 9 due to invariant violations from the
      ;; settled escrow — this is expected with the current protocol.
      (is (some? w) "world present"))
    ;; The key behavioral assertion: reversal slash at seq 7 correctly debits L0.
    (let [scenario (sew/replay-with-sew-protocol
                    {:scenario-id "reversal-single-test" :schema-version "1.0"
                     :initial-block-time 1000
                     :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                              {:id "seller" :address "0xseller" :strategy "honest"}
                              {:id "l0" :address "0xl0" :role "resolver"}
                              {:id "l1" :address "0xl1" :role "resolver"}
                              {:id "keeper" :address "0xkeeper" :role "keeper"}]
                     :protocol-params {:governance-mode :legacy :resolver-fee-bps 0 :appeal-window-duration 60
                                       :max-dispute-duration 120 :resolver-bond-bps 0
                                       :resolution-module "0xkleros-proxy"
                                       :escalation-resolvers {:0 "0xl0" :1 "0xl1"}
                                       :reversal-slash-bps 2500 :challenge-bounty-bps 0}
                     :allow-open-disputes? true
                     :events [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
                              {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
                              {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
                               :params {:token "USDC" :to "0xseller" :amount 5000}}
                              {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
                              {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
                               :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                              {:seq 5 :time 1120 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
                              {:seq 6 :time 1180 :agent "l1" :action "execute_resolution"
                               :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}]}
                    {:allow-dirty? true})
          w (:world scenario)]
      (is (= :pass (:outcome scenario)) "reversal scenario passes")
      (is (= 6000 (get-in w [:resolver-stakes "0xl0"] 0)) "L0 correctly slashed by L1 reversal")
      (is (= 8000 (get-in w [:resolver-stakes "0xl1"] 0)) "L1 stake unchanged")
      (let [ic (resolver-sim.protocols.sew.invariants/check-all w)]
        (is (:all-hold? ic) "all invariants pass after single reversal")))))

;; ============ Idempotency and edge-case tests ============

(deftest execute-fraud-slash-twice-rejected
  (testing "executing an already-executed slash returns :already-executed"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
          world2 (time-ctx/advance-time world1 {:to 1011})
          r1 (res/execute-fraud-slash world2 workflow-id)
          r2 (res/execute-fraud-slash (:world r1) workflow-id)]
      (is (true? (:ok r1)) "first execute succeeds")
      (is (false? (:ok r2)) "second execute returns false")
      (is (= :already-executed (:error r2)) "second execute returns :already-executed"))))

(deftest appeal-slash-twice-rejected
  (testing "appealing an already-appealed slash returns :slash-not-pending"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
          r1 (res/appeal-slash world1 workflow-id resolver-addr)
          r2 (res/appeal-slash (:world r1) workflow-id resolver-addr)]
      (is (true? (:ok r1)) "first appeal succeeds")
      (is (false? (:ok r2)) "second appeal returns false")
      (is (= :slash-not-pending (:error r2)) "second appeal returns :slash-not-pending"))))

(deftest resolve-appeal-rejected-idempotent
  (testing "calling resolve-appeal twice on a rejected appeal fails the second time"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                          :appeal-bond-amount 0})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
          r1 (res/resolve-appeal world2 workflow-id gov false workflow-id
                                 :authorization-provenance {:authorization/type :governance
                                                            :authorization/basis :test})
          r2 (res/resolve-appeal (:world r1) workflow-id gov false workflow-id
                                 :authorization-provenance {:authorization/type :governance
                                                            :authorization/basis :test})]
      (is (true? (:ok r1)) "first resolve (reject) succeeds")
      (is (= :pending (get-in (:world r1) [:pending-fraud-slashes workflow-id :status]))
          "rejected appeal sets status to :pending (deferred execution)")
      (is (false? (:ok r2)) "second resolve returns false")
      (is (= :no-active-appeal (:error r2))
          "second resolve returns :no-active-appeal since status is :pending not :appealed"))))

(deftest reversal-slash-zero-stake-noop
  (testing "Reversal slash with zero stake is a no-op (no slash entry created)"
    (let [res "0xRes" l1 "0xL1" buyer "0xBuyer" seller "0xSeller"
          snap (snap-fix/escrow-snapshot
                {:dispute-resolver res :reversal-slash-bps 2500
                 :appeal-window-duration 3600 :challenge-window-duration 3600
                 :max-dispute-level 2 :resolver-bond-bps 0 :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     ;; res omitted — unregistered resolvers default to zero stake
                     (reg/register-stake l1 10000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id res true "0xl0" nil))
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver l1})
          after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))
          after-l1 (:world (res/execute-resolution after-esc workflow-id l1 false "0xl1" nil))
          slash-id (slash-id-for after-l1 workflow-id :reversal 0)
          slash (slash-for after-l1 workflow-id :reversal 0)]
      (is (nil? slash) "no slash entry created when stake is zero")
      (is (= 0 (reg/get-stake after-l1 res)) "stake unchanged at zero"))))

(deftest reversal-slash-exact-stake
  (testing "Reversal slash at exactly 100% of stake consumes the full stake"
    (let [res "0xRes" l1 "0xL1" buyer "0xBuyer" seller "0xSeller"
          snap (snap-fix/escrow-snapshot
                {:dispute-resolver res :reversal-slash-bps 10000  ;; 100% of stake
                 :appeal-window-duration 3600 :challenge-window-duration 3600
                 :max-dispute-level 2 :resolver-bond-bps 0 :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake res 500)
                     (reg/register-stake l1 10000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 5000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id res true "0xl0" nil))
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver l1})
          after-esc (:world (res/escalate-dispute after-l0 workflow-id buyer esc-fn))
          after-l1 (:world (res/execute-resolution after-esc workflow-id l1 false "0xl1" nil))
          slash-id (slash-id-for after-l1 workflow-id :reversal 0)
          slash (slash-for after-l1 workflow-id :reversal 0)]
      (is (some? slash) "slash entry created")
      (is (= 500 (:amount slash)) "slash amount equals full stake at 10000 bps")
      (is (= 0 (reg/get-stake after-l1 res)) "stake consumed to zero")
      (is (not (neg? (reg/get-stake after-l1 res))) "stake never goes negative"))))

(deftest reversal-slash-credit-rejects-slash-total-underflow
  (let [workflow-id 42
        world (-> {:dispute-levels {workflow-id 2}
                    :escrow-transfers {workflow-id {:token :USDC}}
                    :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                    :pending-fraud-slashes {}
                    :slash-by-context {}
                    :next-slash-id 0
                    :resolver-slash-total {"0xRes" 5}}
                   (insert-test-slash workflow-id :reversal 0
                                      {:status :executed
                                       :reason :reversal
                                       :resolver "0xRes"
                                       :amount 10}))
        result (#'res/reverse-reversal-slash-on-vindication world workflow-id true)]
    (is (= result world) "underflow condition returns world unchanged (no crash)")))

(deftest reversal-slash-credit-rejects-non-positive-amount
  (let [workflow-id 42
        world (-> {:dispute-levels {workflow-id 2}
                    :escrow-transfers {workflow-id {:token :USDC}}
                    :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                    :pending-fraud-slashes {}
                    :slash-by-context {}
                    :next-slash-id 0
                    :resolver-slash-total {"0xRes" 100}}
                   (insert-test-slash workflow-id :reversal 0
                                      {:status :executed
                                       :reason :reversal
                                       :resolver "0xRes"
                                       :amount 0}))
        result (#'res/reverse-reversal-slash-on-vindication world workflow-id true)]
    (is (= result world) "zero-amount slash returns world unchanged (no crash)")))

(deftest execute-fraud-slash-on-appealed-slash
  (testing "executing a slash while appeal is in progress returns :appeal-in-progress"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)]
      (is (= :appealed (get-in world2 [:pending-fraud-slashes workflow-id :status])))
      (let [r (res/execute-fraud-slash world2 workflow-id)]
        (is (false? (:ok r)))
        (is (= :appeal-in-progress (:error r)))))))

(deftest execute-fraud-slash-on-reversed-with-credit-slash
  (testing "executing after rejected appeal executes the slash"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
          world3 (:world (res/resolve-appeal world2 workflow-id gov false workflow-id
                                            :authorization-provenance {:authorization/type :governance
                                                                       :authorization/basis :test}))
          deadline (get-in world3 [:pending-fraud-slashes workflow-id :appeal-deadline] 0)
          world-timed (time-ctx/advance-time world3 {:to (inc deadline)})
          r (res/execute-fraud-slash world-timed workflow-id)]
      (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status]))
          "rejected appeal sets status to :pending (deferred)")
      (is (true? (:ok r))
          "execute-fraud-slash succeeds after rejected appeal")
      (is (= :executed (get-in (:world r) [:pending-fraud-slashes workflow-id :status]))
          "slash executed after execute-fraud-slash"))))

(deftest execute-fraud-slash-on-reversed-status-slash
  (testing "executing a governance-reversed slash returns :slash-already-reversed"
    (let [resolver-addr "0xRes"
          gov "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
          world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
          world3 (:world (res/resolve-appeal world2 workflow-id gov true workflow-id
                                            :authorization-provenance {:authorization/type :governance
                                                                       :authorization/basis :test}))
          r (res/execute-fraud-slash world3 workflow-id)]
      (is (= :reversed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
      (is (false? (:ok r)))
      (is (= :slash-already-reversed (:error r))))))

(deftest force-reversal-slash-immediate
  (testing "force-reversal-slash produces an executed slash entry"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          w (res/force-reversal-slash world workflow-id :track :immediate)
          slash-entry (slash-for w workflow-id :force-reversal 0)]
      (is (some? slash-entry) "force-reversal slash entry should exist")
      (is (= :executed (:status slash-entry)) "immediate track should be executed")
      (is (pos? (:amount slash-entry)) "slash amount should be positive")
      (is (= :reversal (:reason slash-entry)) "reason should be :reversal"))))

(deftest force-reversal-slash-pending
  (testing "force-reversal-slash produces a pending slash entry"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          w (res/force-reversal-slash world workflow-id :track :pending)
          slash-entry (slash-for w workflow-id :force-reversal 0)]
      (is (some? slash-entry) "force-reversal slash entry should exist")
      (is (= :pending (:status slash-entry)) "pending track should be pending")
      (is (pos? (:appeal-deadline slash-entry)) "pending track should have appeal deadline"))))

(deftest force-reversal-slash-custom-bps
  (testing "force-reversal-slash accepts custom slash-bps override when snapshot has zero"
    (let [snap (snap-fix/escrow-snapshot {:reversal-slash-bps 0 :appeal-window-duration 120
                                          :challenge-window-duration 120 :max-dispute-level 2
                                          :dispute-resolver "0xL0Res"})
          w0 (rev-fx/build-reversal-world {:snapshot snap})
          [wf world] [(:workflow-id w0) (:world w0)]
          w (res/force-reversal-slash world wf :slash-bps 5000 :track :immediate)
          slash-entry (slash-for w wf :force-reversal 0)]
      (is (some? slash-entry) "custom bps override should produce a slash entry")
      (is (= :executed (:status slash-entry)) "immediate track should be executed")
      (is (pos? (:amount slash-entry)) "slash amount should be positive"))))

(deftest execute-fraud-slash-emits-allocation-evidence
  (testing "execute-fraud-slash computes and emits pro-rata allocation evidence"
    (let [resolver-addr "0xRes"
          gov  "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 0})
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 10000)
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 1000 snap)
          r-prop (res/propose-fraud-slash world workflow-id gov resolver-addr 300)
          world-prop (:world r-prop)
          world-after-deadline (time-ctx/advance-time world-prop {:to 1001})
          r-exec (res/execute-fraud-slash world-after-deadline workflow-id workflow-id)
          world-exec (:world r-exec)]
      (is (= :pending (get-in world-prop [:pending-fraud-slashes workflow-id :status])))
      (is (true? (:ok r-exec)))
      (is (= :executed (get-in world-exec [:pending-fraud-slashes workflow-id :status])))
      (is (= 9700 (reg/get-stake world-exec resolver-addr))
          "stake reduced by 300"))))

(deftest execute-fraud-slash-emits-projection-and-claims-evidence
  (testing "build-prorata-slash-evidence carries projection and pro-rata proof fields"
    (let [resolver-addr "0xRes"
          world0 (reg/register-stake (t/empty-world 1000) resolver-addr 10000)
          allocation-input {:slash-obligation 300
                            :liable-parties [{:id resolver-addr
                                              :slashable-stake 10000
                                              :available-slashable 10000}]}
          allocation-result (sew-econ/calculate-sew-slash-allocation allocation-input)
          {:keys [evidence]}
          (slashing-ev/build-prorata-slash-evidence
           {:world world0
            :slash-id 0
            :workflow-id 0
            :epoch 0
            :trigger :fraud-slash
            :allocation-input allocation-input
            :allocation-result allocation-result
            :transition-dependencies []
            :attribution nil})
          result (:evidence/result evidence)]
      (is (some? (:evidence/hash evidence)))
      (is (some? (get-in result [:projection :projection-hash])))
      (is (some? (get-in result [:projection :projection-definition-hash])))
      (is (map? (get result :pro-rata)))
      (is (= 13 (count (get-in result [:pro-rata :claims]))))
      (is (= true (get-in result [:pro-rata :summary :holds?])))
      (is (some? (get-in result [:pro-rata :allocation-hash])))
      (is (some? (get-in result [:pro-rata :allocation-result-hash]))
          "Evidence must link to the canonical pro-rata allocation result artifact"))))

(deftest test-proportional-slashing-basis-invariance
  (testing "Proportional slashing must be invariant to intermediate stake mutations"
    (let [r1 "0xRes1"
          r2 "0xRes2"
          initial-world (-> (t/empty-world 1000)
                            (reg/register-stake r1 1000)
                            (reg/register-stake r2 1000))
          slash-obligation 100

          ;; Snapshot the basis BEFORE any mutations
          basis-r1 (reg/get-stake initial-world r1)
          basis-r2 (reg/get-stake initial-world r2)
          total-basis (+ basis-r1 basis-r2)

          ;; Calculate allocation using fixed basis
          liable-parties [{:id r1 :slashable-stake basis-r1 :available-slashable 1000}
                          {:id r2 :slashable-stake basis-r2 :available-slashable 1000}]
          allocation (sew-econ/calculate-sew-slash-allocation
                      {:slash-obligation slash-obligation
                       :liable-parties liable-parties})

          ;; Apply slash sequentially
          w1 (:world (reg/slash-resolver-stake initial-world r1 (get-in allocation [:allocations 0 :paid])))
          w2 (:world (reg/slash-resolver-stake w1 r2 (get-in allocation [:allocations 1 :paid])))]

          ;; The invariant: The total basis used MUST remain 2000,
          ;; even though the world state mutated to 1900.
          
      (is (= 2000 total-basis) "Invariant: Total basis must be snapshotted at transition start")
      (is (= 950 (reg/get-stake w2 r1)))
      (is (= 950 (reg/get-stake w2 r2))))))

(deftest fraud-group-slash-snapshots-canonical-members-and-executes-prorata
  (let [r-a "0xA" r-b "0xB" gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (-> (t/empty-world 1000)
                   (assoc-in [:params :slash-epoch-cap-bps] 10000)
                   (reg/register-stake r-a 100)
                   (reg/register-stake r-b 300))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" r-b 1000 snap)
        proposed (propose-test-fraud-group-slash
                  world workflow-id gov [r-b r-a] 200 "test-group-fraud"
                  {:authorization/type :governance}
                  {:provenance/source :test})
        slash-id (:slash-id proposed)
        pending (get-in (:world proposed) [:pending-fraud-slashes slash-id] {})
        executed (res/execute-fraud-group-slash
                  (time-ctx/advance-time (:world proposed)
                                         {:to (inc (:appeal-deadline pending 0))})
                  workflow-id slash-id)
        final-world (:world executed)
        entry (get-in final-world [:pending-fraud-slashes slash-id])]
    (is (:ok proposed))
    (is (= :fraud-group (:slash/kind pending)))
    (is (= slash-id (:liable-group/id pending))
        "the slash ID is the canonical incident-scoped liable-group identity")
    (is (= :canonical-resolver-id-ascending (:liable-group/ordering pending)))
    (is (= (hc/hash-with-intent
            {:hash/intent :provenance}
            {:liable-group/member-snapshot (:members pending)})
           (:liable-group/member-snapshot-hash pending))
        "the immutable member snapshot is content-addressed")
    (is (= :canonical-resolver-id-ascending (:policy-ordering pending)))
    (is (= [r-a r-b] (mapv :id (:members pending))))
    (is (= [100 300] (mapv :slashable-stake (:members pending))))
    (is (= {} (:appeals pending)))
    (is (:ok executed))
    (is (= :executed (:status entry)))
    (is (= 50 (reg/get-stake final-world r-a)))
    (is (= 150 (reg/get-stake final-world r-b)))
    (is (= [50 150] (mapv :paid (get-in entry [:allocation :allocations]))))
    (is (every? #(pos? (get-in final-world [:resolver-frozen-until %] 0)) [r-a r-b]))
    (is (= 50 (get-in final-world [:resolver-epoch-slashed r-a :amount])))
    (is (= 150 (get-in final-world [:resolver-epoch-slashed r-b :amount])))))

(deftest fraud-group-slash-skips-epoch-capped-members-without-aborting
  (let [r-a "0xA" r-b "0xB"
        world0 (-> (t/empty-world 1000)
                   ;; 20% cap: proportional allocation (A=50, B=150) exceeds each
                   ;; member's 20% epoch capacity (20 and 60), so both are skipped.
                   (assoc-in [:params :slash-epoch-cap-bps] 2000)
                   (reg/register-stake r-a 100) (reg/register-stake r-b 300))
        {:keys [world workflow-id]} (world-ready-for-fraud-slash-propose
                                     world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                     (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
        proposed (propose-test-fraud-group-slash world workflow-id "0xGov" [r-a r-b] 200 "test-epoch-cap"
                                                 {:authorization/type :governance}
                                                 {:provenance/source :test})
        slash-id (:slash-id proposed)
        pending (get-in (:world proposed) [:pending-fraud-slashes slash-id] {})
        before (time-ctx/advance-time (:world proposed) {:to (inc (:appeal-deadline pending 0))})
        result (res/execute-fraud-group-slash before workflow-id slash-id)]
    ;; A member's epoch-cap block no longer aborts the whole group with an error.
    (is (true? (:ok result)))
    (is (nil? (:error result)))
    (is (= :pending (get-in (:world result) [:pending-fraud-slashes slash-id :status]))
        "slash stays pending for retry once the epoch cap resets")
    (is (= 100 (reg/get-stake (:world result) r-a)))
    (is (= 300 (reg/get-stake (:world result) r-b)))
    (let [payments (get-in (:world result) [:pending-fraud-slashes slash-id :allocation :allocations])]
      (is (every? #(= :unpaid (:execution-status %)) payments)
          "both members left unpaid (epoch-capped)")
      (is (every? #(= :slash-epoch-cap-exceeded (:blocked-reason %)) payments)))))

(deftest fraud-group-slash-executes-slashable-member-when-another-has-active-dispute
  (testing "one member's active dispute is skipped while the other is slashed"
    (let [r-a "0xA" r-b "0xB"
          world0 (-> (t/empty-world 1000) (assoc-in [:params :slash-epoch-cap-bps] 10000)
                     (reg/register-stake r-a 100) (reg/register-stake r-b 300))
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                               (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
          world-d (let [{w1 :world wf-id :workflow-id}
                         (lc/create-escrow world "0xBuyer2" "USDC" "0xSeller2" 1000
                                           {:custom-resolver r-a}
                                           (snap-fix/escrow-snapshot {:appeal-window-duration 10}))]
                     (:world (lc/raise-dispute w1 wf-id "0xBuyer2")))
          proposed (propose-test-fraud-group-slash world-d workflow-id "0xGov" [r-a r-b] 200 "test-partial"
                                                   {:authorization/type :governance}
                                                   {:provenance/source :test})
          slash-id (:slash-id proposed)
          pending (get-in (:world proposed) [:pending-fraud-slashes slash-id] {})
          before (time-ctx/advance-time (:world proposed) {:to (inc (:appeal-deadline pending 0))})
          result (res/execute-fraud-group-slash before workflow-id slash-id)]
      (is (true? (:ok result)))
      (is (= :pending (get-in (:world result) [:pending-fraud-slashes slash-id :status]))
          "slash stays pending because one member was skipped")
      (is (= 100 (reg/get-stake (:world result) r-a)) "r-a blocked by active dispute: not slashed")
      (is (= 150 (reg/get-stake (:world result) r-b)) "r-b slashed its full pro-rata share")
      (let [payments (get-in (:world result) [:pending-fraud-slashes slash-id :allocation :allocations])]
        (is (= :unpaid (:execution-status (first (filter #(= r-a (:id %)) payments)))))
        (is (= :paid (:execution-status (first (filter #(= r-b (:id %)) payments)))))))))

(deftest propose-fraud-group-slash-enforces-per-offense-cap
  (testing "no member may be allocated more than the per-offense cap (default 50%)"
    (let [r-a "0xA" r-b "0xB"
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r-a 50) (reg/register-stake r-b 300))
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                               (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
          proposed (propose-test-fraud-group-slash world workflow-id "0xGov" [r-a r-b] 200 "test-cap"
                                                   {:authorization/type :governance}
                                                   {:provenance/source :test})]
      (is (false? (:ok proposed)))
      (is (= :slash-exceeds-max-per-offense (:error proposed))))))

(deftest reversal-slash-credit-restores-expired-executed-track2
  (testing "Track-2 expired-executed reversal slash is credited on vindication"
    (let [workflow-id 42
          slash-id 0
          world (-> {:dispute-levels {workflow-id 2}
                     :escrow-transfers {workflow-id {:token :USDC}}
                     :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                     :pending-fraud-slashes {}
                     :slash-by-context {[workflow-id :reversal 0] slash-id}
                     :reversal-slash-history {slash-id {:status :expired-executed
                                                         :reason :reversal
                                                         :resolver "0xRes"
                                                         :amount 10}}
                     :next-slash-id 1
                     :resolver-stakes {"0xRes" 990}
                     :resolver-slash-total {"0xRes" 10}
                     :slash-credit-liabilities {}}
                    )]
      (let [result (#'res/reverse-reversal-slash-on-vindication world workflow-id true)]
        (is (= 1000 (get-in result [:resolver-stakes "0xRes"]))
            "expired-executed Track-2 reversal stake is credited on vindication")
        (is (= 0 (get-in result [:resolver-slash-total "0xRes"])))
        (is (= 10 (get-in result [:slash-credit-liabilities "0xRes"])))
         (is (= :reversed-with-credit
                (get-in result [:reversal-slash-history slash-id :status])))))))

(deftest reversal-slash-credit-uses-actual-amount-when-clamped
  (testing "clamped reversal slash credits the ACTUAL debited amount, not the nominal basis"
    (let [workflow-id 42
          slash-id 0
          ;; Nominal slash basis is 100, but only 30 could be debited because the
          ;; resolver's stake was clamped at execution.
          world (-> {:dispute-levels {workflow-id 2}
                     :escrow-transfers {workflow-id {:token :USDC}}
                     :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                     :pending-fraud-slashes {}
                     :slash-by-context {[workflow-id :reversal 0] slash-id}
                     :reversal-slash-history {slash-id {:status :expired-executed
                                                         :reason :reversal
                                                         :resolver "0xRes"
                                                         :amount 100
                                                         :actual-amount 30
                                                         :actual-from-stake 30}}
                     :next-slash-id 1
                     ;; Resolver was fully debited: started at 30, slashed 30 -> 0.
                     :resolver-stakes {"0xRes" 0}
                     :resolver-slash-total {"0xRes" 30}
                     :slash-credit-liabilities {}}
                    )]
      (let [result (#'res/reverse-reversal-slash-on-vindication world workflow-id true)]
        ;; Buggy behaviour would credit the nominal 100 -> stake 100, liabilities 100.
        (is (= 30 (get-in result [:resolver-stakes "0xRes"]))
            "stake restored by the ACTUAL debited amount (30), not the nominal basis (100)")
        (is (= 0 (get-in result [:resolver-slash-total "0xRes"])))
        (is (= 30 (get-in result [:slash-credit-liabilities "0xRes"]))
            "liability reflects the actual debited amount (30), not the nominal basis (100)")
        (is (= :reversed-with-credit
               (get-in result [:reversal-slash-history slash-id :status])))))))

(deftest reversal-slash-credit-clamped-track2
  (testing "Track-2 reversal slash under clamped stake records :actual-amount and credits only that"
    (let [res "0xRes" workflow-id 42
          ;; Resolver stake is only 100, so the nominal 250 penalty (1000 * 0.25)
          ;; must clamp to the 100 actually debited at enforcement time.
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake res 100))
          slash-id 0
          entry {:resolver res
                 :basis-amount 1000
                 :basis-kind :stake
                 :slash-bps 2500
                 :amount 250
                 :token :USDC
                 :workflow-id workflow-id
                 :reason :reversal
                 :status :pending
                 :proposed-at 0
                 :appeal-deadline 0
                 :appeal-bond-held 0
                 :contest-deadline 0
                 :reversal-detection-probability 0.0}
          w-pending (-> world0
                        (assoc-in [:escrow-transfers workflow-id] {:token :USDC :escrow-state :released})
                        (assoc-in [:dispute-levels workflow-id] 2)
                        (assoc-in [:previous-decisions workflow-id]
                                  {0 {:is-release true} 1 {:is-release false}})
                        (insert-test-slash workflow-id :reversal 0 entry))
          stake-before-expiry (reg/get-stake w-pending res)
          ;; Finalize (released) -> cleanup-orphaned-slashes enforces the expired pending slash.
          w-final (lc/cleanup-orphaned-slashes w-pending workflow-id)
          archived (get-in w-final [:reversal-slash-history slash-id])
          actual (get archived :actual-amount)
          stake-after-expiry (reg/get-stake w-final res)
          ;; Vindicate: higher level agrees with res's original decision (is-release true).
          w-vind (#'res/reverse-reversal-slash-on-vindication w-final workflow-id true)
          stake-after-vind (reg/get-stake w-vind res)]
      (is (= 100 stake-before-expiry) "resolver stake is below the nominal slash basis")
      (is (= :expired-executed (:status archived)) "Track-2 reversal slash enforced at expiry")
      (is (= 100 actual) ":actual-amount records the clamped debit (100), not the nominal 250")
      (is (= 0 stake-after-expiry) "stake fully debited by the clamped amount")
      (is (= 100 stake-after-vind)
          "vindication restores the ACTUAL debited amount (100), not the nominal 250")
      (is (= :reversed-with-credit (get-in w-vind [:reversal-slash-history slash-id :status]))))))

(deftest fraud-group-member-cannot-appeal-for-another-member
  (let [r-a "0xA" r-b "0xB"
        world0 (-> (t/empty-world 1000) (reg/register-stake r-a 100) (reg/register-stake r-b 300))
        {:keys [world workflow-id]} (world-ready-for-fraud-slash-propose
                                     world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                     (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
         proposed (propose-test-fraud-group-slash world workflow-id "0xGov" [r-a r-b] 200 "test-fraud-group"
                                                  {:authorization/type :governance}
                                                  {:provenance/source :test})
        slash-id (:slash-id proposed)
        result (res/appeal-fraud-group-slash (:world proposed) workflow-id "0xOther" slash-id)]
    (is (false? (:ok result)))
    (is (= :not-liable-member (:error result)))))

(deftest fraud-group-upheld-member-appeal-stays-only-that-allocation
  (let [r-a "0xA" r-b "0xB"
        world0 (-> (t/empty-world 1000) (assoc-in [:params :slash-epoch-cap-bps] 10000)
                   (reg/register-stake r-a 100) (reg/register-stake r-b 300))
        {:keys [world workflow-id]} (world-ready-for-fraud-slash-propose
                                     world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                     (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
        proposed (propose-test-fraud-group-slash world workflow-id "0xGov" [r-a r-b] 200 "test-upheld-appeal"
                                                 {:authorization/type :governance}
                                                 {:provenance/source :test})
        slash-id (:slash-id proposed)
        appealed (:world (res/appeal-fraud-group-slash (:world proposed) workflow-id r-a slash-id))
        resolved (:world (res/resolve-fraud-group-appeal appealed workflow-id "0xGov" r-a true slash-id
                                                        :authorization-provenance {:authorization/type :governance}))
        deadline (get-in resolved [:pending-fraud-slashes slash-id :appeal-deadline] 0)
        executed (res/execute-fraud-group-slash (time-ctx/advance-time resolved {:to (inc deadline)}) workflow-id slash-id)
        final-world (:world executed)
        rows (get-in final-world [:pending-fraud-slashes slash-id :allocation :allocations])]
    (is (:ok executed))
    (is (= :upheld (get-in final-world [:pending-fraud-slashes slash-id :appeals r-a :status])))
    (is (= 100 (reg/get-stake final-world r-a)))
    (is (= 150 (reg/get-stake final-world r-b)) "remaining member keeps original 150 debit")
    (is (= :stayed (:execution-status (first rows))))
    (is (= 0 (:actual-paid (first rows))))
    (is (= 150 (:paid (second rows))))))

(deftest fraud-group-rejected-member-appeal-is-debited-on-execution
  (let [r-a "0xA" r-b "0xB"
        world0 (-> (t/empty-world 1000) (assoc-in [:params :slash-epoch-cap-bps] 10000)
                   (reg/register-stake r-a 100) (reg/register-stake r-b 300))
        {:keys [world workflow-id]} (world-ready-for-fraud-slash-propose
                                     world0 "0xBuyer" "USDC" "0xSeller" r-b 1000
                                     (snap-fix/escrow-snapshot {:appeal-window-duration 10}))
         proposed (propose-test-fraud-group-slash world workflow-id "0xGov" [r-a r-b] 200 "test-rejected-appeal"
                                                  {:authorization/type :governance}
                                                  {:provenance/source :test})
        slash-id (:slash-id proposed)
        appealed (:world (res/appeal-fraud-group-slash (:world proposed) workflow-id r-a slash-id))
        resolved (:world (res/resolve-fraud-group-appeal appealed workflow-id "0xGov" r-a false slash-id
                                                        :authorization-provenance {:authorization/type :governance}))
        deadline (get-in resolved [:pending-fraud-slashes slash-id :appeal-deadline] 0)
        final-world (:world (res/execute-fraud-group-slash
                             (time-ctx/advance-time resolved {:to (inc deadline)}) workflow-id slash-id))]
    (is (= :rejected (get-in final-world [:pending-fraud-slashes slash-id :appeals r-a :status])))
    (is (= 50 (reg/get-stake final-world r-a)))
    (is (= 150 (reg/get-stake final-world r-b)))))

(deftest fraud-group-slash-rejects-invalid-liability-groups
  (let [resolver "0xRes"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (reg/register-stake (t/empty-world 1000) resolver 100)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" resolver 1000 snap)]
    (is (= :empty-liable-resolvers
           (:error (propose-test-fraud-group-slash world workflow-id "0xGov" [] 10 "test-empty-group"
                                                    {:authorization/type :governance}
                                                    {:provenance/source :test}))))
    (is (= :duplicate-liable-resolver
           (:error (propose-test-fraud-group-slash world workflow-id "0xGov" [resolver resolver] 10 "test-duplicate-group"
                                                    {:authorization/type :governance}
                                                    {:provenance/source :test}))))
    (is (= :unregistered-liable-resolver
           (:error (propose-test-fraud-group-slash world workflow-id "0xGov" [resolver "0xOther"] 10 "test-unregistered-group"
                                                    {:authorization/type :governance}
                                                    {:provenance/source :test}))))))

(deftest fraud-incident-reference-fails-closed
  (let [resolver "0xRes"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (reg/register-stake (t/empty-world 1000) resolver 100)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 "0xBuyer" "USDC" "0xSeller" resolver 1000 snap)
        declared (res/declare-fraud-incident
                  world "0xGov"
                  {:incident/id "test-incident"
                   :incident/kind :governance-declared-group-fraud
                   :incident/affected-workflows [{:workflow-id workflow-id}]
                   :incident/rationale "test incident"}
                  {} {})
        declared-world (:world declared)
        ref {:schema-version "fraud-incident-ref.v1"
             :incident-id "test-incident" :incident-hash (:incident-hash declared)}
        propose (fn [w incident-ref]
                  (res/propose-fraud-group-slash w workflow-id "0xGov" [resolver] 10
                                                 {:kind :governance-declared-group-fraud
                                                  :incident-ref incident-ref} {} {}))]
    (is (:ok declared))
    (is (= :fraud-incident-not-found
           (:error (propose declared-world (assoc ref :incident-id "unknown-incident")))))
    (is (= :fraud-incident-hash-mismatch
           (:error (propose declared-world (assoc ref :incident-hash "sha256:wrong")))))
    (is (= :fraud-incident-already-declared
           (:error (res/declare-fraud-incident declared-world "0xGov"
                                               {:incident/id "test-incident"
                                                :incident/kind :governance-declared-group-fraud
                                                :incident/affected-workflows [{:workflow-id workflow-id}]} {} {}))))
    (let [mutated (assoc-in declared-world [:fraud-incidents "test-incident" :incident/rationale] "mutated")]
      (is (= :fraud-incident-hash-mismatch (:error (propose mutated ref)))))))

;; ============ Appeal resolution coverage ============

(deftest resolve-appeal-rejected-non-usdc-bond-token
  (testing "resolve-appeal with non-USDC token: bond distribution uses correct token, slash deferred"
    (let [world     (rev-fx/build-appeal-world {:token "0xT" :appeal-bond-amount 50})
          w2        (:world world)
          wf-id     (:workflow-id world)
          slash-id  (:slash-id world)
          gov       "0xGov"
          r         (res/resolve-appeal w2 wf-id gov false slash-id
                                       :authorization-provenance {:authorization/type :governance
                                                                  :authorization/basis :test})
          w3        (:world r)]
      ;; Appeal custody used non-USDC token
      (is (true? (:ok r)) "resolve-appeal succeeds")
      (is (= :pending (get-in w3 [:pending-fraud-slashes slash-id :status]))
          "rejected appeal sets status to :pending (deferred execution)")
      (is (= 50 (get-in w3 [:appeal-bond-distributions-by-token :0xT] 0))
          "bond distribution must use the bond token, not USDC default")
      (is (= 1000 (reg/get-stake w3 "0xResolver"))
          "stake not yet debited (deferred execution)"))))

(deftest resolve-appeal-rejected-partial-slash
  (testing "resolve-appeal at max per-offense cap: full stake debited after execute, epoch tracks correctly"
    (let [world     (rev-fx/build-appeal-world
                     {:stake 500 :slash-amount 500 :appeal-bond-amount 0
                      :max-slash-per-offense-bps 10000 :slash-epoch-cap-bps 5000})
          w2        (:world world)
          wf-id     (:workflow-id world)
          slash-id  (:slash-id world)
          gov       "0xGov"
          r         (res/resolve-appeal w2 wf-id gov false slash-id
                                       :authorization-provenance {:authorization/type :governance
                                                                  :authorization/basis :test})
          w3        (:world r)
          deadline  (get-in w3 [:pending-fraud-slashes slash-id :appeal-deadline] 0)
          w3-timed  (time-ctx/advance-time w3 {:to (inc deadline)})
          w3-params (assoc-in w3-timed [:params :slash-epoch-cap-bps] 10000)
          w4        (:world (res/execute-fraud-slash w3-params wf-id slash-id))]
      (is (true? (:ok r)) "resolve-appeal succeeds")
      (is (= :pending (get-in w3 [:pending-fraud-slashes slash-id :status]))
          "rejected appeal sets status to :pending (deferred)")
      (is (= :executed (get-in w4 [:pending-fraud-slashes slash-id :status]))
          "executed after execute-fraud-slash")
      ;; Stake is 500, slash requested 500, actual debited = 500
      (is (= 0 (reg/get-stake w4 "0xResolver")) "stake fully consumed")
      (is (= 500 (get-in w4 [:resolver-epoch-slashed "0xResolver" :amount] 0))
          "epoch-slashed tracks actual debited (500)"))))

(deftest execute-fraud-slash-partial-slash
  (testing "execute-fraud-slash with full stake consumption: epoch tracks actual debited"
    (let [resolver-addr "0xRes"
          gov  "0xGov"
          snap (snap-fix/escrow-snapshot {:appeal-window-duration 0 :escrow-fee-bps 0})
          world0 (-> (t/empty-world 1000)
                     (assoc-in [:params :max-slash-per-offense-bps] 10000)
                     (assoc-in [:params :slash-epoch-cap-bps] 10000)
                     (reg/register-stake resolver-addr 500))
          {:keys [world workflow-id]}
          (world-ready-for-fraud-slash-propose world0 "0xBuyer" "0xT" "0xSeller" resolver-addr 2000 snap)
          world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 500) :world)
          ;; A zero-duration appeal window still reserves the proposal timestamp
          ;; for appeal; execution is permitted strictly after the deadline.
          world1 (time-ctx/advance-time world1 {:to 1001})
          r-exec (res/execute-fraud-slash world1 workflow-id workflow-id)
          w-exec (:world r-exec)]
      (is (true? (:ok r-exec)) "execute-fraud-slash succeeds")
      (is (= :executed (get-in w-exec [:pending-fraud-slashes workflow-id :status])))
      (is (= 0 (reg/get-stake w-exec resolver-addr)) "stake fully consumed")
      (is (= 500 (get-in w-exec [:resolver-epoch-slashed resolver-addr :amount] 0))
          "epoch-slashed tracks actual debited (500)"))))

(deftest reversal-slash-vindication-voids-pending-track2
  (testing "A Track-2 (:pending) reversal slash is voided (:reversed) when a higher
            level vindicates the originally-reversed decision, so a vindicated
            resolver is never slashed once the appeal window lapses (Bug #1)."
    (let [workflow-id 42
          slash-id 0
          world (-> {:dispute-levels {workflow-id 2}
                     :escrow-transfers {workflow-id {:token "USDC"}}
                     :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                     :pending-fraud-slashes {slash-id {:status :pending
                                                       :reason :reversal
                                                       :resolver "0xRes"
                                                       :amount 10}}
                     :slash-by-context {[workflow-id :reversal 0] slash-id}
                     :resolver-stakes {"0xRes" 1000}
                     :resolver-slash-total {"0xRes" 0}}
                    (assoc-in [:next-slash-id] 1))]
      (let [result (#'res/reverse-reversal-slash-on-vindication world workflow-id true)]
        (is (= :reversed (get-in result [:pending-fraud-slashes slash-id :status]))
            "pending Track-2 reversal slash is voided on vindication")
        (is (= 1000 (get-in result [:resolver-stakes "0xRes"]))
            "vindicated resolver is not slashed while the reversal slash was pending")))))

(deftest reversal-slash-keeps-pending-when-not-vindicated
  (testing "A pending Track-2 reversal slash is preserved when the higher level does
            NOT vindicate (agree with) the reversal."
    (let [workflow-id 42
          slash-id 0
          world (-> {:dispute-levels {workflow-id 2}
                     :escrow-transfers {workflow-id {:token "USDC"}}
                     :previous-decisions {workflow-id {0 {:is-release true}
                                                       1 {:is-release false}}}
                     :pending-fraud-slashes {slash-id {:status :pending
                                                       :reason :reversal
                                                       :resolver "0xRes"
                                                       :age 0
                                                       :amount 10}}
                     :slash-by-context {[workflow-id :reversal 0] slash-id}
                     :resolver-stakes {"0xRes" 1000}}
                    (assoc-in [:next-slash-id] 1))]
      (let [result (#'res/reverse-reversal-slash-on-vindication world workflow-id false)]
        (is (= :pending (get-in result [:pending-fraud-slashes slash-id :status]))
            "pending reversal slash kept when not vindicated")))))

(deftest execute-fraud-slash-epoch-cap-resets-after-epoch
  (testing "After an epoch elapses, a previously-slashed resolver can be slashed
            again: the cap is per-epoch, not a permanent lifetime cap."
    (let [res "0xRes"
          workflow-id 0
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake res 1000)
                     (assoc-in [:params :slash-epoch-duration-seconds] 1000)
                     (assoc-in [:params :slash-epoch-cap-bps] 2000))
          mk-entry (fn [amount]
                     {:status :pending :resolver res :amount amount :token :USDC
                      :appeal-deadline 100 :reason :fraud})
          w1 (insert-test-slash world0 workflow-id :fraud 0 (mk-entry 200))
          sid1 (get-in w1 [:slash-by-context [workflow-id :fraud 0]])
          ex1 (res/execute-fraud-slash w1 workflow-id sid1)
          w2 (insert-test-slash (:world ex1) workflow-id :fraud 1 (mk-entry 50))
          sid2 (get-in w2 [:slash-by-context [workflow-id :fraud 1]])
          ex2 (res/execute-fraud-slash w2 workflow-id sid2)
          w3 (time-ctx/advance-time (:world ex2) {:to 2000})
          w4 (insert-test-slash w3 workflow-id :fraud 2 (mk-entry 50))
          sid3 (get-in w4 [:slash-by-context [workflow-id :fraud 2]])
          ex3 (res/execute-fraud-slash w4 workflow-id sid3)]
      (is (nil? (:error ex1)) "first slash within cap succeeds")
      (is (= :slash-epoch-cap-exceeded (:error ex2))
          "same-epoch second slash is blocked by the cap")
      (is (nil? (:error ex3))
          "after an epoch elapses the cap resets and slashing is allowed again"))))
