#!/bin/bash
echo "Fee,Severity,TimeToCollapse" > results/resilience_sweep.csv
for fee in 50 150 500; do
  for severity in 0.1 0.3 0.6 0.9; do
    echo "Running Fee: $fee, Severity: $severity"
    # Create scenario JSON dynamically
    cat <<INNER_EOF > scenarios/dynamic-sweep.json
{
  "scenario-id": "sweep-$fee-$severity",
  "protocol-params": {"resolver-fee-bps": $fee},
  "yield-config": {"modules": {"aave-v3": {"policy": "de-risking"}}},
  "events": [
    {"seq": 0, "time": 1000, "action": "set-yield-risk", "params": {"module-id": "aave-v3", "token": "USDC", "shortfall": {"available-ratio": $(echo "1.0 - $severity" | bc -l)}}},
    {"seq": 1, "time": 1100, "action": "create-escrow", "params": {"token": "USDC", "to": "0xseller", "amount": 10000, "yield-preset": "aave-v3"}},
    {"seq": 2, "time": 1200, "action": "raise-dispute", "params": {"workflow-id": 0}},
    {"seq": 3, "time": 1300, "action": "trigger-accrue", "params": {"workflow-id": 0}}
  ]
}
INNER_EOF
    # Run with limited invariant suite (just this scenario)
    clojure -M:run --scenario scenarios/dynamic-sweep.json --invariants --output-file results/sweep.json > /dev/null 2>&1
    
    # Extract time-to-collapse
    collapse=$(grep -o '"time-to-collapse":[0-9]*' results/sweep.json | cut -d: -f2)
    [ -z "$collapse" ] && collapse="NONE"
    echo "$fee,$severity,$collapse" >> results/resilience_sweep.csv
  done
done
