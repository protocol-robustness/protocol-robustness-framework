#!/usr/bin/env python3
"""Self-tests for the pure parts of the notebook screenshot exporter.

Runs without a browser or server. Usage: python3 scripts/test_notebook_shots.py
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from notebook_shots_util import (  # noqa: E402
    parse_edn,
    load_config,
    slugify,
    plan_output,
    section_filename,
    notebook_name,
    default_run_id,
    sanitize_run_id,
    collect_runs,
    run_index_html,
)

FAILED = []


def check(name, cond, msg=""):
    if not cond:
        FAILED.append(name)
        print("FAIL %s: %s" % (name, msg))
    else:
        print("ok   %s" % name)


def test_edn():
    s = """
; comment
{:output "shots"
 :viewport {:width 1440 :height 900}
 :device-scale-factor 2
 :default {:modes [:full]}
 :notebooks [{:path "notebooks/report.clj" :modes [:sections]}]}
"""
    v = parse_edn(s)
    check("edn-parse", isinstance(v, dict))
    check("edn-string", v[":output"] == "shots")
    check("edn-int", v[":device-scale-factor"] == 2)
    check("edn-nested", v[":viewport"][":width"] == 1440)
    check("edn-keyword-in-vec", v[":default"][":modes"] == [":full"])

    cfg = load_config(os.path.join(os.path.dirname(__file__), "..", "data", "notebook-shots.edn"))
    check("load-config-output", cfg.get("output") == "shots")
    check("load-config-defaults", cfg.get("default") == {"modes": ["full"]})
    check("load-config-exclude", "_template" in cfg.get("exclude", []))


def test_naming():
    check("slugify", slugify("Adverse Escrow & Resolution (s04)") == "adverse-escrow-resolution-s04")
    check("slugify-ascii-only", slugify("Håkon — Winterfell") == "hakon-winterfell")
    check("slugify-default", slugify("   ") == "section")
    check("section-filename", section_filename(3, "Resolver Slash", "x") == "03-resolver-slash.png")
    check("notebook-name", notebook_name("notebooks/demo_short_circuit.clj") == "demo_short_circuit")


def test_layout():
    single = plan_output("shots", "demo_x", ["full.png"])
    multi = plan_output("shots", "demo_x", ["full.png", "01-a.png"])
    check("single-flattened", single == ["shots/demo_x.png"])
    check("multi-keeps-dir", multi == ["shots/demo_x/full.png", "shots/demo_x/01-a.png"])


def test_roundtrip_dirs():
    # verify the move logic leaves a flat file for one shot and a dir for many
    import shutil
    from export_notebook_shots import plan_output, _clean_prior
    with tempfile.TemporaryDirectory() as d:
        _clean_prior(d, "demo_x", os.path.join(d, "demo_x"))
        os.makedirs(os.path.join(d, "demo_x"), exist_ok=True)
        with open(os.path.join(d, "demo_x", "full.png"), "w") as f:
            f.write("x")
        dest = plan_output(d, "demo_x", ["full.png"])[0]
        shutil.move(os.path.join(d, "demo_x", "full.png"), dest)
        try:
            os.rmdir(os.path.join(d, "demo_x"))  # mirrors driver flatten step
        except OSError:
            pass
        check("roundtrip-flat", os.path.exists(os.path.join(d, "demo_x.png")))
        check("roundtrip-dir-gone", not os.path.isdir(os.path.join(d, "demo_x")))


def test_run_id():
    rid = default_run_id()
    check("run-id-format", len(rid) == 16 and rid.endswith("Z") and "T" in rid, rid)
    check("run-id-sortable", default_run_id(0) < default_run_id(1000000))
    check("run-id-sanitize", sanitize_run_id("PR-123 / x") == "PR-123-x")
    check("run-id-sanitize-empty", sanitize_run_id("   ") == "run")


def test_run_index():
    with tempfile.TemporaryDirectory() as d:
        # run 2 newest, run 1 oldest; run 2 has a failed notebook
        for rid, ok, failed in (("20260101T000000Z", ["a"], []),
                                ("20260102T000000Z", ["b"], ["c"])):
            rdir = os.path.join(d, "runs", rid)
            os.makedirs(os.path.join(rdir, "b"), exist_ok=True)
            with open(os.path.join(rdir, "run-meta.json"), "w") as f:
                import json
                json.dump({"ok": ok, "failed": failed}, f)
            with open(os.path.join(rdir, "b", "01-x.png"), "w") as f:
                f.write("img")
            with open(os.path.join(rdir, "run-meta.json"), "a") as f:
                f.write("")
        runs = collect_runs(d)
        check("collect-runs-newest-first",
              [r["id"] for r in runs] == ["20260102T000000Z", "20260101T000000Z"])
        check("collect-runs-counts",
              runs[0]["images"] == 1 and runs[0]["failed"] == ["c"] and runs[0]["ok"] == ["b"])
        html = run_index_html(d, runs)
        check("run-index-links-both",
              html.count("20260102T000000Z/index.html") == 1
              and html.count("20260101T000000Z/index.html") == 1)
        check("run-index-no-thumbs-by-default", "<img" not in html)
        html_thumbs = run_index_html(d, runs, thumbnail_limit=3)
        check("run-index-thumbs", "src='20260102T000000Z/b/01-x.png'" in html_thumbs)


def test_run_completion():
    # --only-failures should target the newest run WITH failures, not a fresh run
    import json as _json
    from export_notebook_shots import latest_run_meta_path, _scan_run_files, write_contact_sheet
    with tempfile.TemporaryDirectory() as d:
        runs_root = os.path.join(d, "runs")
        for rid, failed in (("20260101T000000Z", []),
                            ("20260102T000000Z", ["workbench"])):
            rdir = os.path.join(runs_root, rid)
            os.makedirs(rdir, exist_ok=True)
            with open(os.path.join(rdir, "run-meta.json"), "w") as f:
                _json.dump({"ok": ["alpha"], "failed": failed}, f)
        # newest run has no failures -> retry should pick the newer run with failures
        check("retry-targets-failing-run",
              latest_run_meta_path(d, with_failures_only=True)
              == os.path.join(runs_root, "20260102T000000Z", "run-meta.json"))
        check("retry-fallback-newest",
              latest_run_meta_path(d)
              == os.path.join(runs_root, "20260102T000000Z", "run-meta.json"))

        # scan recovers files for old metas without the "files" key
        rdir = os.path.join(runs_root, "20260102T000000Z")
        os.makedirs(os.path.join(rdir, "alpha"), exist_ok=True)
        with open(os.path.join(rdir, "alpha", "01-x.png"), "w") as f:
            f.write("img")
        with open(os.path.join(rdir, "workbench.png"), "w") as f:
            f.write("img")
        check("scan-flat-file", _scan_run_files(rdir, "workbench") == ["workbench.png"])
        check("scan-dir-files", _scan_run_files(rdir, "alpha") == ["alpha/01-x.png"])
        check("scan-missing", _scan_run_files(rdir, "nope") == [])

        # contact sheet with a parent link includes it once
        write_contact_sheet(rdir, [("alpha", ["alpha/01-x.png"])], parent_href="../index.html")
        html = open(os.path.join(rdir, "index.html")).read()
        check("contact-parent-link", "../index.html" in html and html.count("alpha/01-x.png") == 2)


def test_guided_site():
    # homepage + explore + catalogue + notebook pages from a registry + runs
    import json as _json
    from notebook_shots_util import (
        load_registry, theme_of, kind_of, level_of, featured_entries,
        collect_notebook_runs, write_guided_site, homepage_html,
        catalogue_html, notebook_page_html,
    )
    registry = load_registry(os.path.join(os.path.dirname(__file__), "..", "data", "notebooks.edn"))
    check("guided-themes-present", len(registry["themes"]) == 5)
    check("guided-featured-6", len(featured_entries(registry)) == 6)

    with tempfile.TemporaryDirectory() as d:
        # fabricate one run with two notebooks
        run_dir = os.path.join(d, "runs", "20260101T000000Z")
        os.makedirs(os.path.join(run_dir, "demo_not_admitted"), exist_ok=True)
        os.makedirs(os.path.join(run_dir, "report"), exist_ok=True)
        for fn in ("01-a.png", "02-b.png"):
            open(os.path.join(run_dir, "demo_not_admitted", fn), "w").write("img")
        open(os.path.join(run_dir, "report", "01-a.png"), "w").write("img")
        with open(os.path.join(run_dir, "run-meta.json"), "w") as f:
            _json.dump({"run_id": "20260101T000000Z", "ok": ["demo_not_admitted", "report"],
                        "failed": [],
                        "files": {"demo_not_admitted": ["demo_not_admitted/01-a.png",
                                                        "demo_not_admitted/02-b.png"],
                                  "report": ["report/01-a.png"]}}, f)
        with open(os.path.join(run_dir, "index.html"), "w") as f:
            f.write("<html>run contact sheet</html>")

        notebook_runs = collect_notebook_runs(d)
        check("collect-nb-runs", set(notebook_runs) == {"demo_not_admitted", "report"})
        check("collect-nb-newest", notebook_runs["report"][0][0] == "20260101T000000Z")

        written = write_guided_site(d, registry, thumbnail_limit=0)
        check("guided-writes-home", "index.html" in written)
        check("guided-writes-explore", "explore/failures-attacks.html" in written)
        check("guided-writes-catalogue", "notebooks/index.html" in written)
        check("guided-writes-nb-pages",
              "notebooks/demo-not-admitted/index.html" in written
              and "notebooks/report/index.html" in written)
        check("guided-assets-copied",
              os.path.exists(os.path.join(d, "assets", "notebooks", "demo-not-admitted", "01-a.png")))
        check("guided-assets-count", len(os.listdir(os.path.join(d, "assets", "notebooks", "demo-not-admitted"))) == 2)

        home = open(os.path.join(d, "index.html")).read()
        check("guided-home-hero", "Protocol Robustness Framework" in home)
        check("guided-home-start-here", "Start here" in home and "demo-not-admitted" in home)
        cat = open(os.path.join(d, "notebooks", "index.html")).read()
        check("guided-catalogue-filters", "data-f='theme:" in cat)
        nb = open(os.path.join(d, "notebooks", "demo-not-admitted", "index.html")).read()
        check("guided-nb-sections", "Sections (2)" in nb)
        check("guided-nb-run-history", "Run history" in nb and "20260101T000000Z" in nb)
        # deeper-id present in registry but not exported -> no dead link
        check("guided-no-dead-deeper",
              "../not-admitted/index.html" not in nb or not os.path.exists(
                  os.path.join(d, "notebooks", "not-admitted")))
        # no page has broken hrefs or image srcs (srcs must resolve relative to
        # the page, including from deep notebook/explore/catalogue locations)
        import re as _re
        for hf in ("index.html",
                   "explore/failures-attacks.html",
                   "notebooks/index.html",
                   "notebooks/demo-not-admitted/index.html",
                   "notebooks/report/index.html"):
            p = os.path.join(d, hf)
            text = open(p).read()
            base = os.path.dirname(p)
            for attr in ("href", "src"):
                bad = [h for h in _re.findall(attr + r"='([^']+)'", text)
                       if not h.startswith(("http", "#", "data:"))
                       and not os.path.exists(os.path.normpath(os.path.join(base, h)))]
                check("guided-no-broken-%s-%s" % (hf.replace("/", "_"), attr), not bad, str(bad))

        # whole card is one link (catalogue + homepage start-here + explore)
        cat = open(os.path.join(d, "notebooks", "index.html")).read()
        card_anchors = _re.findall(r"<a class='card'[^>]*href='[^']+'>", cat)
        check("guided-cards-are-links", len(card_anchors) >= 2, str(len(card_anchors)))
        check("guided-no-nested-preview-links",
              not _re.search(r"<a [^>]*><img class='preview'[^>]*></a>", cat))
        nb = open(os.path.join(d, "notebooks", "demo-not-admitted", "index.html")).read()
        check("guided-nb-img-src-relative",
              "src='../../assets/notebooks/demo-not-admitted/01-a.png'" in nb)


if __name__ == "__main__":
    test_edn()
    test_naming()
    test_layout()
    test_roundtrip_dirs()
    test_run_id()
    test_run_index()
    test_run_completion()
    test_guided_site()
    if FAILED:
        print("\n%d test(s) failed: %s" % (len(FAILED), ", ".join(FAILED)))
        sys.exit(1)
    print("\nAll notebook-shots unit tests passed.")
