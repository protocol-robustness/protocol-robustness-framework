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


if __name__ == "__main__":
    test_edn()
    test_naming()
    test_layout()
    test_roundtrip_dirs()
    if FAILED:
        print("\n%d test(s) failed: %s" % (len(FAILED), ", ".join(FAILED)))
        sys.exit(1)
    print("\nAll notebook-shots unit tests passed.")
