S103 — Negative Yield / Shortfall Cascade

Scenario: s103-negative-yield-shortfall-cascade

Summary
- Principal: 10000
- Resolver fee (~1.5%): 150
- Amount after fee (AFA): 9850

Timeline and key values
- Seq 1 (6 months accrue at +5% APY): total-held ≈ 10092, unrealized-yield ≈ 242
- Market shock → liquidity shortfall (available-ratio 0.8), APY becomes -3%
- Finalize (cancel + withdraw under shortfall): fulfilled ≈ 8034, deferred ≈ 2009 (stays in held)
- Withdraw-escrow: buyer receives 8034 immediately; total-withdrawn incremented
- Recovery (liquidity becomes available)
- Claim-deferred-yield: deferred 2009 reclaimed and credited to escrow recipient

Notes
- All token keys are stored as keywords (e.g. :USDC) to avoid mixed-keymap ClassCastExceptions during diffing.
- Deferred amounts are treated as principal recovery and are credited directly to the escrow's claimable balance when recovered (not via the yield policy which allocates realized yield according to presets).

Suggested narrative (for docs):
"The Aave Cascade"
1. A buyer deposits 10k USDC. Fees reduce the active principal to 9850.
2. Positive yield accrues, temporarily increasing total-held.
3. A market shock causes liquidity shortfall (80% available): when the escrow finalizes, only 80% of the gross is immediately available; the remainder is deferred.
4. The buyer withdraws the immediately-available amount; the deferred portion remains as a shortfall on the yield module.
5. After recovery, the deferred funds are reclaimed and credited to the escrow as claimable; the protocol's accounting balances (total-held, claimable, total-withdrawn) reconcile.

Artifacts
- scenario file: scenarios/S103_negative-yield-shortfall-cascade.json
- key code: protocols/sew.clj (claim-deferred handling), yield/registry.clj (token keywordization), yield/accounting.clj (apply-liquidity-stress / claim-deferred)

"Shortfall-explicit": this scenario demonstrates explicit shortfall accounting where deferred amounts are preserved on the yield position and reclaimed later, ensuring the protocol never double-counts or loses funds.
