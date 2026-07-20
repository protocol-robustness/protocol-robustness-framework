# Scenario Annotation Guide

How to annotate scenario EDN files with concept metadata.

## Annotation Format

Add a `:concept` key to scenario metadata:

```clojure
{:scenario/id "S01-basic-release"
 :concept/id  :ecommerce/purchase
 :concept/role-assignments
 {:buyer  "alice"
  :merchant "bob"}
 :concept/stakeholder-summary
 "Alice buys from Bob. Bob delivers, Alice confirms, funds released."}
```

For scenarios exercising multiple concepts, use a vector:

```clojure
{:scenario/id "S45-hold-with-dispute"
 :concept/ids [:spending-account/controlled-balance
               :ecommerce/purchase]
 :concept/role-assignments
 {:account-holder "alice"
  :buyer "alice"
  :merchant "bob"
  :spending-authority "resolver-1"}
 :concept/stakeholder-summary
 "Alice places a hold for a purchase from Bob, then disputes it."}
```

## Available Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:concept/id` | Yes (or `:concept/ids`) | Single concept identifier. |
| `:concept/ids` | Yes (or `:concept/id`) | Multiple concept identifiers. |
| `:concept/role-assignments` | No | Maps concept roles to actor labels used in the scenario. |
| `:concept/stakeholder-summary` | No | One-sentence stakeholder-facing explanation. |
| `:concept/failure-mode` | No | Failure mode this scenario exercises (from concept definition). |

## Validation

Annotations must reference concepts registered in
`data/concepts/registry.edn`. Unregistered concept IDs will be flagged
by the concepts registry loader.
