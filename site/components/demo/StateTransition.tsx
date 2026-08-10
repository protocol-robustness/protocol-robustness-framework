import styles from './transition.module.css'

/**
 * StateTransition — a responsive HTML/CSS + SVG arrow from one value to
 * another. Text stays selectable; the arrow is decorative.
 */
export default function StateTransition({
  from,
  to,
  unit,
  label,
  signature,
}: {
  from: string
  to: string
  unit: string
  label: string
  signature: string
}) {
  return (
    <div className={styles.transition}>
      <div className={styles.state}>
        <div className={styles.stateLabel}>{label} (before)</div>
        <div className={styles.stateValue}>
          {from} <span className={styles.unit}>{unit}</span>
        </div>
      </div>
      <div className={styles.arrow} aria-hidden="true">
        <svg viewBox="0 0 48 16" width="48" height="16" role="img">
          <line x1="0" y1="8" x2="40" y2="8" stroke="currentColor" strokeWidth="2" />
          <polygon points="40,4 48,8 40,12" fill="currentColor" />
        </svg>
      </div>
      <div className={styles.state}>
        <div className={styles.stateLabel}>{label} (after)</div>
        <div className={styles.stateValue}>
          {to} <span className={styles.unit}>{unit}</span>
        </div>
      </div>
      <div className={styles.signature}>
        committed signature <code>{signature}</code>
      </div>
    </div>
  )
}
