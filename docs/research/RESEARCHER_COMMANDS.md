# Researcher command guide

The researcher commands expose existing PRF artifacts and verifiers. Inputs
are EDN files unless a command says otherwise. A displayed status is scoped to
the specific artifact and trust roots supplied to that command.

## Questions and evidence

```sh
bin/prf research question list --input questions.edn
bin/prf research question show question/example --input questions.edn
bin/prf research evidence inspect --input evidence.edn
```

The question catalog is either a vector of research-definition maps or a map
with `:questions` (or `:research/questions`) containing those maps. Each entry
should include `:research/id`, title, question, and any frozen definition root.
Evidence inspection is intentionally labelled `:loaded-not-verified`; use the
artifact-specific verifier when one exists.

## Conclusions, resolutions, and review

```sh
bin/prf research conclusion verify --input conclusion-check.edn
bin/prf research resolution show --input resolution-view.edn
bin/prf research review status --input approval-check.edn
bin/prf research policy approval prepare --input approval-basis.edn
bin/prf research policy approval sign --input prepared-request.edn \
  --researcher-id member-a --key member-a.key
bin/prf research policy approval verify --input approval-check.edn
```

Conclusion verification input contains `:conclusion`, optional `:theorems`
(a root-to-artifact map or vector), and, for the bounded integer-sum verifier,
`:method-evidence` plus `:trusted-method-verifier-root`. Other methods remain
unverified unless a supported verifier is added.

Resolution input is a `research-resolution-view.v1` artifact. Review status and
approval verification inputs contain `:approval` and
`:trusted-authority-root`. Approval preparation additionally takes
`:policy`, `:review-governance`, `:review-round`, `:governance-policy-id`, and
`:trusted-authority-root`; it prints signing requests but does not sign or
approve them. `approval sign` signs one request with the supplied local key and
emits a signed decision; collect the required decisions and build the receipt
through the research policy approval API before verification.

## Execution and challenge submission

```sh
bin/prf research reproduce --input reproduction.edn
bin/prf research compare --input comparison.edn
bin/prf research submit --input execution-submission.edn
bin/prf researcher challenge submit --help
```

`research submit` admits a research execution artifact. Challenge submissions
are signed actions appended to the challenge history through
`researcher challenge submit`. These commands have separate inputs and effects.

## Export

```sh
bin/prf research export --input evidence.edn --output crate-dir --format ro-crate
```

This copies the selected file into an RO-Crate 1.1 directory with crate
metadata. It packages bytes and does not verify the artifact or discover
related evidence automatically.
