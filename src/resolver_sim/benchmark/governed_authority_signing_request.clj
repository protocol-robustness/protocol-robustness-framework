(ns resolver-sim.benchmark.governed-authority-signing-request
  "AUTH-K3 governed-authority signing request.

   K3 constrains exercise of an already-authorized private key to a validated
   signing request:

     an authorized private key must never sign arbitrary caller bytes; it signs
     only the fixed digest of a governed-authority-signing-request.v1 that
     commits the exact subject, purpose, and authority context.

   This namespace owns:
     - the closed request contract;
     - derive-from-committed-material resolution (never caller-asserted roots);
     - the request root (excludes itself);
     - the fixed signing digest;
     - sign-time eligibility/currentness gates.

   It does NOT own K1/K2 (those attach downstream) and does NOT own K4 custody
   (no daemon, keyring, HSM, KMS, key storage, or private-key lifecycle).

   decision-v2 identity is preserved: the semantic :subject/root is the
   recomputed researcher-decision.v2 root. The K3 signing-request/root
   identifies the authority exercise, not the decision identity."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.signed-external-decision :as sed]
            [buddy.core.codecs :as codecs])
  (:import [java.time Instant]))

(def ^:const signing-request-schema "governed-authority-signing-request.v1")

(def decision-k3-schema
  "Signed-decision container schema version for K3 decisions (re-export from
   researcher-force-authorisation for verifier dispatch without a cycle)."
  rfa/decision-k3-schema-version)

(def request-fields
  "Closed field set of a governed-authority-signing-request.v1 body. All fields
   are required; :artifact/kind is the schema discriminator."
  #{:artifact/kind
    :authority-state/root
    :review-governance/root
    :signer-key-set/root
    :review-round/root
    :principal/id
    :signing-key/id
    :purpose
    :subject/kind
    :subject/root})

(defn- signer-key-set-lookup
  "Public-key hex for [principal-id, signing-key-id] in a signer-key-set body,
   or nil. Local lookup to avoid a dependency cycle with governed-authority-state."
  [signer-key-set principal-id signing-key-id]
  (some->> (:signer-key-set/entries signer-key-set)
           (some (fn [entry]
                   (when (and (= (:researcher/id entry) principal-id)
                              (= (:signing-key/id entry) signing-key-id))
                     (:signing-key/public-key entry))))))

