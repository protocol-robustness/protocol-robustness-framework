(ns resolver-sim.protocols.sew.test-vectors.slash-allocation
  "Canonical slash-allocation test-vector emitters.
   Extracted from resolver-sim.test-vectors.pro-rata for protocol separation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.config.paths :as paths]))

(def slash-schema-version "slash-allocation-vector.v1")

(def default-generated-at "1970-01-01T00:00:00Z")

(def ^:private settlement-critical? true)

(defn- amount-string [x] (str (bigint (or x 0))))

(defn- keyword-name
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    (nil? x) nil
    :else (str x)))

(defn- canonicalize
  [x]
  (cond
    (map? x) (->> x
                  (map (fn [[k v]] [(keyword-name k) (canonicalize v)]))
                  (into (sorted-map)))
    (vector? x) (mapv canonicalize x)
    (sequential? x) (mapv canonicalize x)
    (keyword? x) (keyword-name x)
    (symbol? x) (keyword-name x)
    (integer? x) (amount-string x)
    (ratio? x) {:numerator (amount-string (numerator x))
                :denominator (amount-string (denominator x))}
    :else x))

(defn- canonical-json [x] (json/write-str (canonicalize x) {:escape-slash false}))

(defn- sha256-hex
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn- canonical-hash [x] (sha256-hex (canonical-json x)))

(defn- attach-hashes
  [v]
  (let [with-subtree-hashes (assoc v
                                   :canonical-input-hash (canonical-hash (:input v))
                                   :canonical-expected-output-hash (canonical-hash (:expected-output v)))]
    (assoc with-subtree-hashes
           :full-vector-hash (canonical-hash with-subtree-hashes))))

(defn- source-metadata
  [{:keys [source-function generator-function generated-at source-commit implementation-version]}]
  (cond-> {:source-function source-function
           :generator-function generator-function
           :generated-at (or generated-at default-generated-at)
           :source-commit (or source-commit "unknown")}
    implementation-version (assoc :implementation-version implementation-version)))

(defn- units-metadata
  [{:keys [amount-unit weight-unit token-decimals integer-precision]}]
  {:amount-unit (or amount-unit "base-units")
   :weight-unit (or weight-unit "integer-weight")
   :token-decimals token-decimals
   :integer-precision (or integer-precision "arbitrary-precision-integer")
   :json-numeric-representation "amount-like and weight-like fields are decimal strings; no floats"})

(defn- trust-boundary-metadata
  [{:keys [trust-boundary settlement-critical]}]
  {:classification (or trust-boundary :solidity-parity)
   :settlement-critical (if (some? settlement-critical)
                          settlement-critical
                          settlement-critical?)
   :reference-model "Clojure reference model; not a trusted runtime"})

(defn- vector-filename
  [v]
  (case (:domain v)
    "liquidity-fulfillment" (str "liquidity-fulfillment-" (:vector-id v) ".json")
    "slash-allocation" (str "slash-allocation-" (:vector-id v) ".json")))

(defn- write-vector!
  [dir v]
  (let [file (io/file dir (vector-filename v))]
    (.mkdirs (.getParentFile file))
    (spit file (str (canonical-json v) "\n"))
    (.getPath file)))

(def slash-invariants
  [{:id :slash/conservation
    :expression "total_obligation == total_debited + total_unmet + remainder"}
   {:id :slash/caps-respected
    :expression "forall party: debited <= cap when cap is present"}
   {:id :slash/non-negative-debit
    :expression "forall party: debited >= 0 and unmet >= 0"}
   {:id :slash/zero-weight-handling
    :expression "zero-weight parties receive zero liability"}
   {:id :slash/deterministic-ordering
    :expression "party result order follows input liable party order"}])

(defn- normalize-slash-input
  [slash-obligation liable-parties basis cap-field slash-policy snapshot]
  {:slash-obligation (amount-string slash-obligation)
   :liable-parties (mapv (fn [party]
                           {:party-id (keyword-name (:id party))
                            :weight (amount-string (or (basis party) 0))
                            :weight-key (keyword-name basis)
                            :cap (when (some? (cap-field party))
                                   (amount-string (cap-field party)))})
                         liable-parties)
   :weight-key (keyword-name basis)
   :cap-key (keyword-name cap-field)
   :policy-name (keyword-name (or slash-policy :sew-slash-allocation))
   :snapshot snapshot})

