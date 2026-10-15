# Agent-readable accounting review interaction

The walkthrough extends `/demos/partial-fill-accounting/#accounting-review`.
It uses the separate retained V3 run; the original seven V2 snapshots remain
under their existing provenance and are not combined into a single ledger.

## Diagram

Canonical economic evidence (funding, claims, balances, transfers, policy)
    ↓
Deterministic accounting statement and reconciliation
    ↓ branches into two side-by-side paths on desktop; stacks on mobile
    Left: accounting authority
        Verification → reviewer acceptance → statement ratification
    Right: capital authority
        Operation-specific assurance → AO admission → protected execution

The two paths are independent. No arrow grants execution authority from
accounting ratification. Following actual execution, new observed settlement
evidence supports renewed accounting. A visible text caption states these
relationships so the drawing is not the only explanation.

## Candidate selector and exact amounts

- A is a supported local accounting interpretation: allocations 35/21/14,
  10 restricted, 30 unmet requests. Conservation and policy checks pass.
- B is synthetic: proposed allocations 40/24/16, zero restricted, 20 unmet.
  Conservation passes, restriction and recipient-policy checks fail. No
  observation or payment is attributed to B.
- The post-settlement retained statement is distinct: observed claimant
  payments 35/21/14, separate 10-unit Carol refund, zero liability and custody.

The page labels all units as local test-token units. Carol's designation is
fixture policy; external beneficial ownership and obligation completeness
remain unverified.

## Browser simulation

Initial state: verification Pending, review Pending, ratification Not ratified.
Select A or B: reset all simulated decisions and verification to initial state.
Verify: display the selected candidate's retained qualification as Passed or
Failed. A failed result blocks acceptance and ratification.
Select role: Reviewer or Ratifier. This selection simulates a policy role and
does not authenticate the visitor.
Accept: enabled only for Reviewer, verification Passed, eligible candidate,
and review Pending. Changes review to Accepted; ratification remains Not ratified.
Reject: enabled only for Reviewer while review Pending. Sets Rejected and Blocked.
Ratify: enabled only for Ratifier, verification Passed and review Accepted.
Changes ratification to Ratified for the named accounting purpose.
Reset: restores all initial states and the Reviewer role.
All disabled buttons have visible explanations. Native buttons, focus outlines,
`aria-pressed` selection and live status text support keyboard and assistive use.

The browser produces no signature, authenticated history record or transaction.
This is stated next to the controls. A separate retained-results table describes
actual signed local decisions for A, B and post-settlement accounting, with
downloads of policies, subjects, signed histories, pins, canonical preimages,
verdicts and reproduction instructions. Reviewer and ratifier are distinct
local fixture authorities, not an externally appointed accounting body.

## Symbols and colours

- Green plus ✓ and a status label: an actually passed retained check or
  explicitly simulated successful state.
- Yellow plus ⚠: pending review, uncertainty or a limitation.
- Red plus ✕: failed qualification, rejection or blocked ratification.
- Grey plus an inactive label: not yet attempted or inactive.
- Small arrows show sequence within each path; no arrow joins accounting
  adoption to capital authorization. Colour never carries the meaning alone.
