import type { LiquidityShortfallDemo } from '../../lib/public-demo'
import styles from './allocation.module.css'

export function PoolCard({ demo }: { demo: LiquidityShortfallDemo }) {
  const pool = demo.scenario.pool
  return (
    <section className={styles.card} aria-label="Pool">
      <div className={styles.cardLabel}>Pool</div>
      <div className={styles.poolRow}>
        <div>
          <div className={styles.poolTitle}>
            Available liquidity: {pool.available.toLocaleString('en-US')}{' '}
            {pool.unit}
          </div>
          <div className={styles.poolMeta}>
            Requests: {pool.requested.toLocaleString('en-US')} {pool.unit}
          </div>
        </div>
        <span className={styles.shortfallBadge}>
          Shortfall {pool.requested - pool.available}
        </span>
      </div>
    </section>
  )
}

export function AllocationTable({ demo }: { demo: LiquidityShortfallDemo }) {
  return (
    <section className={styles.card} aria-label="Allocation">
      <div className={styles.cardLabel}>Allocation — pro-rata by request size</div>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Request</th>
            <th>Requested</th>
            <th>Allocated</th>
            <th>Shortfall</th>
          </tr>
        </thead>
        <tbody>
          {demo.scenario.requests.map((r) => (
            <tr key={r.id}>
              <td className={styles.requestId}>{r.id}</td>
              <td>{r.requested.toLocaleString('en-US')}</td>
              <td className={styles.allocated}>{r.allocated.toLocaleString('en-US')}</td>
              <td className={styles.shortfall}>{r.shortfall.toLocaleString('en-US')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className={styles.totals}>
        <span>
          Allocated: <strong>{demo.allocation['total-allocated']}</strong>
        </span>
        <span>
          Unallocated residual:{' '}
          <strong>{demo.allocation['unallocated-residual']}</strong>
        </span>
      </div>
    </section>
  )
}

export function ConservationCard({ demo }: { demo: LiquidityShortfallDemo }) {
  const c = demo.conservation
  return (
    <section className={styles.card} aria-label="Conservation">
      <div className={styles.cardLabel}>Conservation</div>
      <div className={styles.conservationRow}>
        <span>
          requested <strong>{c.requested}</strong> = allocated{' '}
          <strong>{c.allocated}</strong> + shortfall <strong>{c.shortfall}</strong>
        </span>
        <span
          className={c.holds ? styles.holdsChip : styles.violatedChip}
          role="status"
        >
          <span aria-hidden="true">{c.holds ? '✓' : '✕'}</span>{' '}
          {c.holds ? 'HOLDS' : 'VIOLATED'}
        </span>
      </div>
      <p className={styles.note}>
        No liquidity disappears and no request is silently rewritten. What could
        not be filled stays visible as shortfall.
      </p>
    </section>
  )
}
