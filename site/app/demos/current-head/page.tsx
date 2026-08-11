import type { Metadata } from 'next'
import DemoShell from '@/components/demo/DemoShell'
import { BaselineCard, ChangeCard, OutcomeCard, WhyCard, CommitmentsCard } from '@/components/demo/StateCards'
import EvidencePanel from '@/components/demo/EvidencePanel'
import TechnicalProofCTA from '@/components/demo/TechnicalProofCTA'
import { assertPublicDemo } from '@/lib/validate-public-demo'
import type { CurrentHeadDemo } from '@/lib/public-demo'
import raw from '@/generated/demos/current-head.json'
import styles from './page.module.css'
import records from './records.module.css'

export const metadata: Metadata = {
  title: 'Current Head',
  description:
    'Can a resubmission extend an older, superseded result? The real chain facade refuses it and leaves the current verified head unchanged.',
}

assertPublicDemo(raw)
const demo = raw as CurrentHeadDemo

export default function CurrentHeadPage() {
  return (
    <div className={styles.page}>
      <span className={styles.kicker}>Admission</span>
      <h1 className={styles.title}>Current Head</h1>
      <p className={styles.question}>{demo.demo.question}</p>

      <DemoShell
        steps={[
          { id: 'scenario', label: 'Verified history', node: <HistoryCard demo={demo} /> },
          { id: 'baseline', label: 'Current head', node: <BaselineCard demo={demo} /> },
          { id: 'change', label: 'Stale submission', node: <ChangeCard demo={demo} /> },
          { id: 'outcome', label: 'Admission result', node: <OutcomeCard demo={demo} /> },
          { id: 'why', label: 'Why', node: <WhyCard demo={demo} /> },
          { id: 'evidence', label: 'Evidence', node: <><EvidencePanel demo={demo} /><div className={styles.spacer} /><CommitmentsCard demo={demo} /></> },
        ]}
      />

      <div className={styles.proofWrap}><TechnicalProofCTA demo={demo} /></div>
    </div>
  )
}

function HistoryCard({ demo }: { demo: CurrentHeadDemo }) {
  return (
    <section className={records.card} aria-label="Verified resubmission history">
      <div className={records.cardLabel}>Verified history</div>
      <p className={records.lede}>The chain has one current head: only that receipt may receive the next successor.</p>
      <ol className={records.order}>
        {demo.scenario.records.items.map((item, i) => <li key={item}><span className={records.seq}>{i + 1}</span><span className={records.item}>{item}</span></li>)}
      </ol>
    </section>
  )
}
