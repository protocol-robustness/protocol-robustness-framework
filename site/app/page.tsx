import Link from 'next/link'
import styles from './home.module.css'

export default function Home() {
  return (
    <div className={styles.page}>
      <section className={styles.hero}>
        <div className={styles.heroInner}>
          <img className={styles.logo} src="/prf-logo.png" alt="PRF logo" />
          <span className={styles.eyebrow}>Protocol robustness, made visible</span>
          <h1 className={styles.title}>
            Making protocol behaviour visible before failure becomes expensive.
          </h1>
          <p className={styles.tagline}>
            See how protocols behave under failures, disputes, economic stress and
            adversarial conditions — and inspect the evidence behind every result.
          </p>
          <div className={styles.ctas}>
            <Link href="/demos/blocked-decision" className={styles.primaryCta}>
              See Blocked Decision
            </Link>
            <Link href="/demos" className={styles.secondaryCta}>
              All demos
            </Link>
          </div>
        </div>
      </section>

      <section className={styles.promise}>
        <h2>Computed, not scripted.</h2>
        <p>
          Every result you see is the output of the real framework — the same
          verifier, simulation and evidence machinery that powers the assurance
          work. The public page simplifies the explanation; it never changes the
          result.
        </p>
      </section>
    </div>
  )
}