(defn- normalize-slash-output
  [slash-obligation liable-parties basis cap-field result]
  (let [alloc-by-id (into {} (map (juxt :id identity) (:allocations result)))
        total-debited (long (or (:recovered-total result) 0))
        total-unmet (long (or (:unmet-total result) 0))]
    {:status (some-> (:status result) keyword-name)
     :liability-per-party (mapv (fn [party]
                                  (let [id (:id party)
                                        alloc (get alloc-by-id id)
                                        debited (long (or (:paid alloc) 0))
                                        unmet (long (or (:unmet alloc) 0))
                                        owed (long (or (:owed alloc) (+ debited unmet)))]
                                    {:party-id (keyword-name id)
                                     :weight (amount-string (or (basis party) 0))
                                     :cap (when (some? (cap-field party))
                                            (amount-string (cap-field party)))
                                     :owed (amount-string owed)
                                     :debited (amount-string debited)
                                     :unmet (amount-string unmet)}))
                                liable-parties)
     :total-obligation (amount-string slash-obligation)
     :total-debited (amount-string total-debited)
     :total-unmet (amount-string total-unmet)
     :remainder (amount-string (- (long slash-obligation) total-debited total-unmet))
     :reference-output result}))

(defn emit-slash-allocation-vector
  "Emit a canonical Sew slash allocation vector by calling
   `calculate-sew-slash-allocation` and normalizing the result."
  [{:keys [vector-id description slash-obligation slash-amount liable-parties basis cap-field
           slash-policy notes tags snapshot]
    :or {basis :slashable-stake
         cap-field :available-slashable}
    :as opts}]
  (let [amount (or slash-obligation slash-amount 0)
        liable-parties (vec (or liable-parties []))
        result (sew-economics/calculate-sew-slash-allocation
                {:slash-obligation amount
                 :liable-parties liable-parties
                 :basis basis
                 :cap-field cap-field
                 :slash-policy slash-policy})
        input (normalize-slash-input amount liable-parties basis cap-field slash-policy snapshot)
        expected-output (normalize-slash-output amount liable-parties basis cap-field result)]
    (-> {:schema-version slash-schema-version
         :vector-id vector-id
         :domain "slash-allocation"
         :description description
         :input input
         :expected-output expected-output
         :invariants slash-invariants
         :source-function "resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation"
         :source-metadata (source-metadata (assoc opts
                                                  :source-function "resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation"
                                                  :generator-function "resolver-sim.protocols.sew.test-vectors.slash-allocation/emit-slash-allocation-vector"))
         :policy-metadata {:policy-id (keyword-name (or slash-policy :sew-slash-allocation))
                           :policy-version "v1"
                           :weighting-key (keyword-name basis)
                           :rounding-policy "floor-with-largest-remainder"
                           :remainder-policy "unallocated-recorded-as-unmet"
                           :canonical-ordering-policy "input-order"
                           :cap-policy (str "cap by " (keyword-name cap-field))}
         :units (units-metadata opts)
         :snapshot-metadata (or snapshot {:snapshot-boundary "pre-slash-execution"})
         :trust-boundary (trust-boundary-metadata opts)
         :edge-case-tags (mapv keyword-name tags)
         :notes (or notes [])}
        attach-hashes)))

(def slash-golden-specs
  [{:vector-id "slash-equal-weights"
    :description "Equal slashable stake splits the obligation evenly."
    :slash-obligation 100
    :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 100}
                     {:id :bob :slashable-stake 100 :available-slashable 100}]
    :tags [:equal-weights]}
   {:vector-id "slash-unequal-weights"
    :description "Unequal slashable stake allocates liability proportionally."
    :slash-obligation 400
    :liable-parties [{:id :alice :slashable-stake 1000 :available-slashable 1000}
                     {:id :bob :slashable-stake 500 :available-slashable 500}
                     {:id :carol :slashable-stake 500 :available-slashable 500}]
    :tags [:unequal-weights]}
   {:vector-id "slash-zero-weight-party"
    :description "Zero-weight party receives zero liability."
    :slash-obligation 100
    :liable-parties [{:id :alice :slashable-stake 100 :available-slashable 100}
                     {:id :bob :slashable-stake 0 :available-slashable 100}]
    :tags [:zero-weight]}
   {:vector-id "slash-capped-party"
    :description "A cap limits one party debit and records unmet liability."
    :slash-obligation 400
    :liable-parties [{:id :alice :slashable-stake 1000 :available-slashable 1000}
                     {:id :bob :slashable-stake 500 :available-slashable 60}
                     {:id :carol :slashable-stake 500 :available-slashable 500}]
    :tags [:cap-exhaustion :unequal-weights]}
   {:vector-id "slash-no-liable-parties"
    :description "No liable parties records the whole obligation as unmet."
    :slash-obligation 100
    :liable-parties []
    :tags [:no-liable-parties]}])

(defn write-slash-allocation-vectors!
  ([] (write-slash-allocation-vectors! (str (paths/test-vectors-dir) "/pro-rata")))
  ([dir]
   (mapv #(write-vector! dir (emit-slash-allocation-vector %)) slash-golden-specs)))
