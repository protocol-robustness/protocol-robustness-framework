(ns resolver-sim.protocols.sew.evidence.slashing
  "Domain-specific constructors for slashing evidence.

   Claims are now produced through resolver-sim.claims.engine/evaluate-claims
   with explicit evidence-node references, not through raw allocation-input
tunneling."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.evidence.aggregate :as agg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.pro-rata.claims :as pro-rata-claims]))

;; ── Extractors ──────────────────────────────────────────────────────────

(defn slash-subject-reference
  "Qualified protocol-entity reference for slash evidence.
   Runtime slash IDs are only meaningful within the protocol and workflow;
   run/scenario context belongs in the enclosing evidence envelope."
  [slash-id workflow-id]
  {:entity/type :slash
   :entity/id slash-id
   :workflow/id workflow-id
   :protocol/id :sew
   :protocol/version 1})

(defn extract-slash-context
  "Extract slash-specific context."
  [slash-id workflow-id epoch trigger]
  {:subject/ref (slash-subject-reference slash-id workflow-id)
   :slash-id slash-id
   :workflow-id workflow-id
   :epoch epoch
   :trigger trigger})

(defn extract-strategy-context
  "Extract strategy context for all resolvers in the world."
  [world]
  {:schema-version "strategy-context.v1"
   :snapshot-time (:block-time world)
   :snapshot-step (:step world 0)
   :resolvers
   (mapv (fn [[id resolver]]
           {:resolver-id id
            :strategy (:strategy resolver)
            :strategy-source (:strategy-source resolver :unknown)
            :strategy-since-step (:strategy-since-step resolver 0)
            :slashable-balance (:slashable-stake resolver 0)})
         (sort-by first (:resolver-stakes world)))})

;; ── Claim evaluation evidence node ─────────────────────────────────────

(defn- claim-allocation-view
  "Remove derived ratio values before canonical evidence hashing. Exact integer
   allocation facts remain intact; a ratio can be deterministically recomputed
   from basis and total basis by an evaluator when needed."
  [result]
  (-> result
      ;; Legacy projection claims compare SEW presentation facts only. The
      ;; complete generic witness is referenced by the domain result but is
      ;; deliberately validated through mechanism evidence, not reconstructed
      ;; by projection artifacts.
      (dissoc :mechanism/evidence :mechanism/evidence-reference)
      (update :allocations
              (fn [rows]
                (mapv #(dissoc % :share) (or rows []))))))

(defn build-claim-evaluation-node
  "Build a lightweight evidence node carrying the allocation facts needed by
   claim evaluators. Hash is computed on the node content for deterministic
   addressing."
  [allocation-input projection-artifact allocation-result
   projection-artifact-again projection-result]
  (let [permuted-input (update allocation-input :liable-parties
                               #(vec (reverse (or % []))))
        direct-result-permuted
        (sew-economics/calculate-sew-slash-allocation permuted-input)
        projection-artifact-permuted
        (sew-economics/build-sew-slash-projection-artifact permuted-input)
        projection-result-permuted
        (sew-economics/calculate-sew-slash-allocation-from-projection
         projection-artifact-permuted)
        content {:claims/input-context
                 {:liable-parties (:liable-parties allocation-input [])
                  :total-basis (long (:total-basis allocation-result 0))
                  :slash-obligation (or (:slash-obligation allocation-input)
                                        (:slash-amount allocation-input)
                                        0)
                  :basis-field (:basis allocation-input :slashable-stake)
                  :cap-field (:cap-field allocation-input :available-slashable)
                  :unmet-policy (:unmet-policy allocation-input :record-only)}
                 :claims/direct-result (claim-allocation-view allocation-result)
                 :claims/direct-result-permuted (claim-allocation-view direct-result-permuted)
                 :claims/projection-artifact projection-artifact
                 :claims/projection-artifact-again projection-artifact-again
                 :claims/projection-result (claim-allocation-view projection-result)
                 :claims/projection-result-permuted (claim-allocation-view projection-result-permuted)}
        node-hash (hc/hash-with-intent {:hash/intent :evidence-content} content)]
    {:node-hash node-hash
     :result content
     :claims/evaluation-context true}))

(defn legacy-projection-claim-ids
  "Claim IDs that can be evaluated from the legacy SEW projection evidence.
   Witness-backed claims run only against mechanism-result evidence."
  []
  (vec (remove #{:pro-rata/cap-respecting
                 :pro-rata/canonical-remainder-assignment}
               (pro-rata-claims/registered-claim-ids))))

(defn build-claim-requests
  "Build claim requests referencing the claim-evaluation node hash."
  [evaluation-node-hash]
  (let [legacy-projection-claim-ids (legacy-projection-claim-ids)]
    (mapv (fn [claim-id]
            {:claim-id claim-id
             :evidence-references [evaluation-node-hash]})
          legacy-projection-claim-ids)))

;; ── Result shaping ─────────────────────────────────────────────────────

(defn- claim-definition-by-id
  [claim-id]
  (some #(when (= claim-id (:id %)) %)
        (:claim-definitions registries/claim-definition-registry)))

(defn- hash-safe-value
  "Canonical hashing deliberately rejects Clojure Ratio values. Claim evaluators
   use exact ratios for capped pro-rata witnesses, so persist their explicit
   numerator/denominator representation rather than lossy decimal coercion."
  [value]
  (cond
    (ratio? value) {:ratio/numerator (numerator value)
                    :ratio/denominator (denominator value)}
    (map? value) (into {} (map (fn [[k v]] [k (hash-safe-value v)])) value)
    (vector? value) (mapv hash-safe-value value)
    (seq? value) (mapv hash-safe-value value)
    :else value))

(defn- claim-result-entry
  [claim-id claim-result]
  (let [definition (claim-definition-by-id claim-id)
        base {:claim-id claim-id
              :claim-definition-hash (:canonical-hash definition)
              :claim-definition-concept-hash (:concept-hash definition)
              :holds? (boolean (:holds? claim-result))
              :violations (hash-safe-value (vec (:violations claim-result)))
              :status (if (:holds? claim-result) :pass :fail)}]
    (assoc base
           :claim-result-hash (hc/hash-with-intent {:hash/intent :evidence-record} base))))

(defn- claim-summary
  [claim-results]
  {:claim-count (count claim-results)
   :passed-count (count (filter :holds? claim-results))
   :failed-count (count (remove :holds? claim-results))
   :holds? (every? :holds? claim-results)})

(defn- projection-summary
  [projection-artifact]
  (select-keys projection-artifact
               [:projection-hash :projection-definition-hash :summary]))

(defn- pro-rata-summary
  [allocation-result]
  (let [allocations (:allocations allocation-result [])
        capped-count (count (filter #(pos? (:unmet % 0)) allocations))]
    (assoc (select-keys allocation-result
                        [:total-requested :total-allocated :total-unmet :remainder :policy])
           :capped-party-count capped-count)))

;; ── Execution node emission ───────────────────────────────────────────

(defn emit-claim-eval-execution-node!
  "Persist a claim-evaluation content map as an execution evidence node.
   Returns the node map (including :node-hash)."
  [claim-eval-content]
  (node/emit-execution-node!
   {:execution-id :execution/pro-rata-allocation
    :status :pass
    :inputs claim-eval-content
    :outputs {:type :claim-evaluation
              :claim-count (count (get-in claim-eval-content [:claims/input-context :liable-parties]))}
    :extensions {:pro-rata/type :claim-evaluation}
    :execution-kind :claim-evaluation
    :runner :protocol-layer}))

(defn emit-pro-rata-execution-node!
  "Emit a persisted execution evidence node for the full pro-rata computation.
   Links projection artifact, re-projection, allocation result, claim-evaluation
   node, and final evidence/artifact hashes.

   :scenario-replay-node-hash is optional — set when the replay scenario node
   hash is known (e.g., from a containing scenario-replay execution node).

   Reference hashes (projection, allocation-result, evidence) are stored in
   :extensions for researcher visibility. The :inputs and :outputs fields
   are content-addressed hashes of the raw computation data.

   Returns the execution node map (including :node-hash)."
  [{:keys [projection-artifact projection-artifact-again
           claim-eval-node-hash evidence artifact
           allocation-result scenario-replay-node-hash
           slash-id workflow-id]}]
  (node/emit-execution-node!
   {:execution-id :execution/pro-rata-allocation
    :status :pass
    :parent-hashes (cond-> [claim-eval-node-hash]
                     scenario-replay-node-hash (conj scenario-replay-node-hash))
    :inputs {:projection-artifact (select-keys projection-artifact [:projection-hash :projection-definition-hash])
             :allocation-input (select-keys (get-in evidence [:evidence/inputs :allocation] {})
                                            [:slash-obligation :liable-parties :basis :cap-field])}
    ;; Output values are integrity-committed by the node policy; these stable
    ;; extensions remain directly inspectable by package-level verifiers.
    :outputs {:evidence-hash (:evidence/hash evidence)
              :allocation-result-hash (or (:allocation-result-hash allocation-result)
                                          (:allocation-result-hash artifact))
              :allocation-artifact-hash (:allocation-result-hash artifact)}
    :extensions {:mechanism/id :mechanism/pro-rata-allocation
                 :mechanism/version 1
                 :mechanism/node-schema-version "pro-rata-mechanism-node.v1"
                 :pro-rata/type :pro-rata-allocation
                 :pro-rata/evidence-hash (:evidence/hash evidence)
                 :pro-rata/projection-hash (:projection-hash projection-artifact)
                 :pro-rata/re-projection-hash (:projection-hash projection-artifact-again)
                 :pro-rata/allocation-result-hash (or (:allocation-result-hash allocation-result)
                                                      (:allocation-result-hash artifact))
                 :pro-rata/artifact-hash (:allocation-result-hash artifact)
                 :pro-rata/claim-eval-node-hash claim-eval-node-hash
                 :pro-rata/slash-id slash-id
                 :pro-rata/workflow-id workflow-id}
    :execution-kind :pro-rata-allocation
    :runner :protocol-layer}))

;; ── Main evidence constructor ──────────────────────────────────────────

(defn build-prorata-slash-evidence
  "Build pro-rata slash evidence with embedded allocation result artifact.

   Two-phase: builds artifact without evidence-record-hash first, finalizes
   evidence hash, then rebuilds artifact with the evidence hash in
   :external-refs (excluded from canonical hash — no hash identity impact).

   Evidence linking model (fraud slash chain):
     proposal evidence ───────────────────→ allocation evidence
       (:fraud-slash-proposed)               (:evidence/dependencies)
                                             
     stake evidence ──────────────────────→ allocation evidence
       (:slashing,                           (:evidence/dependencies)
        slash-resolver-stake)
                                             
                                             allocation evidence ──→ result artifact
                                               (:pro-rata              (:external-refs
                                                :allocation-            :evidence-record-hash,
                                                result-hash             :evidence-group-id)
     
     All evidence in the same replay event shares :evidence/group-id
     (set via attribution context), discoverable via linked-evidence-group.
     The chain cursor (chain-seq/chain-prev-hash) provides total temporal
     ordering across all targeted protocol evidence.

   Returns {:evidence <evidence-map> :artifact <pro-rata-allocation-result-artifact>}."
  [{:keys [world
           slash-id
           workflow-id
           epoch
           trigger
           resolver
           allocation-input
           projection-artifact
           allocation-result
           execution-result
           transition-dependencies
           attribution
           world-before-hash
           action-hash
           action-hash-at
           scenario-replay-node-hash]}]
  (let [projection-artifact (or projection-artifact
                                (sew-economics/build-sew-slash-projection-artifact
                                 (cond-> allocation-input
                                   world-before-hash (assoc :world-before-hash world-before-hash)
                                   action-hash-at (assoc :action-hash-at action-hash-at))))
        projection-artifact-again (sew-economics/build-sew-slash-projection-artifact
                                   (cond-> allocation-input
                                     world-before-hash (assoc :world-before-hash world-before-hash)
                                     action-hash-at (assoc :action-hash-at action-hash-at)))
        projection-result (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact)
        claim-eval-node (build-claim-evaluation-node
                         allocation-input projection-artifact allocation-result
                         projection-artifact-again projection-result)
        claim-eval-hash (:node-hash claim-eval-node)
        ;; Persist claim-evaluation node as an execution evidence node
        claim-eval-persisted (emit-claim-eval-execution-node! (:result claim-eval-node))
        claim-eval-node-hash (:node-hash claim-eval-persisted)
        claim-requests (build-claim-requests claim-eval-hash)
        {:keys [claim-results]}
        (claims-engine/evaluate-claims
         claim-requests [claim-eval-node]
         {:evaluator-resolver pro-rata-claims/evaluator-resolver})
        shaped-claims (mapv (fn [cr]
                              (claim-result-entry (:claim-id cr) cr))
                            claim-results)
        world-after-hash (hc/hash-with-intent {:hash/intent :world-structure} world)
        artifact-base-opts {:projection-artifact projection-artifact
                            :allocation-result allocation-result
                            :world-before-hash world-before-hash
                            :world-after-hash world-after-hash
                            :action-hash action-hash
                            :action-hash-at action-hash-at
                            :allocation-input allocation-input
                            :claims shaped-claims
                            :invariant-links []
                            :attribution attribution
                            :metadata (when-let [sp (:slash-policy allocation-input)]
                                        {:slash-policy sp})}
        ;; Phase 1: build artifact without evidence-record-hash
        result-artifact-v1 (payoffs/build-pro-rata-allocation-result-artifact
                            artifact-base-opts)
        allocation-result-hash (:allocation-result-hash result-artifact-v1)
        allocation-hash (hc/hash-with-intent {:hash/intent :evidence-content} allocation-result)
        execution-summary (when execution-result
                            (let [rows (:allocations execution-result)
                                  total (fn [field] (reduce + 0 (map #(or (get % field) 0) rows)))]
                              {:rows rows
                               :paid-total (total :actual-paid)
                               :stayed-total (reduce + 0 (map #(if (= :stayed (:execution-status %))
                                                                 (or (:paid %) 0) 0) rows))
                               :unpaid-total (reduce + 0 (map #(if (= :unpaid (:execution-status %))
                                                                  (or (:paid %) 0) 0) rows))}))
        evidence-result {:projection (projection-summary projection-artifact)
                         :pro-rata {:intent {:id :pro-rata/slash-obligation-allocation
                                             :version 1}
                                    :projection-hash (:projection-hash projection-artifact)
                                    :allocation-hash allocation-hash
                                    :allocation-result-hash allocation-result-hash
                                    :claims shaped-claims
                                    :summary (merge (claim-summary shaped-claims)
                                                    (pro-rata-summary allocation-result))}
                         :allocation allocation-result
                         ;; Allocation is the authoritative mathematical claim.
                         ;; Any execution rows are observational until a separate
                         ;; stake-state/debit reconciliation witness is supplied;
                         ;; allocation must never be read as proof of collection.
                         :execution-boundary {:authority :allocation-only
                                              :execution-claim :not-established
                                              :required-for-execution-claim
                                              [:authoritative-stake-state
                                               :actual-debit
                                               :destination-credit-or-burn
                                               :execution-time-uncollected
                                               :post-state-reconciliation]}
                         ;; Proposal allocation is immutable. This projection may
                         ;; report execution effects after appeals, but is not a
                         ;; reallocation and does not upgrade the claim boundary.
                         :execution execution-summary}
        ;; Targeted evidence is finalized independently of the generic replay
        ;; event. Bind its scenario identity before hashing so it remains in the
        ;; same persisted scenario chain rather than being written as `unknown`.
        scenario-id (or (:ctx/scenario-id attribution)
                        (get-in world [:params :scenario-id]))
        evidence-record (assoc (agg/build-evidence-aggregate
                         {:evidence-type :slash/prorata-allocation
                          :schema-version "slash-prorata-allocation.v2"
                          :world world
                          :frame (agg/extract-decision-frame world)
                          :subject {:subject/ref (slash-subject-reference slash-id workflow-id)
                                                              :slash-id slash-id
                                                              :workflow-id workflow-id
                                                              :resolver resolver
                                    :epoch epoch
                                    :trigger trigger}
                          :inputs {:allocation allocation-input
                                   :strategy/context (extract-strategy-context world)
                                   :slash/context (extract-slash-context slash-id workflow-id epoch trigger)}
                          :result evidence-result
                          :dependencies transition-dependencies
                          :attribution attribution})
                               :scenario/id scenario-id)
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-content}
                                           (dissoc evidence-record :evidence/hash :evidence-hash))
        evidence (assoc evidence-record :evidence/hash evidence-hash)
        ;; Phase 2: rebuild artifact with evidence-record-hash and evidence-group-id in :external-refs
        result-artifact (payoffs/build-pro-rata-allocation-result-artifact
                         (assoc artifact-base-opts
                                :evidence-record-hash evidence-hash
                                :evidence-group-id (:ctx/evidence-group-id attribution)))
        ;; Emit top-level pro-rata execution node referencing all computation hashes
        pro-rata-exec-node (emit-pro-rata-execution-node!
                            {:projection-artifact projection-artifact
                             :projection-artifact-again projection-artifact-again
                             :claim-eval-node-hash claim-eval-node-hash
                             :evidence evidence
                             :artifact result-artifact
                             :allocation-result allocation-result
                             :scenario-replay-node-hash scenario-replay-node-hash
                             :slash-id slash-id
                             :workflow-id workflow-id})
        pro-rata-exec-node-hash (:node-hash pro-rata-exec-node)
        ;; Include claim-eval-node-hash and pro-rata-exec-node-hash in evidence dependencies
        evidence (update evidence :evidence/dependencies
                         (fnil conj [])
                         {:node-hash claim-eval-node-hash
                          :type :claim-evaluation})
        evidence (update evidence :evidence/dependencies
                         (fnil conj [])
                         {:node-hash pro-rata-exec-node-hash
                          :type :pro-rata-allocation})]
    {:evidence evidence
     :artifact result-artifact}))

(defn write-allocation-result-artifact!
  "Persist a pro-rata allocation result artifact to disk and register it in the
   chain registry. Returns the file path written."
  [artifact]
  (let [result-id (:allocation-result-id artifact)
        result-hash (:allocation-result-hash artifact)
        _ (when-not (and (string? result-hash) (<= 16 (count result-hash)))
            (throw (ex-info "Allocation artifact requires a content hash"
                            {:reason :missing-allocation-result-hash
                             :allocation-result-id result-id})))
        hash-suffix (subs result-hash 0 16)
        ;; Presentation paths are content-addressed so two allocations with an
        ;; identical logical ID cannot overwrite one another. The artifact
        ;; registry remains authoritative.
        filename (str "allocation-result-" result-id "-" hash-suffix ".json")
        registry-id (keyword (str result-id "-" hash-suffix))
        out-dir (str (evcfg/artifact-dir))
        f (io/file out-dir filename)]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str artifact {:key-fn name :indent true}))
    (println "Wrote allocation result artifact:" filename)
    (chain/register-additional-artifact!
     (chain/index-artifact-entry registry-id filename
                                 "allocation-result.v1" "CORE"))
    (.getPath f)))
