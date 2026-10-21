(ns resolver-sim.benchmark.packs.partial-fill.evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.evidence :as pfev]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.modules.liquid-lending :as ll]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- real-world []
  (-> (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- two-decision-world []
  (-> (real-world)
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- latest-decision [world]
  (last (vals (:yield/partial-fill-decisions world))))

(def sample-final-world
  {:yield/withdrawn {:USDC {"alice" 15}}
   :yield/positions {"alice"
                     {:principal 100
                      :deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}
                      :cumulative-fulfilled 15}}})

(def final-world-pos-hash
  (str "sha256:" (hc/domain-hash :state-projection
                                 (get-in sample-final-world [:yield/positions "alice"]))))

(def sample-application
  {:propagation-id "prop-1"
   :participants
   [{:participant-id "alice"
     :position-before {:deferred-position
                       {:position/id "alice/deferred/1"
                        :position/current-amount 20
                        :position/status :active}}
     :position-before-hash "sha256:before"
     :position-after {:deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}}
     :position-after-hash final-world-pos-hash
     :withdrawn {:token :USDC :before 10 :delta 5 :after 15}
     :obligation {:before 20 :fulfilled 5 :deferred 15 :after 15}
     :cumulative-fulfilled {:before 10 :delta 5 :after 15}}]})

(deftest derive-state-write-back-verified
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)]
    (is (seq wb))
    (let [alice (first wb)]
      (is (= "alice" (:participant/id alice)))
      (is (true? (get-in alice [:withdrawn :verified?])))
      (is (true? (get-in alice [:position :verified?])))
      (is (true? (get-in alice [:deferred-position :verified?]))))))

(deftest derive-state-write-back-values
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)
        alice (first wb)]
    (is (= 10 (get-in alice [:withdrawn :before])))
    (is (= 5 (get-in alice [:withdrawn :delta])))
    (is (= 15 (get-in alice [:withdrawn :after])))
    (is (= 15 (get-in alice [:withdrawn :final-world-value])))
    (is (= 15 (get-in alice [:deferred-position :successor-current-amount])))
    (is (= 15 (get-in alice [:deferred-position :final-world-current-amount])))))

(deftest derive-state-write-back-mismatch-detected
  (let [bad-world (assoc-in sample-final-world
                            [:yield/withdrawn :USDC "alice"] 99)
        wb (pfev/derive-state-write-back sample-application bad-world)
        alice (first wb)]
    (is (false? (get-in alice [:withdrawn :verified?])))))

(deftest derive-state-write-back-nil-for-no-participants
  (let [app {:participants []}
        wb (pfev/derive-state-write-back app {})]
    (is (nil? wb))))

(deftest collect-application-refs
  (let [world {:yield/applied-pro-rata-propagations
               {"prop-1" {:propagation-id "prop-1"
                          :application/hash "sha256:app-hash"
                          :calculation-id "calc-1"
                          :outcome-hash "sha256:outcome"
                          :application-order {:step 1 :event-id 0}}}}]
    (is (= 1 (count (pfev/collect-application-refs world))))
    (is (= "prop-1" (:propagation/id (first (pfev/collect-application-refs world)))))))

(deftest semantic-commitments-empty
  (is (nil? (pfev/semantic-commitments {}))))

(deftest semantic-commitments-with-decisions
  (let [world {:yield/partial-fill-decisions
               {"d1" {:decision/id "d1" :decision/hash "sha256:d1"}}}]
    (let [commitments (pfev/semantic-commitments world)]
      (is (some? commitments))
      (is (map? (:semantic/economic-application commitments)))
      (is (some? (get-in commitments [:semantic/economic-application
                                      :partial-fill-decisions-root]))))))

(deftest application-evidence-ladder-basic
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)]
    (is (= 1 (count ladder)))
    (let [entry (first ladder)]
      (is (= "prop-1" (:propagation/id entry)))
      (is (= 6 (count (:levels entry)))))))

(deftest application-ladder-next-precondition-not-observed
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        continuity (nth (:levels entry) 4)]
    (is (= :continuity-consumed (:level continuity)))
    (is (= "not-observed" (:status continuity)))))

(deftest application-evidence-ladder-levels-verified
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        levels (:levels entry)]
    (is (= "verified" (:status (nth levels 0))))
    (is (= "verified" (:status (nth levels 1))))
    (is (= "verified" (:status (nth levels 2))))
    (is (= "not-observed" (:status (nth levels 3))))
    (is (= "not-observed" (:status (nth levels 4))))
    (is (= "not-observed" (:status (nth levels 5))))))

;; ── partial-fill-decisions-root verification ─────────────────────────────

(defn- claim-key [d]
  (first (keys (:requested d))))

