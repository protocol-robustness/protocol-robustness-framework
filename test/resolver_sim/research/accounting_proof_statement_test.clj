(ns resolver-sim.research.accounting-proof-statement-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.accounting-proof-statement :as statement]
            [resolver-sim.research.semantic-accounting-test :as fixture]))

(defn proof-fixture []
  (let [{:keys [request plan] :as f} (fixture/accounting-fixture)
        unit (first (:partition/work-units plan))
        partial (fixture/accounting-partial request unit
                                            (:implementation-a fixture/roots)
                                            (:runner-a fixture/roots)
                                            (:run-a fixture/roots))]
    (assoc f :partial partial)))

(def rust-build
  (delay (shell/sh "cargo" "build" "--offline" "--manifest-path" "coprocessor/Cargo.toml"
                   "-p" "allocation-kernel" "--bin" "accounting_arithmetic_statement")))

(defn guest
  ([input] (guest input []))
  ([input args]
   (when-not (zero? (:exit @rust-build))
     (throw (ex-info "Arithmetic validator build failed" @rust-build)))
   (apply shell/sh "coprocessor/target/debug/accounting_arithmetic_statement"
          (concat args [:in (json/write-str input)]))))

(deftest cross-language-arithmetic-and-identity-conformance
  (let [{:keys [request contract plan partial]} (proof-fixture)
        projection (statement/derive-statement request contract plan partial)
        input (statement/proof-input request contract plan partial)
        result (guest input)]
    (is (zero? (:exit result)) (:err result))
    (is (= (str (statement/public-values-hex projection) "\n") (:out result)))
    (is (= 320 (count (statement/public-values-hex projection))))
    (is (not (contains? input "public_values")))
    (is (= input (statement/proof-input request contract plan partial)))
    (is (= (:canonical-accounting-partial/root projection)
           (accounting/partial-semantic-root partial)))))

