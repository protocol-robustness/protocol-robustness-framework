(ns resolver-sim.protocols.sew.invariant-scenarios
  "Deterministic invariant scenarios (S01–S100) as Clojure data.

   Each entry in `all-scenarios` is a scenario map accepted by
   resolver-sim.protocols.sew/replay-with-sew-protocol.

   Events use direct integer workflow-ids throughout. The Nth create_escrow
   event produces workflow-id N-1 (zero-indexed by creation order).

   Split across invariant-scenarios.* sub-namespaces; this ns aggregates
   all-scenarios, scenario-type-registry, and startup validation for the
   full invariant scenario registry."
  (:require [clojure.set :as set]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.validation :as replay-validation]
            [resolver-sim.protocols.sew.invariant-scenarios.adversarial :as adversarial]
            [resolver-sim.protocols.sew.invariant-scenarios.baseline :as baseline]
            [resolver-sim.protocols.sew.invariant-scenarios.extended :as extended]
            [resolver-sim.protocols.sew.invariant-scenarios.gaps :as gaps]
            [resolver-sim.protocols.sew.invariant-scenarios.reversal :as reversal]
            [resolver-sim.protocols.sew.invariant-scenarios.cancellation-extended :as cancellation-ext]
            [resolver-sim.validation.scenario-id :as sid]))

;; ---------------------------------------------------------------------------
;; Scenario registry
;; ---------------------------------------------------------------------------

