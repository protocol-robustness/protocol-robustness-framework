(ns resolver-sim.commands.benchmark-command-failure-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.run-benchmark :as command]
            [resolver-sim.commands.scenario-safety :as safety]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "benchmark-command-failure-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(deftest canonical-benchmark-rejects-external-manifest-before-root-mutation
  (let [root (temp-dir)
        manifest (io/file root "external-benchmark.edn")]
    (try
      (spit manifest "{:benchmark/id :external}")
      (let [result (command/run {:cmd/args [(.getPath manifest)]
                                 :run-root (str (io/file root "bundle"))})]
        (is (= 2 (:exit-code result)))
        (is (re-find #"Filesystem benchmark manifests" (:message result)))
        (is (not (.exists (io/file root "bundle")))))
      (finally (delete-tree! root)))))

(deftest every-phase-failure-retains-state-and-never-completes
  (doseq [failed-phase [:execute :write-manifest :snapshot-definition :write-conclusion
                        :write-summary :scan-sensitivity :build-inventory
                        :finalize-registry :validate-registry]]
    (let [root (temp-dir)
          calls (atom [])
          phases [:execute :write-manifest :snapshot-definition :write-conclusion
                  :write-summary :scan-sensitivity :build-inventory
                  :finalize-registry :validate-registry :complete]
          record (fn [phase]
                   (fn [& _]
                     (swap! calls conj phase)
                     (if (= phase failed-phase)
                       (throw (ex-info "injected phase failure" {:phase phase}))
                       (if (= phase :execute) {:exit-code 0 :evidence {}} {}))))
          overrides (into {} (map (fn [phase] [phase (record phase)]) phases))]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected phase failure"
                              (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides))
            (name failed-phase))
        (is (not (.exists (io/file root "completion.json"))) (name failed-phase))
        (is (.exists (io/file root ".run-state")) (name failed-phase))
        (is (not (.exists (io/file root ".run.lock"))) (name failed-phase))
        (is (not (some #{:complete} @calls)) (name failed-phase))
        (finally (delete-tree! root))))))

(deftest public-sensitivity-failure-prevents-finalization
  (let [root (temp-dir)
        calls (atom [])
        phases [:execute :write-manifest :snapshot-definition :write-conclusion :write-summary]
        overrides (merge
                   (into {} (map (fn [phase]
                                   [phase (fn [& _]
                                            (swap! calls conj phase)
                                            (if (= phase :execute) {:exit-code 0 :evidence {}} {}))])
                                 phases))
                   {:scan-sensitivity (fn [context _]
                                        (swap! calls conj :scan-sensitivity)
                                        (spit (io/file (str (:run/root context)) "benchmark/secret.txt") "api_key=forbidden")
                                        (safety/scan-public-bundle! (:run/root context)))
                    :build-inventory (fn [& _] (swap! calls conj :build-inventory))
                    :finalize-registry (fn [& _] (swap! calls conj :finalize-registry))
                    :validate-registry (fn [& _] (swap! calls conj :validate-registry))
                    :complete (fn [& _] (swap! calls conj :complete))})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sensitivity scan failed"
                            (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)))
      (is (= [:execute :write-manifest :snapshot-definition :write-conclusion :write-summary :scan-sensitivity]
             @calls))
      (is (.exists (io/file root ".run-state")))
      (is (not (.exists (io/file root "completion.json"))))
      (is (not (.exists (io/file root ".run.lock"))))
      (finally (delete-tree! root)))))

(deftest completion-failure-retains-running-state
  (let [root (temp-dir)
        calls (atom [])
        phases [:execute :write-manifest :snapshot-definition :write-conclusion
                :write-summary :scan-sensitivity :build-inventory
                :finalize-registry :validate-registry]
        overrides (merge
                   (into {} (map (fn [phase]
                                   [phase (fn [& _]
                                            (swap! calls conj phase)
                                            (if (= phase :execute) {:exit-code 0 :evidence {}} {}))])
                                 phases))
                   {:complete (fn [& _]
                                (swap! calls conj :complete)
                                (throw (ex-info "injected completion failure" {})))})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected completion failure"
                            (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)))
      (is (= :complete (last @calls)))
      (is (.exists (io/file root ".run-state")))
      (is (not (.exists (io/file root "completion.json"))))
      (is (not (.exists (io/file root ".run.lock"))))
      (finally (delete-tree! root)))))

(deftest registry-failure-retains-state-and-releases-lock
  (let [root (temp-dir)
        calls (atom [])
        record (fn [phase value]
                 (fn [& _] (swap! calls conj phase) value))
        overrides {:execute (record :execute {:exit-code 0 :evidence {}})
                   :write-manifest (record :write-manifest {})
                   :snapshot-definition (record :snapshot-definition {})
                   :write-conclusion (record :write-conclusion {"outcome" "pass"})
                   :write-summary (record :write-summary {})
                   :scan-sensitivity (record :scan-sensitivity {})
                   :build-inventory (record :build-inventory {})
                   :finalize-registry (fn [& _]
                                        (swap! calls conj :finalize-registry)
                                        (throw (ex-info "injected registry failure" {})))
                   :validate-registry (record :validate-registry {})
                   :complete (record :complete {})}]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"injected registry failure"
                            (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)))
      (is (= [:execute :write-manifest :snapshot-definition :write-conclusion
              :write-summary :scan-sensitivity :build-inventory :finalize-registry]
             @calls))
      (is (.exists (io/file root ".run-state")))
      (is (not (.exists (io/file root "completion.json"))))
      (is (not (.exists (io/file root ".run.lock"))))
      (finally (delete-tree! root)))))
