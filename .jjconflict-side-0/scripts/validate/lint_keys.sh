#!/bin/bash
# Validate keys/owners.json consistency with keys/ directory

OWNERS_FILE="keys/owners.json"

if [ ! -f "$OWNERS_FILE" ]; then
  echo "Error: keys/owners.json missing."
  exit 1
fi

# Simple check: every public_key in owners.json exists in keys/
python3 -c "
import json, pathlib
owners = json.loads(pathlib.Path('$OWNERS_FILE').read_text())['owners']
for rid, data in owners.items():
    pub = pathlib.Path(data['public_key'])
    if not pub.exists():
        print(f'Error: Public key {pub} for {rid} not found.')
        exit(1)
    # Check fingerprint? Could be added here.
"
if [ $? -ne 0 ]; then
    exit 1
fi

echo "Keys lint passed."
