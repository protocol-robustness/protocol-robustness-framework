(ns resolver-sim.benchmark.game-theory-validation
  "Game-theoretic validation orchestration.
   Runs embedded equilibrium and cancellation-dominance fixture suites,
   extracts theory/equilibrium results from each scenario, and produces
   structured summary output.

   Reuses existing pure validators from:
     - resolver-sim.scenario.equilibrium
     - resolver-sim.protocols.sew.equilibrium
     - resolver-sim.sim.fixtures (run-suite, with resource: path support)"

  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.fixtures :as fixtures]
            [resolver-sim.benchmark.strategic-claim-validation :as strategic]))

;; ── Available suite definitions ─────────────────────────────────────────────

(def equilibrium-suites
  "Registered equilibrium fixture suites embedded in the JAR.
   Suites are discovered via explicit list — no directory scan inside JAR."
  [{:suite-key :suites/equilibrium-validation
    :title "Trace-end mechanism-property and equilibrium-concept validation"
    :description "12 traces covering budget-balance, dominant-strategy, incentive-compatibility, Nash, SPE, and collusion-resistance"}
   {:suite-key :suites/cancellation-equilibrium-validation
    :title "Cancellation equilibrium validation"
    :description "9 traces covering mutual cancel, timeout auto-cancel, status leaks, and cancellation-time scenarios"}
   {:suite-key :suites/spe-validation
    :title "SPE proxy validation"
    :description "5 traces covering rational and irrational disputes, escalations, and strategic-node coverage"}
   {:suite-key :suites/spe-regression
    :title "SPE regression validation"
    :description "8 traces covering bounded SPE, backward induction, reputation, and profile-matrix regressions"}])

;; ── Equilibrium check catalog ───────────────────────────────────────────────

(def mechanism-properties
  "Available mechanism-property validators across generic and Sew-specific.
   These are merged from resolver-sim.scenario.equilibrium/mechanism-validators
   and resolver-sim.protocols.sew.equilibrium/mechanism-property-validators.

   Metadata fields:
     :catalogued?   — present in this catalogue (always true here).
     :wired?        — a validator is registered in a dispatcher map, so declaring
                      the property produces a result other than
                      :inconclusive :unsupported-concept.
     :implemented?  — an evaluation function exists somewhere in the codebase,
                      even if it is not wired into the trace-end dispatcher.
   A property may be catalogued and implemented yet not wired (e.g. the
   framework-sourced multi-epoch analyses below); declaring such a property in a
   single-trace theory block resolves to :inconclusive :unsupported-concept and
   must never be reported as passing."
  [{:id :budget-balance
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Budget balance"
    :summary "Total funds in = total funds out + protocol fees. No value creation or destruction."}
   {:id :incentive-compatibility
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Incentive compatibility"
    :summary "No actor obtains higher realised payoff through adversarial action than through honest baseline."}
   {:id :sybil-resistance
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Sybil resistance"
    :summary "An actor controlling multiple identities cannot obtain higher net payoff than through a single identity."}
   {:id :individual-rationality
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Individual rationality"
    :summary "No required honest participant has a negative net payoff."}
   {:id :collusion-resistance
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Collusion resistance"
    :summary "Labelled coalition does not profit relative to non-collusive baseline."}
   {:id :stake-flow-conservation
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Stake flow conservation"
    :summary "Resolver stakes flow correctly through lifecycle: register, freeze, slash, release."}
   {:id :budget-balance-detailed
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Budget balance (Sew-specific)"
    :summary "Sew-specific budget-balance check via the payout ledger."}
   {:id :force-refund-path-integrity
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Force refund / reversal path integrity"
    :summary "Guard-enforced refund paths exist and function correctly under resolution bypass."}
   {:id :pending-lifecycle-integrity
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Pending lifecycle integrity"
    :summary "Pending settlement lifecycle guards prevent double-finalization and stale-state claims."}
   {:id :coalition-aggregate-payoff
    :source :framework
    :catalogued? true
    :wired? false
    :implemented? true
    :title "Coalition aggregate payoff"
    :summary "Coalition-level payoff aggregation with marginal contributions and side-payment feasibility. Implemented as a multi-epoch economics helper but not wired into the trace-end dispatcher."}
   {:id :grim-trigger-stability
    :source :framework
    :catalogued? true
    :wired? false
    :implemented? true
    :title "Grim-trigger stability"
    :summary "Repeated-game grim-trigger condition: discount >= deviation-gain / (deviation-gain + punishment-loss). Implemented as a multi-epoch analysis but not wired into the trace-end dispatcher."}
   {:id :incentive-margin
    :source :framework
    :catalogued? true
    :wired? false
    :implemented? true
    :title "Incentive margin"
    :summary "U_honest - max(U_deviation). Positive margin means honest strategy is strictly preferred. Implemented as a terminal-payoff helper but not wired into the trace-end dispatcher."}])

