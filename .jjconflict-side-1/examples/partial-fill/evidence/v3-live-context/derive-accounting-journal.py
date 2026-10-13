#!/usr/bin/env python3
"""Derive the bounded V3 event projection from retained Foundry observations."""
import hashlib
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent
log_path = root / "v3-accounting-qualification.log"
log_text = log_path.read_text()
state_prefix = "PARTIAL_FILL_V3_ACCOUNTING_STATE_JSON "
transfer_prefix = "PARTIAL_FILL_TRANSFERS_JSON "
observations = []
transfer_observation = None
for line_number, line in enumerate(log_text.splitlines(), 1):
    if state_prefix in line:
        raw = line.split(state_prefix, 1)[1]
        item = json.loads(raw)
        item["log_line"] = line_number
        observations.append(item)
    if transfer_prefix in line:
        transfer_observation = json.loads(line.split(transfer_prefix, 1)[1])

expected_stages = [
    "opening", "proof_admitted", "funded", "activated", "after_alice_claim",
    "after_bob_claim", "allocator_closed", "reserve_finalized",
]
assert [s["stage"] for s in observations] == expected_stages, "unexpected or incomplete state sequence"
assert transfer_observation is not None and len(transfer_observation) == 5
assert [t["amount"] for t in transfer_observation] == [70, 35, 21, 14, 10]
policy = json.loads((root / "protected-capital-policy.json").read_text())
funding = json.loads((root / "funding-observation.json").read_text())
source_text = (root / "PartialFillV3Controlled.t.sol").read_text()
def solidity_address(name):
    match = re.search(rf"address constant {name} = (0x[0-9A-Fa-f]{{40}});", source_text)
    assert match, f"{name} address missing from retained test source"
    return match.group(1)
alice, bob, carol = (solidity_address(name) for name in ("ALICE", "BOB", "CAROL"))
assert policy["beneficialOwnerAndRefundBeneficiary"].lower() == carol.lower()
assert funding["sponsorTransferObserved"] and funding["observedTransferAmount"] == 80
assert funding["reserve"].lower() == policy["reserve"].lower()
assert funding["token"].lower() == policy["token"].lower()
assert all(t["token"].lower() == policy["token"].lower() for t in transfer_observation)
assert transfer_observation[0]["from"].lower() == policy["reserve"].lower()
assert transfer_observation[0]["to"].lower() == policy["allocator"].lower()
assert transfer_observation[1]["to"].lower() == alice.lower()
assert transfer_observation[2]["to"].lower() == bob.lower()
assert transfer_observation[3]["to"].lower() == carol.lower()
assert transfer_observation[4]["from"].lower() == policy["reserve"].lower()
assert transfer_observation[4]["to"].lower() == carol.lower()
assert observations[0]["reserveBalance"] == 80
assert observations[-1]["reserveBalance"] == 0 and observations[-1]["reserveFinalized"]
assert observations[-1]["carolBalance"] == 24

names = {"aliceBalance": "Alice", "bobBalance": "Bob", "carolBalance": "Carol"}
events = [
    "opening_observation",
    "proof_admitted_without_balance_change",
    "allocator_funded_without_claim_liability",
    "claim_liability_activated",
    "beneficiary_claim",
    "beneficiary_claim",
    "final_beneficiary_claim_and_allocator_closed",
    "restricted_capital_refunded_and_reserve_finalized",
]
states = []
for i, item in enumerate(observations):
    participants = {names[k]: item[k] for k in names}
    custody = item["reserveBalance"] + item["allocatorBalance"] + sum(participants.values())
    assert custody == 80, f"custody does not reconcile at {item['stage']}"
    if i >= 3:
        assert item["claimedAmount"] + item["outstandingLiability"] == 70, f"claim liability does not reconcile at {item['stage']}"
        assert item["allocatorBalance"] == item["outstandingLiability"], f"allocator custody/liability mismatch at {item['stage']}"
    if item["stage"] in ("funded", "activated", "after_alice_claim", "after_bob_claim", "allocator_closed"):
        assert item["reserveBalance"] == 10, f"restricted amount not retained at {item['stage']}"
    states.append({
        "sequence": i,
        "stage": item["stage"],
        "event": events[i],
        "observation": {k: v for k, v in item.items() if k != "log_line"},
        "computed_total_custody": custody,
        "custody_equation_holds": custody == 80,
        "source_ref": f"v3-accounting-qualification.log:line-{item['log_line']}",
    })

states_doc = {
    "schema": "eae.partial-fill.v3-accounting-state-observations.v1",
    "scope": "Eight actual Foundry getter observations emitted at local contract-call boundaries in test_QualificationWithdrawsAndClosesExactly; chain 31337.",
    "primary_evidence": "v3-accounting-qualification.log",
    "source_test": "PartialFillV3Controlled.t.sol::test_QualificationWithdrawsAndClosesExactly",
    "source_observation_helper": "PartialFillV3Controlled.t.sol::_logAccountingState",
    "observation_order": "opening, proof admitted, funded, activated, Alice claim, Bob claim, Carol claim/allocator closure, terminal reserve finalization",
    "states": states,
}
(root / "accounting-states.json").write_text(json.dumps(states_doc, indent=2) + "\n")

transfer_refs = [
    "reserve_to_allocator_funding", "alice_withdrawal", "bob_withdrawal",
    "carol_withdrawal", "restricted_capital_refund_to_carol",
]
transfers = []
for i, transfer in enumerate(transfer_observation):
    transfers.append({"sequence": i, "meaning": transfer_refs[i], "observed_transfer": transfer,
                      "source_ref": f"v3-accounting-qualification.log#PARTIAL_FILL_TRANSFERS_JSON/{i}"})

