(ns resolver-sim.build.default-attestation
  "Versioned, fail-closed evidence for the supported PRF distribution builds.

   This is release-build evidence only. It has no authority over protocol state,
   custody accounting, or live command execution. A signature can be layered on
   later; :verified? here means the bundled inputs, JAR bytes, and required
   packaged-JAR smoke result agree, not that a trusted release signer approved it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.run.release-attestation :as release]))

(def definition-schema "default-build.v1")
(def attestation-schema "default-build-attestation.v1")
(def bundle-schema "default-build-attestation-bundle.v1")

(def ^:private supported-variants #{:prf :sew})

(def smoke-required-assertions
  "The explicit PASS assertions the packaged-JAR smoke script must emit. These
   are the expected smoke assertions (Phase 1.2): an exit code of 0 alone is not
   sufficient — the assertions must appear in the captured output. Verification
   requires them in the committed smoke log, not merely a matching log hash."
  ["does not advertise Sew commands"
   "without CWD scatter"
   "final registry and validation report hashes"
   "verifies completed scenario evidence-chain and benchmark assurance bundles"
   "resolves every declared native command; external wrappers are checked by bb-task parity"])

(defn smoke-output-assertions-hold?
  "True when a packaged-JAR smoke output contains every required PASS assertion."
  [output]
  (every? #(str/includes? (str output) %) smoke-required-assertions))

(defn- fail! [message reason data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- sha256-ref [value]
  (hash-ref/sha256-ref (canonical/domain-hash "DEFAULT_BUILD_ATTESTATION_V1" value)))

(defn- source-input-paths [variant]
  (cond-> ["deps.edn"
           "bb.edn"
           "scripts/build.clj"
           "scripts/portability-smoke-test.sh"
           "resources/prf/commands/registry.edn"]
    (= variant :sew) (conj "resources/prf/sew-release-corpus.edn")))

(defn- jar-name [variant]
  (case variant
    :prf "target/prf.jar"
    :sew "target/prf-runner-sew-0.1.0-uber.jar"))

(defn definition-hash [definition]
  (sha256-ref (dissoc definition :default-build/hash)))

(defn default-build-definition
  "Build the canonical definition for one supported distribution.

   The definition hashes the concrete build inputs rather than a VCS checkout
   identifier, so it remains portable to source archives. The build command is
   declarative evidence; this function does not execute it."
  [project-root variant]
  (when-not (supported-variants variant)
    (fail! "Unsupported default build variant" :unsupported-default-build-variant
           {:variant variant :supported supported-variants}))
  (let [root (io/file project-root)
        inputs (mapv (fn [path]
                       (let [file (io/file root path)
                             sha (hash-ref/sha256-ref-file (.getPath file))]
                         (when-not sha
                           (fail! "Default build input is missing"
                                  :default-build-input-missing
                                  {:path path :project-root (.getPath root)}))
                         {:path path :sha256 sha}))
                     (source-input-paths variant))
        definition {:schema-version definition-schema
                    :build/id (keyword (str "default-build/" (name variant)))
                    :build/variant variant
                    :build/command ["clojure" "-T:build" "uberjar"
                                    ":variant" (name variant)]
                    :build/artifact {:path (jar-name variant)
                                     :entrypoint (if (= variant :prf)
                                                   "resolver-sim.cli-bootstrap"
                                                   "clojure.main")}
                    :build/inputs inputs
                    :build/packaged-jar-smoke
                    {:required? true
                     :script "scripts/portability-smoke-test.sh"
                     :route :native-command-resolution
                     :external-wrapper-policy :excluded-and-bb-parity-checked}}]
    (assoc definition :default-build/hash (definition-hash definition))))

(defn valid-definition? [definition]
  (and (= definition-schema (:schema-version definition))
       (supported-variants (:build/variant definition))
       (vector? (:build/command definition))
       (seq (:build/inputs definition))
       (every? #(and (string? (:path %))
                     (hash-ref/valid-sha256-ref? (:sha256 %)))
               (:build/inputs definition))
       (= (:default-build/hash definition) (definition-hash definition))))

(defn- validate-smoke!
  "Fail-closed construction guard for packaged-JAR smoke evidence.

   When the definition requires packaged-JAR smoke, a nil smoke is rejected at
   construction. A supplied smoke must assert a passed native-command-resolution
   run, and any referenced log file must already be captured with a matching
   sha256."
  [jar smoke required?]
  (when required?
    (when (nil? smoke)
      (fail! "Required packaged-JAR smoke evidence is missing"
             :required-default-build-smoke-missing {})))
  (when (some? smoke)
    (when-not (and (= :passed (:smoke/status smoke))
                   (= :native-command-resolution (:smoke/route smoke)))
      (fail! "Smoke evidence does not assert a passed native-command-resolution run"
             :invalid-default-build-smoke
             {:smoke smoke}))
    (when-let [log (:smoke/log smoke)]
      (let [file (io/file (.getParentFile jar) (:path log))
            actual (hash-ref/sha256-ref-file (.getPath file))]
        (when (or (not (hash-ref/valid-sha256-ref? (:sha256 log)))
                  (not= (:sha256 log) actual))
          (fail! "Smoke log evidence does not match the captured log file"
                 :default-build-smoke-log-mismatch
                 {:path (:path log)
                  :declared (:sha256 log)
                  :actual actual}))))))

(defn build-attestation
  "Bind an actual JAR and a successful packaged-JAR smoke result to a valid
   default-build definition. The caller supplies the smoke result because the
   smoke is intentionally an operational subprocess concern, not library code.

   Required smoke shape: {:smoke/status :passed :smoke/route
   :native-command-resolution}. Construction is fail-closed: a supplied smoke
   that does not assert a passed native-command-resolution run, or whose log
   reference does not match the captured log file, is rejected at build time."
  [{:keys [definition jar-file smoke builder-identity]}]
  (when-not (valid-definition? definition)
    (fail! "Cannot attest an invalid default build definition"
           :invalid-default-build-definition {}))
  (let [jar (io/file jar-file)
        jar-sha (hash-ref/sha256-ref-file (.getPath jar))]
    (when-not jar-sha
      (fail! "Cannot attest a missing JAR" :default-build-jar-missing
             {:jar-file (str jar-file)}))
    (validate-smoke! jar smoke (get-in definition [:build/packaged-jar-smoke :required?]))
    (let [attestation {:schema-version attestation-schema
                       :attestation/build-definition-hash (:default-build/hash definition)
                       :attestation/artifact {:path (.getName jar)
                                              :sha256 jar-sha
                                              :entrypoint (get-in definition [:build/artifact :entrypoint])}
                       :attestation/smoke smoke
                       :attestation/builder-identity builder-identity}]
      (assoc attestation :attestation/hash
             (sha256-ref (dissoc attestation :attestation/hash))))))

(defn attestation-hash-valid? [attestation]
  (= (:attestation/hash attestation)
     (sha256-ref (dissoc attestation :attestation/hash))))

(defn build-attestation-bundle
  "Create a portable in-memory bundle. Persisting/signing it is intentionally
   separate from construction, so an unsigned bundle cannot be mistaken for an
   authorised release."
  [{:keys [definition attestation]}]
  (when-not (and (valid-definition? definition)
                 (attestation-hash-valid? attestation)
                 (= (:default-build/hash definition)
                    (:attestation/build-definition-hash attestation)))
    (fail! "Cannot bundle inconsistent default build evidence"
           :inconsistent-default-build-attestation {}))
  (let [bundle {:schema-version bundle-schema
                :bundle/definition definition
                :bundle/attestation attestation}]
    (assoc bundle :bundle/root-hash (sha256-ref (dissoc bundle :bundle/root-hash)))))

(defn release-payload-for-bundle
  "Build the release payload that a release authority signs for this immutable
   build-evidence bundle. The authorization is intentionally excluded from the
   bundle root to avoid a signature/root circular dependency."
  [bundle distribution release-metadata]
  (release/build-payload
   {:distribution distribution
    :implementation {:build-attestation-bundle-root (:bundle/root-hash bundle)
                     :artifact (get-in bundle [:bundle/attestation :attestation/artifact])}
    :release release-metadata}))

(defn attach-release-authorization
  "Attach signatures over a payload that explicitly binds this bundle root."
  [bundle payload signatures]
  (when-not (= (:bundle/root-hash bundle)
               (get-in payload [:implementation :build-attestation-bundle-root]))
    (fail! "Release payload is not bound to this build bundle"
           :release-payload-build-bundle-mismatch {}))
  (assoc bundle :bundle/release-authorization
         {:payload payload :signatures (vec signatures)}))

(defn verify-bundle
  "Verify portable build integrity. `artifact-root` is the trusted directory in
   which the JAR named by the attestation is expected. Missing smoke evidence is
   a failure, not a warning: structural handler resolution alone is insufficient
   evidence of packaged-JAR availability."
  ([bundle artifact-root] (verify-bundle bundle artifact-root nil))
  ([bundle artifact-root {:keys [distribution trust-policy require-release-authorization?]}]
   (let [definition (:bundle/definition bundle)
         attestation (:bundle/attestation bundle)
         artifact (:attestation/artifact attestation)
         jar (io/file artifact-root (:path artifact))
         checks [{:check/id :bundle-schema
                  :check/status (if (= bundle-schema (:schema-version bundle)) :pass :fail)}
                 {:check/id :bundle-root
                  :check/status (if (= (:bundle/root-hash bundle)
                                       (sha256-ref (dissoc bundle :bundle/root-hash :bundle/release-authorization))) :pass :fail)}
                 {:check/id :definition
                  :check/status (if (valid-definition? definition) :pass :fail)}
                 {:check/id :attestation-hash
                  :check/status (if (attestation-hash-valid? attestation) :pass :fail)}
                 {:check/id :definition-binding
                  :check/status (if (= (:default-build/hash definition)
                                       (:attestation/build-definition-hash attestation)) :pass :fail)}
                 {:check/id :jar-bytes
                  :check/status (if (= (:sha256 artifact)
                                       (hash-ref/sha256-ref-file (.getPath jar))) :pass :fail)}
                 {:check/id :packaged-jar-smoke
                  :check/status (if (and (= :passed (get-in attestation [:attestation/smoke :smoke/status]))
                                         (= :native-command-resolution
                                            (get-in attestation [:attestation/smoke :smoke/route])))
                                  :pass
                                  :fail)}
                 {:check/id :packaged-jar-smoke-log
                  :check/status (let [log (get-in attestation [:attestation/smoke :smoke/log])
                                      file (when (:path log) (io/file artifact-root (:path log)))
                                      file-exists? (and file (.isFile file))
                                      content (when file-exists? (slurp file))]
                                  (if (and (hash-ref/valid-sha256-ref? (:sha256 log))
                                           (= (:sha256 log)
                                              (when file (hash-ref/sha256-ref-file (.getPath file))))
                                           (smoke-output-assertions-hold? content))
                                    :pass
                                    :fail))}]
         authorization (:bundle/release-authorization bundle)
         release-check (when (or require-release-authorization? trust-policy)
                         (let [result (if (and authorization trust-policy distribution)
                                        (release/verify-authorization
                                         (:payload authorization) (:signatures authorization)
                                         distribution trust-policy)
                                        {:authorization {:status :missing-or-insufficient}})]
                           {:check/id :release-authorization
                            :check/status (if (= :authorized (get-in result [:authorization :status])) :pass :fail)
                            :result result}))
         checks (cond-> checks release-check (conj release-check))
         verified? (every? #(= :pass (:check/status %)) checks)]
     {:verified? verified?
      :classification (cond
                        (and verified? release-check) :release-authorized-build
                        verified? :integrity-verified-build
                        :else :invalid-build-evidence)
      :checks checks})))

(defn write-bundle! [bundle destination]
  (spit (io/file destination) (pr-str bundle))
  destination)

(defn read-bundle [source]
  (edn/read-string (slurp (io/file source))))
