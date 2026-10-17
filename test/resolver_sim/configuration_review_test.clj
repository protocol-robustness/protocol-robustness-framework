(ns resolver-sim.configuration-review-test
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.deployment-package-set-test :as package-fixtures]
            [resolver-sim.deployment.source-identity :as source-identity]
            [resolver-sim.deployment.source-identity-fixture :as source-fixture]
            [resolver-sim.benchmark.signing :as key-signing]
            [scripts.deployment.configuration-review :as review])
  (:import [java.nio.file Files FileAlreadyExistsException]
           [java.security KeyPairGenerator]))

(def revision (apply str (repeat 40 "b")))
(def approver "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")

(defn- sha256-file [path]
  (apply str
         (map #(format "%02x" (bit-and 0xff %))
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (Files/readAllBytes (.toPath (io/file path)))))))

(deftest release-source-identity-digest-matches-baseline
  (let [root (io/file ".")
        baseline-path (io/file root "etc/deployments/baselines/bounded-configuration-release-baseline.v1.json")
        baseline (json/read-str (slurp baseline-path) :key-fn keyword)
        prf (:prf baseline)
        tree-root (:sourceTreeSha256 prf)
        source-identities (get-in baseline [:prf :releaseSourceIdentities])
        source-identities-path (io/file root (:path source-identities))
        sources (json/read-str (slurp source-identities-path) :key-fn keyword)
        candidate (:localCandidatePackage prf)
        manifest (edn/read-string (slurp (io/file root (:sourceBoundManifest candidate))))]
    (is (= (:sha256 source-identities) (sha256-file source-identities-path)))
    (is (= tree-root (:sourceTreeSha256 candidate)))
    (is (= tree-root (:sourceTreeSha256 source-identities)))
    (is (= tree-root (:manifest/source-tree-sha256 manifest)))
    (is (= (:sourceFileCount prf) (count (:sourceTreeEntries prf))))
    (is (= (:sourceBoundManifestSha256 candidate)
           (sha256-file (io/file root (:sourceBoundManifest candidate)))))
    (doseq [patches [(get-in sources [:prfEvm :patches])
                     (get-in sources [:protocolDeployments :patches])]
            {:keys [path sha256]} (vals patches)]
      (is (= sha256 (sha256-file (io/file root path))) path))))

(deftest review-binds-exact-local-scope-and-all-material
  (let [packages (package-fixtures/packages)
        manifest (review/review-manifest packages revision approver)
        check #(review/validate-review-bindings % packages revision approver)]
    (is (:valid? (check manifest)))
    (is (= :content-checked-not-approved (:qualification (check manifest))))
    (is (= 8 (count (:manifest/initial-configuration manifest))))
    (doseq [changed [(assoc manifest :approval/status :approved)
                     (assoc-in manifest [:manifest/scope :execution/chain-id] 84532)
                     (assoc-in manifest [:manifest/scope :qualification] :production)
                     (assoc manifest :manifest/source-revision (apply str (repeat 40 "a")))
                     (assoc manifest :manifest/derivation-version "unreviewed")
                     (assoc manifest :manifest/approval-authority "0x0000000000000000000000000000000000000001")
                     (update manifest :manifest/artifact-digests dissoc "authority-state")
                     (update manifest :manifest/initial-configuration dissoc :governance-policy/root)]]
      (is (false? (:valid? (check changed))))
      (is (not= (review/manifest-root manifest) (review/manifest-root changed))))
    (let [substituted (update-in packages [:initial-configuration :component-material :parameter-policy]
                                 assoc :token-policy "unreviewed")]
      (is (false? (:valid? (review/validate-review-bindings manifest substituted revision approver)))))))

(deftest review-requires-explicit-source-and-authority
  (is (thrown? clojure.lang.ExceptionInfo
               (review/review-manifest (package-fixtures/packages) "HEAD" approver)))
  (is (thrown? clojure.lang.ExceptionInfo
               (review/review-manifest (package-fixtures/packages) revision nil))))

(deftest approval-authenticates-exact-manifest-scope-principal-and-action
  (let [packages (package-fixtures/packages)
        manifest (review/review-manifest packages revision approver)
        pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        public-bytes (.getEncoded (.getPublic pair))
        raw-public (java.util.Arrays/copyOfRange public-bytes (- (alength public-bytes) 32) (alength public-bytes))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw-public))
        key-id :local-owner-2026
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal approver}]}
        envelope (review/approval-envelope manifest "approve-initial-inputs" (.getPrivate pair) key-id)]
    (is (= :authenticated-local-approval
           (:qualification (review/verify-approval-envelope envelope manifest trust "approve-initial-inputs"))))
    (is (false? (:valid? (review/verify-approval-envelope envelope manifest trust "approve-successor-transition"))))
    (is (false? (:valid? (review/verify-initial-input-approval-envelope
                          envelope manifest trust "approve-successor-transition"))))
    (is (false? (:valid? (review/verify-approval-envelope envelope
                                                          (assoc-in manifest [:manifest/scope :execution/chain-id] 84532)
                                                          trust "approve-initial-inputs"))))
    (is (false? (:valid? (review/verify-approval-envelope envelope manifest
                                                          (assoc-in trust [:trusted-keys 0 :approval/principal]
                                                                    "0x0000000000000000000000000000000000000001")
                                                          "approve-initial-inputs"))))))

