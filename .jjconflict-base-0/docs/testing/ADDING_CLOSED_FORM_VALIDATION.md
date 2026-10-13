# Adding Closed-Form Validation

PRF provides validation gates and examples. It does not provide universal
economic equations or prove that an adopter's chosen model is correct.

For each new validation, the adopter should declare:

- canonical inputs, outputs, and semantic witness;
- mathematical domain, integer/decimal rules, and tolerances;
- equations, conservation laws, bounds, and terminal conditions;
- exceptional branches, unavailable resources, caps, and residuals;
- positive, negative, malformed, and rejection vectors;
- independent reconstruction or verifier requirements; and
- the coverage required before the result is claimable.

Existing partial-fill examples include conservation, cap adherence, waterfall
priority, rounding bounds, mode validity, and exact pro-rata checks. They are
patterns to reuse, not a comprehensive validation catalogue.

## Adoption workflow

1. Write the normative model before its implementation: define the canonical
   projection, value domain, equations, and every terminal or exceptional case.
2. Build a pure evaluator that recomputes the expected result from committed
   inputs rather than trusting producer-supplied derived values.
3. Emit a semantic witness sufficient for a reviewer or independent
   implementation to reproduce each material intermediate result.
4. Add positive vectors, malformed-input vectors, and adversarial vectors for
   each equation, boundary, and unsupported mode.
5. Bind evaluator version, input root, witness, result root, and check results
   into the evidence produced by the relevant execution path.
6. Declare which mechanism branches must be exercised for the intended claim.
7. Add an independent reconstruction or cross-implementation verifier before
   claiming stronger computation assurance.

## Claimability criteria

Closed-form checks can support a bounded claim only when all of the following
are explicit:

- the exact model, equations, versions, and input domain;
- the check set and all required witnesses;
- exercised coverage for every branch required by the claim;
- negative and tamper-rejection coverage;
- whether validation is producer-local, independently reconstructed, or
  cross-implementation; and
- exclusions, assumptions, and unmodelled economic behavior.

Scenario completion, a passing aggregate result, or a single passing vector is
not by itself closed-form assurance.

## Status semantics

Keep algebraic integrity, economic-model, and strategic gates distinct. If a
required branch or witness was not exercised, report `:not-exercised` or
`:inconclusive` rather than treating scenario completion as proof. Use
`:not-applicable` only when the check is logically irrelevant to the declared
mode; it must not conceal untested required behavior.

## Worked examples

For the current partial-fill evaluator, check catalogue, result semantics, and
test locations, see `docs/testing/CLOSED_FORM_GAME_THEORETIC_VALIDATION.md`.
That document describes the Sew examples; adopters must define equivalent
semantics and coverage for their own protocol.
