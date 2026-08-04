(ns resolver-sim.definitions.registry
  "Canonical semantic definitions registry used by replay/report/evidence/Clerk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.evidence.confidence :as confidence]
            [resolver-sim.config.paths :as paths]))

(def purposes
  {:regression {:label "Regression" :default-story-family :scenario-deep-dive}
   :adversarial-robustness {:label "Adversarial Robustness" :default-story-family :threat-detected}
   :theory-falsification {:label "Theory Falsification" :default-story-family :theory-falsification}
   :unclassified {:label "Unclassified (v1.0)" :default-story-family :scenario-deep-dive}})

(def statuses
  {:not-evaluated {:label "Not evaluated: no theory block"}
   :not-falsified {:label "Not falsified in this replay"}
   :falsified     {:label "Falsified by this replay"}
   :inconclusive  {:label "Inconclusive: evidence incomplete or invalid"}
   :not-applicable {:label "Not applicable"}})

(def proxy-statuses
  "Human-facing labels for mechanism/equilibrium validator status (not metric falsification)."
  {:pass            {:label "Proxy check passed"}
   :fail            {:label "Proxy check failed"}
   :inconclusive    {:label "Proxy check inconclusive"}
   :not-applicable  {:label "Proxy check not applicable"}
   :not-checked     {:label "Proxy check not run"}})

(def severities
  {:critical {:rank 4}
   :high {:rank 3}
   :medium {:rank 2}
   :low {:rank 1}})

(def story-families
  {:theory-falsification {:label "Theory falsification"}
   :threat-detected {:label "Threat detected"}
   :scenario-deep-dive {:label "Scenario deep dive"}
   :deflection {:label "Deflection"}
   :deadline-boundary {:label "Deadline boundary"}
   :collusion {:label "Collusion"}
   :economic-solvency {:label "Economic solvency"}})

(def confidence-levels
  "Confidence level metadata.
   Delegates to resolver-sim.evidence.confidence for canonical vocabulary.
   Scores are ordinal ranks (not calibrated probabilities) for ordering and display."
  (let [rank->score {3 0.9, 2 0.6, 1 0.3}]
    (into {} (map (fn [lvl]
                    [lvl {:score (get rank->score (confidence/confidence-score lvl) 0.0)}])
                  confidence/levels))))

(defn- load-speds-definitions
  "Load SPEDS definitions from the first available source:
   1. PRF_DEFINITIONS_PATH environment variable (explicit override)
   2. Classpath resource at data/speds/definitions.edn (embedded in jar)
   3. Filesystem path data/speds/definitions.edn (development checkout)

   Returns the parsed EDN map, or nil if no source found."
  []
  (or (try (some-> (System/getenv "PRF_DEFINITIONS_PATH")
                   io/file slurp edn/read-string)
           (catch Exception _ nil))
      (try (some-> (io/resource (paths/speds-definitions))
                   slurp edn/read-string)
           (catch Exception _ nil))
      (try (some-> (paths/speds-definitions)
                   io/file slurp edn/read-string)
           (catch Exception _ nil))))

(def ^:private speds-definitions
  "SPEDS semantic definitions loaded via resource chain (env → classpath → filesystem)."
  (delay (load-speds-definitions)))

(def speds-purpose-kind
  (get @speds-definitions :speds-purpose-kind))

(def speds-purpose-classification
  (get @speds-definitions :speds-purpose-classification))

(def status->story-family
  {:falsified :theory-falsification
   :not-falsified :scenario-deep-dive
   :inconclusive :scenario-deep-dive
   :not-evaluated :scenario-deep-dive})

(def transitions
  {:create_escrow {:label "Create escrow"}
   :raise_dispute {:label "Raise dispute"}
   :execute_resolution {:label "Execute resolution"}
   :execute_pending_settlement {:label "Execute pending settlement"}
   :automate_timed_actions {:label "Automate timed actions"}
   :release {:label "Release"}
   :sender_cancel {:label "Sender cancel"}
   :recipient_cancel {:label "Recipient cancel"}
   :auto_cancel_disputed {:label "Auto-cancel disputed"}
   :escalate_dispute {:label "Escalate dispute"}
   :register_stake {:label "Register stake"}
   :challenge_resolution {:label "Challenge resolution"}
   :submit_evidence {:label "Submit evidence"}})

