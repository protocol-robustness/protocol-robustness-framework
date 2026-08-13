(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections for run-collection.
   Scenario IDs are resolved to file paths via resolver-sim.io.scenarios/scenario-path.

   THREE GROUPING CONCEPTS exist in this codebase.
   They differ in registration, execution model, and purpose:

   ─────────────────────────────────────────────────────────────────
   SUITE  (run:scenario:suite)
     Registered, curated scenario ID list in `suites` (this file).
     Executes in a single Clojure process via run-collection.
     Used by: CI gates, golden report refresh, coverage tiers.
     Examples: :dispute-resolution-scenarios, :sew-yield-scenarios
     Keyword namespace: bare keywords (no prefix).

   BENCHMARK PACK SUITE  (bb benchmark:run)
     Same registry as suites but kept in `pack-suites` to avoid
     duplicate scenario-id validation errors.  Referenced by
     benchmark pack manifests via :benchmark/scenario-suite.
     Executes inside a benchmark evidence bundle.
     Keyword namespace: :suite/ prefix (e.g. :suite/sew-dispute-safety-v1).
     NOTE: pack suites currently share the exact same path lists
     as functional suites — they are aliases, not distinct sets.

   SEARCH  (run:scenario:search)
     NOT a registered grouping.  Ad-hoc text search across all scenario
     file paths and contents (case-insensitive substring).  Executes
     each match in a separate subprocess.  Results are not suitable
     for canonical CI, benchmark definitions, or published evidence.
     No file in this namespace implements search logic;
     the selector runs entirely in bb.edn.

   ─────────────────────────────────────────────────────────────────
   OVERLAP WARNING:
   The 44 dispute-resolution files (S-DR-001..S-DR-085) appear in
   THREE groupings — one suite + two pack suites.  This is by design
   (pack suites reuse the same ID lists), but means the same
   scenarios execute under different names depending on the entry
   point.  See `pack-suites` docstring for details.

   Suite naming:
     :yield-provider-scenarios — standalone `yield-v1` provider scenarios (scenarios/Y01..Y05)
     :sew-yield-scenarios       — Sew escrow + yield integration (scenarios/S*)
     :yield-scenarios           — removed June 2026; use :sew-yield-scenarios"
  (:require [resolver-sim.io.scenarios :as sc]
            [resolver-sim.config.paths :as paths]))

;; ── Suite path resolution ─────────────────────────────────────────────────────
;; Scenario IDs are resolved to file paths via sc/scenario-path.
;; Only trace-reference suites (sew-reference-v1) store explicit paths.

(def ^:private yield-provider-scenario-ids
  ["Y01_vault-shared-liquidity"
   "Y02_vault-shortfall-partial-withdraw"
   "Y03_vault-risk-override-schedule-shadowing"
   "Y04_vault-recovery-claim-deferred"
   "Y05_auto-generated-shortfall"
   "Y06_multi-party-pro-rata-shortfall"
   "Y07_adversarial-shortfall-exploit"
   "Y08_optimal-strategy-load-stress"
   "Y09_shared-pool-zero-liquidity-pro-rata"
   "Y10_shared-pool-largest-remainder-pro-rata"
   "Y11_cap-constrained-pro-rata-redistribution"
   "Y12_cascading-cap-redistribution"
   "Y13_all-caps-residual-liquidity"
   "Y14_round-two-shared-liquidity"])