(def equilibrium-concepts
  "Available equilibrium-concept validators across generic and Sew-specific.
   Metadata fields match mechanism-properties (:catalogued?/:wired?/:implemented?)
   plus :alias-of, which records the canonical predicate an alias delegates to.
   Aliases share the underlying predicate implementation but label results with
   their own concept keyword."
  [{:id :dominant-strategy-equilibrium
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Dominant strategy equilibrium"
    :summary "Every player has a single strategy that is optimal regardless of opponents' choices. (Single-trace metric proxy — use :empirical-strategy-dominance for bounded checks.)"}
   {:id :empirical-strategy-dominance
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :alias-of :dominant-strategy-equilibrium
    :title "Empirical strategy dominance"
    :summary "No encoded adversarial deviation improves utility on the evaluated traces (bounded empirical proxy). Reuses the dominant-strategy predicate."}
   {:id :nash-equilibrium
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Nash equilibrium"
    :summary "No player can improve their payoff by unilaterally deviating from their strategy. (Single-trace metric proxy — use :bounded-nash-diagnostic for bounded checks.)"}
   {:id :bounded-nash-diagnostic
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :alias-of :nash-equilibrium
    :title "Bounded Nash diagnostic"
    :summary "No profitable unilateral deviation among encoded alternatives at the evaluated state or strategy profile. Reuses the Nash predicate."}
   {:id :subgame-perfect-equilibrium
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Subgame perfect equilibrium (SPE)"
    :summary "Backward-induction SPE: no player has an ex-post profitable deviation at any subgame. (Trace-conditioned — use :trace-conditioned-epsilon-spe for bounded diagnostic.)"}
   {:id :trace-conditioned-epsilon-spe
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :alias-of :subgame-perfect-equilibrium
    :title "Trace-conditioned epsilon-SPE diagnostic"
    :summary "Bounded regret analysis from a single observed trace at strategic checkpoint nodes. Reuses the subgame-perfect predicate."}
   {:id :bounded-public-state-epsilon-spe
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Bounded public-state epsilon-SPE"
    :summary "Epsilon-SPE under bounded rationality: deviations must exceed epsilon threshold to count."}
   {:id :bounded-backward-induction-spe
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Bounded backward-induction SPE"
    :summary "Backward induction with bounded lookahead depth and epsilon tolerance."}
   {:id :resolver-reputation-spe
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Resolver reputation SPE"
    :summary "SPE incorporating resolver reputation penalties that deter strategic slashing."}
   {:id :resolver-reputation-profile-matrix
    :source :sew
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Resolver reputation profile matrix"
    :summary "Full profile-matrix analysis: payoff matrix across strategy profiles with reputation penalties."}
   {:id :cancellation-dominance
    :source :generic
    :catalogued? true
    :wired? true
    :implemented? true
    :title "Cancellation dominance"
    :summary "Mutual cancel strictly dominates unilateral default for honest participants."}
{:id :repeated-game/grim-trigger-deterrence
     :source :framework
     :catalogued? true
     :wired? false
     :implemented? true
     :title "Grim-trigger deterrence threshold (model-specific)"
     :summary "Model-specific grim-trigger one-shot-deviation deterrence: cooperat forever iff R/(1-δ) >= T + δP/(1-δ), i.e. δ >= (T-R)/(T-P), where R=U_honest, T=U_malicious, P is the per-period punishment payoff (default 0). Applies a fail-closed applicability contract: T>R (deviation profitable), R>P (punishment worse than cooperation), T>P (positive denominator), finite payoffs, U_honest > 0, and δ in [0,1) (δ=1 diverges). Emits a full theorem certificate with assumptions, inequality, margin, robustness distance, and a counterexample witness on failure. This is NOT a general Folk-theorem claim; it is multi-epoch only and is not wired into the single-trace dispatcher."}
    {:id :folk-theorem-cooperation-region
     :source :framework
     :catalogued? true
     :wired? false
     :implemented? true
     :alias-of :repeated-game/grim-trigger-deterrence
     :deprecated true
     :title "Repeated-game deterrence threshold (deprecated alias)"
     :summary "Deprecated alias for :repeated-game/grim-trigger-deterrence. Declaring this legacy id resolves via the canonical multi-epoch evaluator only; the single-trace dispatcher returns :inconclusive :unsupported-concept (no multi-epoch evidence input). Emits a migration classification rather than theorem support."}])

