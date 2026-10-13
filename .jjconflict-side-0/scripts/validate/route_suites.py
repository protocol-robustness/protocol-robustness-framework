import json
import os
import subprocess
import re

def get_changed_files():
    # Get files changed between main and current, plus untracked
    cmd = ["git", "diff", "--name-only", "main"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    changed = result.stdout.splitlines()
    
    cmd_untracked = ["git", "ls-files", "--others", "--exclude-standard"]
    result_untracked = subprocess.run(cmd_untracked, capture_output=True, text=True)
    untracked = result_untracked.stdout.splitlines()
    
    return changed + untracked

def load_suite_map():
    # Simple parser for the EDN manifest
    suite_map = {}
    manifest_path = "data/fixtures/suites/manifest.edn"
    with open(manifest_path, 'r') as f:
        # Simple regex-based extraction of suite-id to file mapping
        content = f.read()
        pattern = r':(\S+)\s+\{:file\s+"([^"]+)"'
        for suite_id, filename in re.findall(pattern, content):
            suite_map[suite_id] = f"data/fixtures/suites/{filename}"
    return suite_map

def get_suite_traces(suite_file):
    # Extract traces from the suite file
    with open(suite_file, 'r') as f:
        content = f.read()
        # Find :traces [...]
        trace_pattern = r':traces\s*\[(.*?)\]'
        match = re.search(trace_pattern, content, re.DOTALL)
        if match:
            traces = re.findall(r':\S+', match.group(1))
            return traces
    return []

def main():
    changed_files = get_changed_files()
    # If src/ changes, we are conservative and run all suites.
    if any(f.startswith("src/") for f in changed_files):
        print("all")
        return

    suite_map = load_suite_map()
    impacted_suites = set()
    
    # Check for scenario/trace changes
    for suite_id, suite_file in suite_map.items():
        traces = get_suite_traces(suite_file)
        for trace in traces:
            # Map trace keyword back to file path
            trace_name = trace.replace(':traces/', '')
            trace_path = f"data/fixtures/traces/{trace_name}.trace.json"
            if trace_path in changed_files:
                impacted_suites.add(suite_id)
        
        # Also check if the suite file itself changed
        if suite_file in changed_files:
            impacted_suites.add(suite_id)

    if not impacted_suites:
        print("none")
    else:
        # Format for Clojure suite list
        print(",".join(impacted_suites))

if __name__ == "__main__":
    main()
