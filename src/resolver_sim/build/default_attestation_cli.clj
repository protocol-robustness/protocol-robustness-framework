(ns resolver-sim.build.default-attestation-cli
  "Operational entrypoint for fail-closed default-build attestations."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [resolver-sim.build.default-attestation :as att]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- usage []
  "Usage: clojure -M -m resolver-sim.build.default-attestation-cli <prf|sew> <jar-path> <bundle-path>")

(defn- smoke! []
  (let [result (shell/sh "bash" "scripts/portability-smoke-test.sh")]
    (when-not (zero? (:exit result))
      (throw (ex-info "Packaged-JAR smoke test failed; build attestation was not emitted"
                      {:reason :packaged-jar-smoke-failed
                       :exit (:exit result)
                       :stdout (:out result)
                       :stderr (:err result)})))
    {:smoke/status :passed
     :smoke/route :native-command-resolution
     :smoke/script "scripts/portability-smoke-test.sh"
     :smoke/output-hash
     (hash-ref/sha256-ref
      (canonical/domain-hash "DEFAULT_BUILD_SMOKE_OUTPUT_V1"
                             (str (:out result) (:err result))))
     :smoke/log-content (str (:out result) (:err result))}))

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
