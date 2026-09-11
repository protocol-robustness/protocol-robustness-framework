#!/usr/bin/env python3
"""Run a scenario through bb, then consolidate artifacts into a single output dir.

Called from bb.edn's run:scenario task:
  bb run:scenario <scenario-path> [--output-dir <dir>] [other-options]

Handles both --output-dir (consolidated) and default (scatter) modes.
"""
import argparse, json, os, pathlib, shutil, subprocess, sys, tempfile, time

def main():
    # Separate scenario args from bb options
    args = sys.argv[1:]
    scenario_path = None
    output_dir = None
    other = []

    i = 0
    while i < len(args):
        if args[i] == "--scenario" and i + 1 < len(args):
            scenario_path = args[i + 1]
            i += 2
        elif args[i] == "--output-dir" and i + 1 < len(args):
            output_dir = args[i + 1]
            i += 2
        elif args[i].startswith("-"):
            other.append(args[i])
            i += 1
        elif scenario_path is None:
            scenario_path = args[i]
            i += 1
        else:
            other.append(args[i])
            i += 1

    if not scenario_path:
        print("Error: --scenario is required", file=sys.stderr)
        sys.exit(1)

    t0 = time.time()
    output_tmp = tempfile.mktemp(suffix=".json")

    # Build Clojure expression
    clojure_expr = (
        "(require 'resolver-sim.io.scenario-runner) "
        "(System/exit (:exit-code "
        "((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report) "
        "{:scenario \"" + scenario_path + "\""
        " :output-file \"" + output_tmp + "\""
        + ((" :output-dir \"" + output_dir + "\"") if output_dir else "")
        + "} {}))))"
    )
    cmd = ["clojure", "-M:with-sew", "-e", clojure_expr]
    result = subprocess.run(cmd, cwd=os.getcwd())
    exit_code = result.returncode
    dur_ms = int((time.time() - t0) * 1000)
    status = "pass" if exit_code == 0 else "fail"

    if output_dir:
        od = pathlib.Path(output_dir)
        od.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable,
            "scripts/evidence/write_scenario_run_manifest.py",
            "--scenario", scenario_path,
            "--status", status,
            "--duration-ms", str(dur_ms),
            "--output-dir", str(od)], check=True)
        if os.path.exists(output_tmp):
            subprocess.run([sys.executable,
                "scripts/evidence/extract_scenario_artifacts.py",
                "--replay", output_tmp,
                "--run-dir", str(od)], check=True)
            shutil.copy2(output_tmp, od / "replay-output.json")
        registry = od / "test-artifacts.json"
        if registry.exists():
            subprocess.run(["clojure", "-M", "-m",
                "resolver-sim.validation.integration.artifact-registry",
                str(registry)], cwd=os.getcwd())
        print()
        print("############################")
        print(f"# Scenario run complete")
        print(f"# Scenario: {scenario_path}")
        print(f"# Output dir: {od}")
        for f in sorted(od.rglob("*")):
            if f.is_file():
                print(f"#   {f}")
        print("############################")
    else:
        slug = pathlib.Path(scenario_path).stem
        subprocess.run([sys.executable,
            "scripts/evidence/write_scenario_run_manifest.py",
            "--scenario", scenario_path,
            "--status", status,
            "--duration-ms", str(dur_ms)])
        if os.path.exists(output_tmp):
            runs_dir = pathlib.Path("results/runs")
            if runs_dir.exists():
                dirs = sorted([d for d in runs_dir.iterdir() if d.is_dir() and d.name.startswith(slug)],
                              key=lambda d: d.stat().st_mtime)
                if dirs:
                    subprocess.run([sys.executable,
                        "scripts/evidence/extract_scenario_artifacts.py",
                        "--replay", output_tmp,
                        "--run-dir", str(dirs[-1])])
        if os.path.exists(output_tmp):
            os.unlink(output_tmp)

    sys.exit(exit_code)

if __name__ == "__main__":
    main()
