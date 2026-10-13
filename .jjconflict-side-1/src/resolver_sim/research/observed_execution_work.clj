(ns resolver-sim.research.observed-execution-work
  "Rooted executable work bundle for the existing observed-Anvil adapter.

   The bundle is not a second proof statement: it carries the exact canonical
   artifacts and action which the adapter already needs, so a dispatched runner
   cannot replace them with ad-hoc caller supplied roots."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const schema "research-observed-execution-work.v1")
(defn- root [x] (hash-ref/sha256-ref (hc/domain-hash :research-observed-execution-work-v1 x)))
(defn work-root [x] (root (dissoc x :research-observed-execution-work/root)))

(defn- anvil-operation [operation]
  ;; Keep the Anvil adapter behind the observed-EVM work boundary. Requiring
  ;; this namespace is also useful to generic transports that only need to
  ;; recognize the work schema; those paths must not load Anvil configuration.
  (requiring-resolve (symbol "resolver-sim.research.anvil-executor" operation)))

(defn valid-work? [work]
  (and (= schema (:artifact/schema work))
       (remote/valid-job? (:work/job work))
       (remote/valid-assignment? (:work/assignment work))
       (:valid? (participation/validate-participation (:work/runner-participation work)))
       (:valid? (environment/validate-environment (:work/execution-environment work)))
       (:valid? (dependency/validate-manifest (:work/dependency-manifest work)))
       (dependency/valid-resolved-set? (:work/resolved-dependencies work))
       (assurance/valid-implementation? (:work/implementation work))
       (assurance/valid-assured-job? (:work/assured-job work))
       (map? (:work/action work))
       (= (:work/expected-action-root work) ((anvil-operation "action-root") (:work/action work)))
       (= (:research-execution-job/root (:work/job work))
          (:research-execution-job/root (:work/assignment work))
          (:research-execution-job/root (:work/assured-job work)))
       (= (:runner-participation/root (:work/runner-participation work))
          (:runner-participation/root (:work/assignment work)))
       (= (:research-execution-environment/root (:work/execution-environment work))
          (:research-execution-environment/root (:work/job work))
          (:research-execution-environment/root (:work/assignment work)))
       (= (:resolved-research-dependency-set/root (:work/resolved-dependencies work))
          (:resolved-research-dependency-set/root (:work/implementation work))
          (:resolved-research-dependency-set/root (:work/assured-job work)))
       (contains? (set (:research-implementation-identity/roots (:work/assured-job work)))
                  (:research-implementation-identity/root (:work/implementation work)))
       (= (:research-observed-execution-work/root work)
          (try (work-root work) (catch Exception _ nil)))))

(defn build-work
  [{:keys [job assignment runner-participation execution-environment dependency-manifest
           resolved-dependencies implementation assured-job action expected-action-root
           expected-prestate-root expected-poststate-root]}]
  (let [body {:artifact/schema schema
              :work/job job :work/assignment assignment
              :work/runner-participation runner-participation
              :work/execution-environment execution-environment
              :work/dependency-manifest dependency-manifest
              :work/resolved-dependencies resolved-dependencies
              :work/implementation implementation :work/assured-job assured-job
              :work/action action :work/expected-action-root expected-action-root
              :work/expected-prestate-root expected-prestate-root
              :work/expected-poststate-root expected-poststate-root}
        rooted (assoc body :research-observed-execution-work/root (work-root body))]
    (when-not (valid-work? rooted)
      (throw (ex-info "Invalid observed execution work bundle" {})))
    rooted))

(defn execute-work!
  "Run exactly the rooted work bundle through the existing fresh-Anvil adapter
   and derive its existing observed-run and V1 assurance artifacts."
  [work]
  (when-not (valid-work? work)
    (throw (ex-info "Invalid observed execution work bundle" {})))
  (let [result ((anvil-operation "execute!") {:job (:work/job work)
                                              :assignment (:work/assignment work)
                                              :runner-participation (:work/runner-participation work)
                                              :execution-environment (:work/execution-environment work)
                                              :dependency-manifest (:work/dependency-manifest work)
                                              :resolved-dependencies (:work/resolved-dependencies work)
                                              :implementation-root (get-in work [:work/implementation :research-implementation-identity/root])
                                              :action (:work/action work)
                                              :expected-action-root (:work/expected-action-root work)
                                              :expected-prestate-root (:work/expected-prestate-root work)
                                              :expected-poststate-root (:work/expected-poststate-root work)})]
    (when-not (= :complete (:execution/status result))
      (throw (ex-info "Observed execution did not complete" {:execution result})))
    (assoc (observed/build-observed-run-assurance
            {:assured-job (:work/assured-job work)
             :assignment (:work/assignment work)
             :runner-participation (:work/runner-participation work)
             :resolved-dependencies (:work/resolved-dependencies work)
             :implementation (:work/implementation work)
             :receipt (:receipt result) :validation (:fixed-validation result)})
           :execution result)))
