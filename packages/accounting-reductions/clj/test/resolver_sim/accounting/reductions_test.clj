(ns resolver-sim.accounting.reductions-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]))

(defn- rooted [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(deftest package-build-preserves-accounting-v1-contract-root
  (is (= "sha256:5a0f00177c21e8cb399c724937f51fe221fdb2decb2c24aaf1db1d087b002a76"
         (:accounting-reduction-contract/root
          (accounting/build-reduction-contract)))))

(deftest package-reconciles-a-complete-single-partition
  (let [contract (accounting/build-reduction-contract)
        request (accounting/build-request
                 {:accounting-domain :accounting/test
                  :cutpoint-root (rooted "1")
                  :accounting-definition-root (rooted "2")
                  :accounting-policy-root (rooted "3")
                  :reduction-contract contract
                 :sources [{:source/root (rooted "4")
                             :accounting/category :accounting/credit
                             :accounting/value 11
                             :accounting/subject-root (rooted "5")
                             :claim-to/root (rooted "6")} ]})
        requirements {:dependency-set-root (rooted "7")
                      :execution-environment-root (rooted "8")
                      :allowed-implementation-roots [(rooted "9")]
                      :required-capabilities [:accounting/sum]
                      :privacy-requirement :public}
        plan (accounting/build-partition-plan
              request [[(rooted "4")]] requirements)
        unit (first (:partition/work-units plan))
        partial (accounting/build-partial-result
                 {:request request :work-unit unit
                  :values (accounting/partial-values request unit)
                  :run-assurance-root (rooted "a")
                  :implementation-root (rooted "9")
                  :runner-reference (rooted "b")
                  :resolved-dependency-set-root (rooted "7")
                  :execution-environment-root (rooted "8")})
        ingestion (accounting/ingest-partial {} partial)
        reconciliation (accounting/reconcile request contract plan ingestion)]
    (is (= {:accounting/credit 11} (accounting/serial-values request)))
    (is (= :complete
           (:accounting/completeness
            (accounting/accounting-completeness request plan ingestion))))
    (is (= "accounting-reconciliation.v1"
           (:artifact/schema reconciliation)))
    (is (string? (:canonical-accounting-result/root reconciliation)))
    (is (= {:accounting/credit 11} (:accounting/values reconciliation)))))
