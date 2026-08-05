#!/usr/bin/env node
// holdout_gate.mjs — G9c private-holdout gate.
//
// Drives the three verifiers (Clojure CLI, Python, JS) over the holdout corpus
// and asserts the manifest contract per case.  The holdout set is deliberately
// excluded from the public corpus root and the release artifact, and MUST stay
// unpublished until an independent submission has passed it.
//
// Usage: node scripts/holdout_gate.mjs [holdout-dir]
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const dir = process.argv[2] || "etc/conformance/holdout";
const manifest = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));

function norm(s) {
  return s === "pass" ? "pass" : "reject";
}

function run(verifier, path) {
  let res;
  if (verifier === "js") {
    res = spawnSync("node", ["scripts/verify3.mjs", "verify", path], { encoding: "utf8", timeout: 120000 });
  } else if (verifier === "python") {
    res = spawnSync("python3", ["scripts/bundle_verify.py", path], { encoding: "utf8", timeout: 120000 });
  }
  const d = JSON.parse(res.stdout);
  const status = verifier === "python" ? d["verification/status"] : d.status;
  return { status, claimable: d.claimable ?? d["claimable?"] ?? false, codes: d.issue_codes || [] };
}

function runJsCrypto(path) {
  const r = JSON.parse(spawnSync("node", ["scripts/verify3.mjs", "vector-verify", path],
                                { encoding: "utf8", timeout: 120000 }).stdout);
  return { status: r.status === "pass" ? "pass" : "rejected",
           claimable: r.status === "pass", codes: [] };
}

// batch: one Clojure JVM evaluates every case it is required for
const clojureCases = manifest.filter((c) => c.required_verifiers.includes("clojure"));
const clj = spawnSync("clojure", ["-M:test:with-sew", "-i", "scripts/holdout_batch.clj", dir],
                      { encoding: "utf8", timeout: 300000 });
const cljByCase = new Map();
for (const line of (clj.stdout || "").split("\n")) {
  const [id, status, claimable] = line.trim().split("|");
  if (id) cljByCase.set(id, { status, claimable: claimable === "true" });
}

let ok = true;
for (const c of manifest) {
  const expected = norm(c.expected_status);
  const rows = [];
  for (const v of c.required_verifiers) {
    let r;
    if (c.kind === "crypto") {
      r = v === "js" ? runJsCrypto(join(dir, c.path)) : cljByCase.get(c.case_id);
    } else {
      r = v === "clojure" ? cljByCase.get(c.case_id) : run(v, join(dir, c.path));
    }
    const pass = r && norm(r.status) === expected && c.claimable === r.claimable;
    ok = ok && Boolean(pass);
    rows.push(`${v}=${r ? r.status : "MISSING"}${pass ? "" : "!!"}`);
  }
  console.log(`${c.case_id.padEnd(32)} expected=${expected.padEnd(6)} ${rows.join("  ")}`);
}
console.log("HOLDOUT GATE:", ok ? "PASS" : "FAIL");
process.exit(ok ? 0 : 1);
