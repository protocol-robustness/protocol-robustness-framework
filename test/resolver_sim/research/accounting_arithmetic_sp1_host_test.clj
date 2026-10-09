(ns resolver-sim.research.accounting-arithmetic-sp1-host-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.research.accounting-arithmetic-sp1-host :as host]
            [resolver-sim.research.accounting-proof-statement :as statement]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.runner-transport :as transport]
            [resolver-sim.research.native-accounting-work-test :as fixture]))

(deftest proof-witness-is-derived-only-from-validated-work-and-execution
  (let [left-work (:work (fixture/work-fixture 100 301))
        right-work (:work (fixture/work-fixture 100 302))
        left-execution (native/execute-work! left-work)
        right-execution (native/execute-work! right-work)
        left (host/derive-authoritative-proof-input left-work left-execution)
        right (host/derive-authoritative-proof-input right-work right-execution)]
    (is (= (:witness left) (:witness right)))
    (is (= (:statement left) (:statement right)))
    (is (= (:canonical-accounting-partial/root (:statement left))
           (:canonical-accounting-partial/root (:statement right))))
    (is (not= (native/execution-root left-execution)
              (native/execution-root right-execution)))
    (testing "a run from another native work item cannot be substituted"
      (is (thrown? clojure.lang.ExceptionInfo
                   (host/derive-authoritative-proof-input left-work right-execution))))
    (testing "the host API has no slot for caller-supplied roots"
      (is (thrown? clojure.lang.ArityException
                   (apply host/derive-authoritative-proof-input
                          [left-work left-execution
                           {:canonical-accounting-partial/root "substitute"}]))))))

