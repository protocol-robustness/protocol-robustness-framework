(ns resolver-sim.research.claim-evidence-work
  "Closed, finite claim-evidence coordinates beneath existing analysis-output
   verification. This is source projection and evidence accounting, not a new
   route to research-claim truth or acceptance."
  (:require [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const plan-schema "research-claim-evidence-plan.v1")
(def ^:const coordinate-schema "research-claim-evidence-coordinate.v1")
(def ^:const assignment-schema "research-claim-evidence-assignment.v1")
(def ^:const observation-schema "research-claim-evidence-observation.v1")
(def ^:const result-schema "research-claim-evidence-coordinate-result.v1")
(def ^:const manifest-schema "research-claim-evidence-manifest.v1")
(def ^:const reconciliation-schema "research-claim-evidence-reconciliation.v1")
(def ^:private roles [:result-value :result-classification :outcome-commitment])

(defn- root [domain body] (hash-ref/sha256-ref (hc/domain-hash domain body)))
(defn- rooted [domain key body] (assoc body key (root domain body)))

(defn build-plan
  "Freeze the exact evidence obligations for one existing analytical result.
   The later analysis-output evidence root is intentionally not an input."
  [context proposition-id descriptor result]
  (let [closure-report (closure/verify-closure context)]
    (when-not (and (qualified-keyword? proposition-id)
                   (closure/closure-valid? closure-report)
                   (:valid? (analysis/validate-result-descriptor descriptor))
                   (:valid? (analysis/validate-result result))
                   (= (:result-descriptor/root descriptor)
                      (:result/descriptor-root result)))
      (throw (ex-info "Invalid closed claim-evidence source" {})))
    (let [body {:artifact/schema plan-schema
                :research-analysis/command-root
                (get-in context [:research-command :command/hash])
                :research-analysis/model-root
                (get-in context [:incentive-model :incentive-model/root])
                :research-analysis/domain-root
                (get-in context [:deviation-domain :deviation-domain/root])
                :research-analysis/assignment-root
                (get-in context [:research-assignment :research-assignment/hash])
                :research-analysis/outcome-root
                (get-in context [:outcome-manifest :benchmark-outcome/hash])
                :research-analysis/closure-root (:research-analysis/root closure-report)
                :claim/proposition-id proposition-id
                :result/descriptor-root (:result-descriptor/root descriptor)
                :result/case-root (:result/case-root result)
                :result/measure-id (:measure/id descriptor)
                :result/source-root
                (root :research-claim-evidence-source-result-v1 result)
                :evidence/roles roles
                :evidence/coverage :closed-exactly-once}]
      (rooted :research-claim-evidence-plan-v1
              :research-claim-evidence-plan/root body))))

(defn valid-plan? [context proposition-id descriptor result plan]
  (= plan (try (build-plan context proposition-id descriptor result)
               (catch Exception _ nil))))

(defn coordinates [plan]
  (mapv (fn [role]
          (rooted
           :research-claim-evidence-coordinate-v1
           :research-claim-evidence-coordinate/root
           {:artifact/schema coordinate-schema
            :research-claim-evidence-plan/root
            (:research-claim-evidence-plan/root plan)
            :claim/proposition-id (:claim/proposition-id plan)
            :evidence/role role
            :result/case-root (:result/case-root plan)
            :result/measure-id (:result/measure-id plan)
            :result/source-root (if (= role :outcome-commitment)
                                  (:research-analysis/outcome-root plan)
                                  (:result/source-root plan))}))
        (:evidence/roles plan)))

(defn- eligible? [runner]
  (and (:valid? (participation/validate-participation runner))
       (:participation/opted-in? runner)
       (:participation/available? runner)
       (contains? (set (:runner/capabilities runner))
                  :capability/claim-evidence)
       (contains? (set (:participation/job-classes runner))
                  :job/research-claim-evidence)
       (contains? (set (:runner/environment-kinds runner))
                  :execution-environment/local)))

(defn- assignment-root [assignment]
  (root :research-claim-evidence-assignment-v1
        (dissoc assignment :research-claim-evidence-assignment/root)))

(defn steal-next
  [plan runner assignments]
  (let [units (coordinates plan)
        allowed (set (map :research-claim-evidence-coordinate/root units))
        claimed (map :research-claim-evidence-coordinate/root assignments)
        claimed-set (set claimed)]
    (when-not (and (= plan-schema (:artifact/schema plan))
                   (= (:research-claim-evidence-plan/root plan)
                      (root :research-claim-evidence-plan-v1
                            (dissoc plan :research-claim-evidence-plan/root)))
                   (= (count claimed) (count claimed-set))
                   (every? allowed claimed)
                   (every? #(and (= assignment-schema (:artifact/schema %))
                                 (= (:research-claim-evidence-plan/root plan)
                                    (:research-claim-evidence-plan/root %))
                                 (= (:research-claim-evidence-assignment/root %)
                                    (assignment-root %)))
                           assignments))
      (throw (ex-info "Invalid claim-evidence steal state" {})))
    (when (eligible? runner)
      (when-let [coordinate (first
                             (remove #(contains? claimed-set
                                                 (:research-claim-evidence-coordinate/root %))
                                     units))]
        (rooted :research-claim-evidence-assignment-v1
                :research-claim-evidence-assignment/root
                {:artifact/schema assignment-schema
                 :research-claim-evidence-plan/root
                 (:research-claim-evidence-plan/root plan)
                 :research-claim-evidence-coordinate/root
                 (:research-claim-evidence-coordinate/root coordinate)
                 :runner-participation/root (:runner-participation/root runner)
                 :runner/reference (:runner/reference runner)
                 :runner/site-id (:runner/site-id runner)})))))