(deftest initial-input-signer-requires-source-and-trusted-key-binding
  (let [packages (package-fixtures/generic-packages)
        tree (apply str (repeat 64 "c"))
        manifest (review/review-manifest-v3 packages revision tree approver)
        pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        wrong-pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw))
        key-id :owner-approval-2026
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal approver}]}
        context {:source-revision revision :source-tree-sha256 tree
                 :source-root "." :source-lock-path "release-lock.json"
                 :principal approver :trust-policy trust :action "approve-initial-inputs"}]
    (with-redefs [source-identity/verify-source-lock
                  (fn [_] {:source-tree-sha256 tree :source-parent revision :source-file-count 1})]
      (let [envelope (review/sign-initial-input-approval
                      manifest packages context (.getPrivate pair) key-id)]
        (is (= :authenticated-local-approval
               (:qualification (review/verify-initial-input-approval-envelope
                                envelope manifest trust "approve-initial-inputs"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"initial-input approval preflight rejected"
                              (review/sign-initial-input-approval
                               manifest packages (assoc context :action "approve-successor-transition")
                               (.getPrivate pair) key-id)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"initial-input approval preflight rejected"
                              (review/sign-initial-input-approval
                               manifest packages
                               (assoc context :trust-policy
                                      (assoc-in trust [:trusted-keys 0 :approval/principal]
                                                "0x0000000000000000000000000000000000000001"))
                               (.getPrivate pair) key-id)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"private key does not match"
                              (review/sign-initial-input-approval
                               manifest packages context (.getPrivate wrong-pair) key-id)))))))

