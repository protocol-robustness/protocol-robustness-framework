"""Minimal EDN reader for the small, committed conformance descriptors.

Supports the subset of EDN used by etc/conformance/claims.edn and
etc/conformance/profiles/*.edn:
  - maps {...}, vectors [...], sets #{...}
  - keywords (:foo, :foo/bar  -> kept as the string ":foo/bar")
  - strings, integers
  - ; comments and whitespace

It intentionally does NOT implement the full EDN spec.  The authoritative
interpretation lives in the Clojure namespaces (resolver-sim.conformance.*);
this reader exists so non-Clojure tooling can consume the same committed data
without a JVM.
"""

from __future__ import annotations

import re
from typing import Any

_TOKEN_RE = re.compile(r"[^\s\[\]{}#\"(),]+")


class _Reader:
    def __init__(self, text: str) -> None:
        self.text = text
        self.pos = 0

    def _skip(self) -> None:
        n = len(self.text)
        while self.pos < n:
            c = self.text[self.pos]
            if c.isspace():
                self.pos += 1
            elif c == ";":
                while self.pos < n and self.text[self.pos] != "\n":
                    self.pos += 1
            else:
                break

    def value(self) -> Any:
        self._skip()
        n = len(self.text)
        if self.pos >= n:
            raise ValueError("unexpected end of EDN input")
        c = self.text[self.pos]

        if c == "{":
            self.pos += 1
            m: dict[Any, Any] = {}
            while True:
                self._skip()
                if self.pos >= n or self.text[self.pos] == "}":
                    self.pos += 1
                    return m
                k = self.value()
                v = self.value()
                m[k] = v

        if c == "[":
            self.pos += 1
            items: list[Any] = []
            while True:
                self._skip()
                if self.pos >= n or self.text[self.pos] == "]":
                    self.pos += 1
                    return items
                items.append(self.value())

        if c == "#":
            self.pos += 1
            if self.pos >= n or self.text[self.pos] != "{":
                raise ValueError("only #{...} set literals are supported")
            self.pos += 1
            s: set[Any] = set()
            while True:
                self._skip()
                if self.pos >= n or self.text[self.pos] == "}":
                    self.pos += 1
                    return s
                s.add(self.value())

        if c == '"':
            self.pos += 1
            buf: list[str] = []
            while self.pos < n and self.text[self.pos] != '"':
                if self.text[self.pos] == "\\" and self.pos + 1 < n:
                    self.pos += 1
                buf.append(self.text[self.pos])
                self.pos += 1
            if self.pos >= n:
                raise ValueError("unterminated string in EDN")
            self.pos += 1
            return "".join(buf)

        if c == ":":
            self.pos += 1
            m = _TOKEN_RE.match(self.text, self.pos)
            if not m:
                raise ValueError("malformed keyword in EDN")
            self.pos = m.end()
            return ":" + m.group(0)

        m = _TOKEN_RE.match(self.text, self.pos)
        if not m:
            raise ValueError(f"unexpected character in EDN: {c!r}")
        tok = m.group(0)
        self.pos = m.end()
        try:
            return int(tok)
        except ValueError:
            return tok


def parse_edn(text: str) -> Any:
    """Parse the first top-level EDN value in `text`."""
    return _Reader(text).value()
