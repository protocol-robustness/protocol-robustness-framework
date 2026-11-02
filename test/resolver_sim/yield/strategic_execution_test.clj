(ns resolver-sim.yield.strategic-execution-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [examples.partial-fill-replay :as demo]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.strategic-execution :as execution]
            [resolver-sim.yield.strategic-partial-fill :as strategic])
  (:import [java.security KeyPairGenerator]))

(defn- row [id owner amount]
  {:row/id id :owner/id owner :entitlement/id owner :key id
   :obligation-id id :source-position-id id :owed amount :weight amount :cap amount})

(deftest production-owned-allocation-rejects-invalid-decisions-and-minted-rights
  (let [input {:available 2 :policy {:mode :pro-rata :rounding-policy :largest-remainder}
               :rows [(row :a :alice 2) (row :b :bob 1)]}
        split [(row :a0 :alice 1) (row :a1 :alice 1) (row :b :bob 1)]
        comparison (execution/compare-owned-transformation input split)]
    (is (= :verified (:status comparison)))
    (is (= 1 (get-in comparison [:gain-by-owner :alice])))
    (is (= 2 (reduce + (vals (get-in comparison [:before :payout-by-owner])))))
    (is (= :rights-not-conserved
           (:reason (execution/compare-owned-transformation input (conj split (row :extra :alice 1))))))
    (with-redefs [pf/calculate-fulfillment-pro-rata (fn [& _] {:filled {:a 100}})]
      (is (= :inconclusive (:status (execution/allocate-owned-rows input)))))))

