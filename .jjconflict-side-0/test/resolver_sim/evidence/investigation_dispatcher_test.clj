(ns resolver-sim.evidence.investigation-dispatcher-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.deployment-bound :as deployment]
            [resolver-sim.evidence.deployment-bound-test :as fixture]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.evidence.investigation-dispatcher :as sut]))

;; Unit fixtures are synthetic adversarial records. The separate CLI exercise
;; uses the genuine retained replay without replacing any evidence.
(defn- write-json! [root path value]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file (json/write-str value))
    (deployment/file-sha256 file)))

(defn- write-bundle! [root input]
  (let [manifest (update (:manifest input) "manifestDigest"
                         (constantly (deployment/recompute-gate-e-manifest-digest (:manifest input))))
        manifest-hash (write-json! root "artifacts/manifest.json" manifest)
        witness-hash (write-json! root "artifacts/witness.json" (:witness input))
        proof (-> (:fixture input)
                  (assoc-in ["source" "witness_sha256"] (str "0x" witness-hash))
                  (assoc-in ["gate_e" "manifest_digest"] (get manifest "manifestDigest")))
        proof-hash (write-json! root "artifacts/fixture.json" proof)
        provenance (assoc (:provenance input)
                          "witness_sha256" (str "sha256:" witness-hash)
                          "manifest_digest" (get manifest "manifestDigest"))
        report (assoc (:report input) "byte_identical_artifacts"
                      {"manifest" {"sha256" manifest-hash}
                       "witness" {"sha256" witness-hash}
                       "proof_fixture" {"sha256" proof-hash}})]
    (write-json! root "artifacts/witness.provenance.json" provenance)
    (write-json! root "replay-report.json" report)
    (spit (io/file root "artifacts/final-state.log") (:final-state input))
    (spit (io/file root "artifacts/fork-test.log") (:fork-test-log input))
    (doseq [[stage script] [[:deploy "DeployGateEEntitlementOptionA"]
                            [:run "RunGateEEntitlementLocal"]
                            [:activate "ActivateGateEEntitlementLocal"]
                            [:withdrawal "WithdrawGateEEntitlementLocal"]]]
      (write-json! root (str "broadcast/" script ".s.sol/31337/run-latest.json")
                   (get-in input [:broadcasts stage])))))

