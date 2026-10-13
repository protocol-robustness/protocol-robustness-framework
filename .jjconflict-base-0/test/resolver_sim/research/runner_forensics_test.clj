(ns resolver-sim.research.runner-forensics-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.runner-forensics :as forensic]))

(defn- root [n] (str "sha256:" (format "%064x" n)))
(def actor (root 1))
(def job (root 2))
(def assignment (root 3))
(def work (root 4))
(def observed (root 5))
(def assurance (root 6))
(def reachability (root 7))
(def cancellation (root 8))

(defn- event [event-key subject & {:keys [parents observed-root]}]
  (forensic/build-event-node
   {:event-key event-key :subject-root subject
    :subject-kind :research-runner/test :actor-root actor
    :causal-parent-roots [job assignment work]
    :parent-nodes (or parents []) :observed-root (or observed-root subject)}))

(defn- state [nodes overrides]
  (merge {:jobs {job {:research-execution-job/root job}}
          :assignments {assignment {:research-run-assignment/root assignment}}
          :work-items {work {:work/root work}}
          :work-states {}
          :cancellations {}
          :completed-work {}
          :forensic-evidence-nodes (into {} (map (juxt :node-hash identity) nodes))}
         overrides))

(deftest reconstruction-reports-causal-falsification-cases
  (let [delivery (event :runner.forensic/delivery-accepted assignment)
        started (event :runner.forensic/execution-started work
                       :parents [(:node-hash delivery)])
        complete-a (event :runner.forensic/execution-completed observed
                          :parents [(:node-hash started)] :observed-root (root 9))
        complete-b (event :runner.forensic/execution-completed observed
                          :parents [(:node-hash started)] :observed-root (root 10))
        posted (event :runner.forensic/result-posted observed
                      :parents [(:node-hash complete-a)])
        redelivery (event :runner.forensic/result-redelivered observed
                          :parents [(:node-hash posted)])
        no-assignment (event :runner.forensic/execution-started (root 11))
        no-execution (event :runner.forensic/execution-completed (root 12))
        result-no-post {:observed-run-evidence
                        {:research-observed-run-evidence/root (root 13)
                         :proof/source-artifacts {:assignment {:research-run-assignment/root (root 14)}}}
                        :run-assurance-evidence {:research-run-evidence/root (root 13)}
                        :result-deliveries []}
        report (forensic/validate-runner-reconstruction
                (state [delivery started complete-a complete-b posted redelivery
                        no-assignment no-execution]
                       {:work-states {work {:work/status :cancelled
                                            :work/history [{:work/event :cancellation-observed}
                                                           {:work/event :execution-started}]}}
                        :completed-work {work (assoc result-no-post
                                                     :result-deliveries
                                                     [{:delivery/status :accepted}
                                                      {:delivery/status :accepted}])}}))
        errors (set (map :error (:errors report)))]
    (is (contains? errors :runner-forensic/execution-without-accepted-assignment))
    (is (contains? errors :runner-forensic/result-without-execution-lineage))
    (is (contains? errors :runner-forensic/retry-without-prior-unacknowledged-attempt))
    (is (contains? errors :runner-forensic/execution-after-terminal-cancellation))
    (is (contains? errors :runner-forensic/two-successful-executions-for-exclusive-work))
    (is (contains? errors :runner-forensic/coordinator-ingestion-assignment-or-result-substitution))
    (is (contains? errors :runner-forensic/missing-result-delivery-attempt))))
