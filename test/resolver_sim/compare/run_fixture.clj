(ns resolver-sim.compare.run-fixture
  "Test fixture builder: a minimal completed single-scenario run package
   laid out on disk the way canonical scenario runs persist it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.run.package-index :as package-index]))

(defn write!
  [f s]
  (io/make-parents (str f))
  (spit f s))

(defn build-run-root!
  "Create a minimal completed single-scenario run package in a temp
   directory and return the root path.  Callers should delete it with
   delete-tree! in a finally block.

   Options:
     :scenario-results  — vector persisted as :run/scenario-results (string-keyed maps)
     :semantic-outcome  — verdict-policy semantic outcome, or nil to omit the policy
     :bundle-root-hash  — bundle root hash string (default \"bundle-root\")
     :run-id            — run id string (default \"run-id\")"
  [& {:keys [scenario-results semantic-outcome bundle-root-hash run-id]
      :or   {scenario-results []
             bundle-root-hash "bundle-root"
             run-id "run-id"}}]
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "prf-run-fixture" (make-array java.nio.file.attribute.FileAttribute 0)))
        idx-path (io/file root "manifest/run-package-index.json")
        bundle   {:run-id        run-id
                  :scenario-id   "scenario-1"
                  :execution-id  (str "exec-" run-id)
                  :run-type      :single-scenario
                  :bundle-root-hash bundle-root-hash
                  :artifacts     {:input-snapshot
                                  {:ref "input/input.edn" :sha256 "sha256:1111" :bytes 4}
                                  :run-finalization
                                  {:ref "evidence/finalizations/run/evidence-finalization.json"
                                   :sha256 "sha256:2222" :bytes 4}}}
        _         (package-index/write! idx-path bundle)
        sha       (str "sha256:" (lifecycle/sha256-file idx-path))
        bytes     (.length idx-path)]
      (write! (io/file root "completion.json")
              (json/write-str
               {:schema_version "run-completion.v1"
                :run_id run-id
                :lifecycle_status "completed"
                :run_type "scenario"
                :run_package_index_ref "manifest/run-package-index.json"
                :run_package_index_sha256 sha
                :run_package_index_bytes bytes}))
      (write! (io/file root "evidence/finalizations/run/evidence-finalization.json")
              (json/write-str
               {"evidence" {"declared-evidence-hashes" ["sha256:aaa" "sha256:bbb"]
                            "scenario-finalizations"
                            [{"scenario-id" "scenario-1"
                              "finalization" {"sha256" "sha256:2222"}}]}}))
      (write! (io/file root "input/input.edn") "{:x 1}")
      (when (seq scenario-results)
        (write! (io/file root "scenarios/scenario-1/execution/replay-output.json")
                (json/write-str {"run/scenario-results" scenario-results})))
      (when semantic-outcome
        (write! (io/file root "manifest/verdict-policy.json")
                (json/write-str {"verdict" {"semantic_outcome" semantic-outcome}})))
      root))

(defn delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (io/delete-file f true)))

(defn scenario-result
  "Build one string-keyed :run/scenario-results entry."
  [& {:keys [scenario-id outcome invariant-status evidence-root]
      :or   {scenario-id "scenario-1" outcome "ok" invariant-status "pass"
             evidence-root "sha256:aaaa"}}]
  {"scenario/id" scenario-id
   "outcome" outcome
   "invariant-results" [{"invariant/id" "conservation" "status" invariant-status}]
   "scenario/evidence-root" evidence-root})
