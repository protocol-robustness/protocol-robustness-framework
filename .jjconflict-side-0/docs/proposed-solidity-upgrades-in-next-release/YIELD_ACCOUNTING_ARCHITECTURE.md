# Yield Accounting Architecture (V2)

## Goal
To establish a clear separation between yield generation (market layer), workflow-level accrual (protocol layer), actor-level entitlement (account layer), and executable claimability (delivery layer).

## The Four Accounting Layers

## Yield Engine (V2)

The yield engine now uses a **fully data-driven architecture** (previously referred to as "liquid-lending-v2"). It leverages decision-based accrual and exact-ratio arithmetic, replacing the legacy double-based arithmetic in the old implementation.

### Key Architectural Improvements
1. **Decision-based Accrual**: Each position is evaluated via `accrual/accrual-decision`, handling edge cases like dust accumulation and exact arithmetic consistently.
2. **Exact-Ratio Math**: All interest, index, and share calculations use `resolver-sim.yield.exact-math` (rational/shares), eliminating rounding drift common in double-precision systems.
3. **Partial-Fill Integration**: Withdrawal fulfillment is now handled by the `partial-fill` engine, providing predictable, audit-ready shortfall and haircut outcomes.
4. **Telemetry Parity**: Integrated `emit-shortfall-event` for forensic auditability of all shortfall events.

   
2. **Workflow Yield Position / Accrual**
   - **Location**: `src/resolver_sim/protocols/sew/lifecycle.clj`
   - **Role**: Maps global indices to specific `workflow-id` positions. Tracks `unrealized` vs `realized` yield.
   
3. **Actor Entitlement Allocation**
   - **Location**: `src/resolver_sim/protocols/sew/accounting.clj` (via `policy.clj`)
   - **Role**: Computes the distribution of accrued yield to actors (Buyer/Seller/Resolver) based on `settlement` or `incentive` policies at the moment of dispute resolution.
   
4. **Claimable / Withdrawable Balances**
   - **Location**: `src/resolver_sim/protocols/sew/accounting.clj` (`:claimable-v2`)
   - **Role**: The executable delivery layer. Funds are tagged by domain for forensic auditability before `withdrawEscrow` is called.

## Domain Vocabulary
- `:settlement/principal`: Principal returned to Buyer/Seller.
- `:settlement/yield`: Market yield returned to Buyer/Seller.
- `:yield/protocol-fee`: Fees accrued by protocol from yield.
- `:yield/resolver-incentive`: Yield-based incentive for resolver.
- `:fees/resolver`: Fixed resolver fees.
- `:fees/protocol`: Fixed protocol fees.
- `:liability/slash-bounty`: Bounty paid for successful slash.
- `:bond/refund`: Appeal bond returned to winner.
- `:bond/forfeit`: Appeal bond forfeited.
- `:reserve/shortfall`: Shortfall repair funds.
