(ns resolver-sim.evidence.forensic-graph-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.evidence.graph-producer :as producer]))

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

(deftest generic-producer-is-deterministic-and-fails-closed
  (let [parent (evidence job-subject :verified :causal-parent-roots [])
        child (evidence result-subject :derived
                        :parent-nodes [(:node-hash parent)]
                        :causal-parent-roots [(:subject/root job-subject)])
        roots [(root 1) (root 90)]
        input {:adapter-id :test/pro-rata :adapter-version 1
               :artifact-roots roots :nodes [parent child]}
        forward (producer/produce input)
        reversed (producer/produce (assoc input :nodes [child parent]))
        replayed-parent (evidence job-subject :verified :causal-parent-roots [])
        replayed-child (evidence result-subject :derived
                                 :parent-nodes [(:node-hash replayed-parent)]
                                 :causal-parent-roots [(:subject/root job-subject)])
        replayed (producer/produce (assoc input :nodes [replayed-child replayed-parent]))
        tampered (assoc-in input [:nodes 0 :evidence :inputs-hash] "tampered")
        missing-root (producer/produce
                      {:adapter-id :test/missing-source
                       :adapter-version 1
                       :artifact-roots []
                       :nodes [(evidence job-subject :declared)]})
        cyclic-parent (assoc parent :parent-hashes [(:node-hash child)])
        cycle (producer/produce (assoc input :nodes [cyclic-parent child]))]
    (is (= (mapv :node-hash (:nodes forward))
           (mapv :node-hash (:nodes replayed))))
    (is (= (:graph forward) (:graph replayed)))
    (is (= (:nodes forward) (:nodes reversed)))
    (is (= (:graph forward) (:graph reversed)))
    (is (:valid? (:graph forward)))
    (is (some #(= :verified (get-in % [:extensions :forensic/role]))
              (:nodes forward)))
    (is (= :test/pro-rata (:adapter/id forward)))
    (is (= 1 (:producer/version forward)))
    (is (false? (get-in forward [:provenance :source-authenticated?]))
        "A self-declared verified role is not source authentication.")
    (is (false? (:integrity-valid? (:graph (producer/produce tampered)))))
    (is (false? (:integrity-valid? (:graph missing-root))))
    (is (some #(= :node/cycle (:error %))
              (get-in cycle [:graph :dag :errors])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (producer/produce (assoc input :artifact-roots [(root 90) (root 1)]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (producer/produce (assoc input :nodes [parent parent]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (producer/produce (assoc input :adapter-id :unqualified))))))
(deftest generic-producer-preserves-conflicts-and-coverage-gaps
  (let [left (evidence job-subject :observed
                       :observation-key :environment/runtime-root
                       :observed-root (root 20))
        right (evidence job-subject :observed
                        :observation-key :environment/runtime-root
                        :observed-root (root 21))
        production (producer/produce
                    {:adapter-id :test/deployment
                     :adapter-version 1
                     :artifact-roots [(root 90)]
                     :nodes [left right]
                     :profile {:forensic-profile/requirements
                               [{:subject result-subject
                                 :roles #{:validated}
                                 :required? true}]}})]
    (is (false? (get-in production [:graph :valid?])))
    (is (= 1 (get-in production [:graph :summary :conflict-count])))
    (is (= 1 (get-in production [:graph :summary :required-gap-count])))
    (is (some #(= :forensic/conflicting-observations (:error %))
              (get-in production [:graph :errors])))
    (is (some #(= :forensic/required-evidence-missing (:error %))
              (get-in production [:graph :errors])))))
(deftest sew-simulator-trace-is-a-portability-fixture-not-chain-evidence
  (let [trace-path "suites/sew-domain-reference-v1/expected/traces/sew-002-pending-settlement-expiry.trace.json"
        sidecar-path (str/replace trace-path ".trace.json" ".trace.sha256")
        bytes (java.nio.file.Files/readAllBytes (.toPath (io/file trace-path)))
        digest (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                               (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))
        pinned "203acbb6695bf68979862d6616ce4be09b95ca62ba517bc52f96ed10e254ba6d"
        trace (json/read-str (String. bytes "UTF-8"))
        sidecar (str/trim (slurp sidecar-path))
        trace-root (str "sha256:" digest)
        subject {:subject/root trace-root :subject/kind :sew/simulator-trace}
        first-node (evidence subject :declared :causal-parent-roots [])
        steps (get trace "steps")
        step-nodes (second
                    (reduce (fn [[parent-hash nodes] step]
                              (let [cutpoint-kind (keyword
                                                   (str "sew-step-" (get step "seq") "-"
                                                        (-> (get step "event_type")
                                                            str/lower-case
                                                            (str/replace #"[^a-z0-9]+" "-"))))
                                    entry (evidence subject :declared
                                                    :parent-nodes [parent-hash]
                                                    :causal-parent-roots [trace-root]
                                                    :actor {:actor/root trace-root
                                                            :actor/role :system}
                                                    :cutpoint {:cutpoint/root trace-root
                                                               :cutpoint/kind cutpoint-kind})]
                                [(:node-hash entry) (conj nodes entry)]))
                            [(:node-hash first-node) []]
                            steps))
        gap-node (evidence subject :declared
                           :parent-nodes [(-> step-nodes last :node-hash)]
                           :causal-parent-roots [trace-root]
                           :gap {:gap/status :not-verifiable
                                 :gap/reason :simulator-only-no-chain-observation})
        nodes (into [first-node] (conj step-nodes gap-node))
        production (producer/produce
                    {:adapter-id :sew/reference-trace
                     :adapter-version 1
                     :artifact-roots [trace-root]
                     :nodes nodes})]
    (is (= pinned digest))
    (is (= pinned sidecar))
    (is (= 6 (count steps)))
    (is (:valid? (get-in production [:graph])))
    (is (= (+ 2 (count steps)) (get-in production [:graph :summary :node-count])))
    (is (= :sew/reference-trace (:adapter/id production)))
    (is (every? #(= :sew/simulator-trace
                    (get-in % [:extensions :forensic/subject :subject/kind]))
                (:nodes production)))
    (is (= (set (map #(keyword
                       (str "sew-step-" (get % "seq") "-"
                            (-> (get % "event_type")
                                str/lower-case
                                (str/replace #"[^a-z0-9]+" "-"))))
                     steps))
           (set (keep #(get-in % [:extensions :forensic/cutpoint :cutpoint/kind])
                      (:nodes production))))
        "Cutpoints identify the ordered source trace steps.")
    (is (= :not-verifiable
           (->> (:nodes production)
                (keep #(get-in % [:extensions :forensic/gap :gap/status]))
                first))
        "The graph does not promote simulator steps to chain observations.")))
