import type { PublicDemo } from '../../lib/public-demo'
import ExtLink from '../common/ExtLink'
import styles from './proof-cta.module.css'

// The executable notebooks live on the Clerk server in dev (localhost:7777)
// and are composed into the static export at /lab/notebooks/<name> in the
// production build (bb demo:public-lab). NODE_ENV is inlined at build time.
const NOTEBOOK_BASE =
  process.env.NODE_ENV === 'production'
    ? '/lab/notebooks' // static Clerk build composes here at publication
    : 'http://localhost:7777/notebooks' // live Clerk dev server

const PREVIEW: Partial<Record<PublicDemo['demo']['id'], { src: string; alt: string }>> = {
  'liquidity-shortfall': {
    src: '/notebook-previews/pro-rata-allocation-result.png',
    alt: 'Preview of the Pro-Rata Allocation Result Clerk notebook',
  },
}

export default function TechnicalProofCTA({ demo }: { demo: PublicDemo }) {
  const notebook = demo.source.notebook
  const href = `${NOTEBOOK_BASE}/${notebook}`
  const preview = PREVIEW[demo.demo.id]
  const notebookLabel = `the ${demo.source.notebook.replaceAll('_', ' ')} notebook`
  return (
    <section className={styles.section} aria-label="Inspect the executable notebook">
      <div className={styles.copy}>
        <div className={styles.heading}>Backed, not improvised</div>
        <p>
          The verdicts on this page are not hardcoded. They are produced by an
          executable PRF scenario: the same verifier and engine run on the
          evidence, and a deterministic assertion fails the build if the
          demonstration ever stops telling the truth.
        </p>
        <ul className={styles.points}>
          <li>Original result preserved</li>
          <li>Result carried through to the evidence</li>
          <li>Committed signature in every artifact</li>
          <li>Reproducible from a single command</li>
        </ul>
        <div className={styles.ctas}>
          <ExtLink href={href} className={styles.primaryCta}>
            Inspect {notebookLabel} ↗
          </ExtLink>
          <code className={styles.cli}>{demo.source.cli}</code>
        </div>
      </div>
      {preview && (
        <div className={styles.previewWrap}>
          {/* The preview is a preview, not the proof. The proof is the notebook. */}
          <a
            href={preview.src}
            target="_blank"
            rel="noopener"
            className={styles.previewLink}
            aria-label={`${preview.alt} — open full size`}
          >
            <img
              src={preview.src}
              alt={preview.alt}
              className={styles.preview}
              loading="lazy"
            />
          </a>
          <span className={styles.previewCaption}>
            Notebook preview — click to open this image.{' '}
            <ExtLink href="/shots/">Browse every screenshot and run archive</ExtLink>
            . The proof is the <ExtLink href={href}>executable notebook</ExtLink>.
          </span>
        </div>
      )}
    </section>
  )
}
