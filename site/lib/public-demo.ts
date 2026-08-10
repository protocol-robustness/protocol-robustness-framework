export interface PublicDemoCheck {
  id: string
  status: 'pass' | 'fail'
  detail?: string
}

export interface PublicDemoEvidence {
  'committed-hash': string
  'input-root'?: string
  'request-hash'?: string
  lines: [string, string][]
  checks: PublicDemoCheck[]
}

export interface PublicDemoSource {
  notebook: string
  'demo-notebook': string
  cli: string
  'scenario-ns': string
  'projection-ns': string
  schema: string
  'result-root': string
  'input-root': string
}

interface PublicDemoEnvelope {
  schema: 'public-demo.v1'
  demo: {
    id: string
    version: number
    question: string
  }
  why: string
  evidence: PublicDemoEvidence
  source: PublicDemoSource
}

export interface BlockedDecisionDemo extends PublicDemoEnvelope {
  demo: { id: 'blocked-decision'; version: number; question: string }
  scenario: {
    escrow: { held: number; unit: string; label: string }
  }
  baseline: {
    label: string
    value: number
    unit: string
    admitted: boolean
  }
  change: {
    label: string
    from: number
    to: number
    unit: string
    detail: string
  }
  outcome: {
    admitted: boolean
    'failed-checks': string[]
  }
  commitments: {
    baseline: string
    'after-change': string
  }
}

export interface LiquidityShortfallDemo extends PublicDemoEnvelope {
  demo: { id: 'liquidity-shortfall'; version: number; question: string }
  scenario: {
    pool: { available: number; unit: string; requested: number }
    requests: {
      id: string
      requested: number
      allocated: number
      shortfall: number
    }[]
  }
  allocation: {
    'total-allocated': number
    'unallocated-residual': number
  }
  conservation: {
    requested: number
    allocated: number
    shortfall: number
    holds: boolean
  }
  commitments: {
    'pool-fully-allocated': boolean
  }
}

export interface ReorderedEvidenceDemo extends PublicDemoEnvelope {
  demo: { id: 'reordered-evidence'; version: number; question: string }
  scenario: {
    records: { order: string; items: string[] }
  }
  baseline: {
    label: string
    value: string
    admitted: boolean
  }
  change: {
    label: string
    from: string
    to: string
    detail: string
  }
  outcome: {
    admitted: boolean
    'failed-checks': string[]
  }
  commitments: {
    baseline: string
    'after-change': string
  }
}

export type PublicDemo =
  | BlockedDecisionDemo
  | LiquidityShortfallDemo
  | ReorderedEvidenceDemo

/** The state-transition narrative shape shared by blocked-decision and
 *  reordered-evidence (baseline → change → outcome → why → commitments). */
export type NarrativeDemo = BlockedDecisionDemo | ReorderedEvidenceDemo

