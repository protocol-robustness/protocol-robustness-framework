# PostgreSQL Admission Store — Operations

The authoritative resubmission admission store is backed by PostgreSQL. This
document describes local development, migrations, the transaction/locking
model, multi-instance assumptions, deployment ordering, backup/recovery policy,
and failure handling.

PostgreSQL is **not** merely a persistence detail. It is the authoritative
concurrency boundary: competing application instances that share no JVM state
must produce exactly one canonical result for each operation, and the database
(not any process) owns authoritative time, fence/version monotonicity, and the
family-partition serialization point.

## Architecture

```
                  ┌──────────────┐
                  │ PostgreSQL   │
                  │ authoritative│
                  │ state/time   │
                  └──────┬───────┘
                         │
              ┌──────────┴──────────┐
              │                     │
       ┌──────▼──────┐       ┌──────▼──────┐
       │ instance A  │       │ instance B  │
       │ independent │       │ independent │
       │ pool/JVM    │       │ pool/JVM    │
       └─────────────┘       └─────────────┘
```

Each application instance owns its own bounded HikariCP pool
(`resolver-sim.db.pool`). Nothing process-local (no atom, mutex, cache, or host
timestamp) is part of canonical protocol authority.

## Local PostgreSQL

Docker Compose provides a pinned PostgreSQL 16 (matching the intended RDS 16
target) on host port `5433`, and a disposable `postgres-test` profile on `5434`.

```bash
bb postgres:up                  # start (idempotent)
bb postgres:ps                  # status
bb postgres:psql                # open a psql shell
bb postgres:stop                # stop (keeps volume)
bb postgres:down                # stop + remove container (keeps volume)
bb postgres:reset               # destroy data volume + recreate fresh
bb postgres:migrate             # apply forward-only migrations (idempotent)
```

Configuration is injected via environment variables (defaults are loopback dev
values). Copy `config/compose.postgres.env.example` to an untracked
`config/compose.postgres.env` and override with
`docker compose --env-file config/compose.postgres.env`. Never commit real
credentials.

## Integration tests

PostgreSQL integration tests require a running instance and an empty/migrated
database. They apply real migrations, execute the real adapter, and dispose of
state. The adapter test and the multi-instance concurrency suite are kept
separate from fast unit tests.

```bash
bb test:integration:postgres              # adapter + lifecycle
bb test:integration:postgres:concurrency  # two independent pools, one DB
bb test:integration:postgres:all          # both
make test-integration-postgres-all        # Makefile equivalent
DATABASE_URL=... clojure -M:test -e "(require 'resolver-sim.resubmission.postgres-admission-concurrency-test) (clojure.test/run-tests 'resolver-sim.resubmission.postgres-admission-concurrency-test)"
```

`resolver-sim.resubmission.postgres-admission-concurrency-test` boots two
independent store instances on separate bounded pools against one database and
covers: reservation contention, idempotent replay/conflict, finalization
contention (identical + incompatible), abort-vs-finalize, stale-worker fence
exclusion, expiry boundaries (before/at/after deadline using the DATABASE
clock), lost-finalization-response resolution, and connection/lock death.

Fast in-memory/reference tests do not require a database:
`bb test:unit`, `bb test:framework`.

## Schema and migrations

Migrations are forward-only plain SQL under `resources/db/migrations`
(`NNNN__name.sql`) applied by `resolver-sim.db.migrate` in strict version order,
each inside its own transaction, recorded in `prf_schema_version` with a
content SHA-256. Editing an applied migration is rejected (checksum mismatch).

Single-owner rule: one migration job applies them. Application instances never
run DDL on startup; the adapter's `ensure-schema!` is explicit and used by the
migration job/tests.

The admission partition table is one row per family partition, the serialization
point:

| column | purpose |
|---|---|
| `partition_key` (PK) | family partition identity; row lock target |
| `concurrency_fence` | monotonic per-family fence (reservation + terminal transitions) |
| `family_version` | advances only on canonical finalization |
| `state_edn` | canonical serialized pure admission state (authoritative payload) |
| `updated_at` | audit timestamp |

DB-enforced: `PRIMARY KEY`, `NOT NULL`, `CHECK (concurrency_fence >= 0)`,
`CHECK (family_version >= 0)`, and the `updated_at` touch trigger.

## Transaction / locking model

Each authoritative operation uses SERIALIZABLE isolation and locks the family
partition row with `SELECT ... FOR UPDATE`, then:

```
BEGIN
  lock family/partition row (FOR UPDATE)
  read ONE authoritative PostgreSQL time value (clock_timestamp()), reuse it
  normalize legacy state with that time
  derive lazy expiry with that time
  validate expected version / active reservation / current fence / idempotency
  / committed roots / requested transition
  run the same pure transition as the in-memory reference adapter
  conditional UPDATE (state_edn + structured fence/version columns)
COMMIT
```

