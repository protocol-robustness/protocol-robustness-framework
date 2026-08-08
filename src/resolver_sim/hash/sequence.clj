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
            [resolver-sim.hash.round-trip :as rt]))

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
  "Decode and verify a canonical-value-sequence commitment.

   Composes the purpose-neutral canonical round-trip primitive
   (resolver-sim.hash.round-trip/verify-canonical-single-bytes) for the generic
   decode/framing/resource verdict, then applies the sequence-contract shape
   checks on top.  The framing layer only guarantees the byte stream is
   well-framed canonical bytes; this re-validates the contract so a loaded
   commitment is a genuine fixed point of `bound-sequence` — i.e. the decoded
   value's contract fields agree:

     :encoding-contract equals the canonical version
     :purpose          is a keyword
     :component-count  is a non-negative integer equal to (count :components)
     :components       is a vector
     the map has exactly the four contract keys (bound-sequence emits no other
     fields, so any extra key means the commitment is not a fixed point)

   Returns {:valid? bool :value (decoded map | nil) :issues [<structured>]}.

   :issues entries are structured maps with a :code and, where relevant,
   offending values — never human-parsed strings.  The generic framing issues
   (non-canonical encodings, resource limits) come from the round-trip
   primitive; the sequence-contract shape violations use
   :sequence/not-a-map, :sequence/wrong-contract, :sequence/purpose-not-keyword,
   :sequence/component-count-invalid, :sequence/components-not-vector,
   :sequence/component-count-mismatch, :sequence/unexpected-keys, and
   :sequence/trailing-bytes.  Resource-limit rejection is surfaced as
   :resource-limit? true (with :resource-reason), never misreported as a decode
   failure."
  [^bytes ba]
  (let [{:keys [valid? value issues resource-limit? resource-reason
                single? fully-consumed?]}
        (rt/verify-canonical-single-bytes ba)
        contract-issues (atom [])]
    (when-not valid?
      (when (and (not resource-limit?) fully-consumed? (not single?))
        (swap! contract-issues conj
               {:code :sequence/trailing-bytes
                :detail "the commitment does not decode to exactly one value (trailing bytes or extra components)"}))
      (when (and (not resource-limit?) single? (not fully-consumed?))
        (swap! contract-issues conj
               {:code :sequence/trailing-bytes
                :detail "the commitment does not decode to the entire byte array"})))
    (when valid?
      (let [value value]
        (when-not (map? value)
          (swap! contract-issues conj
                 {:code :sequence/not-a-map :value-type (type value)}))
        (when (map? value)
          (when-not (= sequence-contract (:encoding-contract value))
            (swap! contract-issues conj
                   {:code :sequence/wrong-contract
                    :actual (:encoding-contract value)
                    :expected sequence-contract}))
          (when-not (keyword? (:purpose value))
            (swap! contract-issues conj
                   {:code :sequence/purpose-not-keyword
                    :purpose (:purpose value)}))
          (let [n (:component-count value)
                cs (:components value)]
            (when-not (and (integer? n) (not (neg? n)))
              (swap! contract-issues conj
                     {:code :sequence/component-count-invalid
                      :component-count n}))
            (when-not (vector? cs)
              (swap! contract-issues conj
                     {:code :sequence/components-not-vector
                      :components-type (type cs)}))
            (when (and (integer? n) (vector? cs) (not= n (count cs)))
              (swap! contract-issues conj
                     {:code :sequence/component-count-mismatch
                      :expected n :actual (count cs)})))
          (let [extra (remove #{:encoding-contract :purpose :component-count :components}
                              (keys value))]
            (when (seq extra)
              (swap! contract-issues conj
                     {:code :sequence/unexpected-keys
                      :keys (vec (sort-by pr-str extra))}))))))
    (let [all-issues (vec (concat issues @contract-issues))
          contract-ok? (empty? @contract-issues)
          ok? (and valid? contract-ok?)]
      {:valid? ok?
       :value (when ok? value)
       :issues all-issues
       :resource-limit? resource-limit?
       :resource-reason resource-reason})))
