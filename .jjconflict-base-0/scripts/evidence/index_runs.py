#!/usr/bin/env python3
"""
Index all simulation runs to provide a searchable overview.
"""

import json
import pathlib
import sys

def index_runs(results_dir: pathlib.Path):
    index = []
    for run_dir in results_dir.glob("runs/*"):
        if not run_dir.is_dir():
            continue
        
        test_run_path = run_dir / "test-run.json"
        mech_summ_path = run_dir / "summaries" / "mechanism-summary.json"
        
        entry = {
            "run_dir": str(run_dir),
            "run_id": run_dir.name,
            "scenario_id": None,
            "outcome": "unknown"
        }
        
        if mech_summ_path.exists():
            try:
                with mech_summ_path.open("r") as f:
                    mech_summ = json.load(f)
                    entry["scenario_id"] = mech_summ.get("scenario_id")
                    entry["outcome"] = mech_summ.get("outcome", "unknown")
            except Exception:
                pass
                
        index.append(entry)
        
    with (results_dir / "run-index.json").open("w") as f:
        json.dump(index, f, indent=2)
    print(f"Indexed {len(index)} runs to {results_dir}/run-index.json")

if __name__ == "__main__":
    results_dir = pathlib.Path("results")
    if not results_dir.exists():
        print("Results directory not found.")
        sys.exit(1)
    index_runs(results_dir)
