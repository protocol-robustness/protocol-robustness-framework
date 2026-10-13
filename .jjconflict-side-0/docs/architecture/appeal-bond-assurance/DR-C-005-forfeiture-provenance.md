# DR-C-005 forfeiture provenance

## Decision

`DR-C-005-appeal-bond-forfeit-reserve` is not currently a valid ordinary-escalation
scenario for the Sew PRF.

Ordinary escalation appeal bonds have two resolution-owned terminal dispositions:

- `:refund` when the appeal reverses the prior decision;
- `:distribution` when the appeal upholds the prior decision.

Forfeiture is a distinct disposition used by slash-appeal rejection paths. It is
not a participant-invoked `slash-bond` operation and is not exposed as a general
scenario command.

The internal `acct/slash-bond` helper remains an accounting primitive. It is not,
by itself, an authority definition. No scenario dispatch is added until the
production mechanism specifies which transition authorizes forfeiture.

## Causal requirement

A valid forfeiture scenario must establish:

```text
slash proposal or equivalent authoritative liability
  -> appeal bond is held for that slash appeal
  -> authorized rejection/forfeiture transition
  -> :forfeiture disposition
  -> insurance/forfeited reserve accounting
```

`DR-C-005` currently contains only:

```text
create escrow
  -> raise dispute
  -> execute resolution
  -> execute pending settlement
```

No appeal bond is posted and no slash-appeal rejection occurs. Therefore
`forfeited_reserve > 0` is not derivable from the authoritative state.

## Classification

- **Production semantics:** ordinary escalation refund/distribution are supported;
  ordinary escalation forfeiture is not established by this fixture.
- **Scenario dispatch:** no new `slash-bond` command is authorized.
- **Projection:** `forfeited_reserve` remains a derived metric over authoritative
  `:forfeiture` dispositions.
- **Fixture status:** `DR-C-005` is a Layer-A semantic fixture divergence and
  must not be counted as evidence of PRF/Solidity equivalence.

The expectation is intentionally not weakened or defaulted. The fixture should
be replaced or revised only after the intended production forfeiture authority
is specified.
