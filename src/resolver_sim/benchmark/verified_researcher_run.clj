(ns resolver-sim.benchmark.verified-researcher-run
  "The single neutral production verification primitive for a signed
   researcher-run-report against an outcome manifest under a supplied public key.

   This is more fundamental than either consumer (the replication observation
   and the public-result admission).  Both delegate here so they use exactly the
   same verification semantics:

     report validation
     canonical report-hash / signing-preimage reconstruction
     real Ed25519 signature verification (supplied key)
     manifest validation
     report → manifest binding
     recomputed outcome-hash equality

   It carries no authority semantics: it establishes that the supplied public
   key signed a self-consistent report.  Researcher→key binding stays
   unresolved; no epoch/corpus membership is implied.

   Preimage discipline (pinned by test): the signed preimage always has
   :researcher-run-report/hash set to nil and :researcher/signature removed,
   regardless of whether the report's hash field is absent, nil, or populated.
   The committed report-hash is therefore never part of the signed content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.researcher-run-report :as report]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def report-schema-version "researcher-run-report.v1")

(defn fail!
  "Throw a structured rejection with :reason."
  [reason data]
  (throw (ex-info (str "Invalid verified researcher run: " (name reason))
                  (assoc data :reason reason))))

(defn report-root
  "The canonical report identity: the committed report-hash (or its /root alias)."
  [r]
  (or (:researcher-run-report/root r)
      (:researcher-run-report/hash r)))

(defn canonical-public-key
  "The canonical, machine-independent representation of a supplied public key:
   the OpenSSH 'ssh-ed25519 <b64>' line with any trailing comment stripped.
   Deterministic for a given key, so derived roots never depend on local machine
   state (paths, hosts, comments)."
  [content]
  (let [tokens (str/split (str/trim content) #"\s+")]
    (str (first tokens) " " (second tokens))))

(defn signed-preimage
  "The exact bytes a researcher signs: the report with :researcher/signature
   removed and :researcher-run-report/hash set to nil.  Deterministic whether
   the report's hash field is absent, nil, or populated."
  [report]
  (-> report
      (dissoc :researcher/signature)
      (assoc :researcher-run-report/hash nil)))

(defn report-hash
  "Recompute the canonical report-hash from the signed preimage:
   sha256:<hex> = sha256(domain-tag || canonical-bytes(preimage))."
  [report]
  (hash-ref/sha256-ref (hc/domain-hash :researcher-run-report (signed-preimage report))))

(defn report-valid?
  "Structural self-consistency of a loaded report.  The report-hash is not
   recomputed here; it is checked against the signed preimage during signature
   verification."
  [report]
  (and (= report-schema-version (:schema-version report))
       (some? (:researcher/id report))
       (some? (:researcher-run-report/outcome-hash report))
       (some? (:researcher-run-report/outcome-manifest-hash report))
       (some? (:benchmark/content-root report))
       (some? (:researcher-run-report/hash report))))

(defn verify-report-signature
  "Verify that the supplied public key signed this report: the committed
   report-hash must equal the signed-preimage reconstruction and the Ed25519
   signature must verify under the supplied key content.  Machine-independent."
  [report public-key-content]
  (let [sig (:researcher/signature report)]
    (if-not (map? sig)
      {:valid? false :reason "no signature present"}
      (let [expected (report-hash report)
            actual (:researcher-run-report/hash report)]
        (if-not (= expected actual)
          {:valid? false :reason "report hash mismatch"}
          (try
            (let [valid? (signing/verify-signature-with-public-key-content
                          (subs actual (count "sha256:"))
                          (:value sig)
                          public-key-content)]
              {:valid? valid?
               :reason (when-not valid? "signature does not verify")})
            (catch Exception _
              {:valid? false :reason "signature does not verify"})))))))

(defn- key-source->content
  "Read a supplied key source (a filesystem path or inline key content) into
   content.  Inline OpenSSH/PKCS#8 content is passed through; a path to an
   existing file is slurped."
  [source]
  (let [s (str source)
        f (io/file s)]
    (if (.exists f) (slurp f) s)))

(defn- resolve-key-content
  "Resolve the verifying key content from an entry: an explicit :public-key
   (content), :public-key-path, or a :public-key-resolver applied to the report."
  [entry report-value]
  (let [resolver (:public-key-resolver entry)
        supplied (or (:public-key entry) (:public-key-path entry))]
    (cond
      resolver (key-source->content (resolver report-value))
      supplied (key-source->content supplied)
      :else (fail! :missing-public-key {:report-root (report-root report-value)}))))

(defn verify
  "Verify one signed researcher-run-report against its outcome manifest under the
   supplied key (resolver, path, or content).

   Returns a verified map:
     {:report :manifest :report-root :manifest-root :outcome-root :verifying-key}
   or throws with a structured :reason.

   Reason vocabulary (shared by both consumers):
     :invalid-report :missing-report-hash :missing-outcome-manifest
     :missing-public-key :invalid-report-signature :invalid-outcome-manifest
     :report-manifest-mismatch :outcome-hash-mismatch"
  [{:keys [report manifest] :as entry}]
  (when-not (map? report)
    (fail! :invalid-report {:entry entry}))
  (when-not (:researcher-run-report/hash report)
    (fail! :missing-report-hash {:report report}))
  (when-not (map? manifest)
    (fail! :missing-outcome-manifest {:report-root (report-root report)}))
  (let [root (report-root report)
        public-key-content (resolve-key-content entry report)]
    (when-not (report-valid? report)
      (fail! :invalid-report {:report-root root}))
    (let [sig (verify-report-signature report public-key-content)]
      (when-not (:valid? sig)
        (fail! :invalid-report-signature {:report-root root :detail sig}))
      (let [manifest-check (outcome/validate-manifest manifest)
            binding (report/verify-against-manifest report manifest)
            expected (outcome/outcome-hash manifest)
            actual (:researcher-run-report/outcome-hash report)]
        (when-not (:valid? manifest-check)
          (fail! :invalid-outcome-manifest
                 {:report-root root :errors (:errors manifest-check)}))
        (when-not (:valid? binding)
          (fail! :report-manifest-mismatch {:report-root root :binding binding}))
        (when-not (= expected actual)
          (fail! :outcome-hash-mismatch
                 {:report-root root :expected expected :actual actual}))
        {:report report
         :manifest manifest
         :report-root root
         :manifest-root (:researcher-run-report/outcome-manifest-hash report)
         :outcome-root (:researcher-run-report/outcome-hash report)
         :verifying-key (canonical-public-key public-key-content)}))))