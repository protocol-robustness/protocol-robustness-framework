(ns resolver-sim.commands.canonical-artifacts
  "Explicit CLI adapters for a small, supported set of canonical artifacts.

   This is intentionally not a runtime loader for the proposed catalog. Each
   supported semantic derivation is named here and delegated to its owner."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [prf.pro-rata.identity :as pro-rata-identity]
            [prf.pro-rata.request :as pro-rata-request]
            [prf.pro-rata.realized-statement :as realized-statement]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private request-id "pro-rata-allocation-request.v1")
(def ^:private request-domain "PROJECTION_ARTIFACT_V1")
(def ^:private bundle-schema "artifact-bundle.v1")
(def ^:private bundle-domain "PRF_ARTIFACT_BUNDLE_V1")
(def ^:private sha256-pattern #"[0-9a-f]{64}")
(def ^:private request-fields
  #{:schema-version :mechanism/version :allocation/id :available :rows
    :rounding-policy :tie-break-policy :redistribution-policy})
(def ^:private request-row-fields
  #{:row/id :obligation/id :requested :weight :cap})
(def ^:private statement-fields
  #{:allocation-context-root :request-set-root :allocation-policy-root
    :realized-results-root :fail-action-policy-root :round-lifecycle-root})
(def ^:private bundle-input-fields #{:schema-version :members})
(def ^:private expected-root-field :verification/expected-root)
(def ^:private opaque-fields
  #{:artifact/id :artifact/schema :hash/scheme :hash/algorithm :hash/domain
    :commitment/root})
(def ^:private bundle-member-fields
  #{:member/role :artifact/id :artifact/schema :hash/scheme :hash/algorithm
    :hash/domain :commitment/root :commitment/status :artifact/data})

(defn- fail! [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- read-edn! [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception error
      (fail! :invalid-edn-input (str "Cannot read EDN input: " path)
             {:path path :cause (.getMessage error)}))))

(defn- sorted-value [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (sorted-value item)])) value)
    (vector? value) (mapv sorted-value value)
    (set? value) (vec (sort-by pr-str (map sorted-value value)))
    :else value))

(defn- emit! [value output-path]
  (let [text (str (with-out-str (pprint/pprint (sorted-value value))) "\n")]
    (if output-path
      (do (io/make-parents (io/file output-path))
          (spit output-path text)
          (println (str "Wrote " output-path)))
      (print text))))

(defn- exact-keys! [value expected label]
  (when-not (map? value)
    (fail! :invalid-input-shape (str label " must be a map")
           {:label label :value value}))
  (let [actual (set (keys value))]
    (when-not (= expected actual)
      (fail! :invalid-input-fields (str label " has missing or unsupported fields")
             {:label label :expected expected :actual actual}))))

(defn- reject-unknown-fields! [value allowed label]
  (when-not (map? value)
    (fail! :invalid-input-shape (str label " must be a map")
           {:label label :value value}))
  (let [unknown (seq (remove allowed (keys value)))]
    (when unknown
      (fail! :unsupported-input-fields (str label " contains unsupported fields")
             {:label label :fields (vec unknown)}))))

(defn- hex-root! [root label]
  (when-not (and (string? root) (re-matches sha256-pattern root))
    (fail! :invalid-commitment-root
           (str label " must be 64 lowercase hexadecimal SHA-256 characters")
           {:label label :root root}))
  root)

(defn- normalize-request! [input]
  (reject-unknown-fields! input request-fields "request")
  (when-not (map? input)
    (fail! :invalid-request "Request must be an EDN map" {}))
  (doseq [row (:rows input)]
    (reject-unknown-fields! row request-row-fields "request row"))
  (let [normalized (try
                     (:canonical-request (pro-rata-request/normalize input))
                     (catch Exception error
                       (fail! :invalid-request "Invalid pro-rata request"
                              {:cause (.getMessage error)
                               :data (ex-data error)})))]
    normalized))

(defn derive-request
  "Derive the frozen pro-rata request identity using its owning package."
  [input]
  (let [canonical-request (normalize-request! input)
        preimage {:intent :projection-artifact :artifact canonical-request}
        root (pro-rata-identity/request-root canonical-request)]
    {:artifact/id request-id
     :artifact/schema request-id
     :hash/scheme "canonical-hash-scheme.v1"
     :hash/algorithm "sha256"
     :hash/domain request-domain
     :commitment/root root
     :canonical/preimage preimage
     :canonical/bytes (hc/canonical-bytes-hex preimage)
     :verification/status :recomputed
     :authority/admission :not-performed}))

