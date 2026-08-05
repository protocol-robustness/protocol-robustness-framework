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
  "Read a preimage string to a value; returns ::unreadable on any parse error."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _ ::unreadable)))

(defn preimage-and-hash-valid?
  "Enforce the full content round trip for a content-addressed artifact:

     body → canonical preimage → content hash

   The stored preimage must agree with the body: it must equal pr-str(body)
   (the exact canonical preimage) OR decode to the same body
   ((edn/read-string preimage) == body). The decode fallback is layout-tolerant:
   for small bodies (PersistentArrayMap) a dissoc/assoc crossing the 8-key
   array-map/hash-map threshold can reorder pr-str output, and both strings
   still decode to the same committed body, so the preimage and decoded body
   can never disagree. The stored hash must re-derive from that same body.
   When the optional parallel canonical commitment is present it is verified
   too. Independent of schema/kind/verifier identity checks."
  [report]
  (and (string? (:artifact/hash report))
       (string? (:artifact/preimage report))
       (let [body (artifact-body report)
             preimage (:artifact/preimage report)]
         (and (or (= preimage (pr-str body))
                  (= body (safe-read-preimage preimage)))
              (= (:artifact/hash report)
                 (str "sha256:" (hash/domain-hash :evidence-record body)))
              (canonical-commitment-valid? report body)))))

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
   and the full content round trip (exact canonical preimage + content hash,
   plus the optional parallel canonical commitment) must all agree."
  [report schema-version kind verifier]
  (and (map? report)
       (= schema-version (:schema-version report))
       (= kind (:artifact/kind report))
       (= verifier (:artifact/verifier report))
       (preimage-and-hash-valid? report)))

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