(def ^:private dispute-resolution-scenario-ids
  ["S-DR-001-basic-release-ruling"
   "S-DR-002-basic-refund-ruling"
   "S-DR-003-duplicate-dispute-rejected"
   "S-DR-004-timeout-default-resolution"
   "S-DR-010-missing-evidence"
   "S-DR-011-contradictory-evidence"
   "S-DR-012-late-evidence-rejected"
   "S-DR-013-evidence-at-deadline"
   "S-DR-020-false-claimant-slashed"
   "S-DR-021-griefing-claim-cost"
   "S-DR-022-lazy-counterparty-timeout"
   "S-DR-030-biased-resolver-appealed"
   "S-DR-031-colluding-resolver-detected"
   "S-DR-032-resolver-insufficient-stake"
   "S-DR-040-finality-blocked-during-appeal"
   "S-DR-041-finality-after-appeal-window"
   "S-DR-042-duplicate-claim-after-finality-rejected"
   "S-DR-043-payout-shortfall-deferred"
   "S-DR-044-slash-obligation-unmet-recorded"
   "S-DR-050-resolution-module-plus-kleros"
   "S-DR-051-challenge-without-escalation"
   "S-DR-052-custom-resolver-bypasses-module"
   "S-DR-053-module-false-fallthrough"
   "S-DR-054-missing-escalation-level"
   "S-DR-055-sender-cancel-refund"
   "S-DR-056-evidence-non-disputed-rejected"
   "S-DR-060-rotate-resolver-mid-dispute"
   "S-DR-061-slash-propose-execute"
   "S-DR-062-rotate-resolver-rejected"
   "S-DR-063-slash-appeal-upheld"
   "S-DR-064-slash-appeal-rejected-executed"
   "S-DR-070-empty-string-resolver-rejected"
   "S-DR-071-governance-rotate-biased-ruling"
   "S-DR-072-resolver-unavailable-timeout"
   "S-DR-073-capacity-exhaustion-permanent-lock"
   "S-DR-074-governance-capacity-bypass"
   "S-DR-075-insufficient-bond-deterrence"
   "S-DR-076-non-governance-rotate-rejected"
   "S-DR-080-stake-capacity-enforced"
   "S-DR-081-stake-capacity-bypass"
   "S-DR-082-stake-capacity-sufficient"
   "S-DR-083-evidence-after-resolution"
   "S-DR-084-evidence-after-settlement-rejected"
   "S-DR-085-repeated-frivolous-disputes"
   "S-DR-086-evidence-after-resolver-rotation"
   "S-DR-087-evidence-after-governance-fee-update"
   "S-DR-088-evidence-before-deadline"
   "S-DR-089-freeze-recovery"
   "S-DR-090-circuit-breaker-recovery"
   "S-DR-091-unavailable-resolver-mid-dispute"
   "S-DR-092-automate-timed-actions"
   "S-DR-093-evidence-during-freeze"
   "S-DR-094-evidence-at-capacity"
   "S-DR-095-evidence-after-settlement-attempt-rejected"
   "S-DR-096-evidence-forking-strategist-combined"
   "S-DR-097-appeal-ev-safe-calibrated"
   "S-DR-098-appeal-ev-under-deterred"
   "S-DR-099-appeal-ev-correct-blocked"
   "S-DR-100-resolver-response-within-window"
   "S-DR-101-resolver-response-window-expired-fresh-resolver"
   "S-DR-102-resolver-response-deadline-boundary"
   "S-DR-103-set-resolution-module-governance"
   "S-DR-104-set-resolution-module-config-drift"
   "S-DR-105-prorata-slash-allocation-capped"
   "S-DR-106-prorata-slash-allocation-zero-weight"
   "S-DR-107-recipient-cancel-during-dispute-rejected"
   "S-DR-108-recipient-cancel-after-finality-rejected"
   "DR-PR-002-fraud-group-prorata-shortfall"])

(def ^:private sew-reference-scenario-paths
  "Curated reference scenarios for external verifier reproducibility.
   All scenarios are cancellation/terminal-state traces that exercise
   the core griefing-protection, same-timestamp, and auto-cancel paths.
   Each file is a standalone .trace.json — not an executable scenario."
  [(str (paths/traces-dir) "/s-auto-cancel-time-via-keeper.trace.json")
   (str (paths/traces-dir) "/s-auto-cancel-time-boundary.trace.json")
   (str (paths/traces-dir) "/s-auto-cancel-time-orphaned-by-dispute.trace.json")
   (str (paths/traces-dir) "/s-same-timestamp-auto-cancel-vs-dispute.trace.json")
   (str (paths/traces-dir) "/s-same-timestamp-dispute-vs-auto-cancel.trace.json")
   (str (paths/traces-dir) "/s-extortion-unilateral-cancel.trace.json")
   (str (paths/traces-dir) "/s-extortion-unilateral-cancel-dual.trace.json")])

