#!/usr/bin/env python3
"""Export screenshots of Clerk notebooks via headless Chromium (Playwright).

Usage:
  python3 scripts/export_notebook_shots.py [options]

Renders each notebook against a live Clerk server (default http://localhost:7777)
and writes PNGs plus a contact sheet and run metadata into --out (default shots/).

The output is a self-contained static site that can be copied to any static
host (GitHub Pages, S3, nginx, …) and served with NO Java: the Clerk server is
only needed to *generate* the shots, never to view them.

Default layout (run-archived, keeps every export):
  <out>/index.html                 # run index: every export, newest first
  <out>/latest.html                # redirect to the newest export
  <out>/runs/<run-id>/index.html   # contact sheet for that export
  <out>/runs/<run-id>/run-meta.json
  <out>/runs/<run-id>/<name>.png                # single-shot notebook
  <out>/runs/<run-id>/<name>/full.png           # multi-shot notebook
  <out>/runs/<run-id>/<name>/01-<slug>.png

--run-id labels the export (default: UTC timestamp); re-using one replaces that
run. --flat keeps the legacy layout (directly under <out>, no runs/<id> nesting)
used by the CI render gate. --index-only rebuilds <out>/index.html from existing
runs without rendering.

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
    collect_runs,
    default_run_id,
    run_index_html,
    sanitize_run_id,
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
    goto_retries = max(1, int(settings.get("goto-retries", 2)))

    ctx = browser.new_context(
        viewport={"width": viewport.get("width", 1440), "height": viewport.get("height", 900)},
        device_scale_factor=dpr,
    )
    page = ctx.new_page()
    notes = []
    try:
        # Heavy interactive notebooks are lazily evaluated by the Clerk server on
        # first request, which can exceed the nav timeout once. Retry with a fresh
        # page so the same URL gets served from the warm cache.
        for attempt in range(goto_retries):
            try:
                page.goto(url, wait_until="commit", timeout=timeout_ms)
                break
            except Exception:  # noqa: BLE001  (playwright.TimeoutError and friends)
                if attempt + 1 >= goto_retries:
                    raise
                time.sleep(5)
                try:
                    page.close()
                except Exception:  # noqa: BLE001
                    pass
                page = ctx.new_page()
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


def write_contact_sheet(out_dir, entries, parent_href=None):
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
    if parent_href:
        html.append("<p style='margin-bottom:8px'><a href='%s'>← all exports</a></p>" % parent_href)
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


def write_run_index(site_dir, thumbnail_limit=0):
    """Regenerate <site>/index.html (+latest.html) from existing runs/."""
    runs = collect_runs(site_dir)
    html = run_index_html(site_dir, runs, thumbnail_limit=thumbnail_limit)
    with open(os.path.join(site_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)
    if runs:
        newest = runs[0]["id"]
        latest = (
            "<!doctype html><html><head><meta charset='utf-8'>"
            "<meta http-equiv='refresh' content='0;url=runs/%s/index.html'>"
            "<title>Latest export</title></head><body>"
            "<a href='runs/%s/index.html'>latest export →</a></body></html>"
            % (newest, newest)
        )
        with open(os.path.join(site_dir, "latest.html"), "w", encoding="utf-8") as f:
            f.write(latest)
    return runs


def latest_run_meta_path(site_dir, with_failures_only=False):
    """Path of the newest run's run-meta.json, or None.

    with_failures_only=True prefers the newest run whose meta lists failures
    (the run a retry should complete); falls back to the newest run overall.
    """
    runs_root = os.path.join(site_dir, "runs")
    if not os.path.isdir(runs_root):
        return None
    run_ids = sorted((d for d in os.listdir(runs_root)
                      if os.path.isdir(os.path.join(runs_root, d))), reverse=True)
    fallback = None
    for rid in run_ids:
        meta = os.path.join(runs_root, rid, "run-meta.json")
        if not os.path.exists(meta):
            continue
        if fallback is None:
            fallback = meta
        if with_failures_only:
            try:
                with open(meta, encoding="utf-8") as f:
                    if json.load(f).get("failed"):
                        return meta
            except (OSError, ValueError):
                pass
        else:
            return meta
    return fallback


def _scan_run_files(run_dir, name):
    """Recover a notebook's final images in a run dir (flat or dir layout).

    Used when completing an older run whose run-meta.json predates the "files"
    key. Mirrors plan_output: single-shot -> <name>.<ext>, multi -> <name>/….
    """
    for ext in (".png", ".jpg", ".jpeg", ".webp"):
        flat = os.path.join(run_dir, name + ext)
        if os.path.exists(flat):
            return [name + ext]
    sub = os.path.join(run_dir, name)
    if os.path.isdir(sub):
        return sorted(os.path.relpath(os.path.join(root, fn), run_dir)
                      for root, _dirs, files in os.walk(sub)
                      for fn in files if fn.lower().endswith((".png", ".jpg", ".jpeg", ".webp")))
    return []


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
    ap.add_argument("--out", default="shots", help="output dir (default: shots)")
    ap.add_argument("--base-url", default="http://localhost:7777", help="Clerk server base URL")
    ap.add_argument("--modes", default=None, help="override modes: full,sections")
    ap.add_argument("--only-failures", action="store_true", help="rerun only previously failed notebooks")
    ap.add_argument("--headless", action="store_true", default=True, help="headless (default on)")
    ap.add_argument("--run-id", default=None, help="label for this export (default: UTC timestamp; reuse to replace a run)")
    ap.add_argument("--flat", action="store_true", help="legacy layout: write directly under --out, no runs/<id> nesting")
    ap.add_argument("--index-only", action="store_true", help="rebuild --out/index.html from existing runs; no rendering")
    ap.add_argument("--thumbnails", type=int, default=0, metavar="N",
                    help="embed up to N preview images per run in the run index (default: 0)")
    args = ap.parse_args(argv)

    cfg = load_config(args.config)
    out_dir = args.out or cfg.get("output", "shots")

    if args.index_only:
        os.makedirs(out_dir, exist_ok=True)
        runs = write_run_index(out_dir, args.thumbnails)
        print("%d run(s) indexed -> %s/index.html" % (len(runs), out_dir))
        return 0

    # Resolve the target run dir. --only-failures completes the newest run that
    # still has failures (unless --run-id pins one), instead of a fresh run.
    run_id = sanitize_run_id(args.run_id) if args.run_id else default_run_id()
    completing = None  # prior run-meta.json dict when completing an existing run
    if args.flat:
        run_dir = out_dir
    else:
        run_dir = os.path.join(out_dir, "runs", run_id)
    if args.only_failures:
        meta_src = None
        if args.flat:
            meta_src = os.path.join(out_dir, "run-meta.json") if os.path.exists(os.path.join(out_dir, "run-meta.json")) else None
        else:
            meta_src = latest_run_meta_path(out_dir, with_failures_only=True)
            if meta_src and not args.run_id:
                run_id = os.path.basename(os.path.dirname(meta_src))
                run_dir = os.path.join(out_dir, "runs", run_id)
        if meta_src and os.path.exists(meta_src):
            with open(meta_src, encoding="utf-8") as f:
                completing = json.load(f)
    os.makedirs(run_dir, exist_ok=True)
    run_meta_path = os.path.join(run_dir, "run-meta.json")

    prev_failures = completing.get("failed", []) if completing else []

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
                out_subdir = os.path.join(run_dir, name)
                # clear this notebook's prior outputs in this run dir (harmless on
                # a fresh run-id; matters when re-running into the same one)
                _clean_prior(run_dir, name, out_subdir)
                print("[%s] rendering %s" % ("shot", name), flush=True)
                try:
                    images, notes = capture_notebook(browser, url, settings, out_subdir)
                    # final layout: flatten single-shot notebooks
                    dests = plan_output(run_dir, name, images)
                    final_rels = []
                    for rel, dest in zip(images, dests):
                        src = os.path.join(out_subdir, rel)
                        if os.path.abspath(src) != os.path.abspath(dest):
                            os.makedirs(os.path.dirname(dest), exist_ok=True)
                            shutil.move(src, dest)
                        final_rels.append(os.path.relpath(dest, run_dir))
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

    # contact sheet for THIS run (final relative paths within the run dir).
    # When completing an existing run (--only-failures), carry over the ok
    # notebooks from the prior run-meta so the sheet still shows them. Older
    # metas lack the "files" key, so fall back to scanning the run dir.
    prior_files = {}
    if completing:
        prior_files = completing.get("files") or {}
        for name in completing.get("ok") or []:
            if name in results:
                continue
            files = prior_files.get(name) or _scan_run_files(run_dir, name)
            if files:
                results[name] = {"ok": True, "files": files, "error": None, "notes": []}
    entries = sorted((name, res["files"]) for name, res in results.items() if res["ok"])
    write_contact_sheet(run_dir, entries, parent_href="../index.html" if not args.flat else None)

    meta = {
        "base_url": args.base_url,
        "out": run_dir,
        "run_id": run_id,
        "settings": cfg,
        "files": {n: r["files"] for n, r in results.items() if r["ok"]},
        "failed": sorted(n for n, r in results.items() if not r["ok"]),
        "ok": sorted(n for n, r in results.items() if r["ok"]),
    }
    with open(run_meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True)

    # run index over ALL runs (unless flat/legacy layout)
    if not args.flat:
        write_run_index(out_dir, args.thumbnails)

    failed = meta["failed"]
    if failed:
        print("\nFAILED (%d): %s" % (len(failed), ", ".join(failed)), file=sys.stderr)
        return 1
    print("\nOK: %d notebooks -> %s" % (len(meta["ok"]), run_dir))
    if not args.flat:
        print("    site index: %s/index.html (latest: %s/latest.html)" % (out_dir, out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
