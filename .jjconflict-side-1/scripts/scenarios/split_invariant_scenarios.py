#!/usr/bin/env python3
"""Split invariant_scenarios.clj into domain modules + thin aggregator."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "resolver_sim" / "protocols" / "sew"
SRC_FILE = SRC / "invariant_scenarios.clj"
OUT_DIR = SRC / "invariant_scenarios"

COMMON_PARAMS = {
    "dr3",
    "dr3-module",
    "ieo",
    "ieo-timeout",
    "timeout",
    "stake-cascade",
    "appeal",
    "kleros",
    "kleros-appeal",
}

NS_DOC = (
    '  "Deterministic invariant scenarios (S01–S100) as Clojure data.\n\n'
    "   Each entry in `all-scenarios` is a scenario map accepted by\n"
    "   resolver-sim.protocols.sew/replay-with-sew-protocol.\n\n"
    "   Events use direct integer workflow-ids throughout. The Nth create_escrow\n"
    "   event produces workflow-id N-1 (zero-indexed by creation order).\n\n"
    "   Split across invariant-scenarios.* sub-namespaces; this ns aggregates\n"
    '   all-scenarios and scenario-type-registry."'
)


def scenario_bucket(defn_name: str) -> str:
    if defn_name.startswith("s62-cross-token"):
        return "extended"
    m = re.match(r"^s(\d+)", defn_name)
    if not m:
        return "extended"
    n = int(m.group(1))
    if n <= 23 or n == 46:
        return "baseline"
    if n <= 45 or n in (66, 67):
        return "adversarial"
    return "extended"


def extract_forms(text: str) -> tuple[str, list[tuple[str, str]], str, str]:
    """Return (header, scenario_forms, all_scenarios, type_registry)."""
    registry_marker = ";; Scenario registry"
    type_marker = ";; Scenario type registry"
    idx = text.index(registry_marker)
    scenarios_text = text[:idx]
    registry_text = text[idx:]

    type_idx = registry_text.index(type_marker)
    all_scenarios = registry_text[:type_idx].strip()
    type_registry = registry_text[type_idx:].strip()

    # Header through common params
    s01_idx = scenarios_text.index(";; S01")
    header = scenarios_text[:s01_idx].rstrip()
    body = scenarios_text[s01_idx:]

    # Split body into def forms (top-level def s... or def s62-cross...)
    pattern = re.compile(r"(?m)^(\(def (?:\^[^\n]*\n\s*)?s[\w-]+)", re.MULTILINE)
    matches = list(pattern.finditer(body))
    forms: list[tuple[str, str]] = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        chunk = body[start:end].rstrip()
        name_match = re.search(r"\(def (?:\^[^\n]*\n\s*)?(s[\w-]+)", chunk)
        if name_match:
            forms.append((name_match.group(1), chunk))

    return header, forms, all_scenarios, type_registry


def common_ns(header: str) -> str:
    lines = header.splitlines()
    ns_line = lines[0]
    doc_lines = lines[1:3]  # docstring lines
    param_block = "\n".join(lines[3:]).replace("(def ^:private ", "(def ")
    return f"""{ns_line.replace("invariant-scenarios", "invariant-scenarios.common")}
  (:doc "Shared protocol-param sets for invariant scenario definitions.")

{chr(10).join(doc_lines)}

{param_block.strip()}
"""


def module_ns(bucket: str, forms: list[tuple[str, str]]) -> str:
    body = "\n\n".join(chunk for _, chunk in forms)
    return f"""(ns resolver-sim.protocols.sew.invariant-scenarios.{bucket}
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

{body}
"""


def qualify_registry(text: str) -> str:
    """Prefix bare scenario var refs in all-scenarios with namespace aliases."""

    def repl(m: re.Match[str]) -> str:
        var = m.group(1)
        if var in ("s12a", "s12b") or re.match(r"^s\d", var) or var.startswith("s62-cross"):
            bucket = scenario_bucket(var)
            return f"{bucket}/{var}"
        return var

    # Match trailing scenario refs: s01, s12a, s62-cross-token-...
    return re.sub(
        r"(?<=\s)(s(?:\d{2}[a-z]?|62-cross-token[\w-]+))(?=\]|$|\n)",
        repl,
        text,
    )


def aggregator(all_scenarios: str, type_registry: str) -> str:
    all_scenarios = qualify_registry(all_scenarios)
    return f"""(ns resolver-sim.protocols.sew.invariant-scenarios
  {NS_DOC}
  (:require [resolver-sim.protocols.sew.invariant-scenarios.baseline :as baseline]
            [resolver-sim.protocols.sew.invariant-scenarios.adversarial :as adversarial]
            [resolver-sim.protocols.sew.invariant-scenarios.extended :as extended]))

{all_scenarios}

{type_registry}
"""


def main() -> None:
    text = SRC_FILE.read_text()
    header, forms, all_scenarios, type_registry = extract_forms(text)

    buckets: dict[str, list[tuple[str, str]]] = {
        "baseline": [],
        "adversarial": [],
        "extended": [],
    }
    for name, chunk in forms:
        buckets[scenario_bucket(name)].append((name, chunk))

    OUT_DIR.mkdir(exist_ok=True)
    (OUT_DIR / "common.clj").write_text(common_ns(header) + "\n")
    for bucket, bforms in buckets.items():
        (OUT_DIR / f"{bucket}.clj").write_text(module_ns(bucket, bforms) + "\n")

    SRC_FILE.write_text(aggregator(all_scenarios, type_registry) + "\n")
    print(f"Split {len(forms)} scenario defs into {OUT_DIR}")
    for bucket, bforms in buckets.items():
        print(f"  {bucket}: {len(bforms)} defs")


if __name__ == "__main__":
    main()
