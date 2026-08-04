(ns resolver-sim.community.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]
            [resolver-sim.community.result :as result]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.graph.export :as gex]
            [resolver-sim.logging :as log]
            [resolver-sim.vcs :as vcs]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.config.paths :as paths]))

(declare compute-code-hash compute-env-hash compute-registry-hash)

(def ^:const default-mailbox-dir
  (str (System/getProperty "user.home") "/.protocol-robustness/community-mailbox"))

(def ^:const default-artifact-dir
  (str (System/getProperty "user.home") "/.protocol-robustness/community-artifacts"))

(def cli-options
  [["-h" "--help" "Show help"]
   ["-t" "--task TASK-REF" "Task reference"]
   ["-r" "--runner RUNNER-ID" "Runner identity"]
   ["-k" "--key PATH" "Path to private signing key"]
   ["-o" "--original-attestation REF" "Original attestation reference for reproduction"]
   ["-d" "--dir DIR" "Artifact/mailbox directory" :default default-artifact-dir]
   ["-m" "--mailbox-dir DIR" "Mailbox directory" :default default-mailbox-dir]
   ["-b" "--benchmark-id ID" "Benchmark ID (e.g. :benchmark/prf-deterministic-replay-v1)"]
   ["-n" "--title TITLE" "Task title"]
   [nil "--description DESC" "Task description (optional)"]
   ["-s" "--suite-id ID" "Suite ID (e.g. :suite/prf-replay-v1)"]
   [nil "--claim-ids CLAIMS" "Comma-separated claim IDs (e.g. :claim/no-nondeterminism,:claim/replay-identical-results)"]
   [nil "--out DIR" "Output directory for graph export files (SVG, JSON, GraphML)"]
   [nil "--allow-dirty" "Allow dirty git working copy during execution"]
   [nil "--benchmark-filter ID" "Filter tasks by benchmark ID (e.g. :benchmark/prf-deterministic-replay-v1)"]
   [nil "--suite-filter ID" "Filter tasks by suite ID (e.g. :suite/prf-replay-v1)"]])

