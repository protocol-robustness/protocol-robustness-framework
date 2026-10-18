# Command cleanup receipt — 2026-10-18

This receipt covers the command interface changes at PRF revisions
`606491d5a12be119cce5ab96cbda6984752d38dc`, `b8532886`, `60e7d18b`,
`0033130e`, `01e498df` and `167f9a4e`. It records interface and regression checks only;
it does not change allocator, forensic, proof or deployment release
qualification.

## Changes

- `bin/prf` records the caller directory; explicit path options and `compare`
  artifact arguments resolve against it while repository resources keep using
  the selected PRF checkout.
- The dispatcher builds ordinary command parsers from each command's registry
  options. This removes the duplicate global option table and rejects unsupported
  options per command. Ten commands mark their parser as handler-owned; those
  parsers reject unknown options and stray positional arguments.
- Scenario comparison declares its two required references and JSON mode emits
  one machine-readable result, with execution progress on stderr.
- Forensic reports are parsed into structured `report` objects. The outer
  `forensic-command-result.v2` carries the selected root, numeric process exit
  code, captured progress and report. The run report includes the exact output
  directory.
- Request errors list missing or unknown fields. Invalid request documents
  return 2; domain check failures return 1; unexpected command exceptions
  return 1.
- Registry path validation checks registry data directly, independent of a
  cached dispatch map. Registry-owned parsing is the default; only commands
  with their own complete parser declare handler ownership. The 85 native
  command entries were audited for duplicate option names.
- Delegated community and simulation parsers receive their own flags without
  dispatcher interference. Raw path options resolve against the caller
  directory. Unused global `--scenario-file`, `--suite`, and `--pack` options
  were removed rather than being accepted and ignored.
- Help and registry descriptions state the distinct comparison guarantees and
  whether commands execute scenarios or write artifacts.

## Verification

| Check | Result |
|---|---|
| Focused Clojure CLI tests: allocation CLI, protocol commands, registry validation, research, researcher, and validate | 55 tests, 352 assertions, 0 failures, 0 errors |
| `python3 -m pytest -q test/forensic_python/test_command_result.py test/forensic_python/test_reproduce_protocol_state.py` | 11 passed |
| `bin/prf commands validate` | `Command registry valid.` |
| Option contract audit | 75 registry-parsed native commands; 10 handler-owned parsers; no duplicate option names |
| `bin/prf compare a.json b.json --json` from an unrelated working directory with caller-relative files | Exit 0; canonical equivalence report parsed |
| `forensic run --json` from an unrelated working directory with a dry-run request | Exit 0; numeric exit code, exact output directory and captured progress verified |

The Clojure suite emits existing namespace-name warnings for `compile` and
`compare`; they do not fail the focused checks. Broader checks expose three
existing errors in the evidence EDN/JSON tests and one scenario parity mismatch:
the BB and JAR runs emitted different randomized attestation claim IDs. Those
failures are outside the option parser cleanup and remain open. The historical
[command implementation qualification](protocol-command-qualification.md)
remains tied to its recorded revisions and assurance gates.
