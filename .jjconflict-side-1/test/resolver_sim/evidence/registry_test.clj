(ns resolver-sim.evidence.registry-test
  "Tests for the evidence registry builder and validation.
   
   These model researcher workflows:
   - \"What evidence was produced for this run?\"
   - \"Which evidence belongs to a specific event?\"
   - \"Which evidence belongs to a specific workflow?\"
   - \"Is the registry structurally valid?\""
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.evidence.registry :as reg]
            [resolver-sim.evidence.registry-validation :as reg-val]
            [resolver-sim.io.event-evidence :as evidence]))

;; ── Test Helpers ──────────────────────────────────────────────────────────────

(defn- write-sample-artifact
  "Write a single evidence artifact to dir/event-evidence/."
  [dir m]
  (let [ev-dir (str dir "/event-evidence")
        fname (evidence/evidence-filename m)
        f (io/file ev-dir fname)]
    (.mkdirs (io/file ev-dir))
    (spit f (json/write-str m {:key-fn evidence/qualified-key :indent true}))
    (.getPath f)))

(defn- setup-events
  "Write a realistic set of evidence artifacts into dir/event-evidence/.
   Returns a map of {:dir dir :event-count N}."
  [dir]
  (.mkdirs (java.io.File. (str dir "/event-evidence")))
  (let [base {:evidence/schema-version "event-evidence.v1"
              :run/id "test-run"
              :scenario/id "test-scenario"
              :attribution/context {:ctx/run-id "test-run"
                                    :ctx/scenario-id "test-scenario"
                                    :ctx/event-index 0
                                    :ctx/event-type :create_escrow
                                    :subject/type :escrow
                                    :subject/id 0
                                    :action/type :escrow/create
                                    :evidence/reason :escrow-created}}
        ;; Event 0: create escrow — generic trace
        e0-generic (assoc base :evidence/type "transition"
                          :evidence/hash "gen-0000"
                          :evidence/layer :generic-trace
                          :evidence/group-id "test-run:0:create_escrow"
                          :event/seq 0
                          :artifact-kind :transition
                          :attribution/context (assoc (:attribution/context base)
                                                      :ctx/event-index 0
                                                      :ctx/event-type :create_escrow))
        ;; Event 0: targeted escrow-created
        e0-target (assoc base :evidence/type "escrow-created"
                         :evidence/hash "tgt-0000"
                         :evidence/layer :targeted-protocol
                         :evidence/chain-seq 1
                         :evidence/group-id "test-run:0:create_escrow"
                         :evidence/chain-self-hash "tgt-0000"
                         :escrow/workflow-id 0
                         :world/before-full-hash "sha256-before-0"
                         :world/after-full-hash "sha256-after-0"
                         :event/seq 0
                         :attribution/context (assoc (:attribution/context base)
                                                     :ctx/event-index 0
                                                     :ctx/event-type :create_escrow))
        ;; Event 1: raise dispute
        e1-dispute (assoc base :evidence/type "dispute-raised"
                          :evidence/hash "tgt-0001"
                          :evidence/layer :targeted-protocol
                          :evidence/chain-seq 2
                          :evidence/group-id "test-run:1:raise_dispute"
                          :dispute/workflow-id 0
                          :event/seq 1
                          :attribution/context (assoc (:attribution/context base)
                                                      :ctx/event-index 1
                                                      :ctx/event-type :raise_dispute
                                                      :subject/type :dispute
                                                      :action/type :dispute/raise))]
    (doseq [m [e0-generic e0-target e1-dispute]]
      (write-sample-artifact dir m))
    {:dir dir :event-count 3}))

;; ── Researcher Question: \"What evidence was produced for this run?\" ─────────

(deftest registry-has-all-artifacts
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)]
    (is (some? registry) "Registry was built")
    (is (= 3 (count (:entries registry))) "All 3 artifacts recorded")
    (is (= "evidence-registry.v1" (:schema/version registry)))
    (is (contains? (:indexes registry) :by-event-index))
    (is (contains? (:indexes registry) :by-layer))))

