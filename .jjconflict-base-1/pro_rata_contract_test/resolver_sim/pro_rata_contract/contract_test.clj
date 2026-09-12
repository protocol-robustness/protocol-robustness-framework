(ns resolver-sim.pro-rata-contract.contract-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata-contract.canonical :as canonical]
            [resolver-sim.pro-rata-contract.contract :as contract])
  (:import [java.security MessageDigest]))

(defn- fixture [name]
  (edn/read-string (slurp (io/file "portable-pro-rata-contract" "vectors" name))))

(deftest canonical-transition-vector-reconstructs
  (let [{:keys [state-before raw-effects expected]} (fixture "canonical-transition-001.edn")
        transition (contract/transition state-before raw-effects)]
    (is (= (:normalized-effects expected) (:effects transition)))
    (is (= (:state-after expected) (:state-after transition)))
    (is (= (:roots expected)
           {:state-before/root (:state-before/root transition)
            :effects/root (:effects/root transition)
            :state-after/root (:state-after/root transition)
            :transition/root (:canonical-effect-transition/root transition)}))
    (doseq [[artifact value] (:canonical-values expected)]
      (is (= (slurp (io/file "portable-pro-rata-contract" "canonical-bytes"
                             (str (name artifact) ".hex")))
             (str (canonical/hex (canonical/canonical-bytes value)) "\n"))))))

(deftest rejection-vectors
  (is (= :portable-contract/underflow
         (:reason (ex-data (try (contract/transition {"q" 1N} [(contract/delta "q" -2N)])
                                (catch clojure.lang.ExceptionInfo error error))))))
  (is (= :portable-contract/noncanonical-input
         (:reason (ex-data (try (canonical/canonical-bytes #{1N})
                                (catch clojure.lang.ExceptionInfo error error)))))))

(defn- file-sha256 [file]
  (with-open [input (io/input-stream file)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur))))
      (canonical/hex (.digest digest)))))

(defn- decode-hex [value]
  (byte-array
   (map (fn [index]
          (unchecked-byte
           (Integer/parseInt (subs value index (+ index 2)) 16)))
        (range 0 (count value) 2))))

(deftest allocation-canonical-bytes-commit-to-published-root
  (let [vector (fixture "allocation-rounding-001.edn")
        body-hex (str/trim
                  (slurp (io/file "portable-pro-rata-contract"
                                  (get-in vector [:expected :canonical-bytes/file]))))
        digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes ^String (:projection-artifact contract/domains) "UTF-8"))
    (.update digest (decode-hex body-hex))
    (is (= (get-in vector [:expected :allocation/hash])
           (canonical/hex (.digest digest))))))

(deftest release-manifest-is-closed-and-rooted
  (let [root (io/file "portable-pro-rata-contract")
        manifest (edn/read-string (slurp (io/file root "manifest.edn")))]
    (is (= (:portable-contract/release-root manifest) (contract/release-root manifest)))
    (doseq [{:keys [path sha256]} (:files manifest)]
      (is (= sha256 (file-sha256 (io/file root path)))))))

(def allocation-vectors
  ["cap-clamping-001.edn"
   "redistribute-cap-excess-001.edn"
   "zero-weight-001.edn"
   "floor-residual-001.edn"])

(deftest allocation-vectors-reconstruct-independently
  (doseq [name allocation-vectors]
    (let [{:keys [request expected]} (fixture name)
          result (contract/allocate request)
          generated-bytes (canonical/canonical-bytes
                           (dissoc result :allocation/hash :allocation/fractional-remainder-total))
          published-bytes (decode-hex
                           (str/trim
                            (slurp (io/file "portable-pro-rata-contract"
                                            (:canonical-bytes/file expected)))))
          digest (MessageDigest/getInstance "SHA-256")]
      (.update digest (.getBytes ^String (:projection-artifact contract/domains) "UTF-8"))
      (.update digest published-bytes)
      (is (= (:canonical-row-order expected) (mapv :row/id (:rows result))))
      (is (= (:rows expected) (mapv #(select-keys % [:row/id :allocated :unmet]) (:rows result))))
      (is (= (:allocated-total expected) (:allocated-total result)))
      (is (= (:unallocated-residual expected) (:unallocated-residual result)))
      (is (= (:residual-reason expected) (:residual-reason result)))
      (is (= (:allocation/hash expected) (:allocation/hash result)))
      (is (= (:allocation/hash result) (contract/allocation-result-root result)))
      (is (= (alength generated-bytes) (alength published-bytes)))
      (is (java.util.Arrays/equals generated-bytes published-bytes))
      (is (= (:allocation/hash expected) (canonical/hex (.digest digest))))
      (when-let [rounds (:rounds expected)]
        (is (= rounds (mapv #(select-keys % [:round/index :active-row-ids
                                             :newly-cap-constrained-row-ids
                                             :committed-by-cap :available-after-caps])
                            (:allocation/rounds result))))))))

(deftest allocation-rejection-contract
  (let [{:keys [request]} (fixture "cap-clamping-001.edn")
        result (contract/allocate request)
        reason (fn [thunk] (:reason (ex-data (try (thunk) (catch clojure.lang.ExceptionInfo error error)))))]
    (is (= :portable-contract/malformed-input
           (reason #(contract/allocate (assoc request :rows (assoc (:rows request) 1 (assoc (second (:rows request)) :row/id :row/b)))))))
    (is (= :portable-contract/unknown-policy
           (reason #(contract/allocate (assoc request :tie-break-policy :input-order)))))
    (is (= :portable-contract/projection-mismatch
           (reason #(contract/verify-allocation-result request (assoc result :rows (vec (reverse (:rows result))))))))
    (is (= :portable-contract/projection-mismatch
           (reason #(contract/verify-allocation-result request (assoc-in result [:rows 0 :effective-cap] 3N)))))
    (is (= :portable-contract/projection-mismatch
           (reason #(contract/verify-allocation-result request (assoc-in result [:allocation/rounds 0 :committed-by-cap] 3N)))))))

(deftest production-realization-is-absent
  (is (nil? (io/resource "resolver_sim/pro_rata/allocation.clj")))
  (is (nil? (io/resource "resolver_sim/protocols/sew/core.clj"))))
