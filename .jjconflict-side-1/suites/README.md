# Suites

Suite directories define scenario collections with lifecycle scripts
(run, verify, clean, report) and pinned expected outputs.

| Suite | Status | Protocols | Scenarios | Notes |
|---|---|---|---|---|
| `reference-validation-v1` | **Canonical** | prf-core | 7 | Flagship suite; Makefile-integrated |
| `sew-domain-reference-v1` | **Canonical** | sew | 5 | Makefile-integrated |
| `yield-reference-v1` | **Canonical** | yield | 13 | Makefile-integrated |
| `liquid-lending-v1` | **Canonical** | yield | 7 | Recent; fully operational |
| `ef-review-v1` | **Trace archive** | sew, yield | 5 pinned traces | EF review reference only; not executable standalone |
| `dispute-resolution-v2` | **Stub** | — | 24 | Manifest only; scenarios not yet implemented |
