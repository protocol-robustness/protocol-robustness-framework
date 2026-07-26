(ns resolver-sim.test-util
  "Test utilities for faster test execution.

   Two modes of evidence isolation:

     1. with-isolated-evidence — suppresses all capture I/O (noop).  Use for
        pure unit tests that don't verify evidence artifacts.

     2. with-temp-evidence — redirects capture to a temp directory that is
        cleaned up after.  Use for scenario tests that need evidence chain
        integrity."

  (:require [clojure.java.io :as io]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.util.attribution :as attr]))

(defn noop-capture
  "In-memory no-op evidence capture.
   Accepts all calling conventions that capture-event-evidence! uses
   but discards the evidence without any I/O."
  [& _]
  nil)

(defn- temp-dir
  "Return a temp dir under /dev/shm (RAM-backed tmpfs) when available,
   falling back to java.io.tmpdir (usually /tmp on disk).  All evidence
   artifact I/O during tests goes to this directory, so using RAM avoids
   ~160s of system CPU from file write/read on spinning media."
  []
  (let [base (if-let [shm (io/file "/dev/shm")]
               (if (.isDirectory shm)
                 (.getPath shm)
                 (System/getProperty "java.io.tmpdir"))
               (System/getProperty "java.io.tmpdir"))]
    (str base "/sew-test-evidence-" (java.util.UUID/randomUUID))))

(defn- cleanup!
  [dir]
  (when dir
    (try
      (let [root (io/file dir)]
        (when (.exists root)
          (doseq [f (reverse (file-seq root))]
            (.delete f))))
      (catch Exception _))))

(defn with-isolated-evidence
  "Execute f with evidence capture suppressed entirely (no I/O).
   All capture calls are no-ops; chain artifacts are not written.
   clean up.  Returns f's return value.

   Suitable for pure unit tests (lifecycle, state-machine, accounting, etc.)
   where evidence artifact correctness is not under test."
  [f]
  (let [dir (temp-dir)]
    (binding [cap/*capture-event-evidence!* noop-capture
              evcfg/*artifact-dir* dir
              chain/*allow-dirty* true
              hc/*validate-intent-constraints* true]
      (try
        (f)
        (finally
          (cleanup! dir))))))

(defn with-temp-evidence
  "Execute f with evidence capture redirected to a temp directory.
   The dirty working copy check is bypassed.  Temp dir is cleaned
   up on exit unless KEEP_TEST_ARTIFACTS is set.

   Suitable for scenario / integration tests that need evidence
   chain integrity but should not pollute the default artifact dir."
  [f]
  (let [dir (temp-dir)]
    (binding [evcfg/*artifact-dir* dir
              chain/*allow-dirty* true
              hc/*validate-intent-constraints* true]
      (try
        (f)
        (finally
          (let [keep? (System/getenv "KEEP_TEST_ARTIFACTS")]
            (if keep?
              (println "Preserved test artifacts:" dir)
              (cleanup! dir))))))))