(defn- checked-member [member]
  (reject-unknown-fields! member bundle-member-fields "bundle member")
  (let [{:keys [member/role artifact/id artifact/schema hash/scheme
                hash/algorithm hash/domain commitment/root commitment/status
                artifact/data]} member]
    (when-not (and (string? role) (not (str/blank? role)))
      (fail! :invalid-member-role "Bundle member role must be a non-empty string"
             {:role role}))
    (when-not (and (string? id) (not (str/blank? id))
                   (string? schema) (not (str/blank? schema)))
      (fail! :invalid-member-identity "Bundle member identity and schema are required"
             {:artifact/id id :artifact/schema schema}))
    (when-not (= "canonical-hash-scheme.v1" scheme)
      (fail! :unsupported-member-scheme "Unsupported member hash scheme"
             {:hash/scheme scheme}))
    (when-not (= "sha256" algorithm)
      (fail! :unsupported-member-algorithm "Only sha256 member commitments are supported"
             {:hash/algorithm algorithm}))
    (when-not (and (string? domain) (not (str/blank? domain)))
      (fail! :invalid-member-domain "Bundle member domain is required"
             {:hash/domain domain}))
    (let [derived (case status
                    :recompute
                    (do
                      (when (contains? member :commitment/root)
                        (fail! :supplied-root-on-recompute
                               "A recomputed member must not supply its commitment root"
                               {:artifact/id id}))
                      (when-not (= id request-id)
                        (fail! :unsupported-derivation
                               "No explicit derivation adapter for this artifact"
                               {:artifact/id id}))
                      (when-not (= schema request-id)
                        (fail! :artifact-schema-mismatch "Artifact schema does not match adapter"
                               {:artifact/id id :artifact/schema schema}))
                      (when-not (= domain request-domain)
                        (fail! :artifact-domain-mismatch "Artifact domain does not match adapter"
                               {:artifact/id id :hash/domain domain}))
                      (:commitment/root (derive-request data)))
                    :opaque
                    (do
                      (when (contains? member :artifact/data)
                        (fail! :opaque-member-has-data
                               "Opaque members must not include artifact source data"
                               {:artifact/id id}))
                      (hex-root! root "opaque member root"))
                    (fail! :invalid-member-status
                           "Member status must be :recompute or :opaque"
                           {:commitment/status status}))
          projection {:member/role role
                      :artifact/id id
                      :artifact/schema schema
                      :hash/scheme scheme
                      :hash/algorithm algorithm
                      :hash/domain domain
                      :commitment/root derived}]
      {:projection projection
       :verification {:member/role role
                      :artifact/id id
                      :commitment/status (if (= status :recompute)
                                           :recomputed
                                           :opaque-supplied)
                      :source-validation (if (= status :recompute)
                                           :passed
                                           :not-performed)}})))

(defn bundle-artifacts
  "Build a deterministic structural bundle; does not validate domain claims."
  [input]
  (exact-keys! input bundle-input-fields "bundle input")
  (when-not (= bundle-schema (:schema-version input))
    (fail! :unsupported-bundle-schema "Unsupported artifact bundle schema"
           {:schema-version (:schema-version input)}))
  (when-not (and (vector? (:members input)) (seq (:members input)))
    (fail! :invalid-bundle-members "Bundle members must be a non-empty vector" {}))
  (let [checked (mapv checked-member (:members input))
        projections (mapv :projection checked)
        sort-key (juxt :member/role :artifact/id :artifact/schema :hash/scheme
                       :hash/algorithm :hash/domain :commitment/root)
        ordered (vec (sort-by sort-key projections))
        duplicate-keys (map (juxt :member/role :artifact/id) ordered)]
    (when-not (= (count duplicate-keys) (count (distinct duplicate-keys)))
      (fail! :duplicate-bundle-member
             "Duplicate (member role, artifact identity) pairs are not allowed"
             {:duplicates (->> duplicate-keys frequencies
                               (filter (fn [[_ n]] (> n 1))) keys vec)}))
    (let [preimage {:schema-version bundle-schema
                    :hash/scheme "canonical-hash-scheme.v1"
                    :hash/algorithm "sha256"
                    :hash/domain bundle-domain
                    :ordering "lexicographic-role-id-schema-scheme-algorithm-domain-root.v1"
                    :duplicate-policy "reject-duplicate-role-and-artifact-id.v1"
                    :members ordered}
          output-root (hc/domain-hash :artifact-bundle-v1 preimage)
          by-key (into {} (map (fn [entry]
                                 [[(get-in entry [:verification :member/role])
                                   (get-in entry [:verification :artifact/id])]
                                  (:verification entry)])) checked)]
      {:bundle/schema bundle-schema
       :bundle/domain bundle-domain
       :bundle/root output-root
       :bundle/preimage preimage
       :canonical/bytes (hc/canonical-bytes-hex preimage)
       :members/verification (mapv (fn [member]
                                     (get by-key [(:member/role member)
                                                  (:artifact/id member)]))
                                   ordered)
       :semantic/interpretation :structural-only
       :authority/admission :not-performed})))

