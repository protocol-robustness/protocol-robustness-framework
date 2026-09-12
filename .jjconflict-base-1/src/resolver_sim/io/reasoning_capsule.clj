(ns resolver-sim.io.reasoning-capsule
  "Generator for 'reasoning-capsule' artifacts.
   These artifacts provide a condensed, diagnostic summary of simulation failures
   for RAG, model evaluation, and reporting.")

(def schema-version "reasoning-capsule.v1")

(defn generate-capsule
  "Generate a reasoning capsule from scenario result and metrics.
   Args:
     scenario-id: ID of the scenario.
     protocol: Protocol identifier.
     risk-domain: Domain where the risk occurred.
     failure-class: Category of the failure.
     critical-path: Vector of events/transitions leading to the failure.
     relevant-invariants: Vector of invariants related to the failure.
     metrics-digest: Map of key metrics (attack-attempts, etc.).
     diagnosis: Plain-text diagnostic summary.
     recommended-next-test: Actionable next step.
     semantic-analysis: Map of protocol-specific state-based metadata."
  [& {:keys [scenario-id protocol risk-domain failure-class critical-path
             relevant-invariants metrics-digest diagnosis recommended-next-test
             semantic-analysis]}]
  (cond-> {:schema_version       schema-version
           :scenario_id          scenario-id
           :protocol             protocol
           :risk_domain          risk-domain
           :failure_class        failure-class
           :critical_path        critical-path
           :relevant_invariants  relevant-invariants
           :metrics_digest       metrics-digest
           :diagnosis            diagnosis
           :recommended_next_test recommended-next-test}
    (some? semantic-analysis) (assoc :semantic_analysis semantic-analysis)))