(deftest initial-input-signer-cli-writes-a-verified-envelope-once
  (let [{:keys [source-revision source-tree-sha256 source-root source-lock-path]}
        (source-fixture/create-lock-fixture)
        revision source-revision
        tree source-tree-sha256
        packages (package-fixtures/generic-packages)
        manifest (review/review-manifest-v3 packages revision tree approver)
        pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw))
        key-id :owner-approval-cli-test
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal approver}]}
        context {:source-revision revision :source-tree-sha256 tree
                 :source-root source-root
                 :source-lock-path source-lock-path
                 :principal approver :trust-policy trust :action "approve-initial-inputs"}
        temp (.toFile (Files/createTempDirectory "prf-initial-approval-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
        manifest-file (io/file temp "manifest.edn")
        packages-file (io/file temp "packages.edn")
        context-file (io/file temp "trusted-context.edn")
        approval-file (io/file temp "approval.edn")
        args ["sign-initial-inputs" (.getPath manifest-file) (.getPath packages-file)
              (.getPath context-file) "outside-repository-key.pem" (pr-str key-id) (.getPath approval-file)]]
    (spit manifest-file (pr-str manifest))
    (spit packages-file (pr-str packages))
    (spit context-file (pr-str context))
    (let [output (java.io.StringWriter.)]
      (binding [*out* output]
        (with-redefs [key-signing/load-private-key! (fn [_ _] (.getPrivate pair))]
          (apply review/-main args)))
      (let [envelope (edn/read-string (slurp approval-file))
            original (slurp approval-file)]
        (is (re-find (re-pattern (str (review/manifest-root manifest))) (str output)))
        (is (:valid? (review/verify-initial-input-approval-envelope
                      envelope manifest trust "approve-initial-inputs")))
        (is (thrown? FileAlreadyExistsException
                     (binding [*out* (java.io.StringWriter.)]
                       (with-redefs [key-signing/load-private-key! (fn [_ _] (.getPrivate pair))]
                         (apply review/-main args)))))
        (is (= original (slurp approval-file)))))
    (.delete approval-file)
    (.delete context-file)
    (.delete packages-file)
    (.delete manifest-file)
    (.delete temp)))

(deftest generic-review-manifest-v2-binds-authority-state-and-local-validator-policy
  (let [packages (package-fixtures/generic-packages)
        manifest (review/review-manifest-v2 packages revision approver)
        pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        public-bytes (.getEncoded (.getPublic pair))
        raw-public (java.util.Arrays/copyOfRange public-bytes (- (alength public-bytes) 32) (alength public-bytes))
        public-hex (apply str (map #(format "%02x" (bit-and 0xff %)) raw-public))
        key-id :generic-review-owner
        trust {:trusted-keys [{:key/id key-id :key/public public-hex
                               :key/role review/approval-role :key/status :active
                               :approval/principal approver}]}
        approval (review/approval-envelope manifest "approve-initial-inputs" (.getPrivate pair) key-id)]
    (is (= "local-configuration-review-manifest.v2" (:manifest/schema manifest)))
    (is (= 4 (count (:manifest/artifact-digests manifest))))
    (is (:valid? (review/validate-review-bindings manifest packages revision approver)))
    (is (= :authenticated-local-approval
           (:qualification (review/verify-approval-envelope approval manifest trust "approve-initial-inputs"))))
    (is (false? (:valid? (review/validate-review-bindings
                          manifest
                          (update-in packages [:local-authority-policy :local-authority-policy]
                                     assoc :authority/transition-validator-key-id "substituted-key")
                          revision approver))))
    (is (false? (:valid? (review/verify-approval-envelope approval
                                                          (assoc manifest :manifest/source-revision (apply str (repeat 40 "a")))
                                                          trust "approve-initial-inputs"))))))

(deftest source-bound-review-manifest-supports-policy-v3-candidate
  (let [packages (edn/read-string
                  (slurp "etc/deployments/bounded-configuration-local-candidate-v2/candidate-package-set.edn"))
        tree (apply str (repeat 64 "a"))
        manifest (review/review-manifest-v3 packages revision tree approver)]
    (is (= "local-configuration-review-manifest.v3" (:manifest/schema manifest)))
    (is (= "sha256:0e124c35e9707f03ddb7f0b2972c22715098a23b58804175df02acdd25ded55c"
           (get-in manifest [:manifest/canonical-roots "local-authority-policy"])))
    (is (= "candidate-owner-approval-required" (:manifest/qualification manifest)))
    (is (:valid? (review/validate-review-bindings manifest packages revision approver)))
    (is (not= (review/manifest-root manifest)
              (review/manifest-root (assoc manifest :manifest/source-tree-sha256 (apply str (repeat 64 "b"))))))))