(def all-scenarios
  "Ordered list of [display-name scenario-or-pair] entries.
    Pairs (S12) are two scenarios that must both pass to count as one passing test.
    Single entries are plain scenario maps."
  [["S01  baseline-happy-path"                      baseline/s01]
   ["S02  dr3-dispute-release"                      baseline/s02]
   ["S03  dr3-dispute-refund"                       baseline/s03]
   ["S04  dispute-timeout-autocancel"               baseline/s04]
   ["S05  pending-settlement-execute"               baseline/s05]
   ["S06  mutual-cancel"                            baseline/s06]
   ["S07  unauthorized-resolver-rejected"           baseline/s07]
   ["S08  state-machine-attack-gauntlet"            baseline/s08]
   ["S09  multi-escrow-solvency"                    baseline/s09]
   ["S10  double-finalize-rejected"                 baseline/s10]
   ["S11  zero-fee-edge-case"                       baseline/s11]
   ["S12 governance-snapshot-isolation"             [baseline/s12a baseline/s12b]]
   ["S13  pending-settlement-refund"                baseline/s13]
   ["S14  dr3-module-authorized"                    baseline/s14]
   ["S15  dr3-module-unauthorized-rejected"         baseline/s15]
   ["S16  ieo-create-release"                       baseline/s16]
   ["S17  ieo-dispute-no-resolver-timeout"          baseline/s17]
   ["S18  dr3-kleros-l0-resolves"                   baseline/s18]
   ["S19  dr3-kleros-escalation-rejected-l0-resolves" baseline/s19]
   ["S20  dr3-kleros-max-escalation-guard"          baseline/s20]
   ["S21  dr3-kleros-pending-cleared-on-escalation" baseline/s21]
   ["S22  status-leak-agree-cancel-over-dispute"    baseline/s22]
   ["S23  preemptive-escalation-blocked"            baseline/s23]
   ["S24  resolver-stake-depletion-cascade"         adversarial/s24]
   ["S25  profit-maximizer-slash-lifecycle"         adversarial/s25]
   ["S26  forking-strategist-l1-reversal"           adversarial/s26]
   ["S27  forking-strategist-l2-fork"               adversarial/s27]
   ["S28  forking-strategist-late-escalation-rejected" adversarial/s28]
   ["S29  forking-strategist-seller-escalates"      adversarial/s29]
   ["S30  forking-strategist-double-loss"           adversarial/s30]
   ["S31  forking-strategist-all-levels-confirm"    adversarial/s31]
   ["S32  forking-strategist-premature-settlement-rejected" adversarial/s32]
   ["S33  forking-strategist-two-escrow-fork-isolation" adversarial/s33]
   ["S34  profit-maximizer-unchallenged-slash"          adversarial/s34]
   ["S35  profit-maximizer-governance-wins-appeal"      adversarial/s35]
   ["S36  profit-maximizer-pre-window-execute-rejected" adversarial/s36]
   ["S37  profit-maximizer-two-resolver-split-outcomes" adversarial/s37]
   ["S38  dr3-bond-mix-valid"                           adversarial/s38]
   ["S39  dr3-senior-coverage-delegation"               adversarial/s39]
   ["S40  dr3-freeze-post-slash"                        adversarial/s40]
   ["S41  dr3-reversal-slash-disabled"                  adversarial/s41]
   ["S42  resolver-buyer-bribery-loop"                  adversarial/s42]
   ["S43  auth-rejected-then-authorized-recovery"       gaps/s43]
   ["S44  escalation-tier-mismatch-rejected"            gaps/s44]
   ["S45  stale-module-snapshot-rejects-legacy"         gaps/s45-stale-module-snapshot]
   ["S45  flash-loan-stake-inflation"                   adversarial/s45]
   ["S46  reorg-idempotence"                            adversarial/s46]
   ["S46 settlement-vs-escalation-window-edge"          [gaps/s46a gaps/s46b]]
   ["S47  appeal-window-boundary-pair"                  [gaps/s47a gaps/s47b]]
   ["S48  max-escalation-exact-boundary"                gaps/s48]
   ["S49  appeal-deadline-boundary"                     extended/s49]
   ["S50  false-assertion-unchallenged"                 extended/s50]
   ["S51  same-block-challenge-finalize-race"          extended/s51]
   ["S51b same-block-escalate-finalize-inverse"        extended/s51-inverse]
   ["S51c deadline-matrix-execute-then-escalate"       extended/s51c-deadline-matrix-execute-then-escalate]
   ["S51d deadline-matrix-escalate-then-execute"       extended/s51d-deadline-matrix-escalate-then-execute]
   ["S52  yield-accrued-during-dispute"                extended/s52]
   ["S53  reentrant-withdrawal-guard"                  extended/s53]
   ["S54  multi-claim-ledger-isolation"                extended/s54]
   ["S55  resolver-unavailable-timeout-fallback"       extended/s55]
   ["S56  rapid-resolver-rotation"                     extended/s56]
   ["S57  corruption-cost-vs-profit"                   extended/s57]
   ["S58  watchdog-valid-challenge"                    extended/s58]
   ["S59  watchdog-false-challenge-loss"               extended/s59]
   ["S60  resolver-abstention-timeout-griefing"        extended/s60]
   ["S61  fee-on-transfer-token-handling"              extended/s61]
   ["S62  multi-appeal-escalation-chain"               extended/s62]
   ["S62_cross-token-isolation-under-dispute-load"     extended/s62-cross-token-isolation-under-dispute-load]
   ["S62_cross-token-fee-on-transfer-under-dispute-load" extended/s62-cross-token-fee-on-transfer-under-dispute-load]
   ["S62_cross-token-parallel-appeal-depths-under-dispute-load" extended/s62-cross-token-parallel-appeal-depths-under-dispute-load]
   ["S62_resolver-capacity-concurrent-dispute-load"  extended/s62-resolver-capacity-concurrent-dispute-load]
   ["S63  frivolous-appeal-slashing"                   extended/s63]
   ["S64  minimal-bond-edge-case"                      extended/s64]
   ["S65  appeal-after-settlement-rejected"            extended/s65]
   ["S68  double-settlement-guard"                     extended/s68]
   ["S69  stale-dispute-cleanup"                       extended/s69]
   ["S70  large-escrow-resolution"                     extended/s70]
   ["S71  zero-fee-settlement"                         extended/s71]
   ["S72  challenge-during-appeal-window"              extended/s72]
   ["S73  challenge-after-appeal-window-closed"        extended/s73]
   ["S74  multi-escrow-parallel-disputes"              extended/s74]
   ["S75  receiver-cancels-after-dispute"              extended/s75]
   ["S76  sender-cancel-during-appeal"                extended/s76]
   ["S77  escalation-rejected-wrong-layer"             extended/s77]
   ["S78  many-appeals-eventually-rejects"             extended/s78]
   ["S79  partial-resolution-release"                 extended/s79]
   ["S80  disputed-escrow-expiry-settlement"           extended/s80]
   ["S81  appeal-deadline-boundary-before"             extended/s81]
   ["S82  appeal-deadline-boundary-exact"              extended/s82]
   ["S83  appeal-deadline-boundary-after"              extended/s83]
   ["S84  false-assertion-unchallenged"                extended/s84]
   ["S85  watchdog-challenges-resolution"              extended/s85]
   ["S86  reentrant-withdrawal-guard"                  extended/s86]
   ["S87  cross-escrow-isolation"                      extended/s87]
   ["S88  resolution-with-conflicting-evidence"        extended/s88]
   ["S89  dispute-resolution-with-zero-appeal-window"  extended/s89]
   ["S90  resolver-capacity-stress"                    extended/s90]
   ["S91  governance-snapshot-dispute-state"           extended/s91]
   ["S92  settlement-zero-amount-edge"                 extended/s92]
   ["S93  multiple-appeals-with-refund"                extended/s93]
   ["S94  dispute-timeout-auto-refund"                 extended/s94]
   ["S95  resolution-challenge-and-counter"            extended/s95]
   ["S96  multi-token-dispute"                         extended/s96]
   ["S97  appeal-after-settlement-attempt"             extended/s97]
   ["S98  receiver-cancel-after-auto-cancel"           extended/s98]
   ["S99  large-escrow-fee-impact"                     extended/s99]
   ["S100 deny-then-resolver-releases"                 extended/s100]
   ["S101 reversal-slash-track1-enabled"             reversal/s101]
   ["S102 reversal-challenge-bounty"                 reversal/s102]
   ["S103 l2-reversal-slash-ids"                       reversal/s103]
   ["S104 executed-reversal-slash-not-appealable"      reversal/s104]
   ["S105 escalate-challenger-on-reversal"             reversal/s105]
   ["S106 reversal-track2-evidence-appeal"             reversal/s106]
    ["S107 reversal-track2-appeal-rejected-executes"    reversal/s107]
    ["S108 senior-coverage-consumed-by-slash"           adversarial/s108]
    ["S109 senior-coverage-exhausted-by-slash"          adversarial/s109]
    ["S110 multi-junior-coverage"                        adversarial/s110]
    ["S111 gradual-coverage-exhaustion"                  adversarial/s111]
    ["S112 coverage-exceeded-error"                      adversarial/s112]
    ["S113 insurance-bps-override"                       adversarial/s113]
    ["S43k dr3-kleros-reversal-slash"                    reversal/s43-kleros-reversal-slash]
   ["S66  cooldown-boundary-reorg"                      adversarial/s66]
   ["S67  reentrancy-callback"                          adversarial/s67]
   ["EXT-unilateral-cancel"                             cancellation-ext/s-extortion-unilateral-cancel]
   ["EXT-unilateral-cancel-dual"                        cancellation-ext/s-extortion-unilateral-cancel-dual]
   ["EXT-same-timestamp-cancel-vs-dispute"               cancellation-ext/s-same-timestamp-cancel-vs-dispute]
    ["EXT-same-timestamp-dispute-vs-cancel"               cancellation-ext/s-same-timestamp-dispute-vs-cancel]
    ["EXT-auto-cancel-time-via-keeper"                     cancellation-ext/s-auto-cancel-time-via-keeper]
    ["S114 resolution-module-mutation"                      extended/s114]
    ["S115 resolution-refused-timeout"                      extended/s115]
    ["S116 resolution-refused-then-resolved"                extended/s116]
    ["EXT-auto-cancel-time-boundary"                       cancellation-ext/s-auto-cancel-time-boundary]
     ["EXT-auto-cancel-time-orphaned-by-dispute"             cancellation-ext/s-auto-cancel-time-orphaned-by-dispute]
     ["EXT-same-timestamp-auto-cancel-vs-dispute"            cancellation-ext/s-same-timestamp-auto-cancel-vs-dispute]
      ["EXT-same-timestamp-dispute-vs-auto-cancel"            cancellation-ext/s-same-timestamp-dispute-vs-auto-cancel]
      ["EXT-cancel-strategy-mutual-only"                      cancellation-ext/s-cancel-strategy-mutual-only]
      ["EXT-cancel-strategy-can-cancel-dominates"             cancellation-ext/s-cancel-strategy-can-cancel-dominates]
      ["EXT-sender-cancel-after-auto-cancel-deadline"          cancellation-ext/s-sender-cancel-after-auto-cancel-deadline]
      ["EXT-auto-cancel-due-on-disputed-time-not-passed"       cancellation-ext/s-auto-cancel-due-on-disputed-time-not-passed]
      ["EXT-auto-cancel-due-on-disputed-pending-settlement"    cancellation-ext/s-auto-cancel-due-on-disputed-pending-settlement]])

    ;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Scenario type registry