(defn- role-projection [role context result]
  (case role
    :result-value {:evidence/status :observed
                   :evidence/value {:result/value (:result/value result)}}
    :result-classification
    {:evidence/status (case (:result/classification result)
                        :supported :supporting
                        :counterexample-found :contradicting
                        :inconclusive :inconclusive)
     :evidence/value {:result/classification (:result/classification result)
                      :result/witness-root (:result/witness-root result)}}
    :outcome-commitment
    {:evidence/status :observed
     :evidence/value {:benchmark-outcome/hash
                      (get-in context [:outcome-manifest :benchmark-outcome/hash])
                      :outcomes/incentive-compatibility-root
                      (get-in context [:outcome-manifest
                                       :outcomes/incentive-compatibility-root])}}))

(defn observe
  "Recompute one coordinate from frozen authoritative inputs. This does not
   independently reproduce an engine calculation or establish a theorem."
  [context proposition-id descriptor result plan assignment runner]
  (let [coordinate (some #(when (= (:research-claim-evidence-coordinate/root %)
                                  (:research-claim-evidence-coordinate/root assignment)) %)
                         (coordinates plan))]
    (when-not (and (valid-plan? context proposition-id descriptor result plan)
                   coordinate (eligible? runner)
                   (= assignment-schema (:artifact/schema assignment))
                   (= (:research-claim-evidence-plan/root plan)
                      (:research-claim-evidence-plan/root assignment))
                   (= (:research-claim-evidence-assignment/root assignment)
                      (assignment-root assignment))
                   (= (:runner-participation/root runner)
                      (:runner-participation/root assignment))
                   (= (:runner/reference runner) (:runner/reference assignment))
                   (= (:runner/site-id runner) (:runner/site-id assignment)))
      (throw (ex-info "Invalid claim-evidence observation lineage" {})))
    (let [projected (role-projection (:evidence/role coordinate) context result)
          semantic (rooted :research-claim-evidence-coordinate-result-v1
                           :research-claim-evidence-coordinate-result/root
                           (merge {:artifact/schema result-schema
                                   :research-claim-evidence-coordinate/root
                                   (:research-claim-evidence-coordinate/root coordinate)}
                                  projected))
          body {:artifact/schema observation-schema
                :research-claim-evidence-plan/root
                (:research-claim-evidence-plan/root plan)
                :research-claim-evidence-coordinate/root
                (:research-claim-evidence-coordinate/root coordinate)
                :research-claim-evidence-assignment/root
                (:research-claim-evidence-assignment/root assignment)
                :runner-participation/root (:runner-participation/root runner)
                :evidence/semantic-result semantic}]
      (rooted :research-claim-evidence-observation-v1
              :research-claim-evidence-observation/root body))))

