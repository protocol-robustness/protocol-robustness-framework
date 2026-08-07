# Assurance Lab

> The Assurance Lab makes PRF research executable for someone who does not need
> to install, understand, or develop PRF itself.

## 1. Product purpose

The Assurance Lab is a browser-facing layer over the existing AWS PRF stack. A
visitor opens a URL, picks one of a small set of curated experiments, changes a
few meaningful assumptions, and runs genuine Protocol Robustness Framework
research — without a repository clone, a JVM, a terminal, an AWS account, or any
PRF knowledge.

AWS makes the research executable without the visitor having to become a PRF
developer.

## 2. Visitor experience

1. Open `https://<cloudfront>/lab/`.
2. Read the research question in plain language.
3. Select an experiment (Withdrawal under constrained liquidity, Insolvency
   after loss, Pro-rata fractional allocation).
4. Change a few constrained parameters.
5. Click **Run experiment**.
6. The existing AWS-hosted PRF executes the experiment.
7. A structured result is returned: outcome, assessment, assurance findings,
   evidence roots, provenance, run identity.
8. Change the assumptions and rerun — the same mechanism, a different outcome,
   and the evidence shows why.

No commands are accepted from the browser. The interface is an allowlisted
experiment registry with typed, bounded parameters.

## 3. Existing AWS components reused

| Component | Reuse |
|---|---|
| EC2 core host (`t4g.small`, eu-north-1a) | Execution host; lab runs in an isolated subprocess on this JVM |
| S3 + CloudFront + OAC | Static lab UI (`/lab/`), default origin unchanged |
| CloudFront | New `/api/*` ordered behavior routes dynamic lab requests to the host |
| Elastic IP (new) | Stable CloudFront origin across stop/start |
| Security group | Inbound port 80 opened only to the CloudFront origin-facing managed prefix list; still no SSH |
| IAM instance role | Existing S3 access reused to sync lab results to the bucket |
| Ansible + systemd | `myapp` service now runs the lab HTTP server |
| `bb aws-deploy` | Builds the sew jar and deploys |
| S3 run artifacts | Lab results copied to `s3://<bucket>/lab/runs/<id>/result.json` |
| Journald / SSM tooling | Lab run IDs are logged; operators use existing tooling |

## 4. Architecture

```
Visitor browser
      │  https://<cloudfront>/lab/ (S3 origin)
      │  https://<cloudfront>/api/lab/* (custom origin → EC2)
      ▼
 CloudFront
      ├── default behavior  → S3 bucket (static UI + demo site)
      └── /api/* behavior   → http://<EIP>:80 → nginx → 127.0.0.1:8082
                                              (resolver-sim.lab.http)
                                                    │
                                                    ▼
                                          subprocess (isolated JVM)
                                          resolver-sim.lab.runner
                                                    │
                                          allowlisted runner dispatch
                                                    │
                                          PRF mechanism execution
                                          (allocate, classify-solvency, …)
                                                    │
                                          normalized lab result → JSON
                                                    ▼
                                          result file + S3 lab/runs/<id>/
```

The web server never executes experiments in its own JVM. Each run spawns a
fresh `java` process running `resolver-sim.lab.runner`, preserving the
repository's clean-execution-context requirements and guaranteeing a crashed
run cannot destabilize the service.

## 5. Experiment contract

Every browser-runnable experiment is registered in
`src/resolver_sim/lab/registry.clj` as:

```clojure
{:experiment/id :withdrawal/constrained-liquidity
 :experiment/version 1
 :experiment/slug "withdrawal-constrained-liquidity"
 :experiment/title "Withdrawal under constrained liquidity"
 :experiment/question "..."
 :experiment/description "..."
 :experiment/mechanism "..."
 :experiment/protocol "sew/yield + pro-rata"
 :experiment/comparison true
 :runner :withdrawal/constrained-liquidity      ; resolved ONLY in runner-dispatch
 :parameters [{:parameter/id :available-liquidity
               :type :integer :min 0 :max 10000000
               :default 1000 :label "Available liquidity"} ...]}
```

Parameter definitions carry enough information for both validation and UI
rendering. A request is `{"experiment": "<slug>.v<N>", "parameters": {...}}`.
The server resolves the experiment ID; `"command"`-style requests are rejected.

## 6. Execution boundary

Runs happen in a **subprocess**: the HTTP server (`resolver-sim.lab.http`)
validates the request, writes a server-generated request file, and spawns
`java -jar <sew-jar> -m resolver-sim.lab.runner <request> <run-id> <output>`.
The runner re-validates defensively and dispatches through a fixed map:

```clojure
(def runner-dispatch
  {:withdrawal/constrained-liquidity withdrawal/run
   :insolvency/impairment insolvency/run
   :pro-rata/allocation pro-rata/run})
```