(defn compose-realized-statement
  "Reconstruct a realized-allocation-statement.v1 root from six parent roots."
  [input]
  (exact-keys! input statement-fields "realized allocation statement components")
  (doseq [[field root] input]
    (hex-root! root (name field)))
  (let [preimage (realized-statement/statement-preimage input)
        root (realized-statement/statement-root input)]
    {:artifact/id "realized-allocation-statement.v1"
     :artifact/schema realized-statement/schema-version
     :hash/scheme "canonical-hash-scheme.v1"
     :hash/algorithm "sha256"
     :hash/domain "REALIZED_ALLOCATION_STATEMENT_V1"
     :commitment/root root
     :canonical/preimage preimage
     :canonical/bytes (hc/canonical-bytes-hex preimage)
     :parent-roots/status :format-checked-only
     :verification/limitations
     [:parent-artifact-data-not-validated
      :source-authority-not-validated
      :proof-verification-not-performed]
     :authority/admission :not-performed}))

(defn- verification-result
  [profile expected recomputed details]
  (let [matches? (= expected recomputed)]
    (merge {:verification/profile profile
            :verification/status (if matches? :commitment-matched :commitment-mismatch)
            :verification/commitment-check (if matches? :matched :mismatched)
            :verification/recomputed-root recomputed
            :verification/expected-root expected
            :verification/cryptographic-proof :not-performed
            :authority/admission :not-performed}
           details)))

(defn- verify-recomputed
  [profile input-fields input derive-fn result-root-key details]
  (try
    (exact-keys! input (conj input-fields expected-root-field) "verification input")
    (hex-root! (get input expected-root-field) "expected commitment root")
    (let [derived (derive-fn (dissoc input expected-root-field))]
      (verification-result profile
                           (get input expected-root-field)
                           (get derived result-root-key)
                           details))
    (catch clojure.lang.ExceptionInfo error
      (merge {:verification/profile profile
              :verification/status :statement-invalid
              :verification/commitment-check :not-performed
              :verification/expected-root (get input expected-root-field)
              :verification/cryptographic-proof :not-performed
              :authority/admission :not-performed
              :verification/error {:reason (:reason (ex-data error))
                                   :message (.getMessage error)}}
             details))))

(defn verify-opaque-commitment
  "Check an opaque commitment descriptor's identity and root encoding only.

   This does not reconstruct source data or assert the statement's truth."
  [input]
  (exact-keys! input opaque-fields "opaque commitment")
  (when-not (and (string? (:artifact/id input)) (seq (:artifact/id input))
                 (string? (:artifact/schema input)) (seq (:artifact/schema input))
                 (string? (:hash/domain input)) (seq (:hash/domain input)))
    (fail! :invalid-opaque-identity "Opaque commitment identity, schema and domain are required" {}))
  (when-not (= "canonical-hash-scheme.v1" (:hash/scheme input))
    (fail! :unsupported-member-scheme "Unsupported opaque commitment hash scheme"
           {:hash/scheme (:hash/scheme input)}))
  (when-not (= "sha256" (:hash/algorithm input))
    (fail! :unsupported-member-algorithm "Unsupported opaque commitment algorithm"
           {:hash/algorithm (:hash/algorithm input)}))
  (hex-root! (:commitment/root input) "opaque commitment root")
  {:verification/profile "opaque-commitment.v1"
   :verification/status :opaque-unrecomputed
   :verification/descriptor-check :passed
   :verification/source-reconstruction :not-performed
   :verification/statement-truth :not-established
   :verification/cryptographic-proof :not-performed
   :authority/admission :not-performed
   :commitment input})

(defn verify-artifact
  "Narrow verification for the explicitly supported commitment profiles.

   Matching means the commitment was recomputed from the supplied inputs. It
   does not establish external source authenticity, proof validity or admission."
  [profile input]
  (case profile
    "pro-rata-allocation-request.v1"
    (verify-recomputed profile request-fields input derive-request :commitment/root
                       {:verification/source-data :validated-by-owner-adapter
                        :verification/statement-truth :not-established})
    "realized-allocation-statement.v1"
    (verify-recomputed profile statement-fields input compose-realized-statement :commitment/root
                       {:verification/source-data :parent-roots-format-only
                        :verification/statement-truth :not-established})
    "artifact-bundle.v1"
    (verify-recomputed profile bundle-input-fields input bundle-artifacts :bundle/root
                       {:verification/source-data :bundle-structure-recomputed
                        :verification/statement-truth :not-established})
    "opaque-commitment.v1"
    (verify-opaque-commitment input)
    (fail! :unsupported-verification-profile "No explicit verifier for profile"
           {:profile profile})))

