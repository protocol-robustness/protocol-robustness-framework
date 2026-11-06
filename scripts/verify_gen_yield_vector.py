#!/usr/bin/env python3
"""Independently verify the frozen yield scenario domain-hash vector.

This checks SHA-256(domain tag || retained canonical bytes). It does not
reconstruct the EDN scenario or reimplement PRF's typed canonical encoder.
"""

from hashlib import sha256
from pathlib import Path
import re
import sys


VECTOR = Path(__file__).resolve().parents[1] / "resources/test-vectors/gen-yield/materialized-v1.edn"
DOMAIN = b"YIELD_MATERIALIZED_SCENARIO_V1"


def main() -> int:
    text = VECTOR.read_text(encoding="utf-8")
    root_match = re.search(r':expected-root "sha256:([0-9a-f]{64})"', text)
    bytes_match = re.search(r':expected-bytes "([0-9a-f]+)"', text)
    if not root_match or not bytes_match:
        print("invalid frozen vector structure", file=sys.stderr)
        return 2
    encoded = bytes_match.group(1)
    if len(encoded) % 2:
        print("canonical byte vector has odd-length hex", file=sys.stderr)
        return 2
    actual = sha256(DOMAIN + bytes.fromhex(encoded)).hexdigest()
    expected = root_match.group(1)
    print(f"expected: {expected}")
    print(f"actual:   {actual}")
    print(f"result:   {'PASS' if actual == expected else 'FAIL'}")
    return 0 if actual == expected else 1


if __name__ == "__main__":
    raise SystemExit(main())
