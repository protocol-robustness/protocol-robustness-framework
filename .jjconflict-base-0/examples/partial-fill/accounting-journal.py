#!/usr/bin/env python3
"""Derive a narrow event journal from retained Partial Fill observations.

This is a deterministic projection of the seven Foundry snapshots and retained
token events. It is not a double-entry ledger, a PRF commitment, or a claim that
all economic rights and liabilities are known.
"""
import hashlib
import json
import pathlib
import sys


root = pathlib.Path(sys.argv[1]).resolve()
read = lambda name: json.loads((root / name).read_text())
states_doc = read("accounting-states.json")
states = states_doc["states"]
qualification = read("qualification.json")
transfers = read("token-transfers.json")["transfers"]
policy = read("protected-capital-policy.json")
funding = read("funding-attribution.json")
expected_stages = [
    "prepared", "proofAdmitted", "funded", "activated", "afterAlice", "afterBob", "closed"
]
assert [s["stage"] for s in states] == expected_stages, "Expected original seven observations in order"
assert len(transfers) == 4 and [t["amount"] for t in transfers] == [70, 35, 21, 14]
assert funding["erc20TransferObserved"] and funding["reserveDepositEventObserved"]
assert funding["sponsorTransferredToReserve"] == 80
assert policy["restrictedAmount"] == 10
assert qualification["requested"] == 100 and qualification["available"] == 70
assert qualification["unmet"] == 30

paid_fields = ["aliceBalance", "bobBalance", "carolBalance"]
for state in states:
    assert state["boundary"] == "after local EVM contract call"
    assert state["totalCustody"] == 80
    assert state["reserveBalance"] + state["allocatorBalance"] + state["paid"] == 80
    assert state["paid"] == sum(state[k] for k in paid_fields)
    assert state["protectedCapital"] == 10
    assert state["reserveBalance"] >= state["protectedCapital"] + state["reservedCapital"]
    if state["activatedLiability"]:
        assert state["activatedLiability"] == state["paid"] + state["remainingLiability"]
    else:
        assert state["remainingLiability"] == 0

assert states[2]["reserveBalance"] == 10 and states[2]["allocatorBalance"] == 70
assert states[2]["paid"] == 0 and states[2]["activatedLiability"] == 0
assert states[3]["activatedLiability"] == 70
assert states[-1]["allocatorState"] == "Closed"
assert states[-1]["remainingLiability"] == 0
assert states[-1]["reserveBalance"] == 10

event_names = [
    "opening_observation",
    "proof_admitted_no_balance_change",
    "allocator_funded",
    "claim_liability_activated",
    "beneficiary_claim",
    "beneficiary_claim",
    "final_beneficiary_claim_and_allocator_closed",
]
transfer_refs = {2: 0, 4: 1, 5: 2, 6: 3}
records = []
for i, state in enumerate(states):
    previous = states[i - 1] if i else None
    record = {
        "sequence": i,
        "observation_stage": state["stage"],
        "event": event_names[i],
        "operation_id": state["operationId"],
        "observation_ref": f"accounting-states.json#/states/{i}",
        "snapshot_boundary": state["boundary"],
        "balances": {
            "reserve_custody": state["reserveBalance"],
            "allocator_custody": state["allocatorBalance"],
            "alice_observed_balance": state["aliceBalance"],
            "bob_observed_balance": state["bobBalance"],
            "carol_observed_balance": state["carolBalance"],
            "total_observed_custody": state["totalCustody"],
        },
        "funded_entitlement_amount": state["fundedEntitlement"],
        "activated_claim_liability": state["activatedLiability"],
        "remaining_activated_claim_liability": state["remainingLiability"],
        "allocator_state": state["allocatorState"],
        "ratification_state": state["ratificationState"],
        "deltas_from_prior_observation": None if previous is None else {
            "reserve_custody": state["reserveBalance"] - previous["reserveBalance"],
            "allocator_custody": state["allocatorBalance"] - previous["allocatorBalance"],
            "alice_observed_balance": state["aliceBalance"] - previous["aliceBalance"],
            "bob_observed_balance": state["bobBalance"] - previous["bobBalance"],
            "carol_observed_balance": state["carolBalance"] - previous["carolBalance"],
            "activated_claim_liability": state["activatedLiability"] - previous["activatedLiability"],
            "remaining_activated_claim_liability": state["remainingLiability"] - previous["remainingLiability"],
        },
        "evidence_refs": [record_ref for record_ref in [
            f"qualification.json#/tests/test_QualificationWithdrawsAndClosesExactly()",
            f"accounting-states.json#/states/{i}",
        ]],
    }
    if i in transfer_refs:
        n = transfer_refs[i]
        record["transfer_ref"] = f"token-transfers.json#/transfers/{n}"
        record["observed_transfer"] = transfers[n]
    if i == 0:
        record["source_funding_ref"] = "funding-attribution.json"
        record["source_funding_boundary"] = (
            "80 local synthetic test-token units were minted to the fixture sponsor and transferred "
            "to the reserve; this records custody attribution, not beneficial ownership."
        )
    records.append(record)

