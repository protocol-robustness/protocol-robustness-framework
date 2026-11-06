# PRO-85 local Sew deployment and recovery rehearsal

Date: 2026-10-28

## Result

The existing `protocol-deployments` local rehearsal completed with `result: passed` against a fresh Anvil chain. It used the exact `sew-core` bundle root `e40550c65fad0007b303384c9b1a592f88d1dfcd7a1c12c3cb9d64e168666507`, exported from clean Sew source revision `6138a9fddf953cd5f651f6367c35cc9410592fd7`.

- Chain: fresh Anvil, chain ID 31337.
- Bundle deployments: 7; each recorded runtime verification passed.
- Local transactions: 66; deployment, module activation and handoff, selected escrow lifecycle, fees and withdrawals completed.
- Public-chain transactions: 0.
- Harness output: `/tmp/pro85-sew-core-local-rehearsal-20261028.json`.
- Command: `SEW_CORE_BUNDLE_DIR=/tmp/sew-pro87-bundle SEW_ERC20_ARTIFACT=/tmp/sew-pro87-token-out/ERC20Mock.sol/ERC20Mock.json SEW_LOCAL_REHEARSAL_OUTPUT=/tmp/pro85-sew-core-local-rehearsal-20261028.json npm run rehearse:sew-core-local` in `protocol-deployments`.

## Qualification limits

The harness explicitly disables the EVM runtime code-size limit and uses an ephemeral local ERC20 mock and zero-delay Timelock. This does not qualify Base Sepolia deployability, real USDC behaviour, production governance parameters, or public-chain operations. In particular, PRO-87 remains blocked by the measured 38,983-byte EscrowVault runtime against the currently observed 24,576-byte limit.

This run supplies local integration evidence for PRO-85 but does not replace the issue's dedicated interrupted-broadcast/replacement recovery fault cases or independent review. Do not close PRO-85 on this run alone.
