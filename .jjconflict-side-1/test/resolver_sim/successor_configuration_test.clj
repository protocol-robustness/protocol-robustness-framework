(ns resolver-sim.successor-configuration-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.signing :as key-signing]
            [resolver-sim.genesis :as genesis]
            [scripts.deployment.genesis-roots :as roots]
            [scripts.deployment.successor-configuration :as successor]
            [scripts.deployment.successor-transition-review :as review]))

(def verifier-entry
  {:verifier/id (str "0x" (apply str (repeat 32 "01")))
   :verifier/adapter "0x00000000000000000000000000000000000000a1"
   :verifier/program-vkey (str "0x" (apply str (repeat 32 "02")))
   :verifier/program-elf-sha256 (str "sha256:" (apply str (repeat 32 "03")))
   :verifier/program-id "fixture-successor"
   :verifier/statement-schema-version-hash (str "0x" (apply str (repeat 32 "04")))
   :verifier/public-values-schema-hash (str "0x" (apply str (repeat 32 "05")))
   :verifier/active true})

(defn fixture-input []
  (let [c0 (assoc genesis/chain-configuration-v0-fixture
                  :verifier-registry/root (genesis/canonical-verifier-registry-root []))
        instance (-> genesis/chain-instance-genesis-ethereum-fixture
                     (assoc :execution/chain-id 31337
                            :settlement/chain-id 31337
                            :protocol/genesis-root genesis/protocol-genesis-fixture-root
                            :configuration/initial-root (genesis/chain-configuration-root c0)))
        instance (assoc-in instance [:governance :initial-authority-state-root]
                           (str "sha256:" (apply str (repeat 64 "a"))))]
    {:initial-configuration c0
     :protocol-genesis-root genesis/protocol-genesis-fixture-root
     :chain-instance-genesis instance
     :successor-verifier-registry-root (genesis/canonical-verifier-registry-root [verifier-entry])
     :successor-proof-mode 1
     :epoch 1}))

(deftest successor-derivation-changes-only-registry-and-recomputes
  (let [derived (successor/derive-successor-configuration (fixture-input))]
    (is (= [:verifier-registry/root] (:changed-fields derived)))
    (is (= "derived-only-not-approved" (:qualification derived)))
    (is (= (:configuration/C0-root derived)
           (get-in derived [:configuration-transition :configuration/parent-root])))
    (is (= (:configuration/C1-root derived)
           (get-in derived [:configuration-transition :configuration/new-root])))
    (is (:valid? (successor/verify-successor-configuration derived)))))

