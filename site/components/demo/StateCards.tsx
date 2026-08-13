import type { NarrativeDemo, PublicDemo } from '../../lib/public-demo'
import { VerdictChip, ChangeChip } from './ScenarioCard'
import styles from './demo-cards.module.css'

export function BaselineCard({ demo }: { demo: NarrativeDemo }) {
  const b = demo.baseline
  const value = b.value
  return (
    <section className={styles.card} aria-label="Baseline result">
      <div className={styles.cardLabel}>Original result</div>
      <div className={styles.row}>
        <div className={styles.rowLabel}>
          {b.label}: <strong>{value}</strong>
        </div>
        <VerdictChip admitted={b.admitted} label={b.admitted ? 'ADMITTED' : 'NOT ADMITTED'} />
      </div>
      <p className={styles.cardNote}>
        The evidence is verified as-is. Every check in the closed-form battery
        passes.
      </p>
    </section>
  )
}

export function ChangeCard({ demo }: { demo: NarrativeDemo }) {
  const c = demo.change
  const fmtFrom = c.from
  const fmtTo = c.to
  const unit = ''
  return (
    <section className={styles.card} aria-label="The change">
      <div className={styles.cardLabel}>Change</div>
      <div className={styles.row}>
        <div className={styles.rowLabel}>
          {c.label}:{' '}
          <strong>
            {fmtFrom} {unit} → {fmtTo} {unit}
          </strong>
        </div>
        <ChangeChip label="CHANGED" />
      </div>
      <p className={styles.cardNote}>{c.detail}</p>
    </section>
  )
}

export function OutcomeCard({ demo }: { demo: NarrativeDemo }) {
  const o = demo.outcome
  return (
    <section className={styles.card} aria-label="Same check again">
      <div className={styles.cardLabel}>Same check again</div>
      <div className={styles.row}>
        <div className={styles.rowLabel}>
          The exact same check runs on the changed record.
        </div>
        <VerdictChip admitted={o.admitted} label={o.admitted ? 'ADMITTED' : 'NOT ADMITTED'} />
      </div>
      {!o.admitted && o['failed-checks'].length > 0 && (
        <p className={styles.cardNote}>
          Failing check: <code>{o['failed-checks'].join(', ')}</code>
        </p>
      )}
    </section>
  )
}

export function WhyCard({ demo }: { demo: Pick<PublicDemo, 'why'> }) {
  return (
    <section className={styles.card} aria-label="Why">
      <div className={styles.cardLabel}>Why</div>
      <p className={styles.why}>{demo.why}</p>
      <p className={styles.cardNote}>
        PRF does not rewrite the result as invalid from the start. It runs the
        same check on what is now recorded — and that check fails, because the
        record no longer matches its committed signature.
      </p>
    </section>
  )
}

export function CommitmentsCard({ demo }: { demo: NarrativeDemo }) {
  const cm = demo.commitments
  return (
    <section className={styles.card} aria-label="Committed expectations">
      <div className={styles.cardLabel}>Committed expectations</div>
      <ul className={styles.commitList}>
        <li>
          Baseline: <code>{cm.baseline}</code>
        </li>
        <li>
          After change: <code>{cm['after-change']}</code>
        </li>
      </ul>
      <p className={styles.cardNote}>
        The demo commits to these two verdicts. A deterministic assertion fails
        the build if the framework ever stops producing them.
      </p>
    </section>
  )
}

