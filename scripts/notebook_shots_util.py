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
    """Site index: one entry per export run, newest first.

    thumbnail_limit > 0 embeds that many small preview images per run (via
    <img> pointing at the run's own PNGs) so the landing page is self-contained.
    """
    html = [
        "<!doctype html><html><head><meta charset='utf-8'>",
        "<title>Notebook Screenshots — All Exports</title>",
        "<style>body{font-family:system-ui,sans-serif;margin:24px;background:#0f172a;color:#e2e8f0}",
        "h1{font-size:20px}h2{font-size:15px;margin:28px 0 10px;color:#7adddc}",
        "a{color:#94a3b8;text-decoration:none;font-size:12px}",
        ".run{display:block;background:#1e293b;border:1px solid #334155;border-radius:8px;",
        "padding:12px;margin:0 0 18px;overflow:hidden}",
        ".run h2{margin:6px 0 4px;font-weight:700;color:#e2e8f0;font-size:13px}",
        ".run .meta{color:#94a3b8;font-size:12px;margin-bottom:8px}",
        ".thumbs{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px}",
        ".thumbs img{width:100%;border-radius:4px;border:1px solid #334155;display:block}",
        "</style></head><body><h1>Notebook screenshots — all exports</h1>",
    ]
    if not runs:
        html.append("<p style='color:#94a3b8'>No exports yet. Run <code>bb notebook:shots</code>.</p>")
    for r in runs:
        ok, failed, images = r["ok"], r["failed"], r["images"]
        html.append("<div class='run'><a href='runs/%s/index.html'><h2>%s</h2></a>"
                    "<div class='meta'>%d notebook(s), %d image(s)"
                    % (r["id"], r["id"], len(ok) + len(failed), images))
        if failed:
            html.append(" · <span style='color:#f87171'>%d failed</span>" % len(failed))
        html.append("</div>")
        if thumbnail_limit > 0:
            run_dir = os.path.join(site_dir, "runs", r["id"])
            thumbs = [os.path.join("runs", r["id"], rel)
                      for rel in list(_iter_images(run_dir))[:thumbnail_limit]]
            if thumbs:
                html.append("<div class='thumbs'>")
                for t in thumbs:
                    html.append("<img loading='lazy' src='%s'>" % t)
                html.append("</div>")
        html.append("</div>")
    html.append("</body></html>")
    return "\n".join(html)


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
