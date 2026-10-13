# Benchmark Authoring Guide (Generated)

Source of truth: scenario contract schema, canonical transition registry, and repository scenario examples.

Definitions hash: `743884729`

## 1) Required top-level fields

- `schema_version
- `scenario_id
- `seed
- `agents
- `events

## 2) Required event fields

- `seq
- `time
- `agent
- `action
- `params

## 3) Supported action names

- `create_escrow`
- `raise_dispute`
- `execute_resolution`
- `execute_pending_settlement`
- `automate_timed_actions`
- `release`
- `sender_cancel`
- `recipient_cancel`
- `auto_cancel_disputed`

## 4) Canonical transition vocabulary

| Transition ID | Label |
|---|---|
| `auto_cancel_disputed` | Auto-cancel disputed |
| `automate_timed_actions` | Automate timed actions |
| `challenge_resolution` | Challenge resolution |
| `create_escrow` | Create escrow |
| `escalate_dispute` | Escalate dispute |
| `execute_pending_settlement` | Execute pending settlement |
| `execute_resolution` | Execute resolution |
| `raise_dispute` | Raise dispute |
| `recipient_cancel` | Recipient cancel |
| `register_stake` | Register stake |
| `release` | Release |
| `sender_cancel` | Sender cancel |
| `submit_evidence` | Submit evidence |

## 5) Example scenarios (generated from repo)

| File | Scenario ID | Schema version | Actions present |
|---|---|---|---|

## 6) Authoring checklist

- Keep `seq` contiguous starting at `0` and monotonic.
- Ensure `time` is monotonic non-decreasing.
- Ensure every `agent` in events exists in the top-level `agents` array.
- Prefer canonical action names from this guide.
- Include `purpose`/threat tags where your workflow expects narrative classification.
- Run replay + docs checks before publishing benchmark artifacts.
