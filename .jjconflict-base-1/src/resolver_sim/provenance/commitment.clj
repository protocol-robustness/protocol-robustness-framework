(ns resolver-sim.provenance.commitment
  "Shared verification of creation-provenance and source-creation commitments
  stored in canonical-integrity.v1 envelopes.

  Both the benchmark and scenario assurance paths write the same flat-field
  shape (one schema version, one structure):
    \"creation_provenance\"        — string, \"in-band\" or \"out-of-band\"
    \"creation_provenance_hash\"   — sha256: ref over {:creation/provenance <kw>}
    \"source_creation\"            — string, \"in-band\" or \"out-of-band\"
    \"source_creation_hash\"       — sha256: ref over {:source/creation {:provenance <kw>}}

  This namespace provides the single verification routine both verifiers delegate
  to, eliminating the write-only gap that existed when only the benchmark verifier
  recomputed provenance commitments."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private allowed-values
  #{"in-band" "out-of-band"})

(defn- normalize-provenance-value
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    (string? v) v
    :else nil))

(defn expected-creation-provenance-hash
  "Compute the expected sha256 ref for a creation provenance value."
  [provenance-kw]
  (hash-ref/sha256-ref
   (canonical/hash-with-intent {:hash/intent :creation-provenance}
                               {:creation/provenance provenance-kw})))

(defn expected-source-creation-hash
  "Compute the expected sha256 ref for a source creation provenance value."
  [provenance-kw]
  (hash-ref/sha256-ref
   (canonical/hash-with-intent {:hash/intent :source-creation}
                               {:source/creation {:provenance provenance-kw}})))

(defn verify-creation-provenance-commitment
  "Verify creation-provenance commitment consistency across three dimensions:
   1. Paired-presence: if the provenance value is present, the hash must be present (and vice-versa).
   2. Allowed-value: the stored provenance must be a known enum value.
   3. Recomputation: the stored hash must match a fresh hash of the stored provenance,
      AND the stored provenance must match the evidence's actual :creation/provenance.

  Arguments:
    stored-provenance — string (\"in-band\" | \"out-of-band\") or nil, as persisted
                        in canonical-integrity.v1 under \"creation_provenance\" (or
                        :creation_provenance for EDN consumers).
    stored-hash       — string (\"sha256:...\") or nil, as persisted in
                        canonical-integrity.v1 under \"creation_provenance_hash\".
    evidence-provenance — keyword (:in-band | :out-of-band) or nil, read from the
                          evidence bundle's actual :creation/provenance field.

  Returns a map:
    {:valid? bool
     :reason :ok | :missing-hash | :unsupported-value | :provenance-mismatch | :hash-mismatch
     :expected-hash str | nil
     :provenance-keyword keyword | nil}"
  [stored-provenance stored-hash evidence-provenance]
  (let [evidence-provenance (or evidence-provenance :in-band)]
    (cond
      (and stored-provenance (nil? stored-hash))
      {:valid? false :reason :missing-hash
       :expected-hash nil :provenance-keyword nil}

      (nil? stored-hash)
      {:valid? true :reason :ok
       :expected-hash nil :provenance-keyword nil}

      (not (contains? allowed-values (normalize-provenance-value stored-provenance)))
      {:valid? false :reason :unsupported-value
       :expected-hash nil :provenance-keyword (some-> stored-provenance keyword)}

      :else
      (let [stored-kw (keyword (normalize-provenance-value stored-provenance))
            expected (expected-creation-provenance-hash stored-kw)]
        (cond
          (not= stored-kw evidence-provenance)
          {:valid? false :reason :provenance-mismatch
           :expected-hash expected :provenance-keyword stored-kw}

          (not= stored-hash expected)
          {:valid? false :reason :hash-mismatch
           :expected-hash expected :provenance-keyword stored-kw}

          :else
          {:valid? true :reason :ok
           :expected-hash expected :provenance-keyword stored-kw})))))

(defn verify-source-creation-commitment
  "Verify source-creation provenance commitment, mirroring
  verify-creation-provenance-commitment but for the :source/creation field.

  Arguments:
    stored-source       — string (\"in-band\" | \"out-of-band\") or nil, as persisted
                          under \"source_creation\".
    stored-hash         — string (\"sha256:...\") or nil, as persisted under
                          \"source_creation_hash\".
    evidence-source     — map {:provenance :in-band/:out-of-band} or nil, read from
                          the evidence bundle's actual :source/creation field.

  Returns the same shape as verify-creation-provenance-commitment."
  [stored-source stored-hash evidence-source]
  (let [evidence-source (or evidence-source {:provenance :in-band})
        evidence-kw (or (some-> evidence-source :provenance keyword) :in-band)]
    (cond
      (and stored-source (nil? stored-hash))
      {:valid? false :reason :missing-hash
       :expected-hash nil :provenance-keyword nil}

      (nil? stored-hash)
      {:valid? true :reason :ok
       :expected-hash nil :provenance-keyword nil}

      (not (contains? allowed-values (normalize-provenance-value stored-source)))
      {:valid? false :reason :unsupported-value
       :expected-hash nil :provenance-keyword (some-> stored-source keyword)}

      :else
      (let [stored-kw (keyword (normalize-provenance-value stored-source))
            expected (expected-source-creation-hash stored-kw)]
        (cond
          (not= stored-kw evidence-kw)
          {:valid? false :reason :provenance-mismatch
           :expected-hash expected :provenance-keyword stored-kw}

          (not= stored-hash expected)
          {:valid? false :reason :hash-mismatch
           :expected-hash expected :provenance-keyword stored-kw}

          :else
          {:valid? true :reason :ok
           :expected-hash expected :provenance-keyword stored-kw})))))

(defn normalize-integrity-map
  "Normalize a canonical-integrity.v1 map (string or keyword keys) so the
  shared verification functions can read provenance fields uniformly.

  Accepts either JSON-deserialized maps (string keys, keyword or string key-fn)
  or Clojure maps with keyword keys. Returns a map with :creation-provenance,
  :creation-provenance-hash, :source-creation, :source-creation-hash keyword
  keys."
  [integrity]
  (let [get-key (fn [k]
                  (or (get integrity k)
                      (get integrity (name k))
                      (get integrity (keyword (name k)))))]
    {:creation-provenance (get-key :creation_provenance)
     :creation-provenance-hash (get-key :creation_provenance_hash)
     :source-creation (get-key :source_creation)
     :source-creation-hash (get-key :source_creation_hash)}))
