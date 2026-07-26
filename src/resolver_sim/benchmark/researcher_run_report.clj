(ns resolver-sim.benchmark.researcher-run-report
  "Researcher benchmark run report: a signed attestation binding a
   researcher identity to a canonical benchmark outcome.
   
   The outcome-hash excludes researcher identity, signature, timestamp,
   runner identity and environment identity — enabling cross-researcher
   outcome equality.
   
   The run-report includes the outcome-manifest-hash for cross-validation
   against the referenced outcome manifest. Validation verifies exact
   equality between embedded execution fields and outcome-manifest fields."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.outcome-manifest :as outcome-manifest]))

(def ^:const schema-version "researcher-run-report.v1")

(defn build-report
  "Build a researcher run report.
   
   outcome-manifest — the canonical benchmark-outcome.v1 map
   researcher-id    — string identifying the researcher
   runner-info      — map with :runner/id, :source-tree-hash, :distribution-hash, :environment-hash
   evidence-refs    — map with :evidence-dag-root, :event-evidence-root, :execution-log-root
   run-id           — string identifying this run
   
   Returns the report map WITHOUT :researcher/signature.
   Call sign-report! to add the signature."
  [{:keys [outcome-manifest researcher-id runner-info evidence-refs run-id]}]
  (let [o-hash (outcome-manifest/outcome-hash outcome-manifest)]
    {:schema-version schema-version
     :researcher/id researcher-id
     :run/id run-id
     :benchmark/content-root (:benchmark/content-root outcome-manifest)
     :benchmark/model-root (:benchmark/model-root outcome-manifest)
     :benchmark/evaluation-policy-root (:benchmark/evaluation-policy-root outcome-manifest)
     :execution/content-root (:benchmark/content-root outcome-manifest)
     :execution/model-root (:benchmark/model-root outcome-manifest)
     :execution/model-instance-root (:execution/model-instance-root outcome-manifest)
     :execution/plan-root (:execution/plan-root outcome-manifest)
     :execution/parameter-domain-root (:execution/parameter-domain-root outcome-manifest)
     :execution/sampling-policy-root (:execution/sampling-policy-root outcome-manifest)
     :execution/realised-parameter-set-root (:execution/realised-parameter-set-root outcome-manifest)
     :execution/generated-case-set-root (:execution/generated-case-set-root outcome-manifest)
     :researcher-run-report/outcome-hash o-hash
     :researcher-run-report/outcome-manifest-hash (:benchmark-outcome/hash outcome-manifest)
     :runner
     {:runner/id (:runner/id runner-info)
      :source-tree-hash (:source-tree-hash runner-info)
      :distribution-hash (:distribution-hash runner-info)
      :environment-hash (:environment-hash runner-info)}
     :evidence
     {:evidence-dag-root (:evidence-dag-root evidence-refs)
      :event-evidence-root (:event-evidence-root evidence-refs)
      :execution-log-root (:execution-log-root evidence-refs)}
     :researcher-run-report/hash nil
     :researcher/signature nil}))

;; ── Signature helpers ─────────────────────────────────────────────────────

(defn- signature-preimage
  "The data signed by the researcher.
   
   Excludes :researcher/signature so the preimage is deterministic before signing."
  [report]
  (dissoc report :researcher/signature))

(defn pre-sign-checks
  "Pre-condition checks that must pass BEFORE signing a run report.
   
   Verifies:
     1. Schema version is recognised
     2. Researcher identity is present
     3. Benchmark content root is present (model identity)
     4. Model root is present (model version pinning)
     5. Outcome hash is present (cross-researcher comparison anchor)
     6. Outcome manifest hash is present (referenced manifest identity)
     7. All execution identity fields are present (replication key)
     8. Runner identity is present (execution attribution)
   
   Unlike validate-report (post-hoc), this does NOT require a report hash
   or signature — those are results of signing, not preconditions.
   
   Returns {:pre-sign-valid? bool :errors [string]}."
  [report]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version report))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version report))))
    (when-not (some? (:researcher/id report))
      (swap! errors conj "missing :researcher/id"))
    (when-not (some? (:benchmark/content-root report))
      (swap! errors conj "missing :benchmark/content-root"))
    (when-not (some? (:benchmark/model-root report))
      (swap! errors conj "missing :benchmark/model-root"))
    (when-not (some? (:researcher-run-report/outcome-hash report))
      (swap! errors conj "missing :researcher-run-report/outcome-hash"))
    (when-not (some? (:researcher-run-report/outcome-manifest-hash report))
      (swap! errors conj "missing :researcher-run-report/outcome-manifest-hash"))
    (doseq [field [:execution/content-root :execution/model-root
                   :execution/model-instance-root :execution/plan-root
                   :execution/parameter-domain-root :execution/sampling-policy-root
                   :execution/realised-parameter-set-root :execution/generated-case-set-root]]
      (when-not (some? (get report field))
        (swap! errors conj (str "missing " field " (required for replication key)"))))
    (let [runner (:runner report)]
      (when-not (some? (:runner/id runner))
        (swap! errors conj "missing runner/id"))
      (when (and (some? (:runner/id runner))
                 (not (some? (:source-tree-hash runner))))
        (swap! errors conj "missing runner source-tree-hash"))
      (when (and (some? (:runner/id runner))
                 (not (some? (:distribution-hash runner))))
        (swap! errors conj "missing runner distribution-hash"))
      (when (and (some? (:runner/id runner))
                 (not (some? (:environment-hash runner))))
        (swap! errors conj "missing runner environment-hash")))
    {:pre-sign-valid? (empty? @errors) :errors @errors}))

