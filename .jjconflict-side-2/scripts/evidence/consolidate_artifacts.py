#!/usr/bin/env python3
"""Consolidate all scenario run artifacts into a single output directory.
Replaces the scatter-mode pipeline when --output-dir is provided on run:scenario.
"""
import argparse, json, os, pathlib, shutil, subprocess, sys, tempfile

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--replay", required=True)
    ap.add_argument("--output-dir", required=True)
    ap.add_argument("--status", default="pass")
    ap.add_argument("--duration-ms", type=int, default=0)
    args = ap.parse_args()

    od = pathlib.Path(args.output_dir)
    od.mkdir(parents=True, exist_ok=True)

    # 1. Write manifest stubs
    subprocess.run([sys.executable,
        "scripts/evidence/write_scenario_run_manifest.py",
        "--scenario", args.scenario,
        "--status", args.status,
        "--duration-ms", str(args.duration_ms),
        "--output-dir", str(od)], check=True)

    # 2. Extract artifacts
    subprocess.run([sys.executable,
        "scripts/evidence/extract_scenario_artifacts.py",
        "--replay", args.replay,
        "--run-dir", str(od)], check=True)

    # 3. Copy the raw replay into the output dir
    shutil.copy2(args.replay, od / "replay-output.json")

    # 4. Validate artifact registry
    registry = od / "test-artifacts.json"
    if registry.exists():
        subprocess.run(["clojure", "-M", "-m",
            "resolver-sim.validation.integration.artifact-registry",
            str(registry)], cwd=os.getcwd())

if __name__ == "__main__":
    main()
