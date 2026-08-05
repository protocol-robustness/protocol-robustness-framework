(ns resolver-sim.evidence.artifact
  "Generic content-addressed-artifact primitives shared by core evidence and
   evidence extensions: content hashing, the canonical preimage round-trip,
   exact kind/schema/verifier validation, and the optional parallel canonical
   commitment.

   This namespace knows nothing about force-authorisation, held custody, or any
   specific artifact domain. It is the neutral dependency for evidence
   extensions (for example the held-custody mutation extension) so they reuse
   core content addressing without coupling to a legacy domain namespace.

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - any extension namespace (prf.extensions.*)"
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hash]))

(def artifact-envelope-keys
  "Envelope keys that are stripped from the artifact body before hashing or
   canonical preimage computation. Includes the legacy :artifact/hash and
   :artifact/preimage plus the OPTIONAL parallel canonical commitment
   (:artifact/canonical-bytes-v2 / :artifact/canonical-hash-v2). The canonical
   commitment is representation-independent proof of the committed hash; it is
   envelope metadata so attaching it never changes :artifact/hash."
  #{:artifact/hash
    :artifact/preimage
    :artifact/canonical-bytes-v2
    :artifact/canonical-hash-v2})

(defn artifact-body
  "The artifact body with every envelope key removed (what the content hash and
   canonical preimage commit to)."
  [report]
  (apply dissoc report artifact-envelope-keys))

(defn- bytes->hex-str
  "Lowercase hex encoding of a byte array."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) ba)))

(defn- canonical-commitment-valid?
  "Verify the optional parallel canonical commitment when present. When the
   commitment keys exist, :artifact/canonical-hash-v2 must equal the committed
   :artifact/hash AND :artifact/canonical-bytes-v2 must be the hex of
   canonical-bytes(artifact body) — the standard typed encoding per
   CANONICAL_HASH_SPEC_V1. A cross-language consumer proves portable hashing via
   sha256(domain-tag || hex-decode(canonical-bytes-v2)) == :artifact/hash.

   Artifacts without the commitment (the default) validate exactly as before."
  [report body]
  (if (or (contains? report :artifact/canonical-bytes-v2)
          (contains? report :artifact/canonical-hash-v2))
    (and (string? (:artifact/hash report))
         (string? (:artifact/canonical-hash-v2 report))
         (= (:artifact/hash report) (:artifact/canonical-hash-v2 report))
         (string? (:artifact/canonical-bytes-v2 report))
         (= (:artifact/canonical-bytes-v2 report)
            (bytes->hex-str (hash/canonical-bytes body))))
    true))

