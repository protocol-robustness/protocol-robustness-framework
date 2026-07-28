(ns resolver-sim.run.verdict-policy
  "Canonical, self-validating verdict-policy closure for reviewable run packages."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.run.force-authorisation-policy :as fa-policy]))

(def schema-version "verdict-policy.v1")
(def policy-family-id "canonical-verdict-policy")
(def domain "PRF_VERDICT_POLICY_V1")

;;; ============================================================
;;; Low-level helpers
;;; ============================================================

(defn sha-ref [file]
  (hash-ref/sha256-ref (lifecycle/sha256-file file)))

(defn policy-hash [artifact]
  (hash-ref/sha256-ref (canonical/domain-hash domain (dissoc artifact "policy_sha256"))))

(defn- check [pred err]
  (if pred [] [err]))

;;; ============================================================
;;; Field validation and normalisation
;;; ============================================================

(defn- validate-policy-fields!
  "Validate required fields and types. Returns the fields map or throws."
  [fields]
  (let [errors
        (into []
              cat
              [(check (string? (get fields "schema_version"))
                      (str "schema_version must be a string, got " (pr-str (get fields "schema_version"))))
               (check (string? (get fields "policy_id"))
                      (str "policy_id must be a string, got " (pr-str (get fields "policy_id"))))
               (check (map? (get fields "run"))
                      (str "run must be a map, got " (type (get fields "run"))))
               (check (string? (get-in fields ["run" "id"]))
                      (str "run.id must be a string, got " (pr-str (get-in fields ["run" "id"]))))
               (check (string? (get-in fields ["run" "type"]))
                      (str "run.type must be a string, got " (pr-str (get-in fields ["run" "type"]))))
               (check (map? (get fields "verdict"))
                      (str "verdict must be a map, got " (type (get fields "verdict"))))
               (check (string? (get-in fields ["verdict" "semantic_outcome"]))
                      (str "verdict.semantic_outcome must be a string, got " (pr-str (get-in fields ["verdict" "semantic_outcome"]))))
               (check (map? (get fields "registries"))
                      (str "registries must be a map, got " (type (get fields "registries"))))
               (check (string? (get-in fields ["registries" "evidence_policy_hash"]))
                      (str "registries.evidence_policy_hash must be a string, got " (pr-str (get-in fields ["registries" "evidence_policy_hash"]))))
               (check (map? (get fields "evaluator_implementation"))
                      (str "evaluator_implementation must be a map, got " (type (get fields "evaluator_implementation"))))
               (check (string? (get-in fields ["evaluator_implementation" "source_tree_hash"]))
                      (str "evaluator_implementation.source_tree_hash must be a string, got " (pr-str (get-in fields ["evaluator_implementation" "source_tree_hash"]))))
               (check (string? (get-in fields ["evaluator_implementation" "source_tree_hash_algorithm"]))
                      (str "evaluator_implementation.source_tree_hash_algorithm must be a string, got " (pr-str (get-in fields ["evaluator_implementation" "source_tree_hash_algorithm"]))))
               (check (map? (get fields "semantic_environment"))
                      (str "semantic_environment must be a map, got " (type (get fields "semantic_environment"))))
               (check (string? (get-in fields ["semantic_environment" "runner_id"]))
                      (str "semantic_environment.runner_id must be a string, got " (pr-str (get-in fields ["semantic_environment" "runner_id"]))))
               (check (string? (get-in fields ["semantic_environment" "protocol_id"]))
                      (str "semantic_environment.protocol_id must be a string, got " (pr-str (get-in fields ["semantic_environment" "protocol_id"]))))
               (check (map? (get fields "distribution_provenance"))
                      (str "distribution_provenance must be a map, got " (type (get fields "distribution_provenance"))))
               (check (contains? #{"release-distribution" "unverified-distribution" "source-classpath"}
                                 (get-in fields ["distribution_provenance" "mode"]))
                      (str "distribution_provenance.mode must be one of release-distribution, unverified-distribution, source-classpath, got " (pr-str (get-in fields ["distribution_provenance" "mode"]))))
               (check (vector? (get fields "immutable_inputs"))
                      (str "immutable_inputs must be a vector, got " (type (get fields "immutable_inputs"))))
               (check (string? (get fields "version_id"))
                      (str "version_id must be a string, got " (pr-str (get fields "version_id"))))
               ;; Lineage fields (optional but typed when present)
               (let [v (get fields "supersedes_policy_sha256")]
                 (check (or (nil? v) (string? v))
                        (str "supersedes_policy_sha256 must be a string when present, got " (pr-str v))))
               (let [v (get fields "supersession_reason")]
                 (check (or (nil? v) (string? v))
                        (str "supersession_reason must be a string when present, got " (pr-str v))))
               (let [v (get fields "policy_family_id")]
                 (check (or (nil? v) (string? v))
                        (str "policy_family_id must be a string when present, got " (pr-str v))))
               ;; Supersession policy (optional, governs how this policy can be superseded)
               (let [sp (get fields "supersession_policy")]
                 (check (or (nil? sp) (map? sp))
                        (str "supersession_policy must be a map when present, got " (type sp)))
                 (when (map? sp)
                   (into []
                         cat
                         [(check (contains? #{"research-force-authorisation" "governance-only"} (get sp "authorization_required"))
                                 (str "supersession_policy.authorization_required must be research-force-authorisation or governance-only, got " (pr-str (get sp "authorization_required"))))
                          (check (vector? (get sp "allowed_change_classes"))
                                 (str "supersession_policy.allowed_change_classes must be a vector, got " (pr-str (get sp "allowed_change_classes"))))
                          (check (string? (get sp "force_authorisation_policy_id"))
                                 (str "supersession_policy.force_authorisation_policy_id must be a string, got " (pr-str (get sp "force_authorisation_policy_id"))))
                          (check (string? (get sp "force_authorisation_policy_hash"))
                                 (str "supersession_policy.force_authorisation_policy_hash must be a string, got " (pr-str (get sp "force_authorisation_policy_hash"))))])))
               ;; Supersession record (present only on successor policies)
               (let [sr (get fields "supersession")]
                 (check (or (nil? sr) (map? sr))
                        (str "supersession must be a map when present, got " (type sr)))
                 (when (map? sr)
                   (into []
                         cat
                         [(check (string? (get sr "predecessor_policy_sha256"))
                                 (str "supersession.predecessor_policy_sha256 must be a string, got " (pr-str (get sr "predecessor_policy_sha256"))))
                          (check (vector? (get sr "change_classes"))
                                 (str "supersession.change_classes must be a vector, got " (pr-str (get sr "change_classes"))))
                          (let [h (get sr "authorization_instance_hash")]
                            (check (or (nil? h) (string? h))
                                   (str "supersession.authorization_instance_hash must be a string when present, got " (pr-str h))))
                          (let [h (get sr "force_authorisation_policy_hash")]
                            (check (or (nil? h) (string? h))
                                   (str "supersession.force_authorisation_policy_hash must be a string when present, got " (pr-str h))))])))])]
    (when (seq errors)
      (throw (ex-info "Verdict policy field validation failed" {:errors errors})))
    fields))

(defn- normalise-policy-fields
  "Apply defaults and sort order."
  [fields]
  (let [with-defaults (cond-> fields
                        true (assoc "schema_version" schema-version
                                    "policy_family_id" (get fields "policy_family_id" policy-family-id))
                        (nil? (get-in fields ["verdict" "mapping"]))
                        (assoc-in ["verdict" "mapping"] {"pass" "pass" "fail" "fail"}))]
    (update with-defaults "immutable_inputs"
            #(vec (sort-by (fn [e] (get e "logical_id")) %)))))

;;; ============================================================
;;; Shared canonical constructor
;;; ============================================================

(defn build-artifact
  "Canonical constructor: validates, normalises, and hashes a verdict-policy fields map.
   Returns the complete artifact with policy_sha256 computed.
   Every policy creation path (build, supersede) MUST pass through this function."
  [fields]
  (let [validated (validate-policy-fields! fields)
        normalised (normalise-policy-fields validated)]
    (assoc normalised "policy_sha256" (policy-hash normalised))))

;;; ============================================================
;;; Build from run inputs (initial creation)
;;; ============================================================

(defn build
  "Build a verdict policy artifact for a new run."
  [{:keys [run-id run-type policy-id version-id semantic-outcome inputs registries
           semantic-environment evaluator-implementation distribution-provenance]}]
  (build-artifact
   {"schema_version" schema-version
    "policy_id" policy-id
    "run" {"id" run-id "type" run-type}
    "verdict" {"semantic_outcome" semantic-outcome
               "mapping" {"pass" "pass" "fail" "fail"}}
    "registries" registries
    "evaluator_implementation" evaluator-implementation
    "distribution_provenance" distribution-provenance
    "immutable_inputs" (vec (sort-by #(get % "logical_id") inputs))
    "semantic_environment" semantic-environment
    "version_id" version-id
    "policy_family_id" policy-family-id}))

;;; ============================================================
;;; Internal artifact validation (no file system)
;;; ============================================================

(defn verify-artifact
  "Validate a parsed verdict-policy artifact's internal consistency.
   Checks schema, required fields, types, self-commitment hash,
   and optional lineage fields.
   Returns {:valid? true} or {:valid? false :errors [...]}.
   Does NOT touch the file system (no input file verification)."
  [artifact]
  (let [errors
        (into []
              cat
              [(check (= schema-version (get artifact "schema_version"))
                      (str "schema_version mismatch: expected " schema-version ", got " (pr-str (get artifact "schema_version"))))
               (check (string? (get artifact "policy_id"))
                      (str "policy_id must be a string, got " (pr-str (get artifact "policy_id"))))
               (check (map? (get artifact "run"))
                      (str "run must be a map, got " (type (get artifact "run"))))
               (check (string? (get-in artifact ["run" "id"]))
                      (str "run.id must be a string, got " (pr-str (get-in artifact ["run" "id"]))))
               (check (string? (get-in artifact ["run" "type"]))
                      (str "run.type must be a string, got " (pr-str (get-in artifact ["run" "type"]))))
               (check (= (get artifact "policy_sha256") (policy-hash artifact))
                      "policy_sha256 self-commitment does not match")
               (check (map? (get artifact "verdict"))
                      (str "verdict must be a map, got " (type (get artifact "verdict"))))
               (check (string? (get-in artifact ["verdict" "semantic_outcome"]))
                      (str "verdict.semantic_outcome must be a string, got " (pr-str (get-in artifact ["verdict" "semantic_outcome"]))))
               (check (map? (get artifact "registries"))
                      (str "registries must be a map, got " (type (get artifact "registries"))))
               (check (string? (get-in artifact ["registries" "evidence_policy_hash"]))
                      (str "registries.evidence_policy_hash must be a string, got " (pr-str (get-in artifact ["registries" "evidence_policy_hash"]))))
               (check (map? (get artifact "evaluator_implementation"))
                      (str "evaluator_implementation must be a map, got " (type (get artifact "evaluator_implementation"))))
               (check (string? (get-in artifact ["evaluator_implementation" "source_tree_hash"]))
                      (str "evaluator_implementation.source_tree_hash must be a string, got " (pr-str (get-in artifact ["evaluator_implementation" "source_tree_hash"]))))
               (check (string? (get-in artifact ["evaluator_implementation" "source_tree_hash_algorithm"]))
                      (str "evaluator_implementation.source_tree_hash_algorithm must be a string, got " (pr-str (get-in artifact ["evaluator_implementation" "source_tree_hash_algorithm"]))))
               (check (map? (get artifact "semantic_environment"))
                      (str "semantic_environment must be a map, got " (type (get artifact "semantic_environment"))))
               (check (string? (get-in artifact ["semantic_environment" "runner_id"]))
                      (str "semantic_environment.runner_id must be a string, got " (pr-str (get-in artifact ["semantic_environment" "runner_id"]))))
               (check (string? (get-in artifact ["semantic_environment" "protocol_id"]))
                      (str "semantic_environment.protocol_id must be a string, got " (pr-str (get-in artifact ["semantic_environment" "protocol_id"]))))
               (check (map? (get artifact "distribution_provenance"))
                      (str "distribution_provenance must be a map, got " (type (get artifact "distribution_provenance"))))
               (check (contains? #{"release-distribution" "unverified-distribution" "source-classpath"}
                                 (get-in artifact ["distribution_provenance" "mode"]))
                      (str "distribution_provenance.mode must be one of release-distribution, unverified-distribution, source-classpath, got " (pr-str (get-in artifact ["distribution_provenance" "mode"]))))
               (check (vector? (get artifact "immutable_inputs"))
                      (str "immutable_inputs must be a vector, got " (type (get artifact "immutable_inputs"))))
               (check (string? (get artifact "version_id"))
                      (str "version_id must be a string, got " (pr-str (get artifact "version_id"))))
               (check (string? (get artifact "policy_family_id"))
                      (str "policy_family_id must be a string, got " (pr-str (get artifact "policy_family_id"))))
               ;; Predecessor link validation (optional fields)
               (let [v (get artifact "supersedes_policy_sha256")]
                 (check (or (nil? v) (string? v))
                        (str "supersedes_policy_sha256 must be a string when present, got " (pr-str v))))
               (let [v (get artifact "supersession_reason")]
                 (check (or (nil? v) (string? v))
                        (str "supersession_reason must be a string when present, got " (pr-str v))))
               ;; Supersession policy validation
               (let [sp (get artifact "supersession_policy")]
                 (check (or (nil? sp) (map? sp))
                        (str "supersession_policy must be a map when present, got " (type sp)))
                 (when (map? sp)
                   (into []
                         cat
                         [(check (contains? #{"research-force-authorisation" "governance-only"} (get sp "authorization_required"))
                                 (str "supersession_policy.authorization_required must be research-force-authorisation or governance-only, got " (pr-str (get sp "authorization_required"))))
                          (check (vector? (get sp "allowed_change_classes"))
                                 (str "supersession_policy.allowed_change_classes must be a vector, got " (pr-str (get sp "allowed_change_classes"))))
                          (check (string? (get sp "force_authorisation_policy_id"))
                                 (str "supersession_policy.force_authorisation_policy_id must be a string, got " (pr-str (get sp "force_authorisation_policy_id"))))
                          (check (string? (get sp "force_authorisation_policy_hash"))
                                 (str "supersession_policy.force_authorisation_policy_hash must be a string, got " (pr-str (get sp "force_authorisation_policy_hash"))))])))
               ;; Supersession record validation
               (let [sr (get artifact "supersession")]
                 (check (or (nil? sr) (map? sr))
                        (str "supersession must be a map when present, got " (type sr)))
                 (when (map? sr)
                   (into []
                         cat
                         [(check (string? (get sr "predecessor_policy_sha256"))
                                 (str "supersession.predecessor_policy_sha256 must be a string, got " (pr-str (get sr "predecessor_policy_sha256"))))
                          (check (vector? (get sr "change_classes"))
                                 (str "supersession.change_classes must be a vector, got " (pr-str (get sr "change_classes"))))
                          (let [h (get sr "authorization_instance_hash")]
                            (check (or (nil? h) (string? h))
                                   (str "supersession.authorization_instance_hash must be a string when present, got " (pr-str h))))
                          (let [h (get sr "force_authorisation_policy_hash")]
                            (check (or (nil? h) (string? h))
                                   (str "supersession.force_authorisation_policy_hash must be a string when present, got " (pr-str h))))])))])]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

;;; ============================================================
;;; Change class computation
;;; ============================================================

(def ^:private change-class-paths
  {"run" "identity"
   "verdict" "verdict"
   "registries" "registries"
   "evaluator_implementation" "evaluator"
   "distribution_provenance" "distribution"
   "semantic_environment" "environment"
   "immutable_inputs" "inputs"
   "policy_id" "identity"
   "policy_family_id" "metadata"})

(defn compute-change-classes
  "Given the predecessor and successor verdict-policy artifacts
   (both without policy_sha256), compute the set of affected
   change classes by structural diff.
   Returns a sorted vector of strings like [\"metadata\" \"registries\"].
   The caller-supplied :change-class metadata is validated against
   this computed result — the diff is authoritative."
  [predecessor successor]
  (let [ignore #{"policy_sha256" "supersedes_policy_sha256"
                 "supersession_reason" "supersession" "version_id"
                 "supersession_policy"}
        pred-clean (apply dissoc predecessor ignore)
        succ-clean (apply dissoc successor ignore)
        explicit (into #{}
                       (comp (filter (fn [[k v]]
                                       (not= v (get succ-clean k))))
                             (map first)
                             (keep change-class-paths))
                       pred-clean)
        added (into #{}
                    (comp (remove (fn [k] (contains? pred-clean k)))
                          (keep change-class-paths))
                    (keys succ-clean))]
    (vec (sort (set/union explicit added)))))

;;; ============================================================
;;; Write
;;; ============================================================

(defn write!
  "Atomically write a verdict policy artifact to file.
   Refuses to overwrite an existing file.
   Use supersede to produce a successor artifact."
  [file artifact]
  (when (.exists (io/file (str file)))
    (throw (ex-info "Refusing to overwrite existing verdict policy file"
                    {:path (str file)})))
  (lifecycle/atomic-json! file artifact)
  artifact)

;;; ============================================================
;;; Supersede (create a new policy that supersedes an existing one)
;;; ============================================================

(defn supersede
  "Create a new verdict policy superseding an existing one.
   existing    - parsed artifact map of the predecessor
   output-path - java.io.File for the new artifact
   changes     - map of field overrides to apply to the predecessor
   metadata    - map with:
     :version-id (string, required) — new version identifier
     :reason     (string, required) — supersession reason
     :authorization (map, optional) — force-authorisation evidence
       {:instance       map  — from researcher-fa/build-authorisation
        :provenance     map  — optional, governance provenance
        :fa-policy      map  — resolved force-authorisation policy artifact}

   Enforces:
   - existing artifact verifies before it can be superseded
   - output path does not already exist
   - version_id differs from predecessor
   - supersession_policy on predecessor is respected
   - change classes are derived from actual diff (not caller-supplied)
   - force-authorisation is verified when authorisation_required is set
   Returns the new artifact with supersession record.
   The predecessor file is never modified."
  [existing output-path changes metadata]
  (let [prev-verify (verify-artifact existing)]
    (when-not (:valid? prev-verify)
      (throw (ex-info "Existing artifact is not internally valid; cannot supersede"
                      {:errors (:errors prev-verify)}))))
  (let [prev-hash (get existing "policy_sha256")
        prev-version (get existing "version_id")
        new-version (:version-id metadata)
        reason (:reason metadata)
        auth (:authorization metadata)]
    (when (= new-version prev-version)
      (throw (ex-info "Supersede requires a different version_id"
                      {:previous prev-version :attempted new-version})))
    (when (.exists output-path)
      (throw (ex-info "Output path already exists" {:path (str output-path)})))
    (let [base (dissoc existing "policy_sha256" "supersession_policy")
          merged (merge base changes)
          pre-hash (assoc merged
                          "version_id" new-version
                          "supersedes_policy_sha256" prev-hash
                          "supersession_reason" reason)
          pre-artifact (build-artifact pre-hash)
          successor-policy-hash (get pre-artifact "policy_sha256")
          ss-policy (get existing "supersession_policy")
          computed-classes (compute-change-classes
                            (dissoc existing "policy_sha256")
                            (dissoc pre-artifact "policy_sha256"))]
      (if ss-policy
        ;; Authorized supersession — enforce supersession_policy
        (let [allowed (set (get ss-policy "allowed_change_classes"))
              unauthorized (remove allowed computed-classes)]
          (when (seq unauthorized)
            (throw (ex-info "Supersession not allowed: change classes not in allowed_change_classes"
                            {:disallowed-classes (vec unauthorized)
                             :allowed-classes allowed
                             :computed-classes computed-classes
                             :supersession-policy ss-policy})))
          (let [auth-required (get ss-policy "authorization_required")]
            (when (and auth-required (nil? auth))
              (throw (ex-info "Supersession requires authorization but none provided"
                              {:authorization-required auth-required
                               :supersession-policy ss-policy})))
            (let [instance (:instance auth)
                  fa-policy-artifact (:fa-policy auth)
                  fa-policy-hash (get fa-policy-artifact "policy_sha256")
                  committed-fa-hash (get ss-policy "force_authorisation_policy_hash")]
              ;; Verify the force-authorisation policy hash matches
              (when (and committed-fa-hash (not= committed-fa-hash fa-policy-hash))
                (throw (ex-info "Force-authorisation policy hash mismatch"
                                {:committed committed-fa-hash :provided fa-policy-hash})))
              ;; Verify the force-authorisation policy is internally valid
              (let [fa-verify (fa-policy/verify-artifact fa-policy-artifact)]
                (when-not (:valid? fa-verify)
                  (throw (ex-info "Force-authorisation policy is not valid"
                                  {:errors (:errors fa-verify)}))))
              ;; Verify authorization instance is structurally valid
              (when-not (researcher-fa/authorisation-valid? instance)
                (throw (ex-info "Force-authorisation instance is not valid"
                                {:instance instance})))
              (when-not (researcher-fa/authorisation-approved? instance)
                (throw (ex-info "Force-authorisation instance is not approved"
                                {:status (researcher-fa/authorisation-status instance)})))
              ;; Build scope hash that binds this operation to the authorization
              (let [scope-map {:operation "policy/supersede"
                               :policy-family-id (get existing "policy_family_id")
                               :predecessor-policy-hash prev-hash
                               :proposed-policy-hash successor-policy-hash
                               :change-classes computed-classes}
                    instance-hash (str "sha256:" (canonical/domain-hash
                                                  "PRF_AUTHORISATION_INSTANCE_V1"
                                                  (dissoc instance :authorisation/approvals :authorisation/dissents)))
                    updated (assoc pre-hash
                                   "version_id" new-version
                                   "supersedes_policy_sha256" prev-hash
                                   "supersession_reason" reason
                                   "supersession"
                                   {"predecessor_policy_sha256" prev-hash
                                    "change_classes" computed-classes
                                    "authorization_instance_hash" instance-hash
                                    "authorization_provenance_hash" (str "sha256:" (canonical/domain-hash
                                                                                    "PRF_AUTHORISATION_PROVENANCE_V1"
                                                                                    (or (:provenance auth) {})))
                                    "force_authorisation_policy_hash" fa-policy-hash})
                    artifact (build-artifact updated)]
                (write! output-path artifact)
                artifact))))

          ;; No supersession policy — simple supersede without authorization
        (let [updated (assoc pre-hash
                             "version_id" new-version
                             "supersedes_policy_sha256" prev-hash
                             "supersession_reason" reason
                             "supersession"
                             {"predecessor_policy_sha256" prev-hash
                              "change_classes" computed-classes
                              "force_authorisation_policy_hash" nil})
              artifact (build-artifact updated)]
          (write! output-path artifact)
          artifact)))))

;;; ============================================================
;;; Full verification (artifact + file system + run identity)
;;; ============================================================

(defn- contained-file [root path]
  (let [root-path (.toAbsolutePath (.normalize (.toPath (io/file root))))
        file (.toAbsolutePath (.normalize (.toPath (io/file root path))))]
    (when (.startsWith file root-path) (.toFile file))))

(defn verify!
  "Validate the artifact's schema, self-commitment, and every package-local input.
   Returns {:valid? true} or {:valid? false :errors [msg ...]}.
   The caller supplies the expected run identity because completion is authoritative.
   Delegates internal consistency checks to verify-artifact."
  [root artifact expected-run-type expected-run-id]
  (let [internal (verify-artifact artifact)
        inputs (get artifact "immutable_inputs")
        input-errors
        (cond
          (not (vector? inputs)) [(str "immutable_inputs must be a vector, got " (type inputs))]
          :else
          (into []
                (comp (map-indexed
                       (fn [i entry]
                         (let [path (get entry "path")
                               file (and (string? path) (contained-file root path))]
                           (cond
                             (not (string? path)) [(str "input[" i "] has no string :path, got " (pr-str path))]
                             (nil? file) [(str "input[" i "] path escapes root: " (pr-str path))]
                             (not (.isFile file)) [(str "input[" i "] file not found: " path)]
                             (not (= (get entry "sha256") (sha-ref file))) [(str "input[" i "] sha256 mismatch for " path)]
                             :else []))))
                      cat)
                inputs))
        run-errors
        (into []
              cat
              [(check (= expected-run-id (get-in artifact ["run" "id"]))
                      (str "run id mismatch: expected " (pr-str expected-run-id) ", got " (pr-str (get-in artifact ["run" "id"]))))
               (check (= expected-run-type (get-in artifact ["run" "type"]))
                      (str "run type mismatch: expected " (pr-str expected-run-type) ", got " (pr-str (get-in artifact ["run" "type"]))))])
        all-errors (into (vec (:errors internal [])) cat [input-errors run-errors])]
    (if (seq all-errors)
      {:valid? false :errors all-errors}
      {:valid? true})))

;;; ============================================================
;;; Draft replacement (test-only, visibly unsafe)
;;; ============================================================

(defn replace-draft!
  "Replace a draft (non-authoritative) verdict policy file in-place.
   Refuses to operate on files that appear finalised (valid self-commitment).
   Only intended for development fixtures where the file is not yet
   referenced by any package index or external commitment.
   Returns the new artifact."
  [file f]
  (let [artifact (json/read-str (slurp file))]
    (when (= (get artifact "policy_sha256") (policy-hash artifact))
      (throw (ex-info "replace-draft! refused: artifact has valid self-commitment (appears finalised)"
                      {:path (str file)}))))
  (let [artifact (json/read-str (slurp file))
        new-draft (f artifact)
        updated (assoc new-draft "policy_sha256" (policy-hash new-draft))]
    (lifecycle/atomic-json! file updated)
    updated))
