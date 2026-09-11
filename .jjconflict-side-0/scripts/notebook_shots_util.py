"""Shared helpers for notebook screenshot export.

Kept importable without Playwright so the pure functions (EDN config parsing,
slugify, output path planning) can be unit-tested in isolation.
"""
from __future__ import annotations

import html
import json
import os
import re
import shutil
import unicodedata


def _tokenize(s: str) -> list:
    toks = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in " \t\r\n,":
            i += 1
        elif c == ";":
            j = s.find("\n", i)
            i = n if j == -1 else j + 1
        elif c in "()[]{}":
            toks.append(c)
            i += 1
        elif c == '"':
            j, buf = i + 1, []
            while j < n:
                if s[j] == "\\" and j + 1 < n:
                    buf.append(s[j + 1])
                    j += 2
                elif s[j] == '"':
                    j += 1
                    break
                else:
                    buf.append(s[j])
                    j += 1
            toks.append("STR:" + "".join(buf))
            i = j
        else:
            j = i
            while j < n and s[j] not in " \t\r\n,()[]{};":
                j += 1
            toks.append(s[i:j])
            i = j
    return toks


class _Parser:
    def __init__(self, toks):
        self.toks = toks
        self.i = 0

    def peek(self):
        return self.toks[self.i] if self.i < len(self.toks) else None

    def next(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def parse(self):
        t = self.peek()
        if t is None:
            raise ValueError("unexpected end of input")
        if t == "(":
            self.next()
            return self._seq(")")
        if t == "[":
            self.next()
            return self._seq("]")
        if t == "{":
            self.next()
            return self._map()
        return self._atom()

    def _seq(self, close):
        out = []
        while True:
            t = self.peek()
            if t == close:
                self.next()
                return out
            if t is None:
                raise ValueError("missing closing " + close)
            out.append(self.parse())

    def _map(self):
        out = {}
        while True:
            t = self.peek()
            if t == "}":
                self.next()
                return out
            if t is None:
                raise ValueError("missing closing }")
            k = self.parse()
            v = self.parse()
            out[k] = v

    def _atom(self):
        t = self.next()
        if t == "nil":
            return None
        if t == "true":
            return True
        if t == "false":
            return False
        if t.startswith("STR:"):
            return t[4:]
        if re.match(r"^-?\d+$", t):
            return int(t)
        if re.match(r"^-?\d*\.\d+$", t):
            return float(t)
        return t


def parse_edn(text: str):
    toks = _tokenize(text)
    p = _Parser(toks)
    if not toks:
        return None
    v = p.parse()
    if p.i != len(toks):
        raise ValueError("trailing input in EDN")
    return v


def load_config(path: str) -> dict:
    raw = parse_edn(open(path, encoding="utf-8").read()) if path and os.path.exists(path) else None
    if raw is None:
        return {}
    return _normalize(raw)


def _normalize(x):
    if isinstance(x, dict):
        return {_key(k): _normalize(v) for k, v in x.items()}
    if isinstance(x, list):
        return [_normalize(i) for i in x]
    if isinstance(x, str) and x.startswith(":"):
        return x[1:]
    return x


def _key(k):
    return k[1:] if isinstance(k, str) and k.startswith(":") else k


def notebook_name(path: str) -> str:
    base = os.path.basename(path)
    return re.sub(r"\.clj$", "", base)


def slugify(s: str, default: str = "section", maxlen: int = 60) -> str:
    s = (s or "").strip()
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode("ascii")
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s).strip("-").lower()
    if not s:
        return default
    return s[:maxlen].rstrip("-")


def plan_output(out_dir: str, name: str, rel_files: list[str]) -> list[str]:
    if len(rel_files) == 1:
        return [os.path.join(out_dir, name + os.path.splitext(rel_files[0])[1])]
    return [os.path.join(out_dir, name, f) for f in rel_files]


def section_filename(index: int, heading_text: str, heading_id: str | None) -> str:
    slug = slugify(heading_text or heading_id or "")
    return "%02d-%s.png" % (index, slug)