(def transition-metadata
  {:create_escrow {:allowed-sources [:none]
                   :allowed-targets [:pending]
                   :guards [:unpaused :valid-params]
                   :actor-permissions [:sender]
                   :pause-effect :blocked-when-paused}
   :raise_dispute {:allowed-sources [:pending]
                   :allowed-targets [:disputed]
                   :guards [:participant :state-pending]
                   :actor-permissions [:sender :recipient]
                   :pause-effect :blocked-when-paused}
   :execute_resolution {:allowed-sources [:disputed]
                        :allowed-targets [:released :refunded]
                        :guards [:authorized-resolver :state-disputed]
                        :actor-permissions [:resolver]
                        :pause-effect :blocked-when-paused}
   :execute_pending_settlement {:allowed-sources [:disputed]
                                :allowed-targets [:released :refunded]
                                :guards [:pending-exists :deadline-expired]
                                :actor-permissions [:keeper :executor]
                                :pause-effect :blocked-when-paused}
   :automate_timed_actions {:allowed-sources [:pending :disputed]
                            :allowed-targets [:pending :released :refunded]
                            :guards [:deadline-eligible]
                            :actor-permissions [:keeper]
                            :pause-effect :blocked-when-paused}
   :release {:allowed-sources [:pending]
             :allowed-targets [:released]
             :guards [:authorized-release]
             :actor-permissions [:sender :authorized-release-address]
             :pause-effect :blocked-when-paused}
   :sender_cancel {:allowed-sources [:pending]
                   :allowed-targets [:pending]
                   :guards [:caller-is-sender]
                   :actor-permissions [:sender]
                   :pause-effect :blocked-when-paused}
   :recipient_cancel {:allowed-sources [:pending]
                      :allowed-targets [:pending :refunded]
                      :guards [:caller-is-recipient]
                      :actor-permissions [:recipient]
                      :pause-effect :blocked-when-paused}
   :auto_cancel_disputed {:allowed-sources [:disputed]
                          :allowed-targets [:refunded]
                          :guards [:timeout-expired]
                          :actor-permissions [:keeper]
                          :pause-effect :blocked-when-paused}
   :escalate_dispute {:allowed-sources [:disputed]
                      :allowed-targets [:disputed]
                      :guards [:pending-exists :appeal-window-open :max-level-not-reached]
                      :actor-permissions [:sender :recipient]
                      :pause-effect :blocked-when-paused}
   :register_stake {:allowed-sources [:none]
                    :allowed-targets [:none]
                    :guards [:stake-params-valid]
                    :actor-permissions [:resolver]
                    :pause-effect :blocked-when-paused}
   :challenge_resolution {:allowed-sources [:disputed]
                          :allowed-targets [:disputed]
                          :guards [:resolution-exists :challenge-window-open]
                          :actor-permissions [:challenger :watchdog]
                          :pause-effect :blocked-when-paused}
   :submit_evidence {:allowed-sources [:disputed]
                     :allowed-targets [:disputed]
                     :guards [:state-disputed]
                     :actor-permissions [:sender :recipient :challenger :watchdog]
                     :pause-effect :blocked-when-paused}})

(def invariants
  {:invariant/conservation {:invariant/id :invariant/conservation
                            :label "Conservation"
                            :default-severity :high
                            :class :safety}
   :invariant/solvency {:invariant/id :invariant/solvency
                        :label "Solvency"
                        :default-severity :high
                        :class :safety}
   :invariant/finality {:invariant/id :invariant/finality
                        :label "Finality"
                        :default-severity :medium
                        :class :liveness}
   :invariant/evidence-on-state-change
   {:invariant/id :invariant/evidence-on-state-change
    :label "Evidence on state change"
    :default-severity :high
    :class :safety}
   :invariant/no-duplicate-dispute
   {:invariant/id :invariant/no-duplicate-dispute
    :label "No duplicate dispute"
    :default-severity :high
    :class :safety}
   :invariant/appeal-requires-prior-resolution
   {:invariant/id :invariant/appeal-requires-prior-resolution
    :label "Appeal requires prior resolution"
    :default-severity :high
    :class :safety}
   :invariant/resolver-decision-attributable
   {:invariant/id :invariant/resolver-decision-attributable
    :label "Resolver decision attributable"
    :default-severity :medium
    :class :safety}
   :invariant/appeal-reversal-detectable
   {:invariant/id :invariant/appeal-reversal-detectable
    :label "Appeal reversal detectable"
    :default-severity :medium
    :class :liveness}
   :invariant/finality-blocked-during-appeal
   {:invariant/id :invariant/finality-blocked-during-appeal
    :label "Finality blocked during appeal"
    :default-severity :high
    :class :safety}
   :invariant/challenge-bond-proportional
   {:invariant/id :invariant/challenge-bond-proportional
    :label "Challenge bond proportional to escrow value"
    :default-severity :medium
    :class :economic-safety}
   :invariant/resolver-stake-proportional
   {:invariant/id :invariant/resolver-stake-proportional
    :label "Resolver stake proportional to escrow value"
    :default-severity :medium
    :class :economic-safety}})

