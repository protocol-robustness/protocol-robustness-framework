#!/usr/bin/env python3
"""Export screenshots of Clerk notebooks via headless Chromium (Playwright).

Usage:
  python3 scripts/export_notebook_shots.py [options]

Renders each notebook against a live Clerk server (default http://localhost:7777)
and writes PNGs plus a contact sheet and run metadata to --out (default shots/).

Layout (per the repo convention):
  - Single-shot notebook   -> <out>/<name>.png
  - Multi-shot notebook    -> <out>/<name>/full.png, <out>/<name>/01-<slug>.png, …

Exit code is non-zero if any notebook failed to render.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time

from notebook_shots_util import (
    load_config,
    notebook_name,
    plan_output,
    section_filename,
    RENDER_READY_JS,
    COLLECT_HEADINGS_JS,
    SCROLL_TO_JS,
    COLLECT_SECTIONS_JS,
    GET_HEADING_TOP_JS,
    BODY_BG_JS,
)

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = os.path.join(os.path.dirname(HERE), "data", "notebook-shots.edn")
NOTEBOOKS_DIR = os.path.join(os.path.dirname(HERE), "notebooks")

# Playwright / Chromium cap on viewport height (CSS px). A section taller than
# this cannot be captured in one shot without stitching; we clip to this height
# and record a note.
MAX_VIEWPORT_H = 16000


# ---------------------------------------------------------------------------
# Notebook discovery
# ---------------------------------------------------------------------------


def scan_notebooks(exclude=()):
    """Top-level notebooks/*.clj (no subdirs), minus excluded names."""
    names = []
    if os.path.isdir(NOTEBOOKS_DIR):
        for fn in sorted(os.listdir(NOTEBOOKS_DIR)):
            path = os.path.join(NOTEBOOKS_DIR, fn)
            if os.path.isfile(path) and fn.endswith(".clj"):
                name = notebook_name(fn)
                if name not in exclude:
                    names.append(name)
    return names


def resolve_notebooks(cfg, cli_names, only_failures, failures):
    exclude = set(cfg.get("exclude", []))
    configured = cfg.get("notebooks") or []
    configured_names = {notebook_name(e.get("path", "")) for e in configured}

    if only_failures:
        names = [n for n in failures if os.path.exists(os.path.join(NOTEBOOKS_DIR, n + ".clj"))]
    elif cli_names:
        names = list(cli_names)
    elif configured_names:
        names = sorted(configured_names)
    else:
        names = scan_notebooks(exclude)

    # drop names that don't correspond to a real notebook file
    names = [n for n in names if os.path.exists(os.path.join(NOTEBOOKS_DIR, n + ".clj"))]
    return names


def settings_for(cfg, name):
    """Per-notebook settings: default merged with any config override."""
    merged = dict(cfg.get("default") or {})
    for e in cfg.get("notebooks") or []:
        if notebook_name(e.get("path", "")) == name:
            merged.update(e)
            break
    return merged


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def wait_for_render(page, settle_ms, timeout_ms):
    import time as _t

    deadline = _t.time() + timeout_ms / 1000.0
    page.wait_for_selector(".notebook-viewer", timeout=min(timeout_ms, 30000))
    prev = -1
    while _t.time() < deadline:
        try:
            cur = page.evaluate(RENDER_READY_JS)
            ready, count = cur.get("ready"), cur.get("count")
        except Exception:
            ready, count = False, prev
        if ready and count == prev and count > 0:
            break
        prev = count
        _t.sleep(0.3)
    _t.sleep(settle_ms / 1000.0)
    try:
        page.evaluate("() => document.fonts.ready.then(()=>true)")
        page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:
        pass


def _capture_full_sections(page, out_subdir, section_level, notes):
    """Capture each section's FULL extent (heading → next heading), not just the
    first viewport. Playwright clips to the viewport, so we temporarily resize the
    viewport to the section height, scroll the heading to the top, then clip
    y=0..height — capturing the whole section in one shot. A section taller than
    MAX_VIEWPORT_H is clipped with a note (full stitching would need PIL)."""
    vw = (page.viewport_size or {}).get("width", 1440)
    sections = page.evaluate(COLLECT_SECTIONS_JS, section_level)
    images = []
    for i, s in enumerate(sections, 1):
        rel = section_filename(i, s["text"], s["id"])
        height = max(int(s["bottom"] - s["top"]), 60)
        cap = min(height, MAX_VIEWPORT_H)
        page.set_viewport_size({"width": vw, "height": cap})
        page.wait_for_timeout(150)
        # reset scroll so absolute heading offsets are exact after the resize
        page.evaluate("() => window.scrollTo(0, 0)")
        page.wait_for_timeout(50)
        fresh_top = page.evaluate(GET_HEADING_TOP_JS, [section_level, i - 1])
        if fresh_top is not None:
            page.evaluate("(y) => window.scrollTo(0, y)", fresh_top)
            page.wait_for_timeout(150)
        page.screenshot(path=os.path.join(out_subdir, rel),
                        clip={"x": 0, "y": 0, "width": vw, "height": cap})
        images.append(rel)
        if height > MAX_VIEWPORT_H:
            notes.append("%s: section > %spx; clipped (PIL stitch needed for full)" % (rel, MAX_VIEWPORT_H))
    if not sections:
        notes.append("sections mode produced no headings (section-level=%s)" % section_level)
    return images, notes


def capture_notebook(browser, url, settings, out_subdir):
    """Capture one notebook; returns (images, notes). images = [(rel_path, abs_path)]."""
    viewport = settings.get("viewport") or {}
    dpr = settings.get("device-scale-factor", 2)
    modes = settings.get("modes") or ["full"]
    section_level = int(settings.get("section-level", 2))
    settle_ms = int(settings.get("settle-ms", 1500))
    timeout_ms = int(settings.get("timeout-ms", 60000))

    ctx = browser.new_context(
        viewport={"width": viewport.get("width", 1440), "height": viewport.get("height", 900)},
        device_scale_factor=dpr,
    )
    page = ctx.new_page()
    notes = []
    try:
        page.goto(url, wait_until="commit", timeout=timeout_ms)
        wait_for_render(page, settle_ms, timeout_ms)

        bg = page.evaluate(BODY_BG_JS)
        if bg and re.match(r"rgba?\(\s*2[0-4][0-9]", bg) is None and "dark" not in bg.lower():
            notes.append("light-background: %s (expected dark mode)" % bg)

        os.makedirs(out_subdir, exist_ok=True)
        images = []

        if "full" in modes:
            rel = "full.png"
            page.screenshot(path=os.path.join(out_subdir, rel), full_page=True)
            images.append(rel)

        if "sections" in modes:
            if settings.get("section-viewport"):
                headings = page.evaluate(COLLECT_HEADINGS_JS, section_level)
                for i, h in enumerate(headings, 1):
                    page.evaluate(SCROLL_TO_JS, [section_level, i - 1])
                    page.wait_for_timeout(150)
                    rel = section_filename(i, h["text"], h["id"])
                    page.screenshot(path=os.path.join(out_subdir, rel))
                    images.append(rel)
                if not headings:
                    notes.append("sections mode produced no headings (section-level=%s)" % section_level)
            else:
                images, section_notes = _capture_full_sections(
                    page, out_subdir, section_level, notes)
                notes.extend(section_notes)
        return images, notes
    finally:
        ctx.close()


# ---------------------------------------------------------------------------
# Contact sheet + metadata
# ---------------------------------------------------------------------------


def write_contact_sheet(out_dir, entries):
    """entries: list of (name, [rel_final_paths...]) relative to out_dir."""
    html = [
        "<!doctype html><html><head><meta charset='utf-8'>",
        "<title>Notebook Screenshots</title>",
        "<style>body{font-family:system-ui,sans-serif;margin:24px;background:#0f172a;color:#e2e8f0}",
        "h1{font-size:20px}h2{font-size:15px;margin:28px 0 10px;color:#7adddc}",
        ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}",
        "a{color:#94a3b8;text-decoration:none;font-size:12px}",
        ".tile{background:#1e293b;border:1px solid #334155;border-radius:8px;padding:10px;overflow:hidden}",
        ".tile img{width:100%;border-radius:4px;border:1px solid #334155;display:block}",
        ".tile .name{margin:6px 0 4px;font-weight:700;color:#e2e8f0;font-size:13px}",
        "</style></head><body><h1>Notebook screenshots</h1>",
    ]
    for name, rels in entries:
        html.append("<h2>%s (%d)</h2><div class='grid'>" % (name, len(rels)))
        for rel in rels:
            html.append(
                "<div class='tile'><a href='%s'><img loading='lazy' src='%s'></a>"
                "<div class='name'>%s</div></div>"
                % (rel, rel, os.path.basename(rel))
            )
        html.append("</div>")
    html.append("</body></html>")
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write("\n".join(html))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def _clean_prior(out_dir, name, notebook_dir):
    """Remove prior outputs for a notebook (both flat <name>.<ext> and dir forms)."""
    if os.path.isdir(notebook_dir):
        shutil.rmtree(notebook_dir, ignore_errors=True)
    for ext in (".png", ".jpg", ".jpeg", ".webp"):
        flat = os.path.join(out_dir, name + ext)
        if os.path.exists(flat):
            os.remove(flat)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default=DEFAULT_CONFIG, help="EDN config (default: data/notebook-shots.edn)")
    ap.add_argument("--notebook", action="append", dest="notebooks", help="notebook name(s) to shoot")
    ap.add_argument("--out", default="shots", help="output dir")
    ap.add_argument("--base-url", default="http://localhost:7777", help="Clerk server base URL")
    ap.add_argument("--modes", default=None, help="override modes: full,sections")
    ap.add_argument("--only-failures", action="store_true", help="rerun only previously failed notebooks")
    ap.add_argument("--headless", action="store_true", default=True, help="headless (default on)")
    args = ap.parse_args(argv)

    cfg = load_config(args.config)
    out_dir = args.out or cfg.get("output", "shots")
    os.makedirs(out_dir, exist_ok=True)

    run_meta_path = os.path.join(out_dir, "run-meta.json")
    prev_failures = []
    if args.only_failures and os.path.exists(run_meta_path):
        with open(run_meta_path, encoding="utf-8") as f:
            prev_failures = json.load(f).get("failed", [])

    names = resolve_notebooks(cfg, args.notebooks, args.only_failures, prev_failures)
    if not names:
        print("No notebooks to process.", file=sys.stderr)
        return 2

    forced_modes = [m.strip() for m in (args.modes or "").split(",") if m.strip()]

    from playwright.sync_api import sync_playwright

    results = {}  # name -> {"ok": bool, "images": [...], "error": str, "notes": [...]}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=args.headless)
        try:
            for name in names:
                settings = settings_for(cfg, name)
                if forced_modes:
                    settings = dict(settings, modes=forced_modes)
                url = "%s/notebooks/%s" % (args.base_url.rstrip("/"), name)
                out_subdir = os.path.join(out_dir, name)
                # clear this notebook's prior outputs so re-runs don't accumulate
                _clean_prior(out_dir, name, out_subdir)
                print("[%s] rendering %s" % ("shot", name), flush=True)
                try:
                    images, notes = capture_notebook(browser, url, settings, out_subdir)
                    # final layout: flatten single-shot notebooks
                    dests = plan_output(out_dir, name, images)
                    final_rels = []
                    for rel, dest in zip(images, dests):
                        src = os.path.join(out_subdir, rel)
                        if os.path.abspath(src) != os.path.abspath(dest):
                            os.makedirs(os.path.dirname(dest), exist_ok=True)
                            shutil.move(src, dest)
                        final_rels.append(os.path.relpath(dest, out_dir))
                    if len(images) == 1:
                        try:
                            os.rmdir(out_subdir)
                        except OSError:
                            pass
                    results[name] = {"ok": True, "files": final_rels, "error": None, "notes": notes}
                    print("    -> %s" % ", ".join(dests), flush=True)
                except Exception as e:  # noqa: BLE001
                    results[name] = {"ok": False, "images": [], "error": str(e), "notes": []}
                    print("    !! %s" % e, flush=True)
        finally:
            browser.close()

    # contact sheet over all produced images (final relative paths)
    entries = sorted((name, res["files"]) for name, res in results.items() if res["ok"])
    write_contact_sheet(out_dir, entries)

    meta = {
        "base_url": args.base_url,
        "out": out_dir,
        "settings": cfg,
        "failed": sorted(n for n, r in results.items() if not r["ok"]),
        "ok": sorted(n for n, r in results.items() if r["ok"]),
    }
    with open(run_meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True)

    failed = meta["failed"]
    if failed:
        print("\nFAILED (%d): %s" % (len(failed), ", ".join(failed)), file=sys.stderr)
        return 1
    print("\nOK: %d notebooks -> %s/index.html" % (len(meta["ok"]), out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
