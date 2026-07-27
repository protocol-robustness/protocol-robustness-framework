# Archived Benchmark Material

Material from earlier benchmark experiments, consolidated here for
reference without polluting the canonical `benchmarks/` tree.

**This directory is excluded from the canonical benchmark registry.** Tools
that validate, list, or resolve the benchmark registry (`registry.edn`,
`BENCHMARKS.edn`) must not descend into `archived/`.

## Contents

| Item                          | Classification     | Notes                        |
|-------------------------------|--------------------|------------------------------|
| original `benchmarks/dispute-liveness.edn` | legacy prototype | ID preserved for backward compat; migrated to `packs/sew/` |

## Policy

To archive material:
1. Place under `archived/<descriptive-name>/`.
2. Include a brief README explaining origin and migration path.
3. Reference the replacement location in the canonical tree.
4. Add an exclusion note to `benchmarks/README.md` if adding a new directory.