(def run-strategic-claim-validation strategic/run-strategic-claim-validation)

(defn run-held-custody-closed-form-validation
  "Emit a minimal deterministic game-theoretic validation artifact over
   first-class held custody artifacts.

   Inputs:
   - :world         world containing :held-artifacts, or
   - :held-artifacts explicit artifact collection

   This is intentionally narrow: one closed-form custody conservation claim,
   one mechanism level, deterministic checks only."
  [& {:keys [world held-artifacts out-dir]
      :or {out-dir "./prf-out/game-theory"}}]
  (let [artifacts (->> (or held-artifacts
                           (some-> world :held-artifacts vals)
                           [])
                       (sort-by :held-adjustment/id)
                       vec)
        check-results (try
                        ((requiring-resolve 'resolver-sim.assurance.custody/held-custody-closed-form-checks)
                         artifacts)
                        (catch clojure.lang.ExceptionInfo e
                          (:check-results (ex-data e))))
        verdict (if (every? #(= :pass (:status %)) check-results) :pass :fail)
        artifact {:artifact/kind :game-theoretic-validation
                  :artifact/version "game-theoretic-validation.artifact.v1"
                  :claim/id :claim/held-custody-conservation
                  :claim/title "Held custody conservation"
                  :claim/description
                  "Held custody artifacts should remain content-addressed,
                   locally conservative, non-negative after mutation, and
                   replay-consistent across the artifact sequence."
                  :benchmark/id :benchmark/held-custody-local
                  :benchmark/scenario-suite nil
                  :matched-artifacts
                  (mapv (fn [artifact]
                          {:artifact/id (:artifact/id artifact)
                           :held-adjustment/id (:held-adjustment/id artifact)
                           :held/reason (:held/reason artifact)
                           :held/action (:held/action artifact)
                           :evidence-references [{:reference/type :held-adjustment-id
                                                  :reference/value (:held-adjustment/id artifact)}
                                                 {:reference/type :held-custody-artifact-hash
                                                  :reference/value (:artifact/hash artifact)}]})
                        artifacts)
                  :level-verdicts [{:mechanism-level :custody/held-balance
                                    :verdict verdict
                                    :artifact-ids (mapv :artifact/id artifacts)
                                    :check-results check-results
                                    :evidence-references
                                    (mapv (fn [artifact]
                                            {:reference/type :held-custody-artifact-hash
                                             :reference/value (:artifact/hash artifact)})
                                          artifacts)}]
                  :coverage-gaps (if (seq artifacts)
                                   []
                                   [{:mechanism-level :custody/held-balance
                                     :reason :no-held-custody-artifacts}])
                  :summary {:matched-artifact-count (count artifacts)
                            :passed-level-count (if (= :pass verdict) 1 0)
                            :failed-level-count (if (= :fail verdict) 1 0)
                            :uncovered-level-count (if (seq artifacts) 0 1)
                            :valid? (and (seq artifacts) (= :pass verdict))}}
        base-path (str out-dir "/held-custody-conservation")
        edn-path (str base-path "/game-theoretic-validation-artifact.edn")
        json-path (str base-path "/game-theoretic-validation-artifact.json")]
    (io/make-parents edn-path)
    (spit edn-path (pr-str artifact))
    (spit json-path (json/write-str artifact {:key-fn name}))
    {:exit-code (if (get-in artifact [:summary :valid?]) 0 1)
     :artifact artifact
     :output-files [edn-path json-path]}))

;; ── Orchestration ───────────────────────────────────────────────────────────

(defn- equilibrium-check-summary
  "Count equilibrium/mechanism results by status.
   Results come from the :theory key in each scenario's fixture result."
  [results]
  (let [all (mapcat (fn [r]
                      (let [theory (:theory r)]
                        (concat
                         (vals (:mechanism-results theory))
                         (vals (:equilibrium-results theory)))))
                    results)]
    {:total (count all)
     :passed (count (filter #(= :pass (:status %)) all))
     :failed (count (filter #(= :fail (:status %)) all))
     :inconclusive (count (filter #(= :inconclusive (:status %)) all))
     :not-applicable (count (filter #(= :not-applicable (:status %)) all))}))

(defn run-equilibrium-validation
  "Run equilibrium fixture suites and return structured results.

   opts:
     :suite    — suite keyword or nil (default: run both equilibrium suites)
     :out-dir  — output directory (default ./prf-out/game-theory)
     :format   — :edn, :json, or :both (default :both)

   Returns {:exit-code int :summary map :output-files [str] :checks-skipped [...]}."
  [& {:keys [suite out-dir format]
      :or {out-dir "./prf-out/game-theory"
           format :both}}]
  (let [suites-to-run (if suite
                        (filter #(= (:suite-key %) suite) equilibrium-suites)
                        equilibrium-suites)
        _ (when (and suite (empty? suites-to-run))
            (throw (ex-info "Unknown game-theory validation suite"
                            {:suite suite
                             :known-suites (mapv :suite-key equilibrium-suites)})))
        suite-keys (mapv :suite-key suites-to-run)
        run-id (str "gt-" (java.time.Instant/now))
        effective-out-dir (str out-dir "/" run-id)
        _ (io/make-parents (io/file effective-out-dir "placeholder"))
        results (mapv (fn [sk]
                        (try
                          (let [suite-result (fixtures/run-suite-from-key sk nil nil {:silent? true})]
                            (assoc suite-result :suite-key sk))
                          (catch Exception e
                            {:suite-key sk :error (.getMessage e) :ok? false :results []})))
                      suite-keys)
        all-ok? (every? :ok? results)
        scenario-results (mapcat :results results)
        eq-summary (equilibrium-check-summary scenario-results)
        overall-pass? (and all-ok? (zero? (:failed eq-summary)))
        exit-code (if overall-pass? 0 1)
        summary {:valid? overall-pass?
                 :exit-code exit-code
                 :run-id run-id
                 :suites-executed (count suite-keys)
                 :suites-passed (count (filter :ok? results))
                 :scenario-count (count scenario-results)
                 :equilibrium-check-summary eq-summary
                 :results-by-suite (mapv (fn [r]
                                           (let [sres (remove nil? (map :theory (:results r)))]
                                             {:suite-key (:suite-key r)
                                              :status (if (:ok? r) :pass :fail)
                                              :error (:error r)
                                              :scenario-count (count (:results r))
                                              :equilibrium-summary
                                              {:mechanism (count (mapcat :mechanism-results sres))
                                               :equilibrium (count (mapcat :equilibrium-results sres))}}))
                                         results)
                 :all-checks (count (remove nil? (mapcat (fn [r]
                                                           (let [t (:theory r)]
                                                             (when t
                                                               (concat
                                                                (keys (:mechanism-results t))
                                                                (keys (:equilibrium-results t))))))
                                                         scenario-results)))}
        edn-path (str effective-out-dir "/game-theory-validation-summary.edn")
        json-path (str effective-out-dir "/game-theory-validation-summary.json")
        manifest-path (str effective-out-dir "/resolved-game-theory-validation-manifest.edn")
        manifest {:manifest/version "game-theory-validation.v1"
                  :manifest/at (str (java.time.Instant/now))
                  :suites (mapv (fn [s]
                                  {:suite-key (:suite-key s)
                                   :resource-path (str "resource:data/fixtures/suites/"
                                                       (name (:suite-key s)) ".edn")
                                   :scenario-count (count (:results s))})
                                results)}]
    (spit edn-path (pr-str summary))
    (spit json-path (json/write-str summary {:key-fn name}))
    (spit manifest-path (pr-str manifest))
    {:exit-code exit-code
     :summary summary
     :results results
     :output-files [edn-path json-path manifest-path]}))

(defn list-game-theory-checks
  "Return structured list of available mechanism properties and equilibrium concepts."
  []
  {:mechanism-properties (mapv (fn [mp]
                                 (assoc mp :type :mechanism-property))
                               mechanism-properties)
   :equilibrium-concepts (mapv (fn [ec]
                                 (assoc ec :type :equilibrium-concept))
                               equilibrium-concepts)})

(defn explain-game-theory
  "Return human-readable sections explaining what equilibrium validation covers."
  []
  [{:title "What equilibrium validation covers"
    :body "Equilibrium validation checks whether realised terminal trace outcomes are consistent with claimed economic properties. Each check is a trace-consistency test: a single replay cannot prove an equilibrium exists (that requires comparing deviations across many traces), but it can falsify one.\n\nAll results carry a :basis field declaring the strength of the check."}
   {:title "Mechanism properties"
    :body "Properties of the protocol's incentive structure checked against terminal world state and accumulated metrics:\n\n- Budget balance — total funds in = total funds out + fees\n- Incentive compatibility — no actor profits from adversarial action\n- Individual rationality — honest participants don't end with negative payoff\n- Collusion resistance — coalitions don't profit relative to baseline\n- Sybil resistance — multiple identities don't increase payoff\n- Stake flow conservation — resolver stakes flow correctly through lifecycle"}
   {:title "Equilibrium concepts"
    :body "Game-theoretic solution concepts checked against the trace:\n\n- Nash equilibrium — no unilateral profitable deviation\n- Dominant strategy — every player has a strategy optimal against all opponents\n- Subgame perfect equilibrium (SPE) — backward-induction: no ex-post profitable deviation at any subgame\n- Bounded epsilon-SPE — deviations must exceed epsilon threshold\n- Reputation SPE — resolver reputation penalties deter strategic slashing\n- Cancellation dominance — mutual cancel dominates unilateral default\n- Repeated-game deterrence threshold — grim-trigger cooperation sustainability with discount factor\n- Trace-conditioned epsilon-SPE — bounded regret analysis from single observed trace"}
   {:title "Claim-strength taxonomy"
    :body "Each result declares its evidential basis:\n\n- :single-trace-terminal-proxy — terminal world state only\n- :single-trace-metric-proxy — accumulated metrics from one trace\n- :absent-evidence — required fields missing → inconclusive\n- :not-applicable — property cannot apply in this context\n- :multi-trace-required — only meaningful across N traces\n- :multi-epoch-required — only meaningful across epochs"}
   {:title "Severity"
    :body ":hard — a :fail blocks the suite (same as scenario failure)\n:soft — a :fail is a warning; :inconclusive is always soft"}])
