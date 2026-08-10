import styles from './demo-cards.module.css'

export function ScenarioCard({
  held,
  unit,
  label,
  owner,
  workflow,
}: {
  held: number
  unit: string
  label: string
  owner: string
  workflow: number
}) {
  return (
    <section className={styles.card} aria-label="Scenario">
      <div className={styles.cardLabel}>Scenario</div>
      <div className={styles.scenarioRow}>
        <div>
          <div className={styles.scenarioTitle}>{label}</div>
          <div className={styles.scenarioMeta}>
            {owner} · workflow {workflow}
          </div>
        </div>
        <div className={styles.scenarioValue}>
          <span className={styles.scenarioAmount}>
            {held.toLocaleString('en-US')}
          </span>
          <span className={styles.scenarioUnit}>{unit}</span>
        </div>
      </div>
    </section>
  )
}

export function VerdictChip({
  admitted,
  label,
}: {
  admitted: boolean
  label: string
}) {
  return (
    <span
      className={admitted ? styles.chipVerified : styles.chipRejected}
      role="status"
    >
      <span aria-hidden="true">{admitted ? '✓' : '✕'}</span> {label}
    </span>
  )
}

export function ChangeChip({ label }: { label: string }) {
  return (
    <span className={styles.chipChanged} role="status">
      <span aria-hidden="true">↻</span> {label}
    </span>
  )
}
