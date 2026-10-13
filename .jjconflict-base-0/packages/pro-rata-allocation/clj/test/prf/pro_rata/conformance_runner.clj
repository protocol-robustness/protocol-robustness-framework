(ns prf.pro-rata.conformance-runner
  "Standalone runner for frozen allocation roots, bytes, and request admission."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest]]
            [prf.canonical.codec :as codec]
            [prf.pro-rata.api :as api]
            [prf.pro-rata.identity :as identity]
            [prf.pro-rata.outcome :as outcome]
            [prf.pro-rata.realized-statement :as realized-statement]
            [prf.pro-rata.result :as result]
            [prf.pro-rata.reference :as reference]))

(def ^:dynamic *fixture-root* nil)

(def ^:private portable-release-root
  "b863e04a61d0dded845dc3351626ebf57c4d5abea7e18ea19a89cacf4b1ad12a")

(def ^:private allocation-fixture-manifest-sha256
  "21b541bcc7958f96821927ebe10c16030e0461247afe0dcb82d622e2c4142157")

(defn- sha256-hex [^bytes bytes]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- fixture-edn [root relative-path]
  (edn/read-string (slurp (io/file root relative-path))))

(deftest allocation-fixture-bundle-is-intact
  (let [manifest-bytes (java.nio.file.Files/readAllBytes
                        (.toPath (io/file *fixture-root* "allocation-fixtures.v1.edn")))
        manifest (edn/read-string (String. manifest-bytes java.nio.charset.StandardCharsets/UTF_8))]
    (test/is (= allocation-fixture-manifest-sha256 (sha256-hex manifest-bytes))
             "package allocation fixture manifest digest")
    (test/is (= portable-release-root (:source-release-root manifest))
             "source portable release root is unchanged")
    (doseq [{:keys [path sha256]} (:files manifest)]
      (let [bytes (java.nio.file.Files/readAllBytes
                   (.toPath (io/file *fixture-root* path)))]
        (test/is (= sha256 (sha256-hex bytes)) (str "file digest: " path))))
    (test/is (seq (:files manifest)) "allocation fixtures are registered")))

