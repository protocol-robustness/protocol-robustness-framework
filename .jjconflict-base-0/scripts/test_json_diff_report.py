#!/usr/bin/env python3
"""Unit tests for json_diff_report."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from json_diff_report import collect_diffs, render_report


class JsonDiffReportTests(unittest.TestCase):
    def test_collect_diffs_nested_map(self) -> None:
        diffs = collect_diffs({"a": 1, "b": {"c": 2}}, {"a": 1, "b": {"c": 3}})
        self.assertEqual(len(diffs), 1)
        self.assertEqual(diffs[0]["path"], "b.c")
        self.assertEqual(diffs[0]["expected"], 2)
        self.assertEqual(diffs[0]["actual"], 3)

    def test_render_report_limits_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            expected = Path(tmp) / "expected.json"
            actual = Path(tmp) / "actual.json"
            expected.write_text(json.dumps({"items": [1, 2, 3]}), encoding="utf-8")
            actual.write_text(json.dumps({"items": [1, 9, 3]}), encoding="utf-8")
            report = render_report(expected, actual, limit=5)
            self.assertIn("items[1]", report)
            self.assertIn("differences: 1", report)


if __name__ == "__main__":
    unittest.main()
