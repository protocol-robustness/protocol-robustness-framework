(ns resolver-sim.evidence.forensic-graph-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(defn root [n] (str "sha256:" (format "%064x" n)))

(def job-subject {:subject/root (root 1) :subject/kind :research-execution/job})
(def result-subject {:subject/root (root 2) :subject/kind :research-result/semantic})
(def partial-subject {:subject/root (root 3) :subject/kind :accounting/canonical-partial})

(defn evidence
  [subject role & {:as opts}]
  (apply forensic/build-forensic-evidence-node
         (merge {:subject subject :role role :visibility :commitment-only
                 :causal-parent-roots [(root 90)]}
                opts)
         []))

(deftest subject-binding-prevents-evidence-substitution
  (let [a (evidence job-subject :declared)
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [a]
                :artifact-roots [(root 90)]
                :profile {:forensic-profile/requirements
                          [{:subject result-subject :roles #{:declared} :required? true}]}})]
    (is (false? (:valid? graph)))
    (is (= :forensic/required-evidence-missing (-> graph :errors last :error)))
    (is (= result-subject (-> graph :gaps first :forensic/subject)))))

(deftest semantic-subject-is-independent-of-forensic-runner-provenance
  (let [a (evidence partial-subject :derived
                    :actor {:actor/root (root 11) :actor/role :runner})
        b (evidence partial-subject :derived
                    :actor {:actor/root (root 12) :actor/role :runner})
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [a b] :artifact-roots [(root 90)]})]
    (is (= (:subject/root partial-subject)
           (get-in a [:extensions :forensic/subject :subject/root])
           (get-in b [:extensions :forensic/subject :subject/root])))
    (is (not= (:node-hash a) (:node-hash b)))
    (is (:valid? graph))))

(deftest optional-gaps-do-not-invalidate-unrelated-evidence
  (let [a (evidence job-subject :declared)
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [a] :artifact-roots [(root 90)]
                :profile {:forensic-profile/requirements
                          [{:subject job-subject :roles #{:declared :attested}
                            :required? false}]}})]
    (is (:valid? graph))
    (is (= 1 (get-in graph [:summary :gap-count])))
    (is (= :not-applicable (-> graph :gaps first :gap/status)))))

(deftest required-gap-and-explicit-gap-remain-distinct
  (let [missing (evidence result-subject :observed
                          :gap {:gap/status :not-observed
                                :gap/reason :poststate-unavailable})
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [missing] :artifact-roots [(root 90)]
                :profile {:forensic-profile/requirements
                          [{:subject result-subject :roles #{:observed :validated}
                            :required? true}]}})]
    (is (false? (:valid? graph)))
    (is (= :not-observed (get-in missing [:extensions :forensic/gap :gap/status])))
    (is (= [:validated] (-> graph :gaps first :forensic/missing-roles)))))

(deftest incompatible-observations-are-retained-as-conflict
  (let [a (evidence job-subject :observed :observation-key :environment/code-root
                    :observed-root (root 20))
        b (evidence job-subject :observed :observation-key :environment/code-root
                    :observed-root (root 21))
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [a b] :artifact-roots [(root 90)]})]
    (is (false? (:valid? graph)))
    (is (= :conflicting (-> graph :conflicts first :conflict/status)))
    (is (= [(root 20) (root 21)]
           (-> graph :conflicts first :forensic/observed-roots)))))

(deftest causal-parent-and-consequence-integrity-are-enforced
  (let [orphan (evidence result-subject :derived :causal-parent-roots [] :consequence? true)
        unknown (evidence result-subject :validated :causal-parent-roots [(root 99)])
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [orphan unknown] :artifact-roots [(root 90)]})]
    (is (false? (:integrity-valid? graph)))
    (is (some #(= :forensic/orphan-required-consequence (:error %)) (:errors graph)))
    (is (some #(= :forensic/missing-causal-artifact (:error %)) (:errors graph)))))

(deftest commitment-only-private-custody-does-not-require-plaintext
  (let [sealed (evidence job-subject :declared :visibility :sealed
                         :custody-roots [(root 70)])
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [sealed] :artifact-roots [(root 70) (root 90)]})]
    (is (:valid? graph))
    (is (= [(root 70)] (get-in sealed [:extensions :forensic/custody-roots])))
    (is (= :sealed (get-in sealed [:extensions :forensic/visibility])))))

(deftest generic-evidence-node-dag-validation-is-composed
  (let [parent (evidence job-subject :declared)
        child (evidence result-subject :derived
                        :parent-nodes [(:node-hash parent)]
                        :causal-parent-roots [(root 1)])
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [parent child] :artifact-roots [(root 90)]})]
    (is (:valid? graph))
    (is (:valid? (:dag graph)))
    (is (= 2 (get-in graph [:summary :node-count])))))

(deftest declaration-only-anvil-chain-fits-existing-evidence-dag
  (let [environment {:subject/root (root 30) :subject/kind :research-execution/environment}
        action {:subject/root (root 31) :subject/kind :research-execution/action}
        pre (evidence environment :declared :causal-parent-roots [(root 40)])
        action-node (evidence action :declared :parent-nodes [(:node-hash pre)]
                              :causal-parent-roots [(root 30)])
        post (evidence environment :observed :parent-nodes [(:node-hash action-node)]
                       :causal-parent-roots [(root 31)]
                       :gap {:gap/status :not-observed
                             :gap/reason :declaration-only})
        result (evidence result-subject :derived :parent-nodes [(:node-hash post)]
                         :causal-parent-roots [(root 30) (root 31)] :consequence? true)
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [pre action-node post result]
                :artifact-roots [(root 40)]})]
    (is (:valid? graph))
    (is (= :declaration-only (get-in post [:extensions :forensic/gap :gap/reason])))
    (is (= result-subject (get-in result [:extensions :forensic/subject])))))

(deftest cycles-remain-rejected-by-the-generic-node-dag
  (let [a (evidence job-subject :declared)
        b (evidence result-subject :derived :parent-nodes [(:node-hash a)])
        ;; Deliberate tampering demonstrates that the composed generic DAG
        ;; validator, rather than a parallel forensic graph, rejects it.
        cyclic-a (assoc a :parent-hashes [(:node-hash b)])
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [cyclic-a b] :artifact-roots [(root 90)]})]
    (is (false? (:valid? graph)))
    (is (some #(= :node/cycle (:error %)) (get-in graph [:dag :errors])))))
