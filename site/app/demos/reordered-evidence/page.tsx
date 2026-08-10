import type { Metadata } from 'next'
import DemoShell from '@/components/demo/DemoShell'
import { BaselineCard, ChangeCard, OutcomeCard, WhyCard, CommitmentsCard } from '@/components/demo/StateCards'
import EvidencePanel from '@/components/demo/EvidencePanel'
import TechnicalProofCTA from '@/components/demo/TechnicalProofCTA'
import { assertPublicDemo } from '@/lib/validate-public-demo'
import type { ReorderedEvidenceDemo } from '@/lib/public-demo'
import raw from '@/generated/demos/reordered-evidence.json'
import styles from './page.module.css'
import styles2 from './records.module.css'

export const metadata: Metadata = {
  title: 'Reordered Evidence',
  description:
    'Does the same evidence in a different order mean the same thing? The same chain verifier now rejects it.',
}

// Fail closed at build time: this throws if the generated artifact is missing
// any required evidence, so the page can never silently render an empty shell.
assertPublicDemo(raw)
const demo = raw as ReorderedEvidenceDemo

export default function ReorderedEvidencePage() {
  return (
    <div className={styles.page}>
      <span className={styles.kicker}>Demo</span>
      <h1 className={styles.title}>Reordered Evidence</h1>
      <p className={styles.question}>{demo.demo.question}</p>

      <DemoShell
        steps={[
          {
            id: 'scenario',
            label: 'Evidence in order',
            node: <RecordsCard demo={demo} />,
          },
          {
            id: 'baseline',
            label: 'Verified',
            node: <BaselineCard demo={demo} />,
          },
          {
            id: 'change',
            label: 'Reorder',
            node: <ChangeCard demo={demo} />,
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

function RecordsCard({ demo }: { demo: ReorderedEvidenceDemo }) {
  return (
    <section className={styles2.card} aria-label="Evidence records">
      <div className={styles2.cardLabel}>Evidence records</div>
      <p className={styles2.lede}>
        Three evidence items are committed in a chain, each bound to its
        position.
      </p>
      <ol className={styles2.order}>
        {demo.scenario.records.items.map((item, i) => (
          <li key={item}>
            <span className={styles2.seq}>{i + 1}</span>
            <span className={styles2.item}>{item}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
