import type { Metadata } from 'next'
import Link from 'next/link'
import './globals.css'
import styles from './site.module.css'

export const metadata: Metadata = {
  title: {
    default: 'PRF — Protocol Robustness Framework',
    template: '%s · PRF',
  },
  description:
    'Making protocol behaviour visible before failure becomes expensive. See how protocols behave under failures, disputes, economic stress and adversarial conditions — and inspect the evidence behind every result.',
  openGraph: {
    title: 'PRF — Protocol Robustness Framework',
    description:
      'Executable assurance, simulation, evidence and provenance for protocols. Watch the demos, then inspect the evidence.',
    type: 'website',
  },
}

const navItems = [
  { label: 'Home', href: '/' },
  { label: 'Demos', href: '/demos' },
]

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <body>
        <header className={styles.header}>
          <div className={styles.headerInner}>
            <Link href="/" className={styles.brand}>
              <span className={styles.brandMark}>PRF</span>
              <span className={styles.brandText}>Protocol Robustness Framework</span>
            </Link>
            <nav className={styles.nav}>
              {navItems.map((item) => (
                <Link key={item.href} href={item.href} className={styles.navLink}>
                  {item.label}
                </Link>
              ))}
            </nav>
          </div>
        </header>
        <main className={styles.main}>{children}</main>
        <footer className={styles.footer}>
          <div className={styles.footerInner}>
            <p>
              Protocol Robustness Framework — executable assurance, evidence and
              provenance. Every demo result is computed by the framework, not
              scripted.
            </p>
          </div>
        </footer>
      </body>
    </html>
  )
}