(defn sign-report!
  "Sign the run report with the researcher's Ed25519 key.
   
   Runs pre-sign-checks before signing. Returns {:ok true :report ...}
   on success, {:ok false :errors [...]} on pre-condition failure.
   
   The signed report includes :researcher-run-report/hash and
   :researcher/signature."
  [report private-key-path & [password]]
  (let [pre-checks (pre-sign-checks report)]
    (if-not (:pre-sign-valid? pre-checks)
      {:ok false :errors (:errors pre-checks)
       :stage :pre-sign-validation}
      (try
        (let [preimage (signature-preimage report)
              report-hash (hc/domain-hash :researcher-run-report preimage)
              signature (signing/sign-hash report-hash private-key-path password)]
          {:ok true
           :report (assoc report
                          :researcher-run-report/hash (str "sha256:" report-hash)
                          :researcher/signature
                          {:algorithm :ed25519
                           :value signature
                           :signed-at (str (java.time.Instant/now))})})
        (catch Exception e
          {:ok false :errors [(str "signing failed: " (.getMessage e))]
           :stage :signing-error})))))

;; ── Verification ──────────────────────────────────────────────────────────

(defn report-valid?
  "Quick structural check for builder-produced reports.
   Does not verify the cryptographic signature."
  [report]
  (and (= schema-version (:schema-version report))
       (some? (:researcher/id report))
       (some? (:researcher-run-report/outcome-hash report))
       (some? (:benchmark/content-root report))))

(defn validate-report
  "Standalone validator for a loaded researcher run report.
   
   Checks schema version, required fields, and structural integrity.
   Does not verify the cryptographic signature — use verify-report-signature.
   
   Returns {:valid? bool :errors [string]}."
  [report]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version report))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version report))))
    (when-not (some? (:researcher/id report))
      (swap! errors conj "missing :researcher/id"))
    (when-not (some? (:researcher-run-report/outcome-hash report))
      (swap! errors conj "missing :researcher-run-report/outcome-hash"))
    (when-not (some? (:benchmark/content-root report))
      (swap! errors conj "missing :benchmark/content-root"))
    (when (:researcher-run-report/hash report)
      (let [preimage (signature-preimage report)
            expected (str "sha256:" (hc/domain-hash :researcher-run-report preimage))]
        (when-not (= expected (:researcher-run-report/hash report))
          (swap! errors conj (str "report-hash mismatch: declared "
                                  (:researcher-run-report/hash report)
                                  " computed " expected)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn- execution-field-mismatches
  "Compare embedded execution fields in the report against the
   outcome manifest fields. Returns a vector of mismatch descriptions.
   
   Returns an empty vector when all fields match."
  [report outcome-manifest]
  (let [pairs [[:benchmark/content-root :benchmark/content-root]
               [:benchmark/model-root :benchmark/model-root]
               [:benchmark/evaluation-policy-root :benchmark/evaluation-policy-root]
               [:execution/content-root :benchmark/content-root]
               [:execution/model-instance-root :execution/model-instance-root]
               [:execution/plan-root :execution/plan-root]
               [:execution/parameter-domain-root :execution/parameter-domain-root]
               [:execution/sampling-policy-root :execution/sampling-policy-root]
               [:execution/realised-parameter-set-root :execution/realised-parameter-set-root]
               [:execution/generated-case-set-root :execution/generated-case-set-root]]]
    (vec (keep (fn [[report-key manifest-key]]
                 (let [rv (get report report-key)
                       mv (get outcome-manifest manifest-key)]
                   (when (not= rv mv)
                     {:field report-key
                      :report-value rv
                      :manifest-value mv})))
               pairs))))

(defn verify-against-manifest
  "Verify that a run report's embedded execution fields match the
   outcome manifest they reference.
   
   Returns {:valid? bool :mismatches [mismatch-map]}."
  [report outcome-manifest]
  (let [expected-manifest-hash (:benchmark-outcome/hash outcome-manifest)
        actual-manifest-hash (:researcher-run-report/outcome-manifest-hash report)
        manifest-match? (= expected-manifest-hash actual-manifest-hash)
        mismatches (execution-field-mismatches report outcome-manifest)]
    {:valid? (and manifest-match? (empty? mismatches))
     :manifest-hash-match? manifest-match?
     :mismatches mismatches}))

(defn verify-report-signature
  "Verify the Ed25519 signature on a researcher run report.
   Returns {:valid? bool :reason string | nil}."
  [report public-key-path]
  (let [signature (:researcher/signature report)]
    (if-not signature
      {:valid? false :reason "no signature present"}
      (let [preimage (signature-preimage report)
            expected-hash (hc/domain-hash :researcher-run-report preimage)
            actual-hash (:researcher-run-report/hash report)]
        (if-not (= (str "sha256:" expected-hash) actual-hash)
          {:valid? false :reason "report hash mismatch"}
          (try
            (let [stripped (clojure.string/replace actual-hash #"^sha256:" "")
                  valid? (signing/verify-signature
                          stripped
                          (:value signature)
                          public-key-path)]
              {:valid? valid?
               :reason (when-not valid? "signature does not verify")})
            (catch Exception e
              {:valid? false
               :reason (str "signature verification error: " (.getMessage e))})))))))
