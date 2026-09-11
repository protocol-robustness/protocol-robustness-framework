(ns resolver-sim.replay-core
  "Protocol-agnostic CLI for PRF core operations: bundle verification,
   canonical hashing, scenario loading, and report generation.
   NO Sew protocol, NO database, NO gRPC.

   Usage:
     java -jar prf-runner-core.jar --verify-bundle bundle-root.json
     java -jar prf-runner-core.jar --compute-hash bundle-root.json
     java -jar prf-runner-core.jar --load-scenario scenario.edn"
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.data.json :as json]
            [resolver-sim.run.bundle-root :as br])
  (:gen-class))

(def cli-options
  [["-v" "--verify-bundle PATH" "Verify bundle root JSON hash"]
   ["-c" "--compute-hash PATH"  "Compute JSON-canonical hash of bundle"]
   ["-h" "--help"]])

(defn- verify-bundle
  "Load a bundle-root.json and verify its :bundle/hash matches computed."
  [path]
  (let [bundle (json/read-str (slurp (io/file path)) :key-fn keyword)
        recorded (:bundle/hash bundle)
        computed (br/compute-json-hash bundle)]
    (println (str "Bundle: " path))
    (println (str "  Recorded hash: " recorded))
    (println (str "  Computed hash: " computed))
    (if (= recorded computed)
      (do (println "  VERIFIED: hashes match") 0)
      (do (println "  FAILURE: hashes do NOT match") 1))))

(defn- compute-hash
  "Load a bundle-root.json and print its JSON-canonical hash."
  [path]
  (let [bundle (json/read-str (slurp (io/file path)) :key-fn keyword)
        hash (br/compute-json-hash bundle)]
    (println (str "JSON-canonical hash: " hash))
    0))

(defn- print-help [summary]
  (println "PRF Core Runner — protocol-agnostic PRF operations")
  (println)
  (println "Usage: java -jar prf-runner-core.jar [options]")
  (println summary)
  (println)
  (println "Examples:")
  (println "  Verify a bundle root:")
  (println "    java -jar prf-runner-core.jar --verify-bundle bundle-root.json")
  (println)
  (println "  Compute canonical hash:")
  (println "    java -jar prf-runner-core.jar --compute-hash bundle-root.json")
  0)

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors (do (run! println errors) (System/exit 1))
      (:help options) (System/exit (print-help summary))
      (:verify-bundle options) (System/exit (verify-bundle (:verify-bundle options)))
      (:compute-hash options) (System/exit (compute-hash (:compute-hash options)))
      :else (do (println "No action specified. Use --help for usage.") (System/exit 1)))))