(defn- with-bundle [input f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "prf-investigation-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (write-bundle! root input)
      (f root)
      (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(deftest raw-inputs-produce-bound-tasks-without-authority
  (with-bundle (fixture/fixture-input)
    (fn [root]
      (let [plan (sut/plan-bundle root)
            tasks (:tasks plan)
            final-task (last tasks)]
        (is (= :planning-only (:mode plan)))
        (is (= :incomplete (:status plan)))
        (is (true? (get-in plan [:reconstructed-validation :valid?])))
        (is (empty? (:routing-blockers plan)))
        (is (some #(= "historical allocator storage and consumed-claim state" (get-in % [:issue :artifact])) tasks))
        (is (= #{:request-evidence :request-independent-validation :reconstruct-forensic-view}
               (set (map :action tasks))))
        (is (= (mapv :task-id (butlast tasks)) (:depends-on final-task)))
        (is (= :blocked (:status final-task)))
        (is (every? #(= (:source plan) (:source %)) tasks))
        (is (every? :gap-root (butlast tasks)))
        (is (every? forensic/forensic-roles (map :intended-role tasks)))
        (is (every? empty? (map :result-artifact-references tasks)))
        (is (= 0 (get-in plan [:budget :execution-attempts])))
        (is (= 0 (get-in plan [:budget :retries])))
        (is (false? (get-in plan [:authority :execution-authorized])))
        (is (false? (get-in plan [:authority :reward-or-payment-authorized])))
        (is (seq (get-in plan [:source :context :retained-block-contexts])))
        (is (= fixture/operation (get-in plan [:source :context :operation-id])))))))

(deftest plan-is-reproducible-and-task-status-cannot-authorize-evidence
  (with-bundle (fixture/fixture-input)
    (fn [root]
      (let [output (io/file root "plan-output")
            plan (sut/write-plan! root output {})
            path (io/file output "investigation-plan.edn")]
        (is (= plan (sut/plan-bundle root)))
        (is (:plan-matches-reconstruction? (sut/verify-plan root path)))
        (is (thrown? java.nio.file.FileAlreadyExistsException (sut/write-plan! root output {})))
        (doseq [substituted [(assoc-in plan [:tasks 0 :status] :complete)
                             (assoc-in plan [:tasks 0 :action] :run-arbitrary-command)
                             (assoc-in plan [:reconstructed-validation :valid?] false)
                             (assoc-in plan [:source :context :operation-id] "substituted")
                             (assoc plan :plan-root "sha256:forged")]]
          (spit path (pr-str substituted))
          (is (false? (:plan-matches-reconstruction? (sut/verify-plan root path)))))
        (spit path (pr-str plan))
        (spit (io/file root "artifacts/final-state.log") "reserve_balance=999\n")
        (is (false? (:plan-matches-reconstruction? (sut/verify-plan root path))))))))

(deftest changed-source-context-and-forged-labels-do-not-dispatch
  (doseq [input [(assoc-in (fixture/fixture-input) [:manifest "configurationRoot"] "0xff")
                 (assoc-in (fixture/fixture-input) [:fixture "gate_e" "operation_id"] "0xff")
                 (assoc-in (fixture/fixture-input) [:fixture "proof"] "0xffff")
                 (assoc-in (fixture/fixture-input)
                           [:broadcasts :withdrawal "receipts" 0 "logs" 0 "blockHash"] "0xff")]]
    (with-bundle (assoc-in input [:report "verification"] {"status" "verified"})
      (fn [root]
        (let [plan (sut/plan-bundle root)]
          (is (some #{:source-or-context-validation-failed} (:routing-blockers plan)))
          (is (every? #(= :manual-review (:action %)) (butlast (:tasks plan))))
          (is (every? #(= :blocked (:status %)) (:tasks plan)))
          (is (false? (get-in plan [:reconstructed-validation :valid?]))))))))

(deftest unsupported-profile-protocol-and-environment-block
  (doseq [[input reason]
          [[(assoc-in (fixture/fixture-input) [:manifest "schema"] "unknown.v2") :unsupported-bundle-profile]
           [(assoc-in (fixture/fixture-input) [:report "protocol"] {"id" "unknown" "version" "9"})
            :unsupported-protocol-descriptor]
           [(assoc-in (fixture/fixture-input) [:manifest "chainId"] 84532) :unsupported-chain-or-environment]
           [(assoc-in (fixture/fixture-input) [:manifest "localOnly"] false) :unsupported-chain-or-environment]
           [(assoc-in (fixture/fixture-input) [:manifest "localOnly"] "false") :unsupported-chain-or-environment]]]
    (with-bundle input
      (fn [root]
        (let [plan (sut/plan-bundle root)]
          (is (some #{reason} (:routing-blockers plan)))
          (is (every? #(= :blocked (:status %)) (:tasks plan)))
          (is (nil? (:runner (last (:tasks plan))))))))))

(deftest gaps-are-deterministic-deduplicated-and-unsupported-cases-stay-blocked
  (with-bundle (fixture/fixture-input)
    (fn [root]
      (let [r (deployment/reconstruct-bundle root)
            gaps (get-in r [:validation :gaps])
            plan (#'sut/plan-reconstruction r 32)
            permuted (update-in r [:validation :gaps] #(vec (concat (reverse %) %)))
            reordered (#'sut/plan-reconstruction permuted 32)]
        ;; The retained validator report preserves input order, but task IDs,
        ;; membership, and ordering are independent of issue enumeration order.
        (is (= (:tasks plan) (:tasks reordered)))
        (is (= (count (distinct (map :task-id (:tasks plan)))) (count (:tasks plan))))
        (doseq [[kind action blocker]
                [[:unknown-gap :manual-review :unsupported-gap-type]
                 [:policy-required-attestation :request-attestation :policy-and-attestor-authority-required]
                 [:conflicting-evidence :review-conflict :independent-conflict-resolution-required]]]
          (let [new-issue {:kind kind :artifact "test subject" :status :not-available}
                p (#'sut/plan-reconstruction (assoc-in r [:validation :gaps] (conj gaps new-issue)) 32)
                t (first (filter #(= kind (get-in % [:issue :kind])) (:tasks p)))]
            (is (= action (:action t)))
            (is (= :blocked (:status t)))
            (is (some #{blocker} (:blockers t)))))
        (testing "optional rewards and research never become evidence prerequisites"
          (let [p (#'sut/plan-reconstruction
                   (update-in r [:validation :gaps] into
                              [{:kind :optional-reward} {:kind :optional-research}]) 32)]
            (is (= (:tasks plan) (:tasks p)))
            (is (= 2 (count (:excluded-issues p))))
            (is (every? #(false? (:required-for-evidence-closure %)) (:excluded-issues p)))))
        (testing "graph conflicts also require review"
          (let [p (#'sut/plan-reconstruction (assoc-in r [:view :graph :conflicts]
                                                       [{:subject "conflicting observation"}]) 32)]
            (is (some #(= :graph-conflict (get-in % [:issue :origin])) (:tasks p)))))
        (testing "invalid graph cannot select an automated runner"
          (let [p (#'sut/plan-reconstruction (assoc-in r [:view :graph :integrity-valid?] false) 32)]
            (is (some #{:forensic-graph-integrity-invalid} (:routing-blockers p)))
            (is (every? #(= :blocked (:status %)) (:tasks p)))))))))

(deftest budgets-defer-explicitly-and-reject-execution-options
  (with-bundle (fixture/fixture-input)
    (fn [root]
      (let [plan (sut/plan-bundle root {:max-tasks 1})]
        (is (= 1 (count (:tasks plan))))
        (is (= (dec (count (:tasks (sut/plan-bundle root)))) (count (:deferred-tasks plan))))
        (is (= :reconstruct-forensic-view (:action (last (:deferred-tasks plan)))))
        (is (every? #(= :task-budget-exhausted (:deferred-reason %)) (:deferred-tasks plan)))
        (is (empty? (:depends-on (first (:tasks plan)))))
        (is (= plan (sut/plan-bundle root {:max-tasks 1}))))
      (doseq [opts [{:max-tasks 0} {:max-tasks 101} {:max-tasks nil} {:max-tasks 1.5}
                    {:execute true} {:runner "arbitrary"} {:verified true}]]
        (is (thrown? clojure.lang.ExceptionInfo (sut/plan-bundle root opts))))
      (io/delete-file (io/file root "artifacts/witness.json"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete" (sut/plan-bundle root))))))

(deftest source-body-is-consumed-and-revalidated-not-trusted-by-path
  (let [source-path "etc/conformance/vectors/source-bound-entitlement-v1.json"
        source (json/read-str (slurp source-path))
        input (-> (fixture/fixture-input)
                  (assoc :witness (get source "base-input"))
                  (assoc-in [:fixture "gate_e" "vector_id"] "A-minimum-realistic")
                  (assoc-in [:fixture "gate_e" "source_bundle_sha256"]
                            (str "0x" (deployment/file-sha256 source-path))))]
    (with-bundle input
      (fn [root]
        (let [opts {:source-bundle source-path}
              plan (sut/write-plan! root (io/file root "source-plan") opts)
              file (io/file root "source-plan/investigation-plan.edn")]
          (is (true? (get-in plan [:reconstructed-validation :checks :source-bundle-content-bound])))
          (is (false? (get-in plan [:reconstructed-validation :checks :witness-derived-public-values]))
              "matching source bytes cannot authorize mismatched proof outputs")
          (is (some #{:source-or-derived-output-validation-failed} (:routing-blockers plan)))
          (is (:plan-matches-reconstruction? (sut/verify-plan root file opts)))
          (is (false? (:plan-matches-reconstruction? (sut/verify-plan root file)))))))))
