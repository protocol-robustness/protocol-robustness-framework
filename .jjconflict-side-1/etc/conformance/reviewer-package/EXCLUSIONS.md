# Exclusions (Sew trace-equivalence attestation)

The attestation universe is partitioned into included and explicitly excluded
subjects.  Exclusion is recorded by `subject/id` with the subject-set root in
the coverage envelope.  A claim never extends to excluded subjects.

| Reason category | Meaning |
|---|---|
| environment not reproducible | subject depends on state the pipeline cannot faithfully replay |
| mode not applicable | evaluation mode does not apply to this subject kind |
| declared out of scope | profile explicitly excludes this subject class |

Exclusion does not weaken the claim: coverage completeness requires every
subject to be included or explicitly excluded.