# ---------------------------------------------------------------------------
# Run-archived site layout
# ---------------------------------------------------------------------------
# Each export is stored under <site>/runs/<run-id>/ so history is preserved and
# the whole tree can be copied to any static host (no Clerk/Java needed to
# serve it). <site>/index.html is a run index listing every export, newest
# first; each run keeps its own contact sheet (index.html + run-meta.json).
#
#   <site>/
#     index.html            # run index: one entry per export, newest first
#     runs/<run-id>/
#       index.html          # contact sheet for that export
#       run-meta.json
#       <name>.png          # single-shot notebook (flattened)
#       <name>/full.png     # multi-shot notebook
#       <name>/01-<slug>.png


def default_run_id(t=None) -> str:
    """Run directory name: UTC timestamp, sortable so lexicographic order == age order."""
    import time as _time

    return _time.strftime("%Y%m%dT%H%M%SZ", _time.gmtime(t))


def sanitize_run_id(s: str, default: str = "run") -> str:
    s = (s or "").strip()
    s = re.sub(r"[^A-Za-z0-9._-]+", "-", s).strip("-")
    return s[:80].rstrip("-") or default


def _iter_images(run_dir):
    for root, _dirs, files in os.walk(run_dir):
        for fn in sorted(files):
            if fn.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                yield os.path.relpath(os.path.join(root, fn), run_dir)


def collect_runs(site_dir: str) -> list:
    """Scan <site>/runs/*/run-meta.json; return list of run dicts, newest first.

    Each dict: {"id", "ok", "failed", "images"}. Runs without a run-meta.json
    (partial/interrupted) are still listed with empty metadata.
    """
    runs_root = os.path.join(site_dir, "runs")
    if not os.path.isdir(runs_root):
        return []
    runs = []
    for rid in sorted(os.listdir(runs_root), reverse=True):
        run_dir = os.path.join(runs_root, rid)
        if not os.path.isdir(run_dir):
            continue
        meta = {}
        meta_path = os.path.join(run_dir, "run-meta.json")
        if os.path.exists(meta_path):
            try:
                with open(meta_path, encoding="utf-8") as f:
                    meta = json.load(f)
            except (OSError, ValueError):
                meta = {}
        runs.append({
            "id": rid,
            "ok": sorted(meta.get("ok") or []),
            "failed": sorted(meta.get("failed") or []),
            "images": sum(1 for _ in _iter_images(run_dir)),
        })
    return runs


def run_index_html(site_dir: str, runs: list, thumbnail_limit: int = 0) -> str:
    """Run archive page: one entry per export run, newest first.

    thumbnail_limit > 0 embeds that many small preview images per run (via
    <img> pointing at the run's own PNGs). Lives at <site>/runs/index.html.
    """
    body = ["<h1>Run archive</h1>",
            "<p class='sec-sub'>What exactly was exported in build X? "
            "These are provenance records — for discovery, start at the "
            "<a href='../index.html'>homepage</a>.</p>"]
    if not runs:
        body.append("<p class='notice'>No exports yet. Run <code>bb notebook:shots</code>.</p>")
    for r in runs:
        ok, failed, images = r["ok"], r["failed"], r["images"]
        body.append("<div class='run' style='background:#1e293b;border:1px solid #334155;"
                    "border-radius:8px;padding:12px;margin:0 0 18px;overflow:hidden'>"
                    "<a href='%s/index.html'><h3 style='margin:6px 0 4px'>%s</h3></a>"
                    "<div class='meta' style='color:#94a3b8;font-size:13px'>"
                    "%d notebook(s), %d image(s)"
                    % (r["id"], r["id"], len(ok) + len(failed), images))
        if failed:
            body.append(" · <span style='color:#f87171'>%d failed</span>" % len(failed))
        body.append("</div>")
        if thumbnail_limit > 0:
            run_dir = os.path.join(site_dir, "runs", r["id"])
            thumbs = [os.path.join(r["id"], rel)
                      for rel in list(_iter_images(run_dir))[:thumbnail_limit]]
            if thumbs:
                body.append("<div class='thumbs' style='display:grid;"
                            "grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px'>")
                for t in thumbs:
                    body.append("<a href='%s/index.html'><img loading='lazy' src='%s' "
                                "style='width:100%%;border-radius:4px;border:1px solid #334155'>"
                                "</a>" % (r["id"], t))
                body.append("</div>")
        body.append("</div>")
    return page_html("Run archive", "".join(body), active="runs", root="../")


