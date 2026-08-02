# Transition & Guard Catalog (Generated)

Source of truth: `definitions.registry/transitions`, `definitions.registry/transition-metadata`, `results/test-artifacts/coverage.json`.

Definitions hash: `743884729`

| Transition ID | Label | Allowed sources | Allowed targets | Guards | Actor permissions | Pause effect | Coverage status | Hit count |
|---|---|---|---|---|---|---|---|---:|
| `auto_cancel_disputed` | Auto-cancel disputed | `disputed` | `refunded` | `timeout-expired` | `keeper` | `blocked-when-paused` | missing | 0 |
| `automate_timed_actions` | Automate timed actions | `pending`, `disputed` | `pending`, `released`, `refunded` | `deadline-eligible` | `keeper` | `blocked-when-paused` | missing | 0 |
| `challenge_resolution` | Challenge resolution | `disputed` | `disputed` | `resolution-exists`, `challenge-window-open` | `challenger`, `watchdog` | `blocked-when-paused` | missing | 0 |
| `create_escrow` | Create escrow | `none` | `pending` | `unpaused`, `valid-params` | `sender` | `blocked-when-paused` | missing | 0 |
| `escalate_dispute` | Escalate dispute | `disputed` | `disputed` | `pending-exists`, `appeal-window-open`, `max-level-not-reached` | `sender`, `recipient` | `blocked-when-paused` | missing | 0 |
| `execute_pending_settlement` | Execute pending settlement | `disputed` | `released`, `refunded` | `pending-exists`, `deadline-expired` | `keeper`, `executor` | `blocked-when-paused` | missing | 0 |
| `execute_resolution` | Execute resolution | `disputed` | `released`, `refunded` | `authorized-resolver`, `state-disputed` | `resolver` | `blocked-when-paused` | missing | 0 |
| `raise_dispute` | Raise dispute | `pending` | `disputed` | `participant`, `state-pending` | `sender`, `recipient` | `blocked-when-paused` | missing | 0 |
| `recipient_cancel` | Recipient cancel | `pending` | `pending`, `refunded` | `caller-is-recipient` | `recipient` | `blocked-when-paused` | missing | 0 |
| `register_stake` | Register stake | `none` | `none` | `stake-params-valid` | `resolver` | `blocked-when-paused` | missing | 0 |
| `release` | Release | `pending` | `released` | `authorized-release` | `sender`, `authorized-release-address` | `blocked-when-paused` | missing | 0 |
| `sender_cancel` | Sender cancel | `pending` | `pending` | `caller-is-sender` | `sender` | `blocked-when-paused` | missing | 0 |
| `submit_evidence` | Submit evidence | `disputed` | `disputed` | `state-disputed` | `sender`, `recipient`, `challenger`, `watchdog` | `blocked-when-paused` | missing | 0 |
