(ns resolver-sim.build.default-attestation-cli
  "Operational entrypoint for fail-closed default-build attestations."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [resolver-sim.build.default-attestation :as att]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private smoke-required-assertions
  "The explicit PASS assertions the packaged-JAR smoke script must emit. These
   are the expected smoke assertions referenced by Phase 1.2: an exit code of 0
   alone is not sufficient evidence — the assertions must appear in the captured
   output, otherwise the run is treated as non-passing."
  ["does not advertise Sew commands"
   "without CWD scatter"
   "final registry and validation report hashes"
   "verifies completed scenario evidence-chain and benchmark assurance bundles"
   "resolves every declared native command; external wrappers are checked by bb-task parity"])

(defn- usage []
  "Usage: clojure -M -m resolver-sim.build.default-attestation-cli <prf|sew> <jar-path> <bundle-path>")

(defn smoke-output-assertions-hold?
  "True when the packaged-JAR smoke output contains every required PASS
   assertion. The expected smoke assertions remain required even when the
   subprocess exits 0 (Phase 1.2): log collection must not turn an exit code
   of zero into a pass."
  [output]
  (every? #(str/includes? (str output) %) smoke-required-assertions))

(defn- smoke! []
  (let [result (shell/sh "bash" "scripts/portability-smoke-test.sh")
        output (str (:out result) (:err result))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Packaged-JAR smoke test failed; build attestation was not emitted"
                      {:reason :packaged-jar-smoke-failed
                       :exit (:exit result)
                       :stdout (:out result)
                       :stderr (:err result)})))
    (when-not (smoke-output-assertions-hold? output)
      (throw (ex-info "Packaged-JAR smoke exited 0 but did not emit the required PASS assertions; build attestation was not emitted"
                      {:reason :packaged-jar-smoke-assertions-missing
                       :output output})))
    {:smoke/status :passed
     :smoke/route :native-command-resolution
     :smoke/script "scripts/portability-smoke-test.sh"
     :smoke/output-hash
     (hash-ref/sha256-ref
      (canonical/domain-hash "DEFAULT_BUILD_SMOKE_OUTPUT_V1" output))
     :smoke/log-content output}))

(defn- emit! [variant jar-path bundle-path smoke]
  (let [log-file (str bundle-path ".smoke.log")
        _ (spit log-file (:smoke/log-content smoke))
        smoke (assoc (dissoc smoke :smoke/log-content)
                     :smoke/log {:path (.getName (java.io.File. log-file))
                                 :sha256 (hash-ref/sha256-ref-file log-file)})
        definition (att/default-build-definition "." variant)
        attestation (att/build-attestation
                     {:definition definition
                      :jar-file jar-path
                      :smoke smoke
                      :builder-identity {:builder/id (System/getProperty "user.name")}})
        bundle (att/build-attestation-bundle
                {:definition definition :attestation attestation})
        parent (.getParentFile (io/file bundle-path))]
    (when parent (.mkdirs parent))
    (att/write-bundle! bundle bundle-path)
    (println "Wrote default build attestation bundle:" bundle-path)
    (println "Bundle root:" (:bundle/root-hash bundle))))

(defn -main [& args]
  (let [[variant & rest] args]
    (case variant
      "all"
      (let [[prf-jar sew-jar output-dir & extra] rest]
        (when (or (seq extra) (nil? output-dir))
          (throw (ex-info (str (usage) "\nOr: ... all <prf-jar> <sew-jar> <output-dir>")
                          {:reason :invalid-default-build-attestation-arguments})))
        (let [smoke (assoc (smoke!) :smoke/shared-gate? true)]
          (emit! :prf prf-jar (str (io/file output-dir "default-build-attestation-prf.edn")) smoke)
          (emit! :sew sew-jar (str (io/file output-dir "default-build-attestation-sew.edn")) smoke)))

      (let [[jar-path bundle-path & extra] rest]
        (when (or (seq extra) (nil? bundle-path))
          (throw (ex-info (usage) {:reason :invalid-default-build-attestation-arguments})))
        (emit! (keyword variant) jar-path bundle-path (smoke!))))))