(def invariant-metadata
  {:invariant/conservation
   {:related-transitions [:create_escrow :release :execute_resolution :execute_pending_settlement]
    :related-scenario-families [:scenario-deep-dive :economic-solvency]}
   :invariant/solvency
   {:related-transitions [:create_escrow :release :execute_resolution :execute_pending_settlement :automate_timed_actions]
    :related-scenario-families [:economic-solvency :threat-detected]}
   :invariant/finality
   {:related-transitions [:release :execute_resolution :execute_pending_settlement :automate_timed_actions]
    :related-scenario-families [:scenario-deep-dive :deadline-boundary]}
   :invariant/evidence-on-state-change
   {:related-transitions [:raise_dispute :submit_evidence :execute_resolution :escalate_dispute]
    :related-scenario-families [:scenario-deep-dive :deadline-boundary]}
   :invariant/no-duplicate-dispute
   {:related-transitions [:raise_dispute]
    :related-scenario-families [:scenario-deep-dive]}
   :invariant/appeal-requires-prior-resolution
   {:related-transitions [:escalate_dispute]
    :related-scenario-families [:scenario-deep-dive :deadline-boundary]}
   :invariant/resolver-decision-attributable
   {:related-transitions [:execute_resolution :escalate_dispute]
    :related-scenario-families [:scenario-deep-dive :collusion]}
   :invariant/appeal-reversal-detectable
   {:related-transitions [:escalate_dispute :execute_resolution]
    :related-scenario-families [:scenario-deep-dive :theory-falsification]}
   :invariant/finality-blocked-during-appeal
   {:related-transitions [:execute_pending_settlement]
    :related-scenario-families [:scenario-deep-dive :deadline-boundary]}
   :invariant/challenge-bond-proportional
   {:related-transitions [:challenge-resolution :raise-dispute]
    :related-scenario-families [:economic-liveness :theory-falsification]}
   :invariant/resolver-stake-proportional
   {:related-transitions [:create_escrow :register_stake]
    :related-scenario-families [:economic-security :theory-falsification]}})

