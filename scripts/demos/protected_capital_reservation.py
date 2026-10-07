#!/usr/bin/env python3
"""Local EVM reservation demonstration. All identities here are synthetic fixtures."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


class RpcError(RuntimeError):
    def __init__(self, error):
        super().__init__(error.get("message", str(error)))
        self.data = error.get("data")


class Chain:
    def __init__(self, url):
        self.url = url
        self.sender = None

    def rpc(self, method, *params):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
        request = urllib.request.Request(
            self.url, body.encode(), {"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
        if "error" in result:
            raise RpcError(result["error"])
        return result["result"]

    def call(self, address, signature, *args):
        return self.rpc("eth_call", self.transaction(address, signature, *args), "latest")

    def transaction(self, address, signature, *args):
        return {"from": self.sender, "to": address,
                "data": cast("calldata", signature, *map(str, args)), "gas": hex(12_000_000)}

    def send(self, transaction, success=True):
        tx_hash = self.rpc("eth_sendTransaction", transaction)
        for _ in range(100):
            receipt = self.rpc("eth_getTransactionReceipt", tx_hash)
            if receipt is not None:
                break
            time.sleep(0.05)
        else:
            raise RuntimeError(f"Transaction not mined: {tx_hash}")
        require(int(receipt["status"], 16) == int(success), f"Unexpected transaction status: {tx_hash}")
        if not success:
            require(not receipt["logs"], "Reverted transaction retained logs")
        return {"hash": tx_hash, "block": int(receipt["blockNumber"], 16),
                "status": int(receipt["status"], 16)}, receipt.get("contractAddress")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def cast(*args):
    return subprocess.check_output(["cast", *args], text=True).strip()


def words(encoded):
    require(encoded.startswith("0x") and (len(encoded) - 2) % 64 == 0, "Malformed ABI response")
    return [int(encoded[i:i + 64], 16) for i in range(2, len(encoded), 64)]


SOURCE_PATHS = [
    "src/applications/shortfall/OnchainShortfallReserveV2.sol",
    "src/applications/pro-rata/ProductionProRataAllocatorV2.sol",
    "src/interfaces/IEntitlementRepresentationV1.sol",
    "src/interfaces/IOnchainShortfallEntitlementReserveV1.sol",
    "src/interfaces/IOnchainShortfallReserveV2.sol",
    "src/interfaces/IShortfallAllocatorV2.sol",
    "src/interfaces/IShortfallEntitlementAllocatorV1.sol",
    "src/verification/ShortfallV2Commitments.sol",
]

IERC20_SHIM = """// SPDX-License-Identifier: MIT
pragma solidity ^0.8.33;
interface IERC20 {
    function totalSupply() external view returns (uint256);
    function balanceOf(address account) external view returns (uint256);
    function transfer(address to, uint256 amount) external returns (bool);
    function allowance(address owner, address spender) external view returns (uint256);
    function approve(address spender, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
}
"""

TOKEN_SOURCE = """// SPDX-License-Identifier: MIT
pragma solidity ^0.8.33;
contract ReservationDemoToken {
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;
    function mint(address to, uint256 amount) external { balanceOf[to] += amount; }
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        return true;
    }
    function transfer(address to, uint256 amount) external returns (bool) {
        require(balanceOf[msg.sender] >= amount, "BALANCE");
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        return true;
    }
    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        uint256 approved = allowance[from][msg.sender];
        require(approved >= amount && balanceOf[from] >= amount, "ALLOWANCE_OR_BALANCE");
        allowance[from][msg.sender] = approved - amount;
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        return true;
    }
}
"""


def make_build_root(repo, directory):
    root = directory / "build-root"
    for relative in SOURCE_PATHS:
        source = repo / relative
        require(source.is_file(), f"Missing companion source: {relative}")
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    openzeppelin = root / "lib/openzeppelin-contracts/contracts/token/ERC20/IERC20.sol"
    openzeppelin.parent.mkdir(parents=True, exist_ok=True)
    openzeppelin.write_text(IERC20_SHIM)
    token = root / "src/demo/ReservationDemoToken.sol"
    token.parent.mkdir(parents=True, exist_ok=True)
    token.write_text(TOKEN_SOURCE)
    (root / "foundry.toml").write_text("""[profile.default]
