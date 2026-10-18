#!/usr/bin/env python3
"""Run BountySettlement on fresh Anvil and verify its mined payout via PRF JSON-RPC."""

from __future__ import annotations

import json
import hashlib
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
EXAMPLE = REPO / "examples" / "assured-operations"
EVENT_TOPIC = "0x6dc490073c04b7752ab94752fb6ab71a1c57314a137ad2ae0446d5a68246aef1"
RESERVE_FUNDED_TOPIC = "0xeffc741564567dbe9bc7dad33348f2f0a9789307fd569400ffd2ba83201ff45a"
FUNDER = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
AWARD_AUTHORITY = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
BENEFICIARY = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc"
PAYABLE_ID = "0x" + "11" * 32
OPERATION_ROOT = "0x" + "22" * 32


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
    for _ in range(100):
        receipt = rpc("eth_getTransactionReceipt", [tx_hash])
        if receipt is not None:
            if receipt["status"] != "0x1":
                raise RuntimeError(f"transaction reverted: {tx_hash}")
            return receipt
        time.sleep(0.05)
    raise TimeoutError(f"transaction not mined: {tx_hash}")


def cast_root(rpc_url: str, contract: str, source_root: str,
              sequence: int, lifecycle: int) -> str:
    args = (f"({OPERATION_ROOT},{source_root},{BENEFICIARY},70,{sequence},{lifecycle})")
    result = run("cast", "call", contract,
                 "payableRoot(bytes32,(bytes32,bytes32,address,uint256,uint256,uint8))",
                 PAYABLE_ID, args, "--rpc-url", rpc_url)
    return result.split()[0]