(defn- safe-read-preimage
  "Read a preimage string to a value; returns ::unreadable on any parse error.
   Parse failures never raise."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _ ::unreadable)))

(defn preimage-decodes-to-body?
  "True when the stored preimage parses safely to the same value as the visible
   artifact body (map equality is layout-independent). Does NOT require the
   preimage to be a canonical serialization."
  [artifact]
  (and (string? (:artifact/preimage artifact))
       (= (artifact-body artifact)
          (safe-read-preimage (:artifact/preimage artifact)))))

(defn canonical-preimage-valid?
  "True when the stored preimage is the canonical (pr-str fixed-point)
   serialization of the content it decodes to: applying the canonical serializer
   to the safely decoded preimage reproduces the stored bytes byte-for-byte.

   This validates FROM the stored preimage (the committed body representation)
   rather than regenerating pr-str from a map whose concrete array-map/hash-map
   representation may have changed after envelope fields were attached, so it
   is stable across map-implementation thresholds. Whitespace, missing
   separators, and other non-canonical EDN spellings fail."
  [artifact]
  (and (string? (:artifact/preimage artifact))
       (let [decoded (safe-read-preimage (:artifact/preimage artifact))]
         (and (not= ::unreadable decoded)
              (= (:artifact/preimage artifact) (pr-str decoded))))))

(defn content-hash-valid?
  "True when the stored content hash re-derives from the committed artifact
   body."
  [artifact]
  (and (string? (:artifact/hash artifact))
       (= (:artifact/hash artifact)
          (str "sha256:" (hash/domain-hash :evidence-record (artifact-body artifact))))))

(def supported-preimage-policies
  "Accepted preimage policies."
  #{:exact :decoded-agreement})

(defn- resolve-preimage-policy
  "Normalize a preimage policy argument (nil → :exact, keyword, or a
   {:preimage-policy ...} map)."
  [policy]
  (cond
    (nil? policy) :exact
    (keyword? policy) policy
    (map? policy) (or (:preimage-policy policy) :exact)
    :else ::unsupported))

(defn preimage-and-hash-valid?
  "Enforce the full content round trip per an explicit preimage policy.

     (preimage-and-hash-valid? artifact)
     (preimage-and-hash-valid? artifact {:preimage-policy :exact})
     (preimage-and-hash-valid? artifact :decoded-agreement)

   Supported policies:
     :exact             (default) — the stored preimage decodes to the body AND
                          is the canonical fixed-point serialization, and the
                          content hash re-derives.
     :decoded-agreement — the stored preimage parses safely to the visible
                          body and the content hash re-derives. Frozen-legacy
                          compatibility only; callers must request it
                          explicitly — it is never inferred from a failed
                          :exact check.

   Unknown policies fail closed. Parse errors return false (never an uncaught
   reader exception). When the optional parallel canonical commitment is
   present it is verified too."
  ([artifact]
   (preimage-and-hash-valid? artifact :exact))
  ([artifact policy]
   (let [p (resolve-preimage-policy policy)]
     (if-not (contains? supported-preimage-policies p)
       false
       (let [body (artifact-body artifact)]
         (and (case p
                :exact (and (preimage-decodes-to-body? artifact)
                            (canonical-preimage-valid? artifact))
                :decoded-agreement (preimage-decodes-to-body? artifact))
              (content-hash-valid? artifact)
              (canonical-commitment-valid? artifact body)))))))

(defn finalize-artifact
  "Attach the content hash and exact preimage to an artifact body."
  [body]
  (let [hash (str "sha256:"
                  (hash/domain-hash :evidence-record body))]
    (assoc body
           :artifact/hash hash
           :artifact/preimage (pr-str body))))

(defn valid-artifact?
  "Re-verify a content-addressed artifact: schema version, kind, verifier id,
   and the full content round trip per a preimage policy (default :exact).

     (valid-artifact? report schema-version kind verifier)
     (valid-artifact? report schema-version kind verifier :exact)
     (valid-artifact? report schema-version kind verifier :decoded-agreement)"
  ([report schema-version kind verifier]
   (valid-artifact? report schema-version kind verifier :exact))
  ([report schema-version kind verifier preimage-policy]
   (and (map? report)
        (= schema-version (:schema-version report))
        (= kind (:artifact/kind report))
        (= verifier (:artifact/verifier report))
        (preimage-and-hash-valid? report preimage-policy))))

(defn attach-canonical-commitment
  "Attach an OPTIONAL, non-breaking parallel commitment proving
   representation-independent hashing alongside the legacy pr-str preimage:

     :artifact/canonical-bytes-v2  lowercase hex of the canonical typed bytes
                                   (canonical-bytes, per CANONICAL_HASH_SPEC_V1)
                                   of the artifact body
     :artifact/canonical-hash-v2   the domain-separated hash of the body, which
                                   equals :artifact/hash

   The commitment keys are envelope metadata: they are stripped before hashing,
   so attaching them never changes :artifact/hash or :artifact/preimage.
   valid-artifact? (and therefore every reader) verifies the commitment when
   present, and it cannot be forged (canonical-hash-v2 must equal the committed
   hash and the bytes must match canonical-bytes of the body). This is the
   non-breaking migration path toward portable, cross-language verification:
   adopt it behind your own version/feature flag and migrate gradually.

   Throws ex-info if the body contains a type canonical-bytes cannot encode."
  [artifact]
  (let [body (artifact-body artifact)
        hex (bytes->hex-str (hash/canonical-bytes body))]
    (assoc artifact
           :artifact/canonical-bytes-v2 hex
           :artifact/canonical-hash-v2 (:artifact/hash artifact))))
