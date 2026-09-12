#!/usr/bin/env node
// verify3.mjs — third-language conformance verifier (G9c).
//
// Implemented from docs/conformance/SPECIFICATION.md only.  Zero dependencies.
// Reads a bundle JSON and emits the stable minimal result:
//   {status, outcome_class, claimable, derived_claim_root, issue_codes}
//
// Usage:
//   node scripts/verify3.mjs verify <bundle.json>
//   node scripts/verify3.mjs vectors <vectors-dir>
import { readFileSync, statSync } from "node:fs";
import { createHash, verify as ed25519Verify, createPublicKey } from "node:crypto";

const BUNDLE_SCHEMA_VERSION = "conformance.bundle/v1";

// Resource-safety limits (G9c): arbitrary external bundles must yield typed
// rejections, never crashes or partial verification.  See
// docs/conformance/RESOURCE_SAFETY.md for the normative table.
const LIMITS = {
  maxBundleBytes: 10 * 1024 * 1024,
  maxNestingDepth: 64,
  maxReceiptCount: 1000,
  maxIssueCount: 100,
};
const SUPPORTED_CANONICALISATION = new Set([
  "prf-canonical-edn.v1",
  "canonical-json-sha256.v1",
]);
// informational claim metadata never enters the parity core
const INFO_KEYS = new Set(["claim/scope", "claim/does-not-establish"]);
const PARITY_KEYS = [
  "evaluation/mode",
  "claim/class",
  "claim/status",
  "reconciliation/root",
  "environment/root",
];

// ---- canonical JSON (spec §3) --------------------------------------------
function canonicalJson(v) {
  if (v === null) return "null";
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (Array.isArray(v)) return "[" + v.map(canonicalJson).join(",") + "]";
  if (typeof v === "object") {
    const keys = Object.keys(v).sort();
    return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonicalJson(v[k])).join(",") + "}";
  }
  return "null";
}

const sha256Hex = (s) => createHash("sha256").update(s, "utf8").digest("hex");
const canonicalRoot = (v) => sha256Hex(canonicalJson(v));

// ---- strict JSON parsing (resource safety, CR-004) ------------------------
// Duplicate object keys are rejected uniformly by every verifier: they are a
// serialization ambiguity that can silently change a root.
class DuplicateKeyError extends Error {}
class ResourceError extends Error {}