(deftest successor-json-tooling-round-trips-canonical-scope
  (let [input (fixture-input)
        directory (.toFile (java.nio.file.Files/createTempDirectory "prf-successor-json" (make-array java.nio.file.attribute.FileAttribute 0)))
        request-file (io/file directory "derive.json")
        derived (successor/derive-successor-configuration input)
        review-file (io/file directory "review.json")]
    (try
      (spit request-file (json/write-str input :key-fn #(if (keyword? %) (if-let [ns (namespace %)] (str ns "/" (name %)) (name %)) %)))
      (let [output (with-out-str (successor/-main "derive-json" (.getPath request-file)))
            parsed (json/read-str output :key-fn keyword)]
        (is (= (:configuration/C0-root derived) (:configuration/C0-root parsed)))
        (is (= (:configuration/C1-root derived) (:configuration/C1-root parsed)))
        (is (= (:decision-root derived) (:decision-root parsed))))
      (spit review-file (json/write-str {:derivation (json/read-str (with-out-str (successor/-main "derive-json" (.getPath request-file))) :key-fn keyword)
                                         :verifier-registry [{:id (:verifier/id verifier-entry)
                                                              :adapter (:verifier/adapter verifier-entry)
                                                              :programVKey (:verifier/program-vkey verifier-entry)
                                                              :programElfSha256 (:verifier/program-elf-sha256 verifier-entry)
                                                              :programId (:verifier/program-id verifier-entry)
                                                              :statementSchemaVersionHash (:verifier/statement-schema-version-hash verifier-entry)
                                                              :publicValuesSchema (:verifier/public-values-schema-hash verifier-entry)
                                                              :active (:verifier/active verifier-entry)}]
                                         :source-revision (apply str (repeat 40 "b"))
                                         :approval-principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"}
                                        :key-fn #(if (keyword? %) (if-let [ns (namespace %)] (str ns "/" (name %)) (name %)) %)))
      (let [manifest (edn/read-string (with-out-str (review/-main "manifest-json" (.getPath review-file))))]
        (is (= "prf-successor-transition-review-manifest.v1" (:manifest/schema manifest)))
        (is (= (:configuration/C1-root derived) (get-in manifest [:manifest/scope :configuration/new-root]))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest successor-derivation-rejects-mismatched-bindings
  (let [input (fixture-input)
        record (successor/derive-successor-configuration input)]
    (is (not (:valid? (successor/verify-successor-configuration
                       (assoc record :decision-root "sha256:bad")))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (successor/derive-successor-configuration
                  (assoc input :expected-chain-instance-genesis-root "sha256:bad"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (successor/derive-successor-configuration (assoc input :epoch 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (successor/derive-successor-configuration (assoc input :successor-proof-mode 2))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (successor/derive-successor-configuration
                  (assoc-in input [:chain-instance-genesis :execution/chain-id] 1))))))

(deftest successor-approval-is-separate-and-binds-the-exact-transition
  (let [input (fixture-input)
        derivation (successor/derive-successor-configuration input)
        revision (apply str (repeat 40 "b"))
        principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
        manifest (review/review-manifest derivation [verifier-entry] revision principal)
        pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        public-bytes (.getEncoded (.getPublic pair))
        raw-public (java.util.Arrays/copyOfRange public-bytes (- (alength public-bytes) 32) (alength public-bytes))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw-public))
        key-id :successor-approver
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal principal}]}
        context {:source-revision revision :principal principal
                 :trust-policy trust :action review/approval-action}
        approval (review/sign-successor-transition-approval
                  manifest [verifier-entry] context (.getPrivate pair) key-id)
        verified (review/verify-review manifest [verifier-entry]
                                       context
                                       approval)]
    (is (:valid? (review/validate-review-manifest manifest [verifier-entry] revision principal)))
    (is (= :authenticated-successor-transition-approval
           (:qualification (review/verify-approval approval manifest trust review/approval-action))))
    (is (:valid? verified))
    (is (= (review/manifest-root manifest) (:manifest-root verified)))
    (is (= (:manifest/scope manifest) (:scope verified)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"successor-transition approval preflight rejected"
                          (review/sign-successor-transition-approval
                           manifest [verifier-entry] (assoc context :action "approve-initial-inputs")
                           (.getPrivate pair) key-id)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"successor-transition approval preflight rejected"
                          (review/sign-successor-transition-approval
                           manifest [verifier-entry]
                           (assoc context :trust-policy
                                  (assoc-in trust [:trusted-keys 0 :key/status] :revoked))
                           (.getPrivate pair) key-id)))
    (is (false? (:valid? (review/verify-approval approval manifest trust "approve-initial-inputs"))))
    (is (false? (:valid? (review/verify-approval
                          approval manifest
                          (assoc-in trust [:trusted-keys 0 :approval/principal]
                                    "0x0000000000000000000000000000000000000001")
                          review/approval-action))))
    (is (false? (:valid? (review/verify-approval approval
                                                 (assoc-in manifest [:manifest/scope :execution/chain-id] 1)
                                                 trust review/approval-action))))
    (is (false? (:valid? (review/validate-review-manifest manifest
                                                          [(assoc verifier-entry :verifier/active false)]
                                                          revision principal))))
    (is (false? (:valid? (review/validate-review-manifest
                          (assoc manifest :manifest/source-revision (apply str (repeat 40 "c")))
                          [verifier-entry] revision principal))))
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                              "prf-successor-sign" (make-array java.nio.file.attribute.FileAttribute 0)))
          manifest-file (io/file directory "manifest.edn")
          registry-file (io/file directory "registry.edn")
          context-file (io/file directory "trusted-context.edn")
          approval-file (io/file directory "approval.edn")
          args ["sign" (.getPath manifest-file) (.getPath registry-file) (.getPath context-file)
                "external-key.pem" (pr-str key-id) (.getPath approval-file)]]
      (try
        (spit manifest-file (pr-str manifest))
        (spit registry-file (pr-str [verifier-entry]))
        (spit context-file (pr-str context))
        (binding [*out* (java.io.StringWriter.)]
          (with-redefs [key-signing/load-private-key! (fn [_ _] (.getPrivate pair))]
            (apply review/-main args)))
        (let [cli-approval (edn/read-string (slurp approval-file))
              original (slurp approval-file)]
          (is (:valid? (review/verify-review manifest [verifier-entry] context cli-approval)))
          (is (thrown? java.nio.file.FileAlreadyExistsException
                       (binding [*out* (java.io.StringWriter.)]
                         (with-redefs [key-signing/load-private-key! (fn [_ _] (.getPrivate pair))]
                           (apply review/-main args)))))
          (is (= original (slurp approval-file))))
        (finally (doseq [file (reverse (file-seq directory))] (.delete file)))))))

(deftest successor-approval-json-verifier-emits-deployment-scope
  (let [derivation (successor/derive-successor-configuration (fixture-input))
        revision (apply str (repeat 40 "b"))
        principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
        manifest (review/review-manifest derivation [verifier-entry] revision principal)
        pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw))
        key-id :successor-approver
        trust {:trusted-keys [{:key/id key-id :key/public public-hex :key/role review/approval-role
                               :key/status :active :approval/principal principal}]}
        approval (review/approval-envelope manifest (.getPrivate pair) key-id)
        context {:source-revision revision :principal principal :trust-policy trust :action review/approval-action}
        directory (.toFile (java.nio.file.Files/createTempDirectory "prf-transition-verify" (make-array java.nio.file.attribute.FileAttribute 0)))
        manifest-file (io/file directory "manifest.edn")
        registry-file (io/file directory "registry.edn")
        context-file (io/file directory "trusted.edn")
        approval-file (io/file directory "approval.edn")]
    (try
      (spit manifest-file (pr-str manifest))
      (spit registry-file (pr-str [verifier-entry]))
      (spit context-file (pr-str context))
      (spit approval-file (pr-str approval))
      (let [output (with-out-str (review/-main "verify-json" (.getPath manifest-file) (.getPath registry-file)
                                               (.getPath context-file) (.getPath approval-file)))
            parsed (json/read-str output)]
        (is (true? (get parsed "valid")))
        (is (= "authenticated-successor-transition" (get parsed "qualification")))
        (is (= (:chain-instance-genesis-root derivation) (get-in parsed ["scope" "chain-instance-genesis-root"])))
        (is (= (:configuration/C0-root derivation) (get-in parsed ["scope" "configuration/parent-root"])))
        (is (= (:configuration/C1-root derivation) (get-in parsed ["scope" "configuration/new-root"]))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest bounded-candidate-c0-derives-and-authenticates-distinct-c1-transition
  ;; The ephemeral key proves review-path behavior only; it is not a retained
  ;; owner approval or deployment authorization.
  (let [packages (edn/read-string
                  (slurp "etc/deployments/bounded-configuration-local-candidate-v2/candidate-package-set.edn"))
        package-set (roots/derive-generic-candidate-package-set packages)
        c0 (get package-set "initialConfiguration")
        protocol-root (get-in package-set ["roots" "protocol-genesis"])
        authority-root (get-in package-set ["roots" "authority-state"])
        instance (-> genesis/chain-instance-genesis-ethereum-fixture
                     (assoc :execution/chain-id 31337
                            :settlement/chain-id 31337
                            :protocol/genesis-root protocol-root
                            :configuration/initial-root (genesis/chain-configuration-root c0))
                     (assoc-in [:governance :initial-authority-state-root] authority-root))
        registry-root (genesis/canonical-verifier-registry-root [verifier-entry])
        derivation (successor/derive-successor-configuration
                    {:initial-configuration c0
                     :protocol-genesis-root protocol-root
                     :chain-instance-genesis instance
                     :successor-verifier-registry-root registry-root
                     :successor-proof-mode 1
                     :epoch 1})
        principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
        revision (apply str (repeat 40 "b"))
        manifest (review/review-manifest derivation [verifier-entry] revision principal)
        pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        public-bytes (.getEncoded (.getPublic pair))
        raw-public (java.util.Arrays/copyOfRange public-bytes (- (alength public-bytes) 32) (alength public-bytes))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw-public))
        key-id :bounded-successor-test-only
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal principal}]}
        context {:source-revision revision :principal principal :trust-policy trust
                 :action review/approval-action}
        approval (review/sign-successor-transition-approval
                  manifest [verifier-entry] context (.getPrivate pair) key-id)
        verified (review/verify-review manifest [verifier-entry] context approval)]
    (is (= (:verifier-registry/root c0) (genesis/canonical-verifier-registry-root [])))
    (is (= [:verifier-registry/root] (:changed-fields derivation)))
    (is (not= (:configuration/C0-root derivation) (:configuration/C1-root derivation)))
    (is (= (:chain-instance-genesis-root derivation)
           (get-in manifest [:manifest/scope :chain-instance-genesis-root])))
    (is (= (:configuration/C0-root derivation)
           (get-in manifest [:manifest/scope :configuration/parent-root])))
    (is (= (:configuration/C1-root derivation)
           (get-in manifest [:manifest/scope :configuration/new-root])))
    (is (:valid? verified))
    (is (false? (:valid? (review/verify-review
                          manifest [verifier-entry]
                          (assoc context :action "approve-initial-inputs") approval))))
    (is (false? (:valid? (review/validate-review-manifest
                          (update-in manifest [:manifest/derivation :configuration/C1-root]
                                     (constantly "sha256:invalid"))
                          [verifier-entry] revision principal))))))
