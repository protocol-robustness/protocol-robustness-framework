'use client'

import { useState } from 'react'
import type { ReactNode } from 'react'
import styles from './demo-shell.module.css'

export type StepId =
  | 'scenario'
  | 'baseline'
  | 'change'
  | 'outcome'
  | 'why'
  | 'evidence'

export interface Step {
  id: StepId
  label: string
  node: ReactNode
}

export default function DemoShell({
  steps,
}: {
  steps: Step[]
}) {
  const [active, setActive] = useState<StepId>(steps[0].id)
  const activeIndex = steps.findIndex((s) => s.id === active)
  const current = steps[activeIndex]

  return (
    <div className={styles.story}>
      <nav className={styles.stepNav} aria-label="Demo steps">
        {steps.map((step, i) => (
          <button
            key={step.id}
            type="button"
            className={
              step.id === active ? styles.stepButtonActive : styles.stepButton
            }
            onClick={() => setActive(step.id)}
            aria-current={step.id === active ? 'step' : undefined}
          >
            {i + 1}. {step.label}
          </button>
        ))}
      </nav>
      <div key={current.id}>{current.node}</div>
    </div>
  )
}