(def ^:private reference-validation-scenario-ids
  ["S25_profit-maximizer-slash-lifecycle"
   "S62_resolver-throughput-exhaustion"
   "S05_pending-settlement-execute"])

(def ^:private yield-scenario-ids
  ["S78_yield-aave-partial-liquidity-release"
   "S78_yield-negative-yield-release-path"
   "S79_yield-aave-partial-liquidity-dispute-resolution"
   "S79_yield-negative-yield-dispute-refund-path"
   "S80_yield-mostly-liquid-partial-liquidity"
   "S80_yield-aave-partial-liquidity-governance-disable-post-create"
   "S81_escrow-yield-may-be-partially-deferred"
   "S82_shortfall-recovery-cycle"
   "S83_yield-accrual-reorg-race"
   "S87_resolver-frozen-while-yield-due"
   "S88_yield-accrual-efficiency"
   "S103_negative-yield-shortfall-cascade"
   "S108_negative-yield-mild"
   "S109_negative-yield-severe-repair"
   "S110_resolver-yield-accrual"])

(def ^:private shortfall-scenario-ids
  ["S-DR-043-payout-shortfall-deferred"
   "S82_shortfall-recovery-cycle"
   "S103_negative-yield-shortfall-cascade"
   "S104_resolver-stake-shortfall"])

(def ^:private redistribution-fairness-scenario-ids
  ["S82_shortfall-recovery-cycle"
   "S103_negative-yield-shortfall-cascade"
   "S105_policy-response-cascade"
   "S108_senior-coverage-consumed-by-slash"
   "S114_multi-escrow-pool-shortage"
   "S-DR-043-payout-shortfall-deferred"
   "S110_multi-junior-coverage"
   "Y04_vault-recovery-claim-deferred"])

(def ^:private force-authorisation-scenario-ids
  ;; Scenario IDs are filenames and therefore case-sensitive in classpath
  ;; resources as well as on Linux filesystems.
  ["DR-FA-001-force-authorisation-basic"
   "DR-FA-002-force-authorisation-expired"])

(def ^:private reversal-slashing-scenario-ids
  ["DR-N-001-reversal-slash-appeal-lifecycle"
   "DR-N-002-reversal-slash-appeal-rejected"
   "DR-N-003-reversal-slash-appeal-window-expired"
   "DR-N-004-reversal-slash-appeal-wrong-party"
   "DR-O-001-vindication-4-level"
   "DR-O-002-vindication-minimum-stake"
   "DR-O-003-vindication-zero-stake"
   "DR-P-001-force-reversal-slash"
   "DR-P-002-force-reversal-slash-idempotent"
   "DR-Q-001-challenge-bounty-reversal"
   "DR-Q-002-challenge-bounty-no-challenger"
   "DR-R-001-reversal-slash-insufficient-stake"
   "S-DR-097-appeal-ev-safe-calibrated"
   "S-DR-098-appeal-ev-under-deterred"
   "S-DR-099-appeal-ev-correct-blocked"])

;; ── Suite definitions ─────────────────────────────────────────────────────────
;; Use :scenario-ids for executable scenarios (resolved via sc/scenario-path)
;; or :paths for trace/reference files (used as-is).

