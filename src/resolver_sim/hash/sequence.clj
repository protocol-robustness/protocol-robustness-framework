(ns resolver-sim.hash.sequence
  "Named sequence-framing contract for consecutive canonical values.

   Bare consecutive concatenation

     encode(v1) || encode(v2) || … || encode(vN)

   is prefix-free and parseable, but it is *unbound*: the same parseable byte
   stream can be interpreted as different protocol objects or argument lists.
   To remove that interpretation ambiguity the `canonical-value-sequence.v1`
   contract binds every sequence commitment to a purpose and an explicit
   component structure:

     {:encoding-contract \"canonical-value-sequence.v1\"
      :purpose <domain-specific-purpose>
      :component-count n
      :components [v1 … vN]}

   Use `canonical-sequence-bytes` / `sequence-hash` for new commitments.  The
   bare form (`encode-sequence`) is retained only for compatibility and where
   an existing specification requires it; its docstring says so.

   Vector versus sequence naming (normative):

     `canonical-bytes` of a vector        — a canonical value whose type is
                                            :vector (self-delimiting, one value)
     `canonical-sequence-bytes`          — n independently framed canonical
                                            values bound to a contract

   These are different semantic constructions and MUST NOT be called by the
   same name or used interchangeably:

     canonical-bytes [a b] ≠ encode-sequence [a b]"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as framing]
            [prf.canonical.sequence :as canonical-sequence]))

(def ^:const sequence-contract
  "Encoding-contract identifier for the named sequence framing."
  "canonical-value-sequence.v1")

(def ^:const sequence-domain
  "Domain tag reserved in resolver-sim.hash.canonical for sequence hashing."
  :canonical-value-sequence)

(defn- concat-bytes
  "Consecutive byte-array concatenation (bare framing helper)."
  [bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0 bas bas]
      (when (seq bas)
        (System/arraycopy ^bytes (first bas) 0 out idx (count (first bas)))
        (recur (+ idx (count (first bas))) (rest bas))))
    out))

(defn encode-sequence
  "Bare consecutive framing: encode(v1) || encode(v2) || … || encode(vN).

   DEPRECATED for new commitments.  This stream is prefix-free but *unbound*:
   it carries no purpose, schema, or component count, so the same bytes can be
   parsed as different protocol objects.  New commitments MUST use
   `canonical-sequence-bytes` (or the canonical vector encoding where the
   semantics are those of a single vector value).  Retained only for
   compatibility and where an existing specification requires the bare form."
  [components]
  (concat-bytes (map hc/canonical-bytes components)))

(defn bound-sequence
  "The contract-bound value for a sequence.

   Returns
     {:encoding-contract <version>
      :purpose <purpose>
      :component-count n
      :components (vec components)}
   which is what `canonical-sequence-bytes` and `sequence-hash` commit.

   Options: {:purpose <keyword> required
             :contract <version-string> optional, defaults to
             canonical-value-sequence.v1
             :expected-component-count int optional, validated when present}.

   Boundary contract: `components` must be a sequential collection.  nil,
   strings, maps, and scalar values are rejected — a string is a scalar
   canonical value (not a sequence of chars), a map is not a sequence, and
   silently coercing them would let distinct intended inputs collide on the
   same commitment (nil ≡ [], {:k 1} ≡ [[:k 1]]).

   Component values are additionally validated with
   validate-canonical-value!: sets, lists/seqs, ratios, and other non-canonical
   types are rejected rather than silently coerced.  canonical-bytes normalizes
   a set to a sorted vector and a list to a vector (§11.2 projection); a
   sequence commitment must NOT inherit that coercion, because it would make a
   set component collide with a same-element sorted vector component
   (#{1 2} ≡ [1 2]) and defeat the component-structure binding.  Callers that
   intend a set must project it to a sorted vector explicitly before sequencing."
  [options components]
  (let [purpose (:purpose options)
        _ (when-not (keyword? purpose)
            (throw (ex-info "canonical-sequence requires a :purpose keyword"
                            {:required :purpose :got purpose})))
        _ (when (nil? components)
            (throw (ex-info "canonical-sequence requires a :components sequence"
                            {:got nil})))
        _ (when-not (sequential? components)
            (throw (ex-info "canonical-sequence :components must be a sequential collection"
                            {:components components :type (type components)})))
        components (vec components)
        _ (try (hc/validate-canonical-value! components)
               (catch clojure.lang.ExceptionInfo e
                 (throw (ex-info "canonical-sequence rejected a non-canonical component; project sets/lists/ratios to canonical values before sequencing"
                                 (assoc (ex-data e) :purpose purpose)
                                 e))))
        expected (:expected-component-count options)
        _ (when (and expected (not (and (integer? expected) (not (neg? expected)))))
            (throw (ex-info ":expected-component-count must be a non-negative integer"
                            {:expected expected :type (when expected (type expected))})))
        _ (when (and expected (not= expected (count components)))
            (throw (ex-info "sequence component-count does not match the declared contract"
                            {:expected expected :actual (count components)})))]
    {:encoding-contract (or (:contract options) sequence-contract)
     :purpose purpose
     :component-count (count components)
     :components components}))

(defn canonical-sequence-bytes
  "Canonical bytes of a bound sequence commitment.

   Returns `canonical-bytes(bound-sequence options components)`, so the byte
   stream itself states its framing contract, purpose, and component
   structure — the byte stream is self-describing under the contract."
  [options components]
  (hc/canonical-bytes (bound-sequence options components)))

(defn sequence-hash
  "Domain-separated sha256 commitment of a bound sequence.
   Returns the hex digest (domain tag CANONICAL_VALUE_SEQUENCE_V1)."
  [options components]
  (hc/domain-hash sequence-domain (bound-sequence options components)))

(defn verify-sequence-commitment
  "Decode and verify a sequence using the canonical package decoder.

   The legacy PRF adapter supplies its established host limits explicitly;
   the standalone canonical package has no PRF configuration dependency."
  [^bytes ba]
  (canonical-sequence/verify-sequence-commitment
   ba {:limits framing/default-limits}))
