#!/usr/bin/env python3
import json
import sys
from pathlib import Path

def gate_validation_root(path):
    if not path.exists():
        print(f"CI Gate: {path} not found — no validation root to check. Skipping.")
        sys.exit(0)
        
    with open(path, 'r') as f:
        root = json.load(f)
        
    status = root.get('status')
    
    # Define failure statuses
    failure_statuses = {'failed', 'failed-critical'}
    
    if status in failure_statuses:
        print(f"CI Gate: Validation failed with status '{status}'.")
        print(f"Metrics: {root.get('metrics')}")
        sys.exit(1)
    else:
        print(f"CI Gate: Validation passed with status '{status}'.")
        sys.exit(0)

if __name__ == '__main__':
    path = Path('results/test-artifacts/validation-root.json')
    gate_validation_root(path)