(defn- full-fill-world []
  (-> (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
      (assoc-in [:total-held :USDC] 100)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- three-claim-world []
  (-> (reduce (fn [w o]
                (ll/deposit w test-mod {:owner/id o :amount 100 :token "USDC"}))
              base-world
              ["a1" "a2" "a3"])
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- partial-recovery-concurrent-world []
  (-> (reduce (fn [w o]
                (ll/deposit w test-mod {:owner/id o :amount 100 :token "USDC"}))
              base-world
              ["a1" "a2" "a3"])
      (assoc-in [:total-held :USDC] 0)
      (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})
      ;; All three deferred entitlements arrive together after 15 units become
      ;; available in a later round.
      (assoc-in [:total-held :USDC] 15)
      (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- shortfall-observation-world [& [execution-id]]
  (let [world (cond-> base-world execution-id (assoc :execution/id execution-id))]
    (-> (reduce (fn [w owner]
                  (ll/deposit w test-mod {:owner/id owner :amount 10000 :token "USDC"}))
                world ["a1" "a2" "a3"])
        (assoc-in [:total-held :USDC] 20000)
        (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3"]
                                      :token "USDC"
                                      :allocation-mode :pro-rata}))))

(defn- shortfall-world-with-incoming-entitlement []
  (-> (shortfall-observation-world)
      (ll/deposit test-mod {:owner/id "a4" :amount 10000 :token "USDC"})
      (assoc-in [:total-held :USDC] 0)
      (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3" "a4"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(deftest shortfall-recovery-evidence-captures-concurrent-rounds
  (let [world (partial-recovery-concurrent-world)
        artifact (pfev/build-shortfall-recovery-evidence world :episode/id "episode-1")
        verification (pfev/verify-shortfall-recovery-evidence world artifact)]
    (is (true? (pfev/valid-shortfall-recovery-evidence? artifact)))
    (is (= :evaluated-pass (:classification verification)))
    (is (= 2 (:round-count artifact)))
    (is (= [3 3] (mapv (comp count :incoming-claims) (:rounds artifact))))
    (is (= [0 15] (mapv #(get-in % [:budget-observation :liquidity/available])
                         (:rounds artifact))))
    (is (= [nil 15] (mapv :budget-change-from-prior-round (:rounds artifact))))
    (is (= [300 300] (mapv #(get-in % [:totals :owed]) (:rounds artifact))))
    (is (= [0 15] (mapv #(get-in % [:totals :filled]) (:rounds artifact))))
    (is (true? (:lineage-conservation/holds? artifact)))
    (is (= :source-event-authentication-required
           (get-in artifact [:recovery-observation :gross-recovery-attribution])))))

(deftest shortfall-recovery-artifact-tampering-is-detected
  (let [world (partial-recovery-concurrent-world)
        artifact (pfev/build-shortfall-recovery-evidence world :episode/id "episode-1")
        tampered (assoc-in artifact [:rounds 1 :incoming-claims 0 :filled] 999)
        verification (pfev/verify-shortfall-recovery-evidence world tampered)]
    (is (false? (pfev/valid-shortfall-recovery-evidence? tampered)))
    (is (= :evaluated-fail (:classification verification)))
    (is (false? (:artifact-integrity? verification)))))

(deftest shortfall-verification-fails-closed-on-invalid-source-decision-hash
  (let [world (partial-recovery-concurrent-world)
        decision (first (vals (:yield/partial-fill-decisions world)))
        corrupted (assoc-in world
                            [:yield/partial-fill-decisions (:decision/id decision)
                             :decision/hash]
                            "invalid-hash")
        artifact (pfev/build-shortfall-recovery-evidence corrupted)
        verification (pfev/verify-shortfall-recovery-evidence corrupted artifact)]
    (is (false? (:decision-integrity/holds? artifact)))
    (is (false? (pfev/valid-shortfall-recovery-evidence? artifact)))
    (is (= :evaluated-fail (:classification verification)))
    (is (false? (:decision-integrity? verification)))))

(deftest short-lived-and-partially-restored-shortfalls-preserve-intermediate-observations
  (let [world (shortfall-observation-world)
        decision (first (filter #(= :yield-withdraw-shared (:decision/source %))
                                (vals (:yield/partial-fill-decisions world))))
        rows (get-in decision [:evidence :allocation-rows])
        owed-rights (mapv (fn [row] {:entitlement/id (:key row)
                                     :beneficiary (:key row)
                                     :amount (:owed row)}) rows)
        deferred-rights (mapv (fn [row] {:entitlement/id (:key row)
                                         :beneficiary (:key row)
                                         :amount (:deferred row)})
                              (filter #(pos? (:deferred %)) rows))
        context {:operation/id "test-execution" :decision/id (:decision/id decision)
                 :module/id :test-mod :token :USDC :time-unit :seconds}
        observation (fn [id at order phase available rights events]
                      (merge context {:observation/id id :observed-at at :order order
                                      :observation/phase phase
                                      :obligation/amount (reduce + 0 (map :amount rights))
                                      :claim-set rights
                                      :liquidity/available available
                                      :events-since-prior events
                                      :source :chain-state :source-ref (str "block:" at)
                                      :source-state-root (str "root:" at)}))
        event (fn [id kind amount direction]
                (cond-> {:event/id id :kind kind :amount amount
                         :source-ref (str "event:" id)
                         :source-state-root (str "event-root:" id)}
                  direction (assoc :direction direction)))
        payment-events (mapv (fn [row]
                               (assoc (event (str "pay-" (:key row))
                                             :beneficiary-payment (:filled row) nil)
                                      :entitlement/id (:key row)
                                      :beneficiary (:key row))) rows)
        remaining-rights (fn [rights entitlement amount]
                           (mapv (fn [right]
                                   (if (= entitlement (:entitlement/id right))
                                     (update right :amount - amount)
                                     right))
                                 rights))
        after-one-payment (remaining-rights deferred-rights (:key (first rows)) 1000)
        payment-event (assoc (event "pay-a1-after-recovery"
                                    :beneficiary-payment 1000 nil)
                             :entitlement/id (:key (first rows))
                             :beneficiary (:key (first rows)))
        observations [(observation "obs-0" 99
                                   {:block-number 10 :transaction-index 0 :log-index 0}
                                   :pre-settlement 30000 owed-rights [])
                      (observation "obs-1" 100
                                   {:block-number 11 :transaction-index 0 :log-index 0}
                                   :pre-settlement 20000 owed-rights
                                   [(event "temporary-availability-loss"
                                           :availability-reclassification 10000 :decrease)])
                      (observation "obs-2" 101
                                   {:block-number 12 :transaction-index 0 :log-index 0}
                                   :post-settlement 0 deferred-rights payment-events)
                      (observation "obs-3" 105
                                   {:block-number 13 :transaction-index 0 :log-index 0}
                                   :post-settlement 5000 deferred-rights
                                   [(event "recover-5k-a" :capital-recovery 5000 nil)])
                      (observation "obs-4" 106
                                   {:block-number 14 :transaction-index 0 :log-index 0}
                                   :post-settlement 4000 after-one-payment [payment-event])
                      (observation "obs-5" 109
                                   {:block-number 15 :transaction-index 0 :log-index 0}
                                   :post-settlement 9000 after-one-payment
                                   [(event "recover-5k-b" :capital-recovery 5000 nil)])]
        artifact (pfev/build-shortfall-recovery-evidence
                  world :episode/id "short-lived-episode"
                  :short-lived-threshold 10
                  :shortfall-observations observations)
        first-deferred (first deferred-rights)
        reduced-rights (update-in deferred-rights [0 :amount] - 1000)
        liability-reduction-observations
        [(observation "liability-0" 200
                      {:block-number 20 :transaction-index 0 :log-index 0}
                      :post-settlement 0 deferred-rights [])
         (observation "liability-1" 201
                      {:block-number 21 :transaction-index 0 :log-index 0}
                      :post-settlement 0 reduced-rights
                      [(assoc (event "cancel-1k" :liability-reduction 1000 nil)
                              :entitlement/id (:entitlement/id first-deferred)
                              :beneficiary (:beneficiary first-deferred))])]
        liability-artifact (pfev/build-shortfall-recovery-evidence
                            world :episode/id "liability-reduction-episode"
                            :shortfall-observations liability-reduction-observations)
        invalid-flow (pfev/build-shortfall-recovery-evidence
                      world :episode/id "unreconciled-flow-episode"
                      :shortfall-observations
                      (assoc-in observations [2 :events-since-prior 0 :amount] 4000))
        invalid-duration (pfev/build-shortfall-recovery-evidence
                          world :episode/id "invalid-duration-episode"
                          :short-lived-threshold -1
                          :shortfall-observations observations)
        still-open-observations (subvec (vec observations) 0 5)
        still-open-artifact (pfev/build-shortfall-recovery-evidence
                             world :episode/id "partially-restored-open-episode"
                             :short-lived-threshold 10
                             :shortfall-observations still-open-observations)
        timeline (get-in artifact [:shortfall-observations :timeline])
        still-open-timeline (get-in still-open-artifact [:shortfall-observations :timeline])
        still-open-last (last still-open-timeline)
        liability-timeline (get-in liability-artifact [:shortfall-observations :timeline])
        verification (pfev/verify-shortfall-recovery-evidence world artifact)]
    (is (= :provided-with-source-references
           (get-in artifact [:shortfall-observations :classification])))
    (is (= [0 10000 10000 5000 5000 0] (mapv :shortfall/amount timeline)))
    (is (= [:shortfall-unchanged :shortfall-opened :shortfall-unchanged
            :shortfall-reduced :shortfall-unchanged :shortfall-fully-restored]
           (mapv :episode/event timeline)))
    (is (= [nil 0 0 5000 0 5000]
           (mapv :shortfall/reduced-since-prior timeline)))
    (is (= [nil 0 0 5000 0 5000]
           (mapv :declared-capital-recovery-event-amount timeline)))
    (is (= [10000 9000] (mapv :shortfall/amount liability-timeline)))
    (is (= [nil 0]
           (mapv :declared-capital-recovery-event-amount liability-timeline)))
    (is (= :shortfall-reduced (:episode/event (second liability-timeline))))
    (is (zero? (:declared-capital-recovery-event-amount
                (second liability-timeline))))
    (is (= :invalid (get-in invalid-flow [:shortfall-observations :classification])))
    (is (false? (pfev/valid-shortfall-recovery-evidence? invalid-flow)))
    (is (false? (:episode-duration-policy/holds? invalid-duration)))
    (is (false? (pfev/valid-shortfall-recovery-evidence? invalid-duration)))
    (is (= [0 10000 10000 5000 5000 0]
           (mapv :shortfall/residual-after-observation timeline)))
    (is (= 10000 (:obligation/amount (nth timeline 2))))
    (is (= 10000 (:shortfall/amount (nth timeline 2))))
    (is (= 5000 (:shortfall/amount (nth timeline 4))))
    (is (= 4000 (:liquidity/available (nth timeline 4))))
    (is (= after-one-payment (:claim-set (nth timeline 4))))
    (is (= 10000 (reduce + (map :amount
                                (filter #(= :capital-recovery (:kind %))
                                        (mapcat :events-since-prior timeline)))))
        "the two declared recovery events are recorded separately from the 1k claim")
    (is (= [nil nil nil nil nil 9]
           (mapv :episode/detected-to-closed-duration timeline)))
    (is (= 10 (:episode/duration-upper-bound (last timeline))))
    (is (true? (:episode/short-lived? (last timeline))))
    (is (= :qualified-by-duration-upper-bound
           (:episode/short-lived-classification (last timeline))))
    (is (true? (pfev/valid-shortfall-recovery-evidence? still-open-artifact)))
    (is (= [0 10000 10000 5000 5000]
           (mapv :shortfall/amount still-open-timeline)))
    (is (= :shortfall-unchanged (:episode/event still-open-last)))
    (is (= 5000 (:shortfall/amount still-open-last)))
    (is (nil? (:episode/closed-at still-open-last)))
    (is (nil? (:episode/duration-upper-bound still-open-last)))
    (is (nil? (:episode/short-lived? still-open-last)))
    (is (= :not-closed (:episode/short-lived-classification still-open-last))
        "partial restoration without a later zero observation is not classified as short-lived")
    (is (every? #(true? (get-in % [:transition/reconciliation :reconciles?]))
                (rest timeline)))
    (is (= :not-performed (:shortfall-observations/source-authentication artifact)))
    (is (= :evaluated-pass (:classification verification)))
    (is (true? (pfev/valid-shortfall-recovery-evidence? artifact)))))

(deftest incoming-entitlement-joins-open-shortfall-alongside-parallel-claims
  (let [world (shortfall-world-with-incoming-entitlement)
        decisions (->> (vals (:yield/partial-fill-decisions world))
                       (filter #(= :yield-withdraw-shared (:decision/source %)))
                       (sort-by (fn [decision]
                                  [(count (get-in decision [:evidence :allocation-rows]))
                                   (:decision/id decision)]))
                       vec)
        [initial-decision incoming-decision] decisions
        rights (fn [decision field]
                 (mapv (fn [row] {:entitlement/id (:key row)
                                  :beneficiary (:key row)
                                  :amount (get row field)})
                       (filter #(pos? (get % field))
                               (get-in decision [:evidence :allocation-rows]))))
        pre-rights (rights initial-decision :owed)
        first-residual-rights (rights initial-decision :deferred)
        incoming-rights (rights incoming-decision :deferred)
        obs (fn [id decision at block phase available claims events]
              {:observation/id id :observed-at at
               :order {:block-number block :transaction-index 0 :log-index 0}
               :operation/id "test-execution" :decision/id (:decision/id decision)
               :module/id :test-mod :token :USDC :observation/phase phase
               :time-unit :seconds :obligation/amount (reduce + 0 (map :amount claims))
               :claim-set claims :liquidity/available available
               :events-since-prior events :source :chain-state
               :source-ref (str "block:" block) :source-state-root (str "root:" block)})
        event (fn [id kind amount entitlement beneficiary]
                (cond-> {:event/id id :kind kind :amount amount
                         :source-ref (str "event:" id)
                         :source-state-root (str "event-root:" id)}
                  entitlement (assoc :entitlement/id entitlement)
                  beneficiary (assoc :beneficiary beneficiary)))
        opening-payments (->> (get-in initial-decision [:evidence :allocation-rows])
                              (filter #(pos? (:filled %)))
                              (mapv #(event (str "initial-payment-" (:key %))
                                            :beneficiary-payment (:filled %)
                                            (:key %) (:key %))))
        a1-remaining (update (first (filter #(= "a1" (:entitlement/id %))
                                            incoming-rights))
                             :amount - 1000)
        parallel-rights (->> incoming-rights
                             (mapv #(if (= "a1" (:entitlement/id %)) a1-remaining %)))
        observations [(obs "baseline" initial-decision 99 39
                           :pre-settlement 30000 pre-rights [])
                      (obs "shortfall-opened" initial-decision 100 40
                           :pre-settlement 20000 pre-rights
                           [(assoc (event "availability-drops-10k"
                                          :availability-reclassification 10000 nil nil)
                                   :direction :decrease)])
                      (obs "initial-parallel-settlement" initial-decision 101 41
                           :post-settlement 0 first-residual-rights opening-payments)
                      (obs "incoming-right-arrives" incoming-decision 102 42
                           :post-settlement 0 incoming-rights
                           [(event "new-a4-right" :new-entitlement 10000 "a4" "a4")])
                      (obs "partially-recovered" incoming-decision 105 43
                           :post-settlement 5000 incoming-rights
                           [(event "recover-5k" :capital-recovery 5000 nil nil)])
                      (obs "claim-during-residual-shortfall" incoming-decision 106 44
                           :post-settlement 4000 parallel-rights
                           [(event "a1-pays-1k" :beneficiary-payment 1000 "a1" "a1")])
                      (obs "fully-recovered" incoming-decision 109 45
                           :post-settlement 19000 parallel-rights
                           [(event "recover-15k" :capital-recovery 15000 nil nil)])]
        artifact (pfev/build-shortfall-recovery-evidence
                  world :episode/id "incoming-entitlement-shortfall"
                  :shortfall-observations observations)
        timeline (get-in artifact [:shortfall-observations :timeline])
        verification (pfev/verify-shortfall-recovery-evidence world artifact)]
    (is (= 2 (count decisions)))
    (is (true? (pfev/valid-shortfall-recovery-evidence? artifact)))
    (is (= :evaluated-pass (:classification verification)))
    (is (= [0 10000 10000 20000 15000 15000 0]
           (mapv :shortfall/amount timeline)))
    (is (= 4 (count (:claim-set (nth timeline 3)))))
    (is (= 10000 (:amount (last (:claim-set (nth timeline 3))))))
    (is (= 19000 (:obligation/amount (nth timeline 6))))
    (is (= 9 (:episode/detected-to-closed-duration (last timeline))))
    (is (= 10 (:episode/duration-upper-bound (last timeline))))
    (is (every? #(true? (get-in % [:transition/reconciliation :reconciles?]))
                (rest timeline)))))

(deftest shortfall-source-authentication-is-explicit-and-fails-closed
  (let [world (shortfall-observation-world)
        decision (first (filter #(= :yield-withdraw-shared (:decision/source %))
                                (vals (:yield/partial-fill-decisions world))))
        rights (mapv (fn [row] {:entitlement/id (:key row)
                                :beneficiary (:key row)
                                :amount (:deferred row)})
                     (filter #(pos? (:deferred %))
                             (get-in decision [:evidence :allocation-rows])))
        obligation (reduce + 0 (map :amount rights))
        base {:operation/id "test-execution" :decision/id (:decision/id decision)
              :chain/id 31337 :deployment/id "local-anvil/partial-fill-v3"
              :module/id :test-mod :token :USDC :time-unit :seconds
              :observation/phase :post-settlement :obligation/amount obligation
              :claim-set rights :source :chain-state}
        event {:event/id "recovery-event" :kind :capital-recovery :amount 10000
               :source-ref "event:recovery" :source-state-root "event-root:recovery"}
        observations [(merge base {:observation/id "shortfall-open"
                                   :observed-at 100
                                   :order {:block-number 10 :transaction-index 0 :log-index 0}
                                   :liquidity/available 0 :events-since-prior []
                                   :source-ref "block:10" :source-state-root "root:10"})
                      (merge base {:observation/id "shortfall-restored"
                                   :observed-at 105
                                   :order {:block-number 11 :transaction-index 0 :log-index 0}
                                   :liquidity/available 10000 :events-since-prior [event]
                                   :source-ref "block:11" :source-state-root "root:11"})]
        artifact (pfev/build-shortfall-recovery-evidence
                  world :episode/id "source-authentication-episode"
                  :shortfall-observations observations)
        authenticator (fn [record]
                        {:status :verified
                         :source-ref (:source-ref record)
                         :source-state-root (:source-state-root record)
                         :verifier/id "test/chain-state-verifier"
                         :verifier/revision "test-verifier@immutable-rev"
                         :attestation/ref (str "attestation:" (:record/id record))
                         :attestation/root (str "attestation-root:" (:record/id record))})
        no-auth (pfev/verify-shortfall-recovery-evidence world artifact)
        verified (pfev/verify-shortfall-recovery-evidence
                  world artifact :source-authenticator authenticator)
        authenticated-records (get-in verified [:source-authentication :records])
        observed-record (some #(when (= "shortfall-open" (:record/id %)) %) authenticated-records)
        recovered-event (some #(when (= "recovery-event" (:record/id %)) %) authenticated-records)
        rejected (pfev/verify-shortfall-recovery-evidence
                  world artifact :source-authenticator
                  (fn [record]
                    (assoc (authenticator record)
                           :source-state-root "substituted-root")))
        missing-identity (pfev/verify-shortfall-recovery-evidence
                          world artifact :source-authenticator
                          (fn [record]
                            (dissoc (authenticator record) :verifier/id)))
        mixed-revision (pfev/verify-shortfall-recovery-evidence
                        world artifact :source-authenticator
                        (fn [record]
                          (assoc (authenticator record)
                                 :verifier/revision
                                 (if (= "recovery-event" (:record/id record))
                                   "test-verifier@other-revision"
                                   "test-verifier@immutable-rev"))))
        reused-attestation (pfev/verify-shortfall-recovery-evidence
                            world artifact :source-authenticator
                            (fn [record]
                              (assoc (authenticator record)
                                     :attestation/ref "attestation:reused"
                                     :attestation/root "attestation-root:reused")))]
    (is (= :not-performed (get-in no-auth [:source-authentication :classification])))
    (is (= :evaluated-pass (:classification no-auth)))
    (is (= :verified (get-in verified [:source-authentication :classification])))
    (is (= 3 (get-in verified [:source-authentication :record-count])))
    (is (= 3 (count authenticated-records)))
    (is (every? :attestation/root
                authenticated-records))
    (is (= "test-execution" (get-in observed-record [:source/context :operation/id])))
    (is (= 31337 (get-in observed-record [:source/context :chain/id])))
    (is (= "local-anvil/partial-fill-v3"
           (get-in recovered-event [:source/context :deployment/id])))
    (is (= {:block-number 10 :transaction-index 0 :log-index 0}
           (get-in observed-record [:source/context :observation/order])))
    (is (= 0 (get-in observed-record [:source/expected :liquidity/available])))
    (is (= :capital-recovery (get-in recovered-event [:source/expected :kind])))
    (is (= 10000 (get-in recovered-event [:source/expected :amount])))
    (is (= "shortfall-restored"
           (get-in recovered-event [:source/context :parent-observation/id])))
    (is (= 0 (get-in recovered-event [:source/context :event/index])))
    (is (= :evaluated-pass (:classification verified)))
    (is (= :failed (get-in rejected [:source-authentication :classification])))
    (is (= :evaluated-fail (:classification rejected)))
    (is (false? (:source-authentication? rejected)))
    (is (= :failed (get-in missing-identity [:source-authentication :classification])))
    (is (= :evaluated-fail (:classification missing-identity)))
    (is (= :failed (get-in mixed-revision [:source-authentication :classification])))
    (is (= :evaluated-fail (:classification mixed-revision)))
    (is (= :failed (get-in reused-attestation [:source-authentication :classification])))
    (is (= :evaluated-fail (:classification reused-attestation)))))

(deftest shortfall-observations-reject-time-reordering-and-context-mixing
  (let [world (shortfall-observation-world)
        decision (first (filter #(= :yield-withdraw-shared (:decision/source %))
                                (vals (:yield/partial-fill-decisions world))))
        base {:operation/id "test-execution" :decision/id (:decision/id decision)
              :module/id :test-mod :token :USDC :time-unit :seconds
              :observation/phase :post-settlement
              :obligation/amount (reduce + 0 (map :deferred
                                                   (get-in decision [:evidence :allocation-rows])))
              :liquidity/available 0
              :claim-set (mapv (fn [row] {:entitlement/id (:key row)
                                         :beneficiary (:key row)
                                         :amount (:deferred row)})
                               (filter #(pos? (:deferred %))
                                       (get-in decision [:evidence :allocation-rows])))
              :events-since-prior [] :source :chain-state
              :source-ref "block" :source-state-root "root"}
        ordered (fn [observation-id at block]
                  (assoc base :observation/id observation-id :observed-at at
                         :order {:block-number block :transaction-index 0 :log-index 0}))
        out-of-order [(ordered "a" 2 2) (ordered "b" 1 3)]
        mixed-operation [(ordered "a" 1 1)
                         (assoc (ordered "b" 2 2) :operation/id "foreign-execution")]
        misbound-claim [(ordered "a" 1 1)
                        (assoc (ordered "b" 2 2)
                               :claim-set [{:entitlement/id "intruder"
                                            :beneficiary "attacker-wallet" :amount 10000}])]
        artifact (pfev/build-shortfall-recovery-evidence
                  world :episode/id "invalid-observation-episode"
                  :shortfall-observations out-of-order)]
    (is (= :invalid (get-in artifact [:shortfall-observations :classification])))
    (is (false? (pfev/valid-shortfall-recovery-evidence? artifact)))
    (is (= :invalid
           (get-in (pfev/build-shortfall-recovery-evidence
                    world :episode/id "mixed-operation-episode"
                    :shortfall-observations mixed-operation)
                   [:shortfall-observations :classification])))
    (is (= :invalid
           (get-in (pfev/build-shortfall-recovery-evidence
                    world :episode/id "claim-beneficiary-drift-episode"
                    :shortfall-observations misbound-claim)
                   [:shortfall-observations :classification])))))

(deftest valid-shortfall-observation-cannot-be-transplanted-across-operation-or-rights
  (let [source-world (shortfall-observation-world)
        source-decision (latest-decision source-world)
        rights (mapv (fn [row] {:entitlement/id (:key row)
                                :beneficiary (:key row)
                                :amount (:deferred row)})
                     (filter #(pos? (:deferred %))
                             (get-in source-decision [:evidence :allocation-rows])))
        observation {:observation/id "source-observation"
                     :observed-at 300
                     :order {:block-number 30 :transaction-index 0 :log-index 0}
                     :operation/id "test-execution"
                     :decision/id (:decision/id source-decision)
                     :module/id :test-mod :token :USDC
                     :observation/phase :post-settlement
                     :time-unit :seconds
                     :obligation/amount (reduce + 0 (map :amount rights))
                     :claim-set rights :liquidity/available 0
                     :events-since-prior [] :source :chain-state
                     :source-ref "block:30" :source-state-root "root:30"}
        source-artifact (pfev/build-shortfall-recovery-evidence
                         source-world :episode/id "source-episode"
                         :shortfall-observations [observation])
        other-world (shortfall-observation-world "other-execution")
        other-world-with-source-decision
        (assoc-in other-world [:yield/partial-fill-decisions
                               (:decision/id source-decision)] source-decision)
        transplanted (pfev/build-shortfall-recovery-evidence
                      other-world-with-source-decision :episode/id "transplanted-episode"
                      :shortfall-observations [observation])
        attacker-beneficiary (assoc-in observation [:claim-set 0 :beneficiary]
                                       "attacker-wallet")
        changed-token (assoc observation :token :OTHER)
        changed-module (assoc observation :module/id :other-module)
        changed-amount (-> observation
                           (assoc-in [:claim-set 0 :amount]
                                     (inc (get-in observation [:claim-set 0 :amount])))
                           (update :obligation/amount inc))
        unknown-entitlement (assoc-in observation [:claim-set 0 :entitlement/id]
                                      "uncommitted-right")
        rejected (mapv #(pfev/build-shortfall-recovery-evidence
                         source-world :episode/id "misapplied-observation"
                         :shortfall-observations [%])
                       [attacker-beneficiary changed-token changed-module
                        changed-amount unknown-entitlement])]
    (is (= :provided-with-source-references
           (get-in source-artifact [:shortfall-observations :classification])))
    (is (= :invalid (get-in transplanted [:shortfall-observations :classification])))
    (is (every? #(= :invalid (get-in % [:shortfall-observations :classification]))
                rejected))))

(deftest left-censored-shortfall-reports-detection-duration-without-bounding-onset
  (let [world (shortfall-observation-world)
        decision (first (filter #(= :yield-withdraw-shared (:decision/source %))
                                (vals (:yield/partial-fill-decisions world))))
        rights (mapv (fn [row] {:entitlement/id (:key row)
                                :beneficiary (:key row)
                                :amount (:deferred row)})
                     (filter #(pos? (:deferred %))
                             (get-in decision [:evidence :allocation-rows])))
        base {:operation/id "test-execution" :decision/id (:decision/id decision)
              :module/id :test-mod :token :USDC :time-unit :seconds
              :observation/phase :post-settlement
              :obligation/amount (reduce + 0 (map :amount rights))
              :claim-set rights :source :chain-state :events-since-prior []}
        observations [(merge base {:observation/id "already-short"
                                   :observed-at 200
                                   :order {:block-number 20 :transaction-index 0 :log-index 0}
                                   :liquidity/available 0
                                   :source-ref "block:20" :source-state-root "root:20"})
                      (merge base {:observation/id "restored"
                                   :observed-at 209
                                   :order {:block-number 21 :transaction-index 0 :log-index 0}
                                   :liquidity/available 10000
                                   :events-since-prior
                                   [{:event/id "recover-10k" :kind :capital-recovery
                                     :amount 10000 :source-ref "event:recover-10k"
                                     :source-state-root "event-root:recover-10k"}]
                                   :source-ref "block:21" :source-state-root "root:21"})]
        artifact (pfev/build-shortfall-recovery-evidence
                  world :episode/id "left-censored-episode"
                  :short-lived-threshold 10
                  :shortfall-observations observations)
        timeline (get-in artifact [:shortfall-observations :timeline])
        closed (last timeline)]
    (is (true? (pfev/valid-shortfall-recovery-evidence? artifact)))
    (is (= [10000 0] (mapv :shortfall/amount timeline)))
    (is (true? (:episode/onset-left-censored? (first timeline))))
    (is (= 9 (:episode/detected-to-closed-duration closed)))
    (is (nil? (:episode/duration-upper-bound closed)))
    (is (nil? (:episode/short-lived? closed)))
    (is (= :onset-bound-unavailable
           (:episode/short-lived-classification closed)))))

(defn- tamper-decision
  "Set a field (at path) inside a decision of a world."
  [w d path value]
  (assoc-in w (into [:yield/partial-fill-decisions (:decision/id d)] path) value))

;; -- baseline / integrity ------------------------------------------------

(deftest verify-partial-fill-decisions-passes-on-real-world
  (let [result (pfev/verify-partial-fill-decisions (real-world))]
    (is (= :evaluated-pass (:classification result)))
    (is (= 1 (:decision-count result)))
    (is (some? (:root-recomputed result)))
    (is (true? (:decision-integrity? result)))
    (is (= :isolated-exact (:expected-fill-mode result)))
    (is (= 0 (:aggregate-drift result)))
    (is (empty? (:violations result)))))

(deftest verify-partial-fill-decisions-not-evaluated-when-none
  (let [result (pfev/verify-partial-fill-decisions {})]
    (is (= :not-evaluated (:classification result)))
    (is (false? (:decision-integrity? result)))))

(deftest verify-two-round-lineage-is-a-valid-bundle
  (testing "multi-round repetition of a claim is legitimate by default"
    (let [result (pfev/verify-partial-fill-decisions (two-decision-world))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 0 (:aggregate-drift result))))))

;; -- decision integrity (renamed from decidability) ----------------------

(deftest verify-tampered-decision-hash-fails-integrity
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:decision/hash] "sha256:forged")
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (false? (:decision-integrity? result)))
    (is (some #(= :invalid-decision-hash (:code %)) (:violations result)))))

(deftest verify-root-mismatch-fails
  (let [w (real-world)
        result (pfev/verify-partial-fill-decisions w
                                                   :committed-root "sha256:wrong")]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :root-mismatch (:code %)) (:violations result)))))

(deftest verify-scope-bound-root-passes
  (let [w (real-world)
        scope-root (pfev/partial-fill-decisions-root w {:scope {:case "C1" :run "r"}})
        result (pfev/verify-partial-fill-decisions w
                                                   :case-scope {:case "C1" :run "r"}
                                                   :committed-root scope-root)]
    (is (= :evaluated-pass (:classification result)))
    (is (true? (:decision-integrity? result)))))

(deftest verify-replayed-under-other-scope-fails
  (testing "a decision bundle committed under one case/run scope cannot be
            replayed under another"
    (let [w (real-world)
          scope-a (pfev/partial-fill-decisions-root w {:scope {:case "C1" :run "r"}})
          result (pfev/verify-partial-fill-decisions w
                                                     :case-scope {:case "C2" :run "r2"}
                                                     :committed-root scope-a)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :scope-root-mismatch (:code %)) (:violations result))))))

;; -- membership binding ---------------------------------------------------

(deftest verify-missing-claim-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-claims #{"alice" "bob"})]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(and (= :missing-claim (:code %))
                    (= ["bob"] (:claims %)))
              (:violations result)))))

(deftest verify-unexpected-claim-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-claims #{})]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :unexpected-claim (:code %)) (:violations result)))))

(deftest verify-decision-count-mismatch-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-count 2)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :decision-count-mismatch (:code %)) (:violations result)))))

(deftest verify-duplicate-claim-fails-under-unique-contract
  (testing "a single-settlement membership contract rejects a repeated claim"
    (let [result (pfev/verify-partial-fill-decisions (two-decision-world)
                                                     :unique-claims? true)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :duplicate-claim (:code %)) (:violations result))))))

;; -- derived amounts, not trusted redundant fields -----------------------

(deftest verify-negative-amount-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:filled (claim-key d)] -1)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :negative-amount (:code %)) (:violations result)))))

