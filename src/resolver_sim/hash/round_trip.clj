(ns resolver-sim.hash.round-trip
  "Purpose-neutral canonical fixed-point primitive.

   A value is a fixed point of the canonical serialization when
   encode→decode reproduces a value whose canonical bytes are identical AND
   the stream is a single, fully-consumed, canonical encoding.  This namespace
   exposes that round-trip as a reusable primitive so any artifact that must
   survive a canonical commitment (evidence records, review reports, sequence
   commitments, …) can verify it without reimplementing encode/decode/framing.

   It deliberately does NOT compare full decoded values to the input: decoding
   widens integers (Long → BigInt), so full-value equality would false-fail on
   every numeric field.  Callers project/compare the fields they semantically
   care about (see resolver-sim.benchmark.review-aggregate-check for the
   three-member classification example)."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]))

(defn- resource-limit-issue
  "The first issue that represents a resource-limit rejection, or nil."
  [issues]
  (some #(when (or (= :limit-exceeded (:status %))
                   (= :limit-exceeded (:type %)))
           %)
        issues))

(defn verify-canonical-single-bytes
  "Purpose-neutral admission of one canonical value from a byte stream.

   Verifies the stream is exactly one canonical value (well-framed, fully
   consumed, no canonicality issues) and decodes it.

   Returns
     {:valid? bool                  — the stream is a canonical single value
      :value <decoded-or-nil>       — the decoded value (nil when not valid)
      :issues [...]                 — structured canonicality/structural issues
      :resource-limit? bool         — a resource limit was hit (the stream is
                                      inadmissible, not malformed)
      :resource-reason kw-or-nil    — e.g. :max-stream-bytes, :max-collection-depth
      :single? bool                 — the stream is exactly one component
      :fully-consumed? bool         — the stream is fully consumed (no trailing
                                      bytes)}

   This is the out-of-domain fixed-point primitive: any artifact that must
   survive a canonical commitment can admit bytes through this boundary and
   project/compare the fields it cares about."
  [^bytes ba]
  (let [verified (fv/verify-single ba)
        issues (:issues verified)
        limit (resource-limit-issue issues)
        valid? (:canonical? verified)
        value (when valid?
                (try (:value (fv/decode-one ba 0))
                     (catch Exception _ nil)))]
    {:valid? (boolean valid?)
     :value value
     :issues (vec issues)
     :resource-limit? (boolean limit)
     :resource-reason (when limit (:reason limit))
     :single? (boolean (:single? verified))
     :fully-consumed? (boolean (:fully-consumed? verified))}))

(defn canonical-round-trip
  "Encode `v` to canonical bytes, decode it back, and fail-closed verify the
   stream.

   Returns
     {:valid? bool                  — the stream is canonical, fully consumed,
                                      and exactly one component
      :value <decoded-or-nil>       — the decoded value (nil when the stream is
                                      not a valid canonical single value)
      :issues [...]                 — decoder canonicality issues (empty when
                                      :valid? is true)
      :resource-limit? bool
      :resource-reason kw-or-nil}

   Verification mirrors verify-sequence-commitment: a stream may be mechanically
   parseable without being a valid canonical encoding, so the stream must be
   canonical (:canonical?), exactly one component (:single?), and fully consumed
   (no trailing bytes) — re-encoding must reproduce the bytes."
  [v]
  (verify-canonical-single-bytes (hc/canonical-bytes v)))
