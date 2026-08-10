import type { PublicDemo } from '../../lib/public-demo'
import styles from './evidence.module.css'

/**
 * EvidencePanel — the native evidence drill-down. All values come from the
 * public-demo artifact (projected from the executable PRF result); the panel
 * only renders them.
 */
export default function EvidencePanel({ demo }: { demo: PublicDemo }) {
  const evidence = demo.evidence
  return (
    <section className={styles.panel} aria-label="Evidence">
      <div className={styles.heading}>Evidence</div>

      <div className={styles.block}>
        <div className={styles.blockLabel}>Committed signature</div>
        <code className={styles.hash}>{evidence['committed-hash']}</code>
      </div>

      <div className={styles.block}>
        <div className={styles.blockLabel}>Evidence lines</div>
        <ul className={styles.lines}>
          {evidence.lines.map(([label, value]) => (
            <li key={label}>
              <span className={styles.lineLabel}>{label}</span>
              <code className={styles.lineValue}>{value}</code>
            </li>
          ))}
        </ul>
      </div>

      <div className={styles.block}>
        <div className={styles.blockLabel}>Checks run against the changed record</div>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Check</th>
              <th>Status</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {evidence.checks.map((check) => (
              <tr key={check.id}>
                <td className={styles.checkId}>{check.id}</td>
                <td>
                  <span
                    className={
                      check.status === 'pass' ? styles.pass : styles.fail
                    }
                  >
                    {check.status === 'pass' ? 'PASS' : 'FAIL'}
                  </span>
                </td>
                <td className={styles.detail}>{check.detail ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
