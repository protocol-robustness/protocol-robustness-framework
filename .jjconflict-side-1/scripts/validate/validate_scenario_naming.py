#!/usr/bin/env python3
"""Validate scenario naming conventions and IDs.

Extracted from test.sh inline Python block at line 393.
"""

import json
import pathlib
import re
import sys


def main():
    root = pathlib.Path('data/fixtures/traces')
    pat_s = re.compile(r'^s\d{2}[a-z]?-[a-z0-9\-]+\.trace\.json$')
    pat_other = re.compile(r'^(eq-v\d+|spe-v\d+)-[a-z0-9\-]+\.trace\.json$')
    bad = []
    
    for p in sorted(root.glob('*.trace.json')):
        name = p.name
        is_eq_spe = name.startswith('eq-v') or name.startswith('spe-v')
        is_sxx = bool(re.match(r'^s\d{2}[a-z]?-', name))

        # Validate known canonical families only; allow legacy/non-canonical
        # traces (e.g. same-block-ordering.trace.json) to coexist.
        if is_eq_spe:
            if not pat_other.match(name):
                bad.append(f"bad-filename:{p}")
                continue
        elif is_sxx:
            if not pat_s.match(name):
                bad.append(f"bad-filename:{p}")
                continue

        if is_eq_spe and not pat_other.match(name):
            bad.append(f"bad-filename:{p}")
            continue
        try:
            obj = json.loads(p.read_text())
        except Exception as e:
            bad.append(f"bad-json:{p}:{e}")
            continue
        sid = obj.get('id')
        if sid:
            sid_s = str(sid)
            stem = p.name.replace('.trace.json', '')
            valid_ids = {stem, f"scenarios/{stem}"}
            if sid_s not in valid_ids:
                bad.append(f"id-mismatch:{p}:id={sid_s}:expected-one-of={sorted(valid_ids)}")

    if bad:
        print("Scenario naming/ID convention checks failed:")
        for b in bad:
            print(" -", b)
        sys.exit(1)

    print("Scenario naming/ID convention checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
