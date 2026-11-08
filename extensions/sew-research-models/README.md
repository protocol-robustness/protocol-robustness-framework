# Sew research models

This extension contains Sew-specific adversarial and economic research models.
The shared stochastic simulation primitives remain PRF-owned dependencies;
the runner-specific batch adapter remains in `sew-runner`.

This is an in-tree candidate. Its framework and Sew model dependencies use
local roots within the PRF workspace because these are not yet independently
published artifacts. It has not been qualified as a standalone repository
package.

For the monorepo classpath, use `:extension/sew-research-models` or
`:extension/sew-research-models-rt` from `deps.edn`.