# ---------------------------------------------------------------------------
# Guided site: registry-driven homepage / explore / catalogue / notebook pages
# ---------------------------------------------------------------------------
# The exported site's IA comes from data/notebooks.edn (themes + presentation),
# not from the run layout. Runs remain under runs/ as provenance only.
#
#   <site>/index.html                  homepage: hero + Start here + themes
#   <site>/explore/<theme>.html        5 public-theme pages
#   <site>/notebooks/index.html        full catalogue + filters
#   <site>/notebooks/<id>/index.html   stable per-notebook page (current + history)
#   <site>/assets/notebooks/<id>/…     current screenshots (copied from newest run)
#   <site>/runs/<id>/…                 unchanged run artifacts (provenance)


SITE_CSS = """
*{box-sizing:border-box}
body{font-family:system-ui,sans-serif;margin:0;background:#0f172a;color:#e2e8f0;line-height:1.55}
a{color:#7dd3fc;text-decoration:none}
a:hover{text-decoration:underline}
.wrap{max-width:1100px;margin:0 auto;padding:24px 28px 64px}
.top{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:28px}
.top .brand{font-weight:800;font-size:14px;letter-spacing:.05em;color:#f8fafc;font-family:ui-monospace,monospace}
.top .links{display:flex;gap:4px;margin-left:auto;flex-wrap:wrap}
.top .links a{padding:5px 11px;border-radius:5px;font-size:13px;color:#94a3b8}
.top .links a.active,.top .links a:hover{background:#1e293b;color:#f8fafc;text-decoration:none}
.hero{background:linear-gradient(180deg,#0f172a,#111827);border:1px solid #1e293b;border-radius:12px;
  padding:36px 40px;margin-bottom:32px}
.hero h1{margin:0 0 8px;font-size:26px;font-weight:800;letter-spacing:.02em}
.hero p{margin:0 0 22px;color:#94a3b8;font-size:15px;max-width:64ch}
.cta{display:inline-block;background:#0d9488;color:#042f2e!important;font-weight:700;
  padding:10px 18px;border-radius:6px;margin-right:10px;font-size:14px}
.cta.ghost{background:transparent;color:#7dd3fc!important;border:1px solid #334155}
h2.sec{font-size:18px;font-weight:800;margin:40px 0 6px;letter-spacing:.02em}
.sec-sub{color:#94a3b8;font-size:13px;margin:0 0 18px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:14px}
.card{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:14px;display:flex;
  flex-direction:column;gap:10px}
a.card{color:inherit;text-decoration:none;transition:border-color .12s,transform .12s}
a.card:hover{border-color:#0d9488;transform:translateY(-1px)}
a.card .more{color:#7dd3fc}
a.card:hover .more{text-decoration:underline}
.card .num{font-family:ui-monospace,monospace;color:#5eead4;font-size:13px;font-weight:700}
.card h3{margin:0;font-size:15px;color:#f8fafc;font-weight:700;line-height:1.35}
.card h3 a{color:#f8fafc}
.card .q{margin:0;color:#94a3b8;font-size:13.5px}
.card .chips{display:flex;gap:6px;flex-wrap:wrap}
.card .chip{font-size:11px;font-weight:600;padding:2px 9px;border-radius:999px;
  background:#0f172a;border:1px solid #334155;color:#cbd5e1}
.card .chip.theme{background:#064e3b;border-color:#0d9488;color:#99f6e4}
.card .chip.kind-demo{background:#1e1b4b;border-color:#6366f1;color:#c7d2fe}
.card .chip.kind-analysis{background:#3b0764;border-color:#a855f7;color:#e9d5ff}
.card .chip.kind-report{background:#7c2d12;border-color:#ea580c;color:#fed7aa}
.card .chip.kind-tool{background:#1e3a8a;border-color:#3b82f6;color:#bfdbfe}
.card .chip.status{background:#334155;border-color:#475569;color:#e2e8f0}
.card img.preview{width:100%;border-radius:6px;border:1px solid #334155;display:block;object-fit:cover}
.card .more{margin-top:auto;font-size:13px;font-weight:600}
.card .deeper{font-size:12.5px;color:#94a3b8}
.strip{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.strip img{width:100%;border-radius:6px;border:1px solid #334155;display:block}
table.hist{width:100%;border-collapse:collapse;font-size:13px;margin-top:10px}
table.hist td{padding:7px 10px;border-bottom:1px solid #1e293b}
table.hist tr td:first-child{font-family:ui-monospace,monospace;color:#5eead4}
.filters{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:18px}
.filters button{background:#1e293b;border:1px solid #334155;color:#cbd5e1;border-radius:999px;
  padding:4px 12px;font-size:12px;cursor:pointer;font-family:inherit}
.filters button.on{background:#0d9488;color:#042f2e;border-color:#0d9488;font-weight:700}
.notice{color:#94a3b8;font-size:13px}
.notice a{color:#7dd3fc}
"""