(defn supported-artifacts []
  {:derivations [{:artifact/id request-id
                  :schema request-id
                  :domain request-domain
                  :owner :prf-pro-rata-allocation
                  :implementation 'prf.pro-rata.identity/request-root}]
   :compositions [{:artifact/id "realized-allocation-statement.v1"
                   :domain "REALIZED_ALLOCATION_STATEMENT_V1"
                   :owner :prf-pro-rata-allocation
                   :implementation 'prf.pro-rata.realized-statement/statement-root}]
   :bundle {:schema bundle-schema :domain bundle-domain
            :interpretation :structural-only}})

(defn- show-artifact [artifact-id]
  (if-not artifact-id
    (supported-artifacts)
    (or (some #(when (= artifact-id (:artifact/id %)) %)
              (concat (:derivations (supported-artifacts))
                      (:compositions (supported-artifacts))))
        (when (= artifact-id bundle-schema) (:bundle (supported-artifacts)))
        (fail! :unsupported-artifact "No explicit adapter or bundle definition for artifact"
               {:artifact/id artifact-id}))))

(defn- option-map [args]
  (loop [remaining args result {}]
    (if-let [option (first remaining)]
      (if (#{"--input" "--output" "--expected-root"} option)
        (if-let [value (second remaining)]
          (recur (nnext remaining) (assoc result option value))
          (fail! :missing-option-value (str "Missing value for " option)
                 {:option option}))
        (fail! :unknown-option "Unknown command option" {:option option}))
      result)))

(defn- run-profile [profile options]
  (let [input-path (get options "--input")
        output-path (get options "--output")]
    (when-not input-path
      (fail! :missing-input "--input is required" {}))
    (when (and (not= profile :verify) (contains? options "--expected-root"))
      (fail! :unexpected-option "--expected-root is only valid for verify" {}))
    (let [input (read-edn! input-path)
          input (if (and (= profile :verify)
                         (not= "opaque-commitment.v1" (:profile options)))
                  (assoc input expected-root-field (get options "--expected-root"))
                  input)
          _ (when (and (= profile :verify)
                       (not= "opaque-commitment.v1" (:profile options))
                       (nil? (get options "--expected-root")))
              (fail! :missing-expected-root "--expected-root is required for recomputation" {}))
          result (case profile
                   :derive-request (derive-request input)
                   :bundle (bundle-artifacts input)
                   :compose-statement (compose-realized-statement input)
                   :verify (verify-artifact (:profile options) input))]
      (emit! result output-path)
      (when (#{:commitment-mismatch :statement-invalid} (:verification/status result))
        (flush)
        (System/exit 1)))))

(defn -main [& args]
  (try
    (let [[verb & tail] args]
      (case verb
        "show" (do
                 (when (> (count tail) 1)
                   (fail! :unexpected-arguments "show accepts at most one artifact ID" {}))
                 (emit! (show-artifact (first tail)) nil))
        "derive" (let [[artifact-id & options] tail]
                   (when-not (= request-id artifact-id)
                     (fail! :unsupported-artifact "No derivation adapter for artifact"
                            {:artifact/id artifact-id}))
                   (run-profile :derive-request (option-map options)))
        "bundle" (run-profile :bundle (option-map tail))
        "compose" (let [[profile & options] tail]
                    (when-not (= "realized-allocation-statement.v1" profile)
                      (fail! :unsupported-composition "No composition adapter for profile"
                             {:profile profile}))
                    (run-profile :compose-statement (option-map options)))
        "verify" (let [[profile & options] tail]
                   (when-not profile
                     (fail! :missing-verification-profile "verify requires a supported profile" {}))
                   (run-profile :verify (assoc (option-map options) :profile profile)))
        (fail! :usage
               (str "Usage: canonical-artifacts show | derive " request-id
                    " --input <edn> [--output <edn>] | bundle --input <edn>"
                    " [--output <edn>] | compose realized-allocation-statement.v1"
                    " --input <edn> [--output <edn>] | verify <profile>"
                    " --input <edn> [--expected-root <hex>] [--output <edn>]")
               {})))
    (catch Exception error
      (binding [*out* *err*]
        (println (str "canonical-artifacts: " (.getMessage error)))
        (when-let [reason (:reason (ex-data error))]
          (println (str "reason: " reason))))
      (System/exit 2))))
