(ns resolver-sim.research.anvil-executor-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.coprocessor.source :as coprocessor]
            [resolver-sim.research.anvil-executor :as anvil]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.execution-environment :as environment]
            [resolver-sim.research.observed-assurance :as observed]
            [resolver-sim.research.observed-execution-work :as observed-work]
            [resolver-sim.research.observed-run-proof-statement :as statement]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.remote-execution-test :as remote-fixture]
            [resolver-sim.research.runner-participation :as participation]))

(defn- guest-projection [input]
  (let [coprocessor-root (coprocessor/root)]
    (let [process (.start (doto (ProcessBuilder.
                               ^java.util.List ["cargo" "run" "--locked" "--quiet" "--manifest-path"
                                                (str coprocessor-root "/observed-run-sp1-program/Cargo.toml")
                                                "--bin" "observed-run-witness-check"])
                          (.directory (java.io.File. coprocessor-root))))]
    (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
      (.write writer (json/write-str input)))
    (let [stdout (slurp (.getInputStream process))
          stderr (slurp (.getErrorStream process))
          status (.waitFor process)]
        {:status status :stdout (str/trim stdout) :stderr stderr}))))

(defn root [n] (str "sha256:" (format "%064x" n)))
(def from "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
(def to "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")

(defn version []
  (second (re-find #"Version: ([^\s]+)" (slurp (.getInputStream (.start (ProcessBuilder. ^java.util.List ["anvil" "--version"])))))))

(defn env
  ([] (env []))
  ([code-identities]
   (environment/build-anvil-environment
    {:tool-version (version) :chain-id 31337
     :state-source {:state-source/kind :genesis :state-source/root (root 1)}
     :code-identities code-identities :initial-state-root (root 2)
     :config {:genesis-timestamp 0 :fixed-mnemonic true}})))

(defn deps [visibility]
  (let [entry {:dependency/id :dep/anvil :dependency/class :anvil-foundry
               :dependency/visibility visibility
               :dependency/acquisition-mode (case visibility
                                              :public :public-by-root
                                              :runner-readable-private :authenticated-private
                                              :attestation-gated-sealed)
               :dependency/artifact-root (root 3) :dependency/provenance-roots []
               :dependency/metadata {}}
        manifest (dependency/build-manifest [entry])
        policy (dependency/build-policy
                {:required-classes [:anvil-foundry] :allowed-visibility [visibility]
                 :allowed-acquisition-modes [(:dependency/acquisition-mode entry)]
                 :sealed-environment-root (when (= visibility :sealed-to-execution-environment) (root 4))})
        resolved (dependency/resolve-dependencies
                  manifest policy [{:dependency/id :dep/anvil :dependency/artifact-root (root 3)
                                    :resolution/acquisition-mode (:dependency/acquisition-mode entry)
                                    :resolution/status :resolved :resolution/evidence-root (root 5)
                                    :resolution/environment-root (when (= visibility :sealed-to-execution-environment) (root 4))}])]
    {:manifest manifest :policy policy :resolved resolved}))

(defn runner []
  (participation/build-participation
   {:runner-reference (root 12) :disclosure-mode :pseudonymous :site-id :site/local
    :opted-in? true :available? true :capabilities [:capability/anvil]
    :job-classes [:job/research-calculation] :environment-kinds [:anvil]
    :qualification-references []}))

(defn context
  ([] (context {}))
  ([overrides]
   (let [{:keys [manifest resolved]} (deps :public)
         execution-environment (env)
         action {:from from :to to :value-wei 1 :data "0x"}]
     (merge {:job {:research-execution-job/root (root 10)}
             :assignment {:research-run-assignment/root (root 11)
                          :runner/site-id :site/local
                          :research-execution-environment/root
                          (:research-execution-environment/root execution-environment)}
             :runner-participation (runner)
             :execution-environment execution-environment
             :dependency-manifest manifest :resolved-dependencies resolved
             :implementation-root (root 13) :action action
             :expected-action-root (anvil/action-root action)} overrides))))

(defn observed-run-proof-input
  "Build a fresh real-Anvil fixture suitable for the opt-in SP1 prove/verify
   command. Kept as fixture construction, not an assertion that proofing ran."
  []
  (let [base (context)
        execution-policy (remote-fixture/execution-policy {:required-runs 1
                                                           :required-matching-runs 1
                                                           :qualification-references []})
        profile (remote-fixture/local-profile)
        job (remote-fixture/job (:execution-environment base) execution-policy profile)
        participant (remote-fixture/runner \a :pseudonymous :site/a :qualifications [])
        assignment (remote/steal-next job (:execution-environment base)
                                      execution-policy participant [])
        c (assoc base :job job :assignment assignment :runner-participation participant)
        dependencies (deps :public)
        implementation (assurance/build-implementation
                        {:algorithm-id :research/anvil-transfer
                         :artifact-root (root 60) :language-runtime-family :clojure
                         :resolved-dependency-set-root
                         (:resolved-research-dependency-set/root (:resolved dependencies))
                         :semantic-version "1"})
        c (assoc c :implementation-root (:research-implementation-identity/root implementation))
        result (anvil/execute! c)
        policy (assurance/build-assurance-policy
                {:execution-policy-root (:research-execution-policy/root execution-policy)
                 :dependency-policy-root (:research-dependency-policy/root (:policy dependencies))
                 :required-runs 1 :minimum-distinct-runners 1 :minimum-distinct-sites 0
                 :minimum-distinct-implementations 1 :additional-validation-roots []
                 :proof-generation-required? false :verifier-registry-root nil})
        assured-job (assurance/build-assured-job
                     {:job-root (:research-execution-job/root job)
                      :dependency-manifest-root (:research-dependency-manifest/root (:manifest dependencies))
                      :dependency-policy-root (:research-dependency-policy/root (:policy dependencies))
                      :resolved-dependency-set-root (:resolved-research-dependency-set/root (:resolved dependencies))
                      :implementation-roots [(:research-implementation-identity/root implementation)]
                      :assurance-policy-root (:research-execution-assurance-policy/root policy)})
        adapted (observed/build-observed-run-assurance
                 {:assured-job assured-job :assignment assignment
                  :runner-participation participant :resolved-dependencies (:resolved dependencies)
                  :implementation implementation :receipt (:receipt result)
                  :validation (:fixed-validation result)})]
    {:observed-execution-work
     (observed-work/build-work
      {:job job :assignment assignment :runner-participation participant
       :execution-environment (:execution-environment c)
       :dependency-manifest (:manifest dependencies)
       :resolved-dependencies (:resolved dependencies)
       :implementation implementation :assured-job assured-job
       :action (:action c) :expected-action-root (:expected-action-root c)
       :expected-prestate-root (get-in result [:receipt :environment-before/root])
       :expected-poststate-root (get-in result [:receipt :environment-after/root])})
     :observed-run-evidence (:observed-run-evidence adapted)
     :run-assurance-evidence (:run-assurance-evidence adapted)
     :proof-host-config
     {:prover-participation (remote-fixture/runner \d :anonymous :site/d :qualifications [])
      :verifier-reference (root 70)
      :verifier-registry-extension-map {}
      :verification-cutpoint 1}}))

(deftest fresh-real-anvil-execution-is-observed-and-replayable
  (let [first-run (anvil/execute! (context))
        second-run (anvil/execute! (context {:expected-prestate-root (get-in first-run [:receipt :environment-before/root])
                                             :expected-poststate-root (get-in first-run [:receipt :environment-after/root])}))]
    (is (= :complete (:execution/status first-run)))
    (is (= :rpc-action-completed (:realized-reachability first-run)))
    (is (= :observed-local-execution (get-in first-run [:receipt :execution/assurance])))
    (is (= :passed (get-in first-run [:fixed-validation :validation/status])))
    (is (= (get-in first-run [:receipt :environment-before/root])
           (get-in second-run [:receipt :environment-before/root])))
    (is (= (get-in first-run [:receipt :executed-action/root])
           (get-in second-run [:receipt :executed-action/root])))
    (is (= (get-in first-run [:receipt :environment-after/root])
           (get-in second-run [:receipt :environment-after/root])))
    (is (= (get-in first-run [:receipt :semantic-result/root])
           (get-in second-run [:receipt :semantic-result/root])))
    (is (not= (:runtime/port first-run) (:runtime/port second-run)))))

(deftest substitutions-and-observed-contract-mismatch-are-rejected
  (let [baseline (anvil/execute! (context))
        bad-action (assoc (context) :expected-action-root (root 44))
        wrong-code-env (env [{:deployment/address from :deployment/code-root (root 45)}])
        wrong-chain (assoc (context) :runtime-chain-id 31338)
        bad-pre (assoc (context) :expected-prestate-root (root 46))
        bad-post (assoc (context) :expected-poststate-root (root 47))
        bad-dependency (assoc (context) :resolved-dependencies
                              (assoc (:resolved-dependencies (context))
                                     :resolved-research-dependency-set/root (root 48)))]
    (is (= :action-mismatch (:cause (ex-data (try (anvil/execute! bad-action) nil
                                                  (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :contract-code-mismatch
           (:cause (ex-data (try (anvil/execute! (context {:execution-environment wrong-code-env})) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :chain-id-mismatch
           (:cause (ex-data (try (anvil/execute! wrong-chain) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :execution-prestate-mismatch
           (:cause (ex-data (try (anvil/execute! bad-pre) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :execution-poststate-mismatch
           (:cause (ex-data (try (anvil/execute! bad-post) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :dependency-mismatch
           (:cause (ex-data (try (anvil/execute! bad-dependency) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))
    (is (:research-anvil-execution-receipt/root (:receipt baseline)))))

(deftest cancellation-and-sealed-dependency-boundaries
  (let [before (anvil/execute! (context {:cancellation-stage :before-launch}))
        after-reachability (anvil/execute! (context {:cancellation-stage :after-reachability}))
        sealed (deps :sealed-to-execution-environment)]
    (is (= :cancelled (:execution/status before)))
    (is (false? (:process/launched? before)))
    (is (= :cancelled (:execution/status after-reachability)))
    (is (= :rpc-reachable (:realized-reachability after-reachability)))
    (is (= :sealed-dependency-unsupported
           (:cause (ex-data (try (anvil/execute! (context {:dependency-manifest (:manifest sealed)
                                                           :resolved-dependencies (:resolved sealed)})) nil
                                 (catch clojure.lang.ExceptionInfo e e))))))))

(deftest observed-run-adapts-into-existing-v1-assurance-and-forensic-dag
  (let [base (context)
        execution-policy (remote-fixture/execution-policy {:required-runs 2
                                                           :required-matching-runs 1
                                                           :qualification-references []})
        profile (remote-fixture/local-profile)
        job (remote-fixture/job (:execution-environment base) execution-policy profile)
        participant (remote-fixture/runner \a :pseudonymous :site/a :qualifications [])
        assignment (remote/steal-next job (:execution-environment base)
                                      execution-policy participant [])
        c (assoc base :job job :assignment assignment :runner-participation participant)
        dependencies (deps :public)
        implementation (assurance/build-implementation
                        {:algorithm-id :research/anvil-transfer
                         :artifact-root (root 60) :language-runtime-family :clojure
                         :resolved-dependency-set-root
                         (:resolved-research-dependency-set/root (:resolved dependencies))
                         :semantic-version "1"})
        c (assoc c :implementation-root (:research-implementation-identity/root implementation))
        result (anvil/execute! c)
        policy (assurance/build-assurance-policy
                {:execution-policy-root (root 61)
                 :dependency-policy-root (:research-dependency-policy/root (:policy dependencies))
                 :required-runs 1 :minimum-distinct-runners 1 :minimum-distinct-sites 0
                 :minimum-distinct-implementations 1 :additional-validation-roots []
                 :proof-generation-required? false :verifier-registry-root nil})
        assured-job (assurance/build-assured-job
                     {:job-root (get-in c [:job :research-execution-job/root])
                      :dependency-manifest-root (:research-dependency-manifest/root (:manifest dependencies))
                      :dependency-policy-root (:research-dependency-policy/root (:policy dependencies))
                      :resolved-dependency-set-root (:resolved-research-dependency-set/root (:resolved dependencies))
                      :implementation-roots [(:research-implementation-identity/root implementation)]
                      :assurance-policy-root (:research-execution-assurance-policy/root policy)})
        validation (:fixed-validation result)
        adapted (observed/build-observed-run-assurance
                 {:assured-job assured-job :assignment (:assignment c)
                  :runner-participation (:runner-participation c)
                  :resolved-dependencies (:resolved dependencies) :implementation implementation
                  :receipt (:receipt result) :validation validation})]
    (is (assurance/valid-run-assurance-evidence? (:run-assurance-evidence adapted)))
    (is (= (get-in result [:receipt :semantic-result/root])
           (get-in adapted [:run-assurance-evidence :semantic-result/root])))
    (is (= :observed-local-execution
           (get-in adapted [:observed-run-evidence :execution/assurance])))
    (is (= 3 (count (:forensic-nodes adapted))))
    (is (string? (:forensic-evidence-root adapted)))
    (is (observed/valid-observed-run-evidence? (:observed-run-evidence adapted)))
    (let [runner-b (remote-fixture/runner \b :pseudonymous :site/a :qualifications [])
          assignment-b (remote/steal-next job (:execution-environment c) execution-policy
                                          runner-b [(:assignment c)])
          context-b (assoc c :assignment assignment-b :runner-participation runner-b)
          result-b (anvil/execute! context-b)
          adapted-b (observed/build-observed-run-assurance
                     {:assured-job assured-job :assignment assignment-b
                      :runner-participation runner-b :resolved-dependencies (:resolved dependencies)
                      :implementation implementation :receipt (:receipt result-b)
                      :validation (:fixed-validation result-b)})]
      (is (= (:semantic-result/root (:run-assurance-evidence adapted))
             (:semantic-result/root (:run-assurance-evidence adapted-b))))
      (is (not= (:research-run-assurance-evidence/root (:run-assurance-evidence adapted))
                (:research-run-assurance-evidence/root (:run-assurance-evidence adapted-b))))
      (is (not= (:research-observed-run-evidence/root (:observed-run-evidence adapted))
                (:research-observed-run-evidence/root (:observed-run-evidence adapted-b)))))
    (let [projection (statement/derive-observed-run-proof-statement-v1
                      (:observed-run-evidence adapted) (:run-assurance-evidence adapted))]
      (is (= statement/public-value-order (:proof/public-value-order projection)))
      (is (= 12 (count (:proof/public-value-roots projection))))
      (is (= (:semantic-result/root (:run-assurance-evidence adapted))
             (:semantic-result/root projection)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (statement/derive-observed-run-proof-statement-v1
                    (assoc (:observed-run-evidence adapted) :semantic-result/root (root 98))
                    (:run-assurance-evidence adapted)))))
    (let [observed-run (:observed-run-evidence adapted)
          run-assurance (:run-assurance-evidence adapted)
          input (statement/sp1-input observed-run run-assurance)
          expected (apply str
                          (map #(subs % 7)
                               (:proof/public-value-roots
                                (statement/derive-observed-run-proof-statement-v1
                                 observed-run run-assurance))))
          guest (guest-projection input)]
      (is (= 0 (:status guest)) (:stderr guest))
      (is (= expected (:stdout guest)))
      (is (= 384 (/ (count (:stdout guest)) 2)))
      (let [support (:proof/source-artifacts observed-run)
            observed-mutators
            [(fn [x] (assoc x :research-execution-job/root (root 101)))
             (fn [x] (assoc x :research-run-assignment/root (root 115)))
             (fn [x] (assoc x :resolved-research-dependency-set/root (root 102)))
             (fn [x] (assoc x :research-implementation-identity/root (root 103)))
             (fn [x] (assoc x :research-execution-environment/root (root 104)))
             (fn [x] (assoc x :research-execution-environment-observation/root (root 105)))
             (fn [x] (assoc x :environment-before/root (root 106)))
             (fn [x] (assoc x :executed-action/root (root 107)))
             (fn [x] (assoc x :environment-after/root (root 108)))
             (fn [x] (assoc x :semantic-result/root (root 109)))
             (fn [x] (assoc x :research-observed-fixed-validation-receipt/root (root 110)))
             (fn [x] (assoc x :research-observed-run-evidence/root (root 111)))
             (fn [x] (assoc x :proof/source-artifacts
                            (assoc support :resolved-dependencies
                                   (assoc (:resolved-dependencies support)
                                          :resolved-research-dependency-set/root (root 112)))))]
            assurance-mutators
            [(fn [x] (assoc x :research-run-assurance-evidence/root (root 113)))
             (fn [x] (assoc x :semantic-result/root (root 114)))
             (fn [x] (assoc x :research-execution-job-assurance/root (root 116)))
             (fn [x] (assoc x :research-run-evidence/root (root 117)))]]
        (doseq [mutate observed-mutators]
          (let [bad (mutate observed-run)
                result (guest-projection
                        {"schema" statement/schema "observed_run" ((deref #'statement/wire-value) bad)
                         "run_assurance" ((deref #'statement/wire-value) run-assurance)})]
            (is (not= 0 (:status result)))))
        (doseq [mutate assurance-mutators]
          (let [bad (mutate run-assurance)
                result (guest-projection
                        {"schema" statement/schema "observed_run" ((deref #'statement/wire-value) observed-run)
                         "run_assurance" ((deref #'statement/wire-value) bad)})]
            (is (not= 0 (:status result)))))
      ;; A syntactically valid, self-rooted alternate environment is spliced into
      ;; the execution receipt while the observed-run claim remains bound to the
      ;; original one.  Re-root both top-level artifacts so rejection depends on
      ;; guest lineage checks, not merely stale outer hashes.
        (let [alternate-env (env [{:deployment/address to :deployment/code-root (root 88)}])
              support (:proof/source-artifacts observed-run)
              rec (:anvil-receipt support)
              rec-body (-> rec
                           (assoc :research-execution-environment/body alternate-env)
                           (dissoc :research-anvil-execution-receipt/root))
              rec* (assoc rec-body :research-anvil-execution-receipt/root
                          (anvil/receipt-root rec-body))
              fixed (:fixed-validation support)
              fixed-body (-> fixed
                             (assoc :research-anvil-execution-receipt/root
                                    (:research-anvil-execution-receipt/root rec*))
                             (dissoc :research-observed-fixed-validation-receipt/root))
              fixed* (assoc fixed-body :research-observed-fixed-validation-receipt/root
                            (anvil/validation-root fixed-body))
              support* (assoc support :execution-environment alternate-env
                              :anvil-receipt rec* :fixed-validation fixed*)
              observed-body (-> observed-run
                                (assoc :proof/source-artifacts support*
                                       :research-observed-fixed-validation-receipt/root
                                       (:research-observed-fixed-validation-receipt/root fixed*))
                                (dissoc :research-observed-run-evidence/root))
              observed* (assoc observed-body :research-observed-run-evidence/root
                               (observed/observed-run-root observed-body))
              assurance-body (-> run-assurance
                                 (assoc :research-run-evidence/root
                                        (:research-observed-run-evidence/root observed*)
                                        :research-fixed-validation-receipt/root
                                        (:research-observed-fixed-validation-receipt/root fixed*))
                                 (dissoc :research-run-assurance-evidence/root))
              assurance* (assoc assurance-body :research-run-assurance-evidence/root
                                (assurance/run-assurance-root assurance-body))
              wire (deref #'statement/wire-value)
              malicious-input {"schema" statement/schema
                               "observed_run" (wire observed*)
                               "run_assurance" (wire assurance*)}
              rejected (guest-projection malicious-input)]
          (is (not= 0 (:status rejected))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observed/build-observed-run-assurance
                  {:assured-job assured-job :assignment (assoc (:assignment c)
                                                               :research-run-assignment/root (root 99))
                   :runner-participation (:runner-participation c)
                   :resolved-dependencies (:resolved dependencies) :implementation implementation
                   :receipt (:receipt result) :validation validation})))))
