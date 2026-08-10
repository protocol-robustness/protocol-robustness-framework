import type { Metadata } from 'next'
import DemoShell from '@/components/demo/DemoShell'
import { ScenarioSummary, BaselineCard, ChangeCard, OutcomeCard, WhyCard, CommitmentsCard } from '@/components/demo/StateCards'
import StateTransition from '@/components/demo/StateTransition'
import EvidencePanel from '@/components/demo/EvidencePanel'
import TechnicalProofCTA from '@/components/demo/TechnicalProofCTA'
import { assertPublicDemo } from '@/lib/validate-public-demo'
import raw from '@/generated/demos/blocked-decision.json'
import type { BlockedDecisionDemo } from '@/lib/public-demo'
import styles from './page.module.css'

export const metadata: Metadata = {
  title: 'Blocked Decision',
  description:
    'Can a verified result be changed? A result is verified, the recorded amount is edited, and the same check now rejects it. Computed by the real framework.',
}

// Fail closed at build time: this throws if the generated artifact is missing
// any required evidence, so the page can never silently render an empty shell.
assertPublicDemo(raw)
const demo = raw as BlockedDecisionDemo

export default function BlockedDecisionPage() {
  return (
    <div className={styles.page}>
      <span className={styles.kicker}>Demo</span>
      <h1 className={styles.title}>Blocked Decision</h1>
      <p className={styles.question}>{demo.demo.question}</p>

      <DemoShell
        steps={[
          {
            id: 'scenario',
            label: 'Scenario',
            node: <ScenarioSummary demo={demo} />,
          },
          {
            id: 'baseline',
            label: 'Verified',
            node: <BaselineCard demo={demo} />,
          },
          {
            id: 'change',
            label: 'Change',
            node: (
              <>
                <ChangeCard demo={demo} />
                <div className={styles.spacer} />
                <StateTransition
                  from={demo.change.from.toLocaleString('en-US')}
                  to={demo.change.to.toLocaleString('en-US')}
                  unit={demo.change.unit}
                  label={demo.scenario.escrow.label}
                  signature={demo.evidence['committed-hash']}
                />
              </>
            ),
          },
          {
            id: 'outcome',
            label: 'Same check again',
            node: <OutcomeCard demo={demo} />,
          },
          {
            id: 'why',
            label: 'Why',
            node: <WhyCard demo={demo} />,
          },
          {
            id: 'evidence',
            label: 'Evidence',
            node: (
              <>
                <EvidencePanel demo={demo} />
                <div className={styles.spacer} />
                <CommitmentsCard demo={demo} />
              </>
            ),
          },
        ]}
      />

      <div className={styles.proofWrap}>
        <TechnicalProofCTA demo={demo} />
      </div>
    </div>
  )
}
