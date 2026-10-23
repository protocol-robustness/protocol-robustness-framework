(ns resolver-sim.research.accounting-arithmetic-sp1-host
  "Real SP1 prove-then-verify path for an assured native accounting partial.

   The caller supplies only the existing rooted work and execution. Statement
   roots are derived after validating that execution against the work."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.coprocessor.source :as coprocessor]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.accounting-proof-statement :as statement]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.verification.basis :as verification-basis])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.util.concurrent TimeUnit]))

(defn- host-manifest []
  (str (coprocessor/root) "/accounting-arithmetic-sp1-host/Cargo.toml"))
(def ^:private host-bin "accounting-arithmetic-sp1-host")
(defn- host-binary-path []
  (str (coprocessor/root) "/target/release/accounting-arithmetic-sp1-host"))
(defonce ^:private compiled-host-binary (atom nil))

(defn- configured-host-command-timeout-ms []
  (if-let [value (System/getenv "PRF_ACCOUNTING_SP1_HOST_TIMEOUT_MS")]
    (try
      (Long/parseLong value)
      (catch NumberFormatException e
        (throw (ex-info "PRF_ACCOUNTING_SP1_HOST_TIMEOUT_MS must be an integer"
                        {:value value} e))))
    600000))

(def ^:dynamic *host-command-timeout-ms*
  "Runtime-only deadline for the external SP1 host process. A timeout never
   changes proof identity; it leaves the persisted proof bundle retryable."
  (configured-host-command-timeout-ms))

(defn- sha256-ref-bytes [^bytes bytes]
  (hash-ref/sha256-ref
   (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- domain-root [domain body]
  (hash-ref/sha256-ref (hc/domain-hash domain body)))

(defn- write-bytes! [^Path path ^bytes bytes]
  (when-let [parent (.getParent path)]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
  (Files/write path bytes (make-array java.nio.file.OpenOption 0))
  path)

(defn- write-edn! [^Path path value]
  (write-bytes! path (.getBytes (pr-str value) StandardCharsets/UTF_8)))

(defn- ensure-host-binary! []
  (or @compiled-host-binary
      (locking compiled-host-binary
        (or @compiled-host-binary
            (let [override (System/getenv "PRF_ACCOUNTING_SP1_HOST_BIN")
                  executable (if (str/blank? override) (io/file (host-binary-path)) (io/file override))]
              (when (str/blank? override)
                (let [builder (doto (ProcessBuilder. ^java.util.List
                                     ["cargo" "build" "--release" "--locked"
                                      "--manifest-path" (host-manifest) "--bin" host-bin])
                                (.directory (io/file (coprocessor/root)))
                                (.redirectErrorStream true))
                      env (.environment builder)
                      prior (.get env "GOFLAGS")
                      _ (.put env "GOFLAGS" (str (when-not (str/blank? prior) (str prior " "))
                                                 "-buildvcs=false"))
                      process (.start builder)
                      output (slurp (.getInputStream process))
                      status (.waitFor process)]
                  (when-not (zero? status)
                    (throw (ex-info "Accounting SP1 host build failed"
                                    {:exit status :diagnostic output})))))
              (when-not (and (.isFile executable) (.canExecute executable))
                (throw (ex-info "Compiled accounting SP1 host is unavailable"
                                {:path (.getPath executable)})))
              (let [path (.getAbsolutePath executable)]
                (reset! compiled-host-binary path)
                path))))))

(defn- invoke-host! [mode input proof metadata verification]
  (when-not (and (integer? *host-command-timeout-ms*) (pos? *host-command-timeout-ms*))
    (throw (ex-info "Invalid accounting SP1 host command timeout"
                    {:timeout-ms *host-command-timeout-ms*})))
  (let [command (cond-> [(ensure-host-binary!) "--mode" (name mode)
                         "--input" (str input) "--proof" (str proof)
                         "--metadata" (str metadata)]
                  verification (into ["--verification" (str verification)]))
        output-path (Files/createTempFile "prf-accounting-sp1-host-" ".log"
                                          (make-array java.nio.file.attribute.FileAttribute 0))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (io/file ".")) (.redirectErrorStream true))
        _ (.redirectOutput builder (.toFile output-path))
        process (.start builder)]
    (try
      (if (.waitFor process (long *host-command-timeout-ms*) TimeUnit/MILLISECONDS)
        (let [output (slurp (.toFile output-path))
              status (.exitValue process)]
          (when-not (zero? status)
            (throw (ex-info "Accounting SP1 host command failed"
                            {:mode mode :exit status :diagnostic output})))
          output)
        (do
          (doseq [child (iterator-seq (.iterator (.descendants (.toHandle process))))]
            (.destroyForcibly child))
          (.destroyForcibly process)
          (.waitFor process)
          (throw (ex-info "Accounting SP1 host command timed out"
                          {:mode mode :cause :host-command-timeout
                           :timeout-ms *host-command-timeout-ms*
                           :diagnostic (slurp (.toFile output-path))}))))
      (finally
        (Files/deleteIfExists output-path)))))

(defn- read-json [^Path path]
  (json/read-str (slurp (.toFile path))))

(defn- proof-identities [metadata proof-bytes]
  (let [elf-root (get metadata "proof_program_elf_sha256")
        vkey (get metadata "proof_program_vkey")
        vkey-root (str "sha256:" (str/lower-case (str/replace vkey #"^0x" "")))
        program-root (domain-root :research-proof-program-identity-v1
                                  {:artifact/schema "research-proof-program-identity.v1"
                                   :proof-program/id :accounting-arithmetic-sp1-program/v1
                                   :proof-program/elf-root elf-root
                                   :proof-program/vkey-root vkey-root})
        system-root (domain-root :research-proof-system-identity-v1
                                 {:artifact/schema "research-proof-system-identity.v1"
                                  :proof-system/id :sp1 :proof-system/version "6.3.1"
                                  :proof-system/proof-kind :core
                                  :proof-system/encoding (get metadata "proof_encoding")})
        public-bytes (.parseHex (HexFormat/of)
                                (str/replace (get metadata "public_values_hex") #"^0x" ""))]
    (when-not (and (hash-ref/valid-sha256-ref? elf-root)
                   (re-matches #"^(0x)?[0-9a-fA-F]{64}$" vkey)
                   (= 160 (alength ^bytes public-bytes)))
      (throw (ex-info "Malformed accounting SP1 ELF/VKey/public-values metadata" {})))
    {:program-root program-root :system-root system-root :elf-root elf-root
     :vkey-root vkey-root :public-values-bytes public-bytes
     :public-values-root (sha256-ref-bytes public-bytes)
     :proof-root (sha256-ref-bytes proof-bytes)}))

(defn derive-authoritative-proof-input
  "Validate an existing native accounting execution and derive its arithmetic
   statement/witness. The API accepts no caller-supplied partial or root tuple."
  [work execution]
  (when-not (native/valid-work? work)
    (throw (ex-info "Invalid native accounting work for proof generation" {})))
  (native/validate-execution work execution)
  (let [request (:accounting/request work)
        contract (accounting/build-reduction-contract)
        plan (:accounting/partition-plan work)
        partial (:accounting-partial execution)]
    {:statement (statement/derive-statement request contract plan partial)
     :witness (statement/proof-input request contract plan partial)}))

(defn prove-and-verify!
  "Generate and cryptographically verify a proof for one existing assured
   native accounting execution. Inputs contain `:work`, `:execution`, and
   `:proof-host-config`; no constituent roots or partial values are accepted
   separately. This proves the checked-i64 arithmetic statement only."
  [{:keys [work execution proof-host-config output-dir]}]
  (let [{:keys [statement witness]} (derive-authoritative-proof-input work execution)
        {:keys [prover-participation verifier-reference
                verifier-registry-extension-map verification-cutpoint]}
        proof-host-config
        partial (:accounting-partial execution)
        out (.toAbsolutePath (.normalize (.toPath (io/file output-dir))))
        _ (Files/createDirectories out (make-array java.nio.file.attribute.FileAttribute 0))
        temp (.toAbsolutePath (Files/createTempDirectory "prf-accounting-sp1-"
                                                         (make-array java.nio.file.attribute.FileAttribute 0)))
        input-path (.resolve temp "authoritative-accounting-witness.json")
        proof-path (.resolve out "accounting-arithmetic.sp1.bin")
        metadata-path (.resolve out "accounting-arithmetic.sp1-metadata.json")
        verification-path (.resolve temp "verification.json")
        _ (doseq [path [temp input-path verification-path]] (.deleteOnExit (.toFile path)))
        _ (when-not (and (:valid? (participation/validate-participation prover-participation))
                         (hash-ref/valid-sha256-ref? verifier-reference)
                         (map? verifier-registry-extension-map)
                         (integer? verification-cutpoint) (not (neg? verification-cutpoint)))
            (throw (ex-info "Invalid accounting proof-host configuration" {})))
        _ (write-bytes! input-path (.getBytes (json/write-str witness) StandardCharsets/UTF_8))
        _ (invoke-host! :prove input-path proof-path metadata-path nil)
        metadata (read-json metadata-path)
        proof-bytes (Files/readAllBytes proof-path)
        identities (proof-identities metadata proof-bytes)
        registry-root (verification-basis/verifier-registry-root verifier-registry-extension-map)
        _ (when-not (and (= "research-accounting-arithmetic-sp1-proof-artifact.v1"
                            (get metadata "schema"))
                         (= "accounting-arithmetic-roots-160.v1"
                            (get metadata "public_values_schema"))
                         (= (:proof-root identities) (get metadata "proof_sha256"))
                         (= (:public-values-root identities)
                            (sha256-ref-bytes (:public-values-bytes identities))))
            (throw (ex-info "Accounting SP1 proof metadata/content mismatch" {})))
        assurance (:run-assurance-evidence execution)
        generation-receipt
        (proof/build-receipt
         {:assured-job-root (get-in work [:work/assured-job :research-execution-job-assurance/root])
          :resolved-dependency-set-root (get-in work [:work/resolved-dependencies
                                                      :resolved-research-dependency-set/root])
          :run-assurance-evidence-root (:research-run-assurance-evidence/root assurance)
          :semantic-result-root (:semantic-result/root assurance)
          :proof-system-identity-root (:system-root identities)
          :proof-program-identity-root (:program-root identities)
          :public-values-input-root (:public-values-root identities)
          :proof-artifact-root (:proof-root identities)
          :prover-participation prover-participation
          :execution-environment-root (get-in work [:work/execution-environment
                                                    :research-execution-environment/root])
          :verifier-registry-root registry-root})
        _ (when-not (proof/verifier-registry-matches?
                     generation-receipt verifier-registry-extension-map)
            (throw (ex-info "Verifier registry does not match proof receipt" {})))
        _ (write-edn! (.resolve out "research-proof-generation-receipt.edn")
                      generation-receipt)
        _ (invoke-host! :verify input-path proof-path metadata-path verification-path)
        verified (read-json verification-path)
        _ (when-not (and (= "research-accounting-arithmetic-sp1-verification.v1"
                            (get verified "schema"))
                         (= "verified" (get verified "verification"))
                         (= (get metadata "proof_sha256") (get verified "proof_sha256"))
                         (= (get metadata "public_values_sha256")
                            (get verified "public_values_sha256"))
                         (= (get metadata "proof_program_elf_sha256")
                            (get verified "proof_program_elf_sha256"))
                         (= (get metadata "proof_program_vkey")
                            (get verified "proof_program_vkey")))
            (throw (ex-info "SP1 did not verify the exact accounting proof" {})))
        verification-root
        (domain-root :research-sp1-proof-verification-v1
                     {:artifact/schema "research-sp1-proof-verification.v1"
                      :research-proof-generation-receipt/root
                      (:research-proof-generation-receipt/root generation-receipt)
                      :proof-artifact/root (:proof-root identities)
                      :public-values/root (:public-values-root identities)
                      :proof-program/identity-root (:program-root identities)
                      :verifier-registry/root registry-root
                      :verification/status :verified})
        verification-evidence
        (control/build-proof-verification
         {:proof-receipt generation-receipt :verification-root verification-root
          :verifier-reference verifier-reference :verifier-registry-root registry-root
          :verification-cutpoint verification-cutpoint})
        bundle {:artifact/schema "research-accounting-arithmetic-sp1-proof-bundle.v1"
                :research-accounting-arithmetic-statement statement
                :research-native-accounting-work/root (:research-native-accounting-work/root work)
                :research-native-accounting-run/root
                (native/execution-root execution)
                :accounting-partial-result/root (:accounting-partial-result/root partial)
                :proof-system/identity-root (:system-root identities)
                :proof-program/identity-root (:program-root identities)
                :proof-program/elf-root (:elf-root identities)
                :proof-program/vkey-root (:vkey-root identities)
                :proof/public-values-root (:public-values-root identities)
                :proof/artifact-root (:proof-root identities)
                :proof/metadata metadata
                :proof/file (str (.getFileName proof-path))
                :research-proof-generation-receipt generation-receipt
                :research-proof-verification-evidence verification-evidence}]
    (write-edn! (.resolve out "research-proof-verification-evidence.edn") verification-evidence)
    (write-edn! (.resolve out "accounting-arithmetic-sp1-proof-bundle.edn") bundle)
    {:proof-file (str proof-path) :metadata metadata
     :generation-receipt generation-receipt
     :verification-evidence verification-evidence}))

(defn verify-existing-proof!
  "Independently verify an existing arithmetic proof for this exact native
   execution envelope. Unlike proof generation, the coordinator path performs
   structural execution validation only; the guest establishes the sum.

   This API consumes authoritative work/execution, the existing generation
   receipt, and proof files. It accepts no public roots from the caller."
  [{:keys [work execution proof-file metadata-file generation-receipt
           verifier-participation verifier-reference
           verifier-registry-extension-map verification-cutpoint]}]
  (native/validate-execution-lineage work execution)
  (let [request (:accounting/request work)
        plan (:accounting/partition-plan work)
        contract (accounting/build-reduction-contract)
        partial (:accounting-partial execution)
        projection (statement/derive-commitment-projection request contract plan partial)
        witness (statement/proof-input-for-verification request contract plan partial)
        temp (.toAbsolutePath (Files/createTempDirectory "prf-accounting-verify-"
                                                         (make-array java.nio.file.attribute.FileAttribute 0)))
        input-path (.resolve temp "authoritative-accounting-witness.json")
        verified-path (.resolve temp "verification.json")
        proof-path (.toAbsolutePath (.normalize (.toPath (io/file proof-file))))
        metadata-path (.toAbsolutePath (.normalize (.toPath (io/file metadata-file))))
        _ (write-bytes! input-path (.getBytes (json/write-str witness) StandardCharsets/UTF_8))
        metadata (read-json metadata-path)
        proof-bytes (Files/readAllBytes proof-path)
        identities (proof-identities metadata proof-bytes)
        expected-public-values (.parseHex (HexFormat/of)
                                          (statement/public-values-hex projection))
        assured-job-root (get-in work [:work/assured-job :research-execution-job-assurance/root])
        dependency-root (get-in work [:work/resolved-dependencies
                                      :resolved-research-dependency-set/root])
        assurance (:run-assurance-evidence execution)
        assurance-root (:research-run-assurance-evidence/root assurance)
        registry-root (verification-basis/verifier-registry-root
                       verifier-registry-extension-map)
        _ (when-not (and (= "research-accounting-arithmetic-sp1-proof-artifact.v1"
                            (get metadata "schema"))
                         (= "accounting-arithmetic-sp1-program.v1"
                            (get metadata "proof_program"))
                         (= "sp1-v6.3.1-core" (get metadata "proof_system"))
                         (= "sp1-sdk-bincode.v1" (get metadata "proof_encoding"))
                         (= "accounting-arithmetic-roots-160.v1"
                            (get metadata "public_values_schema"))
                         (:valid? (participation/validate-participation verifier-participation))
                         (hash-ref/valid-sha256-ref? verifier-reference)
                         (integer? verification-cutpoint) (not (neg? verification-cutpoint))
                         (proof/receipt-matches-context? generation-receipt assured-job-root
                                                         dependency-root assurance-root)
                         (proof/verifier-registry-matches? generation-receipt
                                                           verifier-registry-extension-map)
                         (= (:proof-root identities) (:proof-artifact/root generation-receipt))
                         (= (:public-values-root identities)
                            (:public-values/input-root generation-receipt))
                         (= (:program-root identities)
                            (:proof-program/identity-root generation-receipt))
                         (= (:system-root identities)
                            (:proof-system/identity-root generation-receipt))
                         (= 160 (alength ^bytes expected-public-values))
                         (java.util.Arrays/equals expected-public-values
                                                  ^bytes (:public-values-bytes identities)))
            (throw (ex-info "Accounting proof does not bind this execution and its rooted receipt"
                            {:cause :accounting-proof-context-mismatch})))
        _ (invoke-host! :verify input-path proof-path metadata-path verified-path)
        verified (read-json verified-path)
        _ (when-not (and (= "research-accounting-arithmetic-sp1-verification.v1"
                            (get verified "schema"))
                         (= "verified" (get verified "verification"))
                         (= (:proof-root identities) (get verified "proof_sha256"))
                         (= (:public-values-root identities)
                            (get verified "public_values_sha256"))
                         (= (:elf-root identities) (get verified "proof_program_elf_sha256"))
                         (= (:vkey-root identities)
                            (str "sha256:" (str/lower-case
                                            (str/replace (get verified "proof_program_vkey")
                                                         #"^0x" "")))))
            (throw (ex-info "SP1 verifier did not verify the exact accounting proof"
                            {:cause :accounting-proof-verification-failed})))
        verification-root
        (domain-root :research-sp1-proof-verification-v1
                     {:artifact/schema "research-sp1-proof-verification.v1"
                      :research-proof-generation-receipt/root
                      (:research-proof-generation-receipt/root generation-receipt)
                      :proof-artifact/root (:proof-root identities)
                      :public-values/root (:public-values-root identities)
                      :proof-program/identity-root (:program-root identities)
                      :verifier-registry/root registry-root
                      :verification/status :verified})
        verification-evidence
        (control/build-proof-verification
         {:proof-receipt generation-receipt :verification-root verification-root
          :verifier-reference verifier-reference :verifier-registry-root registry-root
          :verification-cutpoint verification-cutpoint})]
    {:statement projection :proof-identities identities
     :generation-receipt generation-receipt
     :verification-evidence verification-evidence}))

(defn- parse-args [args]
  (loop [remaining args parsed {}]
    (if (empty? remaining)
      parsed
      (let [[flag value & more] remaining]
        (when-not (and value (str/starts-with? flag "--"))
          (throw (ex-info "Expected --input and --output-dir" {:args remaining})))
        (recur more (assoc parsed (keyword (subs flag 2)) value))))))

(defn -main [& args]
  (let [parsed (parse-args args)
        input (:input parsed)
        output-dir (:output-dir parsed)]
    (when-not (and input output-dir)
      (throw (ex-info "Usage: -m resolver-sim.research.accounting-arithmetic-sp1-host --input <bundle.edn> --output-dir <dir>" {})))
    (let [bundle (edn/read-string (slurp input))
          result (prove-and-verify! (assoc bundle :output-dir output-dir))]
      (println (pr-str (select-keys result [:proof-file :generation-receipt
                                            :verification-evidence]))))))