(deftest structural-lineage-is-not-arithmetic-validation
  (let [{:keys [work]} (fixture/work-fixture 100 301)
        good (native/execute-work! work)
        false-values (assoc (:accounting/values (:accounting-partial good))
                            :accounting/bond 999)
        runtime (select-keys (:work/execution-environment work)
                             [:runtime/clojure-version :runtime/jvm-version
                              :environment/arithmetic])
        forged (#'native/derive-evidence work false-values runtime)]
    (is (= forged (native/validate-execution-lineage work forged)))
    (is (thrown? clojure.lang.ExceptionInfo (native/validate-execution work forged)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (statement/proof-input
                  (:accounting/request work) (accounting/build-reduction-contract)
                  (:accounting/partition-plan work) (:accounting-partial forged))))
    (is (= "research-accounting-arithmetic-statement.v1"
           (get (statement/proof-input-for-verification
                 (:accounting/request work)
                 (accounting/build-reduction-contract)
                 (:accounting/partition-plan work) (:accounting-partial forged))
                "schema")))))

(deftest proof-verifier-rejects-cross-assignment-execution-before-reading-proof
  (let [left-work (:work (fixture/work-fixture 100 301))
        right-work (:work (fixture/work-fixture 100 302))
        right-execution (native/execute-work! right-work)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (host/verify-existing-proof!
                  {:work left-work :execution right-execution
                   :proof-file "/does/not/exist/proof.bin"
                   :metadata-file "/does/not/exist/metadata.json"})))))

(declare remove-tree!)

(deftest external-sp1-host-timeout-is-explicit-and-bounded
  (let [directory (java.nio.file.Files/createTempDirectory
                   "prf-sp1-timeout-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        executable (.resolve directory "slow-host")
        bytes (.getBytes "#!/bin/sh\nsleep 5\n" java.nio.charset.StandardCharsets/UTF_8)
        compiled-host-var (var-get #'host/compiled-host-binary)
        previous @compiled-host-var]
    (try
      (java.nio.file.Files/write executable bytes
                                (make-array java.nio.file.OpenOption 0))
      (.setExecutable (.toFile executable) true)
      (reset! compiled-host-var (str executable))
      (binding [host/*host-command-timeout-ms* 50]
        (let [failure (try
                        (#'host/invoke-host! :verify "input" "proof" "metadata" "verification")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= :host-command-timeout (:cause (ex-data failure))))
          (is (= 50 (:timeout-ms (ex-data failure))))))
      (finally
        (reset! compiled-host-var previous)
        (remove-tree! directory)))))

(defn- remove-tree! [path]
  (when (java.nio.file.Files/exists path (make-array java.nio.file.LinkOption 0))
    (with-open [paths (java.nio.file.Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [entry (reverse (into [] (iterator-seq (.iterator paths))))]
        (java.nio.file.Files/deleteIfExists entry)))))

(defn- proof-participant [runner-reference site capability]
  (participation/build-participation
   {:runner-reference runner-reference :disclosure-mode :pseudonymous :site-id site
    :opted-in? true :available? true :capabilities [capability]
    :job-classes [:job/research-calculation] :environment-kinds [:clojure-jvm]
    :qualification-references []}))

(defn- journaled-runner [directory label work execution participant coordinator-root]
  (let [work-root (:research-native-accounting-work/root work)
        state-path (.resolve directory (str label "-runner-state.edn"))]
    {:runner {:transport/state-path (str state-path)
              :transport/journal (atom {:sequence 0 :format :new})
              :transport/coordinator-root coordinator-root
              :runner-participation participant
              :transport/state
              (atom {:jobs {} :assignments {} :work-items {work-root work}
                     :work-states {} :cancellations {}
                     :completed-work
                     {work-root {:job (:work/job work) :assignment (:work/assignment work)
                                 :native-accounting-execution execution}}
                     :forensic-evidence-nodes {}})}
     :work-root work-root}))

(defn- altered-public-values [metadata]
  (let [value (get metadata "public_values_hex")
        hex (subs value 2)
        replacement (if (= \0 (first hex)) \1 \0)]
    (assoc metadata "public_values_hex" (str "0x" replacement (subs hex 1)))))

(deftest ^:integration real-sp1-proof-delivery-and-substitution-qualification
  ;; CPU proving is intentionally opt-in and requires the pinned SP1 host tool.
  (when (= "1" (System/getenv "PRF_RUN_REAL_SP1_DELIVERY_E2E"))
    (let [{work-a :work participant-a :participant} (fixture/work-fixture 100 301)
          execution-a (native/execute-work! work-a)
          {work-b :work} (fixture/work-fixture 100 302)
          execution-b (native/execute-work! work-b)
          directory (java.nio.file.Files/createTempDirectory
                     "prf-real-accounting-proof-delivery-"
                     (make-array java.nio.file.attribute.FileAttribute 0))
          proof-directory (.resolve directory "proof")
          coordinator-root (str "sha256:" (apply str (repeat 64 "f")))
          verifier (proof-participant (str "sha256:" (apply str (repeat 64 "a")))
                                      :site/verifier :capability/proof-verification)
          proof-result (host/prove-and-verify!
                        {:work work-a :execution execution-a
                         :proof-host-config
                         {:prover-participation participant-a
                          :verifier-reference (str "sha256:" (apply str (repeat 64 "b")))
                          :verifier-registry-extension-map {} :verification-cutpoint 11}
                         :output-dir (str proof-directory)})
          proof-file (:proof-file proof-result)
          metadata (:metadata proof-result)
          generation (:generation-receipt proof-result)
          assignment-a (:work/assignment work-a)
          assignment-b (:work/assignment work-b)
          auth-secret "real-sp1-proof-delivery-qualification-secret"
          dispatches [{:job (:work/job work-a) :assignment assignment-a
                       :execution-work work-a :execute? true}
                      {:job (:work/job work-b) :assignment assignment-b
                       :execution-work work-b :execute? true}]
          coordinator
          (transport/start-coordinator!
           {:coordinator-root coordinator-root :assignments [assignment-a assignment-b]
            :auth-secret auth-secret :initial-dispatches dispatches
            :accounting-proof-verification
            {:verifier-participation verifier :verifier-reference (:runner/reference verifier)
             :verifier-registry-extension-map {} :verification-cutpoint 12}})
          ;; Real CPU verification takes longer than the ordinary result-delivery
          ;; timeout. The timeout is operational configuration, not proof input.
          endpoint (transport/coordinator-endpoint coordinator auth-secret 180000)
          runner-a (journaled-runner directory "good" work-a execution-a participant-a
                                     coordinator-root)
          send-proof! (fn [{:keys [runner work-root]} proof-path proof-metadata receipt]
                        (transport/deliver-native-accounting-proof!
                         {:runner runner :endpoint endpoint :work-root work-root
                          :proof-file proof-path :metadata proof-metadata
                          :generation-receipt receipt :logical-cutpoint 13}))]
      (try
        (let [delivered (send-proof! runner-a proof-file metadata generation)
              coordinator-state (transport/coordinator-state coordinator)
              admission (first (vals (:accounting-proof-admissions coordinator-state)))
              bad-proof (.resolve directory "wrong-proof.bin")
              proof-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file proof-file)))
              altered-bytes (aclone proof-bytes)
              _ (aset-byte altered-bytes 0
                           (unchecked-byte (bit-xor 1 (aget altered-bytes 0))))
              _ (java.nio.file.Files/write bad-proof altered-bytes
                                           (make-array java.nio.file.OpenOption 0))
              final-state (transport/coordinator-state coordinator)
              plan-root (get-in work-a [:accounting/partition-plan
                                        :accounting-partition-plan/root])
              verify-substitution
              (fn [work execution proof-path proof-metadata receipt]
                (let [path (.resolve directory (str (java.util.UUID/randomUUID) ".json"))]
                  (try
                    (java.nio.file.Files/write
                     path (.getBytes (json/write-str proof-metadata)
                                     java.nio.charset.StandardCharsets/UTF_8)
                     (make-array java.nio.file.OpenOption 0))
                    (host/verify-existing-proof!
                     {:work work :execution execution :proof-file proof-path
                      :metadata-file (str path) :generation-receipt receipt
                      :verifier-participation verifier
                      :verifier-reference (:runner/reference verifier)
                      :verifier-registry-extension-map {}
                      :verification-cutpoint 14})
                    (finally (java.nio.file.Files/deleteIfExists path)))))
              substitutions
              [(try (verify-substitution work-a execution-a (str bad-proof) metadata generation)
                    :unexpected-success (catch clojure.lang.ExceptionInfo _ :rejected))
               (try (verify-substitution work-a execution-a proof-file
                                         (altered-public-values metadata) generation)
                    :unexpected-success (catch clojure.lang.ExceptionInfo _ :rejected))
               (try (verify-substitution work-a execution-a proof-file
                                         (assoc metadata "proof_program" "substituted-program.v1")
                                         generation)
                    :unexpected-success (catch clojure.lang.ExceptionInfo _ :rejected))
               (try (verify-substitution work-a execution-a proof-file
                                         (assoc metadata "proof_program_vkey"
                                                (str "0x" (apply str (repeat 64 "0"))))
                                         generation)
                    :unexpected-success (catch clojure.lang.ExceptionInfo _ :rejected))
               (try (verify-substitution work-b execution-b proof-file metadata generation)
                    :unexpected-success (catch clojure.lang.ExceptionInfo _ :rejected))]]
          (is (= :accepted (:delivery/status delivered)))
          (is (= :proof-verified (:accounting/arithmetic-assurance admission)))
          (is (= 1 (count (:accounting-proof-admissions coordinator-state))))
          (is (= 1 (count (get-in coordinator-state [:accounting-partials plan-root]))))
          (is (every? #{:rejected} substitutions)
              "wrong proof, values, program, VKey, and run context reject before proof verification")
          (is (= 1 (count (:accounting-proof-admissions final-state))))
          (is (= (:proof/artifact-root generation) (:proof-artifact/root admission))))
        (finally
          (transport/stop-coordinator! coordinator)
          (remove-tree! directory))))))
