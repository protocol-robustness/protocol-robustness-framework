(ns resolver-sim.definitions.passive-registries
  "Semantic registries validated on namespace load.

   Registries validated at startup:
   - intent-registry
   - projection-definition-registry
   - claim-definition-registry
   - attestor-registry
   - execution-registry
   - evidence-policy-registry
   - hash-projection-registry (wraps canonical.clj hash-intents)
   - domain-tag-registry (wraps canonical.clj domain-tags)

   Validation throws on startup if any registry is invalid.
   This is a hard-fail — the system will not start with corrupt registries."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]))

(def ^:dynamic *startup-registry-validation-enabled*
  "When false, startup registry validation is skipped.
   Set to false only for test fixtures that need to load without valid registries.
   Defaults to true — corrupted registries are a hard-fail."
  true)

(def ^:dynamic *entry-validation-mode*
  "Controls execution-entry validation during registry checks.
   :startup-safe keeps passive startup load behavior.
   :strict uses requiring-resolve after registries are loaded."
  :startup-safe)

(declare validate-claim-definition-registry-entries)
(declare validate-attestor-registry-entries)

(defn- canonical-entry-hash
  [intent entry]
  (hc/hash-with-intent {:hash/intent intent} entry))

(defn- attach-hash
  [intent hash-key entry]
  (assoc entry hash-key (canonical-entry-hash intent entry)))

(defn- sorted-topological
  "Topological sort of claim definitions by :depends-on.
   Returns node IDs in order with leaves (no deps) first.
   Uses Kahn's algorithm. Throws on cycle detection."
  [definitions]
  (let [ids (set (keep :id definitions))
        raw-deps (fn [id]
                   (vec (or (:depends-on (some #(when (= id (:id %)) %) definitions)) [])))
        graph (into {} (map (fn [{:keys [id]}] [id (raw-deps id)]) definitions))
        in-degree (fn [g]
                    (reduce-kv (fn [acc node deps]
                                 (reduce #(update %1 %2 (fnil inc 0)) (assoc acc node 0) deps))
                               {} g))
        deps (in-degree graph)
        queue (into clojure.lang.PersistentQueue/EMPTY
                    (filter #(zero? (get deps %)) (keys graph)))]
    (loop [q queue, deps deps, sorted []]
      (if (empty? q)
        (if (= (count sorted) (count definitions))
          sorted
          (throw (ex-info "Circular dependency in claim definitions"
                          {:remaining (remove (set sorted) (keys graph))
                           :sorted sorted})))
        (let [node (peek q)
              q (pop q)
              neighbors (get graph node [])
              {:keys [q deps]}
              (reduce (fn [acc dep]
                        (let [new-val (dec (get (:deps acc) dep 0))]
                          (if (zero? new-val)
                            (-> acc
                                (update :q conj dep)
                                (assoc-in [:deps dep] new-val))
                            (assoc-in acc [:deps dep] new-val))))
                      {:q q :deps deps}
                      neighbors)]
          (recur q deps (conj sorted node)))))))

(defn enrich-claim-definitions
  "Given a vector of claim definitions with :canonical-hash computed,
   returns enriched definitions with :concept-hash and resolved :depends-on.
   Processes claims in topological order (leaves first) so dependency
   concept-hashes are always available when computing dependent hashes."
  [definitions]
  (if (empty? definitions)
    definitions
    (let [ids (set (keep :id definitions))
          id->defn (into {} (map (fn [d] [(:id d) d]) definitions))
          topo-order (sorted-topological definitions)
          concept-hash-cache (atom {})
          resolve-deps (fn [dep-ids]
                         (when (seq dep-ids)
                           (vec (for [dep-id dep-ids
                                      :when (contains? ids dep-id)]
                                  {:claim-id dep-id
                                   :concept-hash (get @concept-hash-cache dep-id)}))))
          process (fn [defn]
                    (let [raw-deps (or (:depends-on defn) [])
                          resolved (resolve-deps raw-deps)
                          defn' (if (seq resolved)
                                  (assoc defn :depends-on resolved)
                                  (dissoc defn :depends-on))
                          ch (hc/hash-with-intent {:hash/intent :claim-definition-conceptual} defn')]
                      (swap! concept-hash-cache assoc (:id defn) ch)
                      (assoc defn' :concept-hash ch)))]
      (mapv process (mapv id->defn topo-order)))))

(defn- attach-registry-hash
  [intent registry]
  (assoc registry :registry-hash (canonical-entry-hash intent registry)))

(declare validate-execution-registry-entries)

(def intent-definitions
  "Passive INTENT_REGISTRY_SPEC_V1 entries.
   These describe semantic intents used by hash projections and additive
   projection-based pro-rata artifacts."
  (mapv (partial attach-hash :intent-registry-entry :canonical-hash)
        [;; ══════════════════════════════════════════════════════════════════
         ;; Framework-general intent definitions (protocol-agnostic)
         ;; These are ready for any protocol to reference.
         ;; ══════════════════════════════════════════════════════════════════
         {:id :identity/intent-dsl
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :intent-dsl-identity
          :scope {:protocols #{:framework}
                  :domains #{:identity}
                  :modules #{:intent-dsl}}
          :inputs #{:intent-object}
          :constraints #{:canonical-safe :domain-separated}
          :output {:type :canonical-hash
                   :hash/intent :intent-dsl}
          :extensions-policy {:allowed? true
                              :require-namespaced-keys? true}
          :description "Canonical identity for INTENT_DSL_SPEC_V1 intent objects."}
         {:id :identity/intent-registry-entry
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :intent-registry-entry-identity
          :scope {:protocols #{:framework}
                  :domains #{:identity}
                  :modules #{:intent-registry}}
          :inputs #{:intent-registry-entry}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :intent-registry-entry}
          :description "Canonical identity for one registered intent entry."}
         {:id :identity/projection-definition
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :projection-definition-identity
          :scope {:protocols #{:framework}
                  :domains #{:projection}
                  :modules #{:projection-registry}}
          :inputs #{:projection-definition}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :projection-definition}
          :description "Canonical identity for one projection definition."}
         {:id :identity/projection-artifact
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :projection-artifact-identity
          :scope {:protocols #{:framework}
                  :domains #{:projection}
                  :modules #{:projection-artifacts}}
          :inputs #{:projection-artifact}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :projection-artifact}
          :description "Canonical identity for one projection artifact."}
         {:id :identity/claim-definition
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :claim-definition-identity
          :scope {:protocols #{:framework}
                  :domains #{:claims}
                  :modules #{:claim-registry}}
          :inputs #{:claim-definition}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :claim-definition}
          :description "Canonical identity for one claim definition."}
         {:id :identity/attestor
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :attestor-identity
          :scope {:protocols #{:framework}
                  :domains #{:attestation}
                  :modules #{:attestor-registry}}
          :inputs #{:attestor}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :attestor}
          :description "Canonical identity for one attestor registry entry."}
         {:id :identity/scenario
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :scenario-identity
          :scope {:protocols #{:framework}
                  :domains #{:scenario}
                  :modules #{:scenario}}
          :inputs #{:scenario}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :scenario}
          :description "Canonical identity for one scenario definition."}
           ;; ──────────────────────────────────────────────────────────────────
          ;; Protocol-specific intent definitions
          ;; Each entry is scoped to a specific protocol.
          ;; ──────────────────────────────────────────────────────────────────
         {:id :pro-rata/slash-obligation-allocation
          :version 1
          :intent/type :pro-rata/allocation
          :intent/purpose :slash-obligation-allocation
          :scope {:protocols #{:sew}
                  :domains #{:economic-allocation}
                  :modules #{:slashing}}
          :inputs #{:obligations :weights :caps :balances :eligible-participants}
          :constraints #{:pro-rata/conservation :pro-rata/non-negative
                                   :pro-rata/allocation-complete :pro-rata/quota-bounded}
          :output {:type :allocation-vector
                   :unit :wei
                   :rounding :floor-with-largest-remainder}
          :input-policy :exact
          :constraint-policy :require-all
          :extensions-policy {:allowed? true
                              :require-namespaced-keys? true}
          :description "Projection-based pro-rata allocation intent for Sew slash obligations."}]))

(def intent-registry
  (attach-registry-hash
   :intent-registry
   {:registry-version 1
    :intents intent-definitions}))

(def projection-definitions
  "Passive PROJECTION_DEFINITION_REGISTRY_SPEC_V1 entries."
  (mapv (partial attach-hash :projection-definition :canonical-hash)
        [;; ══════════════════════════════════════════════════════════════════
         ;; Framework-general projection definitions (protocol-agnostic)
         ;; These are ready for any protocol to reference.
         ;; ══════════════════════════════════════════════════════════════════
         {:id :projection/world-structure
          :version 1
          :projection-type :world-structure
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:intent-dsl-identity
                             :intent-registry-entry-identity}
          :source {:type :registry-artifact}
          :include-paths [[:registry-version] [:intents]]
          :exclude-paths [[:registry-hash]]
          :transforms [:canonical-artifact-value]
          :output {:type :structure-view}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}]}
         {:id :projection/projection-definition-registry
          :version 1
          :projection-type :registry-view
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:projection-definition-identity}
          :source {:type :projection-definition-registry}
          :include-paths [[:registry-version] [:projection-definitions]]
          :exclude-paths [[:registry-hash]]
          :transforms [:strip-self-hash-fields :canonical-artifact-value]
          :output {:type :definition-registry-view}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}]}
         {:id :projection/projection-artifact
          :version 1
          :projection-type :projection-artifact
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:projection-artifact-identity}
          :source {:type :projection-artifact}
          :include-paths [[:schema-version] [:projection-id] [:projection]]
          :exclude-paths [[:projection-hash]]
          :transforms [:strip-self-hash-fields :canonical-artifact-value]
          :output {:type :projection-artifact-view}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}]}
         {:id :projection/scenario-content
          :version 1
          :projection-type :scenario-content
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:scenario-content-identity}
          :source {:type :scenario-definition}
          :include-paths [[:scenario-id] [:scenario-path] [:protocol] [:dispatcher-id] [:normalized-scenario]]
          :exclude-paths [[:runtime-metadata] [:host-info] [:timestamps]]
          :transforms [:canonical-artifact-value]
          :output {:type :scenario-content-hash
                   :domain-tag "SCENARIO_V1"}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}]}
           ;; ──────────────────────────────────────────────────────────────────
         {:id :projection/scenario
          :version 1
          :projection-type :scenario-view
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:scenario-identity}
          :source {:type :scenario}
          :include-paths [[:scenario-id] [:scenario-path] [:protocol] [:dispatcher-id] [:normalized-scenario]]
          :exclude-paths []
          :transforms [:canonical-artifact-value]
          :output {:type :scenario-view}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}]}
           ;; ──────────────────────────────────────────────────────────────────
          ;; Protocol-specific projection definitions
          ;; Each entry is scoped to a specific protocol.
          ;; ──────────────────────────────────────────────────────────────────
         {:id :projection/pro-rata-slash-obligation
          :version 1
          :projection-type :pro-rata-allocation
          :intent-types #{:pro-rata/allocation}
          :intent-purposes #{:slash-obligation-allocation}
          :source {:type :allocation-input}
          :include-paths [[:amount]
                          [:items]
                          [:rounding]
                          [:remainder-policy]
                          [:ordering-policy]]
          :exclude-paths [[:id-fn]
                          [:weight-fn]
                          [:cap-fn]
                          [:runtime]
                          [:debug]
                          [:telemetry]]
          :transforms [:canonical-artifact-value]
          :output {:type :allocation-frame
                   :unit :wei
                   :rounding :floor-with-largest-remainder
                   :remainder-policy :unallocated
                   :ordering-policy :input-order
                   :required-keys #{:participants
                                    :eligible-participants
                                    :weights
                                    :caps
                                    :total-obligation
                                    :constraints}}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}
                   {:claim-id :pro-rata/allocation-complete
                    :required? true}
                   {:claim-id :pro-rata/non-negative
                    :required? true}
                   {:claim-id :pro-rata/conservation
                    :required? true}
                   {:claim-id :pro-rata/quota-bounded
                    :required? true}
                   {:claim-id :pro-rata/permutation-invariant
                    :required? true}]}]))

(def projection-definition-registry
  (attach-registry-hash
   :projection-definition-registry
   {:registry-version 1
    :projection-definitions projection-definitions}))

(def claim-definitions
  "Passive CLAIM_DEFINITION_REGISTRY_SPEC_V1 entries.
   Each entry has :canonical-hash (structural identity) and :concept-hash
   (transitive concept identity including resolved dependency hashes)."
  (enrich-claim-definitions
   (mapv (partial attach-hash :claim-definition :canonical-hash)
         [{:id :projection-deterministic
           :version 1
           :category :invariant
           :description "Projection output is deterministic for the same source input and definition."
           :inputs [:projection-definition :source-input :projection-artifact]
           :evaluation {:type :recompute-and-compare
                        :expected :same-projection-hash}
           :outputs [:passed? :expected-hash :actual-hash]}
          {:id :projection-canonical-safe
           :version 1
           :category :safety
           :description "Projection output contains only canonical hash-safe values."
           :inputs [:projection-artifact]
           :evaluation {:type :canonical-value-validation
                        :validator :resolver-sim.hash.canonical/validate-canonical-value!}
           :outputs [:passed? :violations]}
          {:id :registry-entry-hash-valid
           :version 1
           :category :audit
           :description "Registry entry self hash matches its registered canonical projection."
           :inputs [:registry-entry]
           :evaluation {:type :hash-recompute
                        :expected :entry-self-hash}
           :outputs [:passed? :expected-hash :actual-hash]}
          {:id :pro-rata/allocation-complete
           :version 1
           :category :invariant
           :description "Every eligible participant has a corresponding allocation row."
           :inputs [:projection-artifact :allocation-result]
           :evaluation {:type :set-coverage
                        :expected :eligible-participants-covered}
           :outputs [:passed? :missing-participants :extra-participants]}
          {:id :pro-rata/non-negative
           :version 1
           :category :invariant
           :description "Allocation, unmet, weight, and cap values are never negative."
           :inputs [:allocation-result]
           :evaluation {:type :numeric-predicate
                        :predicate :all-values-non-negative}
           :outputs [:passed? :violations]}
          {:id :pro-rata/conservation
           :version 1
           :category :invariant
           :description "Requested amount equals allocated plus unmet plus remainder."
           :inputs [:allocation-result]
           :evaluation {:type :arithmetic-equality
                        :left :total-requested
                        :right [:total-allocated :total-unmet :remainder]}
           :outputs [:passed? :difference]}
          {:id :pro-rata/quota-bounded
           :version 1
           :category :invariant
           :description "Rounding behavior is bounded by the registered allocation policy."
           :inputs [:projection-artifact :allocation-result]
           :evaluation {:type :policy-check
                        :policy :floor-with-largest-remainder}
           :outputs [:passed? :violations]}
          {:id :pro-rata/permutation-invariant
           :version 1
           :category :invariant
           :description "Allocation result is invariant under permutation of input items (multi-set equality)."
           :inputs [:allocation-input :allocation-result]
           :evaluation {:type :permutation-test
                        :policy :multi-set-equality}
           :outputs [:passed? :violations]}
          {:id :pro-rata-fairness
           :version 1
           :category :invariant
           :description "Exact pro-rata allocations require cross-product equality. Quantized largest-remainder allocations instead require bounded quota error and the declared deterministic remainder/tie-break ordering."
                      :inputs [:projection-artifact :allocation-result]
                      :evaluation {:type :policy-check
                                   :policy :exact-or-quantized-pro-rata-fairness}
           :outputs [:holds? :violations]}
          {:id :pro-rata/partial-fill-fairness
            :version 1
            :category :invariant
            :description "Pro-rata fairness over partial-fill decisions: exact allocations use cross-product equality; largest-remainder allocations use bounded exact quota error and deterministic remainder ordering."
                       :inputs [:evidence-nodes]
                       :evaluation {:type :policy-check
                                    :policy :exact-or-quantized-pro-rata-fairness}
            :outputs [:holds? :violations]}
           {:id :pro-rata/cap-respecting
            :version 1
            :category :invariant
            :description "Every persisted allocation row is bounded by its effective cap and requested amount."
            :inputs [:pro-rata-allocation-result]
            :evaluation {:type :policy-check :policy :pro-rata/cap-respecting}
            :outputs [:holds? :violations]}
           {:id :pro-rata/canonical-remainder-assignment
            :version 1
            :category :invariant
            :description "Largest-remainder awards follow the persisted canonical rank witness."
            :inputs [:pro-rata-allocation-result]
            :evaluation {:type :policy-check :policy :pro-rata/canonical-remainder-assignment}
            :outputs [:holds? :result :reason :violations]}
            ;; Protocol-specific claim definitions are registered dynamically
           ;; by protocol implementation namespaces via register-claim-definitions!.
           ;; See protocols_src/resolver_sim/evidence/forensic_claims.clj for
            ;; the Sew forensic-grade claims (registry-hash-verifies,
            ;; registry-hash-signed, cursor-verifies, tsa-token-verified,
            ;; evidence-chain-reconciled, forensic-grade).
          ])))

(def claim-definition-registry
  {:registry-version 1
   :claim-definitions claim-definitions})

(def attestors
  "Passive ATTESTOR_REGISTRY_SPEC_V1 entries."
  (mapv (partial attach-hash :attestor :attestor-hash)
        [{:id :local-development
          :version 1
          :type :validator
          :display-name "Local development validator"
          :status :active
          :verification {:type :local-process
                         :trust-boundary :developer-workstation}
          :delegates []
          :key-history []
          :metadata {:intended-use #{:tests :local-replay}}}
         {:id :ci-validation
          :version 1
          :type :ci-runner
          :display-name "CI validation runner"
          :status :active
          :verification {:type :public-key
                         :algorithm :ed25519
                         :key-id "ci-validation-placeholder"
                         :public-key "ci-validation-placeholder-public-key"}
          :delegates [{:id :ci-validation-signing-key
                       :status :active}]
          :key-history [{:key-id "ci-validation-v0"
                         :status :retired}
                        {:key-id "ci-validation-placeholder"
                         :status :active}]
          :metadata {:intended-use #{:validation :attestation}}}]))

(def attestor-registry
  {:registry-version 1
   :attestors attestors})

;; ── Execution Registry ─────────────────────────────────────────────────

(def execution-registry-definitions
  "EXECUTION_REGISTRY_SPEC_V1 entries: registered execution modes."
  [{:id :execution/simulation
    :version 1
    :kind :simulation
    :runner :phase-runner
    :entry 'resolver-sim.core.phases/run-simulation
    :execution/type :simulation
    :execution/mode :full
    :description "Full Monte Carlo simulation from protocol params."
    :claims #{:deterministic-replay :evidence-completeness}}
   {:id :execution/replay
    :version 1
    :kind :replay
    :runner :scenario-runner
    :entry 'resolver-sim.io.scenario-runner/run-and-report
    :execution/type :replay
    :execution/mode :deterministic
    :description "Deterministic replay from recorded trace."
    :claims #{:deterministic-replay :trace-fidelity}}
   {:id :execution/server
    :version 1
    :kind :service
    :runner :grpc-server
    :entry 'resolver-sim.server.grpc/start!
    :execution/type :server
    :execution/mode :long-running
    :description "Long-running gRPC server for interactive simulation."
    :claims #{:evidence-completeness}}
   {:id :execution/batch
    :version 1
    :kind :batch
    :runner :phase-runner
    :entry 'resolver-sim.core.phases/run-sweep
    :execution/type :batch
    :execution/mode :sweep
    :depends-on [:execution/simulation]
    :description "Parameter sweep or batch execution over multiple trials."
    :claims #{:deterministic-replay}}
   {:id :execution/diff
    :version 1
    :kind :differential
    :runner :differential-runner
    :entry 'resolver-sim.io.diff-runner/run-diff-traces!
    :execution/type :diff
    :execution/mode :comparison
    :depends-on [:execution/replay]
    :description "Trace diff between two simulation runs."
    :claims #{:trace-fidelity}}
   {:id :execution/validation
    :version 1
    :kind :validation
    :runner :registry-validator
    :entry 'resolver-sim.definitions.passive-registries/validate-all-registries!
    :execution/type :validation
    :execution/mode :static
    :description "Static validation of registries, fixtures, or scenarios."
    :claims #{}}
   {:id :execution/attestation
    :version 1
    :kind :attestation
    :runner :attestation-emitter
    :entry 'resolver-sim.evidence.node/build-execution-node
    :execution/type :attestation
    :execution/mode :inline
    :description "Attestation creation evidence node — records an attestation event as a DAG-verifiable evidence node."
    :claims #{}}
   {:id :execution/pro-rata-allocation
    :version 1
    :kind :allocation
    :runner :protocol-layer
    :entry 'resolver-sim.evidence.node/build-execution-node
    :execution/type :pro-rata
    :execution/mode :inline
    :description "Pro-rata allocation execution evidence node — records the full pro-rata computation chain (projection, allocation, claims, artifact) as a DAG-verifiable evidence node."
    :claims #{:pro-rata/allocation-complete :pro-rata/non-negative :pro-rata/conservation :pro-rata/quota-bounded :pro-rata/permutation-invariant}}
       {:id :execution/yield-accounting
    :version 1
    :kind :accounting
    :runner :protocol-layer
    :entry 'resolver-sim.evidence.node/build-execution-node
    :execution/type :yield-accounting
    :execution/mode :inline
    :description "Per-event yield accounting delta emitted during deterministic replay."
    :claims #{:conservation :trace-fidelity}}
   {:id :evidence/commitment-root
    :version 1
    :kind :commitment-root
    :runner :scenario-runner
    :entry 'resolver-sim.io.scenario-runner/run-and-report
    :execution/type :commitment-root
    :execution/mode :inline
    :description "Evidence commitment root node — post-hoc DAG anchor committing to the execution node, evidence chain cursor, and bundle root hash. This is the externally meaningful evidence anchor for a run."
    :claims #{}}])

(def execution-registry
  {:registry-version 1
   :executions execution-registry-definitions})

;; ── Evidence Policy Registry ───────────────────────────────────────────

(def evidence-policy-registry-definitions
  "EVIDENCE_POLICY_REGISTRY_SPEC_V1 entries: registered evidence policies."
  [{:id :evidence-policy/in-band
    :version 1
    :evidence-policy/type :capture
    :evidence-policy/source :action
    :description "Evidence captured inline during action execution."
    :constraints #{:deterministic :replayable}}
   {:id :evidence-policy/deterministic
    :version 1
    :evidence-policy/type :capture
    :evidence-policy/source :state
    :description "Evidence computable deterministically from world state alone."
    :constraints #{:state-derived :recomputable}}
   {:id :evidence-policy/out-of-band
    :version 1
    :evidence-policy/type :attestation
    :evidence-policy/source :external
    :description "External evidence submitted via out-of-band channel."
    :constraints #{:externally-sourced}}
   {:id :evidence-policy/attested
    :version 1
    :evidence-policy/type :attestation
    :evidence-policy/source :attestor
    :description "Evidence requiring explicit attestation for verification."
    :constraints #{:attestor-signed}}
   {:id :evidence-policy/computed
    :version 1
    :evidence-policy/type :computed
    :evidence-policy/source :derived
    :description "Evidence derived from existing evidence via transformation."
    :constraints #{:deterministic :derived}
    :classes #{:result :inputs :outputs :failures}
    :failure-policy {:include-expected-failures? false
                     :exclude-classes #{:environment :debug}}}])

(def evidence-policy-registry
  {:registry-version 1
   :evidence-policies evidence-policy-registry-definitions})

;; ── Hash Projection Registry ───────────────────────────────────────────

(defn- hash-intent->registry-entry
  [kw intent]
  (assoc intent
         :id kw
         :version (:intent/version intent)
         :hash-projection/id kw))

(def hash-projection-registry-definitions
  "HASH_PROJECTION_REGISTRY_SPEC_V1 entries: registered hash projections
   from resolver-sim.hash.canonical/hash-intents.
   Note: entries contain :intent/projection-fn which is not canonical-safe,
   so entry validation omits canonical hash checks for this registry."
  (mapv (partial hash-intent->registry-entry)
        (keys hc/hash-intents)
        (vals hc/hash-intents)))

(def hash-projection-registry
  {:registry-version 1
   :projections hash-projection-registry-definitions})

;; ── Domain Tag Registry ────────────────────────────────────────────────

(defn- domain-tag->registry-entry
  [[kw tag-string]]
  {:id kw
   :tag/id kw
   :tag/domain-string tag-string
   :version 1})

(def domain-tag-registry-definitions
  "DOMAIN_TAG_REGISTRY_SPEC_V1 entries: registered domain tags
   from resolver-sim.hash.canonical/domain-tags."
  (mapv domain-tag->registry-entry (seq hc/domain-tags)))

(def domain-tag-registry
  {:registry-version 1
   :domain-tags domain-tag-registry-definitions})

(declare validate-projection-definition-registry-entries)

(def ^:private registry-specs
  {:intent-registry
   {:registry intent-registry
    :entries-key :intents
    :required-registry-fields #{:registry-version :intents :registry-hash}
    :required-entry-fields #{:id :version :intent/type :intent/purpose
                             :scope :inputs :constraints :output
                             :description :canonical-hash}
    :hash-intent :intent-registry-entry
    :hash-key :canonical-hash}

   :projection-definition-registry
   {:registry projection-definition-registry
    :entries-key :projection-definitions
    :required-registry-fields #{:registry-version :projection-definitions :registry-hash}
    :required-entry-fields #{:id :version :projection-type :intent-types
                             :intent-purposes :source :output :claims
                             :canonical-hash}
    :hash-intent :projection-definition
    :hash-key :canonical-hash
    :registry-validator-fn #'validate-projection-definition-registry-entries}

   :claim-definition-registry
   {:registry claim-definition-registry
    :entries-key :claim-definitions
    :required-registry-fields #{:registry-version :claim-definitions}
    :required-entry-fields #{:id :version :category :description
                             :inputs :evaluation :outputs :canonical-hash}
    :hash-intent :claim-definition
    :hash-key :canonical-hash
    :registry-validator-fn #'validate-claim-definition-registry-entries}

   :attestor-registry
   {:registry attestor-registry
    :entries-key :attestors
    :required-registry-fields #{:registry-version :attestors}
    :required-entry-fields #{:id :type :display-name :status
                             :verification :attestor-hash}
    :hash-intent :attestor
    :hash-key :attestor-hash
    :registry-validator-fn #'validate-attestor-registry-entries}

   :execution-registry
   {:registry execution-registry
    :entries-key :executions
    :required-registry-fields #{:registry-version :executions}
    :required-entry-fields #{:id :version :kind :runner :entry
                             :execution/type :execution/mode
                             :description :claims}
    :registry-validator-fn #'validate-execution-registry-entries}

   :evidence-policy-registry
   {:registry evidence-policy-registry
    :entries-key :evidence-policies
    :required-registry-fields #{:registry-version :evidence-policies}
    :required-entry-fields #{:id :version :evidence-policy/type
                             :evidence-policy/source :description :constraints}}

   :hash-projection-registry
   {:registry hash-projection-registry
    :entries-key :projections
    :required-registry-fields #{:registry-version :projections}
    :required-entry-fields #{:id :version :intent/name :intent/domain-tag
                             :intent/description :intent/includes :intent/excludes
                             :intent/projection-fn}}

   :domain-tag-registry
   {:registry domain-tag-registry
    :entries-key :domain-tags
    :required-registry-fields #{:registry-version :domain-tags}
    :required-entry-fields #{:tag/id :tag/domain-string :version}}})

(defn- missing-fields
  [required m]
  (seq (sort (set/difference required (set (keys m))))))

(defn- duplicate-ids
  [entries]
  (->> entries
       (map :id)
       frequencies
       (filter (fn [[_ n]] (< 1 n)))
       (map first)
       sort
       seq))

(defn- error
  [code data]
  (assoc data :error code))

(def ^:private known-execution-runners
  {:phase-runner {:description "Core phase runner in resolver-sim.core.phases"}
   :scenario-runner {:description "Scenario/invariant runner in resolver-sim.io.scenario-runner"}
   :grpc-server {:description "Interactive gRPC server entry point"}
   :differential-runner {:description "Trace diff execution runner"}
   :registry-validator {:description "Registry validation entry point"}
   :attestation-emitter {:description "Attestation evidence node emitter for DAG integration"}
   :protocol-layer {:description "Protocol-layer inline execution for evidence node emission"}})

(def execution-runner-definitions
  "EXECUTION_RUNNER_SPEC_V1 entries: registered execution runners.
   These are the per-node runners that execute individual suites, distinct from
   the orchestrator that dispatches suite-level run-and-report."
  [{:id :runner/local-bb
    :version 1
    :kind :local-bb
    :capabilities #{:clojure :bb :filesystem :evidence-dag}
    :deterministic? true
    :trust-level :local
    :description "Local Babashka execution runner for canonical suite execution."
    :orchestrator-id :orchestrator/run-and-report-v1}
   {:id :runner/local-clojure
    :version 1
    :kind :local-clojure
    :capabilities #{:clojure :jvm :filesystem :evidence-dag :full-classpath}
    :deterministic? true
    :trust-level :local
    :description "Local Clojure JVM execution runner for canonical suite execution."
    :orchestrator-id :orchestrator/run-and-report-v1}])

(def execution-runner-registry
  {:registry-version 1
   :runners execution-runner-definitions})

(def known-execution-runner-ids
  "Validation whitelist: valid execution runner ids."
  (set (map :id execution-runner-definitions)))

(def orchestrator-definitions
  "ORCHESTRATOR_SPEC_V1 entries: registered suite-level orchestrators.
   Orchestrators dispatch run-and-report across execution runners.
   The :entry field points to the suite-level dispatch function."
  [{:id :orchestrator/run-and-report-v1
    :version 1
    :description "Primary orchestrator that dispatches suite-level execution.
                  Used by both :runner/local-bb and :runner/local-clojure."
    :entry 'resolver-sim.io.scenario-runner/run-and-report}])

(defn- namespace-resource-path
  [ns-sym]
  (str (-> (name ns-sym)
           (str/replace "." "/")
           (str/replace "-" "_"))
       ".clj"))

(defn- resolve-entry-point
  ([entry]
   (resolve-entry-point entry *entry-validation-mode*))
  ([entry entry-validation-mode]
   (when (symbol? entry)
     (try
       (case entry-validation-mode
         :strict
         (let [resolved (try
                          (requiring-resolve entry)
                          (catch Throwable _
                            nil))]
           resolved)

         :startup-safe
         (let [ns-sym (symbol (namespace entry))
               var-sym (symbol (name entry))
               loaded (find-ns ns-sym)
               resolved (when loaded (ns-resolve ns-sym var-sym))
               resource-exists? (boolean (io/resource (namespace-resource-path ns-sym)))]
           (or resolved
               (when resource-exists? entry)))

         (let [ns-sym (symbol (namespace entry))
               var-sym (symbol (name entry))
               loaded (find-ns ns-sym)
               resolved (when loaded (ns-resolve ns-sym var-sym))
               resource-exists? (boolean (io/resource (namespace-resource-path ns-sym)))]
           (or resolved
               (when resource-exists? entry))))
       (catch Throwable _
         nil)))))

(defn- cycle-path
  [graph node]
  (letfn [(visit [node stack seen]
            (cond
              (some #{node} stack)
              (conj (vec (drop-while #(not= node %) stack)) node)

              (seen node)
              nil

              :else
              (some #(visit % (conj stack node) (conj seen node))
                    (get graph node))))]
    (visit node [] #{})))

(def ^:private known-claim-evaluation-types
  #{:recompute-and-compare
    :canonical-value-validation
    :hash-recompute
    :set-coverage
    :numeric-predicate
    :arithmetic-equality
    :policy-check
    :permutation-test
    :code-reference})

(defn validate-claim-definition-registry-entries
  "Return claim-definition-registry-specific validation errors for entries.
   Enforces keyword ids, known dependency references, acyclic claim dependency
   graphs, and validates code-reference evaluators when present."
  ([registry-name entries]
   (validate-claim-definition-registry-entries registry-name entries
                                               :entry-validation-mode *entry-validation-mode*))
  ([registry-name entries & {:keys [entry-validation-mode]
                             :or {entry-validation-mode *entry-validation-mode*}}]
   (let [ids (set (keep :id entries))
         graph (into {}
                     (map (fn [{:keys [id depends-on]}]
                            [id (vec (or depends-on []))])
                          entries))]
     (vec
      (concat
       (mapcat (fn [{:keys [id evaluation depends-on]}]
                 (let [evaluation-type (:type evaluation)
                       evaluation-entry (:entry evaluation)
                       errors (cond-> []
                                (not (keyword? id))
                                (conj (error :entry/invalid-id
                                             {:registry registry-name
                                              :id id}))

                                (not (map? evaluation))
                                (conj (error :entry/invalid-evaluation
                                             {:registry registry-name
                                              :id id
                                              :evaluation evaluation}))

                                (and (map? evaluation) (not (keyword? evaluation-type)))
                                (conj (error :entry/invalid-evaluation-type
                                             {:registry registry-name
                                              :id id
                                              :evaluation-type evaluation-type}))

                                (and (keyword? evaluation-type)
                                     (not (contains? known-claim-evaluation-types evaluation-type)))
                                (conj (error :entry/unknown-evaluation-type
                                             {:registry registry-name
                                              :id id
                                              :evaluation-type evaluation-type
                                              :known-types (vec (sort known-claim-evaluation-types))}))

                                (and (some? depends-on) (not (vector? depends-on)))
                                (conj (error :entry/invalid-dependencies
                                             {:registry registry-name
                                              :id id
                                              :depends-on depends-on}))

                                (some #(not (contains? ids %)) (or depends-on []))
                                (conj (error :entry/unknown-dependencies
                                             {:registry registry-name
                                              :id id
                                              :unknown-dependencies (vec (filter #(not (contains? ids %)) (or depends-on [])))})))
                       errors (if (= :code-reference evaluation-type)
                                (cond-> errors
                                  (not (symbol? evaluation-entry))
                                  (conj (error :entry/invalid-evaluation-entry
                                               {:registry registry-name
                                                :id id
                                                :entry evaluation-entry}))

                                  (and (symbol? evaluation-entry)
                                       (nil? (resolve-entry-point evaluation-entry entry-validation-mode)))
                                  (conj (error :entry/unresolved-evaluation-entry
                                               {:registry registry-name
                                                :id id
                                                :entry evaluation-entry
                                                :entry-validation-mode entry-validation-mode})))
                                errors)]
                   errors))
               entries)
       (keep (fn [id]
               (when-let [cycle (cycle-path graph id)]
                 (error :registry/dependency-cycle
                        {:registry registry-name
                         :cycle cycle})))
             (keys graph)))))))

(defn validate-execution-registry-entries
  "Return execution-registry-specific validation errors for entries.
   Enforces globally unique keyword ids, known runners, resolvable entry points,
   known dependencies, and an acyclic dependency graph."
  ([registry-name entries]
   (validate-execution-registry-entries registry-name entries
                                        :entry-validation-mode *entry-validation-mode*))
  ([registry-name entries & {:keys [entry-validation-mode]
                             :or {entry-validation-mode *entry-validation-mode*}}]
   (let [ids (set (keep :id entries))
         graph (into {}
                     (map (fn [{:keys [id depends-on]}]
                            [id (vec (or depends-on []))])
                          entries))]
     (vec
      (concat
       (mapcat (fn [{:keys [id kind runner entry depends-on]}]
                 (cond-> []
                   (not (keyword? id))
                   (conj (error :entry/invalid-id
                                {:registry registry-name
                                 :id id}))

                   (not (keyword? kind))
                   (conj (error :entry/invalid-kind
                                {:registry registry-name
                                 :id id
                                 :kind kind}))

                   (not (keyword? runner))
                   (conj (error :entry/invalid-runner
                                {:registry registry-name
                                 :id id
                                 :runner runner}))

                   (and (keyword? runner) (not (contains? known-execution-runners runner)))
                   (conj (error :entry/unknown-runner
                                {:registry registry-name
                                 :id id
                                 :runner runner
                                 :known-runners (vec (sort (keys known-execution-runners)))}))

                   (not (symbol? entry))
                   (conj (error :entry/invalid-entry-point
                                {:registry registry-name
                                 :id id
                                 :entry entry}))

                   (and (symbol? entry) (nil? (resolve-entry-point entry entry-validation-mode)))
                   (conj (error :entry/unresolved-entry-point
                                {:registry registry-name
                                 :id id
                                 :entry entry
                                 :entry-validation-mode entry-validation-mode}))

                   (and (some? depends-on) (not (vector? depends-on)))
                   (conj (error :entry/invalid-dependencies
                                {:registry registry-name
                                 :id id
                                 :depends-on depends-on}))

                   (some #(not (contains? ids %)) (or depends-on []))
                   (conj (error :entry/unknown-dependencies
                                {:registry registry-name
                                 :id id
                                 :unknown-dependencies (vec (filter #(not (contains? ids %)) (or depends-on [])))}))))
               entries)
       (keep (fn [id]
               (when-let [cycle (cycle-path graph id)]
                 (error :registry/dependency-cycle
                        {:registry registry-name
                         :cycle cycle})))
             (keys graph)))))))

;; ── Projection Definition Registry Validation ───────────────────────────
;; PROJECTION_DEFINITION_REGISTRY_SPEC_V1: startup SHALL fail on:
;;   - unknown claim-id (not in claim-definition-registry)
;;   - duplicate claim-id within a projection's :claims
;;   - claim entry missing :required? flag
;;   - malformed claim reference (not a map, missing :claim-id)

(defn validate-projection-definition-registry-entries
  "Return projection-definition-registry-specific validation errors for entries.
   Checks that every :claims entry resolves to a registered claim definition,
   has no duplicates, carries a :required? flag, is well-formed, and that
   :depends-on references (if any) are valid projection definition IDs with
   an acyclic dependency graph."
  [registry-name entries]
  (let [known-claim-ids (set (keep :id claim-definitions))
        ids (set (keep :id entries))
        graph (into {}
                    (map (fn [{:keys [id depends-on]}]
                           [id (vec (or depends-on []))])
                         entries))]
    (vec
     (concat
      (mapcat (fn [{:keys [id claims depends-on]}]
                (let [claim-errs (if (not (vector? claims))
                                   [(error :projection/invalid-claims
                                           {:registry registry-name
                                            :id id
                                            :claims claims})]
                                   (let [claim-ids (mapv :claim-id claims)
                                         claim-ref-errors (mapcat (fn [claim]
                                                                    (let [cid (:claim-id claim)]
                                                                      (cond-> []
                                                                        (not (map? claim))
                                                                        (conj (error :projection/invalid-claim-reference
                                                                                     {:registry registry-name
                                                                                      :id id
                                                                                      :claim claim}))

                                                                        (and (map? claim) (nil? cid))
                                                                        (conj (error :projection/invalid-claim-reference
                                                                                     {:registry registry-name
                                                                                      :id id
                                                                                      :claim claim
                                                                                      :reason :missing-claim-id}))

                                                                        (and (map? claim) (some? cid) (not (contains? known-claim-ids cid)))
                                                                        (conj (error :projection/unknown-claim
                                                                                     {:registry registry-name
                                                                                      :id id
                                                                                      :claim-id cid
                                                                                      :known-claim-ids (vec (sort known-claim-ids))}))

                                                                        (and (map? claim) (not (contains? claim :required?)))
                                                                        (conj (error :projection/missing-required-flag
                                                                                     {:registry registry-name
                                                                                      :id id
                                                                                      :claim-id cid
                                                                                      :claim claim})))))
                                                                  claims)
                                         duplicates (keys (filter (fn [[_ n]] (< 1 n)) (frequencies claim-ids)))
                                         dup-errors (mapcat (fn [cid]
                                                              [(error :projection/duplicate-claim
                                                                      {:registry registry-name
                                                                       :id id
                                                                       :claim-id cid})])
                                                            (sort duplicates))]
                                     (into claim-ref-errors dup-errors)))
                      dep-errs (cond-> []
                                 (and (some? depends-on) (not (vector? depends-on)))
                                 (conj (error :entry/invalid-dependencies
                                              {:registry registry-name
                                               :id id
                                               :depends-on depends-on}))

                                 (some #(not (contains? ids %)) (or depends-on []))
                                 (conj (error :entry/unknown-dependencies
                                              {:registry registry-name
                                               :id id
                                               :unknown-dependencies (vec (filter #(not (contains? ids %)) (or depends-on [])))})))]
                  (into claim-errs dep-errs)))
              entries)
      (keep (fn [id]
              (when-let [cycle (cycle-path graph id)]
                (error :registry/dependency-cycle
                       {:registry registry-name
                        :cycle cycle})))
            (keys graph))))))

;; ── Attestor Registry Validation ────────────────────────────────────────
;; ATTESTOR_REGISTRY_SPEC_V1 §9: startup SHALL fail on:
;;   - duplicate ids (handled by generic validate-registry)
;;   - invalid verification method
;;   - duplicate active key ids
;;   - malformed public keys

(def ^:private known-verification-types
  #{:public-key :local-process})

(def ^:private known-key-algorithms
  #{:ed25519 :secp256k1})

(def ^:private known-attestor-statuses
  #{:active :revoked :retired :candidate :suspended :slashed})

(defn- validate-attestor-verification
  "Validate an attestor's :verification map.
   Returns a vector of error maps (possibly empty)."
  [registry-name id verification]
  (cond-> []
    (not (map? verification))
    (conj (error :entry/invalid-verification-method
                 {:registry registry-name :id id
                  :reason :not-a-map :verification verification}))
    (and (map? verification) (not (:type verification)))
    (conj (error :entry/invalid-verification-method
                 {:registry registry-name :id id
                  :reason :missing-type :type (:type verification)}))
    (and (map? verification) (:type verification)
         (not (contains? known-verification-types (:type verification))))
    (conj (error :entry/invalid-verification-method
                 {:registry registry-name :id id
                  :reason :unknown-type :type (:type verification)
                  :known-types (vec (sort known-verification-types))}))
    (and (map? verification) (= :public-key (:type verification)))
    (cond->
     (not (:algorithm verification))
      (conj (error :entry/invalid-verification-method
                   {:registry registry-name :id id
                    :reason :missing-algorithm}))
      (and (:algorithm verification)
           (not (contains? known-key-algorithms (:algorithm verification))))
      (conj (error :entry/invalid-verification-method
                   {:registry registry-name :id id
                    :reason :unknown-algorithm :algorithm (:algorithm verification)
                    :known-algorithms (vec (sort known-key-algorithms))}))
      (not (or (:key-id verification) (:active-key-id verification)))
      (conj (error :entry/missing-key-id
                   {:registry registry-name :id id}))
      (and (or (:key-id verification) (:active-key-id verification))
           (not (and (string? (or (:key-id verification) (:active-key-id verification)))
                     (seq (or (:key-id verification) (:active-key-id verification))))))
      (conj (error :entry/malformed-public-key
                   {:registry registry-name :id id
                    :reason :key-id-not-non-empty-string
                    :key-id (:key-id verification)}))
      (and (not (:public-key verification)) (not (:active-key-id verification)))
      (conj (error :entry/missing-public-key
                   {:registry registry-name :id id}))
      (and (:public-key verification)
           (not (and (string? (:public-key verification))
                     (seq (:public-key verification)))))
      (conj (error :entry/malformed-public-key
                   {:registry registry-name :id id
                    :reason :public-key-not-non-empty-string})))))

(defn- validate-attestor-delegates
  "Validate an attestor's :delegates vector.
   Returns a vector of error maps (possibly empty)."
  [registry-name id delegates]
  (cond-> []
    (not (vector? delegates))
    (conj (error :entry/invalid-delegates
                 {:registry registry-name :id id
                  :reason :not-a-vector}))
    (vector? delegates)
    (into (mapcat (fn [d idx]
                    (let [did (:id d)]
                      (cond-> []
                        (nil? did)
                        (conj (error :entry/invalid-delegate
                                     {:registry registry-name :id id
                                      :reason :missing-id
                                      :delegate-index idx}))
                        (and did (not (or (keyword? did) (string? did))))
                        (conj (error :entry/invalid-delegate
                                     {:registry registry-name :id id
                                      :reason :invalid-id-type
                                      :delegate-id did
                                      :delegate-index idx}))
                        (and (contains? d :status)
                             (not (contains? known-attestor-statuses (:status d))))
                        (conj (error :entry/invalid-delegate-status
                                     {:registry registry-name :id id
                                      :delegate-id did
                                      :delegate-index idx
                                      :status (:status d)
                                      :known-statuses (vec (sort known-attestor-statuses))})))))
                  delegates (range)))))

(defn- validate-attestor-key-history
  "Validate an attestor's :key-history vector.
   Returns a vector of error maps (possibly empty)."
  [registry-name id key-history]
  (cond-> []
    (not (vector? key-history))
    (conj (error :entry/invalid-key-history
                 {:registry registry-name :id id
                  :reason :not-a-vector}))
    (vector? key-history)
    (into (mapcat (fn [kh-entry idx]
                    (let [key-id (:key-id kh-entry)]
                      (cond-> []
                        (nil? key-id)
                        (conj (error :entry/invalid-key-history-entry
                                     {:registry registry-name :id id
                                      :reason :missing-key-id
                                      :key-history-index idx}))
                        (and key-id (not (string? key-id)))
                        (conj (error :entry/invalid-key-history-entry
                                     {:registry registry-name :id id
                                      :reason :key-id-not-string
                                      :key-id key-id
                                      :key-history-index idx}))
                        (and (contains? kh-entry :status)
                             (not (contains? known-attestor-statuses (:status kh-entry))))
                        (conj (error :entry/invalid-key-history-entry-status
                                     {:registry registry-name :id id
                                      :key-id key-id
                                      :key-history-index idx
                                      :status (:status kh-entry)
                                      :known-statuses (vec (sort known-attestor-statuses))})))))
                  key-history (range)))))

(defn- active-key-id-pairs
  "Return all [key-id source-info] pairs for active keys in an entry.
   Active key IDs come from:
   - primary verification :key-id (when attestor status is :active and verification type is :public-key)
   - delegate :id (when delegate status is :active or absent)
   - key-history :key-id (when key-history status is :active or absent)"
  [entry]
  (let [id (:id entry)
        status (:status entry)]
    (concat
     (when (and (= :active status)
                (= :public-key (get-in entry [:verification :type])))
       (when-let [kid (get-in entry [:verification :key-id])]
         [[kid {:source :primary :attestor-id id}]]))
     (keep (fn [d]
             (when (= :active (get d :status :active))
               (when-let [did (:id d)]
                 [did {:source :delegate :attestor-id id}])))
           (:delegates entry))
     (keep (fn [kh]
             (when (= :active (get kh :status :active))
               (when-let [kid (:key-id kh)]
                 [kid {:source :key-history :attestor-id id}])))
           (:key-history entry)))))

(defn- find-duplicate-active-key-ids
  "Detect key-id values that are active in more than one role across the registry.
   A key used as :primary and also listed in :key-history for the same attestor
   is NOT a duplicate — the key-history includes the current key.
   All other duplicate occurrences are flagged (same key-id in multiple delegates,
   multiple key-history entries, different attestors, etc.).
   Returns a vector of error maps."
  [entries]
  (let [all-pairs (mapcat active-key-id-pairs entries)
        by-kid (group-by first all-pairs)]
    (mapcat (fn [[key-id pairs]]
              (let [sources (mapv second pairs)
                    by-attestor (group-by :attestor-id sources)]
                (if (> (count by-attestor) 1)
                  ;; Key appears in multiple attestors — always a duplicate
                  [(error :entry/duplicate-active-key-id
                          {:key-id key-id
                           :sources sources})]
                  ;; Within a single attestor
                  (let [srcs (first (vals by-attestor))
                        roles (set (map :source srcs))]
                    (when (and (> (count srcs) 1)
                               (not= #{:primary :key-history} roles))
                      [(error :entry/duplicate-active-key-id
                              {:key-id key-id
                               :sources srcs})])))))
            by-kid)))

(def ^:private ed25519-public-key-hex #"[0-9a-f]{64}")
(def ^:private key-statuses #{:active :retired :revoked})

(defn- valid-instant? [value]
  (try (java.time.Instant/parse value) true (catch Exception _ false)))

(defn- validate-normalized-attestor-keys
  "Validate the WP2 :keys schema when a public-key attestor opts into it."
  [registry-name entry]
  (let [id (:id entry) keys (:keys entry) verification (:verification entry)]
    (cond
      (not= :public-key (:type verification)) []
      (and (contains? entry :keys) (not (vector? keys)))
      [(error :entry/invalid-attestor-keys {:registry registry-name :id id :reason :not-a-vector})]
      (not (vector? keys)) []
      :else
      (let [key-errors (mapcat (fn [key-entry idx]
                                 (let [key-id (:key-id key-entry) status (:status key-entry)
                                       public-key (:public-key key-entry) from (:valid-from key-entry)
                                       until (:valid-until key-entry)]
                                   (cond-> []
                                     (not (and (string? key-id) (seq key-id))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :invalid-key-id}))
                                     (not= :ed25519 (:algorithm key-entry)) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :unsupported-algorithm}))
                                     (not= :hex (:public-key-encoding key-entry)) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :unsupported-key-encoding}))
                                     (not (and (string? public-key) (re-matches ed25519-public-key-hex public-key) (not= public-key (apply str (repeat 64 "0"))))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :invalid-public-key}))
                                     (not (contains? key-statuses status)) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :invalid-key-status}))
                                     (not (and (string? from) (valid-instant? from))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :invalid-valid-from}))
                                     (and until (not (and (string? until) (valid-instant? until)))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :invalid-valid-until}))
                                     (and (= :revoked status) (not (and (string? (:revoked-at key-entry)) (valid-instant? (:revoked-at key-entry))))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :missing-revoked-at}))
                                     (and (= :revoked status) (nil? (:revocation-reason key-entry))) (conj (error :entry/invalid-attestor-key {:registry registry-name :id id :index idx :reason :missing-revocation-reason}))))) keys (range))
            ids (map :key-id keys) active-id (:active-key-id verification)
            registry-errors (cond-> []
                              (not= (count ids) (count (set ids))) (conj (error :entry/duplicate-attestor-key-id {:registry registry-name :id id}))
                              (not= 1 (count (filter #(= active-id (:key-id %)) keys))) (conj (error :entry/invalid-active-key-id {:registry registry-name :id id :key-id active-id})))]
        (vec (concat key-errors registry-errors))))))

(defn validate-attestor-registry-entries
  "Return attestor-registry-specific validation errors for entries.
   ATTESTATOR_REGISTRY_SPEC_V1 §9 checks:
   - :verification is a valid map with required fields
   - :delegates is a vector of valid delegates
   - :key-history is a vector of valid entries
   - no duplicate active key-ids across the registry"
  [registry-name entries]
  (vec
   (concat
    (mapcat (fn [entry]
              (let [id (:id entry)]
                (concat
                 (validate-attestor-verification registry-name id (:verification entry))
                 (when (contains? entry :status)
                   (let [s (:status entry)]
                     (when (and s (not (contains? known-attestor-statuses s)))
                       [(error :entry/invalid-status
                               {:registry registry-name :id id
                                :status s
                                :known-statuses (vec (sort known-attestor-statuses))})])))
                 (validate-attestor-delegates registry-name id (:delegates entry))
                 (validate-attestor-key-history registry-name id (:key-history entry))
                 (validate-normalized-attestor-keys registry-name entry))))
            entries)
    (find-duplicate-active-key-ids entries))))

;; ── Cross-Registry Alignment Validation ───────────────────────────────
;; Validates that passive intent-definitions (INTENT_REGISTRY_SPEC_V1) and
;; runtime hash-intents (HASH_INTENT_REGISTRY_SPEC_V1) are consistent.

(defn- hash-intent-ref
  "Extract the :hash/intent reference from a passive intent-definition entry.
   Returns the hash-intent keyword or nil if not a hash-projection entry."
  [entry]
  (get-in entry [:output :hash/intent]))

(defn validate-intent-registry-alignment
  "Cross-validate passive intent-definitions against runtime hash-intents.
   
   For each passive entry with :intent/type :identity/hash-projection:
     - :output :hash/intent must be present
     - the referenced hash intent must exist in hc/hash-intents
     - versions must match between passive and runtime
   
   For runtime hash intents:
     - lists any hash intent not referenced by a passive intent-definition
       (informational — some intents are projection-only, not semantic)
   
   Returns a vector of error maps (never throws)."
  [intent-definitions hash-intents-map]
  (let [hash-projection-entries (filter #(= :identity/hash-projection (:intent/type %))
                                        intent-definitions)]
    (vec
     (concat
      ;; Check 1: passive hash-projection entries must have :output :hash/intent
      ;;         and the referenced runtime hash intent must exist
      (mapcat (fn [entry]
                (let [hid (hash-intent-ref entry)
                      id (:id entry)
                      errors (cond-> []
                               (nil? hid)
                               (conj (error :cross/missing-hash-intent-ref
                                            {:registry :intent-registry
                                             :id id
                                             :reason :output-missing-hash-intent
                                             :output (:output entry)}))
                               (and hid (not (contains? hash-intents-map hid)))
                               (conj (error :cross/missing-hash-intent
                                            {:registry :intent-registry
                                             :id id
                                             :hash-intent hid
                                             :reason :not-found-in-runtime-registry})))]
                  ;; Version check (separate to avoid cond-> threading into let)
                  (if (and hid (contains? hash-intents-map hid))
                    (let [runtime-version (:intent/version (get hash-intents-map hid))
                          passive-version (:version entry)]
                      (if (and runtime-version passive-version
                               (not= runtime-version passive-version))
                        (conj errors
                              (error :cross/version-mismatch
                                     {:registry :intent-registry
                                      :id id
                                      :hash-intent hid
                                      :passive-version passive-version
                                      :runtime-version runtime-version}))
                        errors))
                    errors)))
              hash-projection-entries)

      ;; Check 2 is intentionally omitted: most runtime hash intents are
      ;; projection-only (world-structure, evidence-record, etc.) and do not
      ;; require passive intent-definitions. The hash-projection-registry in
      ;; passive_registries wraps all runtime hash intents automatically.
      ;; Only passive entries with :intent/type :identity/hash-projection
      ;; are required to map to a runtime hash intent (checked above).
      []))))

(defn- validate-entry-hash
  [{:keys [hash-intent hash-key registry-name]} entry]
  (when-let [expected (get entry hash-key)]
    (let [actual (canonical-entry-hash hash-intent entry)]
      (when (not= expected actual)
        (error :entry/hash-mismatch
               {:registry registry-name
                :id (:id entry)
                :hash-key hash-key
                :expected expected
                :actual actual})))))

(defn validate-registry
  "Validate one passive registry map. Returns {:valid? bool :errors [...]}
   and never throws."
  [registry-name registry spec]
  (let [{:keys [entries-key required-registry-fields required-entry-fields registry-validator-fn]
         :as spec} (assoc spec :registry-name registry-name)
        entries (get registry entries-key)
        registry-missing (missing-fields required-registry-fields registry)
        registry-errors (cond-> []
                          registry-missing
                          (conj (error :registry/missing-fields
                                       {:registry registry-name
                                        :missing (vec registry-missing)}))
                          (not= 1 (:registry-version registry))
                          (conj (error :registry/unsupported-version
                                       {:registry registry-name
                                        :version (:registry-version registry)}))
                          (not (vector? entries))
                          (conj (error :registry/entries-not-vector
                                       {:registry registry-name
                                        :entries-key entries-key})))
        duplicate-errors (if-let [ids (and (vector? entries) (duplicate-ids entries))]
                           [(error :entry/duplicate-ids
                                   {:registry registry-name
                                    :ids (vec ids)})]
                           [])
        entry-errors (if (vector? entries)
                       (mapcat (fn [entry]
                                 (let [entry-missing (missing-fields required-entry-fields entry)
                                       hash-error (validate-entry-hash spec entry)]
                                   (cond-> []
                                     entry-missing
                                     (conj (error :entry/missing-fields
                                                  {:registry registry-name
                                                   :id (:id entry)
                                                   :missing (vec entry-missing)}))
                                     (not (pos-int? (:version entry)))
                                     (conj (error :entry/invalid-version
                                                  {:registry registry-name
                                                   :id (:id entry)
                                                   :version (:version entry)}))
                                     hash-error
                                     (conj hash-error))))
                               entries)
                       [])
        registry-specific-errors (if (and (vector? entries) registry-validator-fn)
                                   (registry-validator-fn registry-name entries)
                                   [])
        errors (vec (concat registry-errors duplicate-errors entry-errors registry-specific-errors))]
    {:registry registry-name
     :valid? (empty? errors)
     :errors errors}))

(defn- registry-result
  [registry-name]
  (let [{:keys [registry] :as spec} (get registry-specs registry-name)]
    (validate-registry registry-name registry spec)))

(defn validate-intent-registry [] (registry-result :intent-registry))
(defn validate-projection-definition-registry [] (registry-result :projection-definition-registry))
(defn validate-claim-definition-registry [] (registry-result :claim-definition-registry))
(defn validate-attestor-registry [] (registry-result :attestor-registry))
(defn validate-execution-registry [] (registry-result :execution-registry))
(defn validate-evidence-policy-registry [] (registry-result :evidence-policy-registry))
(defn validate-hash-projection-registry [] (registry-result :hash-projection-registry))
(defn validate-domain-tag-registry [] (registry-result :domain-tag-registry))

(defn validate-passive-registries
  "Validate all registries and return an aggregate result.
   This function is passive and never throws.
   Includes all 8 registered registry types plus cross-registry alignment."
  ([] (validate-passive-registries {:entry-validation-mode :startup-safe}))
  ([{:keys [entry-validation-mode]
     :or {entry-validation-mode :startup-safe}}]
   (binding [*entry-validation-mode* entry-validation-mode]
     (let [results (mapv registry-result (keys registry-specs))
           registry-errors (vec (mapcat :errors results))
           ;; Cross-registry alignment: passive intent-definitions vs runtime hash-intents
           cross-errors (validate-intent-registry-alignment
                         intent-definitions hc/hash-intents)
           errors (vec (concat registry-errors cross-errors))]
       {:valid? (empty? errors)
        :results results
        :errors errors}))))

(defn- registry-summary
  "Build a summary map of all registry validation results for startup evidence."
  [validation-result]
  {:registry-count (count (:results validation-result))
   :valid? (:valid? validation-result)
   :registries (mapv (fn [r]
                       {:name (name (:registry r))
                        :valid? (:valid? r)
                        :error-count (count (:errors r))})
                     (:results validation-result))
   :generated-at (str (java.time.Instant/now))
   :schema-version "startup-validation.v1"})

(defn emit-startup-evidence!
  "Register a startup validation evidence record in the chain.
   Best-effort — failures are logged but do not halt execution.
   Uses requiring-resolve to avoid a hard dependency on chain when
   no evidence context is active."
  [validation-result]
  (try
    (let [register! (requiring-resolve 'resolver-sim.evidence.chain/register-evidence!)
          summary (registry-summary validation-result)
          h (hc/hash-with-intent {:hash/intent :startup-validation} summary)
          evidence {:artifact-kind :startup-validation
                    :evidence-hash h
                    :startup/valid? (:valid? validation-result)
                    :startup/registry-count (:registry-count summary)
                    :startup/registries (:registries summary)
                    :startup/generated-at (:generated-at summary)
                    :startup/schema-version (:schema-version summary)}]
      (register! evidence))
    (catch Exception e
      (log/warn! :startup-evidence-failed
                 {:error (.getMessage e)
                  :valid? (:valid? validation-result)}))))

(defn validate-all-registries!
  "Validate all registries. Throws on any validation failure.
   Called at namespace load. This is a hard-fail — the system will not
   start with corrupt registries. Startup validation does not persist an
   execution node because no authoritative run root exists at load time.

   Set *startup-registry-validation-enabled* to false to disable
   (for test fixtures that need to load without valid registries)."
  ([] (validate-all-registries! {:entry-validation-mode :startup-safe}))
  ([{:keys [entry-validation-mode]
     :or {entry-validation-mode :startup-safe}}]
   (when *startup-registry-validation-enabled*
     (binding [*entry-validation-mode* entry-validation-mode]
       ;; Namespace-load validation runs before a CLI command can establish an
       ;; authoritative run root. It must therefore remain a process health
       ;; check, not emit a persisted execution node into the caller's CWD.
       (let [result (validate-passive-registries)]
         (when-not (:valid? result)
           (throw (ex-info "Registry validation failed — system cannot start with corrupt registries"
                           {:results (:results result)
                            :errors (:errors result)})))
         (hc/validate-registry!)
         (emit-startup-evidence! result)
         nil)))))

(def validate-passive-registries!
  "Legacy alias for validate-all-registries!."
  (fn
    ([] (validate-all-registries!))
    ([opts]
     (validate-all-registries! opts))))

;; ── Attestor Registry Runtime Query ─────────────────────────────────────
;; ATTESTOR_REGISTRY_SPEC_V1 runtime lookup functions.
;; These are the only entrypoints for runtime attestor identity checks.

(defn find-attestor
  "Look up an attestor entry by :id in the attestor-registry.
   Returns the attestor map or nil if not found."
  [attestor-id]
  (some #(when (= (:id %) attestor-id) %)
        (:attestors attestor-registry)))

(defn attestor-status
  "Returns the current :status of an attestor entry.
   One of :active, :revoked, :retired."
  [attestor]
  (:status attestor))

(defn attestor-active?
  "True if the attestor's current status is :active."
  [attestor]
  (= :active (:status attestor)))

(defn attestor-revoked?
  "True if the attestor's current status is :revoked."
  [attestor]
  (= :revoked (:status attestor)))

(defn- id-match?
  "Match two identifiers regardless of keyword/string type.
   e.g., (id-match? :foo \"foo\") => true, (id-match? \"bar\" :bar) => true,
   (id-match? :foo :foo) => true, (id-match? \"bar\" \"bar\") => true."
  [a b]
  (= (name a) (name b)))

(defn key-history-entry
  "Find a key-history entry for key-id. Returns the entry map or nil."
  [attestor key-id]
  (some #(when (id-match? (:key-id %) key-id) %)
        (:key-history attestor)))

(defn delegate-entry
  "Find a delegate entry by delegate-id. Returns the entry map or nil."
  [attestor delegate-id]
  (some #(when (id-match? (:id %) delegate-id) %)
        (:delegates attestor)))

(defn key-authorized-for-attestor?
  "True if key-id is currently authorized for this attestor.
   Checks, in order:
   1. Is key-id the attestor's primary verification key?
   2. Is key-id an active delegate?
   3. Is key-id active in key-history?
   Returns false if the key is unknown or retired for this attestor."
  [attestor key-id]
  (or (id-match? (:key-id (:verification attestor)) key-id)
      (let [delegate (delegate-entry attestor key-id)]
        (and delegate (= :active (:status delegate))))
      (let [history (key-history-entry attestor key-id)]
        (and history (= :active (:status history))))))

(defn key-known-for-attestor?
  "True if key-id is known for this attestor, regardless of status.
   Covers primary key, all key-history entries, and all delegates.
   This is the broadest check — used for historical attestation verification."
  [attestor key-id]
  (or (id-match? (:key-id (:verification attestor)) key-id)
      (boolean (key-history-entry attestor key-id))
      (boolean (delegate-entry attestor key-id))))

;; ── Startup validation ─────────────────────────────────────────────────
;; Runs when this namespace is loaded.
(when *startup-registry-validation-enabled*
  (validate-all-registries!))