(deftest guest-rejects-source-root-and-lineage-substitutions
  (let [{:keys [request contract plan partial]} (proof-fixture)
        input (statement/proof-input request contract plan partial)
        changed-request (assoc request :accounting/cutpoint-root (:claim fixture/roots))
        changed-plan (assoc plan :accounting-request/root (:claim fixture/roots))
        changed-unit (assoc (first (:partition/work-units plan))
                            :partition/source-roots [])]
    (doseq [[label changed] [[:request (assoc input "request" (#'statement/wire changed-request))]
                             [:plan (assoc input "plan" (#'statement/wire changed-plan))]
                             [:unit (assoc input "plan" (#'statement/wire
                                                         (assoc plan :partition/work-units [changed-unit])))]
                             [:contract (assoc input "contract" (#'statement/wire
                                                                 (assoc contract :reduction/rule :untrusted)))]
                             [:projection-only {"schema" statement/schema
                                                "public_values" (statement/public-values-hex
                                                                 (statement/derive-statement request contract plan partial))}]]]
      (testing (name label) (is (not (zero? (:exit (guest changed)))))))))

(deftest correctly-rooted-but-false-arithmetic-is-rejected
  (let [{:keys [request contract plan partial]} (proof-fixture)
        input (statement/proof-input request contract plan partial)
        wrong (assoc partial :accounting/values {:accounting/debit 999})
        canonical {:artifact/schema "canonical-accounting-partial.v1"
                   :accounting-request/root (:accounting-request/root wrong)
                   :accounting-work-unit/root (:accounting-work-unit/root wrong)
                   :partition/source-roots (:partition/source-roots wrong)
                   :accounting/values (:accounting/values wrong)
                   :canonical-accounting-partial/root (accounting/partial-semantic-root wrong)}]
    (is (thrown? clojure.lang.ExceptionInfo (statement/proof-input request contract plan wrong)))
    (is (not (zero? (:exit (guest (assoc input "partial" (#'statement/wire canonical)))))))
    (is (re-find #"incorrect arithmetic"
                 (:err (guest (assoc input "partial" (#'statement/wire canonical))))))))

(deftest coordinator-witness-derivation-does-not-recompute-but-guest-still-rejects
  (let [{:keys [request contract plan partial]} (proof-fixture)
        wrong (assoc partial :accounting/values {:accounting/debit 999})
        wrong (assoc wrong :accounting-partial-result/root (accounting/partial-root wrong))
        input (statement/proof-input-for-verification request contract plan wrong)]
    (is (= input (statement/proof-input-for-verification request contract plan wrong)))
    (is (not (zero? (:exit (guest input)))))
    (let [commitments (guest input ["--commitments-only"])]
      (is (zero? (:exit commitments)) (:err commitments))
      (is (= (str (statement/public-values-hex
                   (statement/derive-commitment-projection request contract plan wrong)) "\n")
             (:out commitments))))))

(deftest partition-omission-with-recomputed-plan-root-is-rejected
  (let [{:keys [request contract plan partial]} (proof-fixture)
        truncated (assoc plan :partition/work-units [(first (:partition/work-units plan))])
        rooted (assoc truncated :accounting-partition-plan/root
                      (accounting/partition-plan-root truncated))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (statement/derive-statement request contract rooted partial)))
    (is (re-find #"incomplete source coverage"
                 (:err (guest (assoc (statement/proof-input request contract plan partial)
                                     "plan" (#'statement/wire rooted))))))))

(deftest execution-provenance-is-not-arithmetic-result-identity
  (let [{:keys [request contract plan partial]} (proof-fixture)
        unit (first (:partition/work-units plan))
        other (fixture/accounting-partial request unit
                                          (:implementation-b fixture/roots)
                                          (:runner-b fixture/roots) (:run-b fixture/roots))]
    (is (not= (:accounting-partial-result/root partial) (:accounting-partial-result/root other)))
    (is (= (statement/derive-statement request contract plan partial)
           (statement/derive-statement request contract plan other)))
    (is (= (statement/proof-input request contract plan partial)
           (statement/proof-input request contract plan other)))))

(deftest wider-integer-domains-are-not-silently-narrowed
  (let [{:keys [request contract plan partial]} (proof-fixture)
        changed (update-in request [:accounting/sources 0 :accounting/value] bigint)
        changed (assoc changed :accounting-request/root (accounting/request-root changed))
        requirements {:dependency-set-root (:dependency fixture/roots)
                      :execution-environment-root (:environment fixture/roots)
                      :allowed-implementation-roots [(:implementation-a fixture/roots)]
                      :required-capabilities [:capability/accounting] :privacy-requirement :public}
        new-plan (accounting/build-partition-plan changed
                                                  (mapv :partition/source-roots (:partition/work-units plan))
                                                  requirements)
        unit (first (:partition/work-units new-plan))
        partial (assoc partial :accounting-request/root (:accounting-request/root changed)
                       :accounting-work-unit/root (:accounting-work-unit/root unit))]
    (is (= :not-comparable
           (try (statement/derive-statement changed contract new-plan partial)
                (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))))

(deftest overflow-and-ambiguous-witnesses-fail-closed
  (let [{:keys [request contract plan partial]} (proof-fixture)
        input (statement/proof-input request contract plan partial)
        overflowing (-> request
                        (assoc-in [:accounting/sources 0 :accounting/value] Long/MAX_VALUE)
                        (assoc-in [:accounting/sources 1 :accounting/value] 1))
        rooted (assoc overflowing :accounting-request/root (accounting/request-root overflowing))
        duplicate (update-in input ["request" "$map"] #(conj % (first %)))]
    (is (re-find #"checked-long overflow" (:err (guest (assoc input "request" (#'statement/wire rooted))))))
    (is (re-find #"duplicate map key" (:err (guest duplicate))))
    (is (zero? (:exit (guest (update-in input ["request" "$map"] #(vec (reverse %)))))))))

(deftest changed-parent-with-same-sum-has-different-public-identity
  (let [{:keys [request contract plan partial]} (proof-fixture)
        new-request (assoc request :accounting/cutpoint-root (:claim fixture/roots))
        new-request (assoc new-request :accounting-request/root (accounting/request-root new-request))
        old-unit (first (:partition/work-units plan))
        new-plan (accounting/build-partition-plan
                  new-request (mapv :partition/source-roots (:partition/work-units plan))
                  {:dependency-set-root (:work-unit/dependency-set-root old-unit)
                   :execution-environment-root (:work-unit/execution-environment-root old-unit)
                   :allowed-implementation-roots (:work-unit/allowed-implementation-roots old-unit)
                   :required-capabilities (:work-unit/required-capabilities old-unit)
                   :privacy-requirement (:work-unit/privacy-requirement old-unit)})
        new-partial (fixture/accounting-partial new-request (first (:partition/work-units new-plan))
                                                (:implementation-a fixture/roots)
                                                (:runner-a fixture/roots) (:run-a fixture/roots))]
    (is (= (:accounting/values partial) (:accounting/values new-partial)))
    (is (not= (statement/derive-statement request contract plan partial)
              (statement/derive-statement new-request contract new-plan new-partial)))
    (is (zero? (:exit (guest (statement/proof-input new-request contract new-plan new-partial)))))))