def page_html(title, body, active=None, root=""):
    """Full HTML document with shared header. active: 'home'|'explore'|'notebooks'|'runs'.
    root: prefix ('' at site root, '../' one level deep, …) prepended to nav hrefs."""
    return "\n".join([
        "<!doctype html><html><head><meta charset='utf-8'>",
        "<meta name='viewport' content='width=device-width, initial-scale=1'>",
        "<title>%s</title>" % html.escape(title),
        "<style>%s</style></head><body><div class='wrap'>" % SITE_CSS,
        "<div class='top'><span class='brand'>PRF — Protocol Robustness</span>"
        "<div class='links'>"
        "%s" % _nav_link("Home", root + "index.html", "home", active),
        "%s" % _nav_link("Explore", root + "explore/failures-attacks.html", "explore", active),
        "%s" % _nav_link("Notebooks", root + "notebooks/index.html", "notebooks", active),
        "%s" % _nav_link("Runs", root + "runs/index.html", "runs", active),
        "</div></div>",
        body,
        "</div></body></html>",
    ])


def _nav_link(label, href, key, active):
    cls = " active" if key == active else ""
    return "<a class='%s' href='%s'>%s</a>" % (cls.strip(), href, label)


def load_registry(path):
    """Normalized data/notebooks.edn: {themes, categories, notebooks:[...]}."""
    cfg = load_config(path)
    if not cfg:
        cfg = {}
    cfg.setdefault("themes", {})
    cfg.setdefault("categories", {})
    cfg.setdefault("notebooks", [])
    return cfg


def notebook_registry_index(registry):
    """Map clj-basename and registry :id -> entry for fast lookup."""
    by_name, by_id = {}, {}
    for e in registry.get("notebooks", []):
        name = notebook_name(e.get("path", ""))
        by_name[name] = e
        by_id[e.get("id")] = e
    return by_name, by_id


# fallback theme derivation (presentation.theme is authoritative; this only
# fills in entries that predate the :presentation layer)
_CATEGORY_THEME = {
    "security-validation": "failures-attacks",
    "adversarial": "failures-attacks",
    "findings": "failures-attacks",
    "economic-analysis": "allocation-liquidity",
    "yield-liquidity": "allocation-liquidity",
    "governance": "disputes-governance",
    "evidence-artifacts": "evidence-reproducibility",
    "provenance": "evidence-reproducibility",
    "replay-determinism": "evidence-reproducibility",
    "demos": "simulation-scenarios",
    "tooling": "simulation-scenarios",
    "experimental": "simulation-scenarios",
}


def theme_of(entry, registry):
    pres = entry.get("presentation") or {}
    theme = pres.get("theme")
    if theme:
        return theme
    for c in entry.get("categories", []):
        if c in _CATEGORY_THEME:
            return _CATEGORY_THEME[c]
    return "evidence-reproducibility"


def kind_of(entry):
    pres = entry.get("presentation") or {}
    kind = pres.get("kind")
    if kind:
        return kind
    cats = set(entry.get("categories", []))
    if "demos" in cats:
        return "demo"
    if "tooling" in cats:
        return "tool"
    return "analysis"


def level_of(entry):
    pres = entry.get("presentation") or {}
    lvl = pres.get("audience-level")
    if lvl:
        return lvl
    return "intro" if kind_of(entry) == "demo" else "intermediate"


def status_of(entry):
    return entry.get("status") or "draft"


def collect_notebook_runs(site_dir):
    """Map notebook-name -> [(run_id, [rel files])], newest run first."""
    out = {}
    runs_root = os.path.join(site_dir, "runs")
    if os.path.isdir(runs_root):
        for rid in sorted(os.listdir(runs_root), reverse=True):
            meta_path = os.path.join(runs_root, rid, "run-meta.json")
            if not os.path.exists(meta_path):
                continue
            try:
                with open(meta_path, encoding="utf-8") as f:
                    meta = json.load(f)
            except (OSError, ValueError):
                continue
            for name, rels in (meta.get("files") or {}).items():
                out.setdefault(name, []).append((rid, rels))
    return out


