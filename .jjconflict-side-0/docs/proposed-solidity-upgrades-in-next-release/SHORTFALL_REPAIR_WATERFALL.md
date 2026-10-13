# Shortfall Repair Waterfall Design (V2)

## Goal
Establish a structured mechanism to repair protocol-level yield shortfalls using funds recovered from resolver slashing or insurance pool reserves, ensuring domain separation between professional liability (slashing) and protocol stability (shortfall repair).

## Mechanism: The Repair Waterfall
The repair waterfall bridge connects the `liability` domain and the `settlement` domain.

### 1. Inflow: Liability Domain
Slashed funds, appeal bond forfeitures, and protocol fees are aggregated in the `InsurancePoolVault`. This vault maintains the `:liability/slash` and `:bond/forfeit` domains.

### 2. The Bridge: `trigger-repair-waterfall`
This operation performs a cross-domain balance transfer.

*   **Trigger**: A settlement outcome is detected where `total(settlement-claims) < principal`.
*   **Action**:
    1.  The `ResolutionModule` or `Governor` initiates a `repair-waterfall` for the affected `workflow-id`.
    2.  The `InsurancePoolVault` checks for available `:liability/slash` or `:bond/forfeit` reserves.
    3.  If funds are available, the `InsurancePoolVault` transfers funds to the `Escrow` contract.
    4.  The `Escrow` contract records the influx in the `:reserve/shortfall` domain of the `claimable-v2` structure.

### 3. Outflow: Settlement Domain
The `withdrawEscrow` process is updated to draw from the `:reserve/shortfall` domain *after* the principal-settlement domain is exhausted, ensuring users are made whole if the protocol has sufficient reserves.

### Domain Integrity
*   **Source Restriction**: Repair funds **cannot** be sourced directly from other escrow principals. They MUST be sourced from the `InsurancePoolVault`.
*   **Domain Segregation**: The `claimable-v2` structure prevents mixing repair funds with original principal, allowing auditors to distinguish between "Original Funds" and "Protocol-Provided Repair Funds."

## Migration to Solidity
The Solidity implementation will follow this pattern:
1.  `InsurancePoolVault` implements `requestRepair(uint256 workflowId, uint256 amount)`.
2.  `BaseEscrow` inherits a `ShortfallGuard` that restricts the `claimable` withdrawal path to use these repaired funds.
