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

const PREVIEW: Record<string, { src: string; alt: string; notebookLabel: string }> = {
  'blocked-decision': {
    src: '/notebook-previews/not-admitted.png',
    alt: 'Preview of the Not Admitted Clerk notebook',
    notebookLabel: 'the Not Admitted notebook',
  },
  'liquidity-shortfall': {
    src: '/notebook-previews/pro-rata-allocation-result.png',
    alt: 'Preview of the Pro-Rata Allocation Result Clerk notebook',
    notebookLabel: 'the Pro-Rata Allocation Result notebook',
  },
  'reordered-evidence': {
    src: '/notebook-previews/not-admitted.png',
    alt: 'Preview of the Not Admitted Clerk notebook',
    notebookLabel: 'the Not Admitted notebook',
  },
  'current-head': {
    src: '/notebook-previews/not-admitted.png',
    alt: 'Preview of the Resubmission Chain Clerk notebook',
    notebookLabel: 'the Resubmission Chain notebook',
  },
}

export default function TechnicalProofCTA({ demo }: { demo: PublicDemo }) {
  const notebook = demo.source.notebook
  const href = `${NOTEBOOK_BASE}/${notebook}`
  const preview = PREVIEW[demo.demo.id] ?? PREVIEW['blocked-decision']
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
            Inspect {preview.notebookLabel} ↗
          </ExtLink>
          <code className={styles.cli}>{demo.source.cli}</code>
        </div>
      </div>
      <div className={styles.previewWrap}>
        {/* The preview is a preview, not the proof. The proof is the notebook.
            Clicking it opens the full-resolution image (browser zoom / save). */}
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
    </section>
  )
}