def copy_notebook_assets(site_dir, notebook_runs, registry_by_name):
    """Copy each notebook's NEWEST run images into assets/notebooks/<id>/.

    Returns {name: id} for notebooks that now have assets. Idempotent: only
    copies when the destination file is missing or older than the source.
    """
    copied = {}
    for name, runs in notebook_runs.items():
        if not runs:
            continue
        rid, rels = runs[0]
        entry = registry_by_name.get(name)
        if not entry:
            continue
        nid = entry.get("id") or name
        dest_dir = os.path.join(site_dir, "assets", "notebooks", nid)
        os.makedirs(dest_dir, exist_ok=True)
        for rel in rels:
            src = os.path.join(site_dir, "runs", rid, rel)
            dst = os.path.join(dest_dir, os.path.basename(rel))
            if not os.path.exists(src):
                continue
            if (not os.path.exists(dst)
                    or os.path.getmtime(src) > os.path.getmtime(dst) + 0.5):
                shutil.copy2(src, dst)
        copied[name] = nid
    return copied


def _notebook_assets(site_dir, nid):
    d = os.path.join(site_dir, "assets", "notebooks", nid)
    if not os.path.isdir(d):
        return []
    return sorted(fn for fn in os.listdir(d)
                  if fn.lower().endswith((".png", ".jpg", ".jpeg", ".webp")))


def _asset_url(nid, fn, root=""):
    return "%sassets/notebooks/%s/%s" % (root, nid, fn)


def _chips(entry, registry, with_status=True):
    theme = theme_of(entry, registry)
    kind = kind_of(entry)
    lvl = level_of(entry)
    theme_label = ((registry.get("themes") or {}).get(theme) or {}).get("label") or theme
    parts = [
        "<span class='chip theme'>%s</span>" % html.escape(theme_label),
        "<span class='chip kind-%s'>%s</span>" % (kind, KIND_LABEL.get(kind, kind.title())),
        "<span class='chip'>%s</span>" % LEVEL_LABEL.get(lvl, lvl.replace("-", " ").title()),
    ]
    if with_status:
        parts.append("<span class='chip status'>%s</span>" % html.escape(str(status_of(entry)).title()))
    return "".join(parts)


KIND_LABEL = {"demo": "Demo", "analysis": "Analysis", "report": "Report", "tool": "Tool"}
LEVEL_LABEL = {"intro": "Intro", "intermediate": "Intermediate", "deep-dive": "Deep dive"}


def notebook_card(entry, registry, site_dir, numbered=False, prefix="", preview=True,
                  with_status=True, deeper=None):
    """Card component reused on homepage, explore, and catalogue.

    The whole card is one link to the notebook page (when one exists), so every
    part — header, question, chips, preview — is clickable. Notebooks without
    screenshots render a non-clickable "Screenshots pending" card.
    """
    nid = entry.get("id")
    title = entry.get("title") or nid
    question = (entry.get("presentation") or {}).get("question") or entry.get("summary") or ""
    has_page = bool(_notebook_assets(site_dir, nid))
    href = "%snotebooks/%s/index.html" % (prefix, nid)
    inner = []
    if numbered:
        inner.append("<div class='num'>%d</div>" % numbered)
    inner.append("<h3>%s</h3>" % html.escape(title))
    if question:
        inner.append("<p class='q'>%s</p>" % html.escape(question))
    inner.append("<div class='chips'>%s</div>" % _chips(entry, registry, with_status))
    if preview:
        assets = _notebook_assets(site_dir, nid)
        if assets:
            inner.append("<img class='preview' loading='lazy' src='%s'>"
                         % _asset_url(nid, assets[0], prefix))
    if has_page:
        inner.append("<span class='more'>Explore notebook →</span>")
    else:
        inner.append("<span class='more'>Screenshots pending</span>")
    inner_html = "".join(inner)
    attrs = "data-theme='%s' data-kind='%s' data-level='%s'" % (
        theme_of(entry, registry), kind_of(entry), level_of(entry))
    if has_page:
        return ("<a class='card' %s href='%s'>%s</a>" % (attrs, href, inner_html))
    return "<div class='card' %s>%s</div>" % (attrs, inner_html)


def registry_by_id_lookup(registry, nid):
    for e in registry.get("notebooks", []):
        if e.get("id") == nid:
            return e.get("title") or nid
    return nid


