(ns resolver-sim.commands.scenario-orchestration-failure-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.scenario-orchestration :as orchestration]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "scenario-orchestration-failure-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- context [root]
  (let [scenario-root (io/file root "scenarios" "fixture")]
    {:run/id "run-failure-test"
     :run/root (.getPath root)
     :scenario/ref "fixture.edn"
     :scenario/slug "fixture"
     :scenario/root (.getPath scenario-root)
     :execution/dir (.getPath (io/file scenario-root "execution"))
     :forensic/dir (.getPath (io/file scenario-root "forensic"))
     :summaries/dir (.getPath (io/file scenario-root "summaries"))
     :manifest/dir (.getPath (io/file root "manifest"))
     :replay/file (.getPath (io/file scenario-root "execution" "replay-output.json"))
     :sensitivity/profile :public}))

(defn- successful-overrides []
  {:check-runtime (fn [_] {})
   :execute (fn [_] {:exit-code 0})
   :write-manifest (fn [_ _] {})
   :extract-artifacts (fn [_ _] {})
   :scan-sensitivity (fn [_ _] {})
   :finalize-registry (fn [_ _] {})
   :validate-registry (fn [_ _] {})
   :finalize-run-evidence (fn [_ _] {})
   ;; This test namespace exercises orchestration phase ordering and failure
   ;; isolation. Package validity itself is covered by package-index tests, so
   ;; its fixture supplies successful implementations for the package phases.
   :build-attestation-bundle (fn [_ _] {})
   :write-canonical-assurance (fn [_ _] {})
   :write-diagnostic (fn [_ _] {})
   :refresh-inventory (fn [_ _] {})
   :refresh-registry (fn [_ _] {})
   :revalidate-registry (fn [_ _] {})
   :write-package-index (fn [_ _] {})
   :complete (fn [c _]
               ;; Mirror the terminal lifecycle writer's permitted cleanup.
               (spit (io/file (:run/root c) "completion.json") "{}")
               (io/delete-file (io/file (:run/root c) ".run-state") true)
               {})})

(deftest required-phase-failure-never-completes-a-run
  (doseq [phase [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity
                 :finalize-registry :validate-registry]]
    (let [root (temp-dir)
          c (context root)]
      (try
        (testing (name phase)
          (let [result (orchestration/run-scenario!
                        c (assoc (successful-overrides)
                                 phase (fn [& _] (throw (ex-info "injected phase failure" {:phase phase})))))]
            (is (= :failed (:command/status result)))
            (is (= :unknown (:scenario/outcome result)))
            (is (not (.exists (io/file root "completion.json"))))
            (is (.exists (io/file root ".run-state")))
            (is (not (.exists (io/file root ".run.lock"))))))
        (finally (delete-tree! root))))))

(deftest phase-failures-do-not-invoke-downstream-finalization-work
  (doseq [[failed-phase expected-phases]
          [[:write-manifest [:check-runtime :execute :write-manifest]]
           [:extract-artifacts [:check-runtime :execute :write-manifest :extract-artifacts]]
           [:scan-sensitivity [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity]]
           [:finalize-registry [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity :finalize-registry]]]]
    (let [root (temp-dir)
          c (context root)
          calls (atom [])
          record (fn [phase value]
                   (fn [& _] (swap! calls conj phase) value))
          overrides {:check-runtime (record :check-runtime {})
                     :execute (record :execute {:exit-code 0})
                     :write-manifest (record :write-manifest {})
                     :extract-artifacts (record :extract-artifacts {})
                     :scan-sensitivity (record :scan-sensitivity {})
                     :finalize-registry (record :finalize-registry {})
                     :validate-registry (record :validate-registry {})}]
      (try
        (orchestration/run-scenario!
         c (assoc overrides failed-phase
                  (fn [& _]
                    (swap! calls conj failed-phase)
                    (throw (ex-info "injected phase failure" {:phase failed-phase})))))
        (is (= expected-phases @calls) (name failed-phase))
        (is (not (.exists (io/file root "completion.json"))))
        (finally (delete-tree! root))))))