(def claims
  {:claims/forking-l1-reversal
   {:claim/id :claims/forking-l1-reversal
    :claim/title "L1 reversal can overturn L0 decision under valid escalation"
    :claim/type :dispute-resolution
    :claim/statement "When challenged within the window, L1 resolver may legally reverse L0 outcome."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/solvency]}

   :claims/forking-l2-path
   {:claim/id :claims/forking-l2-path
    :claim/title "Escalation to L2 path remains valid and bounded"
    :claim/type :dispute-resolution
    :claim/statement "Escalation from L0→L1→L2 follows bounded levels and valid guards."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/conservation]}

   :claims/appeal-window-enforced
   {:claim/id :claims/appeal-window-enforced
    :claim/title "Appeal window enforces settlement timing"
    :claim/type :time-safety
    :claim/statement "Pending settlements execute only after appeal deadline."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/fork-isolation
   {:claim/id :claims/fork-isolation
    :claim/title "Forking outcomes remain escrow-isolated"
    :claim/type :safety
    :claim/statement "Escalation and settlement on one escrow do not leak state/balance to another escrow."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/conservation :invariant/solvency]}

   :claims/dr3-reversal-slash-disabled
   {:claim/id :claims/dr3-reversal-slash-disabled
    :claim/title "DR3 v3 disables non-zero reversal slashes"
    :claim/type :safety
    :claim/statement "When reversal-slash-bps is 0 in the module snapshot, no reversal slash entry carries a positive amount."
    :claim/evidence-mode :support
    :claim/related-invariants [:reversal-slash-disabled]}

   :claims/bribery-neutralized-by-l1
   {:claim/id :claims/bribery-neutralized-by-l1
    :claim/title "L1 challenge reverses biased L0 ruling"
    :claim/type :dispute-resolution
    :claim/statement "An honest L1 decision after challenge can overturn a collusive L0 refund and release escrow to the seller."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/conservation]}

   :claims/reversal-slash-track1
   {:claim/id :claims/reversal-slash-track1
    :claim/title "Same-evidence reversal slash executes immediately"
    :claim/type :safety
    :claim/statement "When reversal-slash-bps > 0 and no new evidence, the prior resolver is slashed on stake basis with :executed status."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency :invariant/conservation]}

   :claims/reversal-slash-track2-reversed
   {:claim/id :claims/reversal-slash-track2-reversed
    :claim/title "Track 2 reversal slash can be reversed on appeal"
    :claim/type :safety
    :claim/statement "When new evidence triggers a pending reversal slash, a upheld appeal reverses the slash before stake is executed."
    :claim/evidence-mode :support
    :claim/related-invariants [:slash-status-consistent? :invariant/solvency]}

   :claims/reversal-slash-track2-executes
   {:claim/id :claims/reversal-slash-track2-executes
    :claim/title "Rejected Track 2 reversal appeal allows slash execution"
    :claim/type :safety
    :claim/statement "When governance rejects a reversal-slash appeal after the timelock, the pending slash executes and reduces resolver stake."
    :claim/evidence-mode :support
    :claim/related-invariants [:slash-status-consistent? :invariant/solvency]}

   :claims/resolver-capacity-enforced
   {:claim/id :claims/resolver-capacity-enforced
    :claim/title "Resolver concurrent dispute capacity is enforced"
    :claim/type :safety
    :claim/statement "When a resolver is at max concurrent disputes, additional disputes on that resolver are rejected."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency]}

   :claims/forking-strategist-all-levels-confirm
   {:claim/id :claims/forking-strategist-all-levels-confirm
    :claim/title "Third escalation after max level must reject"
    :claim/type :safety
    :claim/statement "Third escalation after max level must reject without corrupting L2 pending settlement."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/forking-strategist-double-loss
   {:claim/id :claims/forking-strategist-double-loss
    :claim/title "Double loss: L1 confirms L0"
    :claim/type :safety
    :claim/statement "When L1 confirms L0, final settlement remains release and accounting stays conserved."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency]}

   :claims/forking-strategist-l1-reversal
   {:claim/id :claims/forking-strategist-l1-reversal
    :claim/title "L1 reversal after L0 release"
    :claim/type :safety
    :claim/statement "L1 escalation may reverse L0 without breaking accounting or monotonic dispute level."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/forking-strategist-l2-fork
   {:claim/id :claims/forking-strategist-l2-fork
    :claim/title "L2 fork after confirming L0 and L1"
    :claim/type :safety
    :claim/statement "Dispute level advances monotonically 0→1→2; L2 may fork without rewriting L0/L1 hashes."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/forking-strategist-late-escalation-rejected
   {:claim/id :claims/forking-strategist-late-escalation-rejected
    :claim/title "Late escalation rejected; L0 release stands"
    :claim/type :safety
    :claim/statement "Post-deadline escalation must not advance dispute level or block L0 settlement."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/forking-strategist-premature-settlement-rejected
   {:claim/id :claims/forking-strategist-premature-settlement-rejected
    :claim/title "Premature settlement rejected; L1 fork finalizes"
    :claim/type :safety
    :claim/statement "Premature settlement must reject without corrupting pending state; post-window refund succeeds."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/forking-strategist-seller-escalates
   {:claim/id :claims/forking-strategist-seller-escalates
    :claim/title "Seller-initiated L1 fork to release"
    :claim/type :safety
    :claim/statement "Seller escalation may fork L0 refund to L1 release with correct bonds and hashes."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/workflow-dispute-isolation-shared-resolver
   {:claim/id :claims/workflow-dispute-isolation-shared-resolver
    :claim/title "Fork isolation across two disputed escrows"
    :claim/type :safety
    :claim/statement "Escalating workflow 0 must not alter, block, cancel, delay, overwrite, or contaminate the pending settlement state of workflow 1."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/conservation]}

   :claims/optimal-strategy-under-load-bounded
   {:claim/id :claims/optimal-strategy-under-load-bounded
    :claim/title "Optimal resolver strategy is honest under low load"
    :claim/type :safety
    :claim/statement "As dispute load increases, the optimal strategy for a resolver remains honest; collusion becomes irrational due to bond-slash risk. Note: Strategy selection models greedy best-response incentives assuming fixed-accuracy estimates; does not account for multi-agent strategy equilibrium."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency]}})

(def claim-scenario-map
  {:claims/forking-l1-reversal
   {:supporting ["S26_forking-strategist-l1-reversal"]
    :falsifying []}
   :claims/forking-l2-path
   {:supporting ["S27_forking-strategist-l2-fork"
                 "S31_forking-strategist-all-levels-confirm"]
    :falsifying []}
   :claims/appeal-window-enforced
   {:supporting ["S32_forking-strategist-premature-settlement-rejected"
                 "S36_profit-maximizer-pre-window-execute-rejected"
                 "S74_appeal-deadline-boundary"]
    :falsifying []}
   :claims/fork-isolation
   {:supporting ["S33_forking-strategist-two-escrow-fork-isolation"
                 "S62_cross-token-isolation-under-dispute-load"
                 "S62_cross-token-fee-on-transfer-under-dispute-load"
                 "S62_cross-token-parallel-appeal-depths-under-dispute-load"]
    :falsifying []}
   :claims/resolver-capacity-enforced
   {:supporting ["S62_resolver-capacity-concurrent-dispute-load"]
    :falsifying []}

   :claims/forking-strategist-all-levels-confirm
   {:supporting ["S31_forking-strategist-all-levels-confirm"]
    :falsifying []}

   :claims/forking-strategist-double-loss
   {:supporting ["S30_forking-strategist-double-loss"]
    :falsifying []}

   :claims/forking-strategist-l1-reversal
   {:supporting ["S26_forking-strategist-l1-reversal"]
    :falsifying []}

   :claims/forking-strategist-l2-fork
   {:supporting ["S27_forking-strategist-l2-fork"]
    :falsifying []}

   :claims/forking-strategist-late-escalation-rejected
   {:supporting ["S28_forking-strategist-late-escalation-rejected"]
    :falsifying []}

   :claims/forking-strategist-premature-settlement-rejected
   {:supporting ["S32_forking-strategist-premature-settlement-rejected"]
    :falsifying []}

   :claims/forking-strategist-seller-escalates
   {:supporting ["S29_forking-strategist-seller-escalates"]
    :falsifying []}

   :claims/workflow-dispute-isolation-shared-resolver
   {:supporting ["S33_forking-strategist-two-escrow-fork-isolation"]
    :falsifying []}

   :claims/optimal-strategy-under-load-bounded
   {:supporting ["Y08_optimal-strategy-load-stress"]
    :falsifying []}

   :claims/dr3-reversal-slash-disabled
   {:supporting ["S41_dr3-reversal-slash-disabled"]
    :falsifying []}

   :claims/bribery-neutralized-by-l1
   {:supporting ["S42_resolver-buyer-bribery-loop"]
    :falsifying []}

   :claims/reversal-slash-track1
   {:supporting ["DR-D-001-reversal-slashing-auto-track"
                 "DR-N-001-reversal-slash-appeal-lifecycle"]
    :falsifying []}

   :claims/reversal-slash-track2-reversed
   {:supporting ["DR-G-001-manual-reversal-slash-t2"
                 "DR-N-001-reversal-slash-appeal-lifecycle"]
    :falsifying []}

   :claims/reversal-slash-track2-executes
   {:supporting ["DR-N-002-reversal-slash-appeal-rejected"]
    :falsifying []}})

(defn purpose-def [k] (get purposes k))
(defn status-def [k] (get statuses k))
(defn proxy-status-def [k] (get proxy-statuses k))
(defn severity-def [k] (get severities k))
(defn story-family-def [k] (get story-families k))
(defn valid-purpose? [k] (contains? purposes k))
(defn valid-status? [k] (contains? statuses k))
(defn status->story-family* [k] (get status->story-family k :scenario-deep-dive))
(defn purpose->default-story-family [k] (or (get-in purposes [k :default-story-family]) :scenario-deep-dive))
(defn purpose->kind [k] (get speds-purpose-kind k (:default speds-purpose-kind)))
(defn purpose->classification [k] (get speds-purpose-classification k (:default speds-purpose-classification)))
(defn transition-def [k] (get transitions k))
(defn transition-meta [k] (get transition-metadata k))
(defn invariant-def [k] (get invariants k))
(defn invariant-meta [k] (get invariant-metadata k))
(defn claim-def [k] (get claims k))
(defn claim-scenarios [k] (get claim-scenario-map k))
(defn canonical-transition-ids [] (set (keys transitions)))

(defn definitions-canonical-edn []
  (pr-str {:purposes purposes
           :statuses statuses
           :severities severities
           :story-families story-families
           :confidence-levels confidence-levels
           :speds-purpose-kind speds-purpose-kind
           :speds-purpose-classification speds-purpose-classification
           :transitions transitions
           :transition-metadata transition-metadata
           :invariants invariants
           :invariant-metadata invariant-metadata
           :claims claims
           :claim-scenario-map claim-scenario-map
           :status->story-family status->story-family}))

(defn definitions-hash []
  (-> (hash (definitions-canonical-edn)) Math/abs str))

(defn vocab-markdown []
  (str "# Semantic Vocabulary\n\n"
       "## Purposes\n"
       (apply str (for [[k v] purposes] (str "- `" (name k) "` — " (:label v) "\n")))
       "\n## Statuses\n"
       (apply str (for [[k v] statuses] (str "- `" (name k) "` — " (:label v) "\n")))))
