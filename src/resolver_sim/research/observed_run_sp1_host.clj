(ns resolver-sim.research.observed-run-sp1-host
  "One real SP1 prove-then-verify command for the frozen observed-run statement.

   The input bundle carries authoritative observed-run and run-assurance
   artifacts plus separate prover/verifier configuration. No constituent
   statement roots are accepted from the caller."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.observed-run-proof-statement :as statement]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.verification.basis :as verification-basis])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private host-manifest "coprocessor/sp1-script/Cargo.toml")
(def ^:private host-bin "observed-run-sp1-prove")
(def ^:private host-binary-path "coprocessor/target/release/observed-run-sp1-prove")
(defonce ^:private compiled-host-binary (atom nil))

(defn- bytes-hex [^bytes bytes]
  (.formatHex (HexFormat/of) bytes))

(defn- sha256-ref-bytes [^bytes bytes]
  (hash-ref/sha256-ref (bytes-hex (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- domain-root [domain body]
  (hash-ref/sha256-ref (hc/domain-hash domain body)))

(defn- write-bytes! [^Path path ^bytes bytes]
  (when-let [parent (.getParent path)] (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
  (Files/write path bytes (make-array java.nio.file.OpenOption 0))
  path)

(defn- write-edn! [^Path path value]
  (write-bytes! path (.getBytes (pr-str value) StandardCharsets/UTF_8)))

(defn- ensure-host-binary! []
  (or @compiled-host-binary
      (locking compiled-host-binary
        (or @compiled-host-binary
            (let [override (System/getenv "PRF_OBSERVED_RUN_SP1_HOST_BIN")
                  executable (if (str/blank? override)
                               (io/file host-binary-path)
                               (io/file override))]
              (when (str/blank? override)
                (let [command ["cargo" "build" "--release" "--manifest-path" host-manifest
                               "--bin" host-bin]
                      builder (doto (ProcessBuilder. ^java.util.List command)
                                (.directory (io/file "."))
                                (.redirectErrorStream true))
                      env (.environment builder)
                      prior-go-flags (.get env "GOFLAGS")
                      _ (.put env "GOFLAGS"
                              (str (when-not (str/blank? prior-go-flags)
                                     (str prior-go-flags " "))
                                   "-buildvcs=false"))
                      process (.start builder)
                      output (slurp (.getInputStream process))
                      status (.waitFor process)]
                  (when-not (zero? status)
                    (throw (ex-info "SP1 host build failed"
                                    {:exit status :diagnostic output})))))
              (when-not (and (.isFile executable) (.canExecute executable))
                (throw (ex-info "Compiled SP1 host executable is unavailable"
                                {:path (.getPath executable)})))
              (let [path (.getAbsolutePath executable)]
                (reset! compiled-host-binary path)
                path))))))

(defn- invoke-host! [mode input-path proof-path metadata-path verification-path]
  (let [command (cond-> [(ensure-host-binary!) "--mode" (name mode)
                         "--input" (str input-path) "--proof" (str proof-path)
                         "--metadata" (str metadata-path)]
                  verification-path (into ["--verification" (str verification-path)]))
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                         (.directory (io/file "."))
                         (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        status (.waitFor process)]
    (when-not (zero? status)
      (throw (ex-info "SP1 host command failed"
                      {:mode mode :exit status :diagnostic output})))
    output))

(defn- read-json [^Path path]
  (json/read-str (slurp (.toFile path))))

(defn- persist-proof-receipt! [^Path out receipt]
  (write-edn! (.resolve out "research-proof-generation-receipt.edn") receipt))

(defn- proof-roots [metadata proof-bytes]
  (let [elf-root (get metadata "proof_program_elf_sha256")
        vkey (get metadata "proof_program_vkey")
        vkey-root (str "sha256:" (str/lower-case (str/replace vkey #"^0x" "")))
        program-root (domain-root :research-proof-program-identity-v1
                                  {:artifact/schema "research-proof-program-identity.v1"
                                   :proof-program/id (get metadata "proof_program")
                                   :proof-program/elf-root elf-root
                                   :proof-program/vkey-root vkey-root})
        system-root (domain-root :research-proof-system-identity-v1
                                 {:artifact/schema "research-proof-system-identity.v1"
                                  :proof-system/id :sp1
                                  :proof-system/version "6.3.1"
                                  :proof-system/proof-kind :core
                                  :proof-system/encoding (get metadata "proof_encoding")})
        public-bytes (.parseHex (HexFormat/of)
                                (str/replace (get metadata "public_values_hex") #"^0x" ""))]
    (when-not (and (hash-ref/valid-sha256-ref? elf-root)
                   (re-matches #"^(0x)?[0-9a-fA-F]{64}$" vkey)
                   (= 384 (alength ^bytes public-bytes)))
      (throw (ex-info "SP1 host returned malformed ELF/VKey/public-values identities" {})))
    {:program-root program-root
     :system-root system-root
     :public-values-root (sha256-ref-bytes public-bytes)
     :proof-root (sha256-ref-bytes proof-bytes)
     :elf-root elf-root :vkey-root vkey-root}))

(defn run-proof-host!
  "Generate a real proof, persist its generated-unverified receipt, invoke the
   compiled verifier on that proof, then create verification evidence.

   Input keys: `:observed-run-evidence`, `:run-assurance-evidence`, and
   `:proof-host-config` with `:prover-participation`, `:verifier-reference`,
   `:verifier-registry-extension-map`, and `:verification-cutpoint`."
  [{:keys [observed-run-evidence run-assurance-evidence proof-host-config output-dir]}]
  (let [{:keys [prover-participation verifier-reference
                verifier-registry-extension-map verification-cutpoint]}
        proof-host-config
        statement-artifact (statement/derive-observed-run-proof-statement-v1
                            observed-run-evidence run-assurance-evidence)
        out (.toAbsolutePath (.normalize (.toPath (io/file output-dir))))
        _ (Files/createDirectories out (make-array java.nio.file.attribute.FileAttribute 0))
        temp (.toAbsolutePath (Files/createTempDirectory "prf-observed-run-sp1-" (make-array java.nio.file.attribute.FileAttribute 0)))
        input-path (.resolve temp "authoritative-witness.json")
        proof-path (.resolve out "observed-run.sp1.bin")
        metadata-path (.resolve out "observed-run.sp1-metadata.json")
        verification-path (.resolve temp "verification.json")
        _ (doseq [path [temp input-path verification-path]]
            (.deleteOnExit (.toFile path)))
        witness (statement/sp1-input observed-run-evidence run-assurance-evidence)
        _ (when-not (and (:valid? (participation/validate-participation prover-participation))
                         (hash-ref/valid-sha256-ref? verifier-reference)
                         (map? verifier-registry-extension-map)
                         (integer? verification-cutpoint)
                         (not (neg? verification-cutpoint)))
            (throw (ex-info "Invalid proof-host participant or verifier configuration" {})))
        _ (write-bytes! input-path (.getBytes (json/write-str witness) StandardCharsets/UTF_8))
        _ (invoke-host! :prove input-path proof-path metadata-path nil)
        metadata (read-json metadata-path)
        proof-bytes (Files/readAllBytes proof-path)
        roots (proof-roots metadata proof-bytes)
        registry-root (verification-basis/verifier-registry-root
                       verifier-registry-extension-map)
        _ (when-not (and (hash-ref/valid-sha256-ref? verifier-reference)
                         (integer? verification-cutpoint) (not (neg? verification-cutpoint))
                         (hash-ref/valid-sha256-ref? (:runner-participation/root prover-participation))
                         (= "research-observed-run-sp1-proof-artifact.v1" (get metadata "schema"))
                         (= statement/public-values-schema
                            (get metadata "public_values_schema"))
                         (= (get metadata "proof_sha256") (:proof-root roots))
                         (= (get metadata "public_values_sha256")
                            (sha256-ref-bytes (.parseHex (HexFormat/of)
                                                         (str/replace (get metadata "public_values_hex") #"^0x" "")))))
            (throw (ex-info "SP1 proof metadata/content binding failed" {})))
        run-assurance-root (:research-run-assurance-evidence/root run-assurance-evidence)
        generation-receipt
        (proof/build-receipt
         {:assured-job-root (:research-execution-job-assurance/root run-assurance-evidence)
          :resolved-dependency-set-root (:resolved-research-dependency-set/root observed-run-evidence)
          :run-assurance-evidence-root run-assurance-root
          :semantic-result-root (:semantic-result/root run-assurance-evidence)
          :proof-system-identity-root (:system-root roots)
          :proof-program-identity-root (:program-root roots)
          :public-values-input-root (:public-values-root roots)
          :proof-artifact-root (:proof-root roots)
          :prover-participation prover-participation
          :execution-environment-root (:research-execution-environment/root observed-run-evidence)
          :verifier-registry-root registry-root})
        _ (persist-proof-receipt! out generation-receipt)
        _ (when-not (proof/verifier-registry-matches?
                     generation-receipt verifier-registry-extension-map)
            (throw (ex-info "Verifier registry selection differs from generation receipt" {})))
        verify-log (invoke-host! :verify input-path proof-path metadata-path verification-path)
        verified (read-json verification-path)
        _ (when-not (and (= "research-observed-run-sp1-verification.v1"
                            (get verified "schema"))
                         (= "verified" (get verified "verification"))
                         (= (get metadata "proof_sha256") (get verified "proof_sha256"))
                         (= (get metadata "public_values_sha256")
                            (get verified "public_values_sha256"))
                         (= (get metadata "proof_program_elf_sha256")
                            (get verified "proof_program_elf_sha256"))
                         (= (get metadata "proof_program_vkey")
                            (get verified "proof_program_vkey")))
            (throw (ex-info "SP1 verifier did not confirm the exact generated artifact"
                            {:host-output verify-log})))
        verification-root
        (domain-root :research-sp1-proof-verification-v1
                     {:artifact/schema "research-sp1-proof-verification.v1"
                      :research-proof-generation-receipt/root
                      (:research-proof-generation-receipt/root generation-receipt)
                      :proof-artifact/root (:proof-root roots)
                      :public-values/root (:public-values-root roots)
                      :proof-program/identity-root (:program-root roots)
                      :verifier-registry/root registry-root
                      :verification/status :verified})
        verification-evidence
        (control/build-proof-verification
         {:proof-receipt generation-receipt :verification-root verification-root
          :verifier-reference verifier-reference :verifier-registry-root registry-root
          :verification-cutpoint verification-cutpoint})]
    (write-edn! (.resolve out "research-proof-verification-evidence.edn")
                verification-evidence)
    (write-edn! (.resolve out "observed-run-sp1-proof-bundle.edn")
                {:statement statement-artifact
                 :proof-system/identity-root (:system-root roots)
                 :proof-program/identity-root (:program-root roots)
                 :proof-program/elf-root (:elf-root roots)
                 :proof-program/vkey-root (:vkey-root roots)
                 :proof/public-values-root (:public-values-root roots)
                 :proof/artifact-root (:proof-root roots)
                 :proof/public-values-hex (get metadata "public_values_hex")
                 :proof/metadata metadata
                 :proof-file (str (.getFileName proof-path))
                 :generation-receipt generation-receipt
                 :verification-evidence verification-evidence})
    {:proof-file (str proof-path)
     :metadata metadata
     :generation-receipt generation-receipt
     :verification-evidence verification-evidence}))

(defn- parse-args [args]
  (loop [args args, parsed {}]
    (if (empty? args)
      parsed
      (let [[flag value & more] args]
        (when-not (and value (str/starts-with? flag "--"))
          (throw (ex-info "Expected --input and --output-dir" {:args args})))
        (recur more (assoc parsed (keyword (subs flag 2)) value))))))

(defn -main [& args]
  (let [{:keys [input output-dir]} (parse-args args)]
    (when-not (and input output-dir)
      (throw (ex-info "Usage: -m resolver-sim.research.observed-run-sp1-host --input <bundle.edn> --output-dir <dir>" {})))
    (let [bundle (edn/read-string (slurp input))
          result (run-proof-host! (assoc bundle :output-dir output-dir))]
      (println (pr-str (select-keys result [:proof-file :generation-receipt :verification-evidence]))))))
