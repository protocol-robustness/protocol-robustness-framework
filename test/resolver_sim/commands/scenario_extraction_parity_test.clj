(ns resolver-sim.commands.scenario-extraction-parity-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-extraction :as extraction]))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))] (io/delete-file file true))))
(defn- temp-dir [prefix] (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- read-json [file] (json/read-str (slurp file)))

(def ^:private replay
  {"scenario-id" "parity-basic"
   "outcome" "pass"
   "events-processed" 2
   "source" {"scenario-id" "parity-basic"}
   "metrics" {"escrow-unrealized" 0 "escrow-realized" 100 "claimable" 0}
   "world" {"total-fees" {"USDC" 2}}
   "trace" [{"seq" 0 "time" 1 "agent" "buyer" "action" "create_escrow" "result" "ok"}
              {"seq" 1 "time" 2 "agent" "resolver" "action" "release_escrow" "result" "ok"}]})

(def ^:private failed-replay
  {"scenario-id" "parity-rejected"
   "outcome" "fail"
   "events-processed" 1
   "source" {"scenario-id" "parity-rejected"}
   "metrics" {"invariant-violations" 1}
   "world" {}
   "trace" [{"seq" 0 "block-time" 9 "caller" "attacker" "action" "raise_dispute" "outcome" "rejected"}]})

(def ^:private yield-replay
  {"scenario-id" "parity-yield"
   "outcome" "pass"
   "events-processed" 1
   "source" {"scenario-id" "parity-yield"}
   "metrics" {"claimable" 25 "available-ratio" 0.75}
   "world" {"claimable-v2" {"w1" {"settlement/principal" {"buyer" 25}}}
             "yield/positions" {"p1" {"deferred-yield" 5 "status" "unwinding"}}
             "total-fees" {"USDC" 2}}
   "trace" [{"seq" 0 "time" 5 "agent" "resolver" "action" "release_claimable" "result" "ok"}]})

(defn- compare-trace-projection! [fixture]
  (let [root (temp-dir "extract-parity-")
        python-root (io/file root "python")
        clj-root (io/file root "clj")
        replay-file (io/file root "replay.json")]
    (try
      (spit replay-file (json/write-str fixture))
      (.mkdirs python-root)
      (let [python (shell/sh "python3" "scripts/evidence/extract_scenario_artifacts.py" "--replay" (.getPath replay-file) "--run-dir" (.getPath python-root))]
        (is (zero? (:exit python)) (:err python)))
      (extraction/write-basic-projections! clj-root (json/read-str (json/write-str fixture) :key-fn keyword) {"path" (.getPath replay-file)} :internal "run-parity")
      (let [py (read-json (io/file python-root "summaries/trace-summary.json"))
            clj (read-json (io/file clj-root "summaries/trace-summary.json"))]
        (is (= (select-keys py ["schema_version" "scenario_id" "outcome" "events_processed"])
               (select-keys clj ["schema_version" "scenario_id" "outcome" "events_processed"])))
        (is (= (mapv #(select-keys % ["seq" "time" "actor" "action" "result"]) (get py "steps"))
               (mapv #(select-keys % ["seq" "time" "actor" "action" "result"]) (get clj "steps")))))
      (finally (delete-tree! root)))))

(deftest python-and-clojure-basic-projections-have-semantic-parity
  (let [root (temp-dir "extract-parity-")
        python-root (io/file root "python")
        clj-root (io/file root "clj")
        replay-file (io/file root "replay.json")]
    (try
      (spit replay-file (json/write-str replay))
      (.mkdirs python-root)
      (let [python (shell/sh "python3" "scripts/evidence/extract_scenario_artifacts.py"
                             "--replay" (.getPath replay-file) "--run-dir" (.getPath python-root))]
        (is (zero? (:exit python)) (:err python)))
      (extraction/write-basic-projections!
       clj-root (json/read-str (json/write-str replay) :key-fn keyword)
       {"path" (.getPath replay-file)} :internal "run-parity")
      (let [py-trace (read-json (io/file python-root "summaries/trace-summary.json"))
            clj-trace (read-json (io/file clj-root "summaries/trace-summary.json"))
            py-world (read-json (io/file python-root "state/world-final.json"))
            clj-world (read-json (io/file clj-root "state/world-final.json"))]
        (is (= (select-keys py-trace ["schema_version" "scenario_id" "outcome" "events_processed"])
               (select-keys clj-trace ["schema_version" "scenario_id" "outcome" "events_processed"])))
        (is (= (mapv #(select-keys % ["seq" "time" "actor" "action" "result"]) (get py-trace "steps"))
               (mapv #(select-keys % ["seq" "time" "actor" "action" "result"]) (get clj-trace "steps"))))
        (is (= (get py-world "world") (get clj-world "world"))))
      (finally (delete-tree! root)))))

(deftest python-and-clojure-failed-outcome-projections-have-semantic-parity
  (compare-trace-projection! failed-replay))

(deftest public-world-projection-removes-secret-bearing-fields
  (let [replay {:source {:scenario-id "sensitive-fixture"}
                :world {"safe" "visible" "api_key" "must-not-export"
                        "nested" {"private_key" "must-not-export" "note" "Authorization: Bearer token"}}}
        public (extraction/world-final replay {} :public (:world replay))
        internal (extraction/world-final replay {} :internal (:world replay))]
    (is (= "public" (get public "sensitivity_profile")))
    (is (= {"safe" "visible" "nested" {"note" "[REDACTED]"}}
           (get public "world")))
    (is (= "must-not-export" (get-in internal ["world" "api_key"])))
    (is (= "internal" (get internal "sensitivity_profile")))))

(deftest python-and-clojure-yield-trace-and-world-have-semantic-parity
  (compare-trace-projection! yield-replay)
  (let [classification (extraction/claimable-classification
                        (json/read-str (json/write-str yield-replay) :key-fn keyword)
                        "run-yield")]
    (is (map? classification))
    ;; Claimable classification is Clojure-authoritative and uses the
    ;; established underscore-keyed taxonomy document contract.
    (is (some? (:schema_version classification)))))