(deftest verify-non-integral-amount-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:requested (claim-key d)] 100.5)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :non-integral-amount (:code %)) (:violations result)))))

(deftest verify-bounds-violation-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:deferred (claim-key d)] 200)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :bounds-violation (:code %)) (:violations result)))))

(deftest verify-inconsistent-shortage-fails
  (testing "a supplied positive shortage is not authoritative"
    (let [w (real-world)
          d (latest-decision w)
          tampered (tamper-decision w d [:evidence :shortage] 999)
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :shortage-inconsistent (:code %)) (:violations result))))))

(deftest verify-over-capacity-fill-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:evidence :available-liquidity] 10)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :over-capacity-fill (:code %)) (:violations result)))))

;; -- reconciliation and expected-fill -------------------------------------

(deftest verify-per-claim-inexact-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (assoc-in w
                           [:yield/partial-fill-decisions (:decision/id d)
                            :evidence :allocation-rows 0 :filled] 28)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :per-claim-inexact (:code %)) (:violations result)))))

(deftest verify-aggregate-drift-fails
  (testing "one-unit errors on many claims cannot accumulate undetected"
    (let [w (three-claim-world)
          d (latest-decision w)
          tampered (reduce (fn [w k]
                             (update-in w
                                        [:yield/partial-fill-decisions
                                         (:decision/id d) :filled k]
                                        inc))
                           w
                           (keys (:filled d)))
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :aggregate-drift (:code %)) (:violations result))))))