records = []
for i, state in enumerate(states):
    o = state["observation"]
    previous = states[i - 1]["observation"] if i else None
    record = {
        "sequence": i,
        "event": state["event"],
        "stage": state["stage"],
        "operation_id": policy["operationId"],
        "observed_balances": {
            "reserve_custody": o["reserveBalance"],
            "allocator_custody": o["allocatorBalance"],
            "alice": o["aliceBalance"],
            "bob": o["bobBalance"],
            "carol": o["carolBalance"],
            "total_custody": state["computed_total_custody"],
        },
        "funding_and_claim_state": {
            "claimed_amount": o["claimedAmount"],
            "claimed_count": o["claimedCount"],
            "outstanding_claim_liability": o["outstandingLiability"],
            "allocator_state_code": o["allocatorState"],
            "ratification_state_code": o["ratificationState"],
        },
        "reserve_state": {
            "restricted_floor": 10,
            "total_reserved_for_other_obligations": o["totalReserved"],
            "availability_revision": o["availabilityRevision"],
            "finalized": o["reserveFinalized"],
        },
        "custody_equation_holds": state["custody_equation_holds"],
        "evidence_ref": state["source_ref"],
    }
    if previous is not None:
        balance_fields = ["reserveBalance", "allocatorBalance", "aliceBalance", "bobBalance", "carolBalance"]
        record["balance_deltas"] = {key: o[key] - previous[key] for key in balance_fields}
        record["claimed_amount_delta"] = o["claimedAmount"] - previous["claimedAmount"]
        record["liability_delta"] = o["outstandingLiability"] - previous["outstandingLiability"]
    if i == 2:
        record["transfer_ref"] = "transfers.json#/0"
    elif i in (4, 5, 6):
        record["transfer_ref"] = f"transfers.json#/{i - 3}"
    elif i == 7:
        record["transfer_ref"] = "transfers.json#/4"
    records.append(record)

checks = {
    "initial_sponsor_deposit": {"equation": "80 deposited = 10 restricted + 70 available", "holds": 80 == 10 + 70,
                                 "basis": "funding-observation.json; local Transfer and Deposited events"},
    "request_disposition": {"equation": "100 requested = 70 allocated + 30 unmet", "holds": 100 == 70 + 30,
                             "basis": "source-bound allocation fixture; unmet is not a recognized loss or funded liability"},
    "claim_liability_at_each_activated_state": all(
        s["observation"]["claimedAmount"] + s["observation"]["outstandingLiability"] == 70
        for s in states[3:]
    ),
    "custody_at_every_observation": all(s["custody_equation_holds"] for s in states),
    "terminal_claim_settlement": {
        "equation": "70 activated = 70 claimed + 0 outstanding", "holds":
        observations[-1]["claimedAmount"] == 70 and observations[-1]["outstandingLiability"] == 0,
    },
    "restricted_capital_release": {
        "equation": "10 restricted in custody before finalization = 10 transferred to Carol at finalization",
        "holds": observations[-2]["reserveBalance"] == 10 and observations[-1]["reserveBalance"] == 0
                and transfer_observation[4]["amount"] == 10
                and observations[-1]["carolBalance"] - observations[-2]["carolBalance"] == 10,
    },
}
assert checks["initial_sponsor_deposit"]["holds"] and checks["request_disposition"]["holds"]
assert checks["claim_liability_at_each_activated_state"] and checks["custody_at_every_observation"]
assert checks["terminal_claim_settlement"]["holds"] and checks["restricted_capital_release"]["holds"]

body = {
    "schema": "eae.partial-fill.v3-observed-accounting-journal.v1",
    "scope": "Deterministic projection of the retained V3 local Foundry test's eight getter observations and five ERC-20 transfers; not a complete economic ledger.",
    "journal_kind": "derived_event_projection_not_double_entry",
    "primary_observations": "accounting-states.json",
    "source_transfer_observations": "transfers.json",
    "ordering": "Actual call-boundary order emitted by the passing Foundry test; does not assert transaction inclusion or public-chain finality.",
    "records": records,
    "transfers": transfers,
    "reconciliation": checks,
    "unmet_request": {"amount": 30, "classification": "unmet request", "funded_entitlement": 0,
                       "recognized_loss": False, "deferred_obligation": False},
    "restricted_capital": {"amount": 10, "classification": "fixture-policy restricted sponsor capital",
                           "beneficiary": "Carol at immutable fixture address",
                           "external_beneficial_ownership_proven": False,
                           "contract_enforced_in_this_v3_fixture": True,
                           "refund_observed_locally": True},
    "limitations": [
        "Local Foundry chain 31337 only; no Base Sepolia or public-chain execution.",
        "Carol's beneficial attribution follows fixture policy and local contract enforcement; external legal ownership is not independently proven.",
        "The controlled test proves only the configured request and reserve population; it does not establish completeness of external obligations or assets.",
        "This is not a double-entry or corporate accounting ledger; balance snapshots and ERC-20 transfers remain the primary evidence.",
    ],
}
canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
result = dict(body)
result["projection_digest"] = {
    "algorithm": "SHA-256",
    "value": hashlib.sha256(canonical).hexdigest(),
    "encoding": "UTF-8 JSON with sorted keys and compact separators over journal body without projection_digest",
    "meaning": "Integrity digest of this derived V3 journal; not a PRF commitment or onchain proof.",
}
(root / "accounting-journal.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
print(f"Derived {len(records)} V3 observations and {len(transfers)} transfers; reconciliation checks all pass; projection {result['projection_digest']['value']}")