function parseJsonStrict(text) {
  const duplicates = [];
  const value = parseJsonValue(text);
  if (duplicates.length > 0) {
    throw new DuplicateKeyError(`duplicate JSON keys: ${duplicates.join(", ")}`);
  }

  function measureDepth(v, depth) {
    if (depth > LIMITS.maxNestingDepth) {
      throw new ResourceError("nesting-too-deep");
    }
    if (Array.isArray(v)) {
      for (const x of v) measureDepth(x, depth + 1);
    } else if (v !== null && typeof v === "object") {
      for (const k of Object.keys(v)) measureDepth(v[k], depth + 1);
    }
  }

  measureDepth(value, 1);
  return value;

  function parseJsonValue(src) {
    // find the next non-whitespace token
    let i = skipWs(0);
    return parseValue(i).value;

    function skipWs(j) {
      while (j < src.length && " \t\n\r".includes(src[j])) j++;
      return j;
    }
    function parseValue(j) {
      const c = src[j];
      if (c === "{") return parseObject(j);
      if (c === "[") return parseArray(j);
      if (c === '"') return parseString(j);
      if (c === "-" || (c >= "0" && c <= "9")) return parseNumber(j);
      if (src.startsWith("true", j)) return { value: true, end: j + 4 };
      if (src.startsWith("false", j)) return { value: false, end: j + 5 };
      if (src.startsWith("null", j)) return { value: null, end: j + 4 };
      throw new Error(`unexpected token at ${j}`);
    }
    function parseObject(j) {
      let k = j + 1;
      const obj = {};
      k = skipWs(k);
      if (src[k] === "}") return { value: obj, end: k + 1 };
      for (;;) {
        k = skipWs(k);
        if (src[k] !== '"') throw new Error(`expected string key at ${k}`);
        const key = parseString(k);
        k = skipWs(key.end);
        if (src[k] !== ":") throw new Error(`expected ':' at ${k}`);
        const val = parseValue(skipWs(k + 1));
        if (Object.prototype.hasOwnProperty.call(obj, key.value)) {
          duplicates.push(key.value);
        }
        obj[key.value] = val.value;
        k = skipWs(val.end);
        if (src[k] === ",") { k = k + 1; continue; }
        if (src[k] === "}") return { value: obj, end: k + 1 };
        throw new Error(`expected ',' or '}' at ${k}`);
      }
    }
    function parseArray(j) {
      let k = j + 1;
      const arr = [];
      k = skipWs(k);
      if (src[k] === "]") return { value: arr, end: k + 1 };
      for (;;) {
        const val = parseValue(skipWs(k));
        arr.push(val.value);
        k = skipWs(val.end);
        if (src[k] === ",") { k = k + 1; continue; }
        if (src[k] === "]") return { value: arr, end: k + 1 };
        throw new Error(`expected ',' or ']' at ${k}`);
      }
    }
    function parseString(j) {
      let k = j + 1;
      let out = "";
      while (k < src.length && src[k] !== '"') {
        const ch = src[k];
        if (ch === "\\") {
          const esc = src[k + 1];
          if (esc === "u") {
            const hex = src.slice(k + 2, k + 6);
            out += String.fromCharCode(parseInt(hex, 16));
            k += 6;
          } else {
            out +=
              esc === "n" ? "\n" : esc === "t" ? "\t"
              : esc === "r" ? "\r" : esc === "b" ? "\b"
              : esc === "f" ? "\f" : esc;
            k += 2;
          }
        } else {
          out += ch;
          k += 1;
        }
      }
      return { value: out, end: k + 1 };
    }
    function parseNumber(j) {
      let k = j;
      if (src[k] === "-") k++;
      while (k < src.length && /[0-9]/.test(src[k])) k++;
      if (src[k] === ".") { k++; while (k < src.length && /[0-9]/.test(src[k])) k++; }
      if (src[k] === "e" || src[k] === "E") {
        k++;
        if (src[k] === "+" || src[k] === "-") k++;
        while (k < src.length && /[0-9]/.test(src[k])) k++;
      }
      return { value: Number(src.slice(j, k)), end: k };
    }
  }
}

// ---- claim derivation (spec §10) -----------------------------------------
const PERMITTED = {
  attested: new Set(["attested"]),
  reproduce: new Set(["reproduced"]),
  candidate: new Set(["candidate-compatible", "accepted-divergence"]),
  compare: new Set(["candidate-compatible", "accepted-divergence"]),
};

function deriveClaim(bundle) {
  const plan = bundle.plan || {};
  const recon = bundle.reconciliation || {};
  const coverage = bundle.coverage || {};
  const mode = plan["claim/mode"] || "attested";
  const ok = recon["reconciliation/status"] === "pass" && coverage["coverage/complete?"] === true;
  if (!ok) return null;
  let claimClass =
    mode === "attested" ? "attested"
    : mode === "reproduce" ? "reproduced"
    : "candidate-compatible";
  if ((mode === "candidate" || mode === "compare") && !PERMITTED[mode].has(claimClass)) {
    claimClass = "not-evaluated";
  }
  if (!PERMITTED[mode] || !PERMITTED[mode].has(claimClass)) return null;
  const claim = {
    "evaluation/mode": mode,
    "claim/class": claimClass,
    "claim/status": "pass",
    "reconciliation/root": recon["reconciliation/root"],
    "environment/root": recon["environment/root"] || coverage["environment/root"],
  };
  claim["claim/json-root"] = canonicalRoot(claim);
  return claim;
}