SERIALIZABLE failures (SQLSTATE `40001`) and deadlocks (`40P01`) are retried
boundedly (`with-transaction-retry!`). No host-clock synchronization is
assumed; the single `clock_timestamp()` value read inside the locked transaction
is authoritative for that operation and is reused for normalization, lazy
expiry, and transition evaluation. No remote/slow work (e.g. signing) is done
inside the transaction; speculative signing occurs outside the lock and only a
successful matching-fence finalization becomes authoritative.

Design notes:
- Ordinary row locking + declarative constraints are authoritative. No JVM
  mutex, application cache, or process-local structure is.
- No advisory locks are used: a one-row-per-family `SELECT ... FOR UPDATE` is
  the correct and sufficient serialization mechanism, is transaction-scoped by
  default, and needs no session-scoped lifecycle to manage.
- Two independent processes cannot both win an operation that must have one
  canonical winner: whichever acquires the row lock first reads the committed
  state, and the conditional transition (plus unique constraints) rejects the
  other.

Considered failure modes: deadlocks, lock ordering, serialization failures,
retryable errors, connection failure before commit, connection failure after
commit but before response (see resolution below), stale workers, process
termination while holding a transaction (PostgreSQL releases locks on
disconnect), and DB restart/failover (see RDS/Multi-AZ below).

## Multi-instance assumptions

The protocol outcome must not depend on whether competing workers share a
machine. Assumptions that would be unsafe (shared atoms, static registries,
filesystem locks, per-process sequence generators, host timestamps, startup-only
JVM state) are deliberately excluded from admission authority; fences and
versions live in PostgreSQL and are per-family row state. Logs and pool names
carry instance/session identity for diagnostics only; it is never part of
canonical semantics.

## Pool sizing

Capacity = per-instance pool size × instance count. Pool sizing is configurable
(`resolver-sim.db.pool/pool`, e.g. `:pool-size`, `:max-pool-size`,
`:min-idle`, `:idle-timeout-ms`, `:connection-timeout-ms`, `:max-lifetime-ms`)
and must be chosen knowing how many application/JVM instances share the
database. Idle and max-lifetime recycling guard against indefinitely idle
transactions.

## Deployment ordering

```text
Terraform (infrastructure repo) → database available → one migration job →
migration verification → application deployment/restart
```

Migrations are forward-only; rollback of a schema that alters canonical data is
via a forward corrective migration in a later version, not by editing history.
RDS provisioned through the separate infrastructure repository now also this
repo's deployment snapshot must run the migration job exactly once per
environment before rolling the application.

## Backups and disaster recovery (AWS RDS)

The AWS/RDS provisioning (VPC, subnet groups, security groups, encryption,
backup retention, deletion protection, Multi-AZ option, Secrets Manager) lives
in the separate infrastructure repository. The operational policy there must
express:

- **RPO** and **RTO** targets and backup retention per environment.
- Automated daily backups + point-in-time recovery (PITR).
- Encrypted backups; manual/pre-destructive snapshots before destructive steps.
- A restore drill: restore into an isolated environment, verify schema state and
  canonical records/invariants, record the result, destroy temporary resources.
- Deletion protection and final-snapshot policy for protected environments.
- If postgres-on-EC2 (not RDS) is ever used, PostgreSQL-native backup is
  required (base backup + WAL archiving/PITR + integrity verification +
  off-instance storage) — never a live data-dir copy.

"Backup enabled" is not "recoverable": run the restore drill on a schedule.

## Failure recovery

- **Deadlock / serialization failure**: retried bounded by the adapter.
- **Connection fails before commit**: nothing persists; operation reports
  failure.
- **Commit succeeded, response lost (indeterminate)**: call
  `resolve-finalization!` (workflow `resolve-finalization!`). A matching
  finalized record returns the canonical result; an eligible still-active
  reservation exact-replays; expired/aborted/stale/incompatible states resolve
  as unavailable and are never wrongly re-finalized.
- **Process/connection death mid-transaction**: PostgreSQL aborts the
  transaction and releases locks; another worker can make progress (see
  `connection-death-releases-lock-and-allows-progress` test).
- **Datacenter/instance failure**: RDS Multi-AZ failover moves authority to a
  healthy replica; the recovery model must never roll back the monotonic fence
  table (see adapter docstring).

## Secrets

Database/application credentials go through Secrets Manager (infrastructure
repo). They must not be committed to Terraform variables, Ansible inventories,
shell scripts, systemd units, or `.env` files in this repository.

## Observability

Operations should log (without passwords or full secret-bearing connection
strings, and without unnecessary canonical payloads): operation, family/partition
identifier or safe digest, reservation ID, fence, expected/current version,
result classification, transaction retry, contention, stale-worker rejection,
idempotent replay, finalization resolution, DB error class, runtime instance id,
duration. Pool connection failure is surfaced at startup via `SELECT 1` init
checks.