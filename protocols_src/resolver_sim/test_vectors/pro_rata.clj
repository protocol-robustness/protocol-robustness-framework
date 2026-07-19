(ns resolver-sim.test-vectors.pro-rata
  "Canonical pro-rata test-vector emitters for parity and regression tests.

   These emitters are intentionally thin wrappers around current reference
   implementations. They normalize inputs and outputs into deterministic,
   integer-safe JSON artifacts without changing allocation semantics."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.protocols.sew.test-vectors.slash-allocation :as sew-slash]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(def liquidity-schema-version "liquidity-fulfillment-vector.v1")

(def default-generated-at "1970-01-01T00:00:00Z")

(def ^:private settlement-critical? true)

(defn amount-string
  [x]
  (str (bigint (or x 0))))

(defn keyword-name
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

(defn canonical-json
  "Return deterministic JSON for a vector or vector subtree."
  [x]
  (json/write-str (canonicalize x) {:escape-slash false}))

(defn read-json
  [s]
  (json/read-str s))

(defn- sha256-hex
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn canonical-hash
  [x]
  (sha256-hex (canonical-json x)))

(defn attach-hashes
  [v]
  (let [with-subtree-hashes (assoc v
                                   :canonical-input-hash (canonical-hash (:input v))
                                   :canonical-expected-output-hash (canonical-hash (:expected-output v)))]
    (assoc with-subtree-hashes
           :full-vector-hash (canonical-hash with-subtree-hashes))))

(defn source-metadata
  [{:keys [source-function generator-function generated-at source-commit implementation-version]}]
  (cond-> {:source-function source-function
           :generator-function generator-function
           :generated-at (or generated-at default-generated-at)
           :source-commit (or source-commit "unknown")}
    implementation-version (assoc :implementation-version implementation-version)))

(defn units-metadata
  [{:keys [amount-unit weight-unit token-decimals integer-precision]}]
  {:amount-unit (or amount-unit "base-units")
   :weight-unit (or weight-unit "integer-weight")
   :token-decimals token-decimals
   :integer-precision (or integer-precision "arbitrary-precision-integer")
   :json-numeric-representation "amount-like and weight-like fields are decimal strings; no floats"})

(defn trust-boundary-metadata
  [{:keys [trust-boundary settlement-critical]}]
  {:classification (or trust-boundary :solidity-parity)
   :settlement-critical (if (some? settlement-critical)
                          settlement-critical
                          settlement-critical?)
   :reference-model "Clojure reference model; not a trusted runtime"})

(defn- total-amount
  [m]
  (reduce + 0 (map (fn [[_ v]] (long (or v 0))) m)))

(defn- normalize-liquidity-input
  [available-liquidity requested policy snapshot]
  {:available-liquidity (amount-string available-liquidity)
   :claim-buckets (mapv (fn [[bucket-id claim-amount]]
                          {:bucket-id (keyword-name bucket-id)
                           :claim-amount (amount-string claim-amount)})
                        requested)
   :policy-name (keyword-name (:mode policy :pro-rata))
   :rounding-mode (keyword-name (:rounding-policy policy))
   :snapshot snapshot})

(defn- normalize-liquidity-output
  [available-liquidity requested result]
  (let [filled (:filled result)
        deferred (:deferred result)
        total-requested (total-amount requested)
        total-fulfilled (total-amount filled)
        total-unmet (- total-requested total-fulfilled)]
    {:settlement-mode (keyword-name (:settlement-mode result))
     :bucket-results (mapv (fn [[bucket-id claim-amount]]
                             (let [fulfilled (long (or (get filled bucket-id) 0))
                                   unmet (long (or (get deferred bucket-id)
                                                   (- (long claim-amount) fulfilled)))]
                               {:bucket-id (keyword-name bucket-id)
                                :claim-amount (amount-string claim-amount)
                                :fulfilled-amount (amount-string fulfilled)
                                :unmet-amount (amount-string unmet)}))
                           requested)
     :total-requested (amount-string total-requested)
     :total-fulfilled (amount-string total-fulfilled)
     :total-unmet (amount-string total-unmet)
     :available-liquidity (amount-string available-liquidity)
     :remainder (amount-string (max 0 (- (long available-liquidity) total-fulfilled)))
     :reference-output result}))

(def liquidity-invariants
  [{:id :liquidity/conservation
    :expression "total_requested == total_fulfilled + total_unmet"}
   {:id :liquidity/available-bound
    :expression "total_fulfilled <= available_liquidity"}
   {:id :liquidity/no-over-fulfillment
    :expression "forall bucket: fulfilled_amount <= claim_amount"}
   {:id :liquidity/non-negative-amounts
    :expression "forall bucket: fulfilled_amount >= 0 and unmet_amount >= 0"}
   {:id :liquidity/deterministic-ordering
    :expression "bucket result order follows input claim bucket order"}])

(defn emit-liquidity-fulfillment-vector
  "Emit a canonical liquidity fulfillment vector by calling
   `calculate-fulfillment-pro-rata` and normalizing the result."
  [{:keys [vector-id description available-liquidity requested policy notes tags snapshot]
    :as opts}]
  (let [policy (merge partial-fill/default-partial-fill-policy
                      {:mode :pro-rata
                       :rounding-policy :largest-remainder}
                      policy)
        requested (or requested {})
        result (partial-fill/calculate-fulfillment-pro-rata available-liquidity requested policy)
        input (normalize-liquidity-input available-liquidity requested policy snapshot)
        expected-output (normalize-liquidity-output available-liquidity requested result)]
    (-> {:schema-version liquidity-schema-version
         :vector-id vector-id
         :domain "liquidity-fulfillment"
         :description description
         :input input
         :expected-output expected-output
         :invariants liquidity-invariants
         :source-function "resolver-sim.yield.partial-fill/calculate-fulfillment-pro-rata"
         :source-metadata (source-metadata (assoc opts
                                                  :source-function "resolver-sim.yield.partial-fill/calculate-fulfillment-pro-rata"
                                                  :generator-function "resolver-sim.test-vectors.pro-rata/emit-liquidity-fulfillment-vector"))
         :policy-metadata {:policy-id (keyword-name (:mode policy))
                           :policy-version "v1"
                           :rounding-policy (keyword-name (:rounding-policy policy))
                           :remainder-policy "unfulfilled-amount-recorded-as-unmet"
                           :canonical-ordering-policy "input-order"}
         :units (units-metadata opts)
         :snapshot-metadata (or snapshot {:snapshot-boundary "pre-fulfillment"})
         :trust-boundary (trust-boundary-metadata opts)
         :edge-case-tags (mapv keyword-name tags)
         :notes (or notes [])}
        attach-hashes)))

(def liquidity-golden-specs
  [{:vector-id "liquidity-exact-match"
    :description "Available liquidity exactly matches total requested claims."
    :available-liquidity 1000
    :requested {:principal 600 :realized-yield 400}
    :tags [:exact-match]}
   {:vector-id "liquidity-insufficient"
    :description "Insufficient liquidity is distributed pro rata across unequal claim buckets."
    :available-liquidity 500
    :requested {:principal 700 :realized-yield 300}
    :tags [:insufficient-liquidity :unequal-claims]}
   {:vector-id "liquidity-zero-available"
    :description "No available liquidity leaves all requested amounts unmet."
    :available-liquidity 0
    :requested {:principal 700 :realized-yield 300}
    :tags [:no-liquidity]}
   {:vector-id "liquidity-one-bucket"
    :description "Single claim bucket receives the available liquidity up to the claim amount."
    :available-liquidity 250
    :requested {:principal 1000}
    :tags [:single-bucket :insufficient-liquidity]}
   {:vector-id "liquidity-equal-buckets-dust"
    :description "Equal buckets with indivisible liquidity exercise deterministic largest-remainder dust handling."
    :available-liquidity 10
    :requested {:a 100 :b 100 :c 100}
    :tags [:dust :equal-buckets]}])

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
    :description "Zero-weight party receives zero liability under current Sew policy."
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

(defn vector-filename
  [v]
  (case (:domain v)
    "liquidity-fulfillment" (str "liquidity-fulfillment-" (:vector-id v) ".json")
    "slash-allocation" (str "slash-allocation-" (:vector-id v) ".json")))

(defn write-vector!
  [dir v]
  (let [file (io/file dir (vector-filename v))]
    (.mkdirs (.getParentFile file))
    (spit file (str (canonical-json v) "\n"))
    (.getPath file)))

(defn write-liquidity-fulfillment-vectors!
  ([] (write-liquidity-fulfillment-vectors! "results/test-vectors/pro-rata"))
  ([dir]
   (mapv #(write-vector! dir (emit-liquidity-fulfillment-vector %)) liquidity-golden-specs)))

(defn golden-vectors
  []
  (vec (concat (map emit-liquidity-fulfillment-vector liquidity-golden-specs)
               (map sew-slash/emit-slash-allocation-vector sew-slash/slash-golden-specs))))

(defn write-golden-vectors!
  ([] (write-golden-vectors! "resources/test-vectors/pro-rata"))
  ([dir]
   (mapv #(write-vector! dir %) (concat
                                 (map emit-liquidity-fulfillment-vector liquidity-golden-specs)
                                 (map sew-slash/emit-slash-allocation-vector sew-slash/slash-golden-specs)))))
