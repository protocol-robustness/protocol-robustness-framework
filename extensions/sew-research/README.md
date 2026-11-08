# Sew research

This extension contains Sew-specific falsification studies, economic and
governance experiments, and analytic phases. Shared PRF simulation code stays
in the framework, and reusable Sew research models stay in
`sew-research-models`.

The package currently uses local in-tree dependencies on PRF framework, Sew
model, and Sew research-model sources. It is an in-tree candidate, not an
independently resolvable package.

For monorepo classpaths, use `:extension/sew-research` or
`:extension/sew-research-rt` from the root `deps.edn`.
