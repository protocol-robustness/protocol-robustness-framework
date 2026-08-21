(ns resolver-sim.allocation.certificate
  "Allocation assurance certificate composition.

   Implements `allocation-assurance-certificate.v1`. In this phase the
   certificate records subject roots, the selected outcome, the result root,
   ordered assertion results, per-assertion assurance classifications, the
   exact-replication classification, PRF artifact identity, and proof fields
   explicitly marked as phase stubs.

   Assurance classifications used in this phase:
     :zk-proof             — not yet available; never assigned in this phase
     :independent-replay    — kernel arithmetic assertions reproducible by the
                              independent Rust kernel
     :economic-assumption   — :assume-punishment-credible remains an explicit
                              economic assumption
     :not-yet-evaluated     — proof-backed fields

   Exact-replication classification is bound to native Rust evidence
   (resolver-sim.allocation.native-evidence): it is proof-backed ONLY when the
   pinned native Rust implementation was actually executed and compared against
   the reference under the exact-replication contract. A mock result, or
   evidence bound to another result or another pinned implementation, never
   produces a stronger classification than the evidence evaluated.

   No assertion is falsely classified as :zk-proof in this phase.

   Content-addressed document (B4):
     compose-certificate attaches :certificate/hash — a deterministic self
     commitment over the whole certificate (excluding :certificate/hash and
     :certificate/signature), so a verifier recomposing from the same kernel
     result (+ round-state + native evidence) reproduces the identical hash.
     sign-certificate optionally attaches an Ed25519 attestation over the SAME
     unsigned projection bytes; signing never changes the certificate identity.
     verify-certificate recomputes the self-hash and, when a trust-policy is
     supplied, verifies the issuer key-id and signature."
  (:require [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.native-evidence :as native-evidence]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.signed-external-decision :as sed]))

(def schema-version "allocation-assurance-certificate.v1")

(def certificate-domain
  "Domain tag separating the allocation-assurance-certificate hash from all
   other content-addressed artifacts."
  "ALLOCATION_ASSURANCE_CERTIFICATE_V1")

(def certificate-signature-schema
  "Schema version of the certificate attestation block (reuses the signed
   external-decision signature schema)."
  sed/signature-schema)

(declare certificate-hash)

(defn assertion-assurance
  "Assurance classification for a kernel assertion id."
  [assertion-id]
  (case assertion-id
    (:allocation.assertion/claimant-set-root-valid
     :allocation.assertion/outcome-set-root-valid
     :allocation.assertion/proposed-rates-root-valid
     :allocation.assertion/rates-canonical-exact
     :allocation.assertion/rates-sum-to-one
     :allocation.assertion/outcomes-eligible-only
     :allocation.assertion/outcomes-no-duplicate-claims
     :allocation.assertion/outcomes-all-or-nothing
     :allocation.assertion/outcomes-exact-capacity
     :allocation.assertion/proportional-proposed
     :allocation.assertion/randomness-selection-valid
     :allocation.assertion/selected-outcome-membership
     :allocation.assertion/result-root-valid
     :allocation.assertion/result-capacity-reconciles)
    :independent-replay
    :not-yet-evaluated))

(defn- prf-artifact-identity
  "PRF artifact identity for the certificate."
  []
  {:artifact-kind :prf-allocation-kernel
   :kernel-version context/kernel-version
   :schema-version context/schema-version
   :canonical-abi-version "CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI"})

(defn exact-replication-classification
  "Classify the exact-replication scope from native Rust evidence bound to the
   reference result.

   native-evidence: nil, or a native-evidence map (native-evidence.v1).
   kernel-result:   the kernel public values; carries an optional
                    :native/reference block with the committed results-artifact
                    hash and pinned Rust identity.

   Returns {:classification kw :proof-backed? bool :reason kw :evidence {...}}.
   Proof-backed (:native-exact-match) only when native evidence was actually
   executed and matched under the exact-replication contract."
  [native-evidence kernel-result]
  (native-evidence/exact-replication-classification
   native-evidence
   {:results-artifact-hash (get-in kernel-result [:native/reference :results-artifact-hash])
    :input-root (:allocation-context-hash kernel-result)
    :result-root (:result-root kernel-result)
    :pinned-prf (prf-artifact-identity)
    :pinned-rust (get-in kernel-result [:native/reference :pinned-rust])}))

