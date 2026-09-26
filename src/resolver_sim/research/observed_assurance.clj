(ns resolver-sim.research.observed-assurance
  "Composition from observed local execution into the unchanged V1 assurance
   consequence.  Observed run evidence is a successor because
   research-run-evidence.v1 is explicitly declaration/simulation-only."
  (:require [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.anvil-executor :as anvil]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const observed-run-schema "research-observed-run-evidence.v1")
(defn- root [domain x] (hash-ref/sha256-ref (hc/domain-hash domain x)))
(defn- root? [x] (hash-ref/valid-sha256-ref? x))
(defn observed-run-root [x]
  (root :research-observed-run-evidence-v1 (dissoc x :research-observed-run-evidence/root)))

(defn valid-observed-run-evidence?
  "Closed validation of the observed-run successor.  It deliberately validates
   roots and assurance classification without re-running Anvil."
  [evidence]
  (and (= observed-run-schema (:artifact/schema evidence))
       (= :observed-local-execution (:execution/assurance evidence))
       (every? root? (map evidence
                           [:research-execution-job/root :research-run-assignment/root
                            :runner-participation/root :runner/reference
                            :research-execution-environment/root
                            :research-execution-environment-observation/root
                            :resolved-research-dependency-set/root
                            :research-implementation-identity/root
                            :environment-before/root :executed-action/root
                            :environment-after/root :semantic-result/root
                            :research-observed-fixed-validation-receipt/root
                            :forensic-evidence-root :research-observed-run-evidence/root]))
       (let [w (:proof/source-artifacts evidence)
             a (:assured-job w) asn (:assignment w) rp (:runner-participation w)
             deps (:resolved-dependencies w) impl (:implementation w)
             env (:execution-environment w) obs (:environment-observation w)
             rec (:anvil-receipt w) fixed (:fixed-validation w)]
         (and (map? w)
              (assurance/valid-assured-job? (:assured-job w))
              (remote/valid-assignment? (:assignment w))
              (:valid? (participation/validate-participation (:runner-participation w)))
              (dependency/valid-resolved-set? (:resolved-dependencies w))
              (assurance/valid-implementation? (:implementation w))
              (:valid? (environment/validate-environment (:execution-environment w)))
              (anvil/valid-observation? (:environment-observation w))
              (anvil/valid-receipt? (:anvil-receipt w))
              (anvil/valid-fixed-validation? fixed)
              (= (:research-execution-job/root evidence) (:research-execution-job/root a)
                 (:research-execution-job/root asn) (:research-execution-job/root rec))
              (= (:research-run-assignment/root evidence) (:research-run-assignment/root asn)
                 (:research-run-assignment/root rec))
              (= (:runner-participation/root evidence) (:runner-participation/root rp)
                 (:runner-participation/root asn) (:runner-participation/root rec))
              (= (:runner/reference evidence) (:runner/reference rp) (:runner/reference asn)
                 (:runner/reference rec))
              (= (:runner/site-id evidence) (:runner/site-id asn) (:runner/site-id rec))
              (= (:resolved-research-dependency-set/root evidence)
                 (:resolved-research-dependency-set/root deps)
                 (:resolved-research-dependency-set/root impl)
                 (:resolved-research-dependency-set/root rec))
              (= (:research-implementation-identity/root evidence)
                 (:research-implementation-identity/root impl)
                 (:research-implementation-identity/root rec))
              (= (:research-execution-environment/root evidence)
                 (:research-execution-environment/root env)
                 (:research-execution-environment/root asn)
                 (:research-execution-environment/root rec)
                 (:research-execution-environment/root obs))
              (= (:research-execution-environment-observation/root evidence)
                 (:research-execution-environment-observation/root obs)
                 (:research-execution-environment-observation/root rec))
              (= (:environment-before/root evidence) (:environment-before/root rec))
              (= (:executed-action/root evidence) (:executed-action/root rec))
              (= (:environment-after/root evidence) (:environment-after/root rec))
              (= (:semantic-result/root evidence) (:semantic-result/root rec))
              (= (:research-observed-fixed-validation-receipt/root evidence)
                 (:research-observed-fixed-validation-receipt/root fixed))
              (= (:research-anvil-execution-receipt/root rec)
                 (:research-anvil-execution-receipt/root fixed))))
       (keyword? (:runner/site-id evidence))
       (= (:research-observed-run-evidence/root evidence)
          (try (observed-run-root evidence) (catch Exception _ nil)))))

(defn- node-ref [node] (str "sha256:" (:node-hash node)))

(defn- forensic-nodes [receipt validation runner-root]
  (let [actor {:actor/root runner-root :actor/role :runner}
        env (forensic/build-forensic-evidence-node
             {:subject {:subject/root (:research-execution-environment-observation/root receipt)
                        :subject/kind :research-execution/environment-observation}
              :role :observed :actor actor :visibility :commitment-only
              :causal-parent-roots [(:research-execution-environment/root receipt)]})
        run (forensic/build-forensic-evidence-node
             {:subject {:subject/root (:research-anvil-execution-receipt/root receipt)
                        :subject/kind :research-execution/observed-receipt}
              :role :derived :actor actor :visibility :commitment-only
              :parent-nodes [(:node-hash env)]
              :causal-parent-roots [(:research-execution-job/root receipt)
                                     (:resolved-research-dependency-set/root receipt)
                                     (:environment-before/root receipt)
                                     (:executed-action/root receipt)
                                     (:environment-after/root receipt)
                                     (:semantic-result/root receipt)]})
        fixed (forensic/build-forensic-evidence-node
               {:subject {:subject/root (:research-observed-fixed-validation-receipt/root validation)
                          :subject/kind :research-execution/fixed-validation}
                :role :validated :actor actor :visibility :commitment-only
                :parent-nodes [(:node-hash run)]
                :causal-parent-roots [(:research-anvil-execution-receipt/root receipt)]
                :consequence? true})]
    [env run fixed]))

(defn build-observed-run-assurance
  "Validate observed receipt lineage, construct a successor observed-run record,
   and return the existing research-run-assurance-evidence.v1 consequence.

   The V1 assurance schema remains unchanged: its constituent fields are roots,
   while this adapter supplies the stronger observed-evidence linkage alongside
   it."
  [{:keys [assured-job assignment runner-participation resolved-dependencies
           implementation receipt validation]}]
  (when-not (and (assurance/valid-assured-job? assured-job)
                 (remote/valid-assignment? assignment)
                 (:valid? (participation/validate-participation runner-participation))
                 (dependency/valid-resolved-set? resolved-dependencies)
                 (assurance/valid-implementation? implementation)
                 (anvil/valid-receipt? receipt)
                 (anvil/valid-observation? (:research-execution-environment-observation/body receipt))
                 (anvil/valid-fixed-validation? validation)
                 (= (:research-anvil-execution-receipt/root receipt)
                    (:research-anvil-execution-receipt/root validation))
                 (= (:research-execution-job/root assured-job) (:research-execution-job/root receipt))
                 (= (:research-run-assignment/root assignment) (:research-run-assignment/root receipt))
                 (= (:runner-participation/root runner-participation) (:runner-participation/root receipt))
                 (= (:resolved-research-dependency-set/root resolved-dependencies)
                    (:resolved-research-dependency-set/root receipt)
                    (:resolved-research-dependency-set/root implementation)
                    (:resolved-research-dependency-set/root assured-job))
                 (= (:research-implementation-identity/root implementation)
                    (:research-implementation-identity/root receipt))
                 (= (:research-execution-environment/root assignment)
                    (:research-execution-environment/root receipt))
                 (every? root? [(:research-execution-environment-observation/root receipt)
                                (:environment-before/root receipt) (:executed-action/root receipt)
                                (:environment-after/root receipt) (:semantic-result/root receipt)]))
    (throw (ex-info "Observed execution assurance provenance mismatch"
                    {:cause :observed-assurance-provenance-mismatch})))
  (let [nodes (forensic-nodes receipt validation (:runner/reference receipt))
        forensic-root (node-ref (last nodes))
        observed-body {:artifact/schema observed-run-schema
                       :research-execution-job/root (:research-execution-job/root receipt)
                       :research-run-assignment/root (:research-run-assignment/root receipt)
                       :runner-participation/root (:runner-participation/root receipt)
                       :runner/reference (:runner/reference receipt)
                       :runner/site-id (:runner/site-id assignment)
                       :research-execution-environment/root (:research-execution-environment/root receipt)
                       :research-execution-environment-observation/root (:research-execution-environment-observation/root receipt)
                       :resolved-research-dependency-set/root (:resolved-research-dependency-set/root receipt)
                       :research-implementation-identity/root (:research-implementation-identity/root receipt)
                       :environment-before/root (:environment-before/root receipt)
                       :executed-action/root (:executed-action/root receipt)
                       :environment-after/root (:environment-after/root receipt)
                       :semantic-result/root (:semantic-result/root receipt)
                       :research-observed-fixed-validation-receipt/root
                       (:research-observed-fixed-validation-receipt/root validation)
                       :proof/source-artifacts
                       {:assured-job assured-job
                        :assignment assignment
                        :runner-participation runner-participation
                        :resolved-dependencies resolved-dependencies
                        :implementation implementation
                        :execution-environment (:research-execution-environment/body receipt)
                        :environment-observation (:research-execution-environment-observation/body receipt)
                        :anvil-receipt receipt
                        :fixed-validation validation}
                       :forensic-evidence-node-roots (mapv node-ref nodes)
                       :forensic-evidence-root forensic-root
                       :execution/assurance :observed-local-execution}
        observed (assoc observed-body :research-observed-run-evidence/root (observed-run-root observed-body))
        assurance-body {:artifact/schema assurance/run-assurance-schema
                        :research-execution-job-assurance/root (:research-execution-job-assurance/root assured-job)
                        :research-run-evidence/root (:research-observed-run-evidence/root observed)
                        :resolved-research-dependency-set/root (:resolved-research-dependency-set/root receipt)
                        :research-implementation-identity/root (:research-implementation-identity/root receipt)
                        ;; V1 retains this generic root field; the observed record
                        ;; supplies its explicit successor schema/type.
                        :research-fixed-validation-receipt/root
                        (:research-observed-fixed-validation-receipt/root validation)
                        :runner/reference (:runner/reference receipt)
                        :runner/site-id (:runner/site-id assignment)
                        :semantic-result/root (:semantic-result/root receipt)
                        :semantic-result/status :execution-result-only
                        :application-authority/status :not-established}
        assured (assoc assurance-body :research-run-assurance-evidence/root
                       (assurance/run-assurance-root assurance-body))]
    (when-not (assurance/valid-run-assurance-evidence? assured)
      (throw (ex-info "Invalid V1 run assurance consequence" {})))
    {:observed-run-evidence observed
     :run-assurance-evidence assured
     :forensic-nodes nodes
     :forensic-evidence-root forensic-root}))