def featured_entries(registry):
    """(rank, entry) for all :featured? true notebooks, sorted by rank."""
    out = []
    for e in registry.get("notebooks", []):
        pres = e.get("presentation") or {}
        if pres.get("featured?"):
            out.append((pres.get("start-here-rank", 99), e))
    return [e for _, e in sorted(out, key=lambda x: x[0])]


def homepage_html(registry, site_dir):
    themes = registry.get("themes") or {}
    by_id = {e.get("id"): e for e in registry.get("notebooks", [])}
    featured = featured_entries(registry)

    def has_page(nid):
        return bool(_notebook_assets(site_dir, nid))

    # theme cards with counts + a preview from the first notebook in that theme
    theme_rows = []
    for tkey, tmeta in themes.items():
        members = [e for e in registry.get("notebooks", []) if theme_of(e, registry) == tkey]
        preview = ""
        for m in members:
            assets = _notebook_assets(site_dir, m.get("id"))
            if assets:
                preview = "<img class='preview' loading='lazy' src='%s'>" % _asset_url(m.get("id"), assets[0])
                break
        href = "explore/%s.html" % tkey
        theme_rows.append(
            "<a class='card' href='%s'>"
            "<h3>%s</h3>"
            "<p class='q'>%s</p>"
            "<div class='chips'><span class='chip theme'>%d notebook(s)</span></div>"
            "%s"
            "<span class='more'>Explore theme →</span></a>"
            % (href, html.escape(tmeta.get("label") or tkey),
               html.escape(tmeta.get("blurb") or ""),
               len(members), preview))

    start_here = []
    for i, e in enumerate(featured, 1):
        nid = e.get("id")
        has = has_page(nid)
        deeper = (e.get("presentation") or {}).get("deeper-id")
        deeper_extra = ""
        if deeper and deeper in by_id and has_page(deeper):
            deeper_extra = ("<span class='deeper'>Deeper analysis → "
                            "<a href='notebooks/%s/index.html'>%s</a></span>"
                            % (deeper, html.escape(by_id[deeper].get("title") or deeper)))
        if has:
            card = (
                "<a class='card' data-theme='%s' href='notebooks/%s/index.html'>"
                "<div class='num'>Start here — %d</div>"
                "<h3>%s</h3>"
                "<p class='q'>%s</p>"
                "<div class='chips'>%s</div>"
                "<img class='preview' loading='lazy' src='%s'>"
                "<span class='more'>Watch it happen →</span></a>"
                % (theme_of(e, registry), nid, i,
                   html.escape(e.get("title") or nid),
                   html.escape((e.get("presentation") or {}).get("question") or e.get("summary") or ""),
                   _chips(e, registry, with_status=False),
                   _asset_url(nid, _notebook_assets(site_dir, nid)[0])))
            if deeper_extra:
                card += "<p class='notice' style='margin:6px 0 0'>%s</p>" % deeper_extra
            start_here.append(card)
        else:
            start_here.append(
                "<div class='card' data-theme='%s'>"
                "<div class='num'>Start here — %d</div>"
                "<h3>%s</h3>"
                "<p class='q'>%s</p>"
                "<div class='chips'>%s</div>"
                "%s"
                "<span class='more'>Screenshots pending — "
                "<a href='notebooks/index.html'>see the catalogue</a></span></div>"
                % (theme_of(e, registry), i, html.escape(e.get("title") or nid),
                   html.escape((e.get("presentation") or {}).get("question") or e.get("summary") or ""),
                   _chips(e, registry, with_status=False),
                   deeper_extra))

    body = "".join([
        "<div class='hero'><h1>Protocol Robustness Framework</h1>"
        "<p>Making protocol behaviour visible before it becomes expensive. "
        "Watch what actually happens when a decision is rejected, evidence is reordered, "
        "liquidity falls short, or a dispute escalates — then follow the deeper analysis.</p>"
        "<a class='cta' href='notebooks/demo-not-admitted/index.html'>Start with a 2-minute demo</a>"
        "<a class='cta ghost' href='explore/failures-attacks.html'>Explore by problem</a>"
        "<a class='cta ghost' href='notebooks/index.html'>Browse all notebooks</a></div>",
        "<h2 class='sec'>Start here</h2>"
        "<p class='sec-sub'>One question → one scenario → one visible consequence.</p>",
        "<div class='grid'>%s</div>" % "".join(start_here),
        "<h2 class='sec'>Explore by problem</h2>"
        "<p class='sec-sub'>What are you worried about? Start with the theme, not the taxonomy.</p>",
        "<div class='grid'>%s</div>" % "".join(theme_rows),
    ])
    return page_html("Protocol Robustness Framework", body, active="home")


