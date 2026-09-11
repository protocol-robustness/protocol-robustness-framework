(ns resolver-sim.conformance.outcome
  "Typed non-success outcomes.

   Not everything that is not a passing claim collapses into nil/false/exit 1.
   Outcomes are first-class machine states with an explicit claimability flag,
   so CI, CLI output, attestations and dashboards use the same semantics.")

(def non-success-outcome-classes
  "Outcome classes that never carry a positive claim."
  #{:not-executable
    :not-evaluated
    :incomplete-evidence
    :incompatible-profile
    :execution-failed
    :comparison-diverged
    :invariant-failed
    :reproduction-mismatch
    :claim-not-permitted})

(def outcome-classes
  "All outcome classes (non-success plus the success classes)."
  (into non-success-outcome-classes
        #{:equivalent :reproduced :candidate-compatible :accepted-divergence}))

(defn outcome
  "Build a typed outcome.

   {:outcome/class <kw>
    :outcome/reason <kw>
    :outcome/details [<map>...]
    :outcome/claimable? bool}

   Non-success classes are never claimable; wording must be derived from the
   class, never from a command name or exit code."
  [{:keys [class reason details]}]
  {:outcome/class class
   :outcome/reason reason
   :outcome/details (vec (or details []))
   :outcome/claimable? (not (contains? non-success-outcome-classes class))})

(defn known-outcome-class?
  [class]
  (contains? outcome-classes class))