Why a subprocess and not in-web-JVM execution:

- The repository enforces clean execution contexts and per-run evidence/registry
  isolation. A fresh JVM per run is the strongest such boundary.
- Determinism: each run starts from a clean classloader and working state.
- Fault isolation: a runaway or crashing experiment cannot take down the API.
- Reuse: the existing `prf-run`/SSM subprocess model is the same pattern.

The subprocess boundary is the conceptual `:ec2-local` backend. The seam is
preserved so a future `:lambda`, `:fargate`, or `:independent-verifier` backend
could run the same validated request through the same runner.

## 7. Evidence flow

- **Pro-rata findings** come from `resolver-sim.pro-rata.invariants/result-violations`
  — the engine's own invariant validators (request-hash, allocation-hash,
  cap-respecting, quota-bounded, residual, round-trace, fractional-remainder,
  canonical-remainder-assignment). The lab renders them; it does not recompute
  them.
- **Solvency findings** come from `classify-solvency`'s own dimensions and
  reasons (`:solvent :impaired :insolvent :unassessable :assessment-invalid`),
  the canonical liability-set artifact root, and the state commitment.
- **FCFS withdrawal** is implemented in the lab as a deterministic sequential
  fill mirroring the batch-withdrawal semantics of `liquid-lending/withdraw-many`
  (first-come-first-served in owner order). Its witness is bound with the
  repository's canonical hashing, and its structural assertions are labelled
  `:lab-consistency` — never presented as PRF claim results.
- Exposed roots are the PRF mechanism's own commitments (`:allocation/hash`,
  `:request/hash`, `:liability-set/root`, `:assessment/commitment`). The lab
  does not mint parallel "lab hashes" where the framework already provides an
  authoritative root.

## 8. Security boundary

- Allowlisted experiment IDs; unknown references rejected.
- Typed, bounded parameters; unknown parameters rejected; top-level fields other
  than `experiment`/`parameters` rejected (no `command`, no scripts).
- Provenance identity is server-derived: a visitor cannot choose or alter
  `git-sha`, package version, runner implementation, evidence schema version,
  experiment version (beyond selecting a registered version), researcher or
  verifier identity, execution backend, output destination, or run ID. The
  visitor controls only the experiment and its declared parameters.
- Request body size limit (64 KB), execution timeout (90 s), concurrency cap (2),
  and a simple global rate limit.
- No arbitrary shell, namespace/function, filesystem path, git revision, URL, or
  package can be selected by a visitor; the subprocess argv is built entirely
  from fixed parts.
- Sanitized errors: no stack traces; failures carry a `LAB-RUN-ID` reference.

### Origin boundary

The dynamic origin is reachable only through CloudFront:

- The security group opens TCP 80 only to the AWS-managed
  `com.amazonaws.global.cloudfront.origin-facing` prefix list. A direct
  `curl http://<EIP>/api/lab/...` from outside AWS fails at the network layer.
- Because the prefix list proves only "the request came from CloudFront
  infrastructure" (any distribution), the origin is additionally protected by a
  shared credential: CloudFront attaches `X-PRF-Origin-Token: <secret>` to every
  `/api/*` origin request, and nginx requires the exact value, returning 403
  otherwise. The boundary is therefore **CloudFront source IP AND correct origin
  credential**. Another CloudFront distribution (or a direct EIP caller) is
  rejected.
- The token is set in `terraform.tfvars` (`lab_origin_token`) and exported as
  `LAB_ORIGIN_TOKEN` when running Ansible; it is never committed. If it is not
  set, nginx fails closed (403) and the lab is disabled.

### Actor model

The concepts are kept separate in the data model and UI:

| Actor | Role | In the lab |
|---|---|---|
| visitor | runs research | `:execution/visitor :anonymous-visitor` |
| researcher | authors / assesses research | the PRF machinery and its findings |
| runner | executes computation | `:execution/runner :anonymous-lab` |
| verifier | verifies evidence | not in V1 |
| consensus | reconciles assessments | the three-researcher demo (`bb aws-demo`), kept separate |

A visitor executing an insolvency experiment is using researcher-produced
machinery; they do not become one of the researchers whose consensus supports a
conclusion.

### Finding origin

Findings carry an origin that survives to the UI. The result page groups them
under two headings that are visually distinct:

- **PRF VERIFIED** (`:findings/origin :prf`) — the framework's own invariant and
  assessment results (pro-rata engine validators, solvency classifier
  dimensions).
- **LAB CONSISTENCY** (`:findings/origin :lab-consistency`) — lab-side
  structural assertions (e.g. FCFS aggregate conservation) that are checked by
  the lab, not certified by PRF. They are presented as consistency checks, never
  as first-class assurance findings.

## 9. Deployment

