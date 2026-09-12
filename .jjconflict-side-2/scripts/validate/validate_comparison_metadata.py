#!/usr/bin/env python3
"""Validate comparison metadata for equivalence testing.

Extracted from test.sh inline Python block at line 664.
"""

import json
import sys
from pathlib import Path


def main():
    traces_dir = Path("data/fixtures/traces")
    files = sorted(traces_dir.glob("*.trace.json"))

    entries = []
    errors = []

    for p in files:
        try:
            obj = json.loads(p.read_text())
        except Exception as e:
            errors.append(f"bad-json:{p}:{e}")
            continue

        comp = obj.get("comparison")
        if not comp:
            continue
        if not isinstance(comp, dict):
            errors.append(f"comparison-not-object:{p}")
            continue

        sid = str(obj.get("scenario-id") or obj.get("id") or p.stem.replace('.trace', ''))
        group = str(comp.get("comparison_group", "")).strip()
        variant = str(comp.get("variant", "")).strip()
        cf = str(comp.get("counterfactual_of", "")).strip()

        if not group:
            errors.append(f"missing-comparison-group:{p}")
        if variant not in {"A", "B"}:
            errors.append(f"invalid-variant:{p}:{variant}")
        if not cf:
            errors.append(f"missing-counterfactual-of:{p}")

        es = obj.get("expected_semantics", {})
        pc = es.get("path_constraints", {}) if isinstance(es, dict) else {}
        mo = pc.get("must_observe") if isinstance(pc, dict) else None
        if not isinstance(mo, list) or len(mo) == 0:
            errors.append(f"missing-path-constraints.must_observe:{p}")

        entries.append({
            "id": sid,
            "group": group,
            "variant": variant,
            "counterfactual_of": cf,
            "path": str(p),
        })

    by_id = {e["id"]: e for e in entries}
    groups = {}
    for e in entries:
        groups.setdefault(e["group"], []).append(e)

    for grp, members in groups.items():
        if len(members) != 2:
            errors.append(f"group-size-not-2:{grp}:{len(members)}")
            continue
        variants = {m["variant"] for m in members}
        if variants != {"A", "B"}:
            errors.append(f"group-variants-invalid:{grp}:{sorted(variants)}")

        a, b = members[0], members[1]
        if a["counterfactual_of"] not in by_id:
            errors.append(f"counterfactual-target-missing:{a['id']}->{a['counterfactual_of']}")
        if b["counterfactual_of"] not in by_id:
            errors.append(f"counterfactual-target-missing:{b['id']}->{b['counterfactual_of']}")

        if a["counterfactual_of"] != b["id"] or b["counterfactual_of"] != a["id"]:
            errors.append(f"counterfactual-not-reciprocal:{grp}:{a['id']}<->{b['id']}")

    if errors:
        print("Comparison metadata lint failed:")
        for e in errors:
            print(" -", e)
        sys.exit(1)

    print(f"Comparison metadata lint passed for {len(entries)} traces across {len(groups)} groups")
    return 0


if __name__ == "__main__":
    sys.exit(main())
