# Repairability report extension

An in-tree, read-only report-section candidate for repair evidence. It projects
declared repair options and `prf-dev` verifier output into a report while
retaining producer claims as unverified assertions.

The extension does not verify signatures, choose trust policies, authorize or
execute repairs, or authorize publication. In particular, a supplied
`repair-verification.v1` value is not treated as proof: the current verifier
output is not a rooted, independently checkable receipt bound to a verifier
release. See `docs/proposals/REPAIRABILITY_REPORT_EXTENSION.md` and PRO-90.

This package is a source candidate only. The report capability API remains
proposed under ADR-0005; the package is not registered by default or independently
released.

The pure renderer is available as
`prf.extensions.repairability-report.report/build-section`. It accepts a map
with `:snapshot-root`, `:options`, and optional `:verification`. Supplied
dimension values are shown under `:claimed-dimensions` with
`:status :unverified`; a verifier output's own status is retained only as
`:claimed-status` and never changes `:qualification :unverified-inputs`.
