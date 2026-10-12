(ns resolver-sim.accounting.reductions-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]))

(defn- rooted [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(def ^:private golden
  {:contract "sha256:5a0f00177c21e8cb399c724937f51fe221fdb2decb2c24aaf1db1d087b002a76"
   :request "sha256:c262c3a763acd433fa7af9c28483fc35390876af17a8638eb84cd23d3732e595"
   :plan "sha256:ff55162a5d26824cf24daaa71b379bbd95c9ae454f963549741067c84f0e8eb2"
   :units ["sha256:57631f9eaa2f458cdd544ee2bc00ef8a83b61897a2290b13c65ca4342d8a80b6"
           "sha256:2c76ee93504160f4a3049fda3cc4e0f537dcc5cb12b0717ec1ab2a8162e216d3"]
   :partials ["sha256:1db86af584f39566f04cacc3048731638fb9ac4bf9dce568b59e736c460bac49"
              "sha256:943abab383092b8a0010fbc3a1863d75b73f267f49b6fae5b2285cd4bd0ebc73"]
   :semantic-partials ["sha256:54746c5068c976d2d2f60ffb5cb230507c0e34a9e23eefffb406a3d7a1cccf6b"
                       "sha256:ad94e0aa1d2b937a7b0e8f5947e991e9a727a0636c4fe159e4b780cffd5276ca"]
   :reconciliation "sha256:abd44762c15aaf139b553769fa87083024f463bbff11c04e49e97196d8fa3dbb"
   :result "sha256:a531bb7d1333947e628bbdb5e43be2c44fa2c861d96f396f890927a048d31721"})

(defn- golden-fixture []
  (let [contract (accounting/build-reduction-contract)
        source-a (rooted \6)
        source-b (rooted \7)
        source-c (rooted \8)
        request (accounting/build-request
                 {:accounting-domain :accounting-domain/claim-basis
                  :cutpoint-root (rooted \5)
                  :accounting-definition-root (rooted \6)
                  :accounting-policy-root (rooted \7)
                  :reduction-contract contract
                  :sources [{:source/root source-a :accounting/category :accounting/debit
                             :accounting/value 11 :accounting/subject-root (rooted \8)
                             :claim-to/root "sha256:de9b477c5a00db9d9b25d14d66bc85b8c7b2820a1086833ab358a1a3c028c88e"}
                            {:source/root source-b :accounting/category :accounting/debit
                             :accounting/value -3 :accounting/subject-root (rooted \8)
                             :claim-to/root "sha256:de9b477c5a00db9d9b25d14d66bc85b8c7b2820a1086833ab358a1a3c028c88e"}
                            {:source/root source-c :accounting/category :accounting/credit
                             :accounting/value 8 :accounting/subject-root (rooted \9)
                             :claim-to/root "sha256:00df30eb4de523c934437627106ee059468aa2c45c1ed3886a6517ff73e98364"}]})
        requirements {:dependency-set-root (rooted \a)
                      :execution-environment-root (rooted \b)
                      :allowed-implementation-roots [(rooted \c) (rooted \d)
                                                     (str "sha256:" (apply str (repeat 62 "0")) "17")]
                      :required-capabilities [:capability/accounting]
                      :privacy-requirement :public}
        plan (accounting/build-partition-plan request [[source-a source-b] [source-c]]
                                               requirements)]
    {:contract contract :request request :plan plan}))

(defn- golden-partial [request unit implementation runner run-root]
  (accounting/build-partial-result
   {:request request :work-unit unit
    :values (accounting/partial-values request unit)
    :run-assurance-root run-root :implementation-root implementation
    :runner-reference runner :resolved-dependency-set-root (rooted \a)
    :execution-environment-root (rooted \b)}))

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

(deftest package-preserves-the-complete-accounting-v1-golden-vector
  (let [{:keys [contract request plan]} (golden-fixture)
        units (:partition/work-units plan)
        partials [(golden-partial request (first units) (rooted \c) (rooted \e) (rooted \0))
                  (golden-partial request (second units) (rooted \d) (rooted \f) (rooted \1))]
        ingestion (reduce accounting/ingest-partial {} partials)
        reconciliation (accounting/reconcile request contract plan ingestion)]
    (is (= (:contract golden) (:accounting-reduction-contract/root contract)))
    (is (= (:request golden) (:accounting-request/root request)))
    (is (= (:plan golden) (:accounting-partition-plan/root plan)))
    (is (= (:units golden) (mapv :accounting-work-unit/root units)))
    (is (= (:partials golden) (mapv :accounting-partial-result/root partials)))
    (is (= (:semantic-partials golden) (mapv accounting/partial-semantic-root partials)))
    (is (= (:reconciliation golden) (:accounting-reconciliation/root reconciliation)))
    (is (= (:result golden) (:canonical-accounting-result/root reconciliation)))
    (is (= {:accounting/credit 8 :accounting/debit 8}
           (:accounting/values reconciliation)))))
