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
            [resolver-sim.hash.framing-view :as fv]))

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

   The framing layer only guarantees the byte stream is well-framed canonical
   bytes; it does not understand the sequence contract.  This re-validates the
   contract so a loaded commitment is a genuine fixed point of `bound-sequence`
   — i.e. `decode(canonical-bytes(commitment))` reproduces a sequence whose
   contract fields agree:

     :encoding-contract equals the canonical version
     :purpose          is a keyword
     :component-count  is a non-negative integer equal to (count :components)
     :components       is a vector
     the map has exactly the four contract keys (bound-sequence emits no other
     fields, so any extra key means the commitment is not a fixed point)
     the byte array decodes to exactly one commitment (no trailing bytes)
     the byte stream is canonical (no decoder :issues such as non-minimal
     varints or out-of-order map keys), so re-encoding reproduces the bytes

   A hand-crafted or corrupted map that disagrees with the contract (for
   example :component-count 2 with :components [1], an extra key, trailing
   bytes after the value, or a non-minimal/non-canonical encoding) frames as
   canonical bytes but is rejected here.

   Returns {:valid? bool :value (decoded map | nil) :errors [string]}.
   Decoder resource limits (depth/payload) are inherited from framing-view."
  [^bytes ba]
  (try
    (let [decoded (fv/decode-one ba 0)
          value (:value decoded)
          errors (atom [])]
      (when (seq (:issues decoded))
        (swap! errors conj (str "commitment is not a canonical fixed point: decoder "
                                "reported " (count (:issues decoded)) " issue(s): "
                                (pr-str (mapv :code (:issues decoded))))))
      (when-not (map? value)
        (swap! errors conj (str "decoded commitment is not a map: " (type value))))
      (when (map? value)
        (when-not (= sequence-contract (:encoding-contract value))
          (swap! errors conj (str "unexpected :encoding-contract: "
                                  (pr-str (:encoding-contract value)))))
        (when-not (keyword? (:purpose value))
          (swap! errors conj (str ":purpose must be a keyword, got "
                                  (pr-str (:purpose value)))))
        (let [n (:component-count value)
              cs (:components value)]
          (when-not (and (integer? n) (not (neg? n)))
            (swap! errors conj (str ":component-count must be a non-negative integer, got "
                                    (pr-str n))))
          (when-not (vector? cs)
            (swap! errors conj (str ":components must be a vector, got " (type cs))))
          (when (and (integer? n) (vector? cs) (not= n (count cs)))
            (swap! errors conj (str ":component-count " n " does not match component count "
                                    (count cs)))))
        (when-not (= #{:encoding-contract :purpose :component-count :components}
                     (set (keys value)))
          (swap! errors conj (str "commitment is not a fixed point of bound-sequence: "
                                  "unexpected keys "
                                  (pr-str (vec (sort-by pr-str (remove #{:encoding-contract
                                                                         :purpose
                                                                         :component-count
                                                                         :components}
                                                                       (keys value)))))))))
      (when-not (= (count ba) (:next decoded))
        (swap! errors conj (str "commitment carries " (- (count ba) (:next decoded))
                                " trailing byte(s); the value must decode to the entire "
                                "byte array")))
      {:valid? (empty? @errors) :value (when (empty? @errors) value) :errors @errors})
    (catch Exception e
      (let [data (ex-data e)
            resource-limit? (or (= :limit-exceeded (:type data))
                                (= :limit-exceeded (:code data)))]
        {:valid? false :value nil
         :errors [(if resource-limit?
                    (str "commitment inadmissible under the admission profile: "
                         (:code data) (when (:reason data) (str " / " (:reason data)))
                         (when (:limit data) (str " (limit " (:limit data) ")")))
                    (str "commitment decode failed: " (.getMessage e)))]}))))
