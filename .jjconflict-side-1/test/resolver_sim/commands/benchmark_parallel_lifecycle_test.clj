(ns resolver-sim.commands.benchmark-parallel-lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.cli :as benchmark-cli]
            [resolver-sim.commands.benchmark-orchestration :as orchestration]
            [resolver-sim.commands.run-benchmark :as command]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "benchmark-parallel-lifecycle-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- successful-package-overrides [calls]
  (into {}
        (map (fn [phase]
               [phase (fn [& _]
                        (swap! calls conj phase)
                        (if (= phase :execute) {:exit-code 0 :evidence {}} {}))])
             orchestration/phases)))

(deftest run-with-root-preserves-parallel-options-through-package-lifecycle
  (let [root (temp-dir)
        calls (atom [])
        seen-context (atom nil)
        overrides (assoc (successful-package-overrides calls)
                         :execute (fn [context]
                                    (reset! seen-context context)
                                    (swap! calls conj :execute)
                                    {:exit-code 0 :evidence {}})
                         :complete (fn [context _]
                                     (swap! calls conj :complete)
                                     (spit (io/file (str (:run/root context)) "completion.json") "{}")))]
    (try
      (let [result (command/run-with-root! "benchmark/test" (.getPath root) nil :public
                                           (assoc overrides
                                                  :execution/parallelism 3
                                                  :execution/chunk-size 7))]
        (is (zero? (:exit-code result)))
        (is (= 3 (:execution/parallelism @seen-context)))
        (is (= 7 (:execution/chunk-size @seen-context)))
        (is (= 1 (:execution/claimant-parallelism @seen-context)))
        (is (= 16 (:execution/claimant-parallel-threshold @seen-context)))
        (is (= orchestration/phases @calls))
        (is (.exists (io/file root "completion.json")))
        (is (not (.exists (io/file root ".run.lock")))))
      (finally (delete-tree! root)))))

(deftest invalid-claimant-runtime-options-are-rejected-before-execution
  (let [called? (atom false)
        run! (fn [opts]
               (with-redefs [benchmark-cli/run-and-report
                             (fn [& _] (reset! called? true) {:exit-code 0})]
                 (command/run opts)))]
    (is (= 2 (:exit-code (run! {:cmd/args ["benchmark/test"]
                                :run-root "target/invalid-claimant-parallelism"
                                :claimant-parallelism 0}))))
    (is (= 2 (:exit-code (run! {:cmd/args ["benchmark/test"]
                                :run-root "target/invalid-claimant-threshold"
                                :claimant-parallel-threshold 0}))))
    (is (false? @called?))))

(deftest claim-evaluator-like-execution-failure-releases-root-without-completion
  (let [root (temp-dir)
        received-options (atom nil)]
    (try
      (with-redefs [benchmark-cli/run-and-report
                    (fn [_ options]
                      (reset! received-options options)
                      (throw (ex-info "claim evaluator rejected execution" {:claim/id :claim/test})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"claim evaluator rejected execution"
                              (command/run-with-root! "benchmark/test" (.getPath root) nil :public
                                                      {:execution/parallelism 3
                                                       :execution/chunk-size 7}))))
      (is (= 3 (:parallelism @received-options)))
      (is (= 7 (:chunk-size @received-options)))
      (is (= 1 (:execution/claimant-parallelism @received-options)))
      (is (= 16 (:execution/claimant-parallel-threshold @received-options)))
      (is (.exists (io/file root ".run-state")))
      (is (not (.exists (io/file root "completion.json"))))
      (is (not (.exists (io/file root ".run.lock"))))
      (finally (delete-tree! root)))))
