import type { Metadata } from 'next'
import Link from 'next/link'
import styles from './demos.module.css'

export const metadata: Metadata = {
  title: 'Demos',
  description:
    'See how protocols behave under failures, disputes, economic stress and adversarial conditions — with the evidence behind every result.',
}

export default function DemosPage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Demos</h1>
      <p className={styles.lede}>
        One question, one scenario, one consequence — each backed by the real
        framework and inspectable down to the evidence.
      </p>

      <section className={styles.grid}>
        <Link href="/demos/current-head" className={styles.card}>
          <span className={styles.cardTag}>Featured · admission</span>
          <h2>Current Head</h2>
          <p>
            Can a resubmission extend an older, superseded result? The chain
            refuses it and leaves the verified head unchanged.
          </p>
          <span className={styles.cardCta}>See the demo →</span>
        </Link>


        <Link href="/demos/liquidity-shortfall" className={styles.card}>
          <span className={styles.cardTag}>Economic stress</span>
          <h2>Liquidity Shortfall</h2>
          <p>
            What happens when $100 of legitimate requests compete for $70 of
            liquidity? The allocation rule makes it visible.
          </p>
          <span className={styles.cardCta}>See the demo →</span>
        </Link>

        <Link href="/demos/reordered-evidence" className={styles.card}>
          <span className={styles.cardTag}>Provenance</span>
          <h2>Reordered Evidence</h2>
          <p>
            Does the same evidence in a different order mean the same thing? The
            same chain verifier now rejects it.
          </p>
          <span className={styles.cardCta}>See the demo →</span>
        </Link>
      </section>
    </div>
  )
}