(defn ingest [ingestion observation]
  (let [coordinate-root (:research-claim-evidence-coordinate/root observation)
        prior (get ingestion coordinate-root)]
    (cond
      (nil? prior) (assoc ingestion coordinate-root observation)
      (= prior observation) ingestion
      :else (throw (ex-info "Conflicting claim-evidence observations"
                            {:conflict/type :claim-evidence-coordinate-conflict
                             :coordinate/root coordinate-root
                             :existing/root (:research-claim-evidence-observation/root prior)
                             :incoming/root
                             (:research-claim-evidence-observation/root observation)})))))

(defn reconcile
  "Exact closed-plan coverage. Semantic evidence excludes runner assignments;
   the forensic reconciliation retains every admitted observation root."
  [context proposition-id descriptor result plan assignments participants ingestion]
  (when-not (valid-plan? context proposition-id descriptor result plan)
    (throw (ex-info "Invalid claim-evidence reconciliation plan" {})))
  (let [units (coordinates plan)
        required (set (map :research-claim-evidence-coordinate/root units))
        by-coordinate (into {} (map (juxt :research-claim-evidence-coordinate/root
                                        identity) assignments))
        by-participant (into {} (map (juxt :runner-participation/root identity)
                                     participants))]
    (when-not (and (= required (set (keys ingestion)) (set (keys by-coordinate)))
                   (= (count units) (count assignments))
                   (= (count participants) (count by-participant))
                   (every? (fn [[coordinate-root observation]]
                             (let [assignment (by-coordinate coordinate-root)
                                   runner (by-participant
                                           (:runner-participation/root assignment))]
                               (and runner
                                    (= coordinate-root
                                       (:research-claim-evidence-coordinate/root observation))
                                    (= observation
                                       (try (observe context proposition-id descriptor result
                                                     plan assignment runner)
                                            (catch Exception _ nil))))))
                           ingestion))
      (throw (ex-info "Missing or substituted closed claim evidence" {})))
    (let [ordered (mapv #(ingestion (:research-claim-evidence-coordinate/root %)) units)
          semantic-results (mapv :evidence/semantic-result ordered)
          semantic (rooted :research-claim-evidence-manifest-v1
                           :research-claim-evidence-manifest/root
                           {:artifact/schema manifest-schema
                            :research-claim-evidence-plan/root
                            (:research-claim-evidence-plan/root plan)
                            :claim/proposition-id proposition-id
                            :evidence/coordinate-results semantic-results
                            :evidence/coverage :complete-exactly-once
                            :evidence/meaning :source-projection-not-claim-proof})
          forensic (rooted :research-claim-evidence-reconciliation-v1
                          :research-claim-evidence-reconciliation/root
                          {:artifact/schema reconciliation-schema
                           :research-claim-evidence-plan/root
                           (:research-claim-evidence-plan/root plan)
                           :research-claim-evidence-manifest/root
                           (:research-claim-evidence-manifest/root semantic)
                           :research-claim-evidence-observation/roots
                           (vec (sort (map :research-claim-evidence-observation/root
                                           ordered)))})]
      {:semantic-evidence semantic :forensic-reconciliation forensic})))

(defn build-output-from-evidence
  "Admit a closed evidence set into the existing analysis-output builder.
   Neither the evidence root nor an acceptance/verification flag is supplied
   by the caller; both remain downstream of exact reconciliation."
  [context proposition-id descriptor result plan assignments participants
   ingestion engine-id engine-version]
  (let [reconciled (reconcile context proposition-id descriptor result plan
                              assignments participants ingestion)
        analysis-output
        (analysis/build-output
         (assoc context
                :analysis-output/closure-root (:research-analysis/closure-root plan)
                :analysis-output/result-descriptor descriptor
                :analysis-engine/id engine-id
                :analysis-engine/version engine-version
                :analysis-output/proposition-id proposition-id
                :analysis-output/result result
                :analysis-output/evidence-root
                (get-in reconciled [:semantic-evidence
                                    :research-claim-evidence-manifest/root])))]
    {:reconciliation reconciled :analysis-output analysis-output}))
