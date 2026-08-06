(ns resolver-sim.resubmission.derive-kind
  "Root-comparison resubmission kind derivation.

   The kind is DERIVED from comparisons against the parent attempt receipt's
   status-bearing roots — never trusted from a :resubmission/kind field.

   Direct relationships:
     :exact-retry        parent results :verified and SAME current results root;
                         subject unchanged; a non-semantic (packaging / signature
                         / execution) rejection. (The gate-pass requirement is a
                         separate verify-stage rule.)
     :corrected-result   parent results :verified and different current results
                         root; subject unchanged.
     :submission-repair  parent results :missing/:invalid; subject unchanged;
                         current supplies a new authoritative result; no claim
                         the semantic result is identical.

   Non-direct:
     :lineage                   subject root changed.
     :duplicate-or-reevaluation identical subject/result/package, no
                                authoritative change.

   Reasons distinguish the failures per the design contract.")

(def semantic-rejection-classifications
  "Rejection classifications that concern the semantic result/reconciliation.
   A parent rejected for these cannot be an :exact-retry with an unchanged
   semantic result."
  #{:result-award-mismatch
    :result-total-capacity-mismatch
    :result-nonzero-residual
    :result-entitlement-mismatch
    :result-rounding-rule-mismatch
    :result-leaf-set-incomplete
    :result-root-mismatch
    :result-capacity-mismatch
    :outcome-not-exact-capacity
    :proportionality-failure})

(defn semantic-rejection?
  "True when the parent's rejection classification concerns the semantic result."
  [parent]
  (contains? semantic-rejection-classifications
             (:rejection-classification parent)))

(defn derive-kind
  "Derive the resubmission relationship from parent roots + current roots.

   parent:
     {:roots {:research-subject {:status :hash}
              :results {:status :hash}
              :execution-context {:status :hash}
              :submission-basis {:status :hash}}
      :rejection-classification kw-or-nil}

   current:
     {:research-subject-hash str
      :results-hash str
      :execution-context-hash str
      :submission-basis-hash str}

   Returns {:kind kw :reason kw}. `:kind` may be :exact-retry | :corrected-result
   | :submission-repair | :lineage | :duplicate-or-reevaluation | :none."
  [parent current]
  (let [subject (get-in parent [:roots :research-subject])
        results (get-in parent [:roots :results])
        exec (get-in parent [:roots :execution-context])
        basis (get-in parent [:roots :submission-basis])]
    (cond
      (and (= :verified (:status subject))
           (not= (:hash subject) (:research-subject-hash current)))
      {:kind :lineage :reason :subject-root-mismatch}

      (not= :verified (:status results))
      {:kind :submission-repair :reason :parent-results-not-verified}

      (= (:hash results) (:results-hash current))
      (if (semantic-rejection? parent)
        {:kind :none :reason :result-change-required}
        (let [exec-changed (and (= :verified (:status exec))
                                (not= (:hash exec) (:execution-context-hash current)))
              basis-changed (and (= :verified (:status basis))
                                 (not= (:hash basis) (:submission-basis-hash current)))]
          (if (or exec-changed basis-changed)
            {:kind :exact-retry :reason (if exec-changed
                                          :execution-context-changed
                                          :submission-basis-changed)}
            {:kind :duplicate-or-reevaluation :reason :no-authoritative-change})))

      :else
      {:kind :corrected-result :reason :results-changed})))

(defn declared-kind-consistent?
  "True when the declared kind matches the derived kind."
  [derived declared]
  (= derived declared))

(defn kind-mismatch-reason
  "The derivation reason when declared and derived kinds disagree, else nil."
  [derived declared]
  (when-not (declared-kind-consistent? derived declared)
    (if (= :none derived)
      (:reason derived)
      :declared-kind-mismatch)))