(defn- proof-block
  "Compose the proof block. Status is :valid only when the exact-replication
   classification is proof-backed; a mock result never produces a valid proof."
  [classification kernel-result]
  (let [proof-backed? (:proof-backed? classification)
        evidence (:evidence classification)
        evidence-source (some-> evidence :native-evidence/source)
        status (:result/status kernel-result)]
    {:status (if proof-backed? :valid :not-yet-evaluated)
     :proof-hash (when proof-backed?
                   (hc/domain-hash :native-exact-replication-v1
                                   (dissoc evidence
                                           :native-evidence/status
                                           :native-evidence/reason)))
     :proof-mode (cond
                   (= :mock evidence-source) :mock-native
                   (some? evidence) :native-rust
                   :else :none)
     :native-evidence evidence
     :public-values-hash (when (= :passing status)
                           (hc/domain-hash :certificate-assertions
                                           {:schema-version schema-version
                                            :subject (:certificate-assertions-digest kernel-result)}))}))

(defn compose-certificate
  "Compose an allocation-assurance-certificate.v1 from a kernel result.

   The kernel result must be the stable public-value map returned by
   `kernel/run-kernel`. If the result is rejected, the certificate records the
   rejection classification and proof fields remain :not-yet-evaluated.

   When a coprocessor `round-state` token is supplied (second arity), the
   certificate additionally carries a `:round-lifecycle` block with the
   `cancellation-window-assertion` for the canonical probabilistic-allocation
   lifecycle. The lifecycle assertion is projected separately from the kernel
   assertions; it never claims :zk-proof in this phase.

   When native Rust evidence is supplied (third arity), the exact-replication
   classification and proof block are bound to that evidence (see
   resolver-sim.allocation.native-evidence). Without it, exact-replication
   stays :pending-independent-replay and the proof block stays
   :not-yet-evaluated.

   The returned certificate always carries :certificate/hash — the deterministic
   content identity of the document (see certificate-hash)."
  ([kernel-result] (compose-certificate kernel-result nil nil))
  ([kernel-result round-state-token] (compose-certificate kernel-result round-state-token nil))
  ([kernel-result round-state-token native-evidence]
   (let [assertions (:assertions kernel-result [])
         status (:result/status kernel-result)
         exact-replication (exact-replication-classification native-evidence kernel-result)
         base
         {:schema-version schema-version
          :subject-roots {:allocation-context-hash (:allocation-context-hash kernel-result)
                          :claimant-set-root (:claimant-set-root kernel-result)
                          :outcome-set-root (:outcome-set-root kernel-result)
                          :proposed-rates-root (:proposed-rates-root kernel-result)
                          :rate-derived-summary-hash (:rate-derived-summary-hash kernel-result)}
          :selected-outcome {:selected-outcome-id (:selected-outcome-id kernel-result)
                             :selected-outcome-index (:selected-outcome-index kernel-result)
                             :selected-outcome-hash (:selected-outcome-hash kernel-result)}
          :result-root (:result-root kernel-result)
          :result-totals {:total-allocated (:total-allocated kernel-result)
                          :residual-capacity (:residual-capacity kernel-result)}
          :assertions
          (mapv (fn [{:keys [assertion/id assertion/result]}]
                  {:assertion/id id
                   :assertion/result result
                   :assurance (assertion-assurance id)})
                assertions)
          :exact-replication exact-replication
          :prf-artifact (prf-artifact-identity)
          :assume-punishment-credible
          {:status :declared-supported
           :assurance :economic-assumption
           :profile-hash nil}
          :proof (proof-block exact-replication kernel-result)
          :result/status status
          :rejection/classification (:rejection/classification kernel-result)
          :rejection/reason (:rejection/reason kernel-result)}
         lifecycle
         (when (some? round-state-token)
           {:round-state (round-state/lifecycle-target-state round-state-token)
            :cancellation/assertion
            (round-state/cancellation-assertion
             {:profile-id "alloc/2-3"} round-state-token)})]
     (let [certificate (if (nil? lifecycle)
                         base
                         (assoc base :round-lifecycle lifecycle))]
       (assoc certificate :certificate/hash (certificate-hash certificate))))))

(defn unsigned-certificate-projection
  "The canonical identity projection of a certificate: everything except the
   self :certificate/hash and any :certificate/signature. The signature is an
   attestation after commit and never changes the certificate identity (the
   same principle as attempt-receipts and transaction-orderings)."
  [certificate]
  (dissoc certificate :certificate/hash :certificate/signature))

(defn certificate-hash
  "Content-addressed identity of a certificate (self hash).

     certificate-hash = sha256:hex(domain-hash(
         ALLOCATION_ASSURANCE_CERTIFICATE_V1,
         canonical-bytes(unsigned-certificate-projection)))

   Deterministic: two certificates composed from the same kernel result,
   round-state token, and native evidence produce the same hash."
  [certificate]
  (hash-ref/sha256-ref
   (hc/domain-hash certificate-domain (unsigned-certificate-projection certificate))))