;;
;; Maps scenario-id → {:scenario/type kw :adversary? bool
;;                      :adversary/type kw :adversary/traits #{kw}}
;;
;; This is the authoritative source for scenario classification metadata.
;; invariant-runner merges this into run results for queryable output.
;; trace_metadata/classify-scenario uses scenario-id as a fallback signal.
;; ---------------------------------------------------------------------------

(def scenario-type-registry
  "Type metadata for all S01–S100 invariant scenarios, keyed by scenario-id."
  {;; ── Baseline: standard happy-path protocol flows ───────────────────────
   "s01-baseline-happy-path"                {:scenario/type :baseline}
   "s02-dr3-dispute-release"                {:scenario/type :baseline}
   "s03-dr3-dispute-refund"                 {:scenario/type :baseline}
   "s04-dispute-timeout-autocancel"         {:scenario/type :baseline}
   "s05-pending-settlement-execute"         {:scenario/type :baseline}
   "s06-mutual-cancel"                      {:scenario/type :baseline}
   "s13-pending-settlement-refund"          {:scenario/type :baseline}
   "s16-ieo-create-release"                 {:scenario/type :baseline}
   "s17-ieo-dispute-no-resolver-timeout"    {:scenario/type :baseline}
   "s18-dr3-kleros-l0-resolves"             {:scenario/type :baseline}
   "s46-reorg-idempotence"                  {:scenario/type :baseline}

    ;; ── Edge-case: permission checks, boundary conditions, state guards ────
   "s07-unauthorized-resolver-rejected"     {:scenario/type :edge-case}
   "s08-state-machine-attack-gauntlet"      {:scenario/type :edge-case}
   "s10-double-finalize-rejected"           {:scenario/type :edge-case}
   "s11-zero-fee-edge-case"                 {:scenario/type :edge-case}
   "s12a-snapshot-isolation-fee-zero"             {:scenario/type :edge-case}
   "s12b-snapshot-isolation-fee-500"              {:scenario/type :edge-case}
   "s14-dr3-module-authorized"              {:scenario/type :edge-case}
   "s15-dr3-module-unauthorized-rejected"   {:scenario/type :edge-case}
   "s19-dr3-kleros-escalation-rejected-l0-resolves" {:scenario/type :edge-case}
   "s20-dr3-kleros-max-escalation-guard"    {:scenario/type :edge-case}
   "s21-dr3-kleros-pending-cleared-on-escalation"   {:scenario/type :edge-case}
   "s22-status-leak-agree-cancel-over-dispute" {:scenario/type :edge-case}
   "s23-preemptive-escalation-blocked"      {:scenario/type :edge-case}
   "s66-cooldown-boundary-reorg"            {:scenario/type :edge-case}

    ;; ── Stress: solvency, multi-escrow, depletion ─────────────────────────
   "s09-multi-escrow-solvency"              {:scenario/type :stress}
   "s24-resolver-stake-depletion-cascade"   {:scenario/type :stress}
   "s38-dr3-bond-mix-valid"                 {:scenario/type :stress}
   "s39-dr3-senior-coverage-delegation"     {:scenario/type :stress}
   "s40-dr3-freeze-post-slash"              {:scenario/type :stress}
   "s41-dr3-reversal-slash-disabled"        {:scenario/type :stress}
   "s101-reversal-slash-track1-enabled"   {:scenario/type :stress}
   "s102-reversal-challenge-bounty"       {:scenario/type :stress}
   "s103-l2-reversal-slash-ids"           {:scenario/type :stress}
   "s104-executed-reversal-slash-not-appealable" {:scenario/type :edge-case}
   "s105-escalate-challenger-on-reversal" {:scenario/type :stress}
   "s106-reversal-track2-evidence-appeal" {:scenario/type :adversarial}
   "s107-reversal-track2-appeal-rejected-executes" {:scenario/type :adversarial}

    ;; ── Adversarial: profit-maximizer ─────────────────────────────────────
   "s25-profit-maximizer-slash-lifecycle"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:multi-step :capital-efficient}}

   "s34-profit-maximizer-unchallenged-slash"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:stealthy :capital-efficient}}

   "s35-profit-maximizer-governance-wins-appeal"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:adaptive :multi-step}}

   "s36-profit-maximizer-pre-window-execute-rejected"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:capital-efficient}}

   "s37-profit-maximizer-two-resolver-split-outcomes"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:multi-step :high-capital}}

   "s67-reentrancy-callback"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:reentrancy :callback}}

    ;; ── Adversarial: forking-strategist ───────────────────────────────────
   "s26-forking-strategist-l1-reversal"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s27-forking-strategist-l2-fork"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s28-forking-strategist-late-escalation-rejected"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step}}

   "s29-forking-strategist-seller-escalates"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :reactive}}

   "s30-forking-strategist-double-loss"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s31-forking-strategist-all-levels-confirm"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :high-capital}}

   "s32-forking-strategist-premature-settlement-rejected"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step}}

   "s33-forking-strategist-two-escrow-fork-isolation"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :capital-efficient}}

   ;; ── Adversarial: Collusion Stress ───────────────────────────────────
   "s42-resolver-buyer-bribery-loop"
   {:scenario/type    :adversarial
    :adversary/type   :colluder
    :adversary/traits #{:multi-agent :bribery}}

   "s108-coverage-consumed-by-slash"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:senior-bond :coverage-consumption}}

   "s109-coverage-exhausted-by-slash"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:senior-bond :coverage-exhaustion}}

   "s110-multi-junior-coverage"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:senior-bond :multi-junior-coverage}}

   "s111-gradual-coverage-exhaustion"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:senior-bond :gradual-exhaustion}}

   "s112-coverage-exceeded-error"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:senior-bond :coverage-exceeded-error}}

   "s113-insurance-bps-override"
   {:scenario/type :stress
    :adversary/type :coverage-economics
    :adversary/traits #{:insurance-bps-override}}

   "s43-auth-rejected-then-authorized-recovery"
   {:scenario/type :edge-case
    :tests #{:authorization-recovery :unauthorized-rejection}}

   "s43-dr3-kleros-reversal-slash"
   {:scenario/type :reversal
    :asserts #{:reversal-slash-executed :challenge-bounty-paid :escalation-layer-protection}}

   "s44-escalation-tier-mismatch-rejected"
   {:scenario/type :edge-case
    :tests #{:escalation-tier :authorization-enforcement}}

   "s45-stale-module-snapshot-rejects-legacy-resolver"
   {:scenario/type :governance
    :tests #{:module-snapshot :authorization-enforcement}}

   "s46a-settlement-before-escalation-window-edge"
   {:scenario/type :timing-boundary
    :tests #{:settlement-race :escalation-rejection}}

   "s46b-escalation-before-settlement-window-edge"
   {:scenario/type :timing-boundary
    :tests #{:settlement-race :escalation-precedence}}

   "s47a-appeal-window-last-second-settlement"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :boundary-inclusive}}

   "s47b-appeal-window-plus-one-rejected"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :boundary-rejection}}

   "s48-max-escalation-exact-boundary"
   {:scenario/type :escalation-mechanism
    :tests #{:max-level :escalation-acceptance}}

   ;; ── Adversarial: Stake Stress ───────────────────────────────────
   "s45-flash-loan-stake-inflation"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:flash-loan :stake-inflation}}

   ;; ── Boundary & Timing Tests ──────────────────────────────────────
   "s49-appeal-deadline-boundary"
   {:scenario/type :boundary-test
    :tests #{:appeal-deadline :timing :determinism}}

   "s50-false-assertion-unchallenged"
   {:scenario/type :monitoring-assumption
    :tests #{:assumption-validation :monitoring-failure}}

   "s51-same-block-challenge-finalize-race"
   {:scenario/type :mev-ordering
    :tests #{:ordering-determinism :finality :race-condition}}

   "s51-inverse-same-block-escalate-then-finalize"
   {:scenario/type :mev-ordering
    :tests #{:ordering-determinism :finality :race-condition :inverse-ordering}}

   "s51c-deadline-matrix-execute-then-escalate"
   {:scenario/type :timing-boundary
    :tests #{:ordering-determinism :deadline-matrix :execute-then-escalate}}

   "s51d-deadline-matrix-escalate-then-execute"
   {:scenario/type :timing-boundary
    :tests #{:ordering-determinism :deadline-matrix :escalate-then-execute}}

   "s52-yield-accrued-during-dispute"
   {:scenario/type :accounting
    :tests #{:yield-accounting :fund-conservation :settlement}}

   "s53-reentrant-withdrawal-guard"
   {:scenario/type :settlement-ledger
    :tests #{:atomicity :ledger-isolation :idempotence}}

   "s54-multi-claim-ledger-isolation"
   {:scenario/type :settlement-ledger
    :tests #{:ledger-isolation :per-escrow-accounting :claim-independence}}

   "s55-resolver-unavailable-timeout-fallback"
   {:scenario/type :governance
    :tests #{:resolver-availability :timeout-fallback :state-recovery}}

   "s56-resolver-diversity"
   {:scenario/type :governance
    :tests #{:resolver-rotation :state-continuity :multi-authority}}

   "s57-corruption-cost-vs-profit"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:corruption :economic-attack}}

   "s58-watchdog-valid-challenge"
   {:scenario/type :challenge-mechanism
    :tests #{:challenge-acceptance :appeal-flow :bounty-allocation}}

   "s59-watchdog-false-challenge-loss"
   {:scenario/type :challenge-mechanism
    :tests #{:false-challenge :bond-forfeiture :appeal-rejection}}

   "s60-resolver-abstention-timeout-griefing"
   {:scenario/type :timeout-mechanism
    :tests #{:resolver-abstention :timeout-fallback :auto-cancel}}

   "s61-fee-on-transfer-token-handling"
   {:scenario/type :token-handling
    :tests #{:fee-on-transfer :settlement-accuracy :ledger-balance}}

   "s62-multi-appeal-escalation-chain"
   {:scenario/type    :escalation-mechanism
    :adversary/type   :colluder
    :tests #{:challenge-chain :escalation-flow :multi-level-appeal}}

   "s62-cross-token-isolation-under-dispute-load"
   {:scenario/type :stress
    :tests #{:cross-token :concurrent-disputes :ledger-isolation}}

   "s62-cross-token-fee-on-transfer-under-dispute-load"
   {:scenario/type :stress
    :tests #{:fee-on-transfer :concurrent-disputes :ledger-isolation}}

   "s62-cross-token-parallel-appeal-depths-under-dispute-load"
   {:scenario/type :stress
    :tests #{:cross-token :concurrent-disputes :escalation-isolation}}

   "s62-resolver-capacity-concurrent-dispute-load"
   {:scenario/type :stress
    :tests #{:resolver-capacity :concurrent-disputes :dispute-flooding}}

   "s63-frivolous-appeal-slashing"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:frivolous-appeal :bond-forfeiture}}

   "s64-minimal-bond-edge-case"
   {:scenario/type :parameter-sensitivity
    :tests #{:bond-constraints :minimal-amounts :edge-case-resolution}}

   "s65-appeal-after-settlement-rejected"
   {:scenario/type :timing-boundary
    :tests #{:post-settlement-appeal :deadline-enforcement :late-appeal-rejection}}

   "s68-double-settlement-guard"
   {:scenario/type :idempotence
    :tests #{:double-settlement-rejection :idempotent-settlement :guard-mechanics}}

   "s69-stale-dispute-cleanup"
   {:scenario/type :state-management
    :tests #{:stale-dispute-handling :state-recovery :old-dispute-cleanup}}

   "s70-large-escrow-resolution"
   {:scenario/type :scale-test
    :tests #{:large-amounts :fee-precision :high-value-settlement}}

   "s71-zero-fee-settlement"
   {:scenario/type :edge-case
    :tests #{:zero-fee-handling :fee-extraction :minimal-fees}}

   "s72-challenge-during-appeal-window"
   {:scenario/type :challenge-mechanism
    :tests #{:appeal-window-challenge :in-window-acceptance :appeal-timing}}

   "s73-challenge-after-appeal-window-closed"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window-expiry :late-challenge-rejection :deadline-enforcement}}

   "s74-multi-escrow-parallel-disputes"
   {:scenario/type :stress-test
    :tests #{:parallel-disputes :resolver-capacity :independence :multi-workflow}}

   "s75-receiver-cancels-after-dispute"
   {:scenario/type :authorization-check
    :tests #{:receiver-cancel-during-dispute :authorization-enforcement}}

   "s76-sender-cancel-during-appeal"
   {:scenario/type :authorization-check
    :tests #{:cancel-during-appeal-window :cancel-blocking}}

   "s77-escalation-rejected-wrong-layer"
   {:scenario/type :protocol-enforcement
    :tests #{:escalation-availability :layer-enforcement :unsupported-features}}

   "s78-many-appeals-eventually-rejects"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:frivolous-appeals :repeated-attacks}}

   "s79-partial-resolution-release"
   {:scenario/type :settlement-variants
    :tests #{:partial-release :settlement-flexibility :split-funds}}

   "s80-disputed-escrow-expiry-settlement"
   {:scenario/type :timing-integration
    :tests #{:escrow-expiry-during-dispute :post-expiry-settlement :deadline-ordering}}

   "s81-appeal-deadline-boundary-before"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :settlement-timing :pre-deadline}}

   "s82-appeal-deadline-boundary-exact"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :boundary-exact :settlement-eligible}}

   "s83-appeal-deadline-boundary-after"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :settlement-timing :post-deadline}}

   "s84-false-assertion-unchallenged"
   {:scenario/type :monitoring-assumption
    :tests #{:honest-majority :unchallenged-resolution :assumption-validation}}

   "s85-watchdog-challenges-resolution"
   {:scenario/type :watchdog-flow
    :tests #{:challenge-mechanics :evidence-handling :watchdog-protection}}

   "s86-reentrant-withdrawal-guard"
   {:scenario/type :settlement-security
    :tests #{:reentrancy-guard :atomic-settlement :double-execution}}

   "s87-cross-escrow-isolation"
   {:scenario/type :settlement-isolation
    :tests #{:cross-escrow-ledger :dispute-release-mix :ledger-integrity}}

   "s88-resolution-with-conflicting-evidence"
   {:scenario/type :evidence-handling
    :tests #{:evidence-types :conflicting-evidence :resolver-discretion}}

   "s89-dispute-resolution-with-zero-appeal-window"
   {:scenario/type :protocol-variant
    :tests #{:fast-path-resolution :zero-appeal-window :kleros-mode}}

   "s90-resolver-capacity-stress"
   {:scenario/type :stress
    :tests #{:multi-dispute-resolution :capacity-limits :parallel-workload}}

   "s91-governance-snapshot-dispute-state"
   {:scenario/type :governance
    :tests #{:snapshot-isolation :dispute-state :governance-update}}

   "s92-settlement-zero-amount-edge"
   {:scenario/type :edge-case
    :tests #{:minimum-amount :ledger-precision :tiny-escrow}}

   "s93-multiple-appeals-with-refund"
   {:scenario/type :settlement-variants
    :tests #{:refund-path :multiple-appeals :appeal-refund}}

   "s94-dispute-timeout-auto-refund"
   {:scenario/type :timing-integration
    :tests #{:resolver-timeout :auto-refund :inaction-fallback}}

   "s95-resolution-challenge-and-counter"
   {:scenario/type :adversarial
    :adversary? true
    :adversary/type :evidence-manipulator
    :tests #{:evidence-complexity :challenge-counter :adversarial-evidence}}

   "s96-multi-token-dispute"
   {:scenario/type :stress
    :tests #{:multi-token :token-isolation :ledger-per-token}}

   "s97-appeal-after-settlement-attempt"
   {:scenario/type :edge-case
    :tests #{:settlement-finality :post-settlement-appeal :boundary-enforcement}}

   "s98-receiver-cancel-after-auto-cancel"
   {:scenario/type :race-condition
    :tests #{:auto-cancel-deadline :receiver-cancel :race-condition}}

   "s99-large-escrow-fee-impact"
   {:scenario/type :stress
    :tests #{:large-amount :fee-arithmetic :scale-limits}}

   "s100-deny-then-resolver-releases"
   {:scenario/type :settlement-variants
    :tests #{:recipient-deny :denial-override :release-override}}

   ;; ── Cancellation extended scenarios (H3, boundary) ──────────────────────
   "s-extortion-unilateral-cancel"
   {:scenario/type :cancellation
    :tests #{:unilateral-cancel :extortion :h3}}

   "s-extortion-unilateral-cancel-dual"
   {:scenario/type :cancellation
    :tests #{:unilateral-cancel :dual-cancel :h3}}

   "s-same-timestamp-cancel-vs-dispute"
   {:scenario/type :timing-boundary
    :tests #{:same-timestamp :cancel-dispute-ordering}}

   "s-same-timestamp-dispute-vs-cancel"
   {:scenario/type :timing-boundary
    :tests #{:same-timestamp :dispute-cancel-ordering}}

   ;; ── Cancellation time gap fills (S9) ─────────────────────────────
   "s-auto-cancel-time-via-keeper"
   {:scenario/type :timing-integration
    :tests #{:auto-cancel-time :automate-timed-actions :keeper-auto-cancel}}

   "s-auto-cancel-time-boundary"
   {:scenario/type :timing-boundary
    :tests #{:auto-cancel-time :deadline-boundary :t-minus-1 :t-exact}}

   "s-auto-cancel-time-orphaned-by-dispute"
   {:scenario/type :griefing-protection
    :tests #{:auto-cancel-time :dispute-auto-cancel-on-disputed :griefing-closed :not-in-solidity}}

   ;; ── Cancellation time same-timestamp gap fills (S11) ──────────────
   "s-same-timestamp-auto-cancel-vs-dispute"
   {:scenario/type :timing-boundary
    :tests #{:same-timestamp :auto-cancel-time :automate-timed-actions :dispute-ordering}}

   "s-same-timestamp-dispute-vs-auto-cancel"
   {:scenario/type :timing-boundary
    :tests #{:same-timestamp :auto-cancel-time :automate-timed-actions :dispute-ordering
             :griefing-closed}}

   ;; ── Cancel strategy gap fills ──────────────────────────────────
   "s-cancel-strategy-mutual-only"
   {:scenario/type :cancellation
    :tests #{:cancel-strategy :mutual-only :unilateral-cancel-false}}

   "s-cancel-strategy-can-cancel-dominates"
   {:scenario/type :cancellation
    :tests #{:cancel-strategy :can-cancel-dominates :authorization-enforcement}}

   "s-sender-cancel-after-auto-cancel-deadline"
   {:scenario/type :cancellation
    :tests #{:auto-cancel-time :manual-cancel-before-keeper :unilateral-cancel}}

   "s-auto-cancel-due-on-disputed-time-not-passed"
   {:scenario/type :timing-boundary
    :tests #{:auto-cancel-due-on-disputed :time-not-passed :automate-timed-actions :griefing-protection}}

   "s-auto-cancel-due-on-disputed-pending-settlement"
   {:scenario/type :griefing-protection
    :tests #{:auto-cancel-due-on-disputed :pending-settlement-blocks :keeper-dispatch}}

   "s114-resolution-module-mutation"
   {:scenario/type :governance
    :tests #{:resolution-module :mid-dispute-mutation :snapshot-immutability}}

   "s115-resolution-refused-timeout"
   {:scenario/type :kleros-ruling-0
    :tests #{:arbitrator-refusal :timeout-fallback :auto-cancel-refused}}

   "s116-resolution-refused-then-resolved"
   {:scenario/type :kleros-ruling-0
    :tests #{:arbitrator-refusal :governance-recovery :resolve-after-refusal}}})

(defn- scenario-registry-entries []
  (mapcat (fn [[display-name entry]]
            (cond
              (map? entry)
              [{:display-name display-name
                :scenario entry}]

              (vector? entry)
              (mapv (fn [scenario]
                      {:display-name display-name
                       :scenario scenario})
                    entry)

              :else
              [{:display-name display-name
                :entry entry}]))
          all-scenarios))

(defn- validate-registry-entry!
  [{:keys [display-name scenario entry]}]
  (when-not (map? scenario)
    (throw (ex-info "Malformed invariant scenario registry entry"
                    {:display-name display-name
                     :entry entry
                     :reason :entry-not-a-scenario-map})))
  (sid/validate-scenario-id! (:scenario-id scenario))
  (when-let [theory (:theory scenario)]
    (when-not (map? theory)
      (throw (ex-info "Malformed invariant scenario theory"
                      {:display-name display-name
                       :scenario-id (:scenario-id scenario)
                       :reason :theory-not-a-map}))))
  (let [validation (replay-validation/validate-scenario (dissoc scenario :theory)
                                                        metrics/base-metrics
                                                        {:strict-validation? false})]
    (when-not (:ok validation)
      (throw (ex-info "Malformed invariant scenario definition"
                      {:display-name display-name
                       :scenario-id (:scenario-id scenario)
                       :validation validation})))))

(defn validate-all-scenarios!
  "Validate the full invariant scenario registry.

   Checks:
   - every registry entry resolves to one or more scenario maps
   - every scenario has a stable, explicit :scenario-id
   - :scenario-id values are unique across the registry
   - each scenario passes structural replay validation
   - scenario-type-registry covers the same scenario-id set"
  []
  (let [entries (scenario-registry-entries)
        _       (doseq [entry entries]
                  (validate-registry-entry! entry))
        ids     (mapv (comp :scenario-id :scenario) entries)
        dupes   (->> ids frequencies
                     (filter (fn [[_ n]] (> n 1)))
                     (mapv first))
        ids-set (set ids)
        type-ids (set (keys scenario-type-registry))
        missing-type-ids (vec (sort (set/difference ids-set type-ids)))
        orphan-type-ids (vec (sort (set/difference type-ids ids-set)))]
    (when (seq dupes)
      (throw (ex-info "Duplicate invariant scenario-id(s) detected"
                      {:duplicates dupes})))
    (when (seq missing-type-ids)
      (throw (ex-info "Scenario type registry is missing scenario-id(s)"
                      {:missing missing-type-ids})))
    (when (seq orphan-type-ids)
      (throw (ex-info "Scenario type registry contains orphan scenario-id(s)"
                      {:orphan orphan-type-ids})))
    true))

;; Startup validation ---------------------------------------------------------

(validate-all-scenarios!)