def explore_page_html(registry, site_dir, theme_key):
    themes = registry.get("themes") or {}
    tmeta = themes.get(theme_key) or {}
    members = [e for e in registry.get("notebooks", [])
               if theme_of(e, registry) == theme_key]
    body = "".join([
        "<h1>%s</h1>" % html.escape(tmeta.get("label") or theme_key),
        "<p class='sec-sub'>%s</p>" % html.escape(tmeta.get("blurb") or ""),
        "<div class='grid'>%s</div>" % "".join(
            notebook_card(e, registry, site_dir, prefix="../")
            for e in members),
    ])
    return page_html(tmeta.get("label") or theme_key, body, active="explore", root="../")


def catalogue_html(registry, site_dir):
    entries = [e for e in registry.get("notebooks", [])
               if e.get("status") != "archived"]
    themes = registry.get("themes") or {}
    theme_labels = {k: (v.get("label") or k) for k, v in themes.items()}
    filter_btns = ["<button data-f='' class='on'>All</button>"]
    filter_btns += ["<button data-f='theme:%s'>%s</button>" % (k, html.escape(label))
                    for k, label in sorted(theme_labels.items())]
    filter_btns += ["<button data-f='kind:%s'>%s</button>" % (k, v)
                    for k, v in sorted(KIND_LABEL.items())]
    cards = [notebook_card(e, registry, site_dir, prefix="../") for e in entries]
    script = (
        "<script>"
        "document.querySelectorAll('.filters button').forEach(function(b){"
        "b.onclick=function(){var f=b.getAttribute('data-f');"
        "document.querySelectorAll('.filters button').forEach(function(x){x.classList.remove('on')});"
        "b.classList.add('on');"
        "document.querySelectorAll('.card').forEach(function(c){var show=!f;"
        "if(f){var p=f.split(':');show=c.getAttribute('data-'+p[0])===p[1];}"
        "c.style.display=show?'':'none';});};});"
        "</script>")
    body = "".join([
        "<h1>All notebooks</h1>",
        "<p class='sec-sub'>Filter by theme or type. Every card leads to a dedicated page with current "
        "screenshots and run history.</p>",
        "<div class='filters'>%s</div>" % "".join(filter_btns),
        "<div class='grid'>%s</div>" % "".join(cards),
        script,
    ])
    return page_html("All notebooks", body, active="notebooks", root="../")


def notebook_page_html(entry, registry, site_dir, run_history):
    """run_history: list of (run_id, [rel files]) for this notebook, newest first."""
    nid = entry.get("id")
    title = entry.get("title") or nid
    question = (entry.get("presentation") or {}).get("question") or entry.get("summary") or ""
    assets = _notebook_assets(site_dir, nid)
    deeper = (entry.get("presentation") or {}).get("deeper-id")
    by_id = {e.get("id"): e for e in registry.get("notebooks", [])}

    body = ["<a class='notice' href='../../index.html'>← Home</a>",
            "<h1>%s</h1>" % html.escape(title)]
    if question:
        body.append("<p class='sec-sub'>%s</p>" % html.escape(question))
    body.append("<div class='chips' style='margin-bottom:16px'>%s</div>" % _chips(entry, registry))
    if deeper and deeper in by_id and _notebook_assets(site_dir, deeper):
        body.append("<p class='notice'>Deeper analysis: "
                    "<a href='../%s/index.html'>%s</a></p>"
                    % (deeper, html.escape(by_id[deeper].get("title") or deeper)))
    if assets:
        body.append("<div class='strip'>%s</div>"
                    % "".join("<a href='#%s'><img loading='lazy' src='%s'></a>"
                              % (fn, _asset_url(nid, fn, "../../")) for fn in assets[:3]))
        body.append("<h2 class='sec'>Sections (%d)</h2>" % len(assets))
        for fn in assets:
            body.append("<img style='width:100%%;border-radius:8px;border:1px solid #334155;"
                        "margin:8px 0;display:block' loading='lazy' id='%s' src='%s'>"
                        % (fn, _asset_url(nid, fn, "../../")))
    else:
        body.append("<p class='notice'>No screenshots exported for this notebook yet.</p>")
    body.append("<h2 class='sec'>Source</h2>")
    body.append("<p class='notice'>Source notebook: <code>%s</code></p>"
                % html.escape(entry.get("path") or ""))
    body.append("<h2 class='sec'>Run history</h2>")
    if run_history:
        rows = ["<tr><td>%s</td><td>%d image(s)</td>"
                "<td><a href='../../runs/%s/index.html'>view run →</a></td></tr>"
                % (rid, len(rels), rid) for rid, rels in run_history]
        body.append("<table class='hist'>%s</table>" % "".join(rows))
    else:
        body.append("<p class='notice'>No runs recorded.</p>")
    return page_html(title, "".join(body), active="notebooks", root="../../")


