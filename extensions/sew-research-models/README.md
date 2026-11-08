# Sew research models

This extension contains Sew-specific adversarial and economic research models.
The shared stochastic simulation primitives remain PRF-owned dependencies;
the runner-specific batch adapter remains in `sew-runner`.

This is an in-tree candidate. Its framework and Sew model dependencies point
to the same pinned PRF repository revision; the model package still resolves
its framework from that repository checkout. These are not yet independently
published PRF artifacts, so this extension has not been qualified as a
standalone repository package.

For the monorepo classpath, use `:extension/sew-research-models` or
`:extension/sew-research-models-rt` from `deps.edn`.
