(ns scripts.test-state
  "Persist and retrieve the last completed test run's state.

   State is stored in .prf/test-state.edn with atomic writes to prevent
   corruption from interrupted or crashing runs.

   Schema:
     {:schema-version 1
      :completed? true
      :command [\"bb\" \"test:unit\"]
      :failed-test-namespaces [resolver-sim.foo-test]}

   Usage:
     clojure -M -m scripts.test-state write <command-json> <failed-ns-edn>
     clojure -M -m scripts.test-state read
     clojure -M -m scripts.test-state clear"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def state-dir ".prf")
(def state-file (str state-dir "/test-state.edn"))
(def tmp-file (str state-dir "/test-state.edn.tmp"))

(defn read-state
  "Read the persisted test state, or nil if missing/unparseable."
  []
  (let [f (io/file state-file)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn write-state!
  "Atomically write test state.  Takes a map with keys:
     :command      — vector of strings, the command that was run
     :failed-nses  — seq of symbols for namespaces that failed"
  [{:keys [command failed-nses]}]
  (let [data {:schema-version 1
              :completed? true
              :command (vec command)
              :failed-test-namespaces (vec (sort (remove nil? failed-nses)))}]
    (io/make-parents (io/file tmp-file))
    (spit tmp-file (prn-str data))
    (.renameTo (io/file tmp-file) (io/file state-file))))

(defn clear-state!
  "Remove the state file."
  []
  (.delete (io/file state-file))
  (.delete (io/file tmp-file)))

(defn -main
  [& args]
  (case (first args)
    "write"
    (let [command (read-string (second args))
          failed-nses (read-string (nth args 2))]
      (write-state! {:command command :failed-nses failed-nses})
      (println "Test state written.")
      (System/exit 0))
    "read"
    (let [state (read-state)]
      (if state
        (do (println (prn-str state))
            (System/exit 0))
        (do (println "No test state found.")
            (System/exit 1))))
    "clear"
    (do (clear-state!)
        (println "Test state cleared.")
        (System/exit 0))
    (println "Usage: clojure -M -m scripts.test-state <write|read|clear>")))