(defn- replay-fixture
  ([] (replay-fixture nil))
  ([retry-mode]
   (let [catalog (edn/read-string (slurp (io/resource "yield/partial-fill-recovery-game-fixtures.edn")))
         prepared (strategic/prepare-recovery-game-model
                   (merge (:defaults catalog) (first (:cases catalog))))
         base-row (assoc (row :alice :alice 10) :entitlement/id :alice-right)
         step0 {:round/index 0 :submission/id :s0 :action :claim-now :row base-row}
         step1 {:round/index 1 :submission/id :s1 :action :claim-now
                :row (assoc base-row :owed 5 :weight 5 :cap 5)}
         policy {:mode :pro-rata :rounding-policy :largest-remainder}
         model {:schema-version "partial-fill-strategic-execution.v1"
                :recovery-model/root (strategic/recovery-game-model-root (:model prepared))
                :ordering :fixed-public-order :cost-schedule :zero :initial-cash 5
                :policy policy :submission-order [:s0 :s1]
                :plans {:alice {:claim (if retry-mode
                                         [step0 (if (= retry-mode :changed)
                                                  (assoc-in step0 [:row :weight] 9) step0) step1]
                                         [step0 step1])}
                        :bob {:wait []} :carol {:wait []}}
                :branches [{:branch/id :deterministic :probability-bps 10000
                            :deposits [{:event/id :credit :round/index 1 :amount 5}]}]}
         executable (execution/prepare-execution-model prepared model)
         keys (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
         encoded (.getEncoded (.getPublic keys))
         public (apply str (map #(format "%02x" (bit-and 255 %)) (take-last 32 encoded)))
         trust {:trusted-keys [{:key/id :permission :key/public public
                                :key/status :active :key/role execution/permission-role}]}
         initial (execution/initial-state executable)
         build (fn [state step]
                 (let [r (:row step)
                       decision (:decision (execution/allocate-owned-rows
                                            {:available (:cash state) :policy policy :rows [r]}))
                       grant (signed/sign-envelope
                              {:schema-version "partial-fill-state-permission.v1"
                               :permission/status :permitted :execution/root (:execution/root executable)
                               :player/id :alice :entitlement/id :alice-right :action :claim-now
                               :round/index (:round/index step) :pre-state/root (execution/state-root state)
                               :priority :alice :amount-limit (:owed r) :cap-limit (:cap r)}
                              execution/permission-domain (.getPrivate keys) :permission)
                       root (execution/content-root :partial-fill-state-permission.v1 grant)
                       post (-> state (update :cash - 5)
                                (update-in [:balances :alice-right] - 5)
                                (update-in [:payments :alice] (fnil conj []) {:amount 5 :round/index (:round/index step)})
                                (assoc-in [:submissions [:alice-right (:submission/id step)]]
                                          {:step step :decision decision}))]
                   {:post post :grant grant :root root
                    :assertion (merge (select-keys step [:round/index :submission/id :action])
                                      {:pre-state/root (execution/state-root state)
                                       :post-state/root (execution/state-root post)
                                       :permission/root root :decision decision})}))
         first-step (build initial step0)
         second-step (build (-> (:post first-step) (update :cash + 5) (update :credited conj :credit)) step1)
         trace {:schema-version "partial-fill-execution-trace.v1" :branch/id :deterministic
                :actions {:alice :claim :bob :wait :carol :wait}
                :execution/root (:execution/root executable) :initial-state/root (execution/state-root initial)
                :steps (if retry-mode
                         [(:assertion first-step)
                          (assoc (:assertion first-step)
                                 :pre-state/root (execution/state-root (:post first-step)))
                          (:assertion second-step)]
                         [(:assertion first-step) (:assertion second-step)])}
         opts {:permission-trust-policy trust
               :permission-sources {(:root first-step) (:grant first-step) (:root second-step) (:grant second-step)}}]
     {:prepared prepared :model model :executable executable :trace trace :opts opts
      :choices {:alice :claim :bob :wait :carol :wait}})))

(deftest replay-retries-do-not-pay-twice-and-changed-retries-are-rejected
  (doseq [mode [:exact :changed]]
    (let [{:keys [executable trace opts choices]} (replay-fixture mode)
          result (execution/replay-profile executable choices :deterministic trace opts)]
      (if (= mode :changed)
        (is (= :changed-retry (:reason result)))
        (do (is (= :verified (:status result)))
            (is (= 2 (count (get-in result [:outcome :by-player :alice :payments])))))))))

(deftest replay-derives-two-round-payments-and-rejects-tampering-before-utility
  (let [{:keys [prepared model executable trace opts choices]} (replay-fixture)
        result (execution/replay-profile executable choices :deterministic trace opts)
        game {:actions {:alice [:claim] :bob [:wait] :carol [:wait]}
              :profiles [{:actions choices :traces-by-id {:deterministic trace}}]}
        analysis (strategic/analyze-replayed-recovery-game-profiles prepared model game opts)]
    (is (= :verified (:status executable)))
    (is (= :verified (:status result)))
    (is (= [{:amount 5 :round/index 0} {:amount 5 :round/index 1}]
           (get-in result [:outcome :by-player :alice :payments])))
    (is (= 9990 (get-in result [:terminal-state :balances :alice-right])))
    (is (= 0 (get-in result [:terminal-state :cash])))
    (is (= :analyzed (:status analysis)))
    (is (= :assumption-trust-policy-required
           (:reason (strategic/analyze-replayed-recovery-game-profiles
                     prepared model game (assoc opts :require-authenticated-assumptions? true)))))
    (is (= :invalid-or-incomplete-payoff-table
           (:reason (strategic/analyze-replayed-recovery-game-profiles
                     prepared model (assoc game :profiles [{:actions 42}]) opts))))
    (is (true? (get-in analysis [:execution-assurance :settlement-replayed?])))
    (is (false? (get-in analysis [:execution-assurance :outcomes-authenticated?])))
    (let [calls (atom 0)]
      (with-redefs [strategic/evaluate-recovery-game-outcome (fn [& _] (swap! calls inc))]
        (is (= :profile-execution-invalid
               (:reason (strategic/analyze-replayed-recovery-game-profiles
                         prepared model (assoc-in game [:profiles 0 :traces-by-id :deterministic :steps 0 :post-state/root] "wrong") opts))))
        (is (zero? @calls))))
    (is (= :permission-signature-invalid
           (:reason (execution/replay-profile executable choices :deterministic trace
                                              (assoc opts :permission-trust-policy {:trusted-keys []})))))))

(deftest signed-replay-propagates-assurance-through-pure-mixed-and-stochastic-solvers
  (doseq [stochastic? [false true] mixed? [false true]]
    (let [{:keys [prepared model game opts]} (demo/build-example stochastic?)
          result (strategic/analyze-replayed-recovery-game-profiles prepared model game (assoc opts :mixed? mixed?))]
      (is (= :analyzed (:status result)))
      (is (true? (:utility-assumptions-authenticated? result)))
      (is (= {:outcome-source :verified-replay :settlement-replayed? true
              :outcomes-authenticated? false :state-dependent-permissions-verified? true}
             (:execution-assurance result)))
      (is (string? (:execution-evidence/root result)))
      (when (and stochastic? (not mixed?))
        (is (= 15/2 (get-in result [:profile-analysis 0 :utilities :alice])))))))

(deftest replay-adversarial-inputs-fail-before-utility-evaluation
  (let [{:keys [prepared model game opts]} (demo/build-example true)
        calls (atom 0)
        run (fn [model game opts]
              (strategic/analyze-replayed-recovery-game-profiles prepared model game opts))
        first-root (get-in game [:profiles 0 :traces-by-id :low :steps 0 :permission/root])
        grant (get-in opts [:permission-sources first-root])]
    (with-redefs [strategic/evaluate-recovery-game-outcome (fn [& _] (swap! calls inc))]
      (doseq [[bad-model bad-game bad-opts expected]
              [[model game (assoc opts :max-replays 1) :profile-budget-exceeded]
               [model game (assoc opts :max-profiles 0) :invalid-profile-budget]
               [model (update game :profiles #(conj % (first %))) opts :invalid-or-incomplete-payoff-table]
               [model (update-in game [:profiles 0 :traces-by-id] dissoc :low) opts :invalid-or-incomplete-payoff-table]
               [(update-in model [:branches 0 :deposits] #(conj % (first %))) game opts :branch-invalid]
               [model game (assoc opts :permission-sources {}) :profile-execution-invalid]
               [model game (assoc-in opts [:permission-trust-policy :trusted-keys 0 :key/role] :wrong-role) :profile-execution-invalid]
               [model game (assoc-in opts [:permission-trust-policy :trusted-keys 0 :key/status] :revoked) :profile-execution-invalid]
               [model game (assoc-in opts [:permission-sources first-root] (assoc grant :pre-state/root "stale")) :profile-execution-invalid]
               [model (assoc-in game [:profiles 0 :traces-by-id :low :initial-state/root] "changed") opts :profile-execution-invalid]]]
        (is (= expected (:reason (run bad-model bad-game bad-opts)))))
      (is (zero? @calls)))))

(deftest owned-weighted-caps-permutations-and-duplicate-buckets
  (let [rows [(assoc (row :a :alice 10) :weight 3 :cap 2)
              (assoc (row :b :bob 10) :weight 1 :cap 10)]
        input {:available 6 :policy {:mode :pro-rata :rounding-policy :largest-remainder
                                     :fail-action-policy {:mode :pro-rata-treatment
                                                          :deferred-policy :contractual
                                                          :haircut-policy :contractual}} :rows rows}
        allocation (execution/allocate-owned-rows input)
        permutation (execution/compare-owned-transformation input (vec (reverse rows)))]
    (is (= :verified (:status allocation)))
    (is (= {:alice 2 :bob 4} (:payout-by-owner allocation)))
    (is (= :verified (:status permutation)))
    (is (= {:alice 0 :bob 0} (:gain-by-owner permutation)))
    (doseq [mode [:pro-rata :principal-first :waterfall]]
      (is (= :row-identity-not-supported
             (:reason (execution/allocate-owned-rows
                       (assoc input :policy {:mode mode :rounding-policy :floor :fill-order [:a]}
                              :rows [(first rows) (assoc (second rows) :key :a)]))))))))

(deftest correctly-signed-stale-grants-and-excessive-requests-are-rejected
  (let [{:keys [prepared model game opts sign-permission]} (demo/build-example)
        trace (get-in game [:profiles 0 :traces-by-id :deterministic])
        source (get-in trace [:steps 0 :permission/root])
        grant (sign-permission (assoc (get-in opts [:permission-sources source]) :pre-state/root "stale"))
        root (execution/content-root :partial-fill-state-permission.v1 grant)
        stale-game (assoc-in game [:profiles 0 :traces-by-id :deterministic :steps 0 :permission/root] root)
        stale-opts (assoc-in opts [:permission-sources root] grant)
        excessive-model (-> model (assoc-in [:plans :alice :claim 0 :row :owed] 11)
                            (assoc-in [:plans :alice :claim 0 :row :cap] 11))
        excessive (execution/prepare-execution-model prepared excessive-model)
        excessive-game (assoc-in game [:profiles 0 :traces-by-id :deterministic :execution/root] (:execution/root excessive))
        calls (atom 0)]
    (with-redefs [strategic/evaluate-recovery-game-outcome (fn [& _] (swap! calls inc))]
      (doseq [[m g o reason] [[model stale-game stale-opts :permission-context-mismatch]
                              [excessive-model excessive-game opts :entitlement-exceeded]]]
        (let [result (strategic/analyze-replayed-recovery-game-profiles prepared m g o)]
          (is (= :profile-execution-invalid (:reason result)))
          (is (= reason (get-in result [:failures 0 :reason])))))
      (is (zero? @calls)))))

(deftest infeasible-joint-profile-stops-the-whole-normal-form-analysis
  (let [{:keys [prepared model game opts sign-permission]} (demo/build-example)
        model (assoc-in model [:plans :alice :wait] [])
        executable (execution/prepare-execution-model prepared model)
        old-trace (get-in game [:profiles 0 :traces-by-id :deterministic])
        grants (mapv (fn [step]
                       (let [grant (sign-permission
                                    (assoc (get-in opts [:permission-sources (:permission/root step)])
                                           :execution/root (:execution/root executable)))]
                         [(execution/content-root :partial-fill-state-permission.v1 grant) grant])) (:steps old-trace))
        trace (assoc old-trace :execution/root (:execution/root executable)
                     :steps (mapv (fn [step [root _]] (assoc step :permission/root root)) (:steps old-trace) grants))
        wait-choices {:alice :wait :bob :claim}
        wait-trace (assoc trace :actions wait-choices
                          :steps [(assoc (second (:steps trace)) :pre-state/root
                                         (get-in trace [:steps 0 :pre-state/root]))])
        game {:actions {:alice [:claim :wait] :bob [:claim]}
              :profiles [{:actions (:actions trace) :traces-by-id {:deterministic trace}}
                         {:actions wait-choices :traces-by-id {:deterministic wait-trace}}]}
        calls (atom 0)]
    (with-redefs [strategic/evaluate-recovery-game-outcome (fn [& _] (swap! calls inc))]
      (let [result (strategic/analyze-replayed-recovery-game-profiles
                    prepared model game (assoc opts :permission-sources (into {} grants)))]
        (is (= :profile-execution-invalid (:reason result)))
        (is (= [wait-choices] (mapv :actions (:failures result))))
        (is (= :permission-context-mismatch (get-in result [:failures 0 :reason])))
        (is (zero? @calls))))))

(deftest signed-static-permissions-cannot-replace-state-bound-grants
  (let [{:keys [prepared model game opts static-permission-trust-policy]} (demo/build-example)
        calls (atom 0)]
    (is (:valid? (strategic/verify-recovery-game-action-permissions
                  (:model prepared) static-permission-trust-policy)))
    (with-redefs [strategic/evaluate-recovery-game-outcome (fn [& _] (swap! calls inc))]
      (let [result (strategic/analyze-replayed-recovery-game-profiles
                    prepared model game
                    (assoc opts :action-permission-trust-policy static-permission-trust-policy
                           :permission-sources {}))]
        (is (= :profile-execution-invalid (:reason result)))
        (is (= :permission-unresolved (get-in result [:failures 0 :reason])))
        (is (zero? @calls))))))

(deftest authenticated-caller-outcomes-retain-narrow-assurance-in-both-adapters
  (let [{:keys [prepared opts static-permission-trust-policy]} (demo/build-example)
        outcome {:by-player (into {} (map (fn [p] [p {:payments [] :delay-rounds 0 :fees 0
                                                      :expected-unrecovered 10 :priority-externality 0 :penalty-count 0}])
                                          [:alice :bob]))}
        game {:actions {:alice [:claim-now] :bob [:claim-now]}
              :profiles [{:actions {:alice :claim-now :bob :claim-now} :outcome outcome}]}
        opts (assoc opts :require-validated-execution? false
                    :action-permission-trust-policy static-permission-trust-policy)]
    (doseq [analyzer [strategic/analyze-recovery-game-profiles strategic/analyze-two-player-recovery-game-profiles]]
      (let [result (analyzer prepared game opts)]
        (is (= :analyzed (:status result)))
        (is (true? (:utility-assumptions-authenticated? result)))
        (is (true? (:action-permissions-authenticated? result)))
        (is (= {:outcome-source :caller-supplied :settlement-replayed? false
                :outcomes-authenticated? false :state-dependent-permissions-verified? false}
               (:execution-assurance result)))))))
