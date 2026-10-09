(ns resolver-sim.research.native-accounting-work
  "Direct execution of one account's frozen accounting partition. Native
   observations and recomputation bind arithmetic to unchanged V1 assurance."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.independent-accounting-impl :as independent-accounting]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def schema "research-native-accounting-work.v1")
(defn- root [domain body] (reference/sha256-ref (canonical/domain-hash domain body)))
(defn work-root [work]
  (root :research-native-accounting-work-v1 (dissoc work :research-native-accounting-work/root)))
(defn implementation-artifact-root []
  (root :research-native-accounting-implementation-v1
        {:source/roots
         (mapv (fn [path] {:source/path path :source/root (reference/sha256-ref-file path)})
               ["src/resolver_sim/research/accelerated_accounting.clj"
                "src/resolver_sim/research/native_accounting_work.clj"])}))
(defn independent-implementation-artifact-root []
  (root :research-native-accounting-implementation-v1
        {:source/path "src/resolver_sim/research/independent_accounting_impl.clj"
         :source/root (reference/sha256-ref-file "src/resolver_sim/research/independent_accounting_impl.clj")}))
(defn implementation [dependency-root]
  (assurance/build-implementation
   {:algorithm-id accounting/supported-operation :artifact-root (implementation-artifact-root)
    :language-runtime-family :clojure/jvm :resolved-dependency-set-root dependency-root
    :semantic-version "1.0.0"}))
(defn independent-implementation [dependency-root]
  (assurance/build-implementation
   {:algorithm-id independent-accounting/algorithm-id
    :artifact-root (independent-implementation-artifact-root)
    :language-runtime-family :clojure/jvm :resolved-dependency-set-root dependency-root
    :semantic-version "1.0.0"}))

(defn account-requests
  "Freeze separate existing V1 requests per subject, preventing same-category
   balances on different accounts from being reduced into one account result."
  [request reduction-contract]
  (when-not (and (accounting/valid-request? request)
                 (accounting/valid-reduction-contract? reduction-contract)
                 (= (:accounting-reduction-contract/root request)
                    (:accounting-reduction-contract/root reduction-contract)))
    (throw (ex-info "Invalid account source basis" {})))
  (into (sorted-map)
        (for [[account sources] (group-by :accounting/subject-root (:accounting/sources request))]
          [account (accounting/build-request
                    {:accounting-domain (:accounting/domain request)
                     :cutpoint-root (:accounting/cutpoint-root request)
                     :accounting-definition-root (:accounting-definition/root request)
                     :accounting-policy-root (:accounting-policy/root request)
                     :reduction-contract reduction-contract :sources sources})])))

(defn account-root [request unit]
  (let [by-root (into {} (map (juxt :source/root identity) (:accounting/sources request)))
        accounts (set (map #(get-in by-root [% :accounting/subject-root]) (:partition/source-roots unit)))]
    (when-not (and (= 1 (count accounts)) (reference/valid-sha256-ref? (first accounts)))
      (throw (ex-info "Native accounting partition must belong to exactly one account" {})))
    (first accounts)))

(defn computed-partial-values [request unit implementation]
  (case (:implementation/algorithm-id implementation)
    :accounting.operation/sum-by-category.v1 (accounting/partial-values request unit)
    :accounting.implementation/group-then-fold.v1 (independent-accounting/partial-values request unit)
    (throw (ex-info "Unsupported native accounting implementation" {}))))

(defn valid-work? [work]
  (boolean (try
    (let [{:work/keys [job assignment runner-participation execution-environment dependency-manifest
                      dependency-policy resolved-dependencies implementation assured-job execution-policy
                      assurance-policy]} work
          request (:accounting/request work) plan (:accounting/partition-plan work)
          unit (:accounting/work-unit work)
          deps (:resolved/dependencies resolved-dependencies)
          dependency-roots (into {} (map (juxt :dependency/id :dependency/artifact-root)
                                         (:dependencies dependency-manifest)))
          declared-dependency-ids (set (map :dependency/id (:dependencies dependency-manifest)))
          selected-code-dependency (if (= (:implementation/algorithm-id implementation)
                                          independent-accounting/algorithm-id)
                                     :accounting/independent-code
                                     :accounting/code)]
      (and (= schema (:artifact/schema work))
           (= #{:artifact/schema :work/job :work/assignment :work/runner-participation
                :work/execution-environment :work/dependency-manifest :work/dependency-policy
                :work/resolved-dependencies :work/implementation :work/assured-job
                :work/execution-policy :work/assurance-policy :accounting/request
                :accounting/partition-plan :accounting/work-unit :research-native-accounting-work/root}
              (set (keys work)))
           (remote/valid-job? job) (remote/valid-assignment? assignment)
           (:valid? (participation/validate-participation runner-participation))
           (= environment/native-schema (:artifact/schema execution-environment))
           (:valid? (environment/validate-environment execution-environment))
           (remote/valid-execution-policy? execution-policy)
           (assurance/valid-assurance-policy? assurance-policy)
           (assurance/valid-assured-job? assured-job)
           (assurance/valid-implementation? implementation)
           (accounting/valid-request? request) (accounting/valid-partition-plan? request plan)
           (= (:accounting-reduction-contract/root request)
              (:accounting-reduction-contract/root (accounting/build-reduction-contract)))
           (some #{unit} (:partition/work-units plan))
           (account-root request unit)
           (contains? #{:public :runner-readable-private} (:work-unit/privacy-requirement unit))
           (not-any? #(= :sealed-to-execution-environment (:dependency/visibility %))
                     (:dependencies dependency-manifest))
           (:valid? (dependency/validate-manifest dependency-manifest))
           (:valid? (dependency/validate-policy dependency-policy))
           (= resolved-dependencies (dependency/resolve-dependencies dependency-manifest dependency-policy deps))
           (= (:research-execution-job/root job) (:research-execution-job/root assignment)
              (:research-execution-job/root assured-job))
           (= (:workload/root job) (:accounting-work-unit/root unit))
           (= (:research-execution-environment/root job) (:research-execution-environment/root assignment)
              (:research-execution-environment/root execution-environment) (:work-unit/execution-environment-root unit))
           (= (:runner-participation/root runner-participation) (:runner-participation/root assignment))
           (= (:runner/reference runner-participation) (:runner/reference assignment))
           (= (:runner/site-id runner-participation) (:runner/site-id assignment))
           (:eligible? (remote/eligibility job execution-environment execution-policy runner-participation))
           (= (:research-run-slot/root assignment)
              (:research-run-slot/root (nth (remote/run-slots job execution-policy) (:run-slot/index assignment) nil)))
           (set/subset? (set (:work-unit/required-capabilities unit)) (set (:runner/capabilities runner-participation)))
           (= (:research-dependency-manifest/root dependency-manifest) (:research-dependency-manifest/root assured-job))
           (= (:research-dependency-policy/root dependency-policy) (:research-dependency-policy/root assured-job)
              (:research-dependency-policy/root assurance-policy))
           (= (:research-execution-policy/root execution-policy) (:research-execution-policy/root job)
              (:research-execution-policy/root assurance-policy))
           (= (:research-execution-assurance-policy/root assurance-policy) (:research-execution-assurance-policy/root assured-job))
           (= (:execution/required-runs assurance-policy) (:execution/required-runs execution-policy))
           (<= (:assurance/minimum-distinct-implementations assurance-policy)
               (count (set (:work-unit/allowed-implementation-roots unit))))
           (not (:proof-generation/required? assurance-policy))
           (= (:resolved-research-dependency-set/root resolved-dependencies)
              (:resolved-research-dependency-set/root implementation) (:resolved-research-dependency-set/root assured-job)
              (:work-unit/dependency-set-root unit))
           (contains? (set (:research-implementation-identity/roots assured-job)) (:research-implementation-identity/root implementation))
           (contains? (set (:work-unit/allowed-implementation-roots unit)) (:research-implementation-identity/root implementation))
           (or (and (= (:implementation/algorithm-id implementation) accounting/supported-operation)
                    (= (:implementation/artifact-root implementation) (implementation-artifact-root)))
               (and (= (:implementation/algorithm-id implementation) independent-accounting/algorithm-id)
                    (= (:implementation/artifact-root implementation) (independent-implementation-artifact-root))))
           (= :clojure/jvm (:implementation/language-runtime-family implementation))
           (contains? declared-dependency-ids :accounting/code)
           (contains? declared-dependency-ids :accounting/runtime)
           (contains? declared-dependency-ids :accounting/sources)
           (set/subset? (set/difference declared-dependency-ids #{:accounting/independent-code})
                        #{:accounting/code :accounting/runtime :accounting/sources})
           (= :clojure-model (some (fn [d] (when (= :accounting/code (:dependency/id d))
                                                (:dependency/class d)))
                                   (:dependencies dependency-manifest)))
           (= :runtime-toolchain (some (fn [d] (when (= :accounting/runtime (:dependency/id d))
                                                    (:dependency/class d)))
                                       (:dependencies dependency-manifest)))
           (= :dataset-fixture (some (fn [d] (when (= :accounting/sources (:dependency/id d))
                                                 (:dependency/class d)))
                                     (:dependencies dependency-manifest)))
           (or (not (contains? declared-dependency-ids :accounting/independent-code))
               (= :clojure-model (some #(when (= :accounting/independent-code (:dependency/id %))
                                          (:dependency/class %)) (:dependencies dependency-manifest))))
           (= {:accounting/runtime (:research-execution-environment/root execution-environment)
               :accounting/sources (:accounting-request/root request)}
              (select-keys dependency-roots
                           [:accounting/runtime :accounting/sources]))
           (= (:implementation/artifact-root implementation)
              (get dependency-roots selected-code-dependency))
           (= (implementation-artifact-root) (get dependency-roots :accounting/code))
           (if (contains? declared-dependency-ids :accounting/independent-code)
             (= (get dependency-roots :accounting/independent-code)
                (independent-implementation-artifact-root))
             (nil? (get dependency-roots :accounting/independent-code)))
           (= (:research-native-accounting-work/root work) (work-root work))))
    (catch Exception _ false))))

(defn build-work [body]
  (let [body (assoc body :artifact/schema schema)
        work (assoc body :research-native-accounting-work/root (work-root body))]
    (when-not (valid-work? work) (throw (ex-info "Invalid native accounting work" {})))
    work))

(defn- run-root [run] (root :research-native-accounting-run-v1 (dissoc run :research-native-accounting-run/root)))
(defn- validation-root [validation]
  (root :research-native-accounting-validation-v1 (dissoc validation :research-native-accounting-validation/root)))

(defn- derive-evidence [work values observed-runtime]
  (let [request (:accounting/request work) unit (:accounting/work-unit work)
        assignment (:work/assignment work) impl (:work/implementation work)
        semantic (accounting/partial-semantic-root
                  {:accounting-request/root (:accounting-request/root request)
                   :accounting-work-unit/root (:accounting-work-unit/root unit)
                   :partition/source-roots (:partition/source-roots unit) :accounting/values values})
        run-body {:artifact/schema "research-native-accounting-run.v1"
                  :research-native-accounting-work/root (:research-native-accounting-work/root work)
                  :research-execution-job/root (:research-execution-job/root assignment)
                  :research-run-assignment/root (:research-run-assignment/root assignment)
                  :accounting/account-root (account-root request unit)
                  :accounting/values values :semantic-result/root semantic
                  :execution/observed-runtime observed-runtime :execution/assurance :observed-native-accounting}
        run (assoc run-body :research-native-accounting-run/root (run-root run-body))
        validation-body {:artifact/schema "research-native-accounting-validation.v1"
                         :research-native-accounting-run/root (:research-native-accounting-run/root run)
                         :research-native-accounting-work/root (:research-native-accounting-work/root work)
                         :semantic-result/root semantic :validation/status :passed
                         :validation/checks (conj assurance/fixed-validation-checks :account-bound :exact-partition-recomputed)
                         :validation/assurance :same-implementation-recomputation}
        validation (assoc validation-body :research-native-accounting-validation/root (validation-root validation-body))
        assurance-body {:artifact/schema assurance/run-assurance-schema
                        :research-execution-job-assurance/root (get-in work [:work/assured-job :research-execution-job-assurance/root])
                        :research-run-evidence/root (:research-native-accounting-run/root run)
                        :resolved-research-dependency-set/root (:work-unit/dependency-set-root unit)
                        :research-implementation-identity/root (:research-implementation-identity/root impl)
                        :research-fixed-validation-receipt/root (:research-native-accounting-validation/root validation)
                        :runner/reference (:runner/reference assignment) :runner/site-id (:runner/site-id assignment)
                        :semantic-result/root semantic :semantic-result/status :execution-result-only
                        :application-authority/status :not-established}
        assured (assoc assurance-body :research-run-assurance-evidence/root (assurance/run-assurance-root assurance-body))
        ;; The envelope is built from the supplied value map. Callers that
        ;; establish execution validity still compare it with the arithmetic
        ;; result; proof-backed admission can instead bind this envelope and
        ;; let the SP1 guest establish the sum.
        partial-body {:artifact/schema accounting/partial-schema
                      :accounting-request/root (:accounting-request/root request)
                      :accounting-work-unit/root (:accounting-work-unit/root unit)
                      :partition/index (:partition/index unit)
                      :partition/source-roots (:partition/source-roots unit)
                      :accounting/values (into (sorted-map) values)
                      :research-run-assurance-evidence/root
                      (:research-run-assurance-evidence/root assured)
                      :research-implementation-identity/root
                      (:research-implementation-identity/root impl)
                      :runner/reference (:runner/reference assignment)
                      :resolved-research-dependency-set/root
                      (:work-unit/dependency-set-root unit)
                      :research-execution-environment/root
                      (:work-unit/execution-environment-root unit)}
        partial (assoc partial-body :accounting-partial-result/root
                       (accounting/partial-root partial-body))]
    {:run-evidence run :fixed-validation validation :run-assurance-evidence assured :accounting-partial partial}))

(defn- validate-execution-after-work-validation [work result]
  (when-not (and (= result (derive-evidence work (computed-partial-values (:accounting/request work)
                                                                           (:accounting/work-unit work)
                                                                           (:work/implementation work))
                                             (select-keys (:work/execution-environment work)
                                                          [:runtime/clojure-version :runtime/jvm-version
                                                           :environment/arithmetic])))
                 (assurance/valid-run-assurance-evidence? (:run-assurance-evidence result)))
    (throw (ex-info "Native accounting execution lineage or arithmetic mismatch" {})))
  result)

(defn validate-execution [work result]
  (when-not (valid-work? work)
    (throw (ex-info "Native accounting execution lineage or arithmetic mismatch" {})))
  (validate-execution-after-work-validation work result))

(defn validate-execution-lineage
  "Validate the canonical native execution envelope without recomputing its
   arithmetic. This is not sufficient by itself to admit a partial: callers
   must pair it with an arithmetic proof for the exact request/plan/unit and
   partial roots. Ordinary V1 admission continues to use validate-execution."
  [work result]
  (let [partial (:accounting-partial result)
        values (:accounting/values partial)
        expected (when (and (valid-work? work)
                            (= accounting/partial-schema (:artifact/schema partial))
                            (map? values)
                            (every? integer? (vals values)))
                   (derive-evidence
                    work values
                    (select-keys (:work/execution-environment work)
                                 [:runtime/clojure-version :runtime/jvm-version
                                  :environment/arithmetic])))]
    (when-not (and expected
                   (= expected result)
                   (assurance/valid-run-assurance-evidence?
                    (:run-assurance-evidence result)))
      (throw (ex-info "Native accounting execution lineage mismatch"
                      {:cause :accounting-execution-lineage-mismatch})))
    result))

(defn execute-work! [work]
  (when-not (valid-work? work) (throw (ex-info "Invalid native accounting work" {})))
  (let [runtime (environment/build-native-environment)]
    (when-not (= runtime (:work/execution-environment work))
      (throw (ex-info "Native accounting runtime mismatch" {:cause :execution-environment-mismatch})))
    ;; No expected values are transported. Actual accounting computation runs here.
    (let [values (computed-partial-values (:accounting/request work) (:accounting/work-unit work)
                                          (:work/implementation work))
          result (derive-evidence work values (select-keys runtime [:runtime/clojure-version :runtime/jvm-version :environment/arithmetic]))]
      ;; `valid-work?` was already applied before computation. Retain full
      ;; arithmetic/result validation without repeating the same deep binding
      ;; walk for this immutable work value.
      (validate-execution-after-work-validation work result))))

(defn execution-root [result] (get-in result [:run-evidence :research-native-accounting-run/root]))
(defn- evidence-nodes-after-execution-validation [work result]
  (let [run-node (forensic/build-forensic-evidence-node
                  {:subject {:subject/kind :accounting/native-execution :subject/root (execution-root result)}
                   :role :observed :visibility :restricted
                   :actor {:actor/role :runner :actor/root (get-in work [:work/assignment :runner/reference])}
                   :causal-parent-roots [(:research-native-accounting-work/root work)
                                         (get-in work [:work/assignment :research-run-assignment/root])
                                         (get-in work [:work/resolved-dependencies :resolved-research-dependency-set/root])
                                         (get-in work [:work/implementation :research-implementation-identity/root])
                                         (get-in work [:work/execution-environment :research-execution-environment/root])
                                         (get-in work [:accounting/request :accounting-request/root])
                                         (get-in work [:accounting/work-unit :accounting-work-unit/root])]})
        validation-node (forensic/build-forensic-evidence-node
                         {:subject {:subject/kind :accounting/native-validation
                                    :subject/root (get-in result [:fixed-validation :research-native-accounting-validation/root])}
                          :role :validated :visibility :restricted :parent-nodes [(:node-hash run-node)]
                          :causal-parent-roots [(execution-root result)] :consequence? true})]
    (let [assured-node (forensic/build-forensic-evidence-node
                       {:subject {:subject/kind :accounting/run-assurance
                                  :subject/root (get-in result [:run-assurance-evidence :research-run-assurance-evidence/root])}
                        :role :derived :visibility :restricted :parent-nodes [(:node-hash validation-node)]
                        :causal-parent-roots [(execution-root result)
                                              (get-in result [:fixed-validation :research-native-accounting-validation/root])]
                        :consequence? true})
          partial-node (forensic/build-forensic-evidence-node
                        {:subject {:subject/kind :accounting/partial
                                   :subject/root (get-in result [:accounting-partial :accounting-partial-result/root])}
                         :role :derived :visibility :restricted :parent-nodes [(:node-hash assured-node)]
                         :causal-parent-roots [(get-in work [:accounting/request :accounting-request/root])
                                               (get-in work [:accounting/work-unit :accounting-work-unit/root])
                                               (get-in result [:run-assurance-evidence :research-run-assurance-evidence/root])]
                         :consequence? true})
          semantic-node (forensic/build-forensic-evidence-node
                         {:subject {:subject/kind :accounting/canonical-partial
                                    :subject/root (get-in result [:run-evidence :semantic-result/root])}
                          :role :derived :visibility :restricted :parent-nodes [(:node-hash partial-node)]
                          :causal-parent-roots [(get-in result [:accounting-partial :accounting-partial-result/root])]
                          :consequence? true})]
      [run-node validation-node assured-node partial-node semantic-node])))

(defn evidence-nodes [work result]
  (validate-execution work result)
  (evidence-nodes-after-execution-validation work result))

(defn execute-work-with-evidence!
  "Execute and validate native accounting work once, then derive its forensic
   nodes from that just-validated result. The returned execution and node
   artifacts are the same as `execute-work!` plus `evidence-nodes`; this only
   avoids revalidating a value that never left this process."
  [work]
  (let [execution (execute-work! work)]
    {:execution execution
     :forensic-evidence-nodes
     (evidence-nodes-after-execution-validation work execution)}))
