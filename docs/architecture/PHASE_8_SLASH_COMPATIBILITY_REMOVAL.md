# Phase 8: Canonical Slash Compatibility Removal

## Objective

Remove the legacy string-based slash identity compatibility layer after all
runtime callers use canonical slash entity IDs or semantic slash references.

The target model is:

```clojure
{:slash/id 7
 :slash/workflow-id 0
 :slash/kind :reversal
 :slash/level 0
 :slash/status :pending}
```

Runtime slash mutations accept only a canonical non-negative integer slash ID
whose `:slash/workflow-id` matches the supplied workflow. Scenario authors use
either a bound ID or a semantic reference; encoded identifiers such as
`"0-reversal-0"` are not accepted.

## Non-goals

- Do not change slash economics, appeal authorization, or settlement ordering.
- Do not reintroduce encoded IDs as canonical storage keys.
- Do not make raw canonical hashing accept fractional or floating-point values.
- Do not silently coerce an invalid slash reference to a workflow ID.

## Current compatibility surface

The following must be removed only after the migration gate passes:

| Surface | Current purpose | Phase 8 disposition |
|---|---|---|
| `:slash-id-aliases` | Maps legacy strings to integer IDs | Delete |
| `register-slash-alias` | Adds legacy mappings during slash creation | Delete |
| `resolve-slash-id-alias` | Resolves legacy action input | Delete |
| `event-slash-id` alias branch | Replay adapter normalization | Accept only canonical integer IDs after scenario binding resolution |
| Reversal/fraud alias registration | Produces `"<wf>-reversal-<level>"` mappings | Delete |
| String-key fallback in `resolution.clj` | Reads pre-migration slash storage | Delete |
| String-key predicates in validators | Identifies slash kind by encoded key | Replace with `:slash/kind` metadata |

The first validator migration is complete: force-reversal mechanism-property
validation now uses `:slash/kind` rather than string matching.

## Canonical scenario authoring contract

### Manual fraud slash

Bind the ID returned by the proposal action:

```clojure
{:action "propose_fraud_slash"
 :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}
 :bind {:slash-id :fraud-slash}}

{:action "execute_fraud_slash"
 :params {:workflow-id 0
          :slash-id {:from-binding :fraud-slash}}}
```

### Reversal slash

Reversal slashes are transition side effects. Reference them by stable semantic
context, not by an assumed allocation number:

```clojure
{:action "appeal_slash"
 :params {:workflow-id 0
          :slash-id {:slash-ref {:workflow-id 0
                                 :kind :reversal
                                 :level 0}}}}
```

A negative unknown-ID test must use an invalid canonical integer, for example
`999`, and assert `:slash-not-found`; it must not rely on a legacy encoded
string.

## Migration plan

### Step 1 — Complete fixture and scenario migration

1. Migrate `S-DR-032-resolver-insufficient-stake.edn` from `"slash-001"` to
   `:bind` / `:from-binding`.
2. Replace remaining runtime encoded reversal references in scenario EDN files
   with `:slash-ref`.
3. Change expectation fixtures that currently name encoded IDs to assert
   canonical slash metadata or a semantic context.
4. Update the wrong-ID Track 2 fixture to use an unknown integer ID and the
   canonical public error.
5. Replace string placeholder IDs in slash evidence tests with canonical
   integer fixture IDs.
6. Remove alias-specific unit tests and replace them with tests proving that
   strings are rejected at the mutation boundary.

**Gate:**

```sh
bb test:unit
bb test:invariants
bb backstop
```

Additionally, parse every edited EDN scenario individually and run each
migrated scenario through `bb run:scenario`.

### Step 2 — Stop producing aliases

Remove alias writes from the slash creation paths:

- `handle-reversal-slashing`
- `force-reversal-slash`
- `handle-fraud-slashing`

At this point, retain alias *reads* temporarily only if a separate external
import path still declares a supported migration window. New worlds must not
contain `:slash-id-aliases` entries.

**Gate:**

- Add a regression test asserting all normal slash creation paths leave no
  alias data.
- Run the Step 1 gate.

### Step 3 — Remove legacy storage fallbacks

Remove all checks for encoded keys inside `:pending-fraud-slashes`, including
reversal idempotence and force-reversal checks. Use only:

```clojure
(t/slash-context-key workflow-id kind level)
```

and canonical record metadata.

**Gate:**

- Add focused tests for idempotence at the same semantic context.
- Add a cross-level reversal test proving levels produce distinct canonical
  IDs and contexts.
- Run the Step 1 gate.

### Step 4 — Remove replay alias ingestion

1. Make `event-slash-id` read only an integer `:slash-id` after
   `resolve-scenario-bindings` has expanded `:from-binding` or `:slash-ref`.
2. Reject string inputs with `:invalid-slash-id` rather than preserving them.
3. Delete `resolve-slash-id-alias` and `register-slash-alias`.
4. Delete `:slash-id-aliases` from `empty-world` and canonical projections.

**Gate:**

- Unit tests must assert string, keyword, ratio, float, negative, and
  out-of-range values yield `:invalid-slash-id`.
- Replay tests must assert missing bindings and semantic references fail with
  their existing descriptive `ExceptionInfo` paths.
- Run the Step 1 gate and a repository-wide search:

```sh
grep -RInE ':slash-id "|slash-id-aliases|register-slash-alias|resolve-slash-id-alias' \
  scenarios protocols_src src test
```

The search must return only historical documentation explicitly labeled as
historical, or no matches.

### Step 5 — Remove obsolete documentation and regenerate artifacts

1. Remove migration-era comments describing string IDs as supported.
2. Update `CHANGELOG.md` to mark the compatibility layer removed.
3. Regenerate any golden traces or reference artifacts whose canonical hashes
   change because slash IDs are now represented solely by integers.
4. Run `bb backstop` after generated artifact refreshes.

## Rollback criteria

Stop and revert the current step—not a prior validated step—if any of these
occur:

- a scenario requires a string ID to dispatch successfully;
- an integer slash ID resolves to a slash from another workflow;
- a semantic reference becomes ambiguous or nondeterministic;
- canonical slash registry or context-index invariants fail;
- replay output changes settlement, slash amount, or appeal outcome rather
  than only identifier representation.

The rollback mechanism is the immediately preceding commit/patch. Do not
restore aliases as parallel registry keys; repair the caller or semantic index
instead.

## Completion criteria

Phase 8 is complete when:

- no world state includes `:slash-id-aliases`;
- no production function defines or calls alias registration/resolution;
- no scenario dispatches a slash action with an encoded string identifier;
- all slash identity checks use `:slash/id`, `:slash/workflow-id`, and
  `:slash-by-context`;
- `bb test:unit`, `bb test:invariants`, and `bb backstop` pass.
