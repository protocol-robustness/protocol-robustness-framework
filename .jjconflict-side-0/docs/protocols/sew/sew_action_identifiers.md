# Sew Action Identifier Taxonomy

This document classifies Sew actions based on their requirements for resource identifiers (workflow-id, token, etc.).

## 1. Allocates workflow-id
- create_escrow

## 2. Requires workflow-id
- raise-dispute
- execute-resolution
- execute-pending-settlement
- escalate-dispute
- challenge-resolution
- release
- sender-cancel
- recipient-cancel
- withdraw-escrow
- auto-cancel-disputed
- trigger-accrue
- claim-deferred-yield
- set-token-liquidity-crunch

## 3. Does not use workflow-id (Governance/Module/Global)
- governance-update-fee
- set-paused
- withdraw-fees
- automate-timed-actions
- register-resolver-bond
- register-senior-bond
- delegate-to-senior

## 4. Uses other target keys
- propose-fraud-slash (slash-id, resolver-addr)
- resolve-appeal (slash-id, appeal-upheld)
- execute-fraud-slash (slash-id)
- rotate-dispute-resolver (new-resolver-addr)
- register-stake (token, stake-amount)
- withdraw-stake (token, stake-amount)

## 5. Legacy accepted forms
- :id (via compat/wf-id) is accepted across all 'Requires workflow-id' actions for backwards compatibility.