(defn list-tasks
  [opts]
  (let [dir (or (:mailbox-dir opts) default-mailbox-dir)
        parse-kw (fn [s] (when s (keyword (str/replace s #"^:" ""))))
        bm-filter (parse-kw (:benchmark-filter opts))
        suite-filter (parse-kw (:suite-filter opts))]
    (binding [mailbox/*mailbox-dir* dir]
      (let [msgs (mailbox/list-messages :type-filter :TASK_ANNOUNCEMENT)
            filtered (filter (fn [m]
                               (let [body (:body m)]
                                 (and (or (nil? bm-filter) (= bm-filter (:benchmark/id body)))
                                      (or (nil? suite-filter) (= suite-filter (:suite/id body))))))
                             msgs)]
        (if (seq filtered)
          (do
            (println (format "%-70s %-25s %-25s %s" "Task Ref" "Title" "Status" "Benchmark"))
            (println (apply str (repeat 140 "-")))
            (doseq [m filtered]
              (let [body (:body m)
                    task-ref (or (:subject-task m) "")
                    title (or (:title body) "")
                    bm (or (:benchmark/id body) "-")
                    status (mailbox/task-status (:subject-task m))]
                (println (format "%-70s %-25s %-25s %s" task-ref title (name status) bm))))
            {:exit-code 0})
          (do (println "No community tasks found.")
              {:exit-code 0}))))))

(defn show-task
  [opts]
  (let [task-ref (:task opts)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (let [dir (or (:mailbox-dir opts) default-mailbox-dir)]
        (binding [mailbox/*mailbox-dir* dir]
          (let [msgs (mailbox/messages-for-task task-ref)
                status (mailbox/task-status task-ref)]
            (println "Task Reference:" task-ref)
            (println "Status:" (name status))
            (println "Messages:" (count msgs))
            (doseq [m msgs]
              (println (str "  " (:message/type m) " — " (:sender m)
                            (when (:message/signature m) " [signed]"))))
            {:exit-code 0}))))))

(defn- compute-code-hash
  "Return code identity: git commit hash with dirty-state marker.
   Returns {:code-hash hex :code-source kw :worktree-status kw :diff-hash hex-or-nil}
   When the worktree is dirty, includes a diff hash to distinguish materially
   different code states that share the same commit."
  []
  (try (let [src (vcs/source-provenance)
             commit-hash (or (:source/hash src) "0000000000000000000000000000000000000000000000000000000000000000")
             dirty? (:dirty? src)]
         (if dirty?
           (let [diff-hash (or (try (vcs/dirty-diff-hash)
                                    (catch Exception _ nil))
                               "0000000000000000000000000000000000000000000000000000000000000000")
                 combined (hc/domain-hash "COMMUNITY_CODE_V0"
                                          {:git-commit commit-hash
                                           :worktree-status :dirty
                                           :diff-hash diff-hash})]
             {:code-hash combined
              :code-source :git-commit-plus-diff
              :worktree-status :dirty
              :diff-hash diff-hash})
           {:code-hash commit-hash
            :code-source :git-commit
            :worktree-status :clean
            :diff-hash nil}))
       (catch Exception _
         {:code-hash "0000000000000000000000000000000000000000000000000000000000000000"
          :code-source :unknown
          :worktree-status :unknown
          :diff-hash nil})))

(defn- compute-env-hash
  "Fingerprint the execution environment.
   Covers execution-affecting environment: language runtime, OS, architecture,
   and runner implementation. Does NOT include hardware-specific identifiers.
   
   Fields:
   - clojure-version     — Clojure language version
   - java-version        — Java runtime version (e.g. \"17.0.1\")
   - java-vendor         — Java runtime vendor (e.g. \"Eclipse Adoptium\")
   - java-vm-name        — JVM implementation name
   - os-name             — Operating system name
   - os-arch             — System architecture (e.g. \"amd64\")
   - os-version          — Operating system version
   - runner-type         — Always :community-cli for this runner"
  []
  (hc/domain-hash "COMMUNITY_ENV_V0"
                  {:clojure-version (or (try (clojure-version) (catch Exception _ "unknown")) "unknown")
                   :java-version (or (System/getProperty "java.version") "unknown")
                   :java-vendor (or (System/getProperty "java.vendor") "unknown")
                   :java-vm-name (or (System/getProperty "java.vm.name") "unknown")
                   :os-name (or (System/getProperty "os.name") "unknown")
                   :os-arch (or (System/getProperty "os.arch") "unknown")
                   :os-version (or (System/getProperty "os.version") "unknown")
                   :runner-type :community-cli}))

(defn- compute-registry-hash
  "Hash the current execution registry for snapshot binding."
  []
  (try (hc/hash-with-intent {:hash/intent :registry}
                            (requiring-resolve 'resolver-sim.definitions.passive-registries/execution-registry))
       (catch Exception _ (hc/domain-hash "REGISTRY_V1" {:registry-version 1 :executions []}))))

(defn- read-benchmark-claims
  "Read claim IDs from a benchmark manifest."
  [manifest-path]
  (try (let [manifest (edn/read-string (slurp (io/file manifest-path)))
             claims (:benchmark/claims manifest)]
         (mapv #(if (keyword? %) % (:claim/id %)) (or claims [])))
       (catch Exception _ nil)))

(defn- resolve-benchmark-manifest
  "Resolve a benchmark manifest path from a task's :benchmark/id.
   Reads benchmarks/registry.edn and walks the pack hierarchy.
   Falls back to the default deterministic-replay path."
  [benchmark-id]
  (when benchmark-id
    (try
      (let [reg-file (io/file (paths/benchmarks-registry))]
        (when (.exists reg-file)
          (let [registry (edn/read-string (slurp reg-file))]
            (some (fn [pack]
                    (let [pack-reg-path (str (paths/benchmarks-dir) "/" (:pack/registry pack))
                          pack-reg (when (.exists (io/file pack-reg-path))
                                     (edn/read-string (slurp pack-reg-path)))
                          pack-dir (.getParent (io/file pack-reg-path))]
                      (some (fn [b]
                              (when (= (:benchmark/id b) benchmark-id)
                                (str pack-dir "/" (:benchmark/file b))))
                            (:benchmarks pack-reg))))
                  (:packs registry)))))
      (catch Exception _ nil))))

(defn register-task
  "Build a community task record and publish it to the mailbox."
  [opts]
  (let [title (:title opts)
        benchmark-id (:benchmark-id opts)
        suite-id (:suite-id opts)
        claim-ids-str (:claim-ids opts)
        registry-hash (compute-registry-hash)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not title
      (do (println "Usage: --title <title> [--benchmark-id <id> --suite-id <id> --claim-ids <ids>]")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [parse-kw (fn [s] (when s (keyword (str/replace s #"^:" ""))))
              benchmark-kw (parse-kw benchmark-id)
              claim-ids (let [from-bench (when benchmark-kw
                                           (when-let [mp (resolve-benchmark-manifest benchmark-kw)]
                                             (read-benchmark-claims mp)))]
                          (if (and (not claim-ids-str) from-bench)
                            from-bench
                            (vec (keep parse-kw (when claim-ids-str (str/split claim-ids-str #","))))))
              manifest-claims (when benchmark-kw
                                (when-let [mp (resolve-benchmark-manifest benchmark-kw)]
                                  (set (read-benchmark-claims mp))))
              _ (when (and manifest-claims (seq claim-ids))
                  (doseq [c claim-ids]
                    (assert (contains? manifest-claims c)
                            (str "Claim " c " is not evaluated by benchmark " benchmark-id
                                 ". Known claims: " (pr-str (sort manifest-claims))))))
              description (:description opts)
              t (task/build-task
                 {:title title
                  :description description
                  :task/type :benchmark-execution
                  :benchmark/id benchmark-kw
                  :suite/id (parse-kw suite-id)
                  :claim-ids claim-ids
                  :acceptance-criteria []
                  :registry-snapshot/hash registry-hash})
              msg (mailbox/build-message
                   {:message/type :TASK_ANNOUNCEMENT
                    :subject-task (:task/ref t)
                    :sender "community-registrar"
                    :body {:title title
                           :description description
                           :benchmark/id benchmark-kw
                           :suite/id (parse-kw suite-id)
                           :claim-ids claim-ids
                           :registry-snapshot/hash registry-hash}})]
          (mailbox/publish! msg)
          (println (str "Task registered: " (:task/ref t)))
          (println (str "Title: " title))
          (when benchmark-id
            (println (str "Benchmark: " benchmark-id)))
          (when (seq claim-ids)
            (println (str "Claims: " (pr-str claim-ids))))
          (println)
          (println "To execute this task:")
          (println (str "  bb community:run --task " (:task/ref t) " --runner <id> --key <key>"))
          {:exit-code 0 :task t :message msg})))))

(defn run-task
  [opts]
  (let [task-ref (:task opts)
        runner-id (:runner opts)
        key-path (:key opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not (and task-ref runner-id key-path)
      (do (println "Usage: --task <task-ref> --runner <runner-id> --key <private-key>")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir
                chain/*allow-dirty* (:allow-dirty opts)]
        (let [announce-msgs (filter #(= :TASK_ANNOUNCEMENT (:message/type %))
                                    (mailbox/messages-for-task task-ref))
              benchmark-id (get-in (first announce-msgs) [:body :benchmark/id])
              manifest-path (or (resolve-benchmark-manifest benchmark-id)
                                (paths/prf-core-deterministic-replay-manifest))
              _ (log/info! :task-manifest {:path manifest-path})
              _ (log/info! :task-executing {:task-ref task-ref})
              evidence (try
                         (runner/run-benchmark manifest-path)
                         (catch Exception e
                           (log/error! :task-execution-failed {:error (.getMessage e)})
                           nil))]
          (if-not evidence
            (do (log/error! :task-no-evidence {:message "Benchmark execution produced no evidence. Aborting."})
                {:exit-code 1})
            (let [passed? (= (get-in evidence [:metrics :passed])
                             (get-in evidence [:metrics :total]))
                  bundle-root (:evidence/hash evidence)
                  proj (result/project-stable-result evidence)
                  stable-hash (:stable/hash proj)
                  stable-projection (:stable/projection proj)]
              (println (str "Task ref: " task-ref))
              (println (str "Bundle root: " bundle-root))
              (println (str "Stable result hash: " stable-hash))
              (when-not passed?
                (println (str "WARNING: Benchmark did not pass all scenarios ("
                              (get-in evidence [:metrics :passed]) "/"
                              (get-in evidence [:metrics :total]) ")")))
              (let [code-info (compute-code-hash)
                    env-hash (compute-env-hash)
                    registry-hash (compute-registry-hash)
                    ev-node-obj (ev-node/build-execution-node
                                 {:execution-id :execution/replay
                                  :status (if passed? :pass :fail)
                                  :inputs {:manifest-path manifest-path
                                           :benchmark/id benchmark-id
                                           :task/ref task-ref}
                                  :outputs {:metrics (:metrics evidence)
                                            :bundle-root bundle-root
                                            :claim-results (count (:claim-results evidence))}
                                  :failure-details (when-not passed?
                                                     [{:failure-type :benchmark-failure
                                                       :message (str "benchmark " (get-in evidence [:metrics :passed]) "/" (get-in evidence [:metrics :total]) " passed")
                                                       :expected? false
                                                       :class :unexpected}])})
                    ev-node-hash (:node-hash ev-node-obj)
                    ev-node-ref (str "evidence-node:sha256:" ev-node-hash)
                    attestation (att/build-execution-attestation
                                 {:task/ref task-ref :runner/id runner-id
                                  :code-hash (:code-hash code-info)
                                  :code-provenance {:code-source (:code-source code-info)
                                                    :worktree-status (:worktree-status code-info)
                                                    :diff-hash (:diff-hash code-info)}
                                  :env-hash env-hash
                                  :bundle-root bundle-root
                                  :execution-node-hash ev-node-ref
                                  :result-projection-hash stable-hash
                                  :registry-snapshot-hash registry-hash})
                    signed (att/sign-attestation! attestation key-path)
                    _ (att/persist-attestation! signed dir)
                    msg (mailbox/build-message
                         {:message/type :RUNNER_RESULT :subject-task task-ref
                          :sender runner-id
                          :attestation-ref (:attestation/ref signed)
                          :evidence-ref ev-node-ref
                          :body {:bundle-root bundle-root
                                 :result-projection stable-hash
                                 :stable-projection stable-projection}})
                    signed-msg (mailbox/sign-message! msg key-path)
                    pub-result (mailbox/publish! signed-msg)]
                (println (str "Code hash: " (:code-hash code-info)))
                (println (str "  source: " (name (:code-source code-info))))
                (println (str "  worktree: " (name (:worktree-status code-info))))
                (println (str "Env hash: " env-hash))
                (println (str "Registry snapshot: " registry-hash))
                (println (str "Bundle root: " bundle-root))
                (println (str "Stable result: " stable-hash))
                (println (str "Evidence node: " ev-node-ref))
                (println (str "Attestation: " (:attestation/ref signed)))
                (println (str "Mailbox message: " (if (= :published pub-result) "published" "duplicate")))
                {:exit-code (if passed? 0 1) :attestation signed :message signed-msg}))))))))

(defn reproduce-task
  [opts]
  (let [task-ref (:task opts)
        original-att-ref (:original-attestation opts)
        runner-id (:runner opts)
        key-path (:key opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not (and task-ref original-att-ref runner-id key-path)
      (do (println "Usage: --task <task-ref> --original-attestation <ref> --runner <runner-id> --key <key>")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir
                chain/*allow-dirty* (:allow-dirty opts)]
        (let [original-att (att/resolve-attestation dir original-att-ref)
              _ (log/info! :task-reproducing {:task-ref task-ref})
              announce-msgs (filter #(= :TASK_ANNOUNCEMENT (:message/type %))
                                    (mailbox/messages-for-task task-ref))
              benchmark-id (get-in (first announce-msgs) [:body :benchmark/id])
              manifest-path (or (resolve-benchmark-manifest benchmark-id)
                                (paths/prf-core-deterministic-replay-manifest))
              evidence (try
                         (runner/run-benchmark manifest-path)
                         (catch Exception e
                           (log/error! :task-reproduction-failed {:error (.getMessage e)})
                           nil))]
          (if-not evidence
            (do (log/error! :task-reproduction-no-evidence {:message "Reproduction benchmark produced no evidence. Aborting."})
                {:exit-code 1})
            (let [passed? (= (get-in evidence [:metrics :passed])
                             (get-in evidence [:metrics :total]))
                  claimed-stable-hash (get-in original-att [:assertion :result-projection-hash])
                  bundle-root (:evidence/hash evidence)
                  repro-proj (result/project-stable-result evidence)
                  repro-hash (:stable/hash repro-proj)
                  matched? (and claimed-stable-hash (= repro-hash claimed-stable-hash))
                  comp-status (if matched? :matched :mismatched)]
              (println (str "Original stable hash: " claimed-stable-hash))
              (println (str "Reproduction stable hash: " repro-hash))
              (println (str "Comparison: " (name comp-status)))
              (let [code-info (compute-code-hash)
                    env-hash (compute-env-hash)
                    repro-ev-node-obj (ev-node/build-execution-node
                                       {:execution-id :execution/replay
                                        :status (if passed? :pass :fail)
                                        :inputs {:manifest-path manifest-path
                                                 :benchmark/id benchmark-id
                                                 :task/ref task-ref
                                                 :reproduction-of original-att-ref}
                                        :outputs {:metrics (:metrics evidence)
                                                  :bundle-root bundle-root
                                                  :original-projection claimed-stable-hash
                                                  :reproduction-projection repro-hash
                                                  :comparison-status comp-status}
                                        :failure-details (when-not passed?
                                                           [{:failure-type :reproduction-failure
                                                             :message (str "reproduction " (get-in evidence [:metrics :passed]) "/" (get-in evidence [:metrics :total]) " passed")
                                                             :expected? false
                                                             :class :unexpected}])})
                    repro-ev-node-ref (str "evidence-node:sha256:" (:node-hash repro-ev-node-obj))
                    attestation (att/build-reproduction-attestation
                                 {:task/ref task-ref
                                  :original-attestation-ref original-att-ref
                                  :original-result-projection-hash claimed-stable-hash
                                  :runner/id runner-id
                                  :code-hash (:code-hash code-info)
                                  :code-provenance {:code-source (:code-source code-info)
                                                    :worktree-status (:worktree-status code-info)
                                                    :diff-hash (:diff-hash code-info)}
                                  :env-hash env-hash
                                  :reproduction-execution-node-hash repro-ev-node-ref
                                  :reproduction-result-projection-hash repro-hash
                                  :comparison-policy :stable-projection-v0
                                  :comparison-status comp-status})
                    signed (att/sign-attestation! attestation key-path)
                    _ (att/persist-attestation! signed dir)
                    msg-type (if matched? :AGREEMENT :DISAGREEMENT)
                    msg (mailbox/build-message
                         {:message/type msg-type :subject-task task-ref :sender runner-id
                          :attestation-ref (:attestation/ref signed)
                          :evidence-ref repro-ev-node-ref
                          :body {:comparison-status comp-status
                                 :comparison-policy :stable-projection-v0
                                 :original-projection claimed-stable-hash
                                 :reproduction-projection repro-hash}})
                    signed-msg (mailbox/sign-message! msg key-path)
                    pub-result (mailbox/publish! signed-msg)]
                (println (str "Attestation: " (:attestation/ref signed)))
                (println (str "Mailbox message: " (if (= :published pub-result) "published" "duplicate")))
                {:exit-code (if passed? 0 1) :attestation signed :message signed-msg}))))))))

(defn verify-task
  "Verify the complete evidence chain for a task:
   - task record hash integrity
   - mailbox message hashes and signatures
   - attestation hash and signature integrity
   - subject-reference consistency (attestation subject matches task)
   - evidence graph integrity
   - comparison derivation consistency"
  [opts]
  (let [task-ref (:task opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t (when task-body
                  (task/build-task
                   {:title (:title task-body)
                    :benchmark/id (:benchmark/id task-body)
                    :suite/id (:suite/id task-body)
                    :claim-ids (:claim-ids task-body)
                    :task/type :benchmark-execution}))
              verified-status (mailbox/verified-task-status task-ref dir)
              status (mailbox/task-status task-ref)
              checks (atom [])
              errors (atom [])
              pass! (fn [check] (swap! checks conj {:check check :pass? true}))
              fail! (fn [check reason] (swap! checks conj {:check check :pass? false :reason reason})
                      (swap! errors conj (str check ": " reason)))]
          (println "Evidence Chain Verification")
          (println "═══════════════════════════")
          (println (str "Task: " task-ref))
          (println)
          ;; 1. Task record integrity
          (if task-body
            (do (pass! "task-integrity")
                (println (str "  Title: " (:title task-body)))
                (println (str "  Benchmark: " (or (:benchmark/id task-body) "(not specified)")))
                (println (str "  Suite: " (or (:suite/id task-body) "(not specified)")))
                (println (str "  Claims: " (or (pr-str (:claim-ids task-body)) "(none)"))))
            (do (fail! "task-integrity" "No TASK_ANNOUNCEMENT message found for this ref")
                (when (seq msgs)
                  (println "  (messages exist but no announcement — the announcement")
                  (println "   may have been deleted. Task metadata is unavailable.)"))))
          (println)
          ;; 2. Mailbox messages
          (doseq [m msgs]
            (let [v (mailbox/verify-message m)]
              (if (:hash-valid? v)
                (pass! (str "message-hash-" (name (:message/type m))))
                (fail! (str "message-hash-" (name (:message/type m))) "Hash mismatch"))
              (if (:message/signature m)
                (if (:valid? v)
                  (pass! (str "message-sig-" (name (:message/type m))))
                  (fail! (str "message-sig-" (name (:message/type m))) "Invalid signature")))))
          ;; 3. Attestation integrity
          (doseq [a attestations]
            (let [pred (:attestation/predicate a)]
              (if (att/valid-attestation? a)
                (pass! (str "attestation-hash-" (name pred)))
                (fail! (str "attestation-hash-" (name pred)) "Hash mismatch"))
              (if (contains? att/supported-predicates pred)
                (pass! (str "attestation-predicate-" (name pred)))
                (fail! (str "attestation-predicate-" (name pred)) "Unknown predicate"))
              ;; Subject-reference consistency
              (let [subject-ref (:reference (:subject a))]
                (if (= subject-ref task-ref)
                  (pass! (str "attestation-subject-ref-" (name pred)))
                  (fail! (str "attestation-subject-ref-" (name pred))
                         (str "Subject ref " subject-ref " != task ref " task-ref))))
              ;; Attestation signature verification (only when signed)
              (when (:attestation/signature a)
                (let [key-path (get-in a [:context :key-path])]
                  (if (and key-path (.exists (io/file key-path)))
                    (let [v (att/verify-attestation-signature a key-path)]
                      (if (:valid? v)
                        (pass! (str "attestation-sig-" (name pred)))
                        (fail! (str "attestation-sig-" (name pred)) (first (:errors v)))))
                    (fail! (str "attestation-sig-" (name pred))
                           (if key-path "Public key not found" "No key-path in context"))))))
          ;; 4. Execution evidence verification
            (doseq [a attestations]
              (let [pred (:attestation/predicate a)
                    exec-ref (get-in a [:assertion :execution-node-hash])
                    bundle-root (get-in a [:assertion :bundle-root])
                    result-proj (get-in a [:assertion :result-projection-hash])]
                (if (att/parse-evidence-ref exec-ref)
                  (pass! (str "execution-ref-" (name pred)))
                  (fail! (str "execution-ref-" (name pred)) "Missing or malformed evidence ref"))
                (if (att/valid-sha256? bundle-root)
                  (pass! (str "bundle-root-" (name pred)))
                  (fail! (str "bundle-root-" (name pred)) "Missing or invalid bundle root"))
                (if (att/valid-sha256? result-proj)
                  (pass! (str "result-projection-" (name pred)))
                  (fail! (str "result-projection-" (name pred)) "Missing or invalid result projection"))))
          ;; 4b. Evidence node resolution
            (doseq [a attestations]
              (let [exec-ref (get-in a [:assertion :execution-node-hash])
                    ref-ok? (att/parse-evidence-ref exec-ref)]
                (when ref-ok?
                  (let [node-dir (io/file dir "evidence-nodes")
                        node-file (io/file node-dir (str "node-" (subs ref-ok? 0 12) ".edn"))]
                    (if (.exists node-file)
                      (pass! (str "evidence-node-resolved-" (name (:attestation/predicate a))))
                      (do (pass! (str "evidence-node-ref-valid-" (name (:attestation/predicate a))))
                          (println (str "  (evidence node not persisted locally — hash reference only)"))))))))
          ;; 4c. Code provenance
            (when (seq attestations)
              (println)
              (println "Code Provenance:")
              (doseq [a attestations]
                (let [pred (:attestation/predicate a)
                      code-hash (get-in a [:assertion :code-hash])
                      cp (get-in a [:context :code-provenance])
                      env-hash (get-in a [:assertion :env-hash])]
                  (println (str "  " (name pred) ":"))
                  (println (str "    Code hash: " code-hash))
                  (when cp
                    (println (str "    Source: " (name (:code-source cp))))
                    (println (str "    Worktree: " (name (:worktree-status cp))))
                    (when-let [dh (:diff-hash cp)]
                      (println (str "    Diff hash: " dh))))
                  (println (str "    Env hash: " env-hash)))))
          ;; 5. Comparison derivation and projection verification
            (let [exec-ats (filter #(= :runner/execution-attested (:attestation/predicate %)) attestations)
                  repro-ats (filter #(= :runner/result-reproduced (:attestation/predicate %)) attestations)]
              (doseq [ra repro-ats]
                (let [claimed-status (:comparison-status (:assertion ra))
                      original-ref (:original-attestation-ref (:assertion ra))
                      orig (or (first (filter #(= (:attestation/ref %) original-ref) exec-ats))
                               (att/resolve-attestation dir original-ref))]
                  (if orig
                    (do (pass! "reproduction-original-resolved")
                        (when (and (not (some #(= (:attestation/ref orig) %) (map :attestation/ref exec-ats)))
                                   (att/valid-attestation? orig))
                          (println (str "  Original attestation resolved from disk (no RUNNER_RESULT message)"))))
                    (fail! "reproduction-original-resolved" "Original attestation not found"))
                  (if (contains? #{:matched :semantically-matched :mismatched :inconclusive} claimed-status)
                    (pass! "reproduction-status-known")
                    (fail! "reproduction-status-known" (str "Unknown status: " claimed-status)))
                ;; Comparison policy known
                  (let [comp-policy (get-in ra [:assertion :comparison-policy])]
                    (if (#{:stable-projection-v0 :strict-hash} comp-policy)
                      (pass! "comparison-policy-known")
                      (fail! "comparison-policy-known" (str "Unknown policy: " comp-policy))))
                ;; Both projections present and consistent
                  (let [orig-proj (get-in ra [:assertion :original-result-projection-hash])
                        repro-proj (get-in ra [:assertion :reproduction-result-projection-hash])]
                    (if (and (att/valid-sha256? orig-proj) (att/valid-sha256? repro-proj))
                      (do (pass! "comparison-projections-present")
                          (let [projs-match? (= orig-proj repro-proj)
                                status-matches? (= projs-match?
                                                   (#{:matched :semantically-matched} claimed-status))]
                            (if status-matches?
                              (pass! "comparison-consistent")
                              (fail! "comparison-consistent" "Projection equality does not match claimed status"))))
                      (fail! "comparison-projections-present" "Missing original or reproduction projection")))))
              (when (and (seq exec-ats) (seq repro-ats))
                (pass! "comparison-derived"))
              (when (and (seq exec-ats) (not (seq repro-ats)))
                (pass! "execution-attested-no-reproduction-yet")))
          ;; 6. Evidence graph integrity
            (let [g (graph/build-task-graph-projection
                     {:task t :messages msgs :attestations attestations})
                  {:keys [valid? errors]} (graph/verify-task-graph g)]
              (if valid?
                (pass! "evidence-graph-integrity")
                (fail! "evidence-graph-integrity" (str "Graph validation errors: " errors))))
            (println)
            (println "Summary:")
            (println "──────────────────────────────────────────")
            (doseq [c @checks]
              (println (str "  " (if (:pass? c) "✓" "✗") "  " (:check c)
                            (when (:reason c) (str " — " (:reason c))))))
            (println)
            (println (str "Status (messages):  " (name status)))
            (println (str "Status (verified): " (name verified-status)))
            (println (str "Passed: " (count (filter :pass? @checks)) "/" (count @checks)))
            {:exit-code (if (empty? @errors) 0 1)}))))))

(defn generate-report
  [opts]
  (let [task-ref (:task opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t {:task/hash (task/parse-task-ref task-ref) :task/ref task-ref
                 :task/type :benchmark-execution
                 :title (or (:title task-body) "Community Task")
                 :benchmark/id (:benchmark/id task-body)
                 :suite/id (:suite/id task-body)
                 :claim-ids (or (:claim-ids task-body) [])
                 :registry-snapshot/hash (:registry-snapshot/hash task-body)
                 :acceptance-criteria (:acceptance-criteria task-body)}
              r (report/build-report {:task t :attestations attestations :messages msgs})]
          (report/print-report r)
          (if (seq msgs)
            (do (println "Raw files:")
                (println (str "  Mailbox: " mailbox-dir))
                (println (str "  Attestations: " dir "/community-attestations"))
                (println)
                (println "To export as GraphML for yEd:")
                (println (str "  bb community:graph:export --task " task-ref " > graph.graphml"))
                (println "  Then open graph.graphml in yEd (File > Open)."))
            (println "No messages found. Run 'bb community:task:list' to see registered tasks."))
          {:exit-code 0})))))

(defn export-graph
  "Export a task's evidence graph as GraphML, SVG, and D3 JSON.
   Without --out, prints GraphML to stdout (backward-compatible yEd export).
   With --out <dir>, writes all formats to the given directory."
  [opts]
  (let [task-ref (:task opts)
        out-dir (:out opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref> [--out <dir>]") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t {:task/hash (task/parse-task-ref task-ref) :task/ref task-ref
                 :task/type :benchmark-execution
                 :title (or (:title task-body) "Community Task")
                 :benchmark/id (:benchmark/id task-body)
                 :suite/id (:suite/id task-body)}
              g (graph/build-task-graph-projection {:task t :messages msgs :attestations attestations})]
          (if out-dir
            (let [result (gex/write-graph-artifacts! g {:task/ref task-ref
                                                        :title (:title t)} out-dir)
                  graphml-path (gex/write-graphml g out-dir)]
              (println "Wrote graph artifacts to:" out-dir)
              (println "  SVG:       " (:svg-path result))
              (println "  D3 JSON:  " (:d3-path result))
              (println "  Artifact: " (:artifact-path result))
              (println "  GraphML:  " graphml-path)
              {:exit-code 0})
            (do (println (graph/export-graphml g))
                {:exit-code 0})))))))

(defn clear-mailbox
  [opts]
  (let [dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (binding [mailbox/*mailbox-dir* dir]
      (mailbox/clear-mailbox!)
      (println (str "Mailbox cleared: " dir))
      {:exit-code 0})))

(defn- subcommand? [args]
  (contains? #{"task:list" "task:show" "task:register" "run" "reproduce" "verify" "report" "graph:export" "mailbox:clear"} (first args)))

(defn- print-help []
  (println "PRF Community CLI — External researcher participation")
  (println)
  (println "Usage: clojure -M:with-sew -m resolver-sim.community.cli <command> [options]")
  (println)
  (println "Commands:")
  (println "  task:list                              List community tasks")
  (println "  task:show --task <ref>                 Show task details")
  (println "  task:register --title <t> [--benchmark-id <id> --suite-id <id>]  Register a new task")
  (println "  run --task <ref> -r <id> -k <key>      Execute a task and publish result")
  (println "  reproduce --task <ref> -o <ref> -r <id> -k <key>  Reproduce a result")
  (println "  verify --task <ref>                    Verify evidence chain")
  (println "  report --task <ref>                    Generate evidence report")
  (println "  graph:export --task <ref> [--out <dir>] Export evidence graph as GraphML (stdout) or SVG+JSON+GraphML (dir)")
  (println "  mailbox:clear                           Clear all mailbox messages")
  (println)
  (println "Options:")
  (println "  -t, --task REF           Task reference")
  (println "  -r, --runner ID          Runner identity (pseudonymous)")
  (println "  -k, --key PATH           Path to Ed25519 private key")
  (println "  -o, --original-attestation REF  Original attestation ref (for reproduce)")
  (println "  -d, --dir DIR            Artifact directory")
  (println "  -m, --mailbox-dir DIR    Mailbox directory")
  (println "  -h, --help               Show this help")
  {:exit-code 0})

(defn -main [& args]
  (if (subcommand? args)
    (let [subcmd (first args)
          sub-args (rest args)
          {:keys [options]} (parse-opts sub-args cli-options)]
      (case subcmd
        "task:list" (System/exit (:exit-code (list-tasks options)))
        "task:show" (System/exit (:exit-code (show-task options)))
        "task:register" (System/exit (:exit-code (register-task options)))
        "run" (System/exit (:exit-code (run-task options)))
        "reproduce" (System/exit (:exit-code (reproduce-task options)))
        "verify" (System/exit (:exit-code (verify-task options)))
        "report" (System/exit (:exit-code (generate-report options)))
        "graph:export" (System/exit (:exit-code (export-graph options)))
        "mailbox:clear" (System/exit (:exit-code (clear-mailbox options)))
        (do (println "Unknown command:" subcmd) (System/exit 1))))
    (let [{:keys [options errors]} (parse-opts args cli-options)]
      (cond
        errors (do (run! println errors) (System/exit 1))
        (:help options) (System/exit (:exit-code (print-help)))
        :else (do (println "Usage: community <command> [options]")
                  (println "Run 'community --help' for available commands.")
                  (System/exit 1))))))
