(ns resolver-sim.research.reproduction-execution-work
  "Durable-runner envelope for canonical research reproduction inputs."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.research.interaction-reproduction :as replay]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-participation :as participation]))

(def schema "research-reproduction-execution-work.v1")
(defn work-root [w]
  (ref/sha256-ref
   (hc/domain-hash :evidence-record
                   (dissoc w :research-reproduction-execution-work/root))))

(defn valid-work? [w]
  (and (= schema (:artifact/schema w))
       (remote/valid-job? (:work/job w))
       (remote/valid-assignment? (:work/assignment w))
       (:valid? (participation/validate-participation (:work/runner-participation w)))
       (replay/valid-input? (:work/reproduction-input w))
       (assurance/valid-implementation? (:work/implementation w))
       (replay/valid-implementation-for-input? (:work/reproduction-input w)
                                               (:work/implementation w))
       (= (:reproduction-input/root (:work/reproduction-input w)) (:workload/root (:work/job w)))
       (= (:research-execution-job/root (:work/job w))
          (:research-execution-job/root (:work/assignment w)))
       (= (:research-execution-environment/root (:work/job w))
          (:research-execution-environment/root (:work/assignment w)))
       (= (:research-execution-policy/root (:work/job w))
          (:research-execution-policy/root (:work/assignment w)))
       (= (:runner-participation/root (:work/runner-participation w))
          (:runner-participation/root (:work/assignment w)))
       (= (:runner/reference (:work/runner-participation w))
          (:runner/reference (:work/assignment w)))
       (= (:research-reproduction-execution-work/root w) (work-root w))))

(defn build-work [{:keys [job assignment runner-participation reproduction-input implementation]}]
  (let [body {:artifact/schema schema :work/job job :work/assignment assignment
              :work/runner-participation runner-participation
              :work/reproduction-input reproduction-input :work/implementation implementation}
        work (assoc body :research-reproduction-execution-work/root (work-root body))]
    (when-not (valid-work? work)
      (throw (ex-info "Invalid reproduction work bundle" {})))
    work))

(defn execute-work! [work]
  (when-not (valid-work? work)
    (throw (ex-info "Invalid reproduction work" {})))
  (replay/execute (:work/reproduction-input work) (:work/implementation work)))
