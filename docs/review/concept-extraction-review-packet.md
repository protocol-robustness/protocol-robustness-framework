# PRF concept extraction review packet

**Status:** evidence and scope handoff for independent review. This packet is not itself an approval or staging-admission record.

## Candidate identity

- Repository: `protocol-robustness/protocol-robustness-framework`
- Issue: PRO-159, concept-bundle / registry extraction
- Integration base: `84ca777e0fca16030c40f158cf593b2a45184c73`
- Implementation candidate: commit `26a70f726a6747b87435fec8628970041b6e05a3`, tree `c3b4b3d4fa7e4ee37031f36bd33105c0f59db800`
- Implementation parent: `d2ce807af7b5d03560957b85bee52acbc62ef92f`
- Jujutsu review bookmark: `agent-c-2-concepts-candidate`
- The implementation removes legacy `data/concepts`, `data/concept-mappings`, and `data/concept-bundles` paths from PRF and loads the extracted package.

## Dependency and provenance

- PRF pins `protocol-robustness/prf-extensions` commit `0078ab6c8e4b07d281629ce78a20a76fa8624bd4` in `deps.edn` and `resources/prf/concept-catalogue-lock.edn`.
- Extension PR #1 merged to `main` at `754c1705c79e77800f0d31bb5c75d03a4306f7f9`; its second parent is pinned commit `0078ab6c…`, so the pin is reachable from main.
- The legacy-order snapshot cites PRF integration commit `84ca777e0fca16030c40f158cf593b2a45184c73`. The historical catalogue root is `sha256:5dee6023ad4c3820391603e2f427b5a275f2114c3c0b78cf169b5fcef7a63cec`; the lock marks historical-root equivalence false.
- The 50 concept IDs were extracted in order from `data/concepts/registry.edn` at that source commit. The LF-joined sequence digest is `231e482d0bc38656751da4008ac82cf9c26a34c7a82499989e5c2c2c49f9bd33`, matching the extension validator’s fixed expected digest. The validator rejects a paired reordering of snapshot and lock.

## Extraction claims and review findings

- The extracted catalogue contains 3 bundles and 50 concepts at root `sha256:96b8e0d5d7a7dc71ad83100481475b7caadc5291fec274fc751dc6efc6b9ed10`.
- Independent semantic comparison found all 50 concepts equivalent to the historical PRF definitions except for the added `:concept/status`; registry fields (ID, type, layer, name, status, owner, protocols) match.
- Mapping files are byte-identical; the mapping README was intentionally left behind.
- The follow-up removes the unused `reg-by-id` binding and private `mapping-categories` var, adds the missing `clojure.string` test require, and updates remediation-plan paths to the extracted package layout. These edits are cleanup and documentation changes.
- The independent reviewer reports the code-level review clean and identifies this packet’s availability as the remaining review blocker. The reviewer independently ran the concept validator and targeted concept tests, but did not rerun the full notebook/SPEDS suite.

## Qualification evidence

Recorded full validation on implementation candidate `26a70f72…` passed:

- Lint: passed, 0 errors, 109 warnings.
- Notebook support: passed.
- All notebooks: 56/56 passed.
- SPEDS: 66 tests / 265 assertions, 0 failures/errors.
- Candidate-specific `bb concepts:validate`: 50 resources, 3 bundles, 5 known-gap issues, no errors.
- Focused follow-up test: 5 tests / 50 assertions, 0 failures/errors.

The full validation report is at `/home/user/Code/.workspaces/agent-c-2-replay-20261120/target/validation/20261121T125004.323Z-d683a5c8-ad6d-438e-b8e4-643f3a8e2b29/validation-report.edn`.

## Lifecycle status

- This packet is being added as a documentation-only Jujutsu follow-up so the reviewed evidence is available in the repository history. The implementation candidate remains `26a70f72…` / tree `c3b4b3d4…`.
- No staging admission, aggregate integration review, preflight, or promotion has occurred. `agent-c-staging` and `integration` have not been moved.
- Package release, publication, and consumer migration remain separate from source integration and are not authorized by this packet.

## Next gates

1. Close independent review against the implementation candidate and the evidence recorded here.
2. Admit only the reviewed implementation candidate through the normal Jujutsu staging workflow.
3. Qualify the exact combined staging tree, complete aggregate integration review, and run integration preflight before conditional promotion.
