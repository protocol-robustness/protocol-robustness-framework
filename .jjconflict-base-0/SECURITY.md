# Security Policy

## Reporting a security issue

Please do not report security vulnerabilities through public GitHub issues, discussions, pull requests, or other public channels.

Use one of the following private reporting methods:

1. Submit a report through GitHub Private Vulnerability Reporting for this repository, if enabled.
2. If private vulnerability reporting is unavailable, email marc@intrinsic.global.

Include `SECURITY` in the email subject.

Do not include private keys, credentials, access tokens, signing material, or other live secrets in the report. If secret material may already have been exposed, describe the type and location of the exposure without copying the secret itself.

## What to include

A useful report should include:

* A description of the issue and its potential impact.
* The affected component, file, command, artifact type, or protocol path.
* The commit, branch, release, or environment where the issue was observed.
* Reproduction steps or a minimal proof of concept.
* Any required configuration or assumptions.
* Whether exploitation requires trusted access, signing keys, governance authority, or control of a runner.
* Suggested mitigations, if known.
* Whether the issue or related details have already been disclosed elsewhere.

For evidence-integrity issues, also include any relevant:

* Artifact or evidence identifiers.
* Schema and contract versions.
* Hashes, signatures, attestations, or verification output.
* Runner, benchmark, scenario, or registry configuration.
* Differences between expected and observed verification results.

## Security issue scope

Security-sensitive issues may include:

* Exposure or mishandling of private keys, credentials, tokens, or signing material.
* Signature verification, canonical hashing, or content-addressing failures.
* Evidence, attestation, artifact, or registry tampering that is not detected by validation.
* Acceptance of forged, substituted, replayed, or ambiguously identified evidence.
* Authorization or governance bypasses.
* Custody, accounting, conservation, settlement, or solvency errors that could incorrectly create, destroy, transfer, or attribute value.
* Unsafe deserialization, command execution, path traversal, or filesystem access.
* Dependency or build-chain compromises.
* Runner isolation failures or unintended access to host resources.
* Cases where generated output materially misrepresents what was executed or verified.
* Denial-of-service issues with realistic impact on supported workflows.
* Vulnerabilities in any deployed service operated by the project.

## Protocol-model and research findings

This repository contains both the Protocol Robustness Framework and the Sew protocol model. Many findings produced through normal research are not software security vulnerabilities.

The following should normally use the public issue, findings, or contribution process:

* A protocol-design gap that is already represented as proposed or not implemented.
* A scenario demonstrating an expected invariant failure.
* A missing benchmark, claim, model assumption, or test case.
* A difference between proposed protocol behavior and deployed Solidity that is already clearly labelled.
* A theoretical attack that does not affect deployed software, project-operated infrastructure, or the integrity of generated evidence.

Report a protocol or model finding privately when early disclosure could:

* Enable immediate exploitation of deployed contracts or services.
* Reveal an unpatched authorization, custody, accounting, or signing weakness.
* Allow forged evidence or attestations to be accepted.
* Expose secrets or confidential infrastructure details.
* Materially compromise an active benchmark, runner, or verification process.

If uncertain, report the issue privately first.

## Supported versions

Security fixes are generally applied to the current `main` branch.

Older commits, historical research artifacts, generated bundles, and experimental branches may not receive security updates unless they are still used by an active deployment or supported workflow.

Reports should identify the exact commit or version affected whenever possible.

## Response process

After receiving a report, maintainers will aim to:

1. Confirm receipt.
2. Assess whether the report is a security vulnerability, protocol-design finding, or ordinary defect.
3. Reproduce and evaluate the impact.
4. Identify affected versions, artifacts, deployments, or workflows.
5. Develop and validate a fix or mitigation where appropriate.
6. Coordinate disclosure with the reporter.

Response and remediation times depend on severity, reproducibility, maintainer availability, and whether external projects or deployments are affected. This policy does not guarantee a specific response or resolution deadline.

Maintainers may ask the reporter to delay public disclosure while a fix or mitigation is prepared. Any disclosure timeline should be agreed in good faith and should account for the risk to users and dependent systems.

## Coordinated disclosure

Please avoid publicly disclosing exploit details before maintainers have had a reasonable opportunity to investigate and mitigate the issue.

When appropriate, the project may publish:

* A security advisory.
* A corrective release or commit.
* A description of affected versions and mitigations.
* Updated schemas, fixtures, verification rules, or evidence.
* Credit to the reporter, unless anonymity is requested.

The project will not intentionally identify a reporter who has requested confidentiality, except where required by law.

## Secrets and test material

Never commit live secrets to the repository.

Examples include:

* Private signing keys.
* Seed phrases.
* API keys and access tokens.
* Cloud credentials.
* Database credentials.
* Production configuration containing secrets.
* Personally identifying test data that is not intended for publication.

Tests, fixtures, scenarios, documentation, and example commands must use clearly non-production values.

Generated evidence and diagnostic output should be reviewed before publication because it may contain:

* Absolute filesystem paths.
* Environment details.
* Account or runner identifiers.
* Repository state.
* Infrastructure addresses.
* Signatures or public keys.
* Command output supplied by external systems.

If a secret is committed or published, assume it has been compromised. Revoke or rotate it immediately; removing it from the latest commit is not sufficient.

## Safe-harbour intent

The project supports good-faith security research intended to improve the safety and integrity of the software, protocol models, and evidence systems.

Researchers should:

* Avoid accessing, modifying, or destroying data that does not belong to them.
* Avoid disrupting services or workflows.
* Use the minimum access and data necessary to demonstrate the issue.
* Stop testing and report the issue if sensitive data or secrets are encountered.
* Comply with applicable law.

This statement expresses the project’s intent but is not a legal guarantee or authorization to test systems owned or operated by third parties.

## Out of scope

The following are generally not treated as security vulnerabilities unless they produce a concrete security impact:

* Missing tests or documentation by themselves.
* Style, formatting, or maintainability concerns.
* Known unsupported configurations.
* Vulnerabilities that require modification of the source code or trusted local environment before execution.
* Denial-of-service claims without a realistic reproduction or supported attack path.
* Automated scanner output without evidence of exploitability.
* Dependency reports that do not affect the repository’s actual dependency graph or supported execution paths.
* Social-engineering attacks against maintainers or contributors.
* Issues exclusively affecting third-party services or protocols that are not operated by this project.

## Recognition

The project may acknowledge reporters in release notes, advisories, or project documentation when they provide a valid report and consent to being named.

This project does not currently operate a bug-bounty programme and cannot guarantee financial compensation.

