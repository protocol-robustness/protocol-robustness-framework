# Contributing

Thank you for considering contributing to this project. Contributions of all kinds are welcome, including bug reports, feature suggestions, documentation improvements, scenarios, benchmarks, research findings, and code changes.

This repository contains:

* The **Protocol Robustness Framework (PRF)**: scenario execution, benchmarks, claims, artifacts, evidence, attestations, registries, and verification tooling.
* The **Sew protocol model**: escrow, dispute resolution, appeals, settlement, yield, slashing, and related protocol behavior.

Framework-only commands avoid loading Sew where possible. Commands using the `:with-sew` alias load both layers.

## Code of conduct

This project follows a code of conduct that all contributors are expected to follow. Read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before participating.

## Getting help

* Open a [GitHub Discussion](https://github.com/OWNER/REPO/discussions) for questions, design discussions, or early ideas.
* File a [GitHub Issue](https://github.com/OWNER/REPO/issues) for reproducible bugs, feature requests, or scoped work.
* Follow [SECURITY.md](SECURITY.md) for vulnerabilities, exposed secrets, evidence-integrity failures, or other security-sensitive reports.

## Reporting issues

Before opening an issue:

* Search existing issues and discussions.
* Provide a clear description and minimal reproduction.
* Include the commands you ran and the relevant output.
* Identify the affected commit, scenario, benchmark, artifact, or protocol path where possible.
* State whether the issue affects current Solidity parity, proposed protocol behavior, framework infrastructure, or research-only functionality.
* Apply appropriate labels if you have triage permissions.

Do not place private keys, credentials, signing material, access tokens, or other secrets in an issue.

## Development setup

Follow [`docs/quickstart/README.md`](docs/quickstart/README.md) for required tooling and initial setup.

The normal contributor interfaces are Babashka tasks and the canonical test runner:

```bash
bb test                        # canonical validation gate (same as ./scripts/test.sh all)
bb test:framework              # focused framework unit coverage
bb test:unit                   # framework + Sew unit coverage
bb test:fast                   # edit-loop validation gate (same as ./scripts/test.sh fast)
./scripts/test.sh fast         # edit-loop validation gate (same as bb test:fast)
./scripts/test.sh all          # broad repository validation gate (same as bb test)
```

Use the Sew alias when invoking Clojure directly against the protocol model:

```bash
clojure -M:with-sew
```

Commands are also available through a built runner jar:

```bash
java -jar target/prf-runner-core-<version>.jar <command>
java -jar target/prf-runner-sew-<version>.jar <command>
```

See [`docs/reference/usage.md`](docs/reference/usage.md) for supported commands, jar build targets, and generated outputs.

## Choosing validation scope

Run the narrowest relevant check first, then expand validation according to the change.

| Change type                                                  | Expected validation                                         |
| ------------------------------------------------------------ | ----------------------------------------------------------- |
| Documentation only                                           | Review links, commands, generated-file rules, and examples  |
| Framework-only source                                        | Focused tests, `bb test:framework`, formatting, and lint    |
| Sew source or protocol scenarios                             | Focused replay, `bb test:unit`, formatting, and lint        |
| Scenario or suite registry                                   | Focused replay and `bb scenario-registry:validate`          |
| Cross-cutting runner, registry, artifact, or evidence change | `bb test:fast` / `./scripts/test.sh fast` plus focused validation |
| Broad, release-sensitive, or repository-wide change          | `bb test` / `./scripts/test.sh all`                               |
| Generated report, golden, or fixture change                  | Regenerate, inspect every diff, and run its producing tests |

If a broad check is intentionally not run, state that clearly in the pull request.

## Before submitting a change

1. Run the narrowest relevant test, scenario, benchmark, or reproduction first.

2. Run formatting and lint checks when touching Clojure source:

   ```bash
   clojure -M:fmt/check
   clojure -M:lint
   ```

3. Regenerate and review tracked derived files when modifying their source. See [`docs/reference/GENERATED_DOCUMENTS.md`](docs/reference/GENERATED_DOCUMENTS.md).

4. Do not commit local output under `results/` unless the change explicitly updates a tracked fixture or generated artifact.

5. Review repository status before committing to ensure that transient results, private keys, signatures, credentials, and local environment files are excluded.

6. Update documentation, schemas, validators, fixtures, and tests whenever changed behavior affects their contracts.

7. Keep the change focused. Do not mix unrelated formatting churn, cleanup, or refactoring with behavioral changes.

## Protocol alignment convention

The repository models both deployed Solidity behavior and proposed protocol enhancements.

Any code, scenario, fixture, benchmark, or documentation that models behavior not yet implemented in Solidity must be explicitly marked:

```clojure
{:protocol/status :protocol/proposed
 :solidity/status :solidity/not-implemented
 :finding/id      "S-DR-NNN"
 :proposal/id     "PRF-PROP-NNN"
 :scenario/kind   :mitigation-validation}
```

See [`docs/concepts/protocol-alignment.md`](docs/concepts/protocol-alignment.md) for the full status convention and [`docs/findings/`](docs/findings/) for recorded protocol design gaps.

The following rules apply:

* Do not represent proposed behavior as deployed Solidity parity.
* Reuse an existing finding or proposal ID when the change implements or validates an existing design.
* Do not invent an identifier without adding or updating its authoritative finding or proposal record.
* Scenario markers must appear in the scenario’s canonical metadata.
* Code, fixtures, reports, and documentation should reference the same finding and proposal IDs consistently.
* State the Solidity version, commit, deployment, or other parity baseline when a claim depends on specific deployed behavior.
* A mitigation-validation scenario demonstrates a proposal; it does not establish deployed parity.

## Adding or changing scenarios

* New executable scenarios must use the canonical EDN format.

* Existing JSON scenarios remain readable during migration and emit a deprecation warning.

* Do not add new executable JSON scenarios.

* Register new scenarios and suites through the existing registry conventions.

* Validate registry changes with:

  ```bash
  bb scenario-registry:validate
  ```

* Run an end-to-end local replay with:

  ```bash
  bb run:scenario <scenario-id>
  ```

* If a scenario intentionally violates an invariant, declare its expected failure using the established scenario expectation format.

* A declared expected failure that is not exercised is reported as stale or unused and must be reviewed.

* When changing summaries or other documented metadata for S01–S23, regenerate and review their table with:

  ```bash
  bb docs:scenarios
  ```

  This command updates only the S01–S23 table in `docs/scenarios.md`. It does not document later scenario families.

## Adding a new invariant

1. Define the check function in the appropriate `invariants.clj` file.
2. Register it in the `check-fns` map using a stable keyword ID.
3. Add the ID to `default-runtime-invariant-ids` in `invariant-catalog.clj`.
4. Add expected-failure entries only to scenarios intentionally designed to trigger it.
5. Add focused passing and failing tests.
6. Run `bb test:unit` and the focused scenario coverage.
7. Regenerate golden reports only when the changed expected behavior is intentional:

   ```bash
   bb regenerate-goldens
   ```

Review every resulting golden-file diff. Explain the behavioral reason for each changed golden in the pull request. Do not regenerate goldens merely to make a failing test pass.

## Changing schemas or artifact contracts

Schemas, producers, validators, fixtures, and documentation form a single contract.

When changing an artifact, evidence object, manifest, registry entry, attestation, claim result, or other persisted structure:

1. Update the authoritative schema or contract definition.
2. Update every producer that emits the structure.
3. Update every validator and verifier that consumes it.
4. Update representative fixtures and golden outputs.
5. Add tests that reject invalid structures, not only tests that accept valid ones.
6. Review canonical hashing and content-addressing implications.
7. Review backward compatibility, versioning, and migration behavior.
8. Document any field addition, removal, rename, semantic change, or version transition.

Do not treat schema files as documentation-only descriptions. Generated output must conform to the declared contract.

If strict schema validation is not yet available for the affected format, the pull request must identify that limitation and show how producer/consumer consistency was checked.

## Stable identifiers

Treat registered identifiers as durable interfaces.

This includes, where applicable:

* Scenario and suite IDs.
* Benchmark and claim IDs.
* Invariant IDs.
* Finding and proposal IDs.
* Registry keywords.
* Artifact kinds and schema versions.
* Evidence-node and attestation subject kinds.
* Public command names.
* Persisted or externally referenced identifiers.

Do not rename, reuse, or change the meaning of an existing identifier without an explicit compatibility and migration assessment.

A new version is generally preferable to silently changing the semantics of a persisted or externally referenced ID.

## Evidence and generated artifacts

Evidence-oriented tasks may create files under `results/`.

Signing requires a private key supplied at invocation time. Never add private keys, seed material, credentials, or production secrets to the repository.

See the following references for artifact and evidence contracts:

* [`docs/reference/usage.md`](docs/reference/usage.md)
* [`schemas/README.md`](schemas/README.md)
* [`docs/evidence/`](docs/evidence/)
* [`docs/reference/GENERATED_DOCUMENTS.md`](docs/reference/GENERATED_DOCUMENTS.md)

Generated documentation, golden reports, trace fixtures, and evidence examples may have different sources of truth. Follow `GENERATED_DOCUMENTS.md` rather than editing generated files directly.

Before committing generated evidence or diagnostics, review them for:

* Absolute filesystem paths.
* Environment or host details.
* Account and runner identifiers.
* Repository state.
* Infrastructure addresses.
* Signatures and public keys.
* Unexpected local output.
* Data copied from external systems.

## Dependencies and build changes

When adding or changing a dependency:

* Explain why the dependency is required.
* Prefer existing repository capabilities where practical.
* Review its license and maintenance status.
* Consider portable-runner and offline-execution implications.
* Update lockfiles or dependency manifests intentionally.
* Run the relevant build and test paths.
* Avoid introducing runtime dependencies for functionality used only during development.

## Documentation changes

Documentation should describe behavior that exists in the repository unless it is explicitly marked as proposed.

When documenting commands:

* Verify them in a clean checkout where practical.
* Show expected inputs and meaningful outputs.
* Avoid relying on unpublished local scripts or environment state.
* Link to the authoritative concept, schema, registry, or generated-file rule.
* Update documentation when public commands, artifact shapes, or protocol-status semantics change.

## Pull request process

1. Create a focused branch from the latest `main`, using a fork unless you have permission to create branches in the repository.

2. Make the change following the conventions above.

3. Run the relevant focused tests and validation gates.

4. Review all generated and golden-file diffs.

5. Open a pull request against `main` with a descriptive title and clear explanation.

6. Include:

   * The problem being addressed.
   * The chosen approach.
   * The affected framework or protocol components.
   * Validation commands that were run.
   * Checks intentionally not run.
   * Behavioral or schema migrations.
   * Protocol-status or Solidity-parity changes.
   * Added, removed, or renamed stable identifiers.
   * Generated artifacts or golden files changed.
   * Known limitations or follow-up work.

7. Keep commits and diffs reviewable.

8. Respond to review feedback and update the branch as needed.

## Review expectations

Reviewers may ask for:

* A smaller or more focused change.
* Additional negative or adversarial tests.
* Explicit protocol-alignment metadata.
* Stronger schema validation.
* Compatibility or migration handling.
* Evidence that generated output matches its declared contract.
* Separation of framework behavior from Sew-specific behavior.
* Removal of unrelated formatting or cleanup.
* Documentation of assumptions and unsupported cases.

Research or exploratory code may be accepted with narrower guarantees when those limitations are explicit and cannot be mistaken for production or deployed-protocol behavior.

## License

By contributing, you agree that your contributions will be licensed under the same license as this project. See [LICENSE](LICENSE).

