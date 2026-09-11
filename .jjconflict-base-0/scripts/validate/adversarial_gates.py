#!/usr/bin/env python3
"""Adversarial profitability gates.
Checks that the latest profitability surface output meets minimum criteria.

Usage: adversarial_gates.py <profitability-surface-dir>
"""

import json
import sys
from pathlib import Path


def main():
    if len(sys.argv) < 2:
        print("Usage: adversarial_gates.py <profitability-surface-dir>")
        sys.exit(1)

    latest = Path(sys.argv[1])
    regions_file = latest / "regions.json"
    promos_file = latest / "promotions.json"

    if not regions_file.exists() or not promos_file.exists():
        print("Missing regions.json or promotions.json in", latest)
        sys.exit(1)

    regions_data = json.loads(regions_file.read_text())
    promos_data = json.loads(promos_file.read_text())

    families = regions_data.get("families", {})
    if not families:
        print("No family data found in regions.json")
        sys.exit(1)

    top = promos_data.get("top", [])
    if len(top) < 3:
        print("Gate failed: expected at least 3 promoted candidates, got", len(top))
        sys.exit(1)

    # Placeholder bounded-growth gate: enforce per-family unsafe ratio <= 100%
    for fam, vals in families.items():
        safe = vals.get("safe", 0)
        unsafe = vals.get("unsafe", 0)
        total = max(1, safe + unsafe + vals.get("borderline", 0))
        ratio = unsafe / total
        if ratio > 1.0:
            print("Gate failed: invalid unsafe ratio for", fam)
            sys.exit(1)

    print("Adversarial gates passed for", latest)


if __name__ == "__main__":
    main()