(deftest validation-failure-may-retain-report-but-never-completes
  (let [root (temp-dir)
        c (context root)
        calls (atom [])
        record (fn [phase value] (fn [& _] (swap! calls conj phase) value))
        overrides {:check-runtime (record :check-runtime {})
                   :execute (record :execute {:exit-code 0})
                   :write-manifest (record :write-manifest {})
                   :extract-artifacts (record :extract-artifacts {})
                   :scan-sensitivity (record :scan-sensitivity {})
                   :finalize-registry (record :finalize-registry {})
                   :validate-registry (fn [& _]
                                        (swap! calls conj :validate-registry)
                                        (spit (io/file root "manifest/artifact-registry-validation.json") "{\"status\":\"failed\"}")
                                        (throw (ex-info "validation failed" {})))}]
    (try
      (orchestration/run-scenario! c overrides)
      (is (= [:check-runtime :execute :write-manifest :extract-artifacts
              :scan-sensitivity :finalize-registry :validate-registry]
             @calls))
      (is (.exists (io/file root "manifest/artifact-registry-validation.json")))
      (is (not (.exists (io/file root "completion.json"))))
      (finally (delete-tree! root)))))

(deftest public-secrets-fail-before-inventory-and-internal-runs-record-retention-policy
  (doseq [[profile expected-status] [[:public :failed] [:internal :completed]]]
    (let [root (temp-dir)
          c (assoc (context root) :sensitivity/profile profile)
          overrides (-> (successful-overrides)
                        (dissoc :scan-sensitivity)
                        (assoc :extract-artifacts (fn [_ _]
                                                   (spit (io/file root "secret.txt") "api_key=must-not-export")
                                                   {})))]
      (try
        (let [result (orchestration/run-scenario! c overrides)]
          (is (= expected-status (:command/status result)))
          (if (= profile :public)
            (do
              (is (not (.exists (io/file root "completion.json"))))
              (is (not (.exists (io/file root "manifest/artifacts.json")))))
            (let [report (json/read-str (slurp (io/file root "manifest/sensitivity-report.json")))]
              (is (.exists (io/file root "completion.json")))
              (is (= "internal" (get report "profile")))
              (is (= "internal-retention" (get report "decision")))
              (is (= 1 (count (get report "findings")))))))
        (finally (delete-tree! root))))))

(deftest same-run-root-cannot-enter-while-another-command-is-active
  (let [root (temp-dir)
        c (context root)
        entered (promise)
        release (promise)
        overrides (assoc (successful-overrides)
                         :execute (fn [_]
                                    (deliver entered true)
                                    @release
                                    {:exit-code 0}))]
    (try
      (let [first-run (future (orchestration/run-scenario! c overrides))]
        @entered
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"already in use"
                              (orchestration/run-scenario! c overrides)))
        (deliver release true)
        (is (= :completed (:command/status @first-run)))
        (is (.exists (io/file root "completion.json")))
        (is (not (.exists (io/file root ".run.lock")))))
      (finally (delete-tree! root)))))

(deftest successful-required-phases-complete-and-clear-running-state
  (let [root (temp-dir)
        c (context root)]
    (try
      (let [result (orchestration/run-scenario! c (successful-overrides))]
        (is (= :completed (:command/status result)))
        (is (.exists (io/file root "completion.json")))
        (is (not (.exists (io/file root ".run-state"))))
        (is (not (.exists (io/file root ".run.lock"))))
        (is (= [:check-runtime :execute :write-manifest :extract-artifacts
                :scan-sensitivity :finalize-registry :validate-registry :finalize-run-evidence
                :build-attestation-bundle :write-canonical-assurance :write-diagnostic
                :refresh-inventory :refresh-registry :revalidate-registry :write-package-index :complete]
               (mapv :phase (:phases result)))))
      (finally (delete-tree! root)))))
