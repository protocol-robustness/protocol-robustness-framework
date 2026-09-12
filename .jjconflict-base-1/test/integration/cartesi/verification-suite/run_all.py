import subprocess
import os
import binascii
import time
import argparse
from pathlib import Path
import shutil

def env(name, default=None, required=False):
    value = os.getenv(name, default)
    if required and (value is None or str(value).strip() == ""):
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


# Configuration (defaults are for local dev only)
DAPP_ADDRESS = env("DAPP_ADDRESS", "0xab7528bb862fB57E8A2BCd567a2e929a0Be56a5e")
INPUT_BOX_ADDRESS = env("INPUT_BOX_ADDRESS", "0x59b22D57D4f067708AB0c00552767405926dc768")
RPC_URL = env("RPC_URL", "http://127.0.0.1:8545")
PRIVATE_KEY = env("PRIVATE_KEY", "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80")
ENVIRONMENT = env("APP_ENV", "dev")
SUBPROCESS_TIMEOUT_SECONDS = 30

def str_to_hex(s):
    return "0x" + binascii.hexlify(s.encode("utf-8")).decode("utf-8")

def run_suite(dry_run=False):
    if shutil.which("cast") is None:
        raise RuntimeError("Required executable 'cast' not found on PATH")

    if ENVIRONMENT != "dev":
        # In non-dev environments, explicit config is required.
        env("DAPP_ADDRESS", required=True)
        env("INPUT_BOX_ADDRESS", required=True)
        env("RPC_URL", required=True)
        env("PRIVATE_KEY", required=True)

    script_dir = Path(__file__).resolve().parent
    scenario_dir = script_dir.parent.parent / "scenarios"
    scenarios = [f for f in os.listdir(scenario_dir) if f.endswith(".json")]
    scenarios.sort()
    
    print(f"[*] Environment: {ENVIRONMENT}")
    print(f"[*] RPC URL: {RPC_URL}")
    print(f"[*] DApp: {DAPP_ADDRESS}")
    print(f"[*] InputBox: {INPUT_BOX_ADDRESS}")
    print(f"[*] Found {len(scenarios)} scenarios. Starting batch submission...")
    
    for filename in scenarios:
        path = scenario_dir / filename
        with open(path, 'r') as f:
            scenario_json = f.read()
            
        hex_payload = str_to_hex(scenario_json)
        
        cmd = [
            "cast", "send", INPUT_BOX_ADDRESS, 
            "addInput(address,bytes)", DAPP_ADDRESS, hex_payload,
            "--rpc-url", RPC_URL,
            "--private-key", PRIVATE_KEY
        ]
        
        print(f"[*] Sending {filename} ... ", end="", flush=True)
        if dry_run:
            print("DRY-RUN")
            continue

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
            shell=False,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
        )
        
        if result.returncode == 0:
            print("OK")
        else:
            print("FAILED")
            print(result.stderr)
            
        time.sleep(0.2) # Avoid congestion

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run Cartesi verification suite")
    parser.add_argument("--dry-run", action="store_true", help="Validate scenario traversal without sending transactions")
    args = parser.parse_args()
    run_suite(dry_run=args.dry_run)
