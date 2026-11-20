#!/usr/bin/env python3
"""Run one eligible researcher obligation through a fresh local Anvil ERC-20 payout."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
import time
import urllib.request
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
EXAMPLE = REPO / "examples" / "assured-operations"
FUNDER = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
AWARD_AUTHORITY = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
BENEFICIARY = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc"
RESERVE_FUNDED_TOPIC = "0x6df4a82f3773364d75520be152897ea139a8c288425391b0c07e8c4b1ea1b556"
BOUNTY_TOKEN_PAID_TOPIC = "0xe1290cddcd8f1e03f4969eb3b2c88c000dd8cd4dc5668255fae0bb0a6d84201c"
ERC20_TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
INITIAL_SUPPLY = 10**30


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Rpc:
    def __init__(self, url: str):
        self.url = url
        self.next_id = 0

    def __call__(self, method: str, params: list):
        self.next_id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.next_id,
                           "method": method, "params": params}).encode()
        request = urllib.request.Request(self.url, body,
                                         {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            payload = json.load(response)
        if "error" in payload:
            raise RuntimeError(f"{method}: {payload['error']}")
        return payload["result"]


def run(*args: str, cwd: Path = REPO) -> str:
    completed = subprocess.run(args, cwd=cwd, check=False, text=True,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
    if completed.returncode:
        raise RuntimeError(f"command failed ({completed.returncode}): {args[0]}\n"
                           f"{completed.stdout}")
    return completed.stdout.strip()


def mined(rpc: Rpc, tx_hash: str) -> dict:
    for _ in range(120):
        receipt = rpc("eth_getTransactionReceipt", [tx_hash])
        if receipt is not None:
            if receipt["status"] != "0x1":
                raise RuntimeError(f"transaction reverted: {tx_hash}")
            return receipt
        time.sleep(0.05)
    raise TimeoutError(f"transaction not mined: {tx_hash}")


def send_unlocked(rpc: Rpc, sender: str, recipient: str, data: str) -> dict:
    tx_hash = rpc("eth_sendTransaction", [{"from": sender, "to": recipient,
                                            "data": data, "gas": "0x989680"}])
    return mined(rpc, tx_hash)


def deploy(rpc_url: str, contract: str, sender: str, *constructor_args: str) -> str:
    output = run("forge", "create", contract, "--root", str(EXAMPLE),
                 "--offline", "--broadcast",
                 "--unlocked", "--from", sender, "--rpc-url", rpc_url,
                 "--constructor-args", *constructor_args)
    match = re.search(r"Deployed to: (0x[0-9a-fA-F]{40})", output)
    if not match:
        raise RuntimeError(f"could not find deployed address in forge output:\n{output}")
    return match.group(1).lower()


def code_digest(code: str) -> str:
    if not re.fullmatch(r"0x(?:[0-9a-fA-F]{2})+", code):
        raise RuntimeError("RPC returned invalid runtime bytecode")
    return "sha256:" + hashlib.sha256(bytes.fromhex(code[2:])).hexdigest()


def write(path: Path, contents: str) -> None:
    with path.open("x", encoding="utf-8") as target:
        target.write(contents.rstrip() + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("configuration", type=Path)
    parser.add_argument("profile", type=Path)
    parser.add_argument("history", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("challenge_root")
    parser.add_argument("obligation_root")
    parser.add_argument("researcher_public", type=Path)
    parser.add_argument("researcher_private", type=Path)
    parser.add_argument("operator_public", type=Path)
    parser.add_argument("operator_private", type=Path)
    parser.add_argument("reviewer_private", type=Path)
    parser.add_argument("output_directory", type=Path)
    parser.add_argument("--keep-chain", action="store_true",
                        help="keep Anvil running after verification so RPC evidence can be rechecked")
    args = parser.parse_args()
    for name in ("configuration", "profile", "history", "evidence",
                 "researcher_public", "researcher_private", "operator_public",
                 "operator_private", "reviewer_private", "output_directory"):
        setattr(args, name, getattr(args, name).resolve())

    for executable in ("anvil", "forge", "cast", "solc", "clojure"):
        if shutil.which(executable) is None:
            parser.error(f"required executable not found: {executable}")
    for path in (args.configuration, args.profile, args.history, args.evidence,
                 args.researcher_public,
                 args.researcher_private, args.operator_public,
                 args.operator_private, args.reviewer_private):
        if not path.is_file():
            parser.error(f"required input file does not exist: {path}")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.challenge_root):
        parser.error("challenge_root must be a sha256 reference")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.obligation_root):
        parser.error("obligation_root must be a sha256 reference")

    run("clojure", "-M", "-m",
        "resolver-sim.research.researcher-challenge-local-payout-preflight",
        str(args.configuration), str(args.profile), str(args.history),
        str(args.evidence), args.challenge_root, args.obligation_root,
        str(args.researcher_public))
    args.output_directory.mkdir(parents=True, exist_ok=False)

    port = free_port()
    rpc_url = f"http://127.0.0.1:{port}"
    node = subprocess.Popen(["anvil", "--silent", "--host", "127.0.0.1",
                             "--port", str(port), "--chain-id", "31337",
                             "--accounts", "5"], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    try:
        rpc = Rpc(rpc_url)
        for _ in range(120):
            try:
                if rpc("eth_chainId", []) == "0x7a69":
                    break
            except Exception:
                time.sleep(0.05)
        else:
            raise TimeoutError("local Anvil did not start")

        token = deploy(rpc_url, "src/ResearcherDemoToken.sol:ResearcherDemoToken",
                       FUNDER, str(INITIAL_SUPPLY))
        settlement = deploy(rpc_url, "src/BountySettlement.sol:BountySettlement",
                            FUNDER, FUNDER, AWARD_AUTHORITY)
        token_code = rpc("eth_getCode", [token, "latest"])
        payout_code = rpc("eth_getCode", [settlement, "latest"])
        token_digest = code_digest(token_code)
        payout_digest = code_digest(payout_code)

        approve_funding = run("cast", "calldata", "approve(address,uint256)",
                              settlement, str(INITIAL_SUPPLY))
        send_unlocked(rpc, FUNDER, token, approve_funding)
        fund_data = run("cast", "calldata", "fundTokenReserve(address,uint256)",
                        token, str(INITIAL_SUPPLY))
        funding_receipt = send_unlocked(rpc, FUNDER, settlement, fund_data)
        funding_logs = [log for log in funding_receipt["logs"]
                        if log["address"].lower() == settlement
                        and log["topics"][0].lower() == RESERVE_FUNDED_TOPIC]
        if len(funding_logs) != 1:
            raise RuntimeError("expected one TokenReserveFunded event")
        funding_root = funding_logs[0]["topics"][1].lower()

        genesis = rpc("eth_getBlockByNumber", ["0x0", False])
        chain_instance_root = "sha256:" + hashlib.sha256(
            f"anvil-local-instance-v1|31337|{genesis['hash'].lower()}".encode()
        ).hexdigest()
        backing_root = "sha256:" + hashlib.sha256(
            b"researcher-challenge-local-erc20-demo-backing-commitment-v1"
        ).hexdigest()

        policy_request = args.output_directory / "payout-policy-request.edn"
        write(policy_request, f"""{{:challenge/root \"{args.challenge_root}\"
 :chain/id 31337
 :chain/instance-root \"{chain_instance_root}\"
 :chain/genesis-hash \"{genesis['hash'].lower()}\"
 :asset/id \"{token}\"
 :asset/decimals 18
 :asset/code-sha256 \"{token_digest}\"
 :asset/amount-per-reward-unit {{:numerator 1 :denominator 1}}
 :payout/sender \"{settlement}\"
 :payout/executor-code-sha256 \"{payout_digest}\"
 :payout/required-finality :included
 :funding/backing-root \"{backing_root}\"
 :funding/source-root \"{funding_root}\"
 :policy/demo-only? true}}""")
        policy_path = args.output_directory / "payout-policy.edn"
        run("clojure", "-M", "-m",
            "resolver-sim.research.researcher-challenge-payout-policy-cli", "build",
            str(args.configuration), str(policy_request), str(args.operator_public),
            str(args.operator_private), str(args.reviewer_private), str(policy_path))

        payee_request = args.output_directory / "payee-request.edn"
        write(payee_request, f"{{:obligation-root \"{args.obligation_root}\"\n"
                             f" :beneficiary-address \"{BENEFICIARY}\"\n"
                             " :nonce \"local-anvil-researcher-payout-demo\"}")
        authorization = args.output_directory / "payee-authorization.edn"
        run("clojure", "-M", "-m",
            "resolver-sim.research.researcher-challenge-payee-cli", "build",
            str(args.configuration), str(args.history), str(policy_path),
            str(payee_request), str(args.operator_public),
            str(args.researcher_private), str(authorization))

        approval_args = (str(args.configuration), str(args.history), str(policy_path),
                         str(authorization), str(args.operator_public))
        prepared = run("clojure", "-M", "-m",
                       "resolver-sim.research.researcher-challenge-payout-approval-cli",
                       "prepare", *approval_args)
        amount_match = re.search(r":asset/amount\s+(\d+)N?\b", prepared)
        if not amount_match:
            raise RuntimeError(f"could not read derived payout amount from CLI output:\n{prepared}")
        payout_amount = int(amount_match.group(1))
        calldata = run("clojure", "-M", "-m",
                       "resolver-sim.research.researcher-challenge-payout-approval-cli",
                       "calldata", *approval_args)
        approval_receipt = send_unlocked(rpc, AWARD_AUTHORITY, settlement, calldata)

        claim_data = run("cast", "calldata", "claim(bytes32)",
                         "0x" + args.obligation_root[7:])
        claim_receipt = send_unlocked(rpc, BENEFICIARY, settlement, claim_data)
        settlement_logs = [log for log in claim_receipt["logs"]
                           if log["address"].lower() == settlement
                           and log["topics"][0].lower() == BOUNTY_TOKEN_PAID_TOPIC]
        transfer_logs = [log for log in claim_receipt["logs"]
                         if log["address"].lower() == token
                         and log["topics"][0].lower() == ERC20_TRANSFER_TOPIC
                         and log["topics"][2][-40:].lower() == BENEFICIARY[2:].lower()]
        if len(settlement_logs) != 1 or len(transfer_logs) != 1:
            raise RuntimeError("claim receipt did not contain the expected payout and ERC-20 transfer logs")
        if int(transfer_logs[0]["data"], 16) != payout_amount:
            raise RuntimeError("transferred ERC-20 amount differs from replay-derived consent amount")

        context_path = args.output_directory / "rpc-context.edn"
        write(context_path, f"""{{:chain-id 31337
 :chain-instance-root \"{chain_instance_root}\"
 :genesis-hash \"{genesis['hash'].lower()}\"
 :token-contract \"{token}\"
 :token-code-sha256 \"{token_digest}\"
 :asset-decimals 18
 :sender \"{settlement}\"
 :beneficiary \"{BENEFICIARY}\"
 :amount {payout_amount}
 :required-finality :included}}""")
        transfer_path = args.output_directory / "transfer.edn"
        transfer_log = transfer_logs[0]
        write(transfer_path, f"""{{:transaction-hash \"{claim_receipt['transactionHash'].lower()}\"
 :block-number {int(claim_receipt['blockNumber'], 16)}
 :block-hash \"{claim_receipt['blockHash'].lower()}\"
 :log-index {int(transfer_log['logIndex'], 16)}}}""")

        report = run("clojure", "-M", "-m",
                     "resolver-sim.research.researcher-challenge-transfer-cli", "verify",
                     str(args.configuration), str(args.history), str(authorization),
                     str(policy_path), str(args.operator_public), str(context_path),
                     str(transfer_path), rpc_url)
        write(args.output_directory / "deployment.edn", f"""{{:chain/id 31337
 :chain/genesis-hash \"{genesis['hash'].lower()}\"
 :chain/instance-root \"{chain_instance_root}\"
 :token/address \"{token}\"
 :token/code-sha256 \"{token_digest}\"
 :payout/address \"{settlement}\"
 :payout/code-sha256 \"{payout_digest}\"
 :funding/source-root \"{funding_root}\"
 :funding/backing-root \"{backing_root}\"}}""")
        receipt_evidence = {
            "funding": funding_receipt,
            "approval": approval_receipt,
            "claim": claim_receipt,
        }
        write(args.output_directory / "receipts.json",
              json.dumps(receipt_evidence, indent=2, sort_keys=True))
        write(args.output_directory / "verification-report.edn", report)
        print("Local researcher ERC-20 payout verified:")
        print(report)
        print(f"Artifacts: {args.output_directory}")
        print(f"Anvil RPC: {rpc_url}")
        print("Backing root is synthetic and demo-only; this run proves no real funding.")
        if args.keep_chain:
            print("Anvil is kept alive for independent replay. Stop with Ctrl-C.")
            node.wait()
    finally:
        if node.poll() is None:
            node.terminate()
            try:
                node.wait(timeout=5)
            except subprocess.TimeoutExpired:
                node.kill()
                node.wait()


if __name__ == "__main__":
    main()
