import type { Metadata } from 'next'
import DemoShell from '@/components/demo/DemoShell'
import { PoolCard, AllocationTable, ConservationCard } from '@/components/demo/AllocationCards'
import EvidencePanel from '@/components/demo/EvidencePanel'
import TechnicalProofCTA from '@/components/demo/TechnicalProofCTA'
import { WhyCard } from '@/components/demo/StateCards'
import { assertPublicDemo } from '@/lib/validate-public-demo'
import type { LiquidityShortfallDemo } from '@/lib/public-demo'
import raw from '@/generated/demos/liquidity-shortfall.json'
import styles from './page.module.css'

export const metadata: Metadata = {
  title: 'Liquidity Shortfall',
  description:
    'What happens when $100 of legitimate requests compete for $70 of liquidity? The real allocation engine makes the treatment visible.',
}

// Fail closed at build time: this throws if the generated artifact is missing
// any required evidence, so the page can never silently render an empty shell.
assertPublicDemo(raw)
const demo = raw as LiquidityShortfallDemo

export default function LiquidityShortfallPage() {
  return (
    <div className={styles.page}>
      <span className={styles.kicker}>Demo</span>
      <h1 className={styles.title}>Liquidity Shortfall</h1>
      <p className={styles.question}>{demo.demo.question}</p>

      <DemoShell
        steps={[
          {
            id: 'scenario',
            label: 'Pool',
            node: <PoolCard demo={demo} />,
          },
          {
            id: 'baseline',
            label: 'Allocation',
            node: <AllocationTable demo={demo} />,
          },
          {
            id: 'outcome',
            label: 'Conservation',
            node: <ConservationCard demo={demo} />,
          },
          {
            id: 'why',
            label: 'Why',
            node: <WhyCard demo={demo} />,
          },
          {
            id: 'evidence',
            label: 'Evidence',
            node: <EvidencePanel demo={demo} />,
          },
        ]}
      />

      <div className={styles.proofWrap}>
        <TechnicalProofCTA demo={demo} />
      </div>
    </div>
  )
}