def main() -> None:
    for executable in ("anvil", "forge", "cast", "solc", "clojure"):
        if shutil.which(executable) is None:
            raise SystemExit(f"required executable not found: {executable}")

    port = free_port()
    rpc_url = f"http://127.0.0.1:{port}"
    node = subprocess.Popen(["anvil", "--silent", "--host", "127.0.0.1",
                             "--port", str(port), "--chain-id", "31337",
                             "--accounts", "3"], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    try:
        rpc = Rpc(rpc_url)
        for _ in range(100):
            try:
                if rpc("eth_chainId", []) == "0x7a69":
                    break
            except Exception:
                time.sleep(0.05)
        else:
            raise TimeoutError("Anvil did not start")

        deploy = run("forge", "create", "src/BountySettlement.sol:BountySettlement",
                     "--root", str(EXAMPLE), "--use", shutil.which("solc"),
                     "--offline", "--broadcast", "--unlocked", "--from", FUNDER,
                     "--rpc-url", rpc_url, "--constructor-args", FUNDER,
                     AWARD_AUTHORITY)
        match = re.search(r"Deployed to: (0x[0-9a-fA-F]{40})", deploy)
        if not match:
            raise RuntimeError(f"could not parse deployed address from forge output:\n{deploy}")
        contract = match.group(1).lower()

        fund_data = run("cast", "calldata", "fundReserve()")
        fund_tx = rpc("eth_sendTransaction", [{"from": FUNDER, "to": contract,
                                               "data": fund_data, "value": "0x64",
                                               "gas": "0x989680"}])
        fund_receipt = mined(rpc, fund_tx)
        funded = next(log for log in fund_receipt["logs"]
                      if log["address"].lower() == contract
                      and log["topics"][0].lower() == RESERVE_FUNDED_TOPIC)
        # ReserveFunded(bytes32,address,uint256,uint256,uint256) has the source root indexed.
        funding_root = funded["topics"][1].lower()

        approve_data = run("cast", "calldata",
                           "approveBounty(bytes32,bytes32,bytes32,address,uint256)",
                           PAYABLE_ID, OPERATION_ROOT, funding_root, BENEFICIARY, "70")
        approve_tx = rpc("eth_sendTransaction", [{"from": AWARD_AUTHORITY,
                                                  "to": contract, "data": approve_data,
                                                  "gas": "0x989680"}])
        mined(rpc, approve_tx)

        predecessor = cast_root(rpc_url, contract, funding_root, 1, 1)
        claim_data = run("cast", "calldata", "claim(bytes32)", PAYABLE_ID)
        claim_tx = rpc("eth_sendTransaction", [{"from": BENEFICIARY, "to": contract,
                                                "data": claim_data, "gas": "0x989680"}])
        receipt = mined(rpc, claim_tx)
        successor = cast_root(rpc_url, contract, funding_root, 2, 2)
        genesis = rpc("eth_getBlockByNumber", ["0x0", False])
        runtime_code = rpc("eth_getCode", [contract, receipt["blockNumber"]])
        runtime_digest = "sha256:" + hashlib.sha256(
            bytes.fromhex(runtime_code[2:])).hexdigest()
        chain_instance_root = "sha256:" + hashlib.sha256(
            f"anvil-local-instance-v1|31337|{genesis['hash'].lower()}".encode()).hexdigest()
        log = next(log for log in receipt["logs"]
                   if log["address"].lower() == contract
                   and log["topics"][0].lower() == EVENT_TOPIC)
        words = [log["data"][2 + i * 64:2 + (i + 1) * 64] for i in range(9)]
        if len(words) != 9 or "" in words:
            raise RuntimeError("BountyPaid event data is malformed")
        if "0x" + words[3] != predecessor or "0x" + words[4] != successor:
            raise RuntimeError("event payable roots do not match independent contract readbacks")

        observation = {
            "observation/contract": contract,
            "observation/chain-instance-root": chain_instance_root,
            "observation/genesis-hash": genesis["hash"],
            "observation/contract-code-sha256": runtime_digest,
            "observation/source-id": "fresh-local-anvil-json-rpc",
            "observation/transaction-hash": receipt["transactionHash"],
            "observation/block-number": int(receipt["blockNumber"], 16),
            "observation/block-hash": receipt["blockHash"],
            "observation/log-index": int(log["logIndex"], 16),
            "observation/amount": int(words[1], 16),
            "observation/sequence": int(words[2], 16),
            "observation/reserve-before": int(words[5], 16),
            "observation/reserve-after": int(words[6], 16),
            "observation/beneficiary-before": int(words[7], 16),
            "observation/beneficiary-after": int(words[8], 16),
        }
        expected = {
            "chain-id": 31337, "chain-instance-root": chain_instance_root,
            "genesis-hash": genesis["hash"],
            "contract-code-sha256": runtime_digest,
            "observation-source-id": "fresh-local-anvil-json-rpc",
            "contract": contract,
            "payable-id": PAYABLE_ID, "operation-root": OPERATION_ROOT,
            "funding-source-root": funding_root,
            "beneficiary-address": BENEFICIARY,
            "predecessor-root": predecessor, "successor-root": successor,
            "required-finality": "included",
        }
        with tempfile.TemporaryDirectory(prefix="prf-bounty-rpc-") as directory:
            evidence_file = Path(directory) / "observation.json"
            artifact_file = Path(directory) / "settlement.edn"
            evidence_file.write_text(json.dumps({"observation": observation,
                                                 "expected": expected}), encoding="utf-8")
            generate = f"""
              (require '[clojure.data.json :as json]
                       '[resolver-sim.economics.bounty-payable :as bp]
                       '[resolver-sim.economics.bounty-payable-backing :as bpb]
                       '[resolver-sim.economics.with-bounty.fixtures :as fixtures]
                       '[resolver-sim.economics.with-bounty.obligation-settlement :as settlement])
              (let [input (json/read-str (slurp \"{evidence_file}\") :key-fn keyword)
                    rpc-context (update (:expected input) :required-finality keyword)
                    raw-observation (:observation input)
                    expected (:expected input)
                    prf-source-root \"sha256:local-anvil-prf-source\"
                    observation (merge raw-observation
                                       {{:observation/chain-id (:chain-id expected)
                                         :observation/asset :token/native
                                         :observation/finality :included
                                         :observation/payable-id (:payable-id expected)
                                         :observation/operation-root (:operation-root expected)
                                         :observation/funding-source-root
                                         (:funding-source-root expected)
                                         :observation/prf-funding-source-root prf-source-root
                                         :observation/beneficiary
                                         (:beneficiary-address expected)
                                         :observation/fee-amount 0
                                         :observation/fee-payer :relayer}})
                    payable (bp/build-bounty-payable
                             {{:payable/id (:payable-id expected)
                               :distribution-root \"sha256:local-anvil-distribution\"
                               :beneficiary (:beneficiary-address expected)
                               :amount (:observation/amount observation)
                               :lifecycle :backed}})
                    backing (bpb/build-bounty-payable-backing
                             {{:payable-root (:payable/hash payable)
                               :payable-id (:payable/id payable)
                               :distribution-root (:payable/distribution-root payable)
                               :amount (:payable/amount payable)
                               :source-allocations {{:local-anvil-reserve
                                                     (:payable/amount payable)}}
                               :context {{:funding/token :token/native
                                          :funding/source-root prf-source-root
                                          :funding/operation-root (:operation-root expected)}}}})
                    payable-after (bp/transition-payable-lifecycle payable :settled)
                    payout (fixtures/payout-receipt
                            payable backing
                            {{:payment-reference (:observation/transaction-hash observation)}})
                    expected-context {{:chain-id (:chain-id expected)
                                       :contract (:contract expected)
                                       :chain-instance-root (:chain-instance-root expected)
                                       :genesis-hash (:genesis-hash expected)
                                       :contract-code-sha256 (:contract-code-sha256 expected)
                                       :observation-source-id (:observation-source-id expected)
                                       :asset :token/native
                                       :funding-source-root (:funding-source-root expected)}}
                    inputs {{:payable-before payable
                             :payable-after payable-after
                             :backing backing
                             :payout-receipt payout
                             :payout-trust-policy fixtures/payout-trust-policy
                             :observation (fixtures/chain-observation observation)
                             :observation-trust-policy fixtures/chain-observer-trust-policy
                             :expected-context expected-context}}
                    artifact (settlement/build-settlement inputs)
                    bundle (assoc inputs :settlement artifact
                                  :expected-rpc-context rpc-context)]
                (spit \"{artifact_file}\" (pr-str bundle)))
            """
            run("clojure", "-M:test", "-e", generate)
            replay = f"""
              (require '[clojure.edn :as edn]
                       '[resolver-sim.economics.with-bounty.obligation-settlement :as settlement]
                       '[resolver-sim.economics.with-bounty.rpc-observation :as rpc-observation])
              (let [bundle (edn/read-string (slurp \"{artifact_file}\"))
                    result (settlement/verify-settlement
                            (assoc bundle
                                   :rpc-client (rpc-observation/http-rpc \"{rpc_url}\")
                                   :expected-rpc-context (:expected-rpc-context bundle)
                                   :require-rpc-observation? true))
                    summary {{:valid? (:valid? result)
                              :paid (get-in bundle [:settlement :settlement/paid])
                              :remaining-liability
                              (get-in bundle [:settlement :settlement/remaining-liability])
                              :rpc-observation (:rpc-observation result)}}]
                (prn summary)
                (when-not (:valid? result) (System/exit 1)))
            """
            verified = run("clojure", "-M:test", "-e", replay)
            print(json.dumps({"chainId": 31337, "contract": contract,
                              "fundingSourceRoot": funding_root,
                              "transactionHash": receipt["transactionHash"],
                              "blockNumber": int(receipt["blockNumber"], 16),
                              "freshProcessCanonicalSettlementReplay": verified}, indent=2))
    finally:
        node.terminate()
        try:
            node.wait(timeout=3)
        except subprocess.TimeoutExpired:
            node.kill()
            node.wait()


if __name__ == "__main__":
    main()
