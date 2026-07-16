(ns resolver-sim.commands.scenario-orchestration-failure-test
  (:require [clojure.java.io :as io]
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
   :validate-registry (fn [_ _] {})})

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
                :scan-sensitivity :finalize-registry :validate-registry :complete]
               (mapv :phase (:phases result)))))
      (finally (delete-tree! root)))))
