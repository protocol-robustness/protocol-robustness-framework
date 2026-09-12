#!/usr/bin/env python3
"""Unit tests for trace_compare structural diff helpers."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from trace_compare import (
    build_structural_summary,
    compare_documents,
    diff_traces,
    structural_diff,
    truncate_value,
)


class TraceCompareTests(unittest.TestCase):
    def test_structural_diff_detects_map_change(self) -> None:
        only_a, only_b = structural_diff({"a": 1, "b": {"c": 2}}, {"a": 1, "b": {"c": 3}})
        self.assertEqual(only_a, {"b": {"c": 2}})
        self.assertEqual(only_b, {"b": {"c": 3}})

    def test_diff_traces_finds_first_step(self) -> None:
        trace_a = [
            {"action": "create", "world": {"block-time": 1}},
            {"action": "dispute", "world": {"block-time": 2, "x": 1}},
        ]
        trace_b = [
            {"action": "create", "world": {"block-time": 1}},
            {"action": "dispute", "world": {"block-time": 2, "x": 2}},
        ]
        result = diff_traces(trace_a, trace_b)
        self.assertIsNotNone(result)
        self.assertEqual(result["divergence_at"], 1)
        self.assertEqual(result["action"], "dispute")

    def test_diff_traces_length_mismatch(self) -> None:
        result = diff_traces([{"world": {}}], [{"world": {}}, {"world": {}}])
        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result["reason"], "trace-length-mismatch")

    def test_compare_documents_includes_projection_hash(self) -> None:
        base = {
            "outcome": "pass",
            "metrics": {"events": 1},
            "trace": [{"action": "x", "world": {"a": 1}, "projection-hash": "hash-a"}],
        }
        cand = {
            "outcome": "pass",
            "metrics": {"events": 1},
            "trace": [{"action": "x", "world": {"a": 2}, "projection-hash": "hash-b"}],
        }
        summary = compare_documents(base, cand)
        structural = summary["structural_diff"]
        self.assertFalse(structural["projection_hash_match"])
        self.assertFalse(structural["worlds_identical"])
        self.assertEqual(structural["baseline_projection_hash"], "hash-a")
        self.assertIn("projection-hash differs", summary["headline"])

    def test_write_comparison_round_trip(self) -> None:
        from trace_compare import to_markdown, write_comparison_report

        summary = compare_documents({"outcome": "pass", "trace": []}, {"outcome": "fail", "trace": []})
        with tempfile.TemporaryDirectory() as tmp:
            json_path, md_path = write_comparison_report(summary, Path(tmp))
            self.assertTrue(json_path.exists())
            self.assertTrue(md_path.exists())
            md = md_path.read_text(encoding="utf-8")
            self.assertIn("Simulation Regression Report", md)
            parsed = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertIn("structural_diff", parsed)

    def test_truncate_value_limits_depth(self) -> None:
        nested = {"a": {"b": {"c": {"d": {"e": 1}}}}}
        truncated = truncate_value(nested, max_depth=2)
        self.assertEqual(truncated, {"a": {"b": "…"}})


if __name__ == "__main__":
    unittest.main()
