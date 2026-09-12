(ns resolver-sim.sim.kernel-bridge
  "Kernel validation bridge: validates batch simulation params against the
   Sew protocol replay kernel.

   The stochastic batch runner (sim.batch) models dispute outcomes as probability
   distributions. This namespace validates that the underlying Sew protocol state
   machine is consistent with those parameters by generating minimal dispute
   scenarios and running them through the full replay kernel.

   Usage (optional per-epoch validation in run-single-epoch):
     - Set :kernel-validation-sample-size in params (e.g. 10)
     - run-single-epoch will call run-kernel-validation once per epoch
     - Any kernel invariant violations are reported in the epoch-summary

   Layering: sim/* may import contract_model/* and protocols/* per project rules."
  (:require [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry       :as preg]
            [resolver-sim.stochastic.rng        :as rng]))

(defn- default-protocol []
  (preg/get-protocol preg/default-protocol-id))

;; ---------------------------------------------------------------------------
;; Scenario generation
;; ---------------------------------------------------------------------------

(defn generate-honest-scenario
  "Generate a minimal honest-resolver dispute scenario from batch params.

   Scenario structure (3 events):
     create_escrow  — buyer creates escrow with custom resolver
     raise_dispute  — buyer raises a dispute
     execute_resolution — resolver issues an honest release verdict

   The protocol-params are extracted from the batch params map so that
   the kernel validates exactly the fee and timeout configuration used by
   the stochastic model.

   Returns a scenario map accepted by replay/replay-with-protocol."
  [params trial-id]
  {:scenario-id        (str "kernel-batch-" trial-id)
   :schema-version     "1.0"
   :initial-block-time 1000
   :agents
   [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
    {:id "seller"   :address "0xseller"   :strategy "honest"}
    {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params
   {:resolver-fee-bps      (:resolver-fee-bps params 100)
    :appeal-window-duration 0
    :max-dispute-duration  (:max-dispute-duration params 2592000)}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller"
              :amount          (:escrow-size params 10000)
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0
              :is-release     true
              :resolution-hash "0xhash"}}]})

(defn generate-fraud-slash-scenario
  "Generate a dispute scenario that includes a fraud slash lifecycle.
   Models a malicious resolver (wrong decision) caught by governance.

   Sequence:
     create_escrow → raise_dispute → execute_resolution (wrong verdict)
     → propose_fraud_slash → execute_fraud_slash

   The slash amount is computed from :fraud-slash-bps and :escrow-size.

   Returns a scenario map accepted by replay/replay-with-protocol."
  [params trial-id]
  (let [escrow-size (:escrow-size params 10000)
        slash-bps   (:fraud-slash-bps params 500)
        slash-amount (long (* escrow-size (/ slash-bps 10000.0)))
        ;; Slash deadline must be past appeal window; advance time well beyond it
        t-slash-exec 3000]
    {:scenario-id        (str "kernel-fraud-" trial-id)
     :schema-version     "1.0"
     :initial-block-time 1000
     :agents
     [{:id "buyer"      :address "0xbuyer"      :strategy "honest"}
      {:id "seller"     :address "0xseller"     :strategy "honest"}
      {:id "resolver"   :address "0xresolver"   :role "resolver"}
      {:id "governance" :address "0xgov"        :role "governance"}
      {:id "keeper"     :address "0xkeeper"     :role "keeper"}]
     :protocol-params
     {:resolver-fee-bps       (:resolver-fee-bps params 100)
      :appeal-window-duration 120
      :max-dispute-duration   (:max-dispute-duration params 2592000)}
     :events
     [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
       :params {:token "USDC" :to "0xseller"
                :amount          escrow-size
                :custom-resolver "0xresolver"}}
      {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
       :params {:workflow-id 0}}
      ;; Malicious: wrong verdict (refund instead of release, i.e. is-release=false)
      {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
       :params {:workflow-id 0
                :is-release      false
                :resolution-hash "0xbad-hash"}
       :event-tags #{:adversarial}}
      ;; Governance proposes fraud slash; appeal window = 120s → deadline = 1130+120 = 1250
      {:seq 3 :time 1130 :agent "governance" :action "propose_fraud_slash"
       :params {:workflow-id 0
                :resolver-addr "0xresolver"
                :amount        slash-amount}}
      ;; Advance time past the appeal deadline, then execute the slash
      {:seq 4 :time t-slash-exec :agent "governance" :action "execute_fraud_slash"
       :params {:workflow-id 0}}]}))

;; ---------------------------------------------------------------------------
;; Kernel validation
;; ---------------------------------------------------------------------------

(defn run-kernel-validation
  "Run n-samples minimal dispute scenarios through the Sew replay kernel.

   Each scenario is generated from the given params and validated against
   the full invariant suite. Violations are reported with scenario ID and
   invariant details.

   Args:
     params    — batch simulation params (escrow-size, fee-bps, etc.)
     n-samples — number of scenarios to validate (default 5)
     rng       — seeded SplittableRandom (used only for sample-index determinism)

   Returns:
     {:pass-count          int
      :fail-count          int
      :pass-rate           double [0,1]
      :violations          [{:scenario-id :halt-reason :metrics}]
      :honest-pass-count   int
      :fraud-pass-count    int
      :honest-fail-count   int
      :fraud-fail-count    int}"
  ([params n-samples rng]
   (let [honest-results
         (vec (for [i (range n-samples)]
                (let [_ (rng/next-long rng) ; consume rng for determinism
                      sc (generate-honest-scenario params (str "h" i))
                      r  (replay/replay-with-protocol (default-protocol) sc {:skip-finalize true})]
                  {:scenario-id (:scenario-id sc)
                   :type        :honest
                   :outcome     (:outcome r)
                   :violations  (:invariant-violations (:metrics r) 0)
                   :halt-reason (:halt-reason r)})))

         fraud-results
         (when (and (pos? (:fraud-slash-bps params 0))
                    (pos? (:fraud-detection-probability params 0.0)))
           (vec (for [i (range n-samples)]
                  (let [_ (rng/next-long rng)
                        sc (generate-fraud-slash-scenario params (str "f" i))
                        r  (replay/replay-with-protocol (default-protocol) sc {:skip-finalize true})]
                    {:scenario-id (:scenario-id sc)
                     :type        :fraud-slash
                     :outcome     (:outcome r)
                     :violations  (:invariant-violations (:metrics r) 0)
                     :halt-reason (:halt-reason r)}))))

         all-results       (concat honest-results (or fraud-results []))
         passed            (filter #(= :pass (:outcome %)) all-results)
         failed            (remove #(= :pass (:outcome %)) all-results)
         honest-passed     (filter #(= :pass (:outcome %)) honest-results)
         fraud-passed      (filter #(= :pass (:outcome %)) (or fraud-results []))
         total             (count all-results)]
     {:pass-count        (count passed)
      :fail-count        (count failed)
      :pass-rate         (if (pos? total) (double (/ (count passed) total)) 1.0)
      :violations        (mapv #(select-keys % [:scenario-id :halt-reason :violations]) failed)
      :honest-pass-count (count honest-passed)
      :honest-fail-count (- n-samples (count honest-passed))
      :fraud-pass-count  (count fraud-passed)
      :fraud-fail-count  (- (count (or fraud-results [])) (count fraud-passed))}))
  ([params rng]
   (run-kernel-validation params 5 rng)))

;; ---------------------------------------------------------------------------
;; Additional scenario generators (Phase 5)
;; ---------------------------------------------------------------------------

(defn generate-pending-settlement-scenario
  "Generate a dispute scenario that exercises the pending-settlement path.

   Sequence:
     create_escrow → raise_dispute → execute_resolution (refund, is-release=false)
     → execute_pending_settlement

   The resolution with is-release=false creates a pending refund-to-buyer
   settlement. The keeper then finalises it after the settlement deadline.
   This validates the full settlement finalization path, not covered by
   generate-honest-scenario (which uses is-release=true, an immediate release).

   Returns a scenario map accepted by replay/replay-with-protocol."
  [params trial-id]
  {:scenario-id        (str "kernel-pending-" trial-id)
   :schema-version     "1.0"
   :initial-block-time 1000
   :agents
   [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
    {:id "seller"   :address "0xseller"   :strategy "honest"}
    {:id "resolver" :address "0xresolver" :role "resolver"}
    {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params
   {:resolver-fee-bps       (:resolver-fee-bps params 100)
    :appeal-window-duration 120
    :max-dispute-duration   (:max-dispute-duration params 2592000)}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller"
              :amount          (:escrow-size params 10000)
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0
              :is-release      false
              :resolution-hash "0xhash-refund"}}
    {:seq 3 :time 2000 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(defn generate-appeal-slash-scenario
  "Generate a dispute scenario covering the appeal-slash path.

   Sequence:
     create_escrow → raise_dispute → execute_resolution (bad verdict)
     → propose_fraud_slash → appeal_slash (resolver appeals)
     → resolve_appeal (governance rejects appeal, slash stands)

   Returns a scenario map accepted by replay/replay-with-protocol."
  [params trial-id]
  (let [escrow-size  (:escrow-size params 10000)
        slash-bps    (:fraud-slash-bps params 500)
        slash-amount (long (* escrow-size (/ slash-bps 10000.0)))]
    {:scenario-id        (str "kernel-appeal-" trial-id)
     :schema-version     "1.0"
     :initial-block-time 1000
     :agents
     [{:id "buyer"      :address "0xbuyer"      :strategy "honest"}
      {:id "seller"     :address "0xseller"     :strategy "honest"}
      {:id "resolver"   :address "0xresolver"   :role "resolver"}
      {:id "governance" :address "0xgov"        :role "governance"}]
     :protocol-params
     {:resolver-fee-bps       (:resolver-fee-bps params 100)
      :appeal-window-duration 120
      :max-dispute-duration   (:max-dispute-duration params 2592000)}
     :events
     [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
       :params {:token "USDC" :to "0xseller"
                :amount          escrow-size
                :custom-resolver "0xresolver"}}
      {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
       :params {:workflow-id 0}}
      {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
       :params {:workflow-id 0
                :is-release      false
                :resolution-hash "0xbad-hash"}
       :event-tags #{:adversarial}}
      {:seq 3 :time 1130 :agent "governance" :action "propose_fraud_slash"
       :params {:workflow-id 0
                :resolver-addr "0xresolver"
                :amount        slash-amount}}
      {:seq 4 :time 1140 :agent "resolver" :action "appeal_slash"
       :params {:workflow-id 0}}
      {:seq 5 :time 1160 :agent "governance" :action "resolve_appeal"
       :params {:workflow-id 0
                :upheld? false}}]}))

(defn- check-domain-metrics
  "Verify that a replay result exercised the expected protocol path.
   Returns {:ok? bool :missing [key ...]} where :missing lists expected
   domain-metric keys that were zero (meaning the path was not traversed).

   expected-nonzero is a seq of metric keys that must be > 0 in the result."
  [r expected-nonzero]
  (let [metrics (:metrics r {})
        missing (vec (filter #(zero? (get metrics % 0)) expected-nonzero))]
    {:ok?     (empty? missing)
     :missing missing}))

(defn run-full-kernel-validation
  "Run all four scenario types through the Sew replay kernel.

   Extends run-kernel-validation with Phase 5 pending-settlement and
   appeal-slash scenario types. All four paths must pass for :all-paths-pass? true.

   Phase 6 addition: each scenario type is verified to have exercised its
   expected protocol path. Pending-settlement scenarios must have non-zero
   :pending-settlements-executed; appeal-slash scenarios must have non-zero
   :disputes-triggered AND :resolutions-executed. This guards against scenarios
   that 'pass' by halting before the relevant protocol path is reached.

   Returns run-kernel-validation result enriched with:
     :pending-pass-count           int
     :pending-fail-count           int
     :appeal-pass-count            int
     :appeal-fail-count            int
     :all-paths-pass?              bool
     :path-coverage-ok?            bool  (domain metrics all non-zero)
     :path-coverage-violations     [{:scenario-id :missing [...]}]"
  ([params n-samples rng]
   (let [base-result (run-kernel-validation params n-samples rng)

         pending-results
         (vec (for [i (range n-samples)]
                (let [_ (rng/next-long rng)
                      sc (generate-pending-settlement-scenario params (str "p" i))
                      r  (replay/replay-with-protocol (default-protocol) sc)
                      coverage (check-domain-metrics r [:disputes-triggered
                                                        :resolutions-executed
                                                        :pending-settlements-executed])]
                  {:scenario-id (:scenario-id sc)
                   :type        :pending-settlement
                   :outcome     (:outcome r)
                   :violations  (:invariant-violations (:metrics r) 0)
                   :halt-reason (:halt-reason r)
                   :coverage    coverage})))

         appeal-results
         (vec (for [i (range n-samples)]
                (let [_ (rng/next-long rng)
                      sc (generate-appeal-slash-scenario params (str "a" i))
                      r  (replay/replay-with-protocol (default-protocol) sc)
                      coverage (check-domain-metrics r [:disputes-triggered
                                                        :resolutions-executed])]
                  {:scenario-id (:scenario-id sc)
                   :type        :appeal-slash
                   :outcome     (:outcome r)
                   :violations  (:invariant-violations (:metrics r) 0)
                   :halt-reason (:halt-reason r)
                   :coverage    coverage})))

         pending-passed  (count (filter #(= :pass (:outcome %)) pending-results))
         appeal-passed   (count (filter #(= :pass (:outcome %)) appeal-results))
         coverage-violations
         (vec (keep (fn [r]
                      (when-not (get-in r [:coverage :ok?])
                        {:scenario-id (:scenario-id r)
                         :type        (:type r)
                         :missing     (get-in r [:coverage :missing])}))
                    (concat pending-results appeal-results)))]

     (merge base-result
            {:pending-pass-count       pending-passed
             :pending-fail-count       (- n-samples pending-passed)
             :appeal-pass-count        appeal-passed
             :appeal-fail-count        (- n-samples appeal-passed)
             :all-paths-pass?          (and (= (:pass-rate base-result) 1.0)
                                            (= pending-passed n-samples)
                                            (= appeal-passed n-samples))
             :path-coverage-ok?        (empty? coverage-violations)
             :path-coverage-violations coverage-violations
             :pending-violations       (mapv #(select-keys % [:scenario-id :halt-reason :violations])
                                             (remove #(= :pass (:outcome %)) pending-results))
             :appeal-violations        (mapv #(select-keys % [:scenario-id :halt-reason :violations])
                                             (remove #(= :pass (:outcome %)) appeal-results))})))
  ([params rng]
   (run-full-kernel-validation params 5 rng)))
