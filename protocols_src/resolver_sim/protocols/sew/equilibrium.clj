(ns resolver-sim.protocols.sew.equilibrium
  "Sew-specific mechanism-property and equilibrium-concept validators.
   These validators are registered with evaluate-mechanism-properties and
   evaluate-equilibrium-concepts via SewProtocol's mechanism-property-validators
   and equilibrium-concept-validators methods.
   Sew-specific validators included here:
     Mechanism properties:
       :individual-rationality       — uses terminal-payoff canonical model
       :budget-balance-detailed      — uses terminal-payoff decomposition"
   (:require [resolver-sim.scenario.subgame-counterfactual :as subgame-cf]
             [resolver-sim.economics.terminal-payoff :as tp]))

;; ---------------------------------------------------------------------------
;; Shared result constructors (mirrors scenario.equilibrium — kept local to
;; avoid a circular dependency between sew/* and scenario/*)
;; ---------------------------------------------------------------------------

(def ^:private property-class
  {:individual-rationality              :validation.class/payoff-property
   :collusion-resistance                :validation.class/deviation-resistance
   :stake-flow-conservation             :validation.class/algebraic-integrity
   :subgame-perfect-equilibrium         :validation.class/equilibrium
   :trace-conditioned-epsilon-spe       :validation.class/equilibrium
   :bounded-public-state-epsilon-spe    :validation.class/equilibrium
   :bounded-backward-induction-spe      :validation.class/equilibrium
   :resolver-reputation-spe             :validation.class/equilibrium
   :resolver-reputation-profile-matrix  :validation.class/equilibrium
   :budget-balance                      :validation.class/payoff-property
   :budget-balance-detailed             :validation.class/payoff-property
   :force-refund-path-integrity         :validation.class/algebraic-integrity
   :force-reversal-path-integrity       :validation.class/algebraic-integrity
   :pending-lifecycle-integrity         :validation.class/algebraic-integrity
   :cancellation-dominance              :validation.class/deviation-resistance})

(defn- pass [property basis observed expected]
  {:property  property
   :status    :pass
   :severity  :hard
   :basis     basis
   :observed  observed
   :expected  expected
   :offending []
   :requires  []
   :validation-class (get property-class property)})

(defn- fail [property basis observed expected offending]
  {:property  property
   :status    :fail
   :severity  :hard
   :basis     basis
   :observed  observed
   :expected  expected
   :offending (vec offending)
   :requires  []
   :validation-class (get property-class property)})

(defn- inconclusive [property basis reason]
  {:property  property
   :status    :inconclusive
   :severity  :soft
   :basis     basis
   :observed  nil
   :expected  nil
   :offending []
   :requires  [reason]
   :validation-class (get property-class property)})

(defn- not-applicable [property reason]
  {:property  property
   :status    :not-applicable
   :severity  :soft
   :basis     :not-applicable
   :observed  nil
   :expected  nil
   :offending []
   :requires  [reason]})

;; ---------------------------------------------------------------------------
;; Sew mechanism-property validators
;; ---------------------------------------------------------------------------

(defn- check-individual-rationality
  "No required honest participant ends with a net payoff below the
   outside-option utility.

   Uses the terminal-payoff canonical IR check when per-actor ledger
   data is available. Falls back to negative-payoff-count metric or
   funds-lost proxy when the ledger is absent.

   :pass when every participant's net payoff >= outside-option (default 0)."
  [{:keys [metrics payoff-ledger-summary outside-option-definition-root]}]
  (let [ledger (get payoff-ledger-summary :per-actor {})]
    (if (seq ledger)
      ;; Canonical IR check via terminal-payoff model
      (let [results (mapv (fn [[actor row]]
                            (let [net (long (:net-payoff row 0))
                                  ir (tp/ir-check net :definition-root outside-option-definition-root)]
                              (assoc ir :actor actor)))
                          ledger)
            failures (filter #(not (:rational? %)) results)]
        (if (empty? failures)
          (pass :individual-rationality :single-trace-metric-proxy
                {:actors-evaluated (count ledger)
                 :ir-results results
                 :outside-option-definition-root outside-option-definition-root}
                "all participants meet or exceed outside-option utility")
          (fail :individual-rationality :single-trace-metric-proxy
                {:actors-evaluated (count ledger)
                 :ir-results results
                 :outside-option-definition-root outside-option-definition-root}
                {:ir-results (mapv #(select-keys % [:actor :net :outside-option :deficit]) failures)}
                (mapv (fn [f] {:actor (:actor f) :deficit (:deficit f)}) failures))))
      ;; Fallback: negative-payoff-count metric (pre-coordinated metric)
      (let [npc (:negative-payoff-count metrics)]
        (if (some? npc)
          (if (zero? npc)
            (pass :individual-rationality :single-trace-metric-proxy
                  {:negative-payoff-count npc}
                  "no participant ended with negative net payoff (metric proxy)")
            (fail :individual-rationality :single-trace-metric-proxy
                  {:negative-payoff-count npc}
                  {:negative-payoff-count 0}
                  [{:metric :negative-payoff-count :observed npc}]))
          ;; Fallback: funds-lost proxy
          (let [lost (:funds-lost metrics 0)]
            (if (pos? lost)
              (fail :individual-rationality :single-trace-metric-proxy
                    {:funds-lost lost}
                    {:funds-lost 0}
                    [{:metric :funds-lost :observed lost
                      :note "partial proxy — full payoff-ledger not tracked"}])
              (inconclusive :individual-rationality :absent-evidence
                            "payoff-ledger not tracked; cannot fully evaluate individual rationality"))))))))

(defn- check-collusion-resistance
  "Labelled coalition does not profit relative to non-collusive baseline.

   Uses the Sew coalition-net-profit metric.
   :inconclusive for single-trace (requires multi-epoch runner or population data)."
  [{:keys [metrics payoff-ledger-summary]}]
  (let [cnp (:coalition-net-profit metrics)]
    (if (nil? cnp)
      (inconclusive :collusion-resistance :multi-trace-required
                    "coalition-net-profit metric absent; requires multi-epoch batch runner")
      (if (<= cnp 0)
        (pass :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp
               :ledger-coalition-net-profit (get payoff-ledger-summary :coalition-net-profit)}
              "coalition net profit ≤ 0")
        (fail :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp
               :ledger-coalition-net-profit (get payoff-ledger-summary :coalition-net-profit)}
              {:coalition-net-profit "≤ 0"}
              [{:metric :coalition-net-profit :observed cnp}])))))

(defn- check-stake-flow-conservation
  "For each resolver: start - withdrawn - slashed == end.
   Uses the Sew resolver-stakes world field via stake-flow-summary."
  [{:keys [stake-flow-summary]}]
  (let [violations (->> stake-flow-summary
                        (keep (fn [[resolver {:keys [start withdrawn slashed end]}]]
                                (let [lhs (- (long (or start 0)) (long (or withdrawn 0)) (long (or slashed 0)))]
                                  (when (not= lhs (long (or end 0)))
                                    {:resolver resolver :start start :withdrawn withdrawn :slashed slashed :end end :expected-end lhs}))))
                        vec)]
    (if (seq violations)
      (fail :stake-flow-conservation :single-trace-metric-proxy
            {:stake-flow-summary stake-flow-summary}
            "start - withdrawn - slashed must equal end stake"
            violations)
      (pass :stake-flow-conservation :single-trace-metric-proxy
            {:resolver-count (count stake-flow-summary)}
            "stake flow balances for all resolvers"))))

;; ---------------------------------------------------------------------------
;; Sew equilibrium-concept validators (all delegate to subgame-cf)
;; ---------------------------------------------------------------------------

(defn- check-subgame-perfect-equilibrium
  "Heuristic: No Profitable Regret (Trace-level SPE Proxy).
   Delegates to resolver-sim.scenario.subgame-counterfactual.

   Takes an optional `eq-concept` keyword (default :subgame-perfect-equilibrium)
   so alias concepts (e.g. :trace-conditioned-epsilon-spe) can reuse this
   predicate while still being labelled with the concept that was requested."
  ([projection] (check-subgame-perfect-equilibrium projection :subgame-perfect-equilibrium))
  ([projection eq-concept]
  (let [spe-result-map (subgame-cf/evaluate-subgame-counterfactual projection)
        {:keys [status basis regret-table max-regret mean-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec class-counts
                exceed-epsilon-count regret-distribution epsilon-abs epsilon-rel
                max-deviation-depth memoization
                spe-result result-strength strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage candidate-deviations coverage]}
        spe-result-map
        continuation-mode (:continuation/mode spe-result-map)
        proof-sketch (str
                      "Claim: Bounded public-state SPE proxy under declared strategy profile "
                      (or (:id strategy-profile) "unknown") ".\n\n"
                      "Method:\n"
                      "  - continuation-policy: " (or (:mode continuation-policy) :unknown)
                      " (version " (or (:version continuation-policy) "unknown") ")\n"
                      "  - utility-spec: " (or (:type utility-spec) :unknown)
                      " (version " (or (:version utility-spec) "unknown") ")\n"
                      "  - max deviation depth: " (long (or max-deviation-depth 1)) "\n"
                      "  - epsilon: abs=" epsilon-abs ", rel=" epsilon-rel "\n\n"
                      "Note: Alternatives are heuristic utility estimates under the configured "
                      "continuation policy, not independent protocol replays. Regret values are "
                      "proxy measurements; this is not a formal SPE proof.\n\n"
                      "Checked:\n"
                      "  - " (long (or proper-subgames-checked 0)) " proper subgame node(s)\n"
                      "  - " (long (or information-set-nodes-checked 0)) " information-set node(s) (inconclusive)\n"
                      "  - " (long (or not-checkable-nodes 0)) " not-checkable node(s)\n"
                      "  - memoization: " (if (get-in memoization [:enabled])
                                            (str "enabled, entries=" (long (or (get memoization :entries) 0))
                                                 ", hits=" (long (or (get memoization :hits) 0)))
                                            "disabled")
                      "\n\n"
                      "Result:\n"
                      (case status
                        :pass   (str "  - No profitable deviation exceeded epsilon = " epsilon-abs "\n"
                                     "  - Max regret: " max-regret "\n"
                                     "  - SPE result: " spe-result)
                        :fail   (str "  - Profitable deviation detected\n"
                                     "  - Max regret: " max-regret " (threshold: " threshold ")\n"
                                     "  - SPE result: " spe-result "\n"
                                     "  - Counterexamples: " (count counterexamples))
                        (str "  - Inconclusive: " (or (first requires) "evidence unavailable") "\n"
                             "  - SPE result: " spe-result)))
        observed {:spe-status      status
                  :spe-result      spe-result
                  :spe-summary     (case status
                                     :pass (str "bounded counterfactual regret <= threshold across " checked-nodes " node(s)")
                                     :fail (str "bounded counterfactual regret exceeds threshold at one or more nodes")
                                     :inconclusive (or (first requires) "counterfactual evidence unavailable")
                                     "counterfactual evidence unavailable")
                  :spe-regret-table regret-table
                  :spe-max-regret   max-regret
                  :spe-mean-regret  mean-regret
                  :spe-threshold    threshold
                  :spe-epsilon-abs epsilon-abs
                  :spe-epsilon-rel epsilon-rel
                  :spe-max-deviation-depth max-deviation-depth
                  :spe-continuation-policy continuation-policy
                  :spe-replay-boundary replay-boundary
                  :spe-utility-spec utility-spec
                  :spe-strategy-profile strategy-profile
                  :spe-proper-subgames-checked proper-subgames-checked
                  :spe-information-set-nodes-checked information-set-nodes-checked
                  :spe-not-checkable-nodes not-checkable-nodes
                  :spe-class-counts class-counts
                  :spe-exceed-epsilon-count exceed-epsilon-count
                  :spe-memoization memoization
                  :spe-regret-distribution regret-distribution
                   :spe-counterexamples (vec counterexamples)
                   :spe-off-path-coverage off-path-coverage
                   :result-strength result-strength
                   :candidate-deviations candidate-deviations
                   :coverage coverage
                   :continuation/mode continuation-mode
                   :spe-proof-sketch proof-sketch
                   :decisions-checked checked-nodes
                   :spe-violations   (vec (filter (fn [r] (pos? (long (or (:local-regret r) 0)))) regret-table))}]
    (case status
      :pass
      (pass eq-concept basis
            observed
            {:spe-status :pass :max-regret (str "<= " threshold)})

      :fail
      (fail eq-concept basis
            observed
            {:spe-status :pass :max-regret (str "<= " threshold)}
            (:spe-violations observed))

      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable"))))))

(defn- check-bounded-public-state-epsilon-spe
  "Phase K: Bounded public-state epsilon-SPE proxy.
   Delegates to resolver-sim.scenario.subgame-counterfactual."
  [projection]
  (let [spe-result-map (subgame-cf/evaluate-subgame-counterfactual projection)
        {:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result result-strength strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage candidate-deviations coverage
                epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret]}
        spe-result-map
        continuation-mode (:continuation/mode spe-result-map)
        eq-concept :bounded-public-state-epsilon-spe]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :candidate-deviations candidate-deviations
             :coverage coverage
             :continuation/mode continuation-mode
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :candidate-deviations candidate-deviations
             :coverage coverage
             :continuation/mode continuation-mode
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-bounded-backward-induction-spe
  "Phase K: Bounded backward-induction epsilon-SPE proxy.
   Delegates to resolver-sim.scenario.subgame-counterfactual."
  [projection]
  (let [projection' (update projection :spe-config assoc :evaluation-mode :backward-induction)
        {:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result result-strength strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret
                evaluation-mode backward-induction-depth
                deviation-terminal-count deviation-continuation-count]}
        (subgame-cf/evaluate-subgame-counterfactual projection')
        eq-concept :bounded-backward-induction-spe]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes
             :evaluation-mode evaluation-mode
             :backward-induction-depth backward-induction-depth
             :deviation-terminal-count deviation-terminal-count
             :deviation-continuation-count deviation-continuation-count}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile
             :evaluation-mode evaluation-mode
             :deviation-terminal-count deviation-terminal-count
             :deviation-continuation-count deviation-continuation-count}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-resolver-reputation-spe
  "Reputation-aware epsilon-SPE proxy (Gap D).
   Delegates to resolver-sim.scenario.subgame-counterfactual with
   :resolver-reputation-v1 utility-spec."
  [projection]
  (let [projection' (update projection :spe-config
                            (fn [cfg]
                              (update cfg :utility-spec
                                      (fn [us] (merge us {:type :resolver-reputation-v1})))))
        {:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result result-strength strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret
                evaluation-mode min-reputation-penalty-for-spe-pass]}
        (subgame-cf/evaluate-subgame-counterfactual projection')
        eq-concept :resolver-reputation-spe
        penalty    (get-in projection' [:spe-config :utility-spec :reputation-slash-penalty] 0)
        slash-detected-count (count (filter #(get-in % [:utility-breakdown :slash-detected?])
                                            regret-table))]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes
             :utility-type :resolver-reputation-v1
             :reputation-slash-penalty penalty
             :slash-detected-count slash-detected-count
             :min-reputation-penalty-for-spe-pass min-reputation-penalty-for-spe-pass}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :result-strength result-strength
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile
             :utility-type :resolver-reputation-v1
             :reputation-slash-penalty penalty
             :slash-detected-count slash-detected-count
             :min-reputation-penalty-for-spe-pass min-reputation-penalty-for-spe-pass}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-resolver-reputation-profile-matrix
  "Profile-matrix reputation SPE validator.
   Delegates to resolver-sim.scenario.subgame-counterfactual."
  [projection]
  (let [eq-concept :resolver-reputation-profile-matrix
        spe-config (:spe-config projection {})
        profiles   (get spe-config :utility-profiles [])]
    (if (empty? profiles)
      (inconclusive eq-concept :absent-evidence
                    "no utility-profiles declared in spe-config; add :utility-profiles vector")
      (let [{:keys [profile-results min-profile-required any-pass? all-pass? fail-profiles]}
            (subgame-cf/run-profile-matrix projection profiles)]
        (cond
          (zero? (count (filter #(pos? (or (:proper-subgames-checked %) 0))
                                profile-results)))
          (inconclusive eq-concept :absent-evidence
                        "no proper subgames found across any profile; all nodes were information-set or not-checkable")

          all-pass?
          (pass eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required min-profile-required
                 :fail-profiles        fail-profiles
                 :any-pass?            any-pass?
                 :all-pass?            all-pass?
                 :profile-count        (count profiles)}
                {:all-pass? true})

          any-pass?
          (fail eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required min-profile-required
                 :fail-profiles        fail-profiles
                 :any-pass?            any-pass?
                 :all-pass?            all-pass?
                 :profile-count        (count profiles)
                 :interpretation       (str "Strategy is incentive-compatible only under profiles: "
                                            (pr-str (mapv :profile-id (filter #(= :pass (:status %)) profile-results)))
                                            ". Fails under: " (pr-str fail-profiles))}
                {:all-pass? true}
                (mapv (fn [pr]
                        {:metric :profile-spe-fail
                         :profile-id (:profile-id pr)
                         :max-regret (:max-regret pr)})
                      (filter #(= :fail (:status %)) profile-results)))

          :else
          (fail eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required nil
                 :fail-profiles        fail-profiles
                 :any-pass?            false
                 :all-pass?            false
                 :profile-count        (count profiles)
                 :interpretation       "Strategy fails under all declared profiles."}
                {:all-pass? true}
                (mapv (fn [pr]
                        {:metric :profile-spe-fail
                         :profile-id (:profile-id pr)
                         :max-regret (:max-regret pr)})
                      (filter #(= :fail (:status %)) profile-results))))))))

;; ---------------------------------------------------------------------------
;; Sew-specific mechanism-property validators (moved from scenario/equilibrium)
;; ---------------------------------------------------------------------------

(defn- check-budget-balance
  "No residual protocol-held funds remain after all relevant escrows reach
   terminal states, excluding explicitly retained fees.

   :not-applicable when escrows are still open (terminal? = false) or
   when the scenario used allow-open-disputes?.
   :pass when every token's total-held-by-token = 0.
   :fail when any token still holds funds."
  [{:keys [terminal-world trace-summary]}]
  (let [halt     (:halt-reason trace-summary)
        terminal (:terminal? terminal-world)]
    (cond
      (#{:open-entities-at-end :open-disputes-at-end} halt)
      (not-applicable :budget-balance "scenario allows open disputes at end")

      (not terminal)
      (not-applicable :budget-balance "non-terminal escrows remain; held funds are expected")

      :else
      (let [held (:total-held-by-token terminal-world {})]
        (if (every? #(zero? (val %)) held)
          (pass :budget-balance :single-trace-terminal-proxy
                held "all total-held-by-token values equal zero when all escrows terminal")
          (let [offending (filterv (fn [[_ v]] (pos? v)) held)]
            (fail :budget-balance :single-trace-terminal-proxy
                  held {:EXPECTED "all token balances zero" :actual held}
                  offending)))))))

(defn- check-budget-balance-detailed
  "Detailed budget balance check using the canonical terminal-payoff model.
   Verifier that the sum of net payoffs across all participant types equals
   zero (or is within an allowed epsilon for integer rounding).
   Uses `resolver-payoff`, `claimant-payoff`, and `protocol-payoff` from
   the terminal-payoff model when trace-level payoff data is available.
   Falls back to the simple stock check when breakdown data is absent."
  [{:keys [metrics payoff-ledger-summary terminal-world]}]
  (let [held (:total-held-by-token terminal-world {})]
    (if-let [per-actor (get payoff-ledger-summary :per-actor {})]
      ;; Use terminal-payoff model when per-actor ledger is available
      (let [participants (mapv (fn [[actor row]]
                                 {:role :resolver
                                  :actor actor
                                  :net (long (:net-payoff row 0))})
                               per-actor)
            bb (tp/budget-balance-check participants :epsilon 1)]
        (if (:balanced? bb)
          (pass :budget-balance-detailed :single-trace-terminal-proxy
                bb "net payoffs sum to zero within rounding epsilon")
          (fail :budget-balance-detailed :single-trace-terminal-proxy
                bb {:balanced? true}
                [{:imbalance (:imbalance bb) :sum (:sum bb)}])))
      ;; Fallback: simple stock check
      (if (every? #(zero? (val %)) held)
        (pass :budget-balance-detailed :single-trace-terminal-proxy
              held "all token balances zero (terminal-payoff breakdown absent)")
        (let [offending (filterv (fn [[_ v]] (pos? v)) held)]
          (fail :budget-balance-detailed :single-trace-terminal-proxy
                held {:expected "all token balances zero"} offending))))))

(defn- check-force-refund-path-integrity
  "Ensure no workflow marked :refunded is also marked as release path.
   Placeholder integrity check over projection-level workflow outcomes."
  [{:keys [money-movement-summary]}]
  (let [outcomes (get money-movement-summary :workflow-outcomes {})
        bad      (->> outcomes
                      (filter (fn [[_ {:keys [terminal-state path]}]]
                                (and (= :refunded terminal-state)
                                     (= :release path))))
                      (mapv first))]
    (if (seq bad)
      (fail :force-refund-path-integrity :single-trace-terminal-proxy
            {:workflow-outcomes outcomes}
            "refunded workflows must not have release path"
            bad)
      (pass :force-refund-path-integrity :single-trace-terminal-proxy
            {:workflow-count (count outcomes)}
            "all refunded workflows preserve refund-only terminal path"))))

(defn- check-force-reversal-path-integrity
  "Ensure force-reversal slash entries reference an actual workflow and
   have a valid slash amount.  Placeholder integrity check over the
   pending-fraud-slashes produced by force-reversal-slash."
  [{:keys [pending-fraud-slashes]}]
  (let [force-reversals (->> pending-fraud-slashes
                             (filter (fn [[_ slash]]
                                       (= :force-reversal (:slash/kind slash)))))
        bad (->> force-reversals
                 (filter (fn [[_ slash]] (not (pos? (:amount slash 0)))))
                 (mapv first))]
    (if (seq bad)
      (fail :force-reversal-path-integrity :single-trace-terminal-proxy
            {:force-reversal-slashes force-reversals}
            "force-reversal slash entries must have positive amount"
            bad)
      (pass :force-reversal-path-integrity :single-trace-terminal-proxy
            {:force-reversal-count (count force-reversals)}
            (str (count force-reversals) " force-reversal slash(es) have valid amounts")))))

(defn- check-pending-lifecycle-integrity
  "Pending lifecycle should not clear more entries than it created.
   Also, superseded count cannot exceed cleared count."
  [{:keys [money-movement-summary]}]
  (let [pl (get-in money-movement-summary [:pending-lifecycle :unknown] {:created 0 :cleared 0 :superseded 0})
        {:keys [created cleared superseded]} pl]
    (cond
      (> cleared created)
      (fail :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending cleared cannot exceed pending created"
            [{:field :cleared :observed cleared :max-allowed created}])

      (> superseded cleared)
      (fail :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending superseded cannot exceed pending cleared"
            [{:field :superseded :observed superseded :max-allowed cleared}])

      :else
      (pass :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending lifecycle counts are consistent"))))

;; ---------------------------------------------------------------------------
;; Cancellation-specific mechanism property and equilibrium validators
;; ---------------------------------------------------------------------------

(def ^:private cancel-actions
  "Actions considered cancellation decision nodes for cancellation-dominance analysis."
  #{"sender_cancel" "recipient_cancel"})

(defn- cancel-decision-node?
  "True when a regret-table entry corresponds to a cancellation decision."
  [entry]
  (contains? cancel-actions (str (:chosen-action entry))))

(defn- check-cancellation-dominance
  "Cancellation-specific equilibrium concept: no alternative action yields strictly
   higher utility than the chosen cancel action at any cancel decision node.

   Evidence-strength labeling:
   - :multi-trace-deviation-tested — projection has deviation bundles meeting minimums
   - :single-trace-cancel-proxy    — single trace, no deviation bundles

   Delegates to subgame-counterfactual (same infrastructure as SPE) but filters
   the regret table to cancellation-specific decision nodes only.

   :pass when no cancel node has positive regret (chosen action >= all alternatives).
   :fail when any cancel node has positive regret.
   :inconclusive when no cancel decisions were present in the trace (no data)."
  [projection]
  (let [{:keys [status basis regret-table max-regret threshold checked-nodes requires
                spe-result result-strength strategy-profile proper-subgames-checked
                information-set-nodes-checked not-checkable-nodes
                counterexamples epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret]}
        (subgame-cf/evaluate-subgame-counterfactual projection)
        eq-concept :cancellation-dominance
        ;; Evidence-strength: deviation-bundle present → multi-trace, else single-trace proxy
        dev-bundle-min? (true? (get-in projection [:deviation-bundle :meets-minimum?]))
        evidence-basis (if dev-bundle-min?
                         :multi-trace-deviation-tested
                         :single-trace-cancel-proxy)
        cancel-rows  (filter cancel-decision-node? regret-table)
        cancel-count (count cancel-rows)
        cancel-max-regret (reduce max 0 (map :local-regret cancel-rows))
        cancel-fails (filter (fn [r] (pos? (long (or (:local-regret r) 0)))) cancel-rows)
        cancel-fail-count (count cancel-fails)]
    (cond
      (zero? cancel-count)
      (inconclusive eq-concept :absent-evidence
                    (str "no cancel decision nodes found (cancel-count=0); "
                         "cancellation-dominance cannot be evaluated"))

      (pos? cancel-fail-count)
      (fail eq-concept evidence-basis
            {:result-strength result-strength
             :cancel-nodes-checked cancel-count
             :cancel-max-regret cancel-max-regret
             :cancel-fails (mapv #(select-keys % [:seq :agent :action-taken :chosen-utility
                                                   :max-alt-utility :local-regret])
                                 cancel-fails)
             :cancel-fail-count cancel-fail-count
             :all-nodes-checked checked-nodes
             :strategy-profile strategy-profile
             :evidence-basis evidence-basis}
            {:cancel-fail-count 0
             :cancel-max-regret (str "<= " threshold)}
            (mapv (fn [r] {:node/seq (:seq r)
                           :agent (:agent r)
                           :action (:chosen-action r)
                           :regret (:local-regret r)
                           :chosen-utility (:chosen-utility r)
                           :max-alt-utility (:max-alt-utility r)})
                  cancel-fails))

      :else
      (pass eq-concept evidence-basis
            {:result-strength result-strength
             :cancel-nodes-checked cancel-count
             :cancel-max-regret cancel-max-regret
             :all-nodes-checked checked-nodes
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :evidence-basis evidence-basis}
            {:cancel-fail-count 0
             :cancel-max-regret (str "<= " threshold)}))))

;; ---------------------------------------------------------------------------
;; Public validator registries
;; ---------------------------------------------------------------------------

(def mechanism-property-validators
  "Map of Sew-specific mechanism-property keyword → validator-fn.
   Returned by SewProtocol/mechanism-property-validators and merged with the
   framework's built-in generic validators."
   {:individual-rationality      check-individual-rationality
    :collusion-resistance        check-collusion-resistance
    :stake-flow-conservation     check-stake-flow-conservation
    :budget-balance              check-budget-balance
    :budget-balance-detailed     check-budget-balance-detailed
    :force-refund-path-integrity check-force-refund-path-integrity
    :force-reversal-path-integrity check-force-reversal-path-integrity
    :pending-lifecycle-integrity check-pending-lifecycle-integrity})

(def equilibrium-concept-validators
  "Map of Sew-specific equilibrium-concept keyword → validator-fn.
   Returned by SewProtocol/equilibrium-concept-validators and merged with the
   framework's built-in generic validators."
  {   :subgame-perfect-equilibrium             check-subgame-perfect-equilibrium
   :trace-conditioned-epsilon-spe           (fn [p] (check-subgame-perfect-equilibrium p :trace-conditioned-epsilon-spe))
   :bounded-public-state-epsilon-spe        check-bounded-public-state-epsilon-spe
   :bounded-backward-induction-spe          check-bounded-backward-induction-spe
   :resolver-reputation-spe                 check-resolver-reputation-spe
   :resolver-reputation-profile-matrix      check-resolver-reputation-profile-matrix
   :cancellation-dominance                  check-cancellation-dominance})
