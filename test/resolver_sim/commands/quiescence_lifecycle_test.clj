(ns resolver-sim.commands.quiescence-lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.benchmark-orchestration :as orchestration]
            [resolver-sim.commands.run-benchmark :as command]
            [resolver-sim.util.thread-quiescence :as quiesce]
            [resolver-sim.io.paths :as paths])
  (:import [java.nio.file Files]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "quiescence-lifecycle-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- successful-overrides [calls]
  (into {}
        (map (fn [phase]
               [phase (fn [& _]
                        (swap! calls conj phase)
                        (if (= phase :execute) {:exit-code 0 :evidence {}} {}))]))
        orchestration/phases))

(deftest quiescence-failure-retains-lock-and-marks-root-incomplete
  (testing "quiescence failure prevents lock release and marks root :quiescence-unknown"
    (let [root (temp-dir)
          calls (atom [])
          overrides (assoc (successful-overrides calls)
                           :execute (fn [_]
                                      (swap! calls conj :execute)
                                      (throw (quiesce/quiescence-failed-exception
                                              "Worker threads did not terminate"
                                              {:quiescence/status :termination-timeout})))
                           :complete (fn [context _]
                                       (swap! calls conj :complete)
                                       (spit (io/file (str (:run/root context)) "completion.json") "{}")))]
      (try
        (let [result (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)]
          (is (= 1 (:exit-code result)))
          (is (= :quiescence-failed (:command/status result)))
          (is (.exists (io/file root paths/run-lock))
              "run lock must persist after quiescence failure")
          (is (.exists (io/file root paths/run-state))
              "run state must be written after quiescence failure")
          (is (not (.exists (io/file root "completion.json")))
              "completion file must not exist after quiescence failure")
          (let [state (slurp (io/file root paths/run-state))]
            (is (re-find #"quiescence-unknown" state)
                "run state should contain :quiescence-unknown")))
        (finally (delete-tree! root))))))

(deftest normal-execution-failure-still-releases-lock
  (testing "Non-quiescence exception releases the lock and marks root :incomplete"
    (let [root (temp-dir)
          calls (atom [])
          overrides (assoc (successful-overrides calls)
                           :execute (fn [_]
                                      (swap! calls conj :execute)
                                      (throw (ex-info "normal execution failure" {:type :normal})))
                           :execution/parallelism 3
                           :execution/chunk-size 7)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"normal execution failure"
                              (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)))
        (is (.exists (io/file root paths/run-state)))
        (is (not (.exists (io/file root "completion.json"))))
        (is (not (.exists (io/file root paths/run-lock)))
            "run lock must be released on non-quiescence failure")
        (finally (delete-tree! root))))))

(deftest success-path-still-releases-lock-and-completes
  (testing "Success path releases lock and writes completion"
    (let [root (temp-dir)
          calls (atom [])
          overrides (assoc (successful-overrides calls)
                           :complete (fn [context _]
                                       (swap! calls conj :complete)
                                       (spit (io/file (str (:run/root context)) "completion.json") "{}")))]
      (try
        (let [result (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)]
          (is (zero? (:exit-code result)))
          (is (not (.exists (io/file root paths/run-lock))))
          (is (.exists (io/file root "completion.json"))))
        (finally (delete-tree! root))))))

(deftest run-with-root-rethrows-quiescence-failures
  (testing "run-with-root! does not swallow quiescence failures from the execute phase"
    (let [root (temp-dir)
          calls (atom [])
          overrides (assoc (successful-overrides calls)
                           :execute (fn [_]
                                      (swap! calls conj :execute)
                                      (throw (quiesce/quiescence-failed-exception
                                              "Workers stuck"
                                              {:quiescence/status :termination-timeout})))
                           :execution/parallelism 3
                           :execution/chunk-size 7)]
      (try
        (let [result (command/run-with-root! "benchmark/test" (.getPath root) nil :public overrides)]
          (is (= 1 (:exit-code result)))
          (is (= :quiescence-failed (:command/status result)))
          (is (.exists (io/file root paths/run-lock))
              "lock persists when execute phase throws quiescence failure"))
        (finally (delete-tree! root))))))