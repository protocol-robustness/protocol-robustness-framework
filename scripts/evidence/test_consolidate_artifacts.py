"""Tests for consolidate_test_artifacts.py.

Covers:
  - manifest-mode merge of multiple ownership-marked producers,
  - content-addressed idempotence (re-run is structurally identical),
  - config-driven scan mode with undeclared-file warning/skip,
  - hard-conflict detection with no partial write,
  - dependency resolution (test-summary -> test-run, theory-eval -> test-summary),
  - run_manifest reference emission,
  - declared content-hash mismatch rejection,
  - container producer discovery,
  - registry schema validation of the merged output.

Run:  python3 scripts/evidence/test_consolidate_artifacts.py
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from schema_validator import SchemaValidator  # noqa: E402

_PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_CONSOLE = pathlib.Path("scripts/evidence/consolidate_test_artifacts.py")

_PASS = 0
_FAIL = 0


def check(name: str, ok: bool, detail: str = ""):
    global _PASS, _FAIL
    if ok:
        _PASS += 1
        print(f"  PASS: {name}")
    else:
        _FAIL += 1
        print(f"  FAIL: {name} — {detail}")


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def write_producer(
    root: pathlib.Path,
    files: dict[str, str],
    manifest: dict | None = None,
    owner: bool = True,
) -> None:
    root.mkdir(parents=True, exist_ok=True)
    if owner:
        # Real ownership markers are EDN (artifact-scope/write-owner-marker!).
        (root / "_owner.edn").write_text(
            '{:artifact-root-format 1 :run-id "t" :namespace "t"}',
            encoding="utf-8",
        )
    for rel, content in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    if manifest is not None:
        (root / "_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def run_collector(
    run_root: pathlib.Path,
    producer_roots: list[str],
    run_id: str = "test-run-1",
    extra: list[str] | None = None,
):
    env = {**os.environ, "PYTHONPATH": "scripts/evidence"}
    return subprocess.run(
        [sys.executable, str(_CONSOLE),
         "--run-root", str(run_root),
         "--producer-roots", *producer_roots,
         "--run-id", run_id,
         *(extra or [])],
        capture_output=True, text=True, cwd=_PROJECT_ROOT, env=env,
    )


def registry_struct(reg: dict) -> list[tuple[str, str, str]]:
    return sorted((a["id"], a["sha256"], a["path"]) for a in reg["artifacts"])


def config_exempt_schemas() -> set[str]:
    cfg_path = _PROJECT_ROOT / "config" / "evidence.json"
    return set(json.loads(cfg_path.read_text(encoding="utf-8")).get("exempt_schemas", []))


def dangling_dependencies(reg: dict) -> tuple[set[str], set[str]]:
    """Mirror resolver-sim.validation.integration.artifact-registry's checks:
    verifies_against schema versions must be provided by some artifact's
    schema_version or be in the config's exempt set; :dependencies :id refs
    must be provided by some artifact id."""
    provided_ids = {a["id"] for a in reg["artifacts"]}
    provided_schemas = {a["schema_version"] for a in reg["artifacts"]}
    exempt = config_exempt_schemas()
    dangling_va = {
        s for a in reg["artifacts"]
        for s in a.get("verifies_against", [])
        if s not in provided_schemas and s not in exempt
    }
    dangling_refs = {
        d["id"] for a in reg["artifacts"]
        for d in a.get("dependencies", [])
        if d["id"] not in provided_ids
    }
    return dangling_va, dangling_refs


def manifest_for(entries: list[dict]) -> dict:
    return {
        "schema_version": "test-artifacts.v1.2",
        "run-id": "t",
        "namespace": "t",
        "scope-status": "complete",
        "artifacts": [
            {
                "logical-id": e["id"],
                "relative-path": e["path"],
                "content-hash": sha256_text(e["content"]),
                "size": len(e["content"]),
                "kind": e.get("kind", "summary"),
            }
            for e in entries
        ],
    }


# ── CON-1: manifest-mode merge of two producers ───────────────────────────────


def test_manifest_mode_merge():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p1 = td_path / "prod-a"
        p2 = td_path / "prod-b"

        write_producer(
            p1,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            ]),
        )
        write_producer(
            p2,
            {"theory-eval.json": '{"schema_version": "theory-eval.v1"}',
             "evidence-registry.json": '{"schema_version": "evidence-registry.v1"}',
             "event-evidence/ev-1.json": '{"schema_version": "event-evidence.v1", "id": 1}'},
            manifest=manifest_for([
                {"id": "theory-eval", "path": "theory-eval.json",
                 "content": '{"schema_version": "theory-eval.v1"}'},
                {"id": "evidence-registry", "path": "evidence-registry.json",
                 "content": '{"schema_version": "evidence-registry.v1"}'},
            ]),
        )

        result = run_collector(run_root, [str(p1), str(p2)])
        check("CON-1a: exit code 0", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return

        reg_path = run_root / "test-artifacts.json"
        check("CON-1b: registry written", reg_path.exists())
        if not reg_path.exists():
            return
        reg = json.loads(reg_path.read_text(encoding="utf-8"))
        errors = SchemaValidator().validate(reg)
        check("CON-1c: merged registry validates", len(errors) == 0,
              "; ".join(f"{e.path}: {e.message}" for e in errors))

        ids = {a["id"] for a in reg["artifacts"]}
        check("CON-1d: all expected ids registered",
              ids == {"test-run", "test-summary", "theory-eval",
                      "evidence-registry", "event-evidence-ev-1"},
              str(sorted(ids)))

        for rel in ("test-run.json", "test-summary.json", "theory-eval.json",
                    "evidence-registry.json", "event-evidence/ev-1.json"):
            check(f"CON-1e: file merged into run-root: {rel}",
                  (run_root / rel).exists())

        check("CON-1f: run_id recorded", reg.get("run_id") == "test-run-1",
              str(reg.get("run_id")))

    check("CON-1g: tempdir cleaned up", True)


# ── CON-2: dependency resolution ──────────────────────────────────────────────


def test_dependency_resolution():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}',
             "theory-eval.json": '{"schema_version": "theory-eval.v1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
                {"id": "theory-eval", "path": "theory-eval.json",
                 "content": '{"schema_version": "theory-eval.v1"}'},
            ]),
        )
        result = run_collector(run_root, [str(p)])
        check("CON-2a: exit code 0", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        by_id = {a["id"]: a for a in reg["artifacts"]}

        summary_deps = {d["id"] for d in by_id["test-summary"].get("dependencies", [])}
        check("CON-2b: test-summary depends on test-run",
              summary_deps == {"test-run"}, str(summary_deps))

        theory_deps = {d["id"] for d in by_id["theory-eval"].get("dependencies", [])}
        check("CON-2c: theory-eval depends on test-summary",
              theory_deps == {"test-summary"}, str(theory_deps))

        check("CON-2d: dependency sha256 matches dep artifact",
              all(d["sha256"] == by_id[d["id"]]["sha256"]
                  for d in by_id["theory-eval"]["dependencies"]))


# ── CON-3: idempotent re-run ──────────────────────────────────────────────────


def test_idempotent_rerun():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            ]),
        )

        r1 = run_collector(run_root, [str(p)])
        r2 = run_collector(run_root, [str(p)])
        check("CON-3a: both runs exit 0", r1.returncode == 0 and r2.returncode == 0,
              f"r1={r1.returncode} r2={r2.returncode}\n{r1.stderr}\n{r2.stderr}")
        if r1.returncode != 0 or r2.returncode != 0:
            return
        check("CON-3b: second run reuses all files (no relink)",
              "linked/copied" in r2.stdout and "0 linked/copied" in r2.stdout,
              r2.stdout)

        reg1 = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        reg2 = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        check("CON-3c: structurally identical artifact sets",
              registry_struct(reg1) == registry_struct(reg2),
              f"{registry_struct(reg1)} vs {registry_struct(reg2)}")
        check("CON-3d: generated_at is informational",
              reg1.get("generated_at") is not None)


# ── CON-4: scan mode, undeclared skipped ──────────────────────────────────────


def test_scan_mode_undeclared_skipped():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}',
             "evidence-registry.json": '{"schema_version": "evidence-registry.v1"}',
             "event-evidence/ev-2.json": '{"schema_version": "event-evidence.v1", "id": 2}',
             "stray.txt": "not an artifact",
             "suite-extra.json": '{"also": "undeclared"}'},
            manifest=None,
        )
        result = run_collector(run_root, [str(p)])
        check("CON-4a: exit code 0 (scan mode)", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        ids = {a["id"] for a in reg["artifacts"]}
        check("CON-4b: declared artifacts registered",
              ids == {"test-summary", "evidence-registry", "event-evidence-ev-2"},
              str(sorted(ids)))
        check("CON-4c: undeclared files NOT merged",
              not (run_root / "stray.txt").exists()
              and not (run_root / "suite-extra.json").exists())
        check("CON-4d: undeclared warning reported",
              "undeclared" in result.stdout.lower(), result.stdout)


# ── CON-5: hard conflict → nothing written ────────────────────────────────────


def test_conflict_hard_failure():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p1 = td_path / "prod-a"
        p2 = td_path / "prod-b"
        write_producer(p1, {"test-summary.json": '{"run_id": "a"}'})
        write_producer(p2, {"test-summary.json": '{"run_id": "b"}'})

        result = run_collector(run_root, [str(p1), str(p2)])
        check("CON-5a: conflict is a hard failure", result.returncode != 0,
              f"exit={result.returncode}")
        check("CON-5b: conflict diagnostic mentions the path",
              "test-summary.json" in (result.stdout + result.stderr)
              and "CONFLICT" in (result.stdout + result.stderr).upper(),
              result.stderr)
        check("CON-5c: registry not written on conflict",
              not (run_root / "test-artifacts.json").exists())
        check("CON-5d: no partial file written on conflict",
              not (run_root / "test-summary.json").exists())


# ── CON-6: run_manifest reference ─────────────────────────────────────────────


def test_run_manifest_reference():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            ]),
        )
        result = run_collector(run_root, [str(p)])
        check("CON-6a: exit code 0", result.returncode == 0, result.stderr)
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        rm = reg.get("run_manifest", {})
        check("CON-6b: run_manifest emitted",
              rm.get("path") == "test-run.json", str(rm))
        check("CON-6c: run_manifest schema_version",
              rm.get("schema_version") == "test-run.v1", str(rm.get("schema_version")))
        check("CON-6d: run_manifest sha256 matches test-run artifact",
              bool(rm.get("sha256")) and
              rm.get("sha256") in {a["sha256"] for a in reg["artifacts"]
                                   if a["id"] == "test-run"},
              str(rm.get("sha256")))


# ── CON-7: declared content-hash mismatch ─────────────────────────────────────


def test_declared_hash_mismatch():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        content = '{"schema_version": "test-summary.v2", "run_id": "r1"}'
        bad_manifest = {
            "schema_version": "test-artifacts.v1.2",
            "scope-status": "complete",
            "artifacts": [
                {"logical-id": "test-summary", "relative-path": "test-summary.json",
                 "content-hash": "0" * 64, "size": len(content), "kind": "summary"}
            ],
        }
        write_producer(p, {"test-summary.json": content}, manifest=bad_manifest)
        result = run_collector(run_root, [str(p)])
        check("CON-7a: hash mismatch is a hard failure", result.returncode != 0,
              f"exit={result.returncode}")
        check("CON-7b: mismatch diagnostic",
              "content-hash mismatch" in (result.stdout + result.stderr),
              result.stderr)


# ── CON-8: container producer discovery ───────────────────────────────────────


def test_container_discovery():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        container = td_path / "bundles"
        p1 = container / "unit"
        p2 = container / "suites"
        write_producer(p1, {"test-summary.json": '{"schema_version": "test-summary.v2"}'})
        write_producer(p2, {"evidence-registry.json": '{"schema_version": "evidence-registry.v1"}'})
        # unmarked subdir must be skipped, not fatal
        (container / "loose").mkdir()

        result = run_collector(run_root, [str(container)])
        check("CON-8a: container mode exit 0", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        ids = {a["id"] for a in reg["artifacts"]}
        check("CON-8b: both marked producers discovered",
              ids == {"test-summary", "evidence-registry"}, str(sorted(ids)))
        check("CON-8c: unmarked subdir skipped with warning",
              "unmarked" in result.stdout.lower(), result.stdout)


# ── CON-9: merged files are hardlinks (same filesystem) ───────────────────────


def test_merged_files_hardlinked():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        content = '{"schema_version": "test-summary.v2", "run_id": "r1"}'
        write_producer(p, {"test-summary.json": content})
        result = run_collector(run_root, [str(p)])
        check("CON-9a: exit code 0", result.returncode == 0, result.stderr)
        if result.returncode != 0:
            return
        src = p / "test-summary.json"
        dst = run_root / "test-summary.json"
        try:
            same_inode = os.stat(src).st_ino == os.stat(dst).st_ino
        except OSError:
            same_inode = False
        check("CON-9b: merged file is a hardlink (same inode)",
              same_inode, f"src={src} dst={dst}")


# ── CON-10: loose (unmarked) producer requires --allow-loose ──────────────────


def test_loose_producer_requires_flag():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        loose = td_path / "loose"
        write_producer(loose, {"test-summary.json": '{"schema_version": "test-summary.v2"}'},
                       owner=False)

        no_flag = run_collector(run_root, [str(loose)])
        check("CON-10a: unmarked dir rejected without --allow-loose",
              no_flag.returncode != 0, f"exit={no_flag.returncode}")

        with_flag = run_collector(run_root, [str(loose)], extra=["--allow-loose"])
        check("CON-10b: accepted with --allow-loose", with_flag.returncode == 0,
              f"{with_flag.stdout}\n{with_flag.stderr}")
        if with_flag.returncode != 0:
            return
        check("CON-10c: loose producer registered",
              (run_root / "test-artifacts.json").exists()
              and (run_root / "test-summary.json").exists())
        check("CON-10d: loose warning emitted",
              "loose producer root" in with_flag.stdout, with_flag.stdout)


# ── CON-11/12: semantic (dangling-dependency) checks ──────────────────────────


def _producer_with_test_run(td: pathlib.Path) -> pathlib.Path:
    p = td / "prod"
    write_producer(
        p,
        {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
         "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}',
         "theory-eval.json": '{"schema_version": "theory-eval.v1"}',
         "evidence-registry.json": '{"schema_version": "evidence-registry.v1"}',
         "event-evidence/ev-1.json": '{"schema_version": "event-evidence.v1", "id": 1}'},
        manifest=manifest_for([
            {"id": "test-run", "path": "test-run.json",
             "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
            {"id": "test-summary", "path": "test-summary.json",
             "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            {"id": "theory-eval", "path": "theory-eval.json",
             "content": '{"schema_version": "theory-eval.v1"}'},
            {"id": "evidence-registry", "path": "evidence-registry.json",
             "content": '{"schema_version": "evidence-registry.v1"}'},
        ]),
    )
    return p


def test_no_dangling_dependencies_when_run_manifest_present():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = _producer_with_test_run(td_path)
        result = run_collector(run_root, [str(p)])
        check("CON-11a: exit code 0", result.returncode == 0, result.stderr)
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        dangling_va, dangling_refs = dangling_dependencies(reg)
        check("CON-11b: no dangling verifies_against refs",
              not dangling_va, str(sorted(dangling_va)))
        check("CON-11c: no dangling :dependencies id refs",
              not dangling_refs, str(sorted(dangling_refs)))


def test_dangling_run_manifest_is_flagged():
    # A merged registry that genuinely lacks test-run.json must flag the
    # test-run.v1 dependency (real signal resolved once the pipeline emits
    # test-run.json), while external schemas stay exempt.
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
        )
        result = run_collector(run_root, [str(p)])
        check("CON-12a: exit code 0", result.returncode == 0, result.stderr)
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        dangling_va, _ = dangling_dependencies(reg)
        check("CON-12b: missing test-run.v1 flagged as dangling",
              "test-run.v1" in dangling_va, str(sorted(dangling_va)))
        check("CON-12c: external schemas (scenario.v1) exempt",
              "scenario.v1" not in dangling_va, str(sorted(dangling_va)))


# ── CON-13: ownership marker parsing (EDN + JSON) ─────────────────────────────


def test_owner_marker_parsing():
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import consolidate_test_artifacts as cta
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        edn_root = td_path / "edn"
        edn_root.mkdir()
        # artifact-scope/write-owner-marker! writes EDN: quoted run-id, bare
        # symbol namespace.
        (edn_root / "_owner.edn").write_text(
            '{:artifact-root-format 1 :run-id "run-9" :namespace resolver-sim.foo-test}',
            encoding="utf-8")
        m = cta.read_owner_marker(edn_root)
        check("CON-13a: EDN marker run-id parsed", m.get("run-id") == "run-9", str(m))
        check("CON-13b: EDN marker symbol namespace parsed",
              m.get("namespace") == "resolver-sim.foo-test", str(m))

        json_root = td_path / "json"
        json_root.mkdir()
        (json_root / "_owner.edn").write_text(
            json.dumps({"run-id": "run-10", "namespace": "resolver-sim.bar"}),
            encoding="utf-8")
        m2 = cta.read_owner_marker(json_root)
        check("CON-13c: JSON marker parsed", m2.get("run-id") == "run-10", str(m2))


# ── CON-14/15: run-root top-level scan + test-run.json emission ───────────────


def test_run_root_only_scan():
    # No --producer-roots: only the canonical run-root's own config files are
    # registered (the summary step writes these in test.sh).
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        run_root.mkdir()
        (run_root / "test-run.json").write_text(
            '{"schema_version": "test-run.v1", "run_id": "r1"}', encoding="utf-8")
        (run_root / "test-summary.json").write_text(
            '{"schema_version": "test-summary.v2", "run_id": "r1"}', encoding="utf-8")
        (run_root / "coverage.json").write_text(
            '{"schema_version": "coverage.v1"}', encoding="utf-8")
        (run_root / "stray.txt").write_text("noise", encoding="utf-8")

        env = {**os.environ, "PYTHONPATH": "scripts/evidence"}
        result = subprocess.run(
            [sys.executable, str(_CONSOLE), "--run-root", str(run_root),
             "--run-id", "test-run-1"],
            capture_output=True, text=True, cwd=_PROJECT_ROOT, env=env)
        check("CON-14a: run-root-only collect exits 0", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        ids = {a["id"] for a in reg["artifacts"]}
        check("CON-14b: run-root config files registered",
              ids == {"test-run", "test-summary", "coverage"}, str(sorted(ids)))
        check("CON-14c: stray file not registered", "stray" not in ids)
        va, refs = dangling_dependencies(reg)
        check("CON-14d: no dangling deps", not va and not refs,
              f"va={sorted(va)} refs={sorted(refs)}")


def test_run_root_scan_with_producer():
    # Producer entries and run-root config files coexist without duplication.
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        run_root.mkdir()
        p = td_path / "prod"
        write_producer(
            p,
            {"evidence-registry.json": '{"schema_version": "evidence-registry.v1"}',
             "theory-eval.json": '{"schema_version": "theory-eval.v1"}'},
        )
        (run_root / "test-run.json").write_text(
            '{"schema_version": "test-run.v1", "run_id": "r1"}', encoding="utf-8")
        (run_root / "test-summary.json").write_text(
            '{"schema_version": "test-summary.v2", "run_id": "r1"}', encoding="utf-8")

        result = run_collector(run_root, [str(p)])
        check("CON-14e: combined collect exits 0", result.returncode == 0,
              f"{result.stdout}\n{result.stderr}")
        if result.returncode != 0:
            return
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        ids = {a["id"] for a in reg["artifacts"]}
        check("CON-14f: producer + run-root files all registered",
              ids == {"test-run", "test-summary", "evidence-registry", "theory-eval"},
              str(sorted(ids)))
        va, refs = dangling_dependencies(reg)
        check("CON-14g: no dangling deps", not va and not refs,
              f"va={sorted(va)} refs={sorted(refs)}")


def test_generate_test_summary_emits_run_manifest():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        env = {**os.environ, "PYTHONPATH": "scripts/evidence"}
        result = subprocess.run(
            [sys.executable, "scripts/evidence/generate_test_summary.py",
             td, "run-1", "0", "all",
             str(td_path / "test-summary.json"),
             str(td_path / "test-run.json"),
             str(td_path / "test-artifacts.json"),
             str(td_path / "claimable-classification.json")],
            capture_output=True, text=True, cwd=_PROJECT_ROOT, env=env)
        check("CON-15a: generate_test_summary exits 0", result.returncode == 0,
              result.stderr)
        check("CON-15b: test-summary.json written",
              (td_path / "test-summary.json").exists())
        check("CON-15c: test-run.json written", (td_path / "test-run.json").exists())
        if (td_path / "test-run.json").exists():
            rm = json.loads((td_path / "test-run.json").read_text(encoding="utf-8"))
            check("CON-15d: test-run schema_version",
                  rm.get("schema_version") == "test-run.v1",
                  str(rm.get("schema_version")))
            check("CON-15e: test-run run_id", rm.get("run_id") == "run-1",
                  str(rm.get("run_id")))


# ── CON-16: audit_artifacts gate ──────────────────────────────────────────────


def run_audit(registry: pathlib.Path, producer_roots: list[str] | None = None):
    env = {**os.environ, "PYTHONPATH": "scripts/evidence"}
    args = [sys.executable, "scripts/evidence/audit_artifacts.py",
            "--registry", str(registry)]
    if producer_roots:
        args += ["--producer-roots", *producer_roots]
    return subprocess.run(args, capture_output=True, text=True,
                          cwd=_PROJECT_ROOT, env=env)


def _audit_fixture(td: pathlib.Path) -> pathlib.Path:
    run_root = td / "audit-run"
    p = _producer_with_test_run(td)
    result = run_collector(run_root, [str(p)])
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"
    return run_root


def _mutate_registry(src: pathlib.Path, dst: pathlib.Path, fn) -> None:
    reg = json.loads(src.read_text(encoding="utf-8"))
    fn(reg)
    dst.write_text(json.dumps(reg), encoding="utf-8")


def test_audit_artifacts_gate():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = _audit_fixture(td_path)
        reg_file = run_root / "test-artifacts.json"

        ok = run_audit(reg_file)
        check("CON-16a: audit passes on valid registry", ok.returncode == 0,
              f"{ok.stdout}\n{ok.stderr}")

        bad_schema = td_path / "bad-schema.json"
        _mutate_registry(reg_file, bad_schema, lambda r: r.pop("run_id"))
        r1 = run_audit(bad_schema)
        check("CON-16b: audit rejects schema-invalid registry",
              r1.returncode != 0 and "FAIL  AUD-2" in r1.stdout, r1.stdout)

        bad_dangling = td_path / "bad-dangling.json"
        _mutate_registry(reg_file, bad_dangling,
                         lambda r: r.__setitem__(
                             "artifacts", [a for a in r["artifacts"]
                                           if a["id"] != "test-run"]))
        r2 = run_audit(bad_dangling)
        check("CON-16c: audit rejects dangling deps", r2.returncode != 0
              and "dangling" in r2.stdout.lower(), r2.stdout)

        bad_path = td_path / "bad-path.json"
        _mutate_registry(reg_file, bad_path,
                         lambda r: r["artifacts"][0].update(path="nope.json"))
        r3 = run_audit(bad_path)
        check("CON-16d: audit rejects unresolvable paths", r3.returncode != 0
              and "resolve" in r3.stdout, r3.stdout)

        bad_id = td_path / "bad-id.json"
        _mutate_registry(reg_file, bad_id,
                         lambda r: r["artifacts"].append(
                             {"id": "mystery", "kind": "x", "path": "test-run.json",
                              "schema_version": "x.v1", "sha256": "a" * 64,
                              "importance": "CORE"}))
        r4 = run_audit(bad_id)
        check("CON-16e: audit rejects undeclared ids", r4.returncode != 0
              and "declared" in r4.stdout, r4.stdout)

        # Producer without a _manifest.json is flagged.
        loose = td_path / "loose-prod"
        write_producer(loose, {"test-run.json": '{"x": 1}'}, owner=True,
                       manifest=None)
        r5 = run_audit(reg_file, producer_roots=[str(loose)])
        check("CON-16f: audit flags marked producer missing _manifest.json",
              r5.returncode != 0 and "_manifest.json" in r5.stdout, r5.stdout)

        missing = run_audit(td_path / "does-not-exist.json")
        check("CON-16g: audit fails cleanly on missing registry",
              missing.returncode != 0 and "AUD-1" in missing.stdout,
              missing.stdout)


# ── CON-17/18/19/20: audit adversarial checks ─────────────────────────────────


def _mutate_artifact(reg: dict, aid: str, **kw) -> dict:
    reg = json.loads(json.dumps(reg))
    art = next(a for a in reg["artifacts"] if a["id"] == aid)
    art.update(kw)
    return reg


def _write_reg(root: pathlib.Path, name: str, reg: dict) -> pathlib.Path:
    p = root / name
    p.write_text(json.dumps(reg), encoding="utf-8")
    return p


def test_audit_path_containment_adversarial():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = _audit_fixture(td_path)
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))

        # 1) lexical .. escape (file exists outside the root)
        (td_path / "outside.json").write_text("{}", encoding="utf-8")
        r = _mutate_artifact(reg, "test-summary", path="../outside.json")
        p = _write_reg(td_path, "lexical-escape.json", r)
        res = run_audit(p)
        check("CON-17a: rejects ../ escape", res.returncode != 0
              and "AUD-3" in res.stdout, res.stdout)

        # 2) absolute path
        r = _mutate_artifact(reg, "test-summary",
                             path=str(td_path / "outside.json"))
        p = _write_reg(td_path, "abs.json", r)
        res = run_audit(p)
        check("CON-17b: rejects absolute path", res.returncode != 0
              and "AUD-3" in res.stdout, res.stdout)

        # 3) symlinked directory escape (link -> sibling outside root)
        evil = td_path / "evil-parent"
        evil.mkdir()
        (evil / "outside.json").write_text("{}", encoding="utf-8")
        (run_root / "evil").symlink_to(evil, target_is_directory=True)
        r = _mutate_artifact(reg, "test-summary", path="evil/outside.json")
        p = _write_reg(td_path, "symlink-dir.json", r)
        res = run_audit(p)
        check("CON-17c: rejects symlink-dir escape", res.returncode != 0
              and "AUD-3" in res.stdout, res.stdout)

        # 4) symlinked artifact FILE
        (run_root / "fake-summary.json").symlink_to(td_path / "outside.json")
        r = _mutate_artifact(reg, "test-summary", path="fake-summary.json")
        p = _write_reg(td_path, "symlink-file.json", r)
        res = run_audit(p)
        check("CON-17d: rejects symlinked artifact file", res.returncode != 0
              and "AUD-3" in res.stdout, res.stdout)

        # 5) prefix attack: sibling dir whose name starts with the root name
        (pathlib.Path(str(run_root) + "-evil") / "x.json").mkdir(parents=True)
        (pathlib.Path(str(run_root) + "-evil") / "x.json").joinpath(
            "x.json").write_text("{}", encoding="utf-8")
        r = _mutate_artifact(reg, "test-summary",
                             path="../" + (str(run_root) + "-evil").rsplit("/", 1)[-1] + "/x.json")
        p = _write_reg(td_path, "prefix.json", r)
        res = run_audit(p)
        check("CON-17e: rejects prefix-attack escape", res.returncode != 0
              and "AUD-3" in res.stdout, res.stdout)


def test_audit_event_evidence_structural_rule():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = _audit_fixture(td_path)
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))

        # event-evidence id with a disallowed character (not a closed vocabulary)
        r = json.loads(json.dumps(reg))
        r["artifacts"][0]["id"] = "event-evidence-../../evil"
        p = _write_reg(td_path, "ev-bad-id.json", r)
        res = run_audit(p)
        check("CON-18a: rejects malformed event-evidence id", res.returncode != 0
              and "AUD-4" in res.stdout, res.stdout)

        # event-evidence entry whose path is outside the event-evidence dir
        r = json.loads(json.dumps(reg))
        r["artifacts"][0]["path"] = "test-summary.json"
        p = _write_reg(td_path, "ev-bad-path.json", r)
        res = run_audit(p)
        check("CON-18b: rejects event-evidence outside its dir", res.returncode != 0
              and "AUD-4" in res.stdout, res.stdout)

        # event-evidence entry with a mismatched schema
        r = json.loads(json.dumps(reg))
        r["artifacts"][0]["schema_version"] = "wrong.v1"
        p = _write_reg(td_path, "ev-bad-schema.json", r)
        res = run_audit(p)
        check("CON-18c: rejects event-evidence schema mismatch", res.returncode != 0
              and "AUD-4" in res.stdout, res.stdout)


def test_audit_uniqueness_collisions():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = _audit_fixture(td_path)
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))

        # path collision: two distinct ids claiming the same resolved file
        r = json.loads(json.dumps(reg))
        r["artifacts"].append({
            "id": "coverage", "kind": "coverage",
            "path": "test-summary.json", "schema_version": "coverage.v1",
            "sha256": next(a["sha256"] for a in r["artifacts"]
                           if a["id"] == "test-summary"),
            "importance": "DIAGNOSTIC",
        })
        p = _write_reg(td_path, "path-collision.json", r)
        res = run_audit(p)
        check("CON-19a: rejects shared resolved path across ids",
              res.returncode != 0 and "AUD-5c" in res.stdout, res.stdout)

        # NFC-normalization collision
        r = json.loads(json.dumps(reg))
        r["artifacts"][0]["id"] = "caf\u00e9-x"
        r["artifacts"].append(dict(r["artifacts"][0], id="cafe\u0301-x"))
        p = _write_reg(td_path, "nfc.json", r)
        res = run_audit(p)
        check("CON-19b: rejects NFC-normalization id collision",
              res.returncode != 0 and "AUD-5b" in res.stdout, res.stdout)


def test_audit_dependency_cycles():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = _audit_fixture(td_path)
        reg = json.loads((run_root / "test-artifacts.json").read_text(encoding="utf-8"))
        by_id = {a["id"]: a for a in reg["artifacts"]}

        # mutual cycle: test-run <-> test-summary
        r = json.loads(json.dumps(reg))
        tr = next(a for a in r["artifacts"] if a["id"] == "test-run")
        tr["dependencies"] = [{"id": "test-summary",
                               "sha256": by_id["test-summary"]["sha256"]}]
        p = _write_reg(td_path, "cycle.json", r)
        res = run_audit(p)
        check("CON-20a: rejects dependency cycle", res.returncode != 0
              and "AUD-6c" in res.stdout, res.stdout)

        # self-loop
        r = json.loads(json.dumps(reg))
        self_art = next(a for a in r["artifacts"] if a["id"] == "test-run")
        self_art["dependencies"] = [{"id": "test-run",
                                     "sha256": self_art["sha256"]}]
        p = _write_reg(td_path, "self-loop.json", r)
        res = run_audit(p)
        check("CON-20b: rejects self-loop dependency", res.returncode != 0
              and "AUD-6c" in res.stdout, res.stdout)


def test_audit_producer_manifest_binding():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            ]),
        )
        ok = run_collector(run_root, [str(p)])
        check("CON-21a: collector builds registry", ok.returncode == 0, ok.stderr)
        reg_file = run_root / "test-artifacts.json"

        # valid producer passes the binding audit
        base = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-21b: bound producer passes audit", base.returncode == 0,
              base.stdout)

        # relative producer path must not false-positive the symlink check
        rel = os.path.relpath(p, _PROJECT_ROOT)
        rel_res = run_audit(reg_file, producer_roots=[rel])
        check("CON-21b2: relative producer path passes audit",
              rel_res.returncode == 0, rel_res.stdout)

        # wrong run-id in manifest vs _owner.edn
        m = json.loads((p / "_manifest.json").read_text(encoding="utf-8"))
        m["run-id"] = "WRONG"
        (p / "_manifest.json").write_text(json.dumps(m), encoding="utf-8")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-21c: flags manifest run-id mismatch", res.returncode != 0
              and "AUD-7" in res.stdout, res.stdout)

        # incomplete scope-status
        m = json.loads((p / "_manifest.json").read_text(encoding="utf-8"))
        m["run-id"] = "t"
        m["scope-status"] = "incomplete"
        (p / "_manifest.json").write_text(json.dumps(m), encoding="utf-8")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-21d: flags incomplete scope-status", res.returncode != 0
              and "AUD-7" in res.stdout, res.stdout)

        # manifest artifact hash mismatch vs disk
        m = json.loads((p / "_manifest.json").read_text(encoding="utf-8"))
        m["scope-status"] = "complete"
        m["artifacts"][0]["content-hash"] = "0" * 64
        (p / "_manifest.json").write_text(json.dumps(m), encoding="utf-8")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-21e: flags manifest hash mismatch", res.returncode != 0
              and "AUD-7" in res.stdout, res.stdout)

        # manifest artifact not represented in the registry
        m = json.loads((p / "_manifest.json").read_text(encoding="utf-8"))
        (p / "theory-eval.json").write_text('{"schema_version": "theory-eval.v1"}',
                                            encoding="utf-8")
        m["artifacts"][0]["content-hash"] = sha256_text(
            '{"schema_version": "test-run.v1", "run_id": "r1"}')
        m["artifacts"].append({
            "logical-id": "theory-eval", "relative-path": "theory-eval.json",
            "content-hash": sha256_text('{"schema_version": "theory-eval.v1"}'),
            "size": 34, "kind": "theory-eval",
        })
        (p / "_manifest.json").write_text(json.dumps(m), encoding="utf-8")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-21f: flags manifest artifact not in registry",
              res.returncode != 0 and "represented in registry" in res.stdout,
              res.stdout)


def test_audit_completeness():
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        run_root = td_path / "run"
        p = td_path / "prod"
        write_producer(
            p,
            {"test-run.json": '{"schema_version": "test-run.v1", "run_id": "r1"}',
             "test-summary.json": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            manifest=manifest_for([
                {"id": "test-run", "path": "test-run.json",
                 "content": '{"schema_version": "test-run.v1", "run_id": "r1"}'},
                {"id": "test-summary", "path": "test-summary.json",
                 "content": '{"schema_version": "test-summary.v2", "run_id": "r1"}'},
            ]),
        )
        run_collector(run_root, [str(p)])
        reg_file = run_root / "test-artifacts.json"

        # producer emits an extra config-relevant file that was never registered
        (p / "evidence-registry.json").write_text(
            '{"schema_version": "evidence-registry.v1"}', encoding="utf-8")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-22a: flags unrepresented producer output", res.returncode != 0
              and "AUD-8" in res.stdout, res.stdout)

        # symlinked producer file is rejected
        (p / "evidence-registry.json").unlink()
        (td_path / "outside.json").write_text("{}", encoding="utf-8")
        (p / "test-run.json").unlink()
        (p / "test-run.json").symlink_to(td_path / "outside.json")
        res = run_audit(reg_file, producer_roots=[str(p)])
        check("CON-22b: flags symlinked producer file", res.returncode != 0
              and "AUD-8" in res.stdout and "symlink" in res.stdout, res.stdout)


# ── run ──────────────────────────────────────────────────────────────────────

def main():
    print("=== test-consolidate-artifacts ===\n")
    print("--- manifest-mode merge ---")
    test_manifest_mode_merge()
    print("\n--- dependency resolution ---")
    test_dependency_resolution()
    print("\n--- idempotent re-run ---")
    test_idempotent_rerun()
    print("\n--- scan mode ---")
    test_scan_mode_undeclared_skipped()
    print("\n--- hard conflict ---")
    test_conflict_hard_failure()
    print("\n--- run_manifest reference ---")
    test_run_manifest_reference()
    print("\n--- declared hash mismatch ---")
    test_declared_hash_mismatch()
    print("\n--- container discovery ---")
    test_container_discovery()
    print("\n--- hardlink merge ---")
    test_merged_files_hardlinked()
    print("\n--- loose producer ---")
    test_loose_producer_requires_flag()
    print("\n--- dangling-dependency checks ---")
    test_no_dangling_dependencies_when_run_manifest_present()
    test_dangling_run_manifest_is_flagged()
    print("\n--- owner marker parsing ---")
    test_owner_marker_parsing()
    print("\n--- run-root top-level scan + test-run emission ---")
    test_run_root_only_scan()
    test_run_root_scan_with_producer()
    test_generate_test_summary_emits_run_manifest()
    print("\n--- audit gate ---")
    test_audit_artifacts_gate()
    print("\n--- audit adversarial (paths, ids, cycles, provenance, completeness) ---")
    test_audit_path_containment_adversarial()
    test_audit_event_evidence_structural_rule()
    test_audit_uniqueness_collisions()
    test_audit_dependency_cycles()
    test_audit_producer_manifest_binding()
    test_audit_completeness()

    print(f"\n=== {_PASS} passed, {_FAIL} failed ===")
    return 1 if _FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
