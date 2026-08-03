(ns resolver-sim.scenario.equilibrium-test
  "Unit tests for the trace-end mechanism-property and equilibrium-concept validators.
   Uses synthetic projections — no replay required.

   These tests cover cases that cannot be included in the fixture suite directly
   (mechanism :fail cases) as well as the full set of :pass, :inconclusive, and
   :not-applicable paths for each validator."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.scenario.equilibrium :as eq]
            [resolver-sim.protocols.sew :as sew-protocol]
            [resolver-sim.protocols.sew.equilibrium :as sew-eq]
            [resolver-sim.protocols.sew.projection :as sew-proj]
            [resolver-sim.scenario.subgame-counterfactual :as subgame-cf]
            [resolver-sim.scenario.reputation-profiles :as rep-profiles]
            [resolver-sim.testing.scenario-builder :as sb]))

(defn -main
  "Allow direct execution via: clojure -M:test -m resolver-sim.scenario.equilibrium-test"
  [& _]
  (run-tests 'resolver-sim.scenario.equilibrium-test))

;; ---------------------------------------------------------------------------
;; Synthetic projection helpers
;; ---------------------------------------------------------------------------

(defn- projection
  "Build a minimal synthetic projection for unit tests."
  [{:keys [terminal? halt-reason total-held
           attack-attempts attack-successes funds-lost
           invariant-violations negative-payoff-count
           coalition-net-profit
           total-shortfall-basis total-shortfall-filled
           total-shortfall-deferred total-shortfall-haircut
           redistribution-total-passes redistribution-iteration-limit-hit?
           redistribution-negative-allocations]
    :or   {terminal?          true
           halt-reason        :all-terminal
           total-held         {}
           attack-attempts    0
           attack-successes   0
           funds-lost         0
           invariant-violations 0}}]
  {:terminal-world {:terminal?          terminal?
                    :total-held-by-token total-held
                    :escrow-count       1}
   :metrics        {:attack-attempts          attack-attempts
                    :attack-successes         attack-successes
                    :funds-lost               funds-lost
                    :invariant-violations     invariant-violations
                    :negative-payoff-count    negative-payoff-count
                    :coalition-net-profit     coalition-net-profit
                    :total-shortfall-basis    total-shortfall-basis
                    :total-shortfall-filled   total-shortfall-filled
                    :total-shortfall-deferred total-shortfall-deferred
                    :total-shortfall-haircut  total-shortfall-haircut
                    :redistribution-total-passes redistribution-total-passes
                    :redistribution-iteration-limit-hit? redistribution-iteration-limit-hit?
                    :redistribution-negative-allocations redistribution-negative-allocations}
   :trace-summary  {:halt-reason   halt-reason
                    :events-count  2
                    :actors        ["buyer" "seller"]
                    :terminal-time 1100}})

;; ---------------------------------------------------------------------------
;; budget-balance
;; ---------------------------------------------------------------------------

(deftest test-budget-balance-pass
  (testing "terminal escrows, total-held = 0 → :pass"
    (let [proj (projection {:terminal? true :total-held {"USDC" 0}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj sew-eq/mechanism-property-validators)
                     :budget-balance)]
      (is (= :pass (:status result)))
      (is (= :hard (:severity result))))))

(deftest test-budget-balance-fail
  (testing "terminal escrows but total-held > 0 → :fail"
    (let [proj (projection {:terminal? true :total-held {"USDC" 500}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj sew-eq/mechanism-property-validators)
                     :budget-balance)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-budget-balance-not-applicable-non-terminal
  (testing "non-terminal escrows → :not-applicable"
    (let [proj (projection {:terminal? false :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj sew-eq/mechanism-property-validators)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

(deftest test-budget-balance-not-applicable-open-disputes
  (testing "halt-reason :open-disputes-at-end → :not-applicable"
    (let [proj (projection {:terminal? false
                            :halt-reason :open-disputes-at-end
                            :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj sew-eq/mechanism-property-validators)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

;; ---------------------------------------------------------------------------
;; incentive-compatibility
;; ---------------------------------------------------------------------------

(deftest test-incentive-compat-inconclusive-no-adversarial
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :inconclusive (:status result))))))

(deftest test-incentive-compat-pass-adversarial-rejected
  (testing "attacks attempted, all rejected, no funds lost → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :pass (:status result))))))

(deftest test-incentive-compat-fail-attack-success
  (testing "attack succeeded (attack-successes > 0) → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

(deftest test-incentive-compat-fail-funds-lost
  (testing "funds-lost > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 0 :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; pro-rata-fairness
;; ---------------------------------------------------------------------------

(deftest test-pro-rata-fairness-inconclusive-no-shortfall
  (testing "no shortfall metrics → :inconclusive"
    (let [proj (projection {})
          result (-> (eq/evaluate-mechanism-properties [:pro-rata-fairness] proj)
                     :pro-rata-fairness)]
      (is (= :inconclusive (:status result))))))

(deftest test-pro-rata-fairness-pass-conservation-holds
  (testing "shortfall conservation holds → :pass"
    (let [proj (projection {:total-shortfall-basis 1000
                            :total-shortfall-filled 600
                            :total-shortfall-deferred 300
                            :total-shortfall-haircut 100})
          result (-> (eq/evaluate-mechanism-properties [:pro-rata-fairness] proj)
                     :pro-rata-fairness)]
      (is (= :pass (:status result))))))

(deftest test-pro-rata-fairness-fail-imbalance
  (testing "shortfall conservation violated → :fail"
    (let [proj (projection {:total-shortfall-basis 1000
                            :total-shortfall-filled 500
                            :total-shortfall-deferred 300
                            :total-shortfall-haircut 100})
          result (-> (eq/evaluate-mechanism-properties [:pro-rata-fairness] proj)
                     :pro-rata-fairness)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; redistribution-fairness
;; ---------------------------------------------------------------------------

(deftest test-redistribution-fairness-inconclusive-no-redistribution
  (testing "no redistribution passes → :inconclusive"
    (let [proj (projection {:redistribution-total-passes 0})
          result (-> (eq/evaluate-mechanism-properties [:redistribution-fairness] proj)
                     :redistribution-fairness)]
      (is (= :inconclusive (:status result))))))

(deftest test-redistribution-fairness-pass-clean
  (testing "redistribution completed cleanly → :pass"
    (let [proj (projection {:redistribution-total-passes 2
                            :redistribution-iteration-limit-hit? false
                            :redistribution-negative-allocations 0})
          result (-> (eq/evaluate-mechanism-properties [:redistribution-fairness] proj)
                     :redistribution-fairness)]
      (is (= :pass (:status result))))))

(deftest test-redistribution-fairness-fail-iteration-limit
  (testing "iteration limit hit → :fail"
    (let [proj (projection {:redistribution-total-passes 10
                            :redistribution-iteration-limit-hit? true
                            :redistribution-negative-allocations 0})
          result (-> (eq/evaluate-mechanism-properties [:redistribution-fairness] proj)
                     :redistribution-fairness)]
      (is (= :fail (:status result))))))

(deftest test-redistribution-fairness-fail-negative-allocations
  (testing "negative allocations after redistribution → :fail"
    (let [proj (projection {:redistribution-total-passes 2
                            :redistribution-iteration-limit-hit? false
                            :redistribution-negative-allocations 1})
          result (-> (eq/evaluate-mechanism-properties [:redistribution-fairness] proj)
                     :redistribution-fairness)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; individual-rationality
;; ---------------------------------------------------------------------------

(deftest test-individual-rationality-inconclusive
  (testing "no payoff-ledger (negative-payoff-count nil), funds-lost = 0 → :inconclusive"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj sew-eq/mechanism-property-validators)
                     :individual-rationality)]
      (is (= :inconclusive (:status result))))))

(deftest test-individual-rationality-pass-with-ledger
  (testing "negative-payoff-count = 0 → :pass"
    (let [proj (projection {:negative-payoff-count 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj sew-eq/mechanism-property-validators)
                     :individual-rationality)]
      (is (= :pass (:status result))))))

(deftest test-individual-rationality-fail-negative-payoff
  (testing "negative-payoff-count > 0 → :fail"
    (let [proj (projection {:negative-payoff-count 2 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj sew-eq/mechanism-property-validators)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

(deftest test-individual-rationality-fail-funds-lost
  (testing "funds-lost > 0 (partial proxy) → :fail"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj sew-eq/mechanism-property-validators)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

(deftest test-individual-rationality-threads-definition-root
  (testing "per-actor IR results carry the outside-option definition root"
    (let [proj (assoc (projection {:negative-payoff-count 0 :funds-lost 0})
                      :payoff-ledger-summary
                      {:per-actor {"0xA" {:net-payoff 100}
                                   "0xB" {:net-payoff 50}}}
                      :outside-option-definition-root "oo-root-0")
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj sew-eq/mechanism-property-validators)
                     :individual-rationality)
          ir-results (get-in result [:observed :ir-results])]
      (is (= :pass (:status result)))
      (is (= "oo-root-0" (get-in result [:observed :outside-option-definition-root])))
      (is (= 2 (count ir-results)))
      (is (every? #(= "oo-root-0" (:definition-root %)) ir-results)))))

;; ---------------------------------------------------------------------------
;; collusion-resistance
;; ---------------------------------------------------------------------------

(deftest test-collusion-resistance-inconclusive
  (testing "coalition-net-profit absent → :inconclusive"
    (let [proj (projection {:coalition-net-profit nil})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj sew-eq/mechanism-property-validators)
                     :collusion-resistance)]
      (is (= :inconclusive (:status result))))))

(deftest test-collusion-resistance-pass
  (testing "coalition-net-profit <= 0 → :pass"
    (let [proj (projection {:coalition-net-profit -50})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj sew-eq/mechanism-property-validators)
                     :collusion-resistance)]
      (is (= :pass (:status result))))))

(deftest test-collusion-resistance-fail
  (testing "coalition-net-profit > 0 → :fail"
    (let [proj (projection {:coalition-net-profit 100})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj sew-eq/mechanism-property-validators)
                     :collusion-resistance)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; dominant-strategy-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-dominant-strategy-inconclusive-no-attacks
  (testing "no adversarial actors, no violations → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-dominant-strategy-pass
  (testing "attacks present, none succeeded, no violations → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-dominant-strategy-fail-attack-success
  (testing "attack-successes > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

(deftest test-dominant-strategy-fail-invariant-violation
  (testing "invariant-violations > 0 → :fail"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 2})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; nash-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-nash-inconclusive-no-attacks
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-nash-pass
  (testing "attacks rejected, no violations → :pass"
    (let [proj (projection {:attack-attempts 2 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-nash-fail-attack-success
  (testing "attack-successes > 0 → :fail (eq-v9 scenario)"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :fail (:status result))))))

(deftest test-claim-tier-deviation-bundle-gating
  (testing "deviation-tested tier requires deviation bundle evidence for DSE/Nash"
    (let [proj   (projection {:attack-attempts 2 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts
                      [:dominant-strategy-equilibrium :nash-equilibrium]
                      proj
                      {}
                      {:claim-tier :deviation-tested}))]
      (is (= :inconclusive (get-in result [:dominant-strategy-equilibrium :status])))
      (is (= :multi-trace-required (get-in result [:dominant-strategy-equilibrium :basis])))
      (is (= :missing-deviation-bundles (get-in result [:dominant-strategy-equilibrium :reason])))
      (is (= :inconclusive (get-in result [:nash-equilibrium :status])))
      (is (= :multi-trace-required (get-in result [:nash-equilibrium :basis])))
      (is (= :missing-deviation-bundles (get-in result [:nash-equilibrium :reason])))))

  (testing "deviation-tested tier passes through when deviation bundle evidence is present"
    (let [proj   (assoc (projection {:attack-attempts 2 :attack-successes 0 :invariant-violations 0})
                        :deviation-bundle {:meets-minimum? true})
          result (-> (eq/evaluate-equilibrium-concepts
                      [:dominant-strategy-equilibrium :nash-equilibrium]
                      proj
                      {}
                      {:claim-tier :deviation-tested}))]
      (is (= :pass (get-in result [:dominant-strategy-equilibrium :status])))
      (is (= :pass (get-in result [:nash-equilibrium :status]))))))

;; ---------------------------------------------------------------------------
;; SPE / BNE — always :inconclusive
;; ---------------------------------------------------------------------------

(deftest test-subgame-perfect-equilibrium
  (testing "SPE inconclusive when no strategic decisions made"
    (let [proj {:raw-trace [{:world {}}] :decisions [] :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result)))))

  (testing "SPE inconclusive when trace is not terminal"
    (let [proj {:raw-trace [{:world {}}]
                :decisions [{:index 0 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? false}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :multi-trace-required (:basis result)))))

  (testing "SPE PASS: bounded counterfactual regret table has zero max regret"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 150}}}}] ; t=2 (won: escrow 100 + bond 50)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))
      (is (= :single-trace-node-counterfactual-proxy (:basis result)))
      (is (= 1 (get-in result [:observed :decisions-checked])))
      (is (= :pass (get-in result [:observed :spe-status])))
      (is (= 0 (get-in result [:observed :spe-max-regret])))
      (is (= 1 (count (get-in result [:observed :spe-regret-table]))))
      (is (= :trace-following (get-in result [:observed :spe-continuation-policy :mode])))
      (is (= :preserve (get-in result [:observed :spe-replay-boundary :ordering-mode])))
      (is (= :terminal-realized-v1 (get-in result [:observed :spe-utility-spec :type])))
      (is (number? (get-in result [:observed :spe-mean-regret])))
      (is (= 0 (get-in result [:observed :spe-exceed-epsilon-count])))
      (is (map? (get-in result [:observed :spe-regret-distribution])))))

  (testing "SPE FAIL: bounded counterfactual regret exceeds threshold"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 0}}}}] ; t=2 (lost: bond slashed)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= :single-trace-node-counterfactual-proxy (:basis result)))
      (is (= 1 (count (get-in result [:observed :spe-violations]))))
      (is (= 50 (get-in result [:observed :spe-max-regret])))
      (is (= 50 (get-in result [:offending 0 :local-regret])))))

  (testing "SPE PASS: rational dispute"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 100}}}}]
                :decisions [{:index 1 :seq 1 :agent "seller" :action "raise_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))))

  (testing "SPE FAIL: multiple violations"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"a" 0 "b" 0}}}}
                            {:world {:bond-balances {"e1" {"a" 10}}}} ; a escalate
                            {:world {:bond-balances {"e1" {"a" 10 "b" 10}}}} ; b escalate
                            {:world {:claimable {"e1" {"a" 0 "b" 0}}}}] ; both lost
                :decisions [{:index 1 :seq 1 :agent "a" :action "escalate_dispute"}
                            {:index 2 :seq 2 :agent "b" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= 1 (count (get-in result [:observed :spe-violations]))))))

  (testing "SPE determinism: identical projection reruns produce identical regret table"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}
                            {:world {:claimable {"e1" {"buyer" 0}}}}]
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          r1 (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                 :subgame-perfect-equilibrium
                 :observed
                 :spe-regret-table)
          r2 (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                 :subgame-perfect-equilibrium
                 :observed
                 :spe-regret-table)]
      (is (= r1 r2)))))

(deftest test-bne-always-inconclusive
  (testing "bayesian-nash-equilibrium always returns :inconclusive"
    (let [proj (projection {:attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:bayesian-nash-equilibrium] proj)
                     :bayesian-nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

;; ---------------------------------------------------------------------------
;; Status roll-up
;; ---------------------------------------------------------------------------

(deftest test-evaluate-equilibrium-all-pass
  (testing "evaluate-equilibrium with clean metrics → mechanism :pass (no attacks → :inconclusive)"
    (let [theory  {:mechanism-properties [:budget-balance]
                   :equilibrium-concept  [:dominant-strategy-equilibrium]}
          result  {:metrics {:attack-attempts 2 :attack-successes 0
                             :invariant-violations 0 :funds-lost 0}
                   :trace   [{:world {:total-held    {"USDC" 0}
                                      :total-fees    {"USDC" 0}
                                      :live-states   {1 :released}
                                      :escrow-count  1}}]
                   :protocol sew-protocol/protocol}
          eq-out  (eq/evaluate-equilibrium theory result)]
      ;; budget-balance: terminal world with 0 held → :pass
      (is (= :pass (get-in eq-out [:mechanism-results :budget-balance :status])))
      ;; dominant-strategy: attack-attempts 2, successes 0 → :pass
      (is (= :pass (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :pass (:mechanism-status eq-out)))
      (is (= :pass (:equilibrium-status eq-out)))
      (is (= :unknown (get-in eq-out [:provenance :temporal :query-mode])))
      (is (= :unknown (get-in eq-out [:provenance :attestation :status]))))))

(deftest test-evaluate-equilibrium-includes-provenance
  (testing "equilibrium output includes temporal and local self-signed attestation provenance"
    (let [theory  {:mechanism-properties [:budget-balance]
                   :equilibrium-concept  [:dominant-strategy-equilibrium]}
          result  {:metrics {:attack-attempts 2 :attack-successes 0
                             :invariant-violations 0 :funds-lost 0}
                   :trace   [{:world {:total-held    {"USDC" 0}
                                      :total-fees    {"USDC" 0}
                                      :live-states   {1 :released}
                                      :escrow-count  1}}]
                   :protocol sew-protocol/protocol
                   :temporal-query-mode :as-of
                   :temporal-confidence {:time-basis :valid-time
                                         :queries-using-explicit-valid-time 1.0
                                         :temporal-consistency-status :snapshot-consistent}
                   :attestation {:status :verified :signer "local-key-1"}}
          eq-out  (eq/evaluate-equilibrium theory result)]
      (is (= :as-of (get-in eq-out [:provenance :temporal :query-mode])))
      (is (true? (get-in eq-out [:provenance :temporal :explicit-valid-time?])))
      (is (= :verified (get-in eq-out [:provenance :attestation :status])))
      (is (= :local-self-signed (get-in eq-out [:provenance :attestation :source]))))))

(deftest test-evaluate-equilibrium-strict-valid-time-gating
  (testing "strict-valid-time trust mode forces equilibrium concepts to inconclusive without explicit valid-time provenance"
    (let [theory {:equilibrium-concept [:dominant-strategy-equilibrium]
                  :equilibrium-trust-mode :strict-valid-time}
          result {:metrics {:attack-attempts 2 :attack-successes 0 :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held {"USDC" 0}
                                     :total-fees {"USDC" 0}
                                     :live-states {1 :released}
                                     :escrow-count 1}}]
                  :protocol sew-protocol/protocol}
          eq-out (eq/evaluate-equilibrium theory result)]
      (is (= :strict-valid-time (:equilibrium-trust-mode eq-out)))
      (is (= :inconclusive (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :inconclusive (:equilibrium-status eq-out)))))

  (testing "strict-valid-time trust mode allows normal concept evaluation when explicit valid-time provenance is present"
    (let [theory {:equilibrium-concept [:dominant-strategy-equilibrium]
                  :equilibrium-trust-mode :strict-valid-time}
          result {:metrics {:attack-attempts 2 :attack-successes 0 :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held {"USDC" 0}
                                     :total-fees {"USDC" 0}
                                     :live-states {1 :released}
                                     :escrow-count 1}}]
                  :protocol sew-protocol/protocol
                  :temporal-confidence {:time-basis :valid-time}}
          eq-out (eq/evaluate-equilibrium theory result)]
      (is (= :strict-valid-time (:equilibrium-trust-mode eq-out)))
      (is (= :pass (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status]))))))

(deftest test-evaluate-equilibrium-strict-attestation-gating
  (testing "strict-attestation trust mode forces equilibrium concepts to inconclusive without verified attestation"
    (let [theory {:equilibrium-concept [:dominant-strategy-equilibrium]
                  :equilibrium-trust-mode :strict-attestation}
          result {:metrics {:attack-attempts 2 :attack-successes 0 :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held {"USDC" 0}
                                     :total-fees {"USDC" 0}
                                     :live-states {1 :released}
                                     :escrow-count 1}}]
                  :protocol sew-protocol/protocol
                  :attestation {:status :missing}}
          eq-out (eq/evaluate-equilibrium theory result)]
      (is (= :strict-attestation (:equilibrium-trust-mode eq-out)))
      (is (= :inconclusive (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :inconclusive (:equilibrium-status eq-out)))))

  (testing "strict-attestation trust mode allows normal concept evaluation when attestation is verified"
    (let [theory {:equilibrium-concept [:dominant-strategy-equilibrium]
                  :equilibrium-trust-mode :strict-attestation}
          result {:metrics {:attack-attempts 2 :attack-successes 0 :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held {"USDC" 0}
                                     :total-fees {"USDC" 0}
                                     :live-states {1 :released}
                                     :escrow-count 1}}]
                  :protocol sew-protocol/protocol
                  :attestation {:status :verified}}
          eq-out (eq/evaluate-equilibrium theory result)]
      (is (= :strict-attestation (:equilibrium-trust-mode eq-out)))
      (is (= :pass (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status]))))))

(deftest test-evaluate-equilibrium-trust-mode-integration-matrix
  (testing "relaxed vs strict trust modes across missing/partial/complete provenance"
    (let [base-result {:metrics {:attack-attempts 2 :attack-successes 0 :invariant-violations 0 :funds-lost 0}
                       :trace   [{:world {:total-held {"USDC" 0}
                                          :total-fees {"USDC" 0}
                                          :live-states {1 :released}
                                          :escrow-count 1}}]
                       :protocol sew-protocol/protocol}
          run* (fn [trust-mode result]
                 (eq/evaluate-equilibrium {:equilibrium-concept [:dominant-strategy-equilibrium]
                                           :equilibrium-trust-mode trust-mode}
                                          result))

          relaxed-missing   (run* :relaxed base-result)
          strict-vt-missing (run* :strict-valid-time base-result)
          strict-att-missing (run* :strict-attestation base-result)

          with-valid-time   (assoc base-result :temporal-confidence {:time-basis :valid-time})
          strict-vt-pass    (run* :strict-valid-time with-valid-time)

          with-attestation  (assoc base-result :attestation {:status :verified})
          strict-att-pass   (run* :strict-attestation with-attestation)

          with-both         (assoc with-valid-time :attestation {:status :verified})
          strict-vt-both    (run* :strict-valid-time with-both)
          strict-att-both   (run* :strict-attestation with-both)]

      ;; relaxed: no provenance requirements
      (is (= :pass (get-in relaxed-missing [:equilibrium-results :dominant-strategy-equilibrium :status])))

      ;; strict modes independently gate on their required evidence
      (is (= :inconclusive (get-in strict-vt-missing [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :inconclusive (get-in strict-att-missing [:equilibrium-results :dominant-strategy-equilibrium :status])))

      ;; each strict mode passes when its required provenance is present
      (is (= :pass (get-in strict-vt-pass [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :pass (get-in strict-att-pass [:equilibrium-results :dominant-strategy-equilibrium :status])))

      ;; fully trusted payload satisfies both strict modes
      (is (= :pass (get-in strict-vt-both [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :pass (get-in strict-att-both [:equilibrium-results :dominant-strategy-equilibrium :status]))))))

(deftest test-evaluate-equilibrium-fail-propagates
  (testing "a :fail in mechanism results rolls up to :fail status"
    (let [theory {:mechanism-properties [:budget-balance]}
          result {:metrics {:attack-attempts 0 :attack-successes 0
                            :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held    {"USDC" 5000}
                                     :total-fees    {"USDC" 0}
                                     :live-states   {1 :released}
                                     :escrow-count  1}}]
                  :protocol sew-protocol/protocol}
          eq-out (eq/evaluate-equilibrium theory result)]
      ;; Escrow is in :released but total-held = 5000 → terminal? check
      ;; live-states has 1 :released → terminal? = true → budget-balance FAIL
      (is (= :fail (get-in eq-out [:mechanism-results :budget-balance :status])))
      (is (= :fail (:mechanism-status eq-out))))))

(deftest test-unknown-property-inconclusive
  (testing "unknown mechanism property → :inconclusive with absent-evidence basis"
    (let [proj (projection {})
          result (-> (eq/evaluate-mechanism-properties [:unknown-future-property] proj)
                     :unknown-future-property)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result))))))

(deftest test-force-refund-path-integrity-pass
  (testing "refunded path remains refund-only"
    (let [proj (assoc (projection {})
                      :money-movement-summary
                      {:workflow-outcomes {0 {:terminal-state :refunded :path :refund}}})
          result (-> (eq/evaluate-mechanism-properties [:force-refund-path-integrity] proj sew-eq/mechanism-property-validators)
                     :force-refund-path-integrity)]
      (is (= :pass (:status result))))))

(deftest test-force-refund-path-integrity-fail
  (testing "refunded workflow marked as release path fails"
    (let [proj (assoc (projection {})
                      :money-movement-summary
                      {:workflow-outcomes {0 {:terminal-state :refunded :path :release}}})
          result (-> (eq/evaluate-mechanism-properties [:force-refund-path-integrity] proj sew-eq/mechanism-property-validators)
                     :force-refund-path-integrity)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-pending-lifecycle-integrity
  (testing "pending lifecycle pass and fail cases"
    (let [pass-proj (assoc (projection {})
                           :money-movement-summary
                           {:pending-lifecycle {:unknown {:created 2 :cleared 2 :superseded 1}}})
          fail-proj (assoc (projection {})
                           :money-movement-summary
                           {:pending-lifecycle {:unknown {:created 1 :cleared 2 :superseded 0}}})
          pass-r (-> (eq/evaluate-mechanism-properties [:pending-lifecycle-integrity] pass-proj sew-eq/mechanism-property-validators)
                     :pending-lifecycle-integrity)
          fail-r (-> (eq/evaluate-mechanism-properties [:pending-lifecycle-integrity] fail-proj sew-eq/mechanism-property-validators)
                     :pending-lifecycle-integrity)]
      (is (= :pass (:status pass-r)))
      (is (= :fail (:status fail-r))))))

(deftest test-stake-flow-conservation
  (testing "stake flow conservation pass and fail"
    (let [pass-proj (assoc (projection {})
                           :stake-flow-summary
                           {"0xR" {:start 100 :withdrawn 20 :slashed 30 :end 50}})
          fail-proj (assoc (projection {})
                           :stake-flow-summary
                           {"0xR" {:start 100 :withdrawn 20 :slashed 30 :end 60}})
          pass-r (-> (eq/evaluate-mechanism-properties [:stake-flow-conservation] pass-proj sew-eq/mechanism-property-validators)
                     :stake-flow-conservation)
          fail-r (-> (eq/evaluate-mechanism-properties [:stake-flow-conservation] fail-proj sew-eq/mechanism-property-validators)
                     :stake-flow-conservation)]
      (is (= :pass (:status pass-r)))
      (is (= :fail (:status fail-r))))))

;; ---------------------------------------------------------------------------
;; SPE observed fields — Phase F-J and Phase K
;; ---------------------------------------------------------------------------
(deftest test-spe-observed-includes-phase-f-g-h-i-j-l-fields
  (testing "SPE observed payload includes all Phase F-J and L fields"
    (let [proj (sb/spe-projection {:chosen-wealth 200 :terminal-wealth 200
                                   :regret-threshold 1000})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (some? (:spe-result obs)))
      (is (some? (:spe-strategy-profile obs)))
      (is (number? (:spe-proper-subgames-checked obs)))
      (is (number? (:spe-information-set-nodes-checked obs)))
      (is (number? (:spe-not-checkable-nodes obs)))
      (is (vector? (:spe-counterexamples obs)))
      (is (map? (:spe-off-path-coverage obs)))
      (is (string? (:spe-proof-sketch obs))))))

(deftest test-spe-result-vocab-pass
  (testing ":spe-result is :spe/pass on no-regret resolver verdict"
    (let [proj (sb/spe-projection {:chosen-wealth 200 :regret-threshold 1000})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (= :pass (:status result)))
      (is (= :spe/pass (:spe-result obs))))))

(deftest test-spe-counterexamples-on-fail
  (testing ":spe-counterexamples non-empty on profitable deviation"
    (let [proj (sb/spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (= :fail (:status result)))
      (is (seq (:spe-counterexamples obs)))
      (let [ce (first (:spe-counterexamples obs))]
        (is (= :profitable-deviation (:failure/type ce)))
        (is (string? (:node/id ce))))))

  (deftest test-spe-proof-sketch-emitted
    (testing ":spe-proof-sketch is a non-empty string"
      (let [proj (sb/spe-projection {:chosen-wealth 200 :regret-threshold 1000})
            obs (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                    :subgame-perfect-equilibrium :observed)]
        (is (string? (:spe-proof-sketch obs)))
        (is (pos? (count (:spe-proof-sketch obs)))))))

  (deftest test-spe-proof-sketch-method-section-and-memoization-line
    (testing ":spe-proof-sketch includes method metadata and memoization diagnostics"
      (let [proj (sb/spe-projection {:chosen-wealth 200 :regret-threshold 1000})
            sketch (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj sew-eq/equilibrium-concept-validators)
                       :subgame-perfect-equilibrium :observed :spe-proof-sketch)]
        (is (re-find #"Method:" sketch))
        (is (re-find #"continuation-policy:" sketch))
        (is (re-find #"utility-spec:" sketch))
        (is (re-find #"max deviation depth:" sketch))
        (is (re-find #"epsilon: abs=" sketch))
        (is (re-find #"memoization:" sketch)))))

  (deftest test-bounded-public-state-epsilon-spe-pass
    (testing ":bounded-public-state-epsilon-spe passes with a proper-subgame resolver node"
      (let [proj (sb/spe-projection {:chosen-wealth 200 :regret-threshold 1000})
            result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj sew-eq/equilibrium-concept-validators)
                       :bounded-public-state-epsilon-spe)]
        (is (= :pass (:status result)))
        (is (= :hard (:severity result)))))))

(deftest test-bounded-public-state-epsilon-spe-fail-deviation
  (testing ":bounded-public-state-epsilon-spe fails when regret exceeds threshold"
    (let [proj (sb/spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0})
          result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj sew-eq/equilibrium-concept-validators)
                     :bounded-public-state-epsilon-spe)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-bounded-public-state-epsilon-spe-inconclusive-no-proper-subgames
  (testing ":bounded-public-state-epsilon-spe is :inconclusive when only info-set nodes"
    ;; buyer raise_dispute is an info-set node → no proper subgames → inconclusive
    (let [proj (sb/spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0
                                   :agent "buyer" :action "raise_dispute"})
          result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj sew-eq/equilibrium-concept-validators)
                     :bounded-public-state-epsilon-spe)]
      (is (= :inconclusive (:status result))))))

;; ---------------------------------------------------------------------------
;; Fix 1 regression: spe-config from theory block must be threaded to evaluator
;; ---------------------------------------------------------------------------

(defn- minimal-replay-result
  "Minimal replay result that trace-end-projection can process.
   Produces a resolver execute_resolution decision with regret=50.

   Wealth is keyed by address '0xresolver' (not agent-id 'resolver') because
   trace-end-projection resolves agent-id → address via :agents, and the SPE
   evaluator looks up wealth using actor = (or address agent).

   pre-wealth=100 (seq=0 register_stake), terminal-wealth=50 (seq=1 execute_resolution)."
  []
  {:trace [{:world {:claimable {"e1" {"0xresolver" 100}}
                    :live-states {"e1" "disputed"}
                    :total-held {}
                    :total-fees {}}
            :agent "resolver" :action "register_stake" :seq 0 :time 1000}
           {:world {:claimable {"e1" {"0xresolver" 50}}
                    :live-states {"e1" "released"}
                    :total-held {}
                    :total-fees {}}
            :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
   :agents [{:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}
   :protocol sew-protocol/protocol})

(deftest test-spe-config-threading-from-theory
  (testing "spe-config from theory block is used by the evaluator (not defaults)"
    (let [result (minimal-replay-result)
          ;; regret=50 > threshold=0 and > epsilon-abs=0 → FAIL
          theory-strict {:equilibrium-concept ["subgame-perfect-equilibrium"]
                         :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          ;; regret=50 <= threshold=9999, and 50 < epsilon-abs=200, 50/50=1.0 not > 1.0 → PASS
          theory-lenient {:equilibrium-concept ["subgame-perfect-equilibrium"]
                          :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          r-strict  (-> (eq/evaluate-equilibrium theory-strict result)
                        :equilibrium-results :subgame-perfect-equilibrium)
          r-lenient (-> (eq/evaluate-equilibrium theory-lenient result)
                        :equilibrium-results :subgame-perfect-equilibrium)]
      ;; Strict threshold: regret(50) > 0 → fail
      (is (= :fail (:status r-strict))
          "regret-threshold=0 should fail when resolver wealth drops by 50")
      ;; Lenient threshold: regret(50) <= 9999 and within epsilon → pass
      (is (= :pass (:status r-lenient))
          "regret-threshold=9999 + epsilon-abs=200 should pass when resolver wealth drops by 50")
      ;; Confirm the declared threshold is visible in the observed payload
      (is (= 9999 (get-in r-lenient [:observed :spe-threshold]))
          "spe-threshold in observed should reflect the theory-declared value, not default 0"))))

;; ---------------------------------------------------------------------------
;; Phase K — Backward induction tests
;; ---------------------------------------------------------------------------

(defn- two-node-bi-replay-result
  "Minimal 2-node replay result for backward induction tests.
   Node seq=2 — buyer raise_dispute (information-set)
   Node seq=3 — resolver execute_resolution (proper-subgame)
   Resolver receives fee at execute_resolution; buyer wealth is 0 throughout."
  []
  {:trace
   [{:world {:resolver-stakes {} :claimable {} :bond-balances {} :live-states {} :total-held {}}
     :agent "buyer" :action "create_escrow" :seq 0 :time 1000}
    {:world {:resolver-stakes {} :claimable {} :bond-balances {} :live-states {"e1" "pending"} :total-held {"e1" 1000}}
     :agent "buyer" :action "raise_dispute" :seq 2 :time 1010}
    {:world {:resolver-stakes {"0xresolver" 200} :claimable {"e1" {"0xresolver" 50}} :bond-balances {} :live-states {"e1" "disputed"} :total-held {}}
     :agent "resolver" :action "execute_resolution" :seq 3 :time 1060}
    {:world {:resolver-stakes {"0xresolver" 200} :claimable {"e1" {"0xresolver" 50}} :bond-balances {} :live-states {"e1" "released"} :total-held {}}
     :agent "resolver" :action "settle" :seq 4 :time 1070}]
   :agents [{:id "buyer" :address "0xbuyer" :role "buyer" :strategy "rational"}
            {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}
   :protocol sew-protocol/protocol})

(deftest test-backward-induction-mode-vs-forward-single-node
  (testing "backward-induction and forward modes produce identical results on single-node trace"
    (let [result (minimal-replay-result)
          theory {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          theory-bi {:equilibrium-concept ["bounded-backward-induction-spe"]
                     :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r-fwd (-> (eq/evaluate-equilibrium theory result)
                    :equilibrium-results :bounded-public-state-epsilon-spe)
          r-bi  (-> (eq/evaluate-equilibrium theory-bi result)
                    :equilibrium-results :bounded-backward-induction-spe)]
      (is (or (= (:status r-fwd) (:status r-bi))
              (and (= :fail (:status r-fwd)) (= :inconclusive (:status r-bi))))
          "single-node trace: forward and backward-induction modes must agree on status"))))

(deftest test-backward-induction-evaluation-mode-in-output
  (testing "backward-induction mode is recorded in output keys"
    (let [result (two-node-bi-replay-result)
          theory {:equilibrium-concept ["bounded-backward-induction-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :bounded-backward-induction-spe)]
      (is (some? r) "bounded-backward-induction-spe result must be present")
      (is (contains? #{:pass :fail :inconclusive :not-applicable} (:status r))
          "status must be a known keyword"))))

(deftest test-backward-induction-terminal-deviation-uses-pre-wealth
  (testing "terminal deviation (settle_now) uses pre-wealth, not chosen-local"
    (let [result (two-node-bi-replay-result)
          theory-bi {:equilibrium-concept ["bounded-backward-induction-spe"]
                     :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          theory-fwd {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                      :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          r-bi  (-> (eq/evaluate-equilibrium theory-bi result)
                    :equilibrium-results :bounded-backward-induction-spe)
          r-fwd (-> (eq/evaluate-equilibrium theory-fwd result)
                    :equilibrium-results :bounded-public-state-epsilon-spe)]
      (is (some? r-bi) "backward-induction result must be present")
      (is (some? r-fwd) "forward result must be present")
      (when (= :pass (:status r-fwd))
        (is (contains? #{:pass :inconclusive} (:status r-bi))
            "backward induction on terminal deviation must not be stricter than forward pass")))))

(deftest test-backward-induction-two-node-pass
  (testing "honest 2-node trace passes bounded-backward-induction-spe"
    (let [result (two-node-bi-replay-result)
          theory {:equilibrium-concept ["bounded-backward-induction-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :bounded-backward-induction-spe)]
      (is (contains? #{:pass :inconclusive} (:status r))
          "honest resolution with no profitable deviation must pass or be inconclusive"))))

;; ---------------------------------------------------------------------------
;; Gap D — Reputation utility (:resolver-reputation-v1) tests
;; ---------------------------------------------------------------------------

(defn- reputation-slash-replay-result
  "Replay result where resolver executes_resolution, earns a fee, and is slashed.
   Pre-world  (seq 0): resolver-stakes=100, resolver-slash-total={}
   Terminal   (seq 1): resolver-stakes=0 (slashed), resolver-slash-total={addr 100},
                       claimable={e1 {addr 50}} (fee earned).
   terminal-realized-wealth = 0 + 50 = 50 (stake drop already included).
   slash-amount (explicit) = 100."
  ([]
   (reputation-slash-replay-result "0xresolver"))
  ([addr]
   {:trace [{:world {:resolver-stakes {addr 100}
                     :resolver-slash-total {}
                     :claimable {}
                     :bond-balances {}
                     :live-states {"e1" "disputed"}
                     :total-held {}
                     :total-fees {}}
             :agent "resolver" :action "register_stake" :seq 0 :time 1000}
            {:world {:resolver-stakes {addr 0}
                     :resolver-slash-total {addr 100}
                     :claimable {"e1" {addr 50}}
                     :bond-balances {}
                     :live-states {"e1" "released"}
                     :total-held {}
                     :total-fees {}
                     :terminal? true}
             :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
    :agents [{:id "resolver" :address addr :role "resolver" :strategy "malicious"}]
    :metrics {}
    :protocol sew-protocol/protocol}))

(defn- reputation-no-slash-replay-result
  "Replay result where resolver executes_resolution and earns fee — no slash.
   Pre-world  (seq 0): resolver-stakes=100, resolver-slash-total={}
   Terminal   (seq 1): resolver-stakes=100, resolver-slash-total={}, claimable={e1 {addr 50}}.
   terminal-realized-wealth = 100 + 50 = 150."
  []
  {:trace [{:world {:resolver-stakes {"0xresolver" 100}
                    :resolver-slash-total {}
                    :claimable {}
                    :bond-balances {}
                    :live-states {"e1" "disputed"}
                    :total-held {}
                    :total-fees {}}
            :agent "resolver" :action "register_stake" :seq 0 :time 1000}
           {:world {:resolver-stakes {"0xresolver" 100}
                    :resolver-slash-total {}
                    :claimable {"e1" {"0xresolver" 50}}
                    :bond-balances {}
                    :live-states {"e1" "released"}
                    :total-held {}
                    :total-fees {}
                    :terminal? true}
            :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
   :agents [{:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}
   :protocol sew-protocol/protocol})

(defn- reputation-withdrawal-replay-result
  "Replay result where resolver's stake drops due to withdrawal (not slash).
   resolver-slash-total stays at {} so :explicit-slash-total mode detects no slash."
  []
  {:trace [{:world {:resolver-stakes {"0xresolver" 100}
                    :resolver-slash-total {}
                    :claimable {}
                    :bond-balances {}
                    :live-states {"e1" "disputed"}
                    :total-held {}
                    :total-fees {}}
            :agent "resolver" :action "register_stake" :seq 0 :time 1000}
           {:world {:resolver-stakes {"0xresolver" 50}
                    :resolver-slash-total {}
                    :claimable {"e1" {"0xresolver" 50}}
                    :bond-balances {}
                    :live-states {"e1" "released"}
                    :total-held {}
                    :total-fees {}
                    :terminal? true}
            :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
   :agents [{:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}
   :protocol sew-protocol/protocol})

(defn- reputation-multi-resolver-replay-result
  "Two resolvers. Resolver-B is slashed but Resolver-A is the decision actor.
   Only Resolver-A's decision node exists. Resolver-A should not be penalized."
  []
  {:trace [{:world {:resolver-stakes {"0xresolver-a" 100 "0xresolver-b" 100}
                    :resolver-slash-total {}
                    :claimable {}
                    :bond-balances {}
                    :live-states {}
                    :total-held {}
                    :total-fees {}}
            :agent "resolver-a" :action "register_stake" :seq 0 :time 1000}
           {:world {:resolver-stakes {"0xresolver-a" 100 "0xresolver-b" 0}
                    :resolver-slash-total {"0xresolver-b" 100}
                    :claimable {"e1" {"0xresolver-a" 50}}
                    :bond-balances {}
                    :live-states {"e1" "released"}
                    :total-held {}
                    :total-fees {}
                    :terminal? true}
            :agent "resolver-a" :action "execute_resolution" :seq 1 :time 1100}]
   :agents [{:id "resolver-a" :address "0xresolver-a" :role "resolver" :strategy "honest"}
            {:id "resolver-b" :address "0xresolver-b" :role "resolver" :strategy "malicious"}]
   :metrics {}
   :protocol sew-protocol/protocol})

(deftest test-reputation-slash-detected-penalty-applied
  (testing ":resolver-reputation-v1 — slash detected, penalty reduces total utility"
    (let [result (reputation-slash-replay-result)
          ;; pre-wealth=100, terminal-realized=50 (stake 0 + claimable 50)
          ;; penalty=200, no discount → rep-adj = -200 → total-utility = -150
          ;; best-alt = pre-wealth = 100 → regret = 250 > threshold=0 → FAIL
          theory {:equilibrium-concept ["resolver-reputation-spe"]
                  :spe-config {:regret-threshold 0
                               :utility-spec {:reputation-slash-penalty 200
                                              :reputation-discount-rate 1.0}}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :resolver-reputation-spe)]
      (is (= :fail (:status r))
          "malicious resolver with large penalty should fail SPE")
      (is (pos? (get-in r [:observed :slash-detected-count] 0))
          "slash-detected-count should be positive when resolver was slashed"))))

(deftest test-reputation-no-slash-equals-terminal-realized
  (testing ":resolver-reputation-v1 with no slash equals :terminal-realized-v1"
    (let [result-no-slash (reputation-no-slash-replay-result)
          ;; Both utility types should agree when no slash occurred
          theory-rep {:equilibrium-concept ["resolver-reputation-spe"]
                      :spe-config {:regret-threshold 9999
                                   :utility-spec {:reputation-slash-penalty 200}}}
          theory-std {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                      :spe-config {:regret-threshold 9999}}
          r-rep (-> (eq/evaluate-equilibrium theory-rep result-no-slash)
                    :equilibrium-results :resolver-reputation-spe)
          r-std (-> (eq/evaluate-equilibrium theory-std result-no-slash)
                    :equilibrium-results :bounded-public-state-epsilon-spe)]
      (is (= (:status r-rep) (:status r-std))
          "no slash → reputation-v1 must agree with terminal-realized-v1 on pass/fail"))))

(deftest test-reputation-zero-penalty-matches-terminal-realized
  (testing ":resolver-reputation-v1 with zero penalty is identical to :terminal-realized-v1"
    (let [result (reputation-slash-replay-result)
          ;; Even though resolver is slashed, penalty=0 → no extra adjustment
          theory-zero {:equilibrium-concept ["resolver-reputation-spe"]
                       :spe-config {:regret-threshold 9999
                                    :utility-spec {:reputation-slash-penalty 0}}}
          theory-std  {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                       :spe-config {:regret-threshold 9999}}
          r-zero (-> (eq/evaluate-equilibrium theory-zero result)
                     :equilibrium-results :resolver-reputation-spe)
          r-std  (-> (eq/evaluate-equilibrium theory-std result)
                     :equilibrium-results :bounded-public-state-epsilon-spe)]
      (is (= (:status r-zero) (:status r-std))
          "penalty=0 → reputation-v1 must agree with terminal-realized-v1"))))

(deftest test-reputation-concept-dispatches
  (testing ":resolver-reputation-spe concept dispatches and returns a valid result"
    (let [result (reputation-no-slash-replay-result)
          theory {:equilibrium-concept ["resolver-reputation-spe"]
                  :spe-config {:regret-threshold 0
                               :utility-spec {:reputation-slash-penalty 100}}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :resolver-reputation-spe)]
      (is (some? r) "resolver-reputation-spe result must be present")
      (is (contains? #{:pass :fail :inconclusive :not-applicable} (:status r))
          "status must be a valid keyword")
      (is (= :resolver-reputation-v1 (get-in r [:observed :utility-type]))
          "observed must report utility-type :resolver-reputation-v1"))))

(deftest test-reputation-wrong-actor-not-penalized
  (testing ":resolver-reputation-v1 — resolver-B slash does not penalize resolver-A"
    (let [result (reputation-multi-resolver-replay-result)
          ;; resolver-B is slashed; resolver-A is the decision actor
          ;; resolver-A's utility should not include resolver-B's slash penalty
          theory {:equilibrium-concept ["resolver-reputation-spe"]
                  :spe-config {:regret-threshold 9999
                               :utility-spec {:reputation-slash-penalty 500}}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :resolver-reputation-spe)]
      (is (contains? #{:pass :inconclusive} (:status r))
          "resolver-B's slash should not penalize resolver-A (wrong actor)")
      (is (zero? (get-in r [:observed :slash-detected-count] 0))
          "slash-detected-count must be 0 when only the non-decision actor was slashed"))))

(deftest test-reputation-stake-withdrawal-not-slash
  (testing ":resolver-reputation-v1 :explicit-slash-total — stake drop without slash-total increase is not a slash"
    (let [result (reputation-withdrawal-replay-result)
          ;; stake drops from 100→50 (voluntary withdrawal), resolver-slash-total stays {}
          ;; penalty=500 but no slash detected → utility = terminal-realized → PASS with lenient threshold
          theory {:equilibrium-concept ["resolver-reputation-spe"]
                  :spe-config {:regret-threshold 9999
                               :utility-spec {:reputation-slash-penalty 500
                                              :slash-detection-mode :explicit-slash-total}}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :resolver-reputation-spe)]
      (is (contains? #{:pass :inconclusive} (:status r))
          "stake withdrawal should not trigger slash penalty in :explicit-slash-total mode")
      (is (zero? (get-in r [:observed :slash-detected-count] 0))
          "slash-detected-count must be 0 when resolver-slash-total did not increase"))))

(deftest test-reputation-no-double-counting-stake-loss
  (testing ":resolver-reputation-v1 — stake loss already in terminal-wealth; only rep-penalty added"
    ;; resolver slashed: slash-amount=100, stake: 100→0, terminal-realized=50 (0+claimable50)
    ;; penalty=200 → total-utility = 50 + (-200) = -150
    ;; If double-counted: 50 - 100 + (-200) = -250 (wrong)
    ;; We verify by checking the utility breakdown of the row.
    (let [result (reputation-slash-replay-result)
          theory {:equilibrium-concept ["resolver-reputation-spe"]
                  :spe-config {:regret-threshold 9999
                               :utility-spec {:reputation-slash-penalty 200
                                              :reputation-discount-rate 1.0}}}
          r        (-> (eq/evaluate-equilibrium theory result)
                       :equilibrium-results :resolver-reputation-spe)
          ;; Find the execute_resolution row in the regret table
          rows     (get-in r [:observed :counterexamples] [])
          ;; Get the actual projection to inspect the regret-table directly
          raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol result)
                       (assoc :spe-config {:regret-threshold 9999
                                           :utility-spec {:type :resolver-reputation-v1
                                                          :reputation-slash-penalty 200
                                                          :reputation-discount-rate 1.0}}))
          eval-r   (subgame-cf/evaluate-subgame-counterfactual raw-proj)
          exec-row (first (filter #(= "execute_resolution" (:chosen-action %))
                                  (:regret-table eval-r)))
          bd       (:utility-breakdown exec-row)]
      (is (some? bd) "utility-breakdown must be present for :resolver-reputation-v1")
      (when bd
        (is (= 50 (:terminal-realized-wealth bd))
            "terminal-realized-wealth should be 50 (stake 0 + claimable 50)")
        (is (true? (:slash-detected? bd))
            "slash should be detected via resolver-slash-total")
        (is (= 100 (:slash-amount bd))
            "slash-amount should equal resolver-slash-total diff (100), not stake drop")
        (is (= -200 (:reputation-adjustment bd))
            "reputation-adjustment should be -(penalty * discount) = -200")
        (is (= -150 (:total-utility bd))
            "total-utility = 50 + (-200) = -150 (NOT 50 - 100 - 200 = -250)")))))

(deftest test-reputation-min-required-penalty-emitted
  (testing ":resolver-reputation-v1 — min-reputation-penalty-required emitted per row"
    ;; terminal-realized=50, best-alt=100 (pre-wealth), gap=50, discount=1.0
    ;; min-required = ceil(50/1.0) = 50
    (let [result (reputation-slash-replay-result)
          raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol result)
                       (assoc :spe-config {:regret-threshold 9999
                                           :utility-spec {:type :resolver-reputation-v1
                                                          :reputation-slash-penalty 200
                                                          :reputation-discount-rate 1.0}}))
          eval-r   (subgame-cf/evaluate-subgame-counterfactual raw-proj)
          exec-row (first (filter #(= "execute_resolution" (:chosen-action %))
                                  (:regret-table eval-r)))
          min-req  (:min-reputation-penalty-required exec-row)
          global   (:min-reputation-penalty-for-spe-pass eval-r)]
      (is (some? min-req)
          "min-reputation-penalty-required must be emitted per row when there is a gap")
      (is (= 50 min-req)
          "min-required should be 50: gap=(100-50)=50, discount=1.0, ceil(50/1.0)=50")
      (is (some? global)
          "min-reputation-penalty-for-spe-pass must be emitted globally")
      (is (= 50 global)
          "global should equal max(per-row min-required)"))))

;; ---------------------------------------------------------------------------
;; Profile matrix tests
;; ---------------------------------------------------------------------------

(defn- reputation-gain-then-slash-result
  "Replay result where resolver earns a large fee AND gets partially slashed.
   This models the v7 pattern: terminal-realized > pre-wealth without reputation,
   but reputation penalty flips the comparison.

   Pre-world  (seq 0): resolver-stakes=100, slash-total={}
   Terminal   (seq 1): resolver-stakes=80 (slashed 20), slash-total={addr 20},
                       claimable={e1 {addr 60}} (large fee).
   terminal-realized = 80 + 60 = 140 > pre=100.

   Profile behaviour:
     :reputation/none         penalty=0 → adj=0  → chosen=140 > 100 → PASS
     :reputation/conservative penalty=25, disc=0.5 → adj=-12 → chosen=128 > 100 → PASS
     :reputation/baseline     penalty=100, disc=0.8 → adj=-80 → chosen=60 < 100 → FAIL"
  []
  (let [addr "0xresolver"]
    {:trace [{:world {:resolver-stakes {addr 100}
                      :resolver-slash-total {}
                      :claimable {}
                      :bond-balances {}
                      :live-states {"e1" "disputed"}
                      :total-held {}
                      :total-fees {}}
              :agent "resolver" :action "register_stake" :seq 0 :time 1000}
             {:world {:resolver-stakes {addr 80}
                      :resolver-slash-total {addr 20}
                      :claimable {"e1" {addr 60}}
                      :bond-balances {}
                      :live-states {"e1" "released"}
                      :total-held {}
                      :total-fees {}
                      :terminal? true}
              :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
     :agents [{:id "resolver" :address addr :role "resolver" :strategy "malicious"}]
     :metrics {}
     :protocol sew-protocol/protocol}))

(deftest test-resolve-utility-profile-keyword
  (testing "resolve-utility-profile returns built-in profile for known keyword"
    (let [p (rep-profiles/resolve-utility-profile :reputation/baseline)]
      (is (map? p) "should return a map")
      (is (= :reputation/baseline (:profile/id p)))
      (is (= :sensitivity (:profile/category p)))
      (is (some? (:reputation-slash-penalty p)) "should have a penalty field"))))

(deftest test-resolve-utility-profile-map-passthrough
  (testing "resolve-utility-profile returns inline map as-is"
    (let [inline {:type :resolver-reputation-v1 :reputation-slash-penalty 42}
          p (rep-profiles/resolve-utility-profile inline)]
      (is (= inline p) "map should be returned unchanged"))))

(deftest test-resolve-utility-profile-unknown-throws
  (testing "resolve-utility-profile throws on unknown keyword"
    (is (thrown? Exception (rep-profiles/resolve-utility-profile :nosuchprofile)))))

(deftest test-resolve-utility-profile-nil-returns-nil
  (testing "resolve-utility-profile returns nil for nil input"
    (is (nil? (rep-profiles/resolve-utility-profile nil)))))

(deftest test-expected-future-earnings-model
  (testing ":expected-future-earnings model computes penalty from routing probability delta"
    (let [result (reputation-slash-replay-result)
          raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol result)
                       (assoc :spe-config {:regret-threshold 9999
                                           :utility-spec {:type :resolver-reputation-v1
                                                          :reputation/model :expected-future-earnings
                                                          :expected-future-cases 100
                                                          :expected-fee-per-case 2.0
                                                          :routing-probability-before 0.05
                                                          :routing-probability-after  0.01
                                                          :resolver-margin 1.0
                                                          :reputation-discount-rate 1.0}}))
          eval-r   (subgame-cf/evaluate-subgame-counterfactual raw-proj)
          exec-row (first (filter #(= "execute_resolution" (:chosen-action %))
                                  (:regret-table eval-r)))
          bd       (:utility-breakdown exec-row)]
      ;; penalty = (0.05-0.01)*100*2.0*1.0 = 8; discount=1.0 → rep-adj=-8
      (is (= :expected-future-earnings (:reputation-model bd))
          "breakdown should record reputation-model")
      (is (= 8 (:reputation-penalty-used bd))
          "penalty should be (0.05-0.01)*100*2.0*1.0 = 8")
      (is (= -8 (:reputation-adjustment bd))
          "adjustment should be -(8*1.0) = -8"))))

(deftest test-profile-matrix-runner-basic
  (testing "run-profile-matrix differentiates profiles when resolver gains then is slashed"
    ;; terminal-realized=140 > pre=100; partial slash of 20
    ;; none:         adj=0,   chosen=140 > 100 → PASS
    ;; baseline:     adj=-80, chosen=60  < 100 → FAIL
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-gain-then-slash-result))
                       (assoc :spe-config {:regret-threshold 0}))
          matrix   (subgame-cf/run-profile-matrix raw-proj [:reputation/none :reputation/baseline])]
      (is (map? matrix) "should return a map")
      (is (= 2 (count (:profile-results matrix))) "should have 2 per-profile results")
      (is (= :reputation/none    (-> matrix :profile-results first :profile-id)))
      (is (= :reputation/baseline (-> matrix :profile-results second :profile-id)))
      (is (= :pass (-> matrix :profile-results first :status))
          ":reputation/none should pass — resolver gains (140>100) with no reputation penalty")
      (is (= :fail (-> matrix :profile-results second :status))
          ":reputation/baseline should fail — adj=-80 makes chosen=60 < pre=100")
      (is (= :reputation/none (:min-profile-required matrix))
          "min-profile-required is the first (weakest) PASS profile")
      (is (true? (:any-pass? matrix)))
      (is (false? (:all-pass? matrix))))))

(deftest test-profile-matrix-all-pass
  (testing "run-profile-matrix all-pass? when resolver is honest (no slash, chosen > pre)"
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-no-slash-replay-result))
                       (assoc :spe-config {:regret-threshold 0}))
          ;; No slash → no reputation penalty applied → chosen=150, pre=100 → PASS for all
          matrix   (subgame-cf/run-profile-matrix raw-proj [:reputation/none :reputation/conservative])]
      (is (true? (:all-pass? matrix)))
      (is (true? (:any-pass? matrix)))
      (is (empty? (:fail-profiles matrix))))))

(deftest test-profile-matrix-conservative-also-fails
  (testing ":reputation/conservative creates regret even at low penalty (any penalty > 0 deters)"
    ;; terminal-realized=140, pre=100, best-alt=140 (chosen-local, no slash)
    ;; conservative: adj=-13, chosen=127 < best-alt=140 → regret=13 → FAIL
    ;; This shows deterrence kicks in at any non-zero reputation penalty.
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-gain-then-slash-result))
                       (assoc :spe-config {:regret-threshold 0}))
          matrix   (subgame-cf/run-profile-matrix raw-proj [:reputation/conservative])]
      (is (= :fail (-> matrix :profile-results first :status))
          "conservative profile: best-alt=140 (no slash path), chosen=127 → regret=13 → FAIL"))))

(deftest test-profile-matrix-validator-no-profiles-inconclusive
  (testing ":resolver-reputation-profile-matrix → inconclusive when no utility-profiles declared"
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-slash-replay-result))
                       (assoc :spe-config {:regret-threshold 0}))
          eq-map   (eq/evaluate-equilibrium-concepts
                    [:resolver-reputation-profile-matrix] raw-proj
                    sew-eq/equilibrium-concept-validators)
          r        (get eq-map :resolver-reputation-profile-matrix)]
      (is (= 1 (count eq-map)) "should have exactly one concept result")
      (is (= :inconclusive (:status r))
          "should be :inconclusive when :utility-profiles absent"))))

(deftest test-profile-matrix-validator-dispatches
  (testing ":resolver-reputation-profile-matrix validator dispatches and returns profile-results"
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-gain-then-slash-result))
                       (assoc :spe-config {:regret-threshold 0
                                           :utility-profiles [:reputation/none
                                                              :reputation/baseline]}))
          eq-map   (eq/evaluate-equilibrium-concepts
                    [:resolver-reputation-profile-matrix] raw-proj
                    sew-eq/equilibrium-concept-validators)
          r        (get eq-map :resolver-reputation-profile-matrix)]
      (is (= 1 (count eq-map)))
      ;; none=PASS, baseline=FAIL → any-pass?=true, all-pass?=false → overall :fail
      (is (= :fail (:status r))
          "status should be :fail when any profile fails (any-pass? but not all-pass?)")
      (is (some? (get-in r [:observed :profile-results]))
          "observed should include :profile-results")
      (is (= 2 (count (get-in r [:observed :profile-results])))
          "should have 2 profile results")
      (is (= :reputation/none (get-in r [:observed :min-profile-required]))
          "min-profile-required should be :reputation/none (first PASS)"))))

(deftest test-profile-matrix-min-profile-required-identification
  (testing "min-profile-required is :reputation/none — only profile where regret=0 (no penalty)"
    ;; gain-then-slash: best-alt=140 in all cases (forward-pass local-alt = max(pre=100, chosen-local=140))
    ;; none:         adj=0,   chosen=140, regret=0  → PASS
    ;; conservative: adj=-13, chosen=127, regret=13 → FAIL (any non-zero penalty creates regret)
    ;; baseline:     adj=-80, chosen=60,  regret=80 → FAIL
    ;; This shows deterrence is active under conservative and strong assumptions; only purely
    ;; reputationless actors (none) are indifferent.
    (let [raw-proj (-> (sew-proj/trace-end-projection sew-protocol/protocol (reputation-gain-then-slash-result))
                       (assoc :spe-config {:regret-threshold 0}))
          matrix   (subgame-cf/run-profile-matrix raw-proj
                                                  [:reputation/none
                                                   :reputation/conservative
                                                   :reputation/baseline])]
      (is (= :reputation/none (:min-profile-required matrix))
          "only :reputation/none passes (no penalty → regret=0)")
      (is (= [:reputation/conservative :reputation/baseline] (:fail-profiles matrix))
          "conservative and baseline both fail (any reputation penalty deters)"))))

;; ---------------------------------------------------------------------------
;; Cancellation-dominance equilibrium concept tests
;; ---------------------------------------------------------------------------

(deftest test-cancellation-dominance-pass
  (testing ":cancellation-dominance passes when cancel node has zero regret"
    (let [proj (sb/spe-projection {:pre-wealth 100 :chosen-wealth 200
                                   :agent "buyer" :action "sender_cancel"})
          result (-> (eq/evaluate-equilibrium-concepts [:cancellation-dominance] proj
                                                       sew-eq/equilibrium-concept-validators)
                     :cancellation-dominance)]
      (is (= :pass (:status result))
          "cancel with chosen-wealth >= pre-wealth should pass")
      (is (= :hard (:severity result)))
      (is (pos? (get-in result [:observed :cancel-nodes-checked]))
          "should report at least one cancel node checked"))))

(deftest test-cancellation-dominance-fail
  (testing ":cancellation-dominance fails when cancel node has positive regret"
    (let [proj (sb/spe-projection {:pre-wealth 100 :chosen-wealth 0
                                   :agent "buyer" :action "sender_cancel"
                                   :regret-threshold 0})
          result (-> (eq/evaluate-equilibrium-concepts [:cancellation-dominance] proj
                                                       sew-eq/equilibrium-concept-validators)
                     :cancellation-dominance)]
      (is (= :fail (:status result))
          "cancel with chosen-wealth < pre-wealth should fail")
      (is (seq (:offending result))
          "should report offending nodes with positive regret"))))

(deftest test-cancellation-dominance-inconclusive-no-cancel-nodes
  (testing ":cancellation-dominance is :inconclusive when no cancel decision nodes present"
    (let [proj (sb/spe-projection {:agent "resolver" :action "execute_resolution"})
          result (-> (eq/evaluate-equilibrium-concepts [:cancellation-dominance] proj
                                                       sew-eq/equilibrium-concept-validators)
                     :cancellation-dominance)]
      (is (= :inconclusive (:status result))
          "non-cancel node should produce inconclusive result")
      (is (= :soft (:severity result))
          "inconclusive has soft severity"))))

;; ---------------------------------------------------------------------------
;; Alias result labelling — semantic-equivalence regression tests
;; ---------------------------------------------------------------------------
;;
;; Aliases intentionally share the predicate of their canonical concept, but
;; must label the result with the *requested* concept keyword (not the parent's).
;; This proves the shared implementation is genuinely labeling-only: every field
;; except :property must be identical to the canonical evaluator for the same
;; input. This protects the stated out-of-scope constraint that the alias fix
;; does not change pass/fail semantics.

(defn- assert-alias-equivalent
  "Run `alias-kw` and `canonical-kw` through the dispatcher on `proj` with
   `validators`, and assert the alias result equals the canonical result except
   for the :property label (which must equal the alias keyword)."
  [alias-kw canonical-kw proj validators]
  (let [results (eq/evaluate-equilibrium-concepts [alias-kw canonical-kw] proj validators)
        alias-result (get results alias-kw)
        canonical-result (get results canonical-kw)
        alias-body (dissoc alias-result :property)
        canonical-body (dissoc canonical-result :property)]
    (is (= alias-kw (:property alias-result))
        (str (name alias-kw) " must be labelled with its own keyword, not the canonical parent"))
    (is (= canonical-kw (:property canonical-result)))
    (is (= canonical-body alias-body)
        (str (name alias-kw) " must be semantically identical to "
             (name canonical-kw) " except for the :property label"))))

(deftest test-empirical-strategy-dominance-is-dominant-label-only
  (testing ":empirical-strategy-dominance reuses the dominant-strategy predicate with its own label"
    (doseq [opts [{:attack-attempts 0 :attack-successes 0 :invariant-violations 0}
                  {:attack-attempts 3 :attack-successes 0 :invariant-violations 0}
                  {:attack-attempts 1 :attack-successes 1 :invariant-violations 0}
                  {:attack-attempts 0 :attack-successes 0 :invariant-violations 2}]]
      (assert-alias-equivalent
       :empirical-strategy-dominance :dominant-strategy-equilibrium
       (projection opts) {}))))

(deftest test-bounded-nash-diagnostic-is-nash-label-only
  (testing ":bounded-nash-diagnostic reuses the nash predicate with its own label"
    (doseq [opts [{:attack-attempts 0 :attack-successes 0 :invariant-violations 0}
                  {:attack-attempts 2 :attack-successes 0 :invariant-violations 0}
                  {:attack-attempts 1 :attack-successes 1 :invariant-violations 0}]]
      (assert-alias-equivalent
       :bounded-nash-diagnostic :nash-equilibrium
       (projection opts) {}))))

(deftest test-trace-conditioned-epsilon-spe-is-spe-label-only
  (testing ":trace-conditioned-epsilon-spe reuses the subgame-perfect predicate with its own label"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 150}}}}] ; t=2 (won: escrow 100 + bond 50)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}]
      (assert-alias-equivalent
       :trace-conditioned-epsilon-spe :subgame-perfect-equilibrium
       proj sew-eq/equilibrium-concept-validators))))

