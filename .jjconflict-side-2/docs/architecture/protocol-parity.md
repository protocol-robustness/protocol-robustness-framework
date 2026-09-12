# Protocol Parity Requirements

This document tracks implementation gaps between the Clojure simulation framework (`sew-sim`) and the canonical Solidity contract implementation.

## Liquidity Guards

### 1. Escrow Creation Block
- **Status**: Implemented in simulation (`SewProtocol`).
- **Parity Note**: The `createEscrow` function in Solidity must explicitly check the liquidity status of the designated yield module (e.g., via Aave's `Pool` or `LendingPool` state) and revert with `InsufficientModuleLiquidity` if the module is in a `:shortfall` or `:frozen` state.

## Strategic Action Guards
- **Status**: Implemented in simulation (`SewProtocol`).
- **Parity Note**: The `executeResolution` and `challengeResolution` functions in Solidity must perform an on-chain check of the resolver's available (non-deferred) stake balance. If the liquid stake is insufficient to bond or slash, the action must revert.