(deftest verify-underfill-despite-capacity-fails
  (testing "a reconciled-but-deliberately-underfilled decision despite capacity"
    (let [w (full-fill-world)
          d (latest-decision w)
          k (claim-key d)
          tampered (-> w
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :filled k] 30)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :deferred k] 70)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :evidence :shortage] 70))
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :expected-fill-mismatch (:code %)) (:violations result))))))

(deftest verify-classification-mismatch-fails
  (testing "derived classification must agree with settlement-mode"
    (let [w (real-world)
          d (latest-decision w)
          tampered (tamper-decision w d [:settlement-mode] :full-fill)
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :classification-mismatch (:code %)) (:violations result)))))
  (testing "zero deferred amounts do not conceal a haircut-only partial fill as a full fill"
    (let [w (full-fill-world)
          d (latest-decision w)
          k (claim-key d)
          tampered (-> w
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :filled k] 80)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :deferred k] 0)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :haircut k] 20)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :settlement-mode] :full-fill))
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(and (= :classification-mismatch (:code %))
                      (= :partial-fill (:derived %))
                      (= :full-fill (:settlement-mode %)))
                (:violations result))))))

;; ── partial-fill verification report file-artifact ───────────────────────

(deftest partial-fill-verification-report-roundtrip
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (= "partial-fill-decisions-verification.v1" (:schema-version r)))
    (is (= :partial-fill-decisions-verification (:artifact/kind r)))
    (is (= :evaluated-pass (:classification r)))
    (is (string? (:report/hash r)))
    (is (true? (pfev/valid-partial-fill-verification-report? r)))))

