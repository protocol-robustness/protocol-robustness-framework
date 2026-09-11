#!/usr/bin/env node
// corpus_gate.mjs — G9c gate for the third-language verifier (verify3.mjs).
//
// Cross-verifier contract (MUST match exactly for every bundle case):
//   status normalization and claimable.
// Reference issue codes (manifest.expected_issue_codes) are asserted against
// the reference verifier; other verifiers MUST reject with a compatible
// non-empty code set and MUST NOT be claimable.
//
// Usage: node scripts/corpus_gate.mjs [corpus-dir]
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

const corpusDir = process.argv[2] || "etc/conformance/corpus";
const verify3 = "scripts/verify3.mjs";

function norm(s) {
  if (s === "pass") return "pass";
  return "reject";
}

const manifest = JSON.parse(readFileSync(join(corpusDir, "manifest.json"), "utf8"));
let ok = true;
const rows = [];
for (const c of manifest) {
  if (c.kind !== "bundle") continue;
  const out = execFileSync("node", [verify3, "verify", join(corpusDir, c.path)], {
    encoding: "utf8",
  });
  const r = JSON.parse(out);
  const statusMatch = norm(c.expected_status) === norm(r.status);
  const claimableMatch = c.claimable === r.claimable;
  const rejectsCompatible =
    c.expected_status === "pass" || (r.issue_codes.length > 0 && !r.claimable);
  const match = statusMatch && claimableMatch && rejectsCompatible;
  ok = ok && match;
  rows.push({
    case_id: c.case_id,
    expected: c.expected_status,
    status: r.status,
    claimable: r.claimable,
    codes: r.issue_codes,
    ok: match,
  });
}
for (const r of rows) {
  console.log(
    `${r.case_id.padEnd(28)} expected=${r.expected.padEnd(6)} js=${r.status.padEnd(18)} ` +
    `claimable=${r.claimable} codes=${JSON.stringify(r.codes)} ${r.ok ? "OK" : "FAIL"}`,
  );
}
console.log("PUBLIC CORPUS GATE (verify3.mjs):", ok ? "PASS" : "FAIL");
process.exit(ok ? 0 : 1);
