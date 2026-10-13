(ns resolver-sim.evidence.investigation-dispatcher
  "Planning-only investigation dispatch over independently reconstructed inputs.
   Plans are diagnostic work records. They grant no evidence, execution,
   acceptance, reward, or payment authority. No runner is executed here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [resolver-sim.evidence.deployment-bound :as deployment]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private profile
  {:adapter "resolver-sim.evidence.deployment-bound/v0"
   :manifest-schema "prf-evm.gate-e-entitlement-deployment.v1"
   :program-id "source-bound-entitlement-sp1-program.v1"
   :scope :local-only})

(def ^:private rules
  {:missing-source {:action :request-evidence :intended-role :validated}
   :missing-observation {:action :request-evidence :intended-role :observed}
   :unverified-claim {:action :request-independent-validation :intended-role :validated
                      :blockers [:qualified-independent-validator-unavailable]}
   :conflicting-evidence {:action :review-conflict :intended-role :validated
                          :blockers [:independent-conflict-resolution-required]}
   :policy-required-attestation {:action :request-attestation :intended-role :attested
                                 :blockers [:policy-and-attestor-authority-required]}})

(def ^:private optional-gap-kinds #{:optional-research :optional-reward})

(def ^:private reconstruction-runner
  {:id "deployment-bound-forensic-v0"
   :action :reconstruct-forensic-view
   :argv ["clojure" "-M:with-sew" "-m"
          "resolver-sim.evidence.deployment-bound"
          "<retained-bundle-dir>" "<new-forensic-output-dir>"]
   :input-profile profile
   :required-validation :independently-reconstruct-retained-artifacts
   :authorization :not-granted-by-plan})

(defn- record-root [record]
  (str "sha256:" (hc/hash-with-intent {:hash/intent :evidence-record} record)))

(defn- source-binding [{:keys [artifact-hashes view parsed]}]
  (let [artifact-roots (into (sorted-map)
                             (map (fn [[k v]] [k (str "sha256:" v)])) artifact-hashes)
        node-hashes (vec (sort (map :node-hash (:nodes view))))]
    {:bundle-input-root (record-root {:schema "investigation-bundle-inputs.v0"
                                      :artifact-roots artifact-roots})
     :graph-input-root (record-root {:schema "investigation-graph-inputs.v0"
                                     :artifact-roots artifact-roots :node-hashes node-hashes
                                     :profile profile})
     :artifact-roots artifact-roots
     :node-hashes node-hashes
     ;; This is a declared source reference, not a reconstructed source root.
     :declared-source-bundle-reference (get-in parsed [:fixture "gate_e" "source_bundle_sha256"])
     :context {:chain-id (get-in parsed [:manifest "chainId"])
               :local-only (boolean (get-in parsed [:manifest "localOnly"]))
               :operation-id (get-in parsed [:manifest "operationId"])
               :prepared-operation-root (get-in parsed [:manifest "preparedOperationRoot"])
               :configuration-root (get-in parsed [:manifest "configurationRoot"])
               :configuration-epoch (get-in parsed [:manifest "configurationEpoch"])
               :program-id (get-in parsed [:fixture "program_id"])
               :vkey (get-in parsed [:fixture "vkey"])
               :elf-sha256 (get-in parsed [:fixture "elf_sha256"])
               :declared-contract-addresses (get-in parsed [:manifest "contracts"])
               :retained-block-contexts
               (->> (:broadcasts parsed)
                    vals
                    (mapcat #(get % "receipts"))
                    (map #(select-keys % ["blockNumber" "blockHash"]))
                    distinct
                    (sort-by record-root)
                    vec)}}))

(defn- route-blockers [{:keys [parsed validation view]}]
  (cond-> []
    (or (not= (:manifest-schema profile) (get-in parsed [:manifest "schema"]))
        (not= (:program-id profile) (get-in parsed [:fixture "program_id"])))
    (conj :unsupported-bundle-profile)
    ;; V0 routes only the known deployment adapter; arbitrary declared protocol
    ;; descriptors do not select validators or runners through this entry point.
    (some #(contains? (get parsed %) "protocol") [:manifest :fixture :report])
    (conj :unsupported-protocol-descriptor)
    (or (not (true? (get-in validation [:checks :local-chain-and-local-only])))
        (not (let [scope (get-in parsed [:manifest "localOnly"])]
               (or (true? scope) (and (map? scope) (seq scope))))))
    (conj :unsupported-chain-or-environment)
    (not (true? (get-in view [:graph :integrity-valid?])))
    (conj :forensic-graph-integrity-invalid)
    (some #(not (true? (get-in validation [:checks %])))
          [:source-files-match-report :manifest-digest-recomputed :fixture-identities-bound
           :proof-witness-manifest-crosslinks :prepared-context-bound :reserve-input-bound
           :public-values-structure-and-binding :proof-bytes-hash-bound
           :witness-provenance-bound :all-retained-receipt-records-consistent])
    (conj :source-or-context-validation-failed)
    (some #(and (contains? (:checks validation) %)
                (not (true? (get-in validation [:checks %]))))
          [:source-bundle-content-bound :witness-derived-public-values])
    (conj :source-or-derived-output-validation-failed)))

(defn- issues [{:keys [validation view]}]
  (->> (concat (map #(assoc % :origin :adapter-gap) (:gaps validation))
               (map #(assoc % :origin :adapter-error) (:errors validation))
               (map #(assoc % :kind :missing-observation :origin :graph-gap)
                    (get-in view [:graph :gaps]))
               (map #(assoc % :kind :conflicting-evidence :origin :graph-conflict)
                    (get-in view [:graph :conflicts]))
               (map #(assoc % :kind :unverified-claim :origin :graph-error)
                    (get-in view [:graph :errors])))
       distinct
       (sort-by record-root)
       vec))

(defn- task [source issue routing-blockers]
  (let [rule (get rules (:kind issue))
        blockers (vec (concat routing-blockers (:blockers rule)
                              (when-not rule [:unsupported-gap-type])))
        definition {:schema "investigation-task.v0"
                    :source source :gap-root (record-root issue) :issue issue
                    :action (if (or (seq routing-blockers) (nil? rule)) :manual-review (:action rule))
                    :intended-role (:intended-role rule)
                    :depends-on [] :blockers blockers
                    :result-artifact-references []
                    :closure :independent-validator-and-graph-reconstruction-required}]
    (assoc definition :task-id (record-root definition)
           :status (if (seq blockers) :blocked :planned))))

(defn- plan-reconstruction [reconstruction max-tasks]
  (let [source (source-binding reconstruction)
        blockers (route-blockers reconstruction)
        all-issues (issues reconstruction)
        excluded (filterv #(contains? optional-gap-kinds (:kind %)) all-issues)
        issue-tasks (->> all-issues
                         (remove #(contains? optional-gap-kinds (:kind %)))
                         (map #(task source % blockers))
                         (sort-by (juxt #(if (= :blocked (:status %)) 0 1) :task-id))
                         vec)
        final-definition {:schema "investigation-task.v0" :source source
                          :action (if (seq blockers) :manual-review (:action reconstruction-runner))
                          :intended-role :validated
                          :runner (when (empty? blockers) reconstruction-runner)
                          :depends-on (mapv :task-id issue-tasks)
                          :blockers (vec (concat blockers
                                                 (when (seq issue-tasks) [:unresolved-evidence-requirements])))
                          :result-artifact-references []
                          :closure :independent-validator-and-graph-reconstruction-required}
        final-task (assoc final-definition :task-id (record-root final-definition)
                          :status (if (or (seq blockers) (seq issue-tasks)) :blocked :planned))
        all-tasks (conj issue-tasks final-task)
        selected (vec (take max-tasks all-tasks))
        deferred (mapv #(assoc % :deferred-reason :task-budget-exhausted)
                       (drop max-tasks all-tasks))
        definition {:schema "prf.investigation-plan.v0" :profile profile :source source
                    :planner-definition-root (record-root {:profile profile :rules rules
                                                           :runner reconstruction-runner
                                                           :optional-gap-kinds (vec (sort optional-gap-kinds))})
                    :mode :planning-only
                    :authority {:evidence-verification :existing-independent-validators
                                :execution-authorized false :acceptance-authorized false
                                :reward-or-payment-authorized false}
                    :budget {:max-tasks max-tasks :execution-attempts 0 :retries 0}
                    :routing-blockers blockers
                    :reconstructed-validation (:validation reconstruction)
                    :reconstructed-graph-validation (get-in reconstruction [:view :graph])
                    :excluded-issues (mapv #(assoc % :reason-for-exclusion :outside-v0-evidence-closure
                                                   :required-for-evidence-closure false) excluded)
                    :tasks selected :deferred-tasks deferred
                    :status (if (or (seq blockers) (seq issue-tasks)) :incomplete :ready-for-review)}]
    (assoc definition :plan-root (record-root definition))))

(defn plan-bundle
  "Reconstruct raw retained evidence before planning. Caller-supplied reports,
   graph labels, executable commands, or completion flags are not inputs.
   An unsupported complete bundle produces blocked manual-review tasks;
   unreadable/incomplete evidence fails before a plan can be created."
  ([bundle-dir] (plan-bundle bundle-dir {}))
  ([bundle-dir opts]
   (when-not (and (map? opts) (every? #{:max-tasks :source-bundle} (keys opts)))
     (throw (ex-info "Unsupported investigation planning option" {:options opts})))
   (let [max-tasks (get opts :max-tasks 32)]
     (when-not (and (integer? max-tasks) (<= 1 max-tasks 100))
       (throw (ex-info "max-tasks must be an integer between 1 and 100" {})))
     (plan-reconstruction (deployment/reconstruct-bundle bundle-dir (select-keys opts [:source-bundle])) max-tasks))))

(defn write-plan!
  "Write a reviewable EDN work plan into a new directory. The content root uses
   the existing evidence-record hash intent; it is not an evidence admission."
  [bundle-dir output-dir opts]
  (let [plan (plan-bundle bundle-dir opts)
        out (io/file output-dir)]
    ;; Atomic create-directory refuses overwrites, including concurrent writers.
    (java.nio.file.Files/createDirectory (.toPath out)
                                         (make-array java.nio.file.attribute.FileAttribute 0))
    (with-open [writer (io/writer (io/file out "investigation-plan.edn"))]
      (binding [*out* writer] (pprint/pprint plan)))
    plan))

(defn verify-plan
  "Reconstruct from retained inputs and compare the entire proposed work plan.
   A matching plan means reproducible planning, never verified conclusions."
  ([bundle-dir plan-file] (verify-plan bundle-dir plan-file {}))
  ([bundle-dir plan-file opts]
   (when-not (and (map? opts) (every? #{:source-bundle} (keys opts)))
     (throw (ex-info "Unknown plan verification option" {:options opts})))
   (let [retained (edn/read-string (slurp plan-file))
         fresh (plan-bundle bundle-dir (assoc opts :max-tasks (get-in retained [:budget :max-tasks])))]
     {:plan-matches-reconstruction? (= fresh retained)
      :recomputed-plan-root (:plan-root fresh)
      :investigation-status (:status fresh)
      :evidence-valid? (get-in fresh [:reconstructed-validation :valid?])
      :execution-authorized false})))

(defn -main [& args]
  (when-not (or (= 3 (count args))
                (and (= 5 (count args)) (= "--source-bundle" (nth args 3))))
    (throw (ex-info "Usage: plan|verify <bundle-dir> <output-dir|plan.edn> [--source-bundle <vectors.json>]" {})))
  (let [opts (if (= 5 (count args)) {:source-bundle (nth args 4)} {})]
    (case (first args)
      "plan" (let [plan (write-plan! (second args) (nth args 2) opts)]
               (println "plan-root:" (:plan-root plan))
               (println "investigation-status:" (name (:status plan)))
               (println "tasks:" (count (:tasks plan)))
               (println "execution-authorized: false"))
      "verify" (let [result (verify-plan (second args) (nth args 2) opts)]
                 (pprint/pprint result)
                 (when-not (:plan-matches-reconstruction? result) (System/exit 1)))
      (throw (ex-info "Usage: plan|verify <retained-bundle-dir> <output-dir|plan.edn>" {})))))
