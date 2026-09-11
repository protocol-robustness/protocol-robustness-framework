from __future__ import annotations

import json
import sys
from pathlib import Path

_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import mechanism_persistence as mp


def _write_run_fixture(tmp_path: Path) -> tuple[Path, Path, Path]:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    (run_dir / "claims").mkdir()
    (run_dir / "attestations").mkdir()
    inventory = {
        "dag/schema-version": "evidence-dag-inventory.v0",
        "dag/semantic-status": "parsed",
        "dag/nodes": [
            {"file": "node-a.edn",
             "node-hash": "node-a",
             "execution-id": "execution/pro-rata-allocation",
             "result-status": "pass",
             "parent-hashes": []}
        ],
        "dag/edges": [],
    }
    (run_dir / "evidence-dag-inventory.json").write_text(json.dumps(inventory))
    claim = {
        "result/hash": "claim-1",
        "result/claim-id": "claim/allocation-complete",
        "result/status": "pass",
    }
    (run_dir / "claims" / "claim-result-1.json").write_text(json.dumps(claim))
    mechanism_map = {
        "schema-version": "mechanism-map.v1",
        "mechanism-map/id": "mechanism-map/test-v1",
        "mechanism-map/version": "mechanism-map.test.v1",
        "mechanisms": [{
            "mechanism/id": "mechanism/pro-rata-fairness",
            "mechanism/name": "Pro-rata fairness",
            "required-sources": [
                {"source": "execution/id", "ids": ["execution/pro-rata-allocation"]},
                {"source": "claim/id", "ids": ["claim/allocation-complete"]},
            ],
        }],
        "scenario-applicability": [{
            "scenario/id": "scenario-1",
            "mechanisms": {"mechanism/pro-rata-fairness": "required"},
        }],
    }
    mechanism_map_path = tmp_path / "mechanism-map.json"
    mechanism_map_path.write_text(json.dumps(mechanism_map))
    benchmark = {
        "benchmark/id": "benchmark/test",
        "benchmark/scenarios": [{"scenario/id": "scenario-1"}],
    }
    benchmark_path = tmp_path / "benchmark.json"
    benchmark_path.write_text(json.dumps(benchmark))
    return run_dir, mechanism_map_path, benchmark_path


def test_build_mechanism_artifacts_marks_required_sources_passed(tmp_path: Path):
    run_dir, mechanism_map_path, benchmark_path = _write_run_fixture(tmp_path)
    index, summary, matrix = mp.build_mechanism_artifacts(
        run_dir, mechanism_map_path, benchmark_path)
    episode = index["episodes"][0]
    assert index["schema-version"] == "mechanism-persistence-index.v1"
    assert episode["status"] == "passed"
    assert episode["applicability"] == "required"
    assert episode["episode/path"] == ["node-a"]
    assert summary["status-counts"]["passed"] == 1
    assert matrix["cells"]["scenario-1|mechanism/pro-rata-fairness"]["status"] == "passed"


def test_build_mechanism_artifacts_reports_missing_required_source(tmp_path: Path):
    run_dir, mechanism_map_path, benchmark_path = _write_run_fixture(tmp_path)
    for claim_file in (run_dir / "claims").glob("*.json"):
        claim_file.unlink()
    index, _, _ = mp.build_mechanism_artifacts(run_dir, mechanism_map_path, benchmark_path)
    assert index["episodes"][0]["status"] == "evidence-missing"


def test_build_mechanism_artifacts_requires_every_id_in_source_group(tmp_path: Path):
    run_dir, mechanism_map_path, benchmark_path = _write_run_fixture(tmp_path)
    mechanism_map = json.loads(mechanism_map_path.read_text())
    mechanism_map["mechanisms"][0]["required-sources"] = [{
        "source": "claim/id",
        "ids": ["claim/allocation-complete", "claim/missing-required-claim"],
    }]
    mechanism_map_path.write_text(json.dumps(mechanism_map))

    index, _, _ = mp.build_mechanism_artifacts(run_dir, mechanism_map_path, benchmark_path)

    assert index["episodes"][0]["status"] == "evidence-missing"