(def suites
  "Suite keyword → {:scenario-ids [str ...] or :paths [str ...] :protocol-id ...}."
  {:dispute-resolution-scenarios {:scenario-ids dispute-resolution-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "Dispute-resolution scenarios"
                                  :description  "Sew dispute-resolution coverage scenarios."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :sew-yield-scenarios          {:scenario-ids yield-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "Sew yield integration scenarios"
                                  :description  "Sew escrow scenarios that exercise yield integration behavior."
                                  :kind         :file-path-suite
                                  :ci-tier      :integration}
   :yield-provider-scenarios     {:scenario-ids yield-provider-scenario-ids
                                  :protocol-id  "yield-v1"
                                  :title        "Yield provider scenarios"
                                  :description  "Standalone yield-v1 provider scenarios."
                                  :kind         :file-path-suite
                                  :ci-tier      :provider}
   :sew-reversal-slashing        {:scenario-ids reversal-slashing-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "Sew reversal-slashing scenarios"
                                  :description  "Reversal-reviewer slashing: appeal due process, multi-level vindication, governance force slashing, challenger bounty, and insufficient-stake accounting."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :sew-reference-v1             {:paths        sew-reference-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew reference v1 — external verifier suite"
                                  :description  "Curated reference scenarios for external-verifier reproducibility.
                                                   Tests cancellation griefing protection, auto-cancel-time, same-timestamp
                                                   ordering, and extortion resistance.  All files are standalone .trace.json."
                                  :kind         :file-path-suite
                                  :ci-tier      :reference}})

(def pack-suites
  "Benchmark pack suite keywords — used by benchmarks/packs/*/ manifests.

   NOTE — OVERLAP WITH FUNCTIONAL SUITES:
   Pack suites currently reference the EXACT SAME ID lists as functional
   suites.  They are semantic aliases, not distinct scenario sets.

     :suite/sew-dispute-safety-v1  ≡ :dispute-resolution-scenarios  (44 S-DR files)
     :suite/sew-yield-safety-v1    ≡ :sew-yield-scenarios           (15 S files)
     :suite/prf-replay-v1          ≡ :dispute-resolution-scenarios  (44 S-DR files)

   This means `bb benchmark:run escrow-dispute-v1.edn` and
   `bb run:scenario:suite dispute-resolution-scenarios` execute the
   same 44 scenarios through different entry points.

   Kept separate from `suites` to avoid duplicate scenario-id
   errors in file-backed suite registry validation.  When new
   protocol-specific scenarios are added for a benchmark pack,
   its pack suite should define its own ID list here."
  {:suite/sew-dispute-safety-v1  {:scenario-ids dispute-resolution-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "Sew dispute-safety benchmark suite"
                                  :description  "Sew escrow dispute, slashing, and liveness scenarios for benchmark execution."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :suite/sew-yield-safety-v1    {:scenario-ids yield-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "Sew yield-safety benchmark suite"
                                  :description  "Sew escrow + yield integration scenarios for benchmark execution."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :suite/prf-replay-v1          {:scenario-ids dispute-resolution-scenario-ids
                                  :protocol-id  "sew-v1"
                                  :title        "PRF deterministic replay benchmark suite"
                                  :description  "Core deterministic replay scenarios for benchmark execution.
                                    NOTE: currently points to Sew dispute-resolution scenarios because
                                    PRF-specific replay scenarios do not yet exist.  A genuine PRF replay
                                    suite would contain protocol-agnostic replay/evidence scenarios."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :suite/force-authorisation-custody-v1
   {:scenario-ids force-authorisation-scenario-ids
    :protocol-id "sew-v1"
    :title "Force-authorisation custody benchmark suite"
    :description "Force-authorisation grant, execution, expiry, and custody-ledger scenarios.
     Evaluated using the Sew protocol implementation."
    :kind :file-path-suite
    :ci-tier :coverage}
   :suite/sew-force-authorisation-custody-v1
   {:scenario-ids force-authorisation-scenario-ids
    :protocol-id "sew-v1"
    :title "Sew force-authorisation and custody benchmark suite (deprecated alias)"
    :description "Alias for :suite/force-authorisation-custody-v1."
    :kind :file-path-suite
    :ci-tier :coverage}
   :suite/reference-validation-v1 {:scenario-ids reference-validation-scenario-ids
                                   :protocol-id  "sew-v1"
                                   :title        "Reference validation v1 — protocol robustness scenarios"
                                   :description  "Simulator-backed scenarios for resolver accountability,
                                     liveness under adversarial load, and autopush settlement safety.
                                     Used by the protocol-robustness-v0 benchmark pack."
                                   :kind         :file-path-suite
                                   :ci-tier      :coverage}
   :suite/sew-reversal-slashing-v1
   {:scenario-ids reversal-slashing-scenario-ids
    :protocol-id  "sew-v1"
    :title        "Sew reversal-slashing benchmark suite"
    :description  "Reversal-reviewer slashing scenarios: appeal due process, multi-level vindication, governance force slashing, challenger bounty, and insufficient-stake accounting."
    :kind         :file-path-suite
    :ci-tier      :coverage}
   :suite/sew-shortfall-allocation-v0
   {:scenario-ids shortfall-scenario-ids
    :protocol-id  "sew-v1"
    :title        "Shortfall allocation v0 — partial fill and pro-rata scenarios"
    :description  "Sew scenarios exercising yield shortfall with partial fill,
       negative yield cascade with deferred recovery, and resolver stake
       shortfall. Used by the shortfall-allocation-v0 benchmark pack."
    :kind         :file-path-suite
    :ci-tier      :coverage}
   :suite/prf-redistribution-fairness-v0
   {:scenario-ids redistribution-fairness-scenario-ids
    :protocol-id "sew-v1"
    :title "PRF redistribution fairness v0 benchmark suite"
    :description "Eight registered scenarios for the experimental redistribution fairness benchmark."
    :kind :file-path-suite
    :ci-tier :coverage}
   :suite/yield-provider-scenarios
   {:scenario-ids yield-provider-scenario-ids
    :protocol-id  "yield-v1"
    :title        "Yield provider benchmark suite"
    :description  "Standalone yield-v1 provider scenarios for partial-fill
       benchmark evaluation. Used by the yield-partial-fill-v0 benchmark pack."
    :kind         :file-path-suite
    :ci-tier      :provider}})

;; ── Suite path resolution ─────────────────────────────────────────────────────
;; Scenario IDs are resolved to file paths via sc/scenario-path.
;; Suite entries with :paths (trace references) are returned as-is.

(defn- resolve-suite-registry
  "Return the registry map for a suite keyword — checks `suites` first,
   then `pack-suites` for benchmark pack references."
  [suite-key]
  (or (get suites suite-key) (get pack-suites suite-key)))

(defn suite-protocol-id
  "Protocol registry id for a named path suite (default sew-v1)."
  [suite-key]
  (or (:protocol-id (resolve-suite-registry suite-key)) "sew-v1"))

(defn suite-paths
  "Return resolved file paths for a registered suite keyword, or nil if unknown.
   Suite entries with :scenario-ids are resolved via sc/scenario-path.
   Entries with :paths (trace/reference files) are returned as-is."
  [suite-key]
  (when-let [defn (resolve-suite-registry suite-key)]
    (if-let [ids (:scenario-ids defn)]
      (mapv sc/scenario-path ids)
      (:paths defn))))

(defn suite-definition
  "Return the registry entry for a named suite keyword, or nil if unknown."
  [suite-key]
  (resolve-suite-registry suite-key))

(defn suite-metadata
  "Return suite metadata without the scenario list."
  [suite-key]
  (some-> (suite-definition suite-key)
          (dissoc :scenario-ids :paths)))

(defn suite-path-count
  "Return the number of scenario files registered for suite-key."
  [suite-key]
  (count (or (suite-paths suite-key) [])))

(defn known-suite-keys
  "Return sorted vector of all registered suite keywords."
  []
  (vec (sort (keys suites))))

(defn known-suite-definitions
  "Return the full registry map for all named scenario suites."
  []
  suites)
