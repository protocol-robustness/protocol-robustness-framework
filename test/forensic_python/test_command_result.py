"""The forensic run adapter can receive its exact artifact directory as JSON."""

import json

from scripts.forensic import run


def test_command_result_emitter_is_opt_in(monkeypatch, capsys, tmp_path):
    output_dir = tmp_path / "run-123"
    monkeypatch.delenv("PRF_FORENSIC_COMMAND_JSON", raising=False)

    run._emit_command_result(output_dir, "pass", 0)
    assert capsys.readouterr().out == ""
    monkeypatch.setenv("PRF_FORENSIC_COMMAND_JSON", "1")
    run._emit_command_result(output_dir, "pass", 0)
    report = json.loads(capsys.readouterr().out)
    assert report["output-dir"] == str(output_dir)
    assert report["exit-code"] == 0