src = "src"
out = "out"
libs = ["lib"]
solc_version = "0.8.33"
optimizer = true
optimizer_runs = 200
via_ir = true
remappings = ["@openzeppelin/=lib/openzeppelin-contracts/"]
""")
    return root


def run(repo, directory):
    root = make_build_root(repo, directory)
    sources = [*SOURCE_PATHS[:2], "src/demo/ReservationDemoToken.sol"]
    build = ["forge", "build", *sources, "--root", str(root),
             "--out", str(directory / "out"), "--cache-path", str(directory / "cache"),
             "--offline"]
    print("Compiling copied companion sources with temporary dependency shims…", flush=True)
    built = subprocess.run(build, cwd=root, capture_output=True, text=True, timeout=600)
    require(built.returncode == 0, "Compilation failed:\n" + built.stdout + built.stderr)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    chain = Chain(f"http://127.0.0.1:{port}")
    with (directory / "anvil.log").open("w") as log:
        node = subprocess.Popen(["anvil", "--host", "127.0.0.1", "--port", str(port)],
                                stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if node.poll() is not None:
                    raise RuntimeError("Anvil exited:\n" + (directory / "anvil.log").read_text())
                try:
                    chain.sender = chain.rpc("eth_accounts")[0]
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(0.1)
            else:
                raise RuntimeError("Anvil did not become ready")
            return demonstrate(chain, repo, directory, build, root)
        finally:
            node.terminate()
            try:
                node.wait(timeout=5)
            except subprocess.TimeoutExpired:
                node.kill()
                node.wait()


def demonstrate(chain, repo, directory, build, root):
    transactions = []
    artifacts = {}

    def deploy(source, name, constructor, *args):
        path = directory / "out" / Path(source).name / f"{name}.json"
        artifact = json.loads(path.read_text())
        bytecode = artifact["bytecode"]["object"]
        require(bytecode.startswith("0x") and len(bytecode) > 2, f"Missing bytecode: {name}")
        artifacts[name] = hashlib.sha256(bytes.fromhex(bytecode[2:])).hexdigest()
        encoded = cast("abi-encode", constructor, *map(str, args))[2:]
        receipt, address = chain.send({"from": chain.sender, "data": bytecode + encoded,
                                       "gas": hex(12_000_000)})
        require(address is not None, f"Deployment failed: {name}")
        transactions.append({"action": f"deploy {name}", **receipt})
        return address

    token = deploy("src/demo/ReservationDemoToken.sol", "ReservationDemoToken", "constructor()")
    allocator = deploy("src/applications/pro-rata/ProductionProRataAllocatorV2.sol",
                       "ProductionProRataAllocatorV2", "constructor(address)", chain.sender)
    reserve = deploy("src/applications/shortfall/OnchainShortfallReserveV2.sol",
                     "OnchainShortfallReserveV2", "constructor(address,address,uint256,address)",
                     token, chain.sender, 10, allocator)
    prepared = cast("keccak", "reservation-demo:opaque-prepared-root")
    reservation = cast("keccak", "reservation-demo:ordinary-obligation")
    operation = cast("keccak", "reservation-demo:stale-operation")
    binding = cast("keccak", "reservation-demo:synthetic-allocation-binding")
    statement = cast("keccak", "reservation-demo:synthetic-statement")
    rows = []

    def send(action, address, signature, *args):
        receipt, _ = chain.send(chain.transaction(address, signature, *args))
        transactions.append({"action": action, **receipt})

    def snapshot():
        economic = words(chain.call(reserve, "economicState(bytes32)", prepared))
        require(len(economic) == 9, "Unexpected economicState ABI")
        return {"gross": economic[3], "reserved": economic[4], "protected": economic[5],
                "available": economic[6], "revision": economic[7],
                "commitment": "0x" + f"{economic[8]:064x}",
                "reservation": words(chain.call(reserve, "reservations(bytes32)", reservation)),
                "allocator_balance": words(chain.call(token, "balanceOf(address)", allocator))[0],
                "allowance": words(chain.call(token, "allowance(address,address)", reserve, allocator))[0],
                "next_snapshot_id": words(chain.call(reserve, "nextSnapshotId()"))[0],
                "ratifying": words(chain.call(reserve, "ratifying()"))[0],
                "operation": chain.call(allocator, "operation(bytes32)", operation)}

    def record(action, reserved, available, revision, reservation_state):
        state = snapshot()
        require((state["gross"], state["protected"], state["reserved"], state["available"],
                 state["revision"], state["reservation"][0]) ==
                (100, 10, reserved, available, revision, reservation_state),
                f"Incorrect reserve state after {action}: {state}")
        require(state["allocator_balance"] == 0 and state["allowance"] == 0,
                "Unexpected allocator custody or allowance")
        rows.append({"action": action, **state})
        print(f"{action:38} gross={state['gross']:3} protected={state['protected']:2} "
              f"reserved={reserved:2} available={available:2} revision={revision}", flush=True)
        return state

    def reject(action, error_signature, address, signature, *args):
        before = snapshot()
        transaction = chain.transaction(address, signature, *args)
        try:
            chain.rpc("eth_call", transaction, "latest")
        except RpcError as error:
            selector = cast("sig", error_signature)
            require(isinstance(error.data, str) and error.data.startswith(selector),
                    f"Wrong rejection for {action}: {error} ({error.data})")
        else:
            raise RuntimeError(f"Unexpected success: {action}")
        receipt, _ = chain.send(transaction, success=False)
        require(snapshot() == before, f"Rejected operation changed reserve/custody state: {action}")
        transactions.append({"action": action, "expected_error": error_signature, **receipt})
        print(f"{action:38} rejected ({error_signature}); state preserved", flush=True)

    send("bind reserve funding authority", allocator, "bindFundingAuthority(address)", reserve)
    send("mint test tokens", token, "mint(address,uint256)", chain.sender, 100)
    send("approve deposit", token, "approve(address,uint256)", reserve, 100)
    send("deposit 100", reserve, "deposit(uint256)", 100)
    original = record("Baseline: deposit 100; protect 10", 0, 90, 0, 0)
    expires_at = int(chain.rpc("eth_getBlockByNumber", "latest", False)["timestamp"], 16) + 86400
    reject("Attempt to reserve protected capital", "InsufficientAvailability()", reserve,
           "reserveForOtherObligation(bytes32,uint256,uint256)", reservation, 91, expires_at)
    send("reserve 20", reserve, "reserveForOtherObligation(bytes32,uint256,uint256)",
         reservation, 20, expires_at)
    record("Reserve 20", 20, 70, 1, 1)
    ratify = "ratifyAndFund((bytes32,bytes32,bytes32,bytes32,bytes32,bytes32,uint64,address))"
    request = f"({operation},{prepared},{original['commitment']},{binding},{statement},{statement},1,{chain.sender})"
    reject("Stale operation while reserved", "CommitmentMismatch(bytes32,bytes32)", reserve,
           ratify, request)
    send("release reservation", reserve, "releaseOtherObligationReservation(bytes32)", reservation)
    restored = record("Release 20; custody stays in reserve", 0, 90, 2, 2)
    require(original["commitment"] != restored["commitment"], "Availability revision did not bind commitment")
    reject("Reuse released reservation ID", "InvalidReservation()", reserve,
           "reserveForOtherObligation(bytes32,uint256,uint256)", reservation, 20, expires_at)
    reject("Release reservation twice", "InvalidReservation()", reserve,
           "releaseOtherObligationReservation(bytes32)", reservation)
    reject("Stale operation after balances restore", "CommitmentMismatch(bytes32,bytes32)", reserve,
           ratify, request)
    record("Final protected reserve", 0, 90, 2, 2)
    head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    status = subprocess.check_output(["git", "-C", str(repo), "status", "--short"], text=True)
    print("PASS: protection, reservation, release, terminal ID, stale commitment, and custody checks", flush=True)
    source_hashes = {
        relative: hashlib.sha256((root / relative).read_bytes()).hexdigest()
        for relative in SOURCE_PATHS
    }
    return {"description": "Local EVM reserve demonstration with a test ERC-20 and synthetic identities",
            "proof_admission_exercised": False, "companion_head": head,
            "companion_worktree_status": status.splitlines(), "build_command": build,
            "copied_companion_source_sha256": source_hashes,
            "build_shims": ["minimal IERC20 interface", "local test ERC-20"],
            "creation_bytecode_sha256": artifacts, "chain_id": int(chain.rpc("eth_chainId"), 16),
            "contracts": {"token": token, "reserve": reserve, "allocator": allocator},
            "baseline": rows[0], "final_state": rows[-1],
            "states": rows, "transactions": transactions}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evm-repo", type=Path, required=True, help="Companion prf-evm source checkout")
    parser.add_argument("--report", type=Path, help="Optional JSON evidence output; must not exist")
    args = parser.parse_args()
    for executable in ("forge", "cast", "anvil", "git"):
        require(shutil.which(executable), f"Missing executable: {executable}")
    repo = args.evm_repo.resolve()
    require((repo / "foundry.toml").is_file(), "Expected a Foundry companion checkout")
    require(args.report is None or not args.report.exists(), "Report path already exists")
    with tempfile.TemporaryDirectory(prefix="prf-reservation-demo-") as temporary:
        report = run(repo, Path(temporary))
    if args.report is not None:
        with args.report.open("x") as output:
            json.dump(report, output, indent=2)
            output.write("\n")
        print(f"Evidence: {args.report}")


if __name__ == "__main__":
    main()
