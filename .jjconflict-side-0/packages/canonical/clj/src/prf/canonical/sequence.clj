(ns prf.canonical.sequence
  "Contract-bound canonical sequence commitments."
  (:require [prf.canonical.codec :as codec]
            [prf.canonical.decode :as decode]
            [prf.canonical.hash :as hash]))

(def ^:const sequence-contract "canonical-value-sequence.v1")
(def ^:const sequence-domain "CANONICAL_VALUE_SEQUENCE_V1")

(defn bound-sequence
  "Return the V1 purpose- and count-bound sequence value."
  ([options components]
   (let [purpose (:purpose options)
         _ (when-not (keyword? purpose)
             (throw (ex-info "canonical-sequence requires a :purpose keyword"
                             {:required :purpose :got purpose})))
         _ (when-not (sequential? components)
             (throw (ex-info "canonical-sequence :components must be sequential"
                             {:components components})))
         components (vec components)
         expected (:expected-component-count options)
         _ (when (and (some? expected)
                      (not (and (integer? expected) (not (neg? expected)))))
             (throw (ex-info ":expected-component-count must be a non-negative integer"
                             {:expected expected})))
         _ (when (and (some? expected) (not= expected (count components)))
             (throw (ex-info "sequence component-count does not match the declared contract"
                             {:expected expected :actual (count components)})))
         _ (doseq [component components]
             (codec/validate-canonical-value! component))]
     {:encoding-contract (or (:contract options) sequence-contract)
      :purpose purpose
      :component-count (count components)
      :components components}))
  ([options]
   (bound-sequence options (:components options))))

(defn canonical-sequence-bytes
  "Encode the bound V1 sequence as one canonical value."
  [options components]
  (codec/canonical-bytes (bound-sequence options components)))

(defn sequence-hash
  "Return the V1 domain-separated SHA-256 commitment for a bound sequence."
  [options components]
  (hash/hash-with-domain sequence-domain (bound-sequence options components)))

(defn verify-sequence-commitment
  "Verify a V1 sequence byte commitment with bounded canonical decoding.

   Returns the legacy result shape, including structured issues and separate
   resource-limit classification. Optional `{:limits ...}` supplies a host
   admission profile; the defaults are the frozen portable PRF V1 limits."
  ([bytes] (verify-sequence-commitment bytes nil))
  ([^bytes bytes options]
   (let [{:keys [valid? value issues resource-limit? resource-reason
                 single? fully-consumed?] :as decoded}
         (decode/verify-single bytes options)
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
       (when-not (map? value)
         (swap! contract-issues conj
                {:code :sequence/not-a-map :value-type (type value)}))
       (when (map? value)
         (when-not (= sequence-contract (:encoding-contract value))
           (swap! contract-issues conj
                  {:code :sequence/wrong-contract :actual (:encoding-contract value)
                   :expected sequence-contract}))
         (when-not (keyword? (:purpose value))
           (swap! contract-issues conj
                  {:code :sequence/purpose-not-keyword :purpose (:purpose value)}))
         (let [n (:component-count value) components (:components value)]
           (when-not (and (integer? n) (not (neg? n)))
             (swap! contract-issues conj
                    {:code :sequence/component-count-invalid :component-count n}))
           (when-not (vector? components)
             (swap! contract-issues conj
                    {:code :sequence/components-not-vector :components-type (type components)}))
           (when (and (integer? n) (vector? components) (not= n (count components)))
             (swap! contract-issues conj
                    {:code :sequence/component-count-mismatch
                     :expected n :actual (count components)})))
         (let [extra (remove #{:encoding-contract :purpose :component-count :components}
                             (keys value))]
           (when (seq extra)
             (swap! contract-issues conj
                    {:code :sequence/unexpected-keys
                     :keys (vec (sort-by pr-str extra))})))))
     (let [all-issues (vec (concat issues @contract-issues))
           contract-ok? (empty? @contract-issues)]
       {:valid? (and valid? contract-ok?)
        :value (when (and valid? contract-ok?) value)
        :issues all-issues
        :resource-limit? resource-limit?
        :resource-reason resource-reason}))))