(deftest allocation-vectors-match-frozen-roots-and-bytes
  (let [vector-dir (io/file *fixture-root* "vectors")
        vector-files (->> (.listFiles vector-dir)
                          (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
                          (sort-by #(.getName %)))]
    (doseq [file vector-files
            :let [{:keys [request expected]} (edn/read-string (slurp file))]
            :when (:canonical-bytes/file expected)]
      (let [allocation (api/allocate request)
            bytes-file (io/file *fixture-root* (:canonical-bytes/file expected))
            expected-bytes (str/trim (slurp bytes-file))]
        (test/is (= (:allocation/hash expected) (:allocation/hash allocation))
                 (str "allocation root: " (.getName file)))
        (test/is (= expected-bytes
                    (codec/canonical-bytes-hex (result/root-input allocation)))
                 (str "canonical result bytes: " (.getName file)))
        (when-let [expected-request-root (:request/hash expected)]
          (test/is (= expected-request-root
                      (identity/request-root (:canonical-request allocation)))
                   (str "request root: " (.getName file))))))))

(deftest allocation-vectors-match-independent-reference
  (let [vector-dir (io/file *fixture-root* "vectors")
        vector-files (->> (.listFiles vector-dir)
                          (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
                          (sort-by #(.getName %)))]
    (doseq [file vector-files
            :let [{:keys [request expected]} (edn/read-string (slurp file))]
            :when expected]
      (let [actual (api/allocate request)
            oracle (reference/allocate request)]
        (test/is (= oracle actual)
                 (str "independent allocation reference: " (.getName file)))
        (test/is (true? (reference/verify-allocation-result request oracle))
                 (str "independent allocation self-verification: " (.getName file)))))))

(defn- apply-result-mutation [allocation-result mutation-entry]
  (let [mutation-id (:mutation/id mutation-entry)
        mutation-result (:result mutation-entry)]
    (case mutation-id
      :canonical-row-order-disagreement
      (let [rows-by-id (into {} (map (juxt :row/id identity) (:rows allocation-result)))]
        (assoc allocation-result :rows (mapv rows-by-id (:rows/order mutation-result))))

      :cap-mutation
      (update allocation-result :rows
              (fn [rows]
                (mapv #(if (= (:row/id mutation-result) (:row/id %))
                         (merge % (dissoc mutation-result :row/id))
                         %)
                      rows)))

      (:redistribution-witness-mutation :malformed-allocation-witness)
      (assoc allocation-result :allocation/rounds (:allocation/rounds mutation-result))

      allocation-result)))

(deftest result-verifier-accepts-derived-results-and-rejects-frozen-mutations
  (let [negative (fixture-edn *fixture-root* "vectors/negative-allocation-001.edn")
        basis (fixture-edn *fixture-root* "vectors/cap-clamping-001.edn")
        request (:request basis)
        derived (api/allocate request)
        result-mutations (filter :result (:mutations negative))]
    (test/is (true? (api/verify-result request derived)))
    (test/is (true? (api/verify-result request
                                       (assoc derived :allocation/fractional-remainder-total
                                              {:diagnostic/changed true})))
             "V1 diagnostic remainder total is outside verification")
    (test/is (= 4 (count result-mutations))
             "all frozen result mutations are exercised")
    (doseq [entry result-mutations
            :let [mutated (apply-result-mutation derived entry)
                  outcome (try
                            (api/verify-result request mutated)
                            nil
                            (catch clojure.lang.ExceptionInfo error
                              (:reason (ex-data error))))]]
      (test/is (= :portable-contract/projection-mismatch outcome)
               (str "result mutation rejected: " (:mutation/id entry))))))

(deftest negative-allocation-requests-are-rejected
  (let [negative (fixture-edn *fixture-root* "vectors/negative-allocation-001.edn")
        basis (fixture-edn *fixture-root* "vectors/cap-clamping-001.edn")
        expected-reasons {:duplicate-row-identity :duplicate-allocation-row-id
                          :policy-substitution :unsupported-tie-break-policy}
        request-mutations (filter :request (:mutations negative))]
    (test/is (= (count expected-reasons) (count request-mutations))
             "all currently supported negative request mutations are covered")
    (doseq [{:keys [mutation/id request]} request-mutations]
      (let [outcome (try
                      (api/allocate (merge (:request basis) request))
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        (:reason (ex-data error))))]
        (test/is (= (get expected-reasons id) outcome)
                 (str "negative request rejected: " id))))))

(deftest policy-outcome-is-standalone-and-domain-separated
  (let [request {:allocation/id :conformance/policy-outcome
                 :available 5
                 :rows [{:row/id :a :obligation/id :a :requested 5 :weight 1}
                        {:row/id :b :obligation/id :b :requested 5 :weight 1}]}
        result (api/allocate request)
        policy-outcome (outcome/build-policy-outcome result)]
    (test/is (= "pro-rata-policy-outcome.v1" (:schema/version policy-outcome)))
    (test/is (= "PRO_RATA_POLICY_OUTCOME_V1" outcome/outcome-domain))
    (test/is (= (:allocation/hash result) (:allocation-result/root policy-outcome)))
    (test/is (= policy-outcome (outcome/validate-policy-outcome! policy-outcome)))
    (test/is (= :binding-invalid
                (try
                  (outcome/validate-policy-outcome! (assoc policy-outcome :policy/root "changed"))
                  nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))

(deftest policy-outcome-vectors-match-pinned-roots
  (let [{:keys [schema/version cases]}
        (fixture-edn *fixture-root* "vectors/policy-outcome-conformance-v1.edn")]
    (test/is (= "pro-rata-policy-outcome-conformance.v1" version))
    (doseq [{:keys [id request expected]} cases
            :let [allocation-result (api/allocate request)
                  policy-outcome (outcome/build-policy-outcome allocation-result)]]
      (test/is (= (:allocated-total expected) (:allocated-total allocation-result))
               (str id " allocated total"))
      (test/is (= (:unallocated-residual expected) (:unallocated-residual allocation-result))
               (str id " residual"))
      (test/is (= (:rounding-result expected)
                  (get-in policy-outcome [:rounding/outcome :result]))
               (str id " rounding outcome"))
      (test/is (= (:outcome-root expected) (:outcome/root policy-outcome))
               (str id " frozen outcome root"))
      (test/is (= policy-outcome (outcome/validate-policy-outcome! policy-outcome))
               (str id " outcome validates")))))

(deftest realized-statement-matches-frozen-v1-roots
  (let [decision {:requested {"A" 50 "B" 30 "C" 20}
                  :filled {"A" 50 "B" 30 "C" 20}
                  :deferred {} :haircut {}
                  :policy {:mode :pro-rata
                           :rounding-policy :largest-remainder
                           :fail-action-policy {:mode :pro-rata-treatment
                                                :deferred-policy :same-ratio
                                                :haircut-policy :same-ratio}}}
        statement (realized-statement/build-statement-from-roots
                   {:allocation-context-root
                    "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5"
                    :round-lifecycle-root
                    "1e7d41793ce424f39e2b2afc83dbb5c528cdd96a746d0a48949f1db5845b4a4a"
                    :decision decision})]
    (test/is (= "realized-allocation-statement.v1" (:schema-version statement)))
    (test/is (= "9c495e37e9844035bd5273dac30682bfb99293c1f380034a69107e8765076114"
                (:request-set-root statement)))
    (test/is (= "798399d750475539bb518657121104c3c4ddea934cb0d61c044699f6671b64cb"
                (:allocation-policy-root statement)))
    (test/is (= "f0ba9de83a691600c73de0ddde4f8d1ab673ba4942a72340c782a5a350278b83"
                (:realized-results-root statement)))
    (test/is (= "10ff923ee0517c2e1dfbbb208946fe0834a8992273ebd983c2c2cd59658c08e7"
                (:fail-action-policy-root statement)))
    (test/is (= "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"
                (:statement/root statement)))
    (test/is (true? (:statement/all-active? statement)))))

(defn -main
  [& [fixture-root]]
  (let [fixture-root (io/file (or fixture-root "test/fixtures/portable-pro-rata-contract"))]
    (when-not (.isDirectory fixture-root)
      (throw (ex-info "Portable contract fixture directory not found"
                      {:fixture-root (.getPath fixture-root)})))
    (binding [*fixture-root* fixture-root]
      (let [summary (test/run-tests 'prf.pro-rata.conformance-runner)]
        (shutdown-agents)
        (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1))))))
