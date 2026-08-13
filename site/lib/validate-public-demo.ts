import type { PublicDemo, PublicDemoCheck } from './public-demo'

/**
 * Fail-closed validation for the public-demo.v1 artifact.
 *
 * The frontend must never render a demo whose required evidence is missing —
 * that would silently invent a successful-looking presentation. Every required
 * field is asserted here, and any missing/typed-wrong field throws at build
 * time (this module is imported only by server/static code paths).
 */

function isCheck(x: unknown): x is PublicDemoCheck {
  if (typeof x !== 'object' || x === null) return false
  const c = x as Record<string, unknown>
  return (
    typeof c.id === 'string' &&
    (c.status === 'pass' || c.status === 'fail')
  )
}

function fail(field: string): never {
  throw new Error(`public-demo.v1: missing or invalid required field: ${field}`)
}

function obj(v: unknown, field: string): Record<string, unknown> {
  if (typeof v !== 'object' || v === null) fail(field)
  return v as Record<string, unknown>
}

function str(v: unknown, field: string): string {
  if (typeof v !== 'string' || v.length === 0) fail(field)
  return v
}

function num(v: unknown, field: string): number {
  if (typeof v !== 'number') fail(field)
  return v
}

function bool(v: unknown, field: string): boolean {
  if (typeof v !== 'boolean') fail(field)
  return v
}

function assertEnvelope(x: Record<string, unknown>): void {
  if (x.schema !== 'public-demo.v1') fail('schema')
  const demo = obj(x.demo, 'demo')
  str(demo.id, 'demo.id')
  num(demo.version, 'demo.version')
  str(demo.question, 'demo.question')
  str(x.why, 'why')
  const evidence = obj(x.evidence, 'evidence')
  str(evidence['committed-hash'], 'evidence.committed-hash')
  if (!Array.isArray(evidence.lines)) fail('evidence.lines')
  if (!Array.isArray(evidence.checks) || !evidence.checks.every(isCheck))
    fail('evidence.checks')
  const source = obj(x.source, 'source')
  str(source.notebook, 'source.notebook')
  // P0 provenance: the page's facts are bound to one executable result.
  str(source['result-root'], 'source.result-root')
  str(source['input-root'], 'source.input-root')
  if (source['result-root'] !== evidence['committed-hash'])
    fail('source.result-root must equal evidence.committed-hash')
  const evidenceInput = evidence['request-hash'] ?? evidence['input-root']
  if (source['input-root'] !== evidenceInput)
    fail('source.input-root must equal the evidence input identity')
}

function assertNarrativeConsistency(x: Record<string, unknown>): void {
  const outcome = obj(x.outcome, 'outcome')
  bool(outcome.admitted, 'outcome.admitted')
  if (!Array.isArray(outcome['failed-checks'])) fail('outcome.failed-checks')
  const evidence = obj(x.evidence, 'evidence')
  const checks = evidence.checks as unknown[]
  const failing = checks
    .filter((c) => (c as Record<string, unknown>).status === 'fail')
    .map((c) => (c as Record<string, unknown>).id)
    .sort()
  const declared = (outcome['failed-checks'] as string[]).slice().sort()
  // P1 never-strengthen: a rejected outcome must be backed by a failing check,
  // and the declared failed-checks must match the failing evidence exactly.
  if (outcome.admitted === false) {
    if (failing.length === 0)
      fail('outcome.admitted=false but no evidence check failed')
    if (JSON.stringify(declared) !== JSON.stringify(failing))
      fail('outcome.failed-checks does not match failing evidence checks')
  } else if (failing.length > 0) {
    fail('outcome.admitted=true but evidence has failing checks')
  }
}


function assertLiquidityShortfall(x: Record<string, unknown>): void {
  const scenario = obj(x.scenario, 'scenario')
  const pool = obj(scenario.pool, 'scenario.pool')
  num(pool.available, 'scenario.pool.available')
  num(pool.requested, 'scenario.pool.requested')
  if (!Array.isArray(scenario.requests)) fail('scenario.requests')
  let sumRequested = 0
  let sumAllocated = 0
  let sumShortfall = 0
  for (const [i, r] of scenario.requests.entries()) {
    const row = obj(r, `scenario.requests[${i}]`)
    str(row.id, `scenario.requests[${i}].id`)
    sumRequested += num(row.requested, `scenario.requests[${i}].requested`)
    sumAllocated += num(row.allocated, `scenario.requests[${i}].allocated`)
    sumShortfall += num(row.shortfall, `scenario.requests[${i}].shortfall`)
  }
  const allocation = obj(x.allocation, 'allocation')
  const totalAllocated = num(allocation['total-allocated'], 'allocation.total-allocated')
  const conservation = obj(x.conservation, 'conservation')
  const cReq = num(conservation.requested, 'conservation.requested')
  const cAlloc = num(conservation.allocated, 'conservation.allocated')
  const cShort = num(conservation.shortfall, 'conservation.shortfall')
  // P1 single-source + never-strengthen: rows, totals, and conservation must
  // reconcile; otherwise facts were spliced from different executions.
  if (sumRequested !== pool.requested) fail('scenario.pool.requested != sum(requested)')
  if (sumAllocated !== totalAllocated) fail('allocation.total-allocated != sum(allocated)')
  if (sumShortfall !== cShort) fail('conservation.shortfall != sum(shortfall)')
  if (cReq !== sumRequested) fail('conservation.requested != sum(requested)')
  if (cAlloc !== totalAllocated) fail('conservation.allocated != total-allocated')
  if (cReq !== cAlloc + cShort) fail('conservation: requested != allocated + shortfall')
  bool(conservation.holds, 'conservation.holds')
}

function assertReorderedEvidence(x: Record<string, unknown>): void {
  const baseline = obj(x.baseline, 'baseline')
  str(baseline.value, 'baseline.value')
  bool(baseline.admitted, 'baseline.admitted')
  assertNarrativeConsistency(x)
}

/** Assert a public-demo artifact of any known kind, failing closed on any
 *  missing/typed-wrong required field. */
export function assertPublicDemo(x: unknown): asserts x is PublicDemo {
  const d = obj(x, 'artifact')
  assertEnvelope(d)
  const kind = (d.demo as Record<string, unknown>).id
  switch (kind) {

    case 'liquidity-shortfall':
      assertLiquidityShortfall(d)
      return
    case 'reordered-evidence':
      assertReorderedEvidence(d)
      return
    case 'current-head':
      assertReorderedEvidence(d)
      return
    default:
      fail(`unsupported demo kind: ${String(kind)}`)
  }
}
