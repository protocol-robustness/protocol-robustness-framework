# Review report: local partial-fill conservation

**Finding: supported.** The frozen model input is 50, 30 and 20 units. Its
recorded allocation is 35, 21 and 14, which totals the specified 70 units.
The deterministic reconstruction reproduces the 70-unit total.

**Falsification attempt: contradiction found.** A deliberately substituted
Carol amount of 15 produces a total of 71. That observation contradicts the
70-unit conservation property and is retained as counterevidence to any claim
that the altered projection conserves the input.

**Evidence gap.** `missing-external-chain-receipt.json` is absent. The review
cannot establish that the modelled values were executed on a public chain or
authenticated externally. The `current-world` snapshot is a timestamped local
fixture only.

**Reproduction.** Run the command in `README.md` (also recorded in
`request.edn`). It executes the script and reads the fixture from the pinned
review package, then redirects the script's output into that same package's
`reconstruction-result.edn`. The manifest pins both the script and result.
Verification checks the result file's identity and compares its contents to
the manifest's expected and observed maps; the verifier itself does not execute
the recorded command.

**Authority and effects.** The reconstruction only reads the package-local
fixture and writes the package-local reproduction result. It does not access or mutate
authoritative protocol state. The machine result records
`:authoritative-state-mutated? false` as an observation of this local run, not
as a proof about any external system.