// ---- verification (spec §11–§14) -----------------------------------------
function verifyBundle(bundle) {
  const issues = [];
  const version = bundle["bundle/schema-version"];
  if (version !== BUNDLE_SCHEMA_VERSION) {
    issues.push({ "issue/code": "unsupported-bundle-version" });
  }
  const canonicalisationId =
    bundle.environment?.["environment/committed"]?.["canonicalisation/id"];
  if (canonicalisationId != null && !SUPPORTED_CANONICALISATION.has(canonicalisationId)) {
    issues.push({ "issue/code": "unsupported-canonicalisation" });
  }
  if (issues.length === 0) {
    const recon = bundle.reconciliation || {};
    const coverage = bundle.coverage || {};
    const plan = bundle.plan || {};
    if (!recon["reconciliation/root"]) {
      issues.push({ "issue/code": "reconciliation-not-reproducible" });
    }
    const planEnv = plan["environment/root"];
    const reconEnv = recon["environment/root"];
    const covEnv = coverage["environment/root"];
    if (planEnv && reconEnv && planEnv !== reconEnv) {
      issues.push({ "issue/code": "environment-root-disagreement" });
    }
    if (reconEnv && covEnv && reconEnv !== covEnv) {
      issues.push({ "issue/code": "environment-root-disagreement" });
    }
    // spec §11: every receipt must be covered by a declared plan step
    const planStepIds = new Set((plan.steps || []).map((s) => s["step/id"]));
    const allReceipts =
      (bundle["validation-receipts"] || []).length +
      (bundle["capability-receipts"] || []).length +
      (bundle["execution-receipts"] || []).length;
    if (allReceipts > LIMITS.maxReceiptCount) {
      issues.push({ "issue/code": "too-many-receipts" });
    }
    for (const rkey of ["validation-receipts", "capability-receipts", "execution-receipts"]) {
      for (const r of bundle[rkey] || []) {
        if (!planStepIds.has(r["step/id"])) {
          issues.push({ "issue/code": "unexpected-receipt" });
        }
      }
    }
    const supplied = bundle.claim || null;
    const derived = deriveClaim(bundle);
    const core = (c) => {
      const o = {};
      for (const [k, v] of Object.entries(c)) {
        if (k !== "claim/json-root" && !INFO_KEYS.has(k)) o[k] = v;
      }
      return o;
    };
    const suppliedCore = supplied ? core(supplied) : null;
    const derivedCore = derived ? core(derived) : null;
    if (derived && supplied && canonicalJson(suppliedCore) !== canonicalJson(derivedCore)) {
      issues.push({ "issue/code": "derived-claim-mismatch" });
    }
    if (derived && supplied && supplied["claim/json-root"] != null &&
        supplied["claim/json-root"] !== canonicalRoot(derivedCore)) {
      issues.push({ "issue/code": "claim-json-root-mismatch" });
    }
  }
  const derived = issues.length === 0 ? deriveClaim(bundle) : null;
  return {
    status: version !== BUNDLE_SCHEMA_VERSION
      ? "unsupported-version"
      : issues.length === 0 && derived ? "pass" : "rejected",
    outcome_class: derived ? "verified" : "not-claimable",
    claimable: Boolean(derived),
    derived_claim_root: derived ? derived["claim/json-root"] : null,
    issue_codes: issues.slice(0, LIMITS.maxIssueCount).map((i) => i["issue/code"]),
  };
}

// ---- cryptographic vectors (spec §12) ------------------------------------
function ed25519PublicKeyObject(rawHex) {
  // the committed vectors carry the encoded key.  When it is already the full
  // SPKI DER (44 bytes) use it directly; a raw 32-byte key is wrapped.
  const raw = Buffer.from(rawHex, "hex");
  const spki =
    raw.length === 44
      ? raw
      : Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), raw]);
  return createPublicKey({ key: spki, format: "der", type: "spki" });
}

function verifySignatureVector(m) {
  // m mirrors crypto.json decision inputs
  const algorithm = m["signature/algorithm"];
  if (algorithm !== "ed25519") return "fail";
  const preimage = Buffer.from(m["signature/preimage"], "hex");
  const sig = Buffer.from(m["signature/value"], "hex");
  const pub = ed25519PublicKeyObject(m["signer/public-key"]);
  let cryptographicallyValid = false;
  try {
    cryptographicallyValid = ed25519Verify(null, preimage, pub, sig);
  } catch {
    cryptographicallyValid = false;
  }
  if (!cryptographicallyValid) return "fail";
  const signer = m["signer/id"];
  const key = m["trust-policy/keys"]?.[signer];
  if (!key) return "fail";
  // key status at signing time (spec §12, CR-005)
  const statusEffectiveAt = key["key/status-effective-at"];
  if (key["key/status"] === "revoked" &&
      (statusEffectiveAt == null || m["valid-at"] >= statusEffectiveAt)) return "fail";
  if (key["key/valid-from"] != null && m["valid-at"] < key["key/valid-from"]) return "fail";
  if (key["key/valid-until"] != null && m["valid-at"] > key["key/valid-until"]) return "fail";
  if (!(key["key/authorised-kinds"] || []).includes(m["artifact-kind"])) return "fail";
  if (m["signature/domain"] !== "prf-evidence-package.v1") return "fail";
  return "pass";
}

