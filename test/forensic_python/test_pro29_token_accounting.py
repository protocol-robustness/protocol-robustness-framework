"""Adversarial checks for transaction-level token accounting evidence."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import pro28_receipts, pro29_token_accounting


PRO56_SOURCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018"


def test_all_receipt_transfer_logs_are_compared_as_a_complete_set() -> None:
    rows = pro29_token_accounting._transfer_rows_from_receipts(PRO56_SOURCE)
    assert len(rows) == 6
    pro29_token_accounting._assert_transfer_coverage(rows, list(rows))

    with pytest.raises(pro28_receipts.EvidenceError, match="chain-wide Transfer sweep"):
        pro29_token_accounting._assert_transfer_coverage(rows, rows[:-1])
    changed = [dict(row) for row in rows]
    changed[-1]["to"] = "0x" + "44" * 20
    with pytest.raises(pro28_receipts.EvidenceError, match="chain-wide Transfer sweep"):
        pro29_token_accounting._assert_transfer_coverage(rows, changed)


def test_balance_and_supply_deltas_reconcile_to_observed_payout() -> None:
    event = next(
        row for row in pro29_token_accounting._transfer_rows_from_receipts(PRO56_SOURCE)
        if row["amount"] == 35
    )
    before = {event["from"]: 70, event["to"]: 0}
    after = {event["from"]: 35, event["to"]: 35}
    assert pro29_token_accounting._validate_transaction_deltas([event], before, after, 80, 80) == {
        event["from"]: -35,
        event["to"]: 35,
    }

    with pytest.raises(pro28_receipts.EvidenceError, match="balance deltas"):
        pro29_token_accounting._validate_transaction_deltas(
            [], before, after, 80, 80
        )
    wrong_recipient_delta = {event["from"]: 35, event["to"]: 34}
    with pytest.raises(pro28_receipts.EvidenceError, match="balance deltas"):
        pro29_token_accounting._validate_transaction_deltas(
            [event], before, wrong_recipient_delta, 80, 80
        )


def test_mint_and_burn_supply_changes_are_explicit() -> None:
    account = "0x" + "11" * 20
    mint = {"from": pro29_token_accounting.ZERO, "to": account, "amount": 80}
    burn = {"from": account, "to": pro29_token_accounting.ZERO, "amount": 80}
    assert pro29_token_accounting._validate_transaction_deltas(
        [mint], {account: 0}, {account: 80}, 0, 80
    ) == {account: 80}
    assert pro29_token_accounting._validate_transaction_deltas(
        [burn], {account: 80}, {account: 0}, 80, 0
    ) == {account: -80}
    with pytest.raises(pro28_receipts.EvidenceError, match="totalSupply"):
        pro29_token_accounting._validate_transaction_deltas(
            [mint], {account: 0}, {account: 80}, 0, 79
        )


def test_balance_snapshots_must_have_identical_account_sets_and_valid_values() -> None:
    account = "0x" + "11" * 20
    other = "0x" + "22" * 20
    with pytest.raises(pro28_receipts.EvidenceError, match="different accounts"):
        pro29_token_accounting._validate_transaction_deltas([], {account: 0}, {other: 0}, 0, 0)
    with pytest.raises(pro28_receipts.EvidenceError, match="nonnegative integers"):
        pro29_token_accounting._validate_transaction_deltas([], {account: -1}, {account: 0}, 0, 0)