def write_guided_site(site_dir, registry, thumbnail_limit=0):
    """Build homepage, explore, catalogue, and per-notebook pages into site_dir.

    Requires assets/notebooks/<id>/ to already hold current screenshots (see
    copy_notebook_assets). Returns dict of files written.
    """
    os.makedirs(site_dir, exist_ok=True)
    for sub in ("explore", "notebooks", "assets/notebooks"):
        os.makedirs(os.path.join(site_dir, sub), exist_ok=True)

    themes = registry.get("themes") or {}
    notebook_runs = collect_notebook_runs(site_dir)
    by_name, by_id = notebook_registry_index(registry)

    # copy current screenshots from the newest run per notebook
    copy_notebook_assets(site_dir, notebook_runs, by_name)

    written = {}
    def _put(rel, content):
        p = os.path.join(site_dir, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(content)
        written[rel] = True

    _put("index.html", homepage_html(registry, site_dir))
    for tkey in themes:
        _put("explore/%s.html" % tkey, explore_page_html(registry, site_dir, tkey))
    _put("notebooks/index.html", catalogue_html(registry, site_dir))
    _put("runs/index.html", run_index_html(site_dir, collect_runs(site_dir), thumbnail_limit=thumbnail_limit))
    for name, runs in notebook_runs.items():
        entry = by_name.get(name)
        if not entry:
            continue
        _put("notebooks/%s/index.html" % entry["id"],
             notebook_page_html(entry, registry, site_dir, runs))
    return written


RENDER_READY_JS = """() => {
  const root = document.querySelector('.notebook-viewer');
  if (!root) return { ready: false, count: 0 };
  return { ready: true, count: document.querySelectorAll('.viewer').length };
}"""

COLLECT_HEADINGS_JS = """(maxLevel) => {
  const sel = [];
  for (let i = 1; i <= maxLevel; i++) sel.push('h' + i);
  const out = [];
  document.querySelectorAll(sel.join(',')).forEach((el) => {
    out.push({ text: el.textContent.trim(), id: el.id || null });
  });
  return out;
}"""

SCROLL_TO_JS = """(arg) => {
  const [maxLevel, idx] = arg;
  const sel = [];
  for (let i = 1; i <= maxLevel; i++) sel.push('h' + i);
  const el = document.querySelectorAll(sel.join(','))[idx];
  if (el) { el.scrollIntoView({ block: 'start', behavior: 'instant' }); return true; }
  return false;
}"""

COLLECT_SECTIONS_JS = """(maxLevel) => {
  const sel = [];
  for (let i = 1; i <= maxLevel; i++) sel.push('h' + i);
  const heads = [...document.querySelectorAll(sel.join(','))];
  const docH = document.documentElement.scrollHeight;
  return heads.map((h, idx) => {
    const r = h.getBoundingClientRect();
    const next = idx + 1 < heads.length
      ? (() => { const q = heads[idx + 1].getBoundingClientRect(); return q.top + window.scrollY; })()
      : docH;
    return { text: h.textContent.trim(), id: h.id || null,
             top: r.top + window.scrollY, bottom: next };
  });
}"""

GET_HEADING_TOP_JS = """(arg) => {
  const [maxLevel, idx] = arg;
  const sel = [];
  for (let i = 1; i <= maxLevel; i++) sel.push('h' + i);
  const el = document.querySelectorAll(sel.join(','))[idx];
  return el ? el.getBoundingClientRect().top + window.scrollY : null;
}"""

BODY_BG_JS = """() => getComputedStyle(document.body).backgroundColor"""
