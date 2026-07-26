import subprocess
import json
import binascii
import requests
import argparse
from pathlib import Path

# Configuration
DAPP_ADDRESS = "0xab7528bb862fB57E8A2BCd567a2e929a0Be56a5e"
INPUT_BOX_ADDRESS = "0x59b22D57D4f067708AB0c00552767405926dc768"
RPC_URL = "http://127.0.0.1:8545"
INSPECT_URL = "http://127.0.0.1:8080/inspect"
GRAPHQL_URL = "http://127.0.0.1:8080/graphql"
PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
REQUEST_TIMEOUT_SECONDS = 10
SUBPROCESS_TIMEOUT_SECONDS = 30

def hex_to_str(hex_str):
    if hex_str.startswith("0x"):
        hex_str = hex_str[2:]
    return binascii.unhexlify(hex_str).decode("utf-8")

def str_to_hex(s):
    return "0x" + binascii.hexlify(s.encode("utf-8")).decode("utf-8")

def send_scenario(file_path):
    print(f"[*] Reading scenario from {file_path}...")
    scenario_path = Path(file_path).resolve()
    if scenario_path.suffix.lower() != ".json":
        print("[!] Error: only .json scenario files are allowed.")
        return

    try:
        with scenario_path.open("r", encoding="utf-8") as f:
            scenario = f.read()
    except FileNotFoundError:
        print(f"[!] Error: File {scenario_path} not found.")
        return

    hex_payload = str_to_hex(scenario)
    
    cmd = [
        "cast", "send", INPUT_BOX_ADDRESS, 
        "addInput(address,bytes)", DAPP_ADDRESS, hex_payload,
        "--rpc-url", RPC_URL,
        "--private-key", PRIVATE_KEY
    ]
    
    print("[*] Sending transaction to InputBox...")
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        check=False,
        shell=False,
        timeout=SUBPROCESS_TIMEOUT_SECONDS,
    )
    
    if result.returncode != 0:
        print("[!] Error sending transaction:")
        print(result.stderr)
        return

    print("[+] Transaction sent successfully!")
    # Parse transaction hash if possible
    for line in result.stdout.splitlines():
        if "transactionHash" in line:
            print(f"[*] {line.strip()}")

def inspect_state(query="all"):
    print(f"[*] Querying DApp state (query: {query})...")
    payload = str_to_hex(query)
    url = f"{INSPECT_URL}/{payload}"
    
    try:
        response = requests.get(url, timeout=REQUEST_TIMEOUT_SECONDS)
        response.raise_for_status()
        data = response.json()
        
        reports = data.get("reports", [])
        if not reports:
            print("[!] No reports returned from inspection.")
            return

        for report in reports:
            payload_hex = report.get("payload", "")
            if payload_hex:
                decoded = hex_to_str(payload_hex)
                try:
                    formatted = json.dumps(json.loads(decoded), indent=2)
                    print(formatted)
                except json.JSONDecodeError:
                    print(decoded)
    except Exception as e:
        print(f"[!] Error during inspection: {e}")

def get_notices():
    query = """
    query {
      notices {
        edges {
          node {
            index
            input {
              index
            }
            payload
          }
        }
      }
    }
    """
    try:
        response = requests.post(GRAPHQL_URL, json={'query': query}, timeout=REQUEST_TIMEOUT_SECONDS)
        response.raise_for_status()
        data = response.json()
        notices = data.get("data", {}).get("notices", {}).get("edges", [])
        
        if not notices:
            print("[*] No notices found.")
            return

        print(f"[*] Found {len(notices)} notices:")
        for edge in notices:
            node = edge["node"]
            idx = node["index"]
            input_idx = node["input"]["index"]
            payload = node["payload"]
            decoded = hex_to_str(payload)
            print(f"\n--- Notice {idx} (Input {input_idx}) ---")
            try:
                print(json.dumps(json.loads(decoded), indent=2))
            except json.JSONDecodeError:
                print(decoded)
    except Exception as e:
        print(f"[!] Error fetching notices: {e}")

def main():
    parser = argparse.ArgumentParser(description="Sew Cartesi CLI")
    subparsers = parser.add_subparsers(dest="command", help="Commands")

    # Send command
    send_parser = subparsers.add_parser("send", help="Send a scenario to the DApp")
    send_parser.add_argument("file", help="Path to the scenario JSON file")

    # Inspect command
    inspect_parser = subparsers.add_parser("inspect", help="Inspect DApp state")
    inspect_parser.add_argument("query", nargs="?", default="all", help="Query type (all, metrics, history)")

    # Notices command
    subparsers.add_parser("notices", help="Fetch all notices (simulation results)")

    args = parser.parse_args()

    if args.command == "send":
        send_scenario(args.file)
    elif args.command == "inspect":
        inspect_state(args.query)
    elif args.command == "notices":
        get_notices()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
