import requests
import json
import binascii
import os

GRAPHQL_URL = "http://127.0.0.1:8080/graphql"
OUTPUT_DIR = "outputs"
REQUEST_TIMEOUT_SECONDS = 10

def hex_to_str(hex_str):
    if hex_str.startswith("0x"):
        hex_str = hex_str[2:]
    return binascii.unhexlify(hex_str).decode("utf-8")

def fetch_results():
    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)

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
    print(f"[*] Fetching notices from {GRAPHQL_URL}...")
    try:
        response = requests.post(GRAPHQL_URL, json={'query': query}, timeout=REQUEST_TIMEOUT_SECONDS)
        response.raise_for_status()
        data = response.json()
        notices = data.get("data", {}).get("notices", {}).get("edges", [])
        
        if not notices:
            print("[!] No notices found. Make sure the DApp has processed the inputs.")
            return

        print(f"[*] Found {len(notices)} verification outputs. Saving to {OUTPUT_DIR}/...")
        
        for edge in notices:
            node = edge["node"]
            idx = node["index"]
            payload = node["payload"]
            
            decoded = hex_to_str(payload)
            try:
                result_json = json.loads(decoded)
                scenario_id = result_json.get("scenario-id", f"unknown_{idx}")
                filename = f"{scenario_id}_output.json"
                
                with open(os.path.join(OUTPUT_DIR, filename), "w") as f:
                    json.dump(result_json, f, indent=2)
                
                print(f"[+] Saved {filename}")
            except json.JSONDecodeError:
                print(f"[!] Could not decode notice {idx} as JSON.")

    except Exception as e:
        print(f"[!] Error fetching results: {e}")

if __name__ == "__main__":
    fetch_results()
