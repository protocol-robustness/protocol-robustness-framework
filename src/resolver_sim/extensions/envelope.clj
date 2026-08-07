(ns resolver-sim.extensions.envelope
  "Closed core, open extension envelope (map-shape extensibility).

   A framework record may carry extension-defined structure under a designated
   :extensions envelope keyed by namespaced extension identifiers. Core fields
   are closed and validated strictly by their own schema; extension payloads
   are governed as follows:

   - recognized and validated — the extension is registered and its payload
     passed the extension's contract validation;
   - recognized but unavailable — the extension is registered but its
     implementation is not resolved for this run;
   - unrecognized — the extension is not registered; the payload is preserved
     losslessly and never interpreted with generic core semantics;
   - malformed — the extension is registered and the payload failed contract
     validation; this FAILS rather than being silently dropped.

   The envelope is part of the canonical preimage: because canonical hashing
   covers :extensions when present, behaviour-affecting extension data is
   hash-bound into evidence.

   This complements the extension-map registry (resolver-sim.extensions.registry,
   which maps extension identifiers to implementations): the envelope is the
   open, per-record slot where extension-owned structure lives."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def envelope-key
  "Designated envelope key on a framework record."
  :extensions)

;; ── envelope access ───────────────────────────────────────────────────────

(defn envelope-of
  "Return the extension envelope of a record (the :extensions map), or nil
   when absent or not a map."
  ([record] (envelope-of record envelope-key))
  ([record key]
   (let [v (get record key ::absent)]
     (when (and (not= ::absent v) (map? v))
       v))))

(defn has-envelope?
  "True when the record carries a map-valued :extensions envelope."
  ([record] (has-envelope? record envelope-key))
  ([record key]
   (map? (get record key))))

(defn without-envelope
  "Core-only view of a record: the record with the envelope removed. Core
   validation operates on this view; the envelope is reassembled after
   validation."
  ([record] (without-envelope record envelope-key))
  ([record key]
   (dissoc record key)))

(defn preserve-envelope
  "Losslessly reassemble a record from its validated core view and its original
   envelope. Unknown entries are carried through unchanged."
  ([core-view record] (preserve-envelope core-view record envelope-key))
  ([core-view record key]
   (if (contains? record key)
     (assoc core-view key (get record key))
     core-view)))

;; ── classification ────────────────────────────────────────────────────────

(defn classify-entry
  "Classify one envelope entry.

   resolver is a function (extension-id payload) returning one of:
     :unrecognized
     {:status :unavailable}
     {:status :valid}
     {:status :invalid :violations [...]}

   Returns a classification map with :extension/id and :classification
   (:recognized-validated | :recognized-unavailable | :unrecognized |
   :malformed)."
  [resolver extension-id payload]
  (let [r (resolver extension-id payload)]
    (cond
      (= :unrecognized r)
      {:extension/id extension-id :classification :unrecognized}

      (and (map? r) (= :unavailable (:status r)))
      {:extension/id extension-id :classification :recognized-unavailable}

      (and (map? r) (= :valid (:status r)))
      {:extension/id extension-id :classification :recognized-validated}

      (and (map? r) (= :invalid (:status r)))
      {:extension/id extension-id
       :classification :malformed
       :violations (:violations r)}

      :else
      (throw (ex-info "extension: invalid envelope resolver result"
                      {:extension/id extension-id :result r})))))

(defn validate-envelope
  "Validate every envelope entry against its owning extension's contract.

   Recognized-but-unavailable and unrecognized entries are preserved but are
   never represented as validated. A malformed entry fails the validation.

   Returns {:valid? bool
            :classifications [...] 
            :violations [{:violation/id :extensions/error-malformed-envelope-entry ...}]}."
  ([record resolver] (validate-envelope record resolver envelope-key))
  ([record resolver key]
   (if (and (contains? record key) (not (map? (get record key))))
     {:valid? false
      :classifications []
      :violations [{:violation/id :extensions/error-invalid-envelope-shape
                    :details {:envelope-key key
                              :value (get record key)}}]}
     (let [envelope (get record key {})
           classifications (mapv (fn [[extension-id payload]]
                                   (classify-entry resolver extension-id payload))
                                 envelope)
           malformed (filterv #(= :malformed (:classification %)) classifications)]
       {:valid? (empty? malformed)
        :classifications classifications
        :violations (mapv (fn [c]
                            {:violation/id :extensions/error-malformed-envelope-entry
                             :details c})
                          malformed)}))))

;; ── hash binding ──────────────────────────────────────────────────────────

(defn envelope-hash-binding
  "Return canonical hashes of a record with and without its envelope, so the
   hash-binding of extension data is inspectable and testable.

   Returns {:with-envelope <hash> :without-envelope <hash>}."
  [domain-tag record]
  {:with-envelope (hc/domain-hash domain-tag record)
   :without-envelope (hc/domain-hash domain-tag (without-envelope record))})

;; ── envelope shape (shareable, content-addressed) ────────────────────────

(def envelope-shape-domain-tag
  "EXTENSION_ENVELOPE_SHAPE_V1")

(def shape-schema-version
  1)

(defn shape-of
  "Canonical structural projection of a value: leaf values become type tags;
   maps and vectors recurse. Keys, extension ids, and versions are preserved.
   The result is pure EDN data — shareable and hashable — and is deliberately
   detached from concrete values."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k val]] [k (shape-of val)])) v)
    (vector? v) {:vector/type :vector
                 :vector/of (mapv shape-of v)}
    (seq? v) {:seq/type :seq
              :seq/of (mapv shape-of v)}
    (nil? v) :nil
    (integer? v) :integer
    (string? v) :string
    (keyword? v) :keyword
    (symbol? v) :symbol
    (boolean? v) :boolean
    :else :opaque))

(defn envelope-shape
  "Structural shape of a record's :extensions envelope, or nil when absent."
  ([record] (envelope-shape record envelope-key))
  ([record key]
   (when-let [envelope (envelope-of record key)]
     (shape-of envelope))))

(defn envelope-shape-hash
  "Content-addressed hash of the envelope shape, e.g. sha256:<64-hex>.
   Two records with structurally identical envelopes share this hash even when
   their payload values differ."
  ([record] (envelope-shape-hash record envelope-key))
  ([record key]
   (hash-ref/sha256-ref (hc/domain-hash envelope-shape-domain-tag (envelope-shape record key)))))

(defn build-envelope-shape-artifact
  "Shareable, content-addressed envelope-shape artifact.

   Detached from record values: the shape commits only the structural skeleton
   of the :extensions envelope (extension ids, versions, key layout, leaf
   types). Publish it so different deployments or consumers can agree on the
   envelope contract without exchanging records."
  ([record] (build-envelope-shape-artifact record envelope-key))
  ([record key]
   (let [shape (envelope-shape record key)
         base {:shape/schema-version shape-schema-version
               :shape/domain :extension-envelope
               :shape/extensions shape}
         root (hc/domain-hash envelope-shape-domain-tag base)]
     (assoc base :shape/hash (hash-ref/sha256-ref  root)))))
