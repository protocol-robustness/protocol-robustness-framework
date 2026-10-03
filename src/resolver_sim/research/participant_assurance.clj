(ns resolver-sim.research.participant-assurance
  "Pairwise assurance for attributed interaction reproductions. Canonical
   references establish only reference/artifact distinctness. Real-world
   assertions remain declared; absent verified evidence remains unknown."
  (:require [resolver-sim.benchmark.researcher-axis :as axis]
            [resolver-sim.research.evidence-attribution :as attribution]
            [resolver-sim.research.execution-assurance :as implementation]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def schema "research-participant-assurance.v1")
(def real-world-dimensions
  [:person-distinct :organization-distinct :operator-distinct :machine-distinct
   :authenticated-identities :hardware-attested])

(defn assurance-root [artifact]
  (reference/sha256-ref
   (canonical/domain-hash :research-participant-assurance-v1
                          (dissoc artifact :research-participant-assurance/root))))

(defn- contribution [researcher-axis context]
  (let [{:keys [attribution implementation execution original evidence]} context]
    (when-not (= #{:attribution :implementation :execution :original :evidence} (set (keys context)))
      (throw (ex-info "Exact reproduction context required" {})))
    (attribution/validate-attribution attribution researcher-axis)
    (when-not (and (implementation/valid-implementation? implementation)
                   (= (:research-implementation-identity/root implementation)
                      (:research-implementation-identity/root execution))
                   (= evidence (reproduction/compare-execution execution original))
                   (= (:attribution/evidence-root attribution) (:reproduction-evidence/root evidence))
                   (= (get-in attribution [:attribution/subject :subject/root])
                      (:authoritative-result/root evidence))
                   (= (get-in attribution [:attribution/subject :subject/kind]) :research/interaction)
                   (= (:attribution/relation attribution)
                      (case (:reproduction/status evidence)
                        :reproduced :reproduced
                        :counterexample-found :reported-counterexample
                        nil)))
      (throw (ex-info "Detached participant/reproduction/implementation context" {})))
    {:contribution/attribution-root (:researcher-evidence-attribution/root attribution)
     :contribution/coordinate (:attribution/coordinate attribution)
     :contribution/disclosure (:attribution/disclosure attribution)
     :contribution/execution-root (:reproduction-execution/root execution)
     :contribution/input-root (:reproduction-input/root execution)
     :contribution/result-root (:authoritative-result/root evidence)
     :contribution/reproduction-root (:reproduction-evidence/root evidence)
     :contribution/status (:reproduction/status evidence)
     :contribution/implementation-root (:research-implementation-identity/root implementation)
     :contribution/implementation-artifact-root (:implementation/artifact-root implementation)}))

(defn- declaration [d]
  (when-not (and (= #{:dimension :value :evidence-roots} (set (keys d)))
                 (some #{(:dimension d)} real-world-dimensions)
                 (contains? #{:yes :no :unknown :undisclosed} (:value d))
                 (vector? (:evidence-roots d))
                 (every? reference/valid-sha256-ref? (:evidence-roots d))
                 (or (contains? #{:unknown :undisclosed} (:value d)) (seq (:evidence-roots d))))
    (throw (ex-info "Invalid participant assurance declaration" {})))
  (assoc d :evidence-roots (vec (sort (distinct (:evidence-roots d))))
           :epistemic-role :declared))

(defn build-assurance
  "Two exact attributed reproduction contexts, optional evidence-backed
   declarations. Reversing the pair or replaying declarations is idempotent.
   No real-world verifier is implemented: declarations never establish a fact."
  [researcher-axis contexts declarations]
  (when-not (and (vector? contexts) (= 2 (count contexts)) (vector? declarations))
    (throw (ex-info "Expected pair of reproduction contexts" {})))
  (let [contributions (vec (sort-by (juxt :contribution/attribution-root :contribution/execution-root
                                        :contribution/implementation-root)
                                   (distinct (map #(contribution researcher-axis %) contexts))))
        members (map #(axis/researcher-member researcher-axis (:contribution/coordinate %)) contributions)
        distinct-reference? (= 2 (count (set (map :researcher/id members))))
        distinct-implementation? (= 2 (count (set (map :contribution/implementation-artifact-root contributions))))
        unique-executions (count (set (map :contribution/execution-root contributions)))
        same-subject? (and (= 1 (count (set (map :contribution/input-root contributions))))
                           (= 1 (count (set (map :contribution/result-root contributions)))))
        matching-count (if same-subject?
                         (count (set (map :contribution/execution-root
                                          (filter #(= :reproduced (:contribution/status %)) contributions))))
                         0)
        body {:artifact/schema schema :researcher-axis/root (:axis/root researcher-axis)
              :assurance/contributions contributions
              :assurance/dimensions
              (merge (zipmap real-world-dimensions (repeat :unknown))
                     {:participant-reference-distinct (if distinct-reference? :yes :no)
                      :implementation-artifact-distinct (if distinct-implementation? :yes :no)})
              :assurance/declarations (vec (sort-by (juxt :dimension :value :evidence-roots)
                                                    (distinct (map declaration declarations))))
              :assurance/unique-execution-count unique-executions
              :assurance/matching-reproduction-count matching-count
              :assurance/comparison-status (if same-subject? :comparable :not-comparable)
              :assurance/meaning :reference-and-artifact-distinctness-only
              :research-closure/meaning :does-not-establish-claim-proof-authority-or-incentive-qualification}]
    (assoc body :research-participant-assurance/root (assurance-root body))))

(defn validate-assurance [artifact researcher-axis contexts declarations]
  (when-not (= artifact (build-assurance researcher-axis contexts declarations))
    (throw (ex-info "Detached participant assurance" {})))
  artifact)

(defn declared-dimension-view
  "Expose declared assertions/conflicts separately from established dimensions."
  [artifact dimension]
  (let [claims (filter #(= dimension (:dimension %)) (:assurance/declarations artifact))
        values (set (map :value claims))]
    {:dimension dimension :established (get-in artifact [:assurance/dimensions dimension] :unknown)
     :declared (cond (empty? values) :unknown (= 1 (count values)) (first values) :else :conflicting)
     :declarations (vec claims)}))

(defn evidence-node [artifact researcher-axis contexts declarations]
  (validate-assurance artifact researcher-axis contexts declarations)
  (forensic/build-forensic-evidence-node
   {:subject {:subject/kind :research/participant-assurance
              :subject/root (:research-participant-assurance/root artifact)}
    :role :derived :visibility :restricted :consequence? true
    :causal-parent-roots
    (vec (sort (distinct
                (concat [(:axis/root researcher-axis)]
                        (mapcat #(map % [:contribution/attribution-root :contribution/execution-root
                                         :contribution/input-root :contribution/result-root
                                         :contribution/reproduction-root :contribution/implementation-root
                                         :contribution/implementation-artifact-root])
                                (:assurance/contributions artifact))
                        (mapcat :evidence-roots (:assurance/declarations artifact))))))}))
