(ns scripts.deployment.configuration-review
  "Machine-checkable local configuration review manifest. Approval authorities
  approve this exact manifest root through their own authenticated mechanism;
  matching content is never itself evidence of that approval."
  (:require [resolver-sim.genesis :as genesis]
            [resolver-sim.deployment.source-identity :as source-identity]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.benchmark.signing :as key-signing]
            [scripts.deployment.genesis-roots :as roots]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardOpenOption]))

(def derivation-version "prf-deployment-candidate-package-set.v1")
(def generic-derivation-version "prf-deployment-generic-candidate-package-set.v1")
(def approval-domain "PRF_LOCAL_CONFIGURATION_APPROVAL_V1")
(def approval-role :prf-local-configuration-approver)

(defn review-manifest
  "Build review material for the existing local demo package set. Source revision
  identifies the derivation source, not an inferred approval or current HEAD."
  [packages source-revision approval-authority]
  (when-not (and (string? source-revision) (re-matches #"[0-9a-f]{40}" source-revision))
    (throw (ex-info "source revision must be an exact lowercase Git object ID" {})))
  (when-not (genesis/valid-evm-address? approval-authority)
    (throw (ex-info "approval authority must be an explicit canonical EVM principal" {})))
  (let [derived (roots/derive-candidate-package-set packages)]
    {:manifest/schema "local-configuration-review-manifest.v1"
     :manifest/scope {:protocol/genesis-root (get-in derived ["roots" "protocol-genesis"])
                      :execution/chain-id 31337 :qualification :local-only}
     :manifest/source-revision source-revision
     :manifest/derivation-version derivation-version
     :manifest/approval-authority approval-authority
     :manifest/artifact-digests (get derived "artifactDigests")
     :manifest/canonical-roots (get derived "roots")
     :manifest/initial-configuration (get derived "initialConfiguration")}))

(defn review-manifest-v2
  "Build a candidate review manifest for generic authority-state.v1 and
  explicitly versioned local-authority-policy.v2/v3. This path is schema-distinct from the historical
  EAE demo manifest and only emits derived, unapproved material."
  [packages source-revision approval-authority]
  (when-not (and (string? source-revision) (re-matches #"[0-9a-f]{40}" source-revision))
    (throw (ex-info "source revision must be an exact lowercase Git object ID" {})))
  (when-not (genesis/valid-evm-address? approval-authority)
    (throw (ex-info "approval authority must be an explicit canonical EVM principal" {})))
  (let [derived (roots/derive-generic-candidate-package-set packages)]
    {:manifest/schema "local-configuration-review-manifest.v2"
     :manifest/scope {:protocol/genesis-root (get-in derived ["roots" "protocol-genesis"])
                      :execution/chain-id 31337 :qualification :local-only}
     :manifest/source-revision source-revision
     :manifest/derivation-version generic-derivation-version
     :manifest/approval-authority approval-authority
     :manifest/artifact-digests (get derived "artifactDigests")
     :manifest/canonical-roots (get derived "roots")
     :manifest/initial-configuration (get derived "initialConfiguration")}))

(defn review-manifest-v3
  "Build an approval manifest that binds generic canonical inputs to the
  independently pinned source-tree digest as well as the source parent."
  [packages source-revision source-tree-sha256 approval-authority]
  (when-not (and (string? source-tree-sha256)
                 (re-matches #"[0-9a-f]{64}" source-tree-sha256))
    (throw (ex-info "source tree digest must be a canonical lowercase SHA-256" {})))
  (assoc (review-manifest-v2 packages source-revision approval-authority)
         :manifest/schema "local-configuration-review-manifest.v3"
         :manifest/qualification "candidate-owner-approval-required"
         :manifest/source-tree-sha256 source-tree-sha256))

(defn manifest-root [manifest]
  (str "sha256:" (canonical/hash-with-intent {:hash/intent :evidence-content} manifest)))

(defn validate-review-bindings
  "Recompute the complete expected manifest, including all seven C0 components.
  The expected revision and authority must come from the reviewer's trusted
  context. This validates content binding only, not approval authentication."
  [manifest packages expected-revision expected-authority]
  (try
    (let [expected (case (:manifest/schema manifest)
                    "local-configuration-review-manifest.v1"
                    (review-manifest packages expected-revision expected-authority)
                    "local-configuration-review-manifest.v2"
                    (review-manifest-v2 packages expected-revision expected-authority)
                    "local-configuration-review-manifest.v3"
                    (review-manifest-v3 packages expected-revision
                                        (:manifest/source-tree-sha256 manifest)
                                        expected-authority)
                    nil)
          valid? (= expected manifest)]
      {:valid? valid?
       :errors (if valid? [] ["manifest differs from recomputed artifacts or trusted review scope"])
       :qualification :content-checked-not-approved})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :errors [(.getMessage e)]
       :qualification :content-checked-not-approved})))

(defn approval-envelope
  "Create a detached, domain-separated approval envelope for a reviewed
  manifest. The key-id is resolved only through the verifier's external trust
  policy; naming a principal in the envelope grants it no authority."
  [manifest action private-key key-id]
  (when-not (and (map? manifest)
                 (contains? #{"local-configuration-review-manifest.v1"
                              "local-configuration-review-manifest.v2"
                              "local-configuration-review-manifest.v3"}
                            (:manifest/schema manifest)))
    (throw (ex-info "approval requires a local review manifest" {})))
  (when-not (and (string? action) (re-matches #"[a-z][a-z0-9-]{2,63}" action))
    (throw (ex-info "approval action must be a canonical action identifier" {})))
  (signed/sign-envelope
   {:approval/schema "prf-local-configuration-approval.v1"
    :approval/manifest-root (manifest-root manifest)
    :approval/principal (:manifest/approval-authority manifest)
    :approval/scope (:manifest/scope manifest)
    :approval/action action}
   approval-domain private-key key-id))

(defn verify-approval-envelope
  "Authenticate exact manifest approval against an independently configured
  trust policy. Trusted-key entries must also bind :approval/principal to the
  EVM principal recorded in the envelope."
  [envelope manifest trust-policy expected-action]
  (let [signature-result (signed/verify-envelope envelope approval-domain trust-policy approval-role)
        trusted-key (some #(when (= (get-in envelope [:signature :key-id]) (:key/id %)) %)
                            (:trusted-keys trust-policy))
        expected {:approval/schema "prf-local-configuration-approval.v1"
                  :approval/manifest-root (manifest-root manifest)
                  :approval/principal (:manifest/approval-authority manifest)
                  :approval/scope (:manifest/scope manifest)
                  :approval/action expected-action}
        actual (dissoc envelope :signature)
        bindings-valid? (and (= expected actual)
                             (= (:approval/principal expected) (:approval/principal trusted-key)))]
    {:valid? (and (:valid? signature-result) bindings-valid?)
     :qualification (if (and (:valid? signature-result) bindings-valid?)
                      :authenticated-local-approval :approval-rejected)
     :errors (cond-> []
               (not (:valid? signature-result)) (conj (str "signature: " (:reason signature-result)))
               (not bindings-valid?) (conj "approval scope, manifest, principal or action mismatch"))
     :key-id (:key-id signature-result)}))

(defn verify-initial-input-approval-envelope
  "Verify that an envelope authorizes the fixed initial-input action. A caller
  cannot turn another valid manifest action into deployment approval by
  changing only the trusted-context action field."
  [envelope manifest trust-policy action]
  (let [authentication (verify-approval-envelope envelope manifest trust-policy
                                                  "approve-initial-inputs")
        action-valid? (= "approve-initial-inputs" action)]
    (assoc authentication
           :valid? (and action-valid? (:valid? authentication))
           :errors (cond-> (:errors authentication)
                     (not action-valid?) (conj "trusted action is not approve-initial-inputs")))))

(defn sign-initial-input-approval
  "Create initial-input approval only after checking the source-bound manifest,
  independently supplied source lock, fixed action, principal, and active
  trusted-key binding. The caller supplies an externally held private key; this
  function never persists or returns key material."
  [manifest packages trusted-context private-key key-id]
  (let [{:keys [source-revision source-tree-sha256 source-root source-lock-path
                principal trust-policy action]} trusted-context
        content (validate-review-bindings manifest packages source-revision principal)
        source-check (try
                       (let [identity (source-identity/verify-source-lock
                                       {:source-root source-root
                                        :source-lock-path source-lock-path
                                        :expected-tree-sha256 source-tree-sha256
                                        :expected-parent source-revision})]
                         (if (= source-tree-sha256 (:manifest/source-tree-sha256 manifest))
                           {:valid? true :identity identity}
                           {:valid? false :error "manifest source-tree identity differs from trusted context"}))
                       (catch Exception error
                         {:valid? false :error (.getMessage error)}))
        trusted-key (some #(when (= key-id (:key/id %)) %) (:trusted-keys trust-policy))
        trust-binding-valid? (and (= principal (:manifest/approval-authority manifest))
                                  (= approval-role (:key/role trusted-key))
                                  (= :active (:key/status trusted-key))
                                  (= principal (:approval/principal trusted-key))) ]
    (when-not (and (= "local-configuration-review-manifest.v3" (:manifest/schema manifest))
                   (= "approve-initial-inputs" action)
                   (:valid? content) (:valid? source-check) trust-binding-valid?)
      (throw (ex-info "initial-input approval preflight rejected"
                      {:content content :source source-check
                       :trust-binding-valid? (boolean trust-binding-valid?)
                       :action action})))
    (let [envelope (approval-envelope manifest "approve-initial-inputs" private-key key-id)
          authentication (verify-initial-input-approval-envelope
                          envelope manifest trust-policy action)]
      (when-not (:valid? authentication)
        (throw (ex-info "private key does not match the independently trusted approval key"
                        {:authentication authentication :key-id key-id})))
      envelope)))

(defn- json-key [key]
  (let [raw (if (keyword? key)
              (if-let [ns (namespace key)] (str ns "/" (name key)) (name key))
              key)]
    (if (and (string? raw) (.endsWith raw "?"))
      (subs raw 0 (dec (count raw)))
      raw)))

(defn- json-safe [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)] (str ns "/" (name value)) (name value))
    (map? value) (into {} (map (fn [[key item]] [(json-key key) (json-safe item)])) value)
    (vector? value) (mapv json-safe value)
    (set? value) (mapv json-safe (sort-by pr-str value))
    (sequential? value) (mapv json-safe value)
    :else value))

(defn -main [& args]
  (case (first args)
    ("manifest-v1" "manifest-v2" "manifest-v3")
    (let [[mode & supplied] args
          v3? (= mode "manifest-v3")
          parsed (if v3? supplied
                     (let [[packages-path source-revision principal & extra] supplied]
                       (into [packages-path source-revision nil principal] extra)))
          [packages-path source-revision source-tree-sha256 principal & extra] parsed]
      (when (or (nil? packages-path) (nil? source-revision) (nil? principal)
                (and v3? (nil? source-tree-sha256))
                (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.configuration-review <manifest-v1|manifest-v2|manifest-v3> <packages.edn> <source-revision> [<source-tree-sha256>] <approval-principal>"))
        (System/exit 2))
      (let [packages (edn/read-string (slurp (io/file packages-path)))
            manifest (case mode
                       "manifest-v1" (review-manifest packages source-revision principal)
                       "manifest-v2" (review-manifest-v2 packages source-revision principal)
                       "manifest-v3" (review-manifest-v3 packages source-revision source-tree-sha256 principal))]
        (prn manifest)))
    "sign-initial-inputs"
    (let [[_ manifest-path packages-path trusted-context-path private-key-path key-id-text output-path & extra] args]
      (when (or (nil? manifest-path) (nil? packages-path) (nil? trusted-context-path)
                (nil? private-key-path) (nil? key-id-text) (nil? output-path) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.configuration-review sign-initial-inputs <manifest.edn> <packages.edn> <trusted-context.edn> <private-key-path> <key-id-keyword> <approval-output.edn>"))
        (System/exit 2))
      (let [manifest (edn/read-string (slurp (io/file manifest-path)))
            packages (edn/read-string (slurp (io/file packages-path)))
            context (edn/read-string (slurp (io/file trusted-context-path)))
            key-id (edn/read-string key-id-text)]
        (when-not (keyword? key-id)
          (throw (ex-info "key id must be an EDN keyword" {:key-id key-id-text})))
        (let [private-key (key-signing/load-private-key! private-key-path nil)
              envelope (sign-initial-input-approval manifest packages context private-key key-id)
              output (.toPath (io/file output-path))]
          (Files/write output (.getBytes (str (pr-str envelope) "\n") java.nio.charset.StandardCharsets/UTF_8)
                       (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
          (println (str "Signed initial-input approval written; manifest root "
                        (:approval/manifest-root envelope))))))
    ("verify" "verify-json")
    (let [[mode manifest-path packages-path trusted-context-path approval-path & extra] args
          json? (= mode "verify-json")]
      (when (or (nil? manifest-path) (nil? packages-path) (nil? trusted-context-path)
                (nil? approval-path) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.configuration-review <verify|verify-json> <manifest.edn> <packages.edn> <trusted-context.edn> <approval.edn>"))
        (System/exit 2))
      (let [manifest (edn/read-string (slurp (io/file manifest-path)))
            packages (edn/read-string (slurp (io/file packages-path)))
            context (edn/read-string (slurp (io/file trusted-context-path)))
            approval (edn/read-string (slurp (io/file approval-path)))
            content (validate-review-bindings manifest packages
                                               (:source-revision context) (:principal context))
            source-check (cond
                           (= "local-configuration-review-manifest.v3" (:manifest/schema manifest))
                           (try
                             (let [verified (source-identity/verify-source-lock
                                             {:source-root (:source-root context)
                                              :source-lock-path (:source-lock-path context)
                                              :expected-tree-sha256 (:source-tree-sha256 context)
                                              :expected-parent (:source-revision context)})]
                               (if (= (:source-tree-sha256 verified)
                                      (:manifest/source-tree-sha256 manifest))
                                 {:valid? true :identity verified}
                                 {:valid? false :error "manifest source-tree identity differs from trusted context"}))
                             (catch Exception error {:valid? false :error (.getMessage error)}))
                           json? {:valid? false :error "deployment approval requires source-bound manifest v3"}
                           :else nil)
            authentication (verify-initial-input-approval-envelope
                            approval manifest (:trust-policy context) (:action context))
            source-valid? (if (nil? source-check) true (:valid? source-check))
            result (cond-> {:valid? (and (:valid? content) (:valid? authentication) source-valid?)
                    :manifest-root (manifest-root manifest)
                    :canonical-roots (:manifest/canonical-roots manifest)
                    :initial-configuration (:manifest/initial-configuration manifest)
                    :content content :source source-check :authentication authentication
                    :action (:action context) :qualification
                    (if (and (:valid? content) (:valid? authentication) source-valid?)
                      :authenticated-content-approval :approval-rejected)}
                     (= "local-authority-policy.v3"
                        (get-in packages [:local-authority-policy :local-authority-policy :authority-policy/schema]))
                     (assoc :authority-transition-validator-address
                            (get-in packages [:local-authority-policy :local-authority-policy
                                              :authority/transition-validator-address])))]
        (if json?
          (println (json/write-str (json-safe result)))
          (prn result))
        (when-not (:valid? result) (System/exit 1))))
    (do
      (binding [*out* *err*]
        (println "Usage: clojure -M:test -m scripts.deployment.configuration-review <manifest-v1|manifest-v2|manifest-v3|sign-initial-inputs|verify|verify-json> ..."))
      (System/exit 2))))
