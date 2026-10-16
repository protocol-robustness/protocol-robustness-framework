(ns scripts.deployment.successor-transition-review
  "Review and authenticate the deployment-specific C0 -> C1 transition. This
  approval is deliberately separate from approval of initial semantic inputs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.benchmark.signing :as key-signing]
            [resolver-sim.signed-external-decision :as signed]
            [scripts.deployment.successor-configuration :as successor])
  (:import [java.nio.file Files StandardOpenOption]))

(def approval-domain "PRF_LOCAL_SUCCESSOR_CONFIGURATION_APPROVAL_V1")
(def approval-role :prf-local-successor-configuration-approver)
(def approval-action "approve-successor-transition")

(defn manifest-root [manifest]
  (str "sha256:" (canonical/hash-with-intent {:hash/intent :evidence-content} manifest)))

(defn review-manifest
  "Create a candidate-only manifest from the fully derived and independently
  recomputable transition, final verifier registry artifact, source identity,
  and explicit local approver principal."
  [derivation verifier-registry source-revision approval-principal]
  (let [verification (successor/verify-successor-configuration derivation)]
    (when-not (:valid? verification)
      (throw (ex-info "successor derivation is not independently reproducible"
                      {:verification (dissoc verification :expected)
                       :expected (:expected verification)
                       :actual derivation}))))
  (when-not (and (string? source-revision) (re-matches #"[0-9a-f]{40}" source-revision))
    (throw (ex-info "source revision must be an exact lowercase Git object ID" {})))
  (when-not (genesis/valid-evm-address? approval-principal)
    (throw (ex-info "approval principal must be a canonical non-zero EVM address" {})))
  (when-not (= (:successor-verifier-registry-root derivation)
               (genesis/canonical-verifier-registry-root verifier-registry))
    (throw (ex-info "verifier registry artifact does not derive the successor root" {})))
  {:manifest/schema "prf-successor-transition-review-manifest.v1"
   :manifest/qualification "candidate-owner-approval-required"
   :manifest/scope {:protocol/genesis-root (:protocol-genesis-root derivation)
                    :execution/chain-id 31337
                    :chain-instance-genesis-root (:chain-instance-genesis-root derivation)
                    :configuration/parent-root (:configuration/C0-root derivation)
                    :configuration/new-root (:configuration/C1-root derivation)
                    :epoch 1
                    :entitlement-proof-mode (:successor-proof-mode derivation)
                    :qualification :local-only}
   :manifest/source-revision source-revision
   :manifest/approval-principal approval-principal
   :manifest/derivation derivation
   :manifest/verifier-registry verifier-registry})

(defn validate-review-manifest
  "Recompute both the transition and its exact review envelope using trusted
  source revision and principal supplied outside the candidate manifest."
  [manifest verifier-registry expected-revision expected-principal]
  (try
    (let [derivation (:manifest/derivation manifest)
          expected (review-manifest derivation verifier-registry expected-revision expected-principal)]
      {:valid? (= expected manifest)
       :qualification (if (= expected manifest) :content-checked-not-approved :rejected)
       :errors (if (= expected manifest) [] ["review manifest differs from recomputed transition or trusted scope"])} )
    (catch Exception error
      {:valid? false :qualification :rejected :errors [(.getMessage error)]})))

(defn approval-envelope
  "Sign the exact transition review using the dedicated transition approval
  domain. The approver identity remains subject to the verifier's trust policy."
  [manifest private-key key-id]
  (when-not (= "prf-successor-transition-review-manifest.v1" (:manifest/schema manifest))
    (throw (ex-info "approval requires a successor transition review manifest" {})))
  (signed/sign-envelope
   {:approval/schema "prf-successor-transition-approval.v1"
    :approval/manifest-root (manifest-root manifest)
    :approval/principal (:manifest/approval-principal manifest)
    :approval/scope (:manifest/scope manifest)
    :approval/action approval-action}
   approval-domain private-key key-id))

(defn verify-approval
  "Authenticate exact transition scope, manifest, principal, and dedicated
  action against separately supplied trusted keys."
  [envelope manifest trust-policy expected-action]
  (let [signature-result (signed/verify-envelope envelope approval-domain trust-policy approval-role)
        trusted-key (some #(when (= (get-in envelope [:signature :key-id]) (:key/id %)) %)
                            (:trusted-keys trust-policy))
        expected {:approval/schema "prf-successor-transition-approval.v1"
                  :approval/manifest-root (manifest-root manifest)
                  :approval/principal (:manifest/approval-principal manifest)
                  :approval/scope (:manifest/scope manifest)
                  :approval/action expected-action}
        bindings-valid? (and (= expected (dissoc envelope :signature))
                             (= (:approval/principal expected) (:approval/principal trusted-key)))]
    {:valid? (and (:valid? signature-result) bindings-valid?)
     :qualification (if (and (:valid? signature-result) bindings-valid?)
                      :authenticated-successor-transition-approval :approval-rejected)
     :errors (cond-> []
               (not (:valid? signature-result)) (conj (str "signature: " (:reason signature-result)))
               (not bindings-valid?) (conj "approval scope, manifest, principal or action mismatch"))
     :key-id (:key-id signature-result)}))

(defn verify-review
  "Verify the recomputable manifest and detached approval using externally
  trusted source, principal and policy bindings. The result carries the exact
  transition scope for a deployment executor to compare with live facts."
  [manifest registry context approval]
  (let [content (validate-review-manifest manifest registry
                                          (:source-revision context) (:principal context))
        authentication (verify-approval approval manifest (:trust-policy context)
                                        (:action context))
        valid? (and (:valid? content) (:valid? authentication))]
    {:valid? valid?
     :qualification (if valid? :authenticated-successor-transition :approval-rejected)
     :manifest-root (manifest-root manifest)
     :scope (:manifest/scope manifest)
     :source content
     :authentication authentication
     :action (:action context)}))

(defn sign-successor-transition-approval
  "Sign a reproducible successor-transition manifest only when its source,
  principal, fixed action, and active trusted-key binding match the external
  review context. The private key is supplied by the owner and is never stored."
  [manifest registry trusted-context private-key key-id]
  (let [{:keys [source-revision principal trust-policy action]} trusted-context
        content (validate-review-manifest manifest registry source-revision principal)
        trusted-key (some #(when (= key-id (:key/id %)) %) (:trusted-keys trust-policy))
        trust-binding-valid? (and (= principal (:manifest/approval-principal manifest))
                                  (= approval-role (:key/role trusted-key))
                                  (= :active (:key/status trusted-key))
                                  (= principal (:approval/principal trusted-key)))]
    (when-not (and (:valid? content) (= approval-action action) trust-binding-valid?)
      (throw (ex-info "successor-transition approval preflight rejected"
                      {:content content :trust-binding-valid? (boolean trust-binding-valid?)
                       :action action})))
    (let [envelope (approval-envelope manifest private-key key-id)
          authentication (verify-approval envelope manifest trust-policy approval-action)]
      (when-not (:valid? authentication)
        (throw (ex-info "private key does not match the independently trusted transition key"
                        {:authentication authentication :key-id key-id})))
      envelope)))

(defn- json-safe [value]
  (cond
    (keyword? value) (if-let [namespace (namespace value)] (str namespace "/" (name value)) (name value))
    (map? value) (into {} (map (fn [[key nested]] [(if (keyword? key) (json-safe key) (str key)) (json-safe nested)]) value))
    (set? value) (mapv json-safe (sort-by pr-str value))
    (sequential? value) (mapv json-safe value)
    (instance? java.util.UUID value) (str value)
    :else value))

(defn- restore-derivation-enums [derivation]
  (let [restore-keyword (fn [value]
                          (if (and (string? value) (str/includes? value "/"))
                            (let [[namespace name] (str/split value #"/" 2)] (keyword namespace name))
                            (if (string? value) (keyword value) value)))]
  (-> derivation
      (update :changed-fields #(mapv restore-keyword %))
      (update-in [:configuration-transition :target :target/type]
                 restore-keyword))))

(defn- normalize-verifier-registry [entries]
  (mapv (fn [entry]
          (if (contains? entry :verifier/id)
            entry
            {:verifier/id (:id entry)
             :verifier/adapter (:adapter entry)
             :verifier/program-vkey (:programVKey entry)
             :verifier/program-elf-sha256 (:programElfSha256 entry)
             :verifier/program-id (:programId entry)
             :verifier/statement-schema-version-hash (:statementSchemaVersionHash entry)
             :verifier/public-values-schema-hash (:publicValuesSchema entry)
             :verifier/active (:active entry)}))
        entries))

(defn -main [& [mode & args]]
  (case mode
    "manifest"
    (let [[derivation-path registry-path source-revision principal & extra] args]
      (when (or (nil? derivation-path) (nil? registry-path) (nil? source-revision) (nil? principal) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.successor-transition-review manifest <derivation.edn> <registry.edn> <source-revision> <approval-principal>"))
        (System/exit 2))
      (prn (review-manifest (edn/read-string (slurp (io/file derivation-path)))
                            (edn/read-string (slurp (io/file registry-path))) source-revision principal)))
    "manifest-json"
    (let [[request-path & extra] args]
      (when (or (nil? request-path) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.successor-transition-review manifest-json <review-request.json>"))
        (System/exit 2))
      (let [request (json/read-str (slurp (io/file request-path)) :key-fn keyword)]
        (prn (review-manifest (restore-derivation-enums (:derivation request))
                              (normalize-verifier-registry (:verifier-registry request))
                              (:source-revision request) (:approval-principal request)))))
    "sign"
    (let [[manifest-path registry-path trusted-context-path private-key-path key-id-text output-path & extra] args]
      (when (or (nil? manifest-path) (nil? registry-path) (nil? trusted-context-path)
                (nil? private-key-path) (nil? key-id-text) (nil? output-path) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.successor-transition-review sign <manifest.edn> <registry.edn> <trusted-context.edn> <private-key-path> <key-id-keyword> <approval-output.edn>"))
        (System/exit 2))
      (let [manifest (edn/read-string (slurp (io/file manifest-path)))
            registry (edn/read-string (slurp (io/file registry-path)))
            context (edn/read-string (slurp (io/file trusted-context-path)))
            key-id (edn/read-string key-id-text)]
        (when-not (keyword? key-id)
          (throw (ex-info "key id must be an EDN keyword" {:key-id key-id-text})))
        (let [private-key (key-signing/load-private-key! private-key-path nil)
              envelope (sign-successor-transition-approval manifest registry context private-key key-id)
              output (.toPath (io/file output-path))]
          (Files/write output (.getBytes (str (pr-str envelope) "\n") java.nio.charset.StandardCharsets/UTF_8)
                       (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
          (println (str "Signed successor-transition approval written; manifest root "
                        (:approval/manifest-root envelope))))))
    ("verify" "verify-json")
    (let [[manifest-path registry-path trusted-context-path approval-path & extra] args]
      (when (or (nil? manifest-path) (nil? registry-path) (nil? trusted-context-path)
                (nil? approval-path) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.successor-transition-review <verify|verify-json> <manifest.edn> <registry.edn> <trusted-context.edn> <approval.edn>"))
        (System/exit 2))
      (let [manifest (edn/read-string (slurp (io/file manifest-path)))
            registry (edn/read-string (slurp (io/file registry-path)))
            context (edn/read-string (slurp (io/file trusted-context-path)))
            approval (edn/read-string (slurp (io/file approval-path)))
            result (verify-review manifest registry context approval)]
        (if (= mode "verify-json")
          (println (json/write-str (-> (json-safe result)
                                       (dissoc "valid?")
                                       (assoc "valid" (:valid? result)))
                                   :escape-slash false))
          (prn result))
        (when-not (:valid? result) (System/exit 1))))
    (do
      (binding [*out* *err*]
        (println "Usage: clojure -M:test -m scripts.deployment.successor-transition-review <manifest|manifest-json|sign|verify|verify-json> ..."))
      (System/exit 2))))