(deftest partial-fill-verification-report-tamper-classification
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :classification :evaluated-fail))))))

(deftest partial-fill-verification-report-tamper-hash
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :report/hash "sha256:forged"))))))

(deftest partial-fill-verification-report-wrong-schema
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :schema-version "wrong.v9"))))))

(deftest partial-fill-verification-report-missing-preimage
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (dissoc r :report/preimage))))))

;; ── partial-fill-decisions-summary file-artifact ─────────────────────────

(deftest partial-fill-decisions-summary-roundtrip
  (let [r (pfev/build-partial-fill-decisions-summary (real-world))]
    (is (= "partial-fill-decisions-summary.v1" (:schema-version r)))
    (is (= :partial-fill-decisions-summary (:artifact/kind r)))
    (is (= 1 (:decision-count r)))
    (is (= 100 (get-in r [:totals :owed])))
    (is (= 30 (get-in r [:totals :filled])))
    (is (= 70 (get-in r [:totals :deferred])))
    (is (= {:partial-fill 1} (:outcome-counts r)))
    (is (= {:numerator 3 :denominator 10} (get-in r [:fill-ratio :min])))
    (is (true? (get-in r [:integrity :decision-integrity?])))
    (is (= :evaluated-pass (:classification r)))
    (is (empty? (get-in r [:failures :invalid-decision-hashes])))
    (is (true? (pfev/valid-partial-fill-decisions-summary? r)))))

(deftest partial-fill-decisions-summary-tamper-detected
  (let [r (pfev/build-partial-fill-decisions-summary (real-world))]
    (is (false? (pfev/valid-partial-fill-decisions-summary?
                 (assoc r :decision-count 99))))
    (is (false? (pfev/valid-partial-fill-decisions-summary?
                 (assoc r :artifact/hash "sha256:forged"))))
    (is (false? (pfev/valid-partial-fill-decisions-summary?
                 (assoc r :schema-version "wrong.v9"))))
    (is (false? (pfev/valid-partial-fill-decisions-summary?
                 (dissoc r :artifact/preimage))))))

(deftest partial-fill-decisions-summary-empty-is-not-evaluated
  (let [r (pfev/build-partial-fill-decisions-summary {})]
    (is (= 0 (:decision-count r)))
    (is (= :not-evaluated (:classification r)))
    (is (nil? (get-in r [:fill-ratio :median])))
    (is (true? (pfev/valid-partial-fill-decisions-summary? r)))))
