"""Shared helpers for notebook screenshot export.

Kept importable without Playwright so the pure functions (EDN config parsing,
slugify, output path planning) can be unit-tested in isolation.
"""
from __future__ import annotations

import os
import re
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