(defn sign-certificate
  "Attach an Ed25519 attestation to a certificate over its unsigned projection
   bytes. The signature block carries the issuer :key-id and :signed-hash (the
   certificate self hash), so a verifier can resolve the claimed issuer and
   recompute the signature over the same canonical bytes.

   Returns the certificate with :certificate/signature attached. The
   certificate identity (:certificate/hash) is unchanged by signing."
  [certificate private-key key-id]
  (let [projection (unsigned-certificate-projection certificate)]
    (assoc certificate
           :certificate/signature
           {:schema-version certificate-signature-schema
            :key-id key-id
            :algorithm :ed25519
            :signed-hash (certificate-hash certificate)
            :signature-encoding :hex
            :signature-bytes (sed/ed25519-sign-bytes
                              (hc/canonical-bytes projection)
                              private-key)})))

(defn verify-certificate
  "Verify a certificate document fail-closed.

   Returns {:valid? bool :signature-valid? bool|nil :issues [...] :reason kw|nil}.

   Integrity: the certificate must be a map of schema allocation-assurance-
   certificate.v1 whose :certificate/hash recomputes from its own unsigned
   projection.

   Attestation: when :certificate/signature is absent, :signature-valid? is nil
   (the self-hash is the document identity; the signature is the issuer
   attestation). When present, it must carry the signed-external-decision
   schema, use :ed25519, commit the recomputed self-hash, and (when a
   trust-policy is supplied) resolve :key-id to an active key of the expected
   role and verify the signature over the unsigned projection bytes. A
   present-but-invalid signature fails the WHOLE certificate — an invalid
   attestation is worse than none.

   trust-policy: {:trusted-keys [{:key/id <kw|str> :key/public <hex> :key/role kw
                                  :key/status kw}]} (the signed-external-decision
   trust-policy shape)."
  ([certificate] (verify-certificate certificate nil nil))
  ([certificate trust-policy] (verify-certificate certificate trust-policy nil))
  ([certificate trust-policy expected-role]
   (let [issues (atom [])
         add! (fn [issue] (swap! issues conj issue))
         sig (:certificate/signature certificate)
         recomputed (certificate-hash certificate)
         hash-ok? (and (map? certificate)
                       (string? (:certificate/hash certificate))
                       (= (:certificate/hash certificate) recomputed))
         _ (when-not (map? certificate)
             (add! {:code :certificate/not-a-map}))
         _ (when (and (map? certificate)
                      (not= schema-version (:schema-version certificate)))
             (add! {:code :certificate/schema-mismatch
                    :actual (:schema-version certificate)
                    :expected schema-version}))
         _ (when-not hash-ok?
             (add! {:code :certificate/hash-mismatch
                    :stored (:certificate/hash certificate)
                    :recomputed recomputed}))
         signature-issue
         (when sig
           (let [projection (unsigned-certificate-projection certificate)
                 signed-hash (:signed-hash sig)]
             (cond
               (not= certificate-signature-schema (:schema-version sig))
               {:code :certificate/signature-schema-mismatch
                :actual (:schema-version sig)}

               (not= :ed25519 (:algorithm sig))
               {:code :certificate/unsupported-signature-algorithm
                :algorithm (:algorithm sig)}

               (not= recomputed signed-hash)
               {:code :certificate/signature-hash-mismatch
                :signed signed-hash
                :recomputed recomputed}

               (nil? trust-policy)
               nil

               (nil? (:key-id sig))
               {:code :certificate/missing-key-id}

               :else
               (let [key (some #(when (= (:key-id sig) (:key/id %)) %)
                               (:trusted-keys trust-policy))]
                 (cond
                   (nil? key)
                   {:code :certificate/untrusted-key :key-id (:key-id sig)}

                   (and expected-role (not= expected-role (:key/role key)))
                   {:code :certificate/wrong-key-role
                    :key-id (:key-id sig) :role (:key/role key)}

                   (not= :active (:key/status key))
                   {:code :certificate/inactive-key :key-id (:key-id sig)}

                   :else
                   (if (sed/ed25519-verify-bytes
                        (hc/canonical-bytes projection)
                        (:signature-bytes sig)
                        (:key/public key))
                     nil
                     {:code :certificate/invalid-signature :key-id (:key-id sig)}))))))
         _ (when signature-issue (add! signature-issue))
         all-issues (vec @issues)
         signature-valid? (if sig (nil? signature-issue) nil)]
     {:valid? (and (map? certificate)
                   (= schema-version (:schema-version certificate))
                   hash-ok?
                   (nil? signature-issue))
      :signature-valid? signature-valid?
      :issues all-issues
      :reason (when signature-issue (:code signature-issue))})))