(defn request-valid?
  "Closed-shape + root-validity validation of a signing-request body. Fails
   closed on unknown/missing fields, non-canonical kinds, or invalid roots."
  [request]
  (let [have (if (map? request) (set (keys request)) #{})
        extra (set/difference have request-fields)
        missing (set/difference request-fields have)
        roots (select-keys request [:authority-state/root :review-governance/root
                                    :signer-key-set/root :review-round/root
                                    :subject/root])]
    (and (map? request)
         (= signing-request-schema (:artifact/kind request))
         (= :researcher-decision (:subject/kind request))
         (empty? extra)
         (empty? missing)
         (every? ref/valid-sha256-ref? (vals roots)))))

(defn derive-request
  "Derive the canonical signing-request from committed authoritative material
   + decision semantics — never caller-asserted.

   authority-state/root — the target authoritative state the caller identifies.
   authority-material   — the retained authenticated material for that state
                          (top-level source roots + :authority-material/* bodies).
   decision-ref        — the unsigned researcher-decision.v2 fields.

   Derives (does not trust): :subject/root (recomputed decision-v2 root),
   :principal/id (= researcher/id), :signing-key/id, :review-governance/root,
   :signer-key-set/root, :review-round/root, and :purpose (committed round
   purpose). A caller cannot select any of these."
  [state-root authority-material decision-ref]
  (let [round (:authority-material/review-round authority-material)]
    {:artifact/kind signing-request-schema
     :authority-state/root state-root
     :review-governance/root (:review-governance/root authority-material)
     :signer-key-set/root (:signer-key-set/root authority-material)
     :review-round/root (:review-round/root authority-material)
     :principal/id (:researcher/id decision-ref)
     :signing-key/id (:signing-key/id decision-ref)
     :purpose (:review-round/purpose round)
     :subject/kind :researcher-decision
     :subject/root (rfa/decision-v2-root decision-ref)}))

(defn signing-request-root
  "Content-addressed root of a closed signing-request body, excluding the root
   itself (no self-reference)."
  [request]
  (ref/sha256-ref
   (hc/domain-hash :governed-authority-signing-request-v1
                   (hc/project-canonical-safe request))))

(defn signing-digest-bytes
  "Fixed, domain-separated digest BYTES over a signing-request/root. These are
   the exact bytes passed to the private-key primitive. Never caller-provided
   bytes, hashes, or hex-string representations of hashes."
  [request-root]
  (codecs/hex->bytes
   (hc/domain-hash :governed-authority-signing-request-signature-v1
                   {:signing-request/root request-root})))

(defn sign-time-eligible?
  "All sign-time preconditions before private-key exercise:
     - requested authority state is the applicable current state (for
       current-admission signing);
     - [principal, key-id] resolves in the committed signer-key-set;
     - the key is governance-eligible for the principal;
     - the principal is constituted for the committed round;
     - :purpose equals the committed round purpose.

   state-current? — (fn [authority-state/root] → bool) from the authoritative
   store. Historical signing does not require currentness."
  [request authority-material state-current?]
  (let [ks    (:authority-material/signer-key-set authority-material)
        gov   (:authority-material/review-governance authority-material)
        round (:authority-material/review-round authority-material)
        principal-id (:principal/id request)
        key-id (:signing-key/id request)]
    (and (state-current? (:authority-state/root request))
         (some? (signer-key-set-lookup ks principal-id key-id))
         (governance/position-key-valid? gov principal-id key-id)
         (contains? (set (map :researcher/id (:review-round/members round)))
                    principal-id)
         (= (:purpose request) (:review-round/purpose round)))))

(defn build-signed-decision-k3
  "Sign a researcher decision under AUTH-K3.

   Mirrors build-signed-decision-v2's decision identity: :decision/hash is the
   recomputed researcher-decision.v2 root (the semantic subject/root). The
   signature is over the fixed K3 signing digest, NOT the decision hash.

   The caller identifies the unsigned decision and the target
   :authority-state/root only. The signer resolves governance root,
   signer-key-set root, review-round root, purpose, principal, key-id, and
   subject/root from committed material — never from caller assertions.

   material-resolver — (fn [authority-state/root] → authenticated material);
     required. The signer resolves the material from the authoritative store,
     so a caller cannot inject forged governance/round/purpose roots.
   state-current?    — optional (fn [authority-state/root] → bool) for
     current-admission; defaults to true (historical signing does not require
     currentness).

   Refuses private-key exercise unless every sign-time precondition holds.

   Returns the K3 signed decision reference carrying :schema-version
   \"researcher-decision.k3\", :decision/hash, :authority-state/root,
   :signing-request/root, and :signature."
  [researcher-id authorisation-id request-root review-round-hash outcome-root
   decision private-key-path state-root
   & {:keys [dissent-reason password signing-key-id state-current? material-resolver]}]
  (let [material (or (and (fn? material-resolver) (material-resolver state-root))
                     (throw (ex-info "no material-resolver for signing request"
                                     {:reason :material-resolver-required})))
        decision-ref {:researcher/id researcher-id
                      :authorisation/id authorisation-id
                      :authorisation/request-root request-root
                      :review-round/hash review-round-hash
                      :outcome/root outcome-root
                      :decision decision
                      :signing-key/id signing-key-id
                      :dissent/reason dissent-reason}
        request (derive-request state-root material decision-ref)
        current? (or state-current? (fn [_] true))
        _ (when-not (request-valid? request)
            (throw (ex-info "signing request is not a closed valid body"
                            {:reason :invalid-signing-request})))
        _ (when-not (sign-time-eligible? request material current?)
            (throw (ex-info "signing request is not eligible at signing time"
                            {:reason :signing-not-eligible
                             :request request})))
        sig-request-root (signing-request-root request)
        digest (signing-digest-bytes sig-request-root)
        priv (signing/load-private-key! private-key-path password)
        signature-hex (sed/ed25519-sign-bytes digest priv)]
    (cond-> {:schema-version rfa/decision-k3-schema-version
             :researcher/id researcher-id
             :authorisation/id authorisation-id
             :authorisation/request-root request-root
             :review-round/hash review-round-hash
             :outcome/root outcome-root
             :decision decision
             :decision/hash (rfa/decision-v2-root decision-ref)
             :authority-state/root state-root
             :signing-request/root sig-request-root
             :signature {:algorithm :ed25519
                         :value signature-hex
                         :signed-at (str (Instant/now))}}
      signing-key-id (assoc :signing-key/id signing-key-id)
      dissent-reason (assoc :dissent/reason dissent-reason))))

(defn verify-signed-decision-k3-with-material
  "CONSISTENCY / CRYPTOGRAPHIC K3 verification against a SUPPLIED authority
   material.

   WARNING (AUTH-K3-V): this proves only that the signature is valid under the
   supplied authority context. It does NOT prove the material was the
   authoritative PRF state — the caller supplies the material. For
   authoritative verification, resolve the material through an authoritative
   store (see governed_authority_state/verify-authoritative-signed-decision-k3),
   never accept it as a caller assertion.

   Recomputes the signing request from the supplied material + decision
   semantics, requires the stored :signing-request/root to equal the recomputed
   root (never trusts the stored root), derives the signing digest internally,
   and verifies (K1) against the committed signer-key-set public key for
   [principal, key-id].

   Returns {:valid? bool :reason kw :subject/root :request/root}."
  [decision-ref state-root authority-material]
  (let [request (derive-request state-root authority-material decision-ref)
        recomputed-root (signing-request-root request)
        stored-root (:signing-request/root decision-ref)]
    (if (not= stored-root recomputed-root)
      {:valid? false :reason :signing-request-root-mismatch
       :stored stored-root :recomputed recomputed-root}
      (let [principal-id (:researcher/id decision-ref)
            key-id (:signing-key/id decision-ref)
            committed-key (signer-key-set-lookup
                           (:authority-material/signer-key-set authority-material)
                           principal-id key-id)]
        (if-not committed-key
          {:valid? false :reason :signer-key-not-found}
          (let [digest (signing-digest-bytes recomputed-root)
                sig-value (get-in decision-ref [:signature :value])]
            (if (sed/ed25519-verify-bytes digest sig-value committed-key)
              {:valid? true
               :subject/root (:subject/root request)
               :request/root recomputed-root}
              {:valid? false :reason :signature-invalid})))))))