# Bind observed balance deltas to the retained token Transfer records.
for index, transfer_index in [(2, 0), (4, 1), (5, 2), (6, 3)]:
    delta = records[index]["deltas_from_prior_observation"]
    transfer = transfers[transfer_index]
    if index == 2:
        assert delta["reserve_custody"] == -70 and delta["allocator_custody"] == 70
        assert transfer["amount"] == abs(delta["reserve_custody"]) == delta["allocator_custody"]
    else:
        assert transfer["amount"] == abs(delta["allocator_custody"])
        beneficiary = ["alice_observed_balance", "bob_observed_balance", "carol_observed_balance"][index - 4]
        assert delta["allocator_custody"] == -transfer["amount"]
        assert delta[beneficiary] == transfer["amount"]
        assert delta["remaining_activated_claim_liability"] == -transfer["amount"]

restricted_rows = [
    {
        "observation_stage": s["stage"],
        "amount_in_reserve_custody": 10,
        "classification": "unresolved_restricted_custody",
        "restriction_basis": "reserve V2 configured protected-capital floor",
        "carol_attribution": "declared_by_fixture_policy_only",
        "beneficial_rights_independently_proven": False,
        "beneficiary_contract_enforced": False,
        "release_observed": False,
        "observation_ref": f"accounting-states.json#/states/{i}",
        "policy_ref": "protected-capital-policy.json",
    }
    for i, s in enumerate(states)
]

body = {
    "schema": "partial-fill-observed-accounting-journal.v1",
    "scope": "Deterministic projection of the retained seven local Foundry call-boundary observations and observed token transfers.",
    "journal_kind": "scoped_event_projection_not_double_entry",
    "primary_evidence": "accounting-states.json",
    "ordering": "Fixed sequence by the retained observation order; records do not assert unobserved intermediate transactions.",
    "records": records,
    "reconciliation": {
        "per_observation": [
            {
                "observation_stage": s["stage"],
                "custody_equation": f"{s['reserveBalance']} reserve + {s['allocatorBalance']} allocator + {s['paid']} paid = {s['totalCustody']} total",
                "custody_holds": s["reserveBalance"] + s["allocatorBalance"] + s["paid"] == s["totalCustody"] == 80,
                "activated_liability": s["activatedLiability"],
                "paid_plus_remaining_liability": s["paid"] + s["remainingLiability"],
                "liability_holds": s["activatedLiability"] == s["paid"] + s["remainingLiability"],
                "observation_ref": f"accounting-states.json#/states/{i}",
            }
            for i, s in enumerate(states)
        ],
        "request_equation": "100 requested = 70 allocated + 30 unmet request",
        "request_equation_holds": qualification["requested"] == qualification["available"] + qualification["unmet"],
        "funded_claim_equation": "70 activated = 70 observed paid + 0 remaining activated liability",
        "funded_claim_equation_holds": states[-1]["activatedLiability"] == states[-1]["paid"] + states[-1]["remainingLiability"] == 70,
    },
    "unresolved_restricted_custody": {
        "amount": 10,
        "observed_location": "reserve custody at every retained observation",
        "proposed_beneficiary": "Carol",
        "attribution_status": "declared in fixture policy; not independently proven as a legal or beneficial right",
        "contract_enforcement_status": "not enforced by frozen reserve V2",
        "release_status": "not released; no final reserve settlement observed",
        "records": restricted_rows,
        "uncertainties": [
            "Legal/beneficial ownership, release authority, and any claim to the 10 units are not established by the observed token balances or events.",
            "The fixture's Carol attribution is policy-declared and not committed or enforced by reserve V2.",
        ],
    },
    "limitations": [
        "This projection covers only the named operation and controlled local test-token balances.",
        "It does not establish completeness of external requests, liabilities, assets, or beneficial rights.",
        "Unmet requests are not classified as a recognized loss or continuing liability.",
        "Allocator closure is observed; final reserve settlement is not.",
        "The journal is not a double-entry/company ledger and does not prove public-chain execution.",
    ],
}
canonical_bytes = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
result = dict(body)
result["projection_digest"] = {
    "algorithm": "SHA-256",
    "value": hashlib.sha256(canonical_bytes).hexdigest(),
    "encoding": "UTF-8 JSON with sorted keys and compact separators, over the journal body without projection_digest",
    "meaning": "Integrity digest for this derived artifact only; not a PRF canonical commitment or onchain proof.",
}
(root / "accounting-journal.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
print(f"Derived {len(records)} journal observations; {len(restricted_rows)} unresolved-custody classifications; digest {result['projection_digest']['value']}")
