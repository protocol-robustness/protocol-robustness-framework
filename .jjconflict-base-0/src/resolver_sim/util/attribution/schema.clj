(ns resolver-sim.util.attribution.schema
  "Attribution key schema and required-key registries.")

(def required-evidence-keys
  "Minimum keys for complete attribution in research evidence."
  #{:ctx/run-id})

(def scenario-evidence-keys
  "Additional keys expected for scenario-based evidence."
  #{:ctx/scenario-id :ctx/event-index})

(def known-attribution-keys
  "Registry of documented attribution keys.
   Used for validation and researcher discoverability.
   These describe provenance: run context, subject, action, reason."
  {:ctx/scenario-id
   {:namespace :ctx
    :description "Scenario identifier for the replay or test case."}

   :ctx/run-id
   {:namespace :ctx
    :description "Unique run identifier."}

   :ctx/event-index
   {:namespace :ctx
    :description "Zero-based event index in replay order."}

   :ctx/event-type
   {:namespace :ctx
    :description "Dispatched event/action type."}

   :subject/type
   {:namespace :subject
    :description "Kind of entity being annotated, e.g. :resolver, :escrow, :yield-module."}

   :subject/id
   {:namespace :subject
    :description "Stable identifier for the subject."}

   :action/type
   {:namespace :action
    :description "Semantic operation being performed."}

   :evidence/reason
   {:namespace :evidence
    :description "Research reason for capturing evidence."}})

(def known-attribution-namespaces
  "Set of known attribution key namespaces derived from the registry."
  (into #{} (map :namespace) (vals known-attribution-keys)))
