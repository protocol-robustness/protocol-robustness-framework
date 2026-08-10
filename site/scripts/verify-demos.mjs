#!/usr/bin/env node
/**
 * verify-demos.mjs — executable contract for the public-demo.v1 artifacts.
 *
 * Runs against the committed generated JSON (not the site build) so the
 * generator/check boundary is exercised independently of Next.js. Mirrors the
 * Clojure-side validator (resolver-sim.demos.public.validate) and the TS
 * build-time validator. Fails with exit 1 on any violation.
 *
 * Locks the completion-bar claims:
 *   - Single-source provenance: result-root == evidence.committed-hash and
 *     input-root == the evidence input identity.
 *   - Cross-field consistency: rows/totals/conservation reconcile, so a future
 *     projection bug that splices facts from different executions is rejected.
 *   - Presentation conservatism: a rejected outcome requires a failing check;
 *     an admitted outcome forbids one.
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const generatedDir = join(root, 'generated', 'demos')

const failures = []
const checked = []

function fail(demo, field, detail) {
  failures.push(`${demo}: ${field} — ${detail}`)
}

function isNum(x) {
  return typeof x === 'number' && Number.isFinite(x)
}

function requireStr(obj, field, demo) {
  const v = obj[field]
  if (typeof v !== 'string' || v.length === 0) fail(demo, field, 'expected a non-empty string')
  return v
}

function requireNum(obj, field, demo) {
  if (!isNum(obj[field])) fail(demo, field, 'expected a number')
  return obj[field]
}

function assertProvenance(demo, d) {
  requireStr(d.source, 'result-root', demo)
  requireStr(d.source, 'input-root', demo)
  if (d.source['result-root'] !== d.evidence['committed-hash']) {
    fail(demo, 'source.result-root', 'must equal evidence.committed-hash')
  }
  const evidenceInput = d.evidence['request-hash'] ?? d.evidence['input-root']
  if (d.source['input-root'] !== evidenceInput) {
    fail(demo, 'source.input-root', 'must equal the evidence input identity')
  }
}

function assertNarrative(demo, d) {
  const failing = d.evidence.checks
    .filter((c) => c.status === 'fail')
    .map((c) => c.id)
    .sort()
  const declared = [...d.outcome['failed-checks']].sort()
  if (d.outcome.admitted === false) {
    if (failing.length === 0) fail(demo, 'outcome.admitted', 'false but no evidence check failed')
    if (JSON.stringify(declared) !== JSON.stringify(failing)) {
      fail(demo, 'outcome.failed-checks', 'does not match failing evidence checks')
    }
  } else if (failing.length > 0) {
    fail(demo, 'outcome.admitted', 'true but evidence has failing checks')
  }
}

function assertLiquidity(demo, d) {
  let sumReq = 0
  let sumAlloc = 0
  let sumShort = 0
  for (const r of d.scenario.requests) {
    sumReq += requireNum(r, 'requested', demo)
    sumAlloc += requireNum(r, 'allocated', demo)
    sumShort += requireNum(r, 'shortfall', demo)
  }
  const totalAlloc = requireNum(d.allocation, 'total-allocated', demo)
  const cReq = requireNum(d.conservation, 'requested', demo)
  const cAlloc = requireNum(d.conservation, 'allocated', demo)
  const cShort = requireNum(d.conservation, 'shortfall', demo)
  if (sumReq !== requireNum(d.scenario.pool, 'requested', demo)) fail(demo, 'pool.requested', '!= sum(requested)')
  if (sumAlloc !== totalAlloc) fail(demo, 'allocation.total-allocated', '!= sum(allocated)')
  if (sumShort !== cShort) fail(demo, 'conservation.shortfall', '!= sum(shortfall)')
  if (cReq !== sumReq) fail(demo, 'conservation.requested', '!= sum(requested)')
  if (cAlloc !== totalAlloc) fail(demo, 'conservation.allocated', '!= total-allocated')
  if (cReq !== cAlloc + cShort) fail(demo, 'conservation', 'requested != allocated + shortfall')
  if (d.conservation.holds !== true) fail(demo, 'conservation.holds', 'expected true')
}

const files = readdirSync(generatedDir).filter((f) => f.endsWith('.json'))
if (files.length === 0) fail('*', 'generated dir', 'no artifacts found')

for (const file of files) {
  const demo = file.replace(/\.json$/, '')
  const d = JSON.parse(readFileSync(join(generatedDir, file), 'utf8'))
  checked.push(demo)

  if (d.schema !== 'public-demo.v1') fail(demo, 'schema', `expected public-demo.v1, got ${d.schema}`)
  if (d.demo.id !== demo) fail(demo, 'demo.id', `expected ${demo}`)
  if (d.demo.version !== 1) fail(demo, 'demo.version', 'expected 1')
  requireStr(d.demo, 'question', demo)
  requireStr(d, 'why', demo)
  if (!Array.isArray(d.evidence?.checks) || d.evidence.checks.length === 0) {
    fail(demo, 'evidence.checks', 'missing or empty')
  }
  for (const c of d.evidence.checks) {
    if (c.status !== 'pass' && c.status !== 'fail') fail(demo, `evidence.checks[${c.id}].status`, 'must be pass|fail')
  }
  assertProvenance(demo, d)

  switch (demo) {
    case 'blocked-decision':
    case 'reordered-evidence':
      assertNarrative(demo, d)
      break
    case 'liquidity-shortfall':
      assertLiquidity(demo, d)
      break
    default:
      fail(demo, 'demo.id', 'unknown demo kind')
  }
}

if (failures.length > 0) {
  console.error('verify-demos FAILED:')
  for (const f of failures) console.error('  - ' + f)
  console.error(`checked: ${checked.join(', ')}`)
  process.exit(1)
}
console.log(`verify-demos OK: ${checked.join(', ')} (${checked.length} artifacts)`)