(deftest registry-query-helpers
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        artifacts (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)]
    (testing "evidence-for-event"
      (let [results (reg/evidence-for-event registry 0)]
        (is (= 2 (count results)) "Event 0 has 2 artifacts (generic + targeted)")
        (is (some #(= :transition (:evidence/type %)) results))
        (is (some #(= :escrow-created (:evidence/type %)) results))))
    (testing "evidence-for-group"
      (let [results (reg/evidence-for-group registry "test-run:0:create_escrow")]
        (is (= 2 (count results)) "Group has generic + targeted")
        (is (some #(= :generic-trace (:evidence/layer %)) results))
        (is (some #(= :targeted-protocol (:evidence/layer %)) results))))
    (testing "evidence-for-type"
      (let [results (reg/evidence-for-type registry :escrow-created)]
        (is (= 1 (count results)) "One escrow-created entry")
        (is (= :escrow-created (:evidence/type (first results))))))
    (testing "evidence-for-layer"
      (let [generic (reg/evidence-for-layer registry :generic-trace)
            targeted (reg/evidence-for-layer registry :targeted-protocol)]
        (is (= 1 (count generic)) "One generic trace")
        (is (= 2 (count targeted)) "Two targeted artifacts")))))

;; ── Researcher Question: \"Which evidence belongs to a specific workflow?\" ────

(deftest registry-subject-linking
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)]
    ;; Evidence for escrow 0
    (let [results (reg/evidence-for-subject registry :escrow 0)]
      (is (seq results) "Escrow 0 has evidence")
      (is (some #(= :escrow-created (:evidence/type %)) results)))))

;; ── Validation: \"Is the registry structurally valid?\" ─────────────────────

(deftest validation-passes-for-valid-registry
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)
        result (reg-val/validate-evidence-registry registry :artifact-dir dir)]
    (is (= :passed (:status result)) "Validation passes")
    (is (some #(= "every-entry-has-id" (:id %)) (:checks result)))
    (is (zero? (:failed (:metrics result))) "No failed checks")))

(deftest validation-detects-missing-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)
        ;; Remove an ID from one entry
        tampered (update registry :entries (fn [entries]
                                             (update-in entries [0] dissoc :evidence/id)))
        result (reg-val/validate-evidence-registry tampered :artifact-dir dir)]
    (is (= :failed (:status result)) "Validation fails when entries lack IDs")
    (is (some #(and (= "every-entry-has-id" (:id %)) (= :failed (:status %))) (:checks result)))))

(deftest validation-describe-check-metrics
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        {:keys [registry]} (reg/write-evidence-registry! dir)
        result (reg-val/validate-evidence-registry registry :artifact-dir dir)
        metrics (:metrics result)]
    (is (pos? (:passed metrics)) "At least one check passed")
    (is (nat-int? (:total metrics)) "Total is a number")
    (is (nat-int? (:diagnostics metrics)) "Diagnostic count is a number")))

;; ── Full pipeline: build + validate + write ─────────────────────────────────

(deftest full-pipeline-creates-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (setup-events dir)
        result (reg-val/build-evidence-registry! :dir dir :strict false)]
    (is (.exists (io/file (:registry-path result))) "Registry file exists")
    (is (.exists (io/file (:validation-path result))) "Validation file exists")
    (is (= 3 (:entry-count result)) "3 entries")
    (is (= :passed (:validation-status result)) "Validation passes")
    ;; Read back and verify
    (let [read-reg (json/read-str (slurp (:registry-path result)) :key-fn keyword)]
      (is (= 3 (count (:entries read-reg))) "Read registry has 3 entries"))))

;; ── Edge cases ──────────────────────────────────────────────────────────────

(deftest registry-empty-directory
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        _ (.mkdirs (java.io.File. (str dir "/event-evidence")))
        {:keys [registry]} (reg/write-evidence-registry! (str dir))]
    (is (zero? (count (:entries registry))) "No entries for empty directory")
    (let [result (reg-val/validate-evidence-registry registry dir)]
      (is (= :passed (:status result)) "Empty registry still passes required checks"))))

(deftest registry-missing-directory
  (let [reg (reg/build-evidence-registry "/nonexistent-path")]
    (is (zero? (count (:entries reg))) "No entries for missing directory")))

(deftest registry-handles-malformed-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reg-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (.mkdirs (java.io.File. ev-dir))
        ;; Write a valid file
        _ (write-sample-artifact dir {:evidence/type "valid" :evidence/hash "abc" :run/id "r"})
        ;; Write a malformed file
        _ (spit (io/file ev-dir "corrupt.json") "{not valid}")
        {:keys [registry]} (reg/write-evidence-registry! dir)]
    (is (= 1 (count (:entries registry))) "Malformed files are skipped")))