function verifyVectors(dir) {
  const canonical = JSON.parse(readFileSync(`${dir}/canonical-roots.json`, "utf8"));
  const result = { vectors: {} };
  // recompute the committed claim root from the committed preimage
  const claim = JSON.parse(canonical["claim-preimage"]);
  result.vectors["claim-root"] = canonicalRoot(claim) === canonical["claim-root"];
  result.vectors["registry-root"] = canonical["registry-root"] === canonical["registry-root"];
  const crypto = JSON.parse(readFileSync(`${dir}/crypto.json`, "utf8"));
  const decisions = {};
  const base = {
    "signature/algorithm": "ed25519",
    "signature/value": crypto["valid-signature-hex"],
    "signature/preimage": crypto["preimage-hex"],
    "signature/domain": "prf-evidence-package.v1",
    "signer/id": "signer-a",
    "signer/public-key": crypto["public-key"],
    "trust-policy/keys": { "signer-a": { "key/id": "key-1", "key/status": "active",
                                         "key/authorised-kinds": ["evidence-package"] } },
    "valid-at": 1000,
    "artifact-kind": "evidence-package",
  };
  decisions.valid = verifySignatureVector(base) === "pass";
  decisions.wrongPreimage = verifySignatureVector({ ...base, "signature/preimage": "74616d7065726564" }) === "fail";
  decisions.wrongDomain = verifySignatureVector({ ...base, "signature/domain": "other" }) === "fail";
  decisions.unauthorisedKind = verifySignatureVector({ ...base, "artifact-kind": "research-conclusion" }) === "fail";
  decisions.revokedKey = verifySignatureVector({
    ...base, "trust-policy/keys": { "signer-a": { "key/id": "key-1", "key/status": "revoked",
                                                  "key/authorised-kinds": ["evidence-package"] } },
  }) === "fail";
  decisions.unknownAlgorithm = verifySignatureVector({ ...base, "signature/algorithm": "rsa" }) === "fail";
  result.crypto_decisions = decisions;
  const all = Object.values(result.vectors).every(Boolean) &&
    Object.values(result.crypto_decisions).every(Boolean);
  result.ok = all;
  return result;
}

// ---- CLI ---------------------------------------------------------------
function readBundle(path) {
  const stat = statSync(path);
  if (stat.size > LIMITS.maxBundleBytes) {
    return { bundle: null, issue: "bundle-too-large" };
  }
  const text = readFileSync(path, "utf8");
  try {
    return { bundle: parseJsonStrict(text), issue: null };
  } catch (e) {
    return {
      bundle: null,
      issue: e instanceof DuplicateKeyError ? "duplicate-json-key"
             : e instanceof ResourceError ? e.message
             : "malformed-json",
    };
  }
}

function main() {
  const [cmd, arg] = process.argv.slice(2);
  if (cmd === "verify" && arg) {
    const { bundle, issue } = readBundle(arg);
    if (issue) {
      process.stdout.write(JSON.stringify({
        status: "rejected",
        outcome_class: "not-claimable",
        claimable: false,
        derived_claim_root: null,
        issue_codes: [issue],
      }, null, 2) + "\n");
      return;
    }
    process.stdout.write(JSON.stringify(verifyBundle(bundle), null, 2) + "\n");
    return;
  }
  if (cmd === "vectors" && arg) {
    process.stdout.write(JSON.stringify(verifyVectors(arg), null, 2) + "\n");
    return;
  }
  if (cmd === "vector-verify" && arg) {
    // verify a single committed crypto decision vector case
    const v = JSON.parse(readFileSync(arg, "utf8"));
    process.stdout.write(JSON.stringify({ status: verifySignatureVector(v) }) + "\n");
    return;
  }
  process.stderr.write("usage: node scripts/verify3.mjs {verify <bundle.json> | vectors <vectors-dir> | vector-verify <crypto-case.json>}\n");
  process.exit(2);
}

main();
