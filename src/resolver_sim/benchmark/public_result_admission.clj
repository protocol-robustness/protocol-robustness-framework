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

   :admission/participation is derived from the signed report and commits the
   two-dimensional truth that anonymous-vs-named (presentation) is NOT the
   same axis as authenticated-vs-unauthenticated (identity-assurance).  V1
   only ever commits :identity-assurance :unresolved; :authenticated is
   refused unless a real binding verifier is referenced (no such verifier
   exists in V1, so any :authenticated attempt fails closed).

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

(def anonymous-presentation-ids
  "Researcher ids that mean 'the researcher presented no name'.  Presentation
   is pinned by the signed report's :researcher/id — never inferred from a
   display string alone."
  #{"anonymous" "anonymous-lab" "anonymous-visitor"})

(defn participation-from-report
  "Derive the honest :admission/participation projection from a signed report.

   presentation       — :anonymous | :declared.  A present, non-anonymous
                        :researcher/id is :declared; an absent id or a
                        recognised anonymous marker is :anonymous.
   identity-assurance — always :unresolved in V1: no researcher↔key binding
                        verifier exists, so no admission may claim otherwise.

   The projection is a pure function of the signed report, so verification
   (and epoch re-enumeration) recompute exactly the admission root."
  [report]
  (let [rid (:researcher/id report)
        anonymous? (or (nil? rid)
                       (and (keyword? rid) (= :anonymous rid))
                       (contains? anonymous-presentation-ids (str rid)))]
    {:presentation (if anonymous? :anonymous :declared)
     :identity-assurance :unresolved}))

(defn valid-participation?
  "V1 legal participation projections (closed shape, exactly two keys).

   :identity-assurance :authenticated is NOT legal: admitting it without a
   verified :identity-binding-root would create a second, weaker route to an
   identity claim and let presentation be mistaken for assurance."
  [p]
  (and (map? p)
       (contains? #{:anonymous :declared} (:presentation p))
       (= :unresolved (:identity-assurance p))
       (= 2 (count p))))

(defn validate-participation!
  "Fail closed on any participation projection that may not be committed.
   Throws ex-info with :reason :identity-assurance-escalation when a caller
   tries to claim :authenticated assurance without a verified binding."
  [p]
  (when-not (map? p)
    (throw (ex-info "admission participation must be a map"
                    {:participation p :reason :participation-invalid})))
  (when (= :authenticated (:identity-assurance p))
    (throw (ex-info "identity-assurance escalation is not permitted in V1: no researcher↔key binding verifier exists"
                    {:participation p :reason :identity-assurance-escalation})))
  (when-not (valid-participation? p)
    (throw (ex-info "invalid admission participation projection"
                    {:participation p :reason :participation-invalid}))))

(defn- admission-body [report public-key-content]
  (let [participation (participation-from-report report)]
    (validate-participation! participation)
    {:schema-version schema-version
     :report/root (:researcher-run-report/hash report)
     :manifest/root (:researcher-run-report/outcome-manifest-hash report)
     :outcome/root (:researcher-run-report/outcome-hash report)
     :admission/authenticity {:signature-verification :verified-against-supplied-key
                              :verifying-key public-key-content
                              :researcher-to-key-binding :unresolved}
     :admission/participation participation}))

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