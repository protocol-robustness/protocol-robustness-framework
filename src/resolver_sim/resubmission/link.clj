(ns resolver-sim.resubmission.link
  "Resubmission link artifact (resubmission-link.artifact.v1).

   The link is a standalone, content-addressed artifact: it carries artifact
   identity fields (:schema-version / :artifact/kind / :artifact/verifier) so
   verify-artifact establishes schema, canonical preimage, content hash, and
   canonical commitment validity; the publisher envelope commits its artifact
   hash; and it is conditionally required when a run declares a resubmission.

   Canonical contract:

     resubmission-hash = \"sha256:\" + domain-hash(
         \"prf.researcher-resubmission.v1\",
         canonical-bytes-v2(unsigned-link-projection))

   The unsigned projection excludes ONLY :resubmission/hash and
   :researcher/signature. The researcher signature covers exactly the same
   unsigned projection bytes.

   Derived identities:
     family-id      = \"sha256:\" + domain-hash(\"prf.resubmission-family.v1\",
                          research-subject-root)
     idempotency-key= \"sha256:\" + domain-hash(\"prf.resubmission-idempotency.v1\",
                          {parent-attempt-receipt-hash,
                           current-submission-basis-root,
                           researcher-authorisation-id})"
  (:require [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.hash.reference :as hash-ref]))

(def link-schema "resubmission-link.artifact.v1")
(def link-kind :resubmission-link)
(def link-verifier "resubmission-link.verifier.v1")
(def link-domain "prf.researcher-resubmission.v1")
(def family-domain "prf.resubmission-family.v1")
(def idempotency-domain "prf.resubmission-idempotency.v1")

(def kinds #{:exact-retry :corrected-result :submission-repair})
(def dispositions #{:addressed :disputed :not-applicable})
(def change-bools [:execution-context-changed? :submission-basis-changed? :results-changed?])

(def artifact-self-keys
  "Artifact-envelope metadata keys excluded from the resubmission projection
   (they are attached by artifact/finalize-artifact AFTER the resubmission hash
   and signature are computed, so including them would break both)."
  #{:artifact/hash :artifact/preimage
    :artifact/canonical-bytes-v2 :artifact/canonical-hash-v2})

(defn unsigned-link-projection
  "The canonical unsigned projection: every authoritative field except the self
   fields :resubmission/hash, the artifact-envelope keys, and the researcher
   signature bytes."
  [link]
  (-> (apply dissoc link (into [:resubmission/hash] artifact-self-keys))
      (update :resubmission/researcher dissoc :signature)))

(defn resubmission-hash
  "Content-derived identity of the parent->child resubmission relationship."
  [link]
  (hash-ref/sha256-ref (hc/domain-hash link-domain (unsigned-link-projection link))))

(defn family-id
  "Stable identity of the research subject / acceptance question."
  [research-subject-root]
  (hash-ref/sha256-ref (hc/domain-hash family-domain research-subject-root)))

(defn idempotency-key
  "Identity of the submission operation."
  [{:keys [parent-attempt-receipt-hash current-submission-basis-root
           researcher-authorisation-id]}]
  (hash-ref/sha256-ref
   (hc/domain-hash idempotency-domain
                   {:parent-attempt-receipt-hash parent-attempt-receipt-hash
                    :current-submission-basis-root current-submission-basis-root
                    :researcher-authorisation-id researcher-authorisation-id})))

(defn sign-link
  "Attach the researcher signature over the unsigned link projection bytes."
  [link private-key]
  (assoc-in link [:resubmission/researcher :signature]
            {:signature/algorithm :ed25519
             :signature (sed/ed25519-sign-bytes
                         (hc/canonical-bytes (unsigned-link-projection link))
                         private-key)}))

(defn verify-link-signature
  "Verify a link's researcher signature over its unsigned projection bytes and
   confirm :resubmission/hash matches the recomputed value. Returns
   {:valid? bool :reason kw :detail str}."
  [link public-hex]
  (let [sig (get-in link [:resubmission/researcher :signature])]
    (cond
      (nil? sig)
      {:valid? false :reason :missing-researcher-signature}

      (not= :ed25519 (:signature/algorithm sig))
      {:valid? false :reason :unsupported-signature-algorithm
       :detail (:signature/algorithm sig)}

      (not= (:resubmission/hash link) (resubmission-hash link))
      {:valid? false :reason :resubmission-hash-mismatch
       :detail (str "stored " (:resubmission/hash link)
                    " recomputed " (resubmission-hash link))}

      (not (sed/ed25519-verify-bytes
            (hc/canonical-bytes (unsigned-link-projection link))
            (:signature sig)
            public-hex))
      {:valid? false :reason :invalid-researcher-signature}

      :else
      {:valid? true :reason :ok :detail (:resubmission/hash link)})))

(defn valid-link-shape?
  "Structural validation of the link (no signature/hash checks)."
  [link]
  (and (map? link)
       (= link-schema (:schema-version link))
       (= link-kind (:artifact/kind link))
       (= link-verifier (:artifact/verifier link))
       (contains? kinds (:resubmission/kind link))
       (pos-int? (:resubmission/sequence link))
       (map? (:resubmission/parent link))
       (string? (get-in link [:resubmission/parent :attempt-receipt-hash]))
       (pos-int? (get-in link [:resubmission/parent :sequence]))
       (map? (:resubmission/current link))
       (string? (get-in link [:resubmission/current :run-id]))
       (map? (get-in link [:resubmission/current :research-subject]))
       (string? (get-in link [:resubmission/current :research-subject :root/hash]))
       (string? (get-in link [:resubmission/idempotency-key]))
       (map? (:resubmission/researcher link))
       (string? (get-in link [:resubmission/researcher :researcher-id]))
       (string? (get-in link [:resubmission/researcher :authorisation-id]))
       (string? (get-in link [:resubmission/researcher :policy/hash]))
       (string? (get-in link [:resubmission/researcher :key/id]))
       (vector? (:resubmission/remediation link))
       (every? (fn [r]
                 (and (map? r)
                      (string? (:finding-id r))
                      (contains? dispositions (:disposition r))
                      (string? (:evidence-hash r))))
               (:resubmission/remediation link))))

(defn finalize-link
  "Produce the content-addressed resubmission link artifact.

   1. computes :resubmission/hash from the unsigned projection;
   2. attaches the researcher signature;
   3. runs artifact/finalize-artifact so :artifact/hash + :artifact/preimage are
      attached and verify-artifact validates it.

   `link` must be a valid link SHAPE (see valid-link-shape?) without
   :resubmission/hash and without the researcher signature."
  [link private-key]
  (let [hashed (assoc link :resubmission/hash (resubmission-hash link))
        signed (sign-link hashed private-key)]
    (artifact/finalize-artifact signed)))
