(ns resolver-sim.benchmark.public-result-admission
  "Single signed researcher-run-report → a public-result admission.

   Boundary intent
   ---------------
   VALID ADMISSION  — the supplied public key signed this self-consistent
                      report (report hash + manifest binding + outcome hash all
                      check out).  This is cryptographic admissibility, nothing
                      more.
   PUBLIC EPOCH MEMBER — a valid admission *included in an epoch*.  A later
                      layer.
   AUTHENTICATED RESEARCHER — researcher→key authority also verifies.  A later,
                      explicitly future layer.

   This namespace deliberately stops at VALID ADMISSION.  It carries no
   researcher-authentication authority and no epoch/corpus-membership
   semantics, matching the frozen replication-observation-set authorship
   boundary: signature verified against the supplied key, researcher→key
   binding unresolved.

   Verification semantics are delegated to verified-researcher-run, the single
   neutral primitive shared with the replication observation.

   Identity vocabulary: :report/root, :manifest/root and :outcome/root are
   exact projections of the canonical hashes already committed by the report
   (see the replication-observation-set convention); the only novel root is
   :admission/root, this artifact's own domain-separated commitment.

   The domain tag is the string \"public-result-admission.v1\" (domain-hash
   accepts string tags directly), so this namespace adds no new entry to the
   shared domain-tags registry."
  (:require [resolver-sim.benchmark.verified-researcher-run :as verified]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "public-result-admission.v1")
(def admission-domain "public-result-admission.v1")

(def canonical-public-key verified/canonical-public-key)

(defn- admission-body [report public-key-content]
  {:schema-version schema-version
   :report/root (:researcher-run-report/hash report)
   :manifest/root (:researcher-run-report/outcome-manifest-hash report)
   :outcome/root (:researcher-run-report/outcome-hash report)
   :admission/authenticity {:signature-verification :verified-against-supplied-key
                            :verifying-key public-key-content
                            :researcher-to-key-binding :unresolved}})

(defn- admission-root [body]
  (hash-ref/sha256-ref (hc/domain-hash admission-domain body)))

(defn build
  "Mechanically derive a public-result-admission from a report, its outcome
   manifest, and the offered public key content.  Verification goes through the
   shared verified-researcher-run primitive.  Every committed field is derived
   here; the caller cannot supply derived fields.

   Produces a VALID ADMISSION: the supplied key signed this self-consistent
   report.  It does NOT authenticate the researcher (binding stays unresolved)
   and does NOT make the report an epoch member (a later epoch-inclusion step)."
  [report manifest public-key-content]
  (let [{:keys [verifying-key]}
        (verified/verify {:report report
                          :manifest manifest
                          :public-key public-key-content})
        body (admission-body report verifying-key)]
    (assoc body :admission/root (admission-root body))))

(defn verify
  "Revalidate a report+manifest+key and require exact equality with the supplied
   admission.  Caller-supplied derived fields are therefore never trusted."
  [admission report manifest public-key-content]
  (try
    (let [expected (build report manifest public-key-content)
          valid? (= expected admission)]
      {:valid? valid?
       :reason (when-not valid? :derived-admission-mismatch)
       :expected expected})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :errors (ex-data e)})
    (catch Exception e
      {:valid? false :reason :verification-error
       :errors {:message (.getMessage e)}})))