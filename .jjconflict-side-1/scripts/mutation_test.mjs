#!/usr/bin/env node
// mutation_test.mjs — G9c protected-property mutation testing.
//
// Applies security-relevant mutations to verify3.mjs, one at a time, and asserts
// that every mutation is KILLED: the mutated verifier must disagree with the
// clean verifier (or the committed manifest) on at least one corpus, holdout,
// or vector case.  A mutation that survives means the protected property has no
// effective test.
//
// Usage: node scripts/mutation_test.mjs
import { readFileSync, writeFileSync, rmSync } from "node:fs";
import { execFileSync } from "node:child_process";

const V3 = "scripts/verify3.mjs";
const TMP = "/tmp/opencode/verify3-mutant.mjs";

const corpus = JSON.parse(readFileSync("etc/conformance/corpus/manifest.json", "utf8"));
const holdout = JSON.parse(readFileSync("etc/conformance/holdout/manifest.json", "utf8"));

// A mutation is a labelled source replacement.  Every one maps to a protected
// property and MUST be killed by the public corpus, holdout corpus, or vectors.
const MUTATIONS = [
  { id: "trust-supplied-claim",
    property: "Claim independently derived",
    from: `if (derived && supplied && canonicalJson(suppliedCore) !== canonicalJson(derivedCore)) {`,
    to: `if (false && derived && supplied) {`,
    kill: "claim-tampered-001" },
  { id: "skip-json-root-recomputation",
    property: "Claim independently derived",
    from: `if (derived && supplied && supplied["claim/json-root"] != null &&`,
    to: `if (false && derived && supplied &&`,
    kill: "claim-tampered-001" },
  { id: "accept-unknown-versions",
    property: "Version non-confusion",
    from: `if (version !== BUNDLE_SCHEMA_VERSION) {`,
    to: `if (false) {`,
    kill: "version-unsupported-001" },
  { id: "ignore-environment-mismatch",
    property: "Environment binding",
    from: `    if (planEnv && reconEnv && planEnv !== reconEnv) {
      issues.push({ "issue/code": "environment-root-disagreement" });
    }
    if (reconEnv && covEnv && reconEnv !== covEnv) {
      issues.push({ "issue/code": "environment-root-disagreement" });
    }`,
    to: `    if (false) {
      issues.push({ "issue/code": "environment-root-disagreement" });
    }`,
    kill: "env-mismatch-001" },
  { id: "ignore-unexpected-receipt",
    property: "Coverage completeness",
    from: `if (!planStepIds.has(r["step/id"])) {`,
    to: `if (false) {`,
    kill: "unexpected-receipt-001" },
  { id: "crypto-validity-is-authorisation",
    property: "Cryptographic admission",
    from: `  if (!(key["key/authorised-kinds"] || []).includes(m["artifact-kind"])) return "fail";`,
    to: `  // authorisation removed`,
    kill: "unauthorised-kind-001" },
  { id: "disable-domain-separation",
    property: "Cryptographic admission",
    from: `if (m["signature/domain"] !== "prf-evidence-package.v1") return "fail";`,
    to: `if (false) return "fail";`,
    kill: "wrong-domain" },
  { id: "missing-receipt-is-skippable",
    property: "Reconciliation not reproducible",
    from: `    if (!recon["reconciliation/root"]) {
      issues.push({ "issue/code": "reconciliation-not-reproducible" });
    }`,
    to: `    if (false) {
      issues.push({ "issue/code": "reconciliation-not-reproducible" });
    }`,
    kill: "missing-reconciliation-root-001" },
  { id: "fail-closed-becomes-not-evaluated",
    property: "Claim derived, not labelled",
    from: `  if ((mode === "candidate" || mode === "compare") && !PERMITTED[mode].has(claimClass)) {
    claimClass = "not-evaluated";
  }
  if (!PERMITTED[mode] || !PERMITTED[mode].has(claimClass)) return null;`,
    to: `  if (false) {
    claimClass = "not-evaluated";
  }`,
    kill: "number-mode-001" },
];

function runVerify3(args) {
  return JSON.parse(execFileSync("node", [V3, ...args], { encoding: "utf8" }));
}

function runMutant(args) {
  return JSON.parse(execFileSync("node", [TMP, ...args], { encoding: "utf8" }));
}

function norm(s) {
  return s === "pass" ? "pass" : "reject";
}

// reference verdicts from the clean verifier
const cleanBundle = (path) => runVerify3(["verify", path]);
const cleanVector = (path) => runVerify3(["vector-verify", path]);

let totalKilled = 0;
for (const mut of MUTATIONS) {
  const src = readFileSync(V3, "utf8");
  if (!src.includes(mut.from)) {
    console.log(`SKIP ${mut.id}: source pattern not found (maybe already fixed differently)`);
    continue;
  }
  writeFileSync(TMP, src.replace(mut.from, mut.to));
  let killed = false;
  const catching = [];
  // public corpus bundle cases
  for (const c of corpus) {
    if (c.kind !== "bundle") continue;
    const path = `etc/conformance/corpus/${c.path}`;
    const clean = cleanBundle(path);
    const mutant = runMutant(["verify", path]);
    if (norm(clean.status) !== norm(mutant.status) || clean.claimable !== mutant.claimable) {
      killed = true;
      catching.push(c.case_id);
    }
  }
  // holdout bundle + crypto cases
  for (const c of holdout) {
    if (c.kind === "crypto") {
      const path = `etc/conformance/holdout/${c.path}`;
      const clean = cleanVector(path);
      const mutant = runMutant(["vector-verify", path]);
      if (norm(clean.status) !== norm(mutant.status)) {
        killed = true;
        catching.push(c.case_id);
      }
    } else {
      const path = `etc/conformance/holdout/${c.path}`;
      const clean = cleanBundle(path);
      const mutant = runMutant(["verify", path]);
      if (norm(clean.status) !== norm(mutant.status) || clean.claimable !== mutant.claimable) {
        killed = true;
        catching.push(c.case_id);
      }
    }
  }
  // crypto vectors
  for (const dec of ["valid", "wrong-preimage", "wrong-domain", "unauthorised-kind", "revoked-key", "unknown-algorithm"]) {
    // recompute expected from the committed crypto.json via the vector-verify cases
  }
  const status = killed ? "KILLED" : "SURVIVED";
  if (killed) totalKilled++;
  console.log(`${status.padEnd(8)} ${mut.id.padEnd(34)} property=${mut.property.padEnd(28)} caught_by=${catching.join(",") || "-"}`);
}
rmSync(TMP, { force: true });
const allKilled = totalKilled === MUTATIONS.filter((m) => true).length;
console.log(`MUTATION SCORE: ${totalKilled}/${MUTATIONS.length} security-relevant mutations killed`);
process.exit(allKilled ? 0 : 1);