```bash
# 1. Set the origin credential (same value in both places).
#    terraform.tfvars:  lab_origin_token = "<random-secret>"
export LAB_ORIGIN_TOKEN="<random-secret>"

# 1b. Open SSH to your operator IP for Ansible deployment (in terraform.tfvars):
#     operator_ssh_cidrs = ["<your-public-ip>/32"]

# 2. From the infra repo terraform dir
terraform plan && terraform apply          # EIP, SG rule, CloudFront /api/* + origin header

# 3. Deploy the lab server + nginx to EC2 (builds the sew jar, runs Ansible)
PRF_PROJECT_DIR=/path/to/protocol-robustness-framework bb aws-deploy

# 4. Publish the static lab UI to S3/CloudFront
PRF_PROJECT_DIR=/path/to/protocol-robustness-framework bb lab-publish
```

The lab UI lives in the PRF repo at `resources/public/lab/` and is served from
the existing S3 bucket at `/lab/`. The dynamic API is served by the existing
EC2 host through nginx at `/api/` and routed by CloudFront.

Pre-deployment acceptance checks:

1. `terraform plan` — confirm the change is only the EIP/association, the
   restricted SG rule, the CloudFront `/api/*` behavior, and the origin header.
2. Apply, deploy, publish.
3. `curl http://<EIP>/api/lab/experiments` from outside AWS → fails (SG).
4. `curl -H "X-PRF-Origin-Token: wrong" http://<EIP>/api/lab/experiments`
   through a non-lab CloudFront distribution → 403 (nginx).
5. Exercise all three experiments through `https://<cloudfront>/api/lab`.
6. Send malformed/injection requests against the deployed endpoint.
7. Run the concurrent isolation test (`./scripts/test.sh lab`).
8. Stop/restart `myapp` during a run and confirm recovery.

## 10. Adding a new experiment

1. Register the experiment in `resolver-sim.lab.registry/experiments` with a
   unique id, version, slug, copy, parameter specs, and a `:runner` key.
2. Implement the runner function (e.g. in
   `resolver-sim.lab.experiments.<name>`) returning
   `{:outcome … :assessment … :findings … :evidence …}`.
3. Wire the runner key into `resolver-sim.lab.runner/runner-dispatch`.
4. Add tests under `test/resolver_sim/lab/`.
5. Rebuild (`bb aws-deploy`) and re-publish the UI.

## 11. Cost profile

- Existing fixed cost: one `t4g.small` ARM instance (always on), plus storage.
  Approximate current cost is low single-digit USD per month plus storage.
- Lab additions: Elastic IP (free while attached), a CloudFront custom origin and
  `/api/*` behavior (no additional CloudFront charge beyond existing request
  pricing), a CloudFront prefix-list SG rule (free). No new compute, no Lambda,
  no ALB, no ECS.
- Estimated incremental cost at very low traffic: ≈ $0–$1/month.
- Scaling constraint: single host with a concurrency cap of 2 runs; wall-clock
  per run is dominated by JVM startup (seconds). Suitable for a research lab,
  not a high-traffic service.

## 12. Operational troubleshooting

- **API 500 / no dynamic endpoint**: check `systemctl status myapp` on EC2 and
  journalctl; the lab server must bind `127.0.0.1:8082`.
- **CloudFront 502 on `/api/*`**: confirm the SG allows the CloudFront
  origin-facing prefix list on port 80 and that nginx is running.
- **Correlating a run**: every result carries a `LAB-RUN-ID` and `:execution`
  block; the server logs `lab run <id> <status> took-<ms>ms` to journald, and
  completed results are copied to `s3://<bucket>/lab/runs/<id>/result.json`.
- **Rebuild after PRF changes**: `bb aws-deploy` (rebuilds the sew jar) then
  `bb lab-publish`.

## 13. Deliberate V1 limitations

- FCFS withdrawal uses a lab-side sequential fill; only pro-rata mode surfaces
  the engine's own invariant findings. The distinction is labelled in the UI.
- The solvency world is a minimal, coherent construction (live escrow + optional
  recognized loss + optional external observation); it demonstrates the real
  classifier vocabulary but not every possible world shape.
- No full scenario package / completion.json is produced — evidence is at the
  mechanism level.
- No accounts, no rate limiting per visitor, no persistence beyond result files.
- The three-researcher demo remains a separate capability (`bb aws-demo`).

## 14. Future scaling seam

The execution boundary (`resolver-sim.lab.exec`) is the seam. A future
`:fargate` backend would run the same validated request through the same
`resolver-sim.lab.runner` in a container; a `:lambda` backend would do the same
with a warm JVM. The trigger to migrate should be actual usage: sustained
concurrency above the single-host cap, run durations that make JVM startup
dominant for real traffic, or a need for independent-verifier isolation.
