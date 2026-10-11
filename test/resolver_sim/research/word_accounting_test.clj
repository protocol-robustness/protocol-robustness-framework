(ns resolver-sim.research.word-accounting-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.accounting-basis-comparison :as comparison]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.research.word-accounting :as words]))

(defn- root [n] (str "sha256:" (format "%064x" n)))

(defn- runner [n opted-in?]
  (participation/build-participation
   {:runner-reference (root n) :disclosure-mode :pseudonymous
    :site-id (keyword "research-site" (str n))
    :opted-in? opted-in? :available? true
    :capabilities [:capability/word-span]
    :job-classes [:job/research-word-span]
    :environment-kinds [:execution-environment/local]
    :qualification-references []}))

(def source-text
  (str ":accounting/appeal-bond-posted 17 " (root 10) " " (root 11) "\n"
       ":accounting/appeal-bond-returned -4 " (root 10) " " (root 11) "\n"
       ":accounting/withdrawal 8 " (root 12) " " (root 13) "\n"
       ":accounting/custodian-held 3 " (root 14) " " (root 15) "\n"
       ":accounting/incentive-accrued 2 " (root 16) " " (root 17)))

(defn- execute-all [document plan participants]
  (loop [assignments [] ingestion {} index 0]
    (if-let [assignment (words/steal-next document plan
                                          (nth participants (mod index (count participants)))
                                          assignments)]
      (let [runner (nth participants (mod index (count participants)))
            observation (words/observe-word document plan assignment runner)]
        (recur (conj assignments assignment)
               (words/ingest-observation ingestion observation)
               (inc index)))
      {:assignments assignments :ingestion ingestion})))

(deftest word-stealing-reconciles-independent-of-runner-order
  (let [document (words/build-document source-text)
        plan (words/build-plan document)
        a (runner 1 true) b (runner 2 true)
        first-run (execute-all document plan [a b])
        second-run (execute-all document plan [b a])
        first-result (words/reconcile-words document plan (:assignments first-run)
                                            [a b] (:ingestion first-run))
        second-result (words/reconcile-words document plan (:assignments second-run)
                                             [a b] (:ingestion second-run))
        input {:accounting-domain :accounting-domain/declared-research-fixture
               :cutpoint-root (root 20)
               :accounting-definition-root (root 21)
               :accounting-policy-root (root 22)
               :reduction-contract (accounting/build-reduction-contract)}
        request-a (words/declared-accounting-request
                   document plan (:assignments first-run) [a b] (:ingestion first-run) input)
        request-b (words/declared-accounting-request
                   document plan (:assignments second-run) [a b] (:ingestion second-run) input)
        basis-a (words/derive-word-accounting-basis
                 document plan (:assignments first-run) [a b]
                 (:ingestion first-run) input)
        basis-b (words/derive-word-accounting-basis
                 document plan (:assignments second-run) [a b]
                 (:ingestion second-run) input)
        source-roots (mapv :source/root (:accounting/sources request-a))
        partition (accounting/build-partition-plan
                   request-a (mapv vector source-roots)
                   {:dependency-set-root (root 23)
                    :execution-environment-root (root 24)
                    :allowed-implementation-roots [(root 25)]
                    :required-capabilities [:capability/accounting]
                    :privacy-requirement :public})
        coarse-partition (accounting/build-partition-plan
                          request-a [(vec source-roots)]
                          {:dependency-set-root (root 23)
                           :execution-environment-root (root 24)
                           :allowed-implementation-roots [(root 25)]
                           :required-capabilities [:capability/accounting]
                           :privacy-requirement :public})
        comparison (comparison/compare-bases request-a partition request-b partition)]
    (is (= 20 (count (:word/work-units plan))))
    (is (= 20 (count (:assignments first-run))))
    (is (not= (:research-word-reconciliation/root first-result)
              (:research-word-reconciliation/root second-result)))
    (is (= (:word/semantic-root first-result) (:word/semantic-root second-result)))
    (is (= (:accounting-request/root request-a) (:accounting-request/root request-b)))
    (is (= (:accounting-request/root (:request basis-a))
           (:accounting-request/root (:request basis-b))))
    (is (not= (get-in basis-a [:basis-evidence :research-word-accounting-basis/root])
              (get-in basis-b [:basis-evidence :research-word-accounting-basis/root])))
    (is (= (:research-word-reconciliation/root (:word-reconciliation basis-a))
           (get-in basis-a [:basis-evidence :research-word-reconciliation/root])))
    (is (words/valid-word-accounting-basis?
         document plan (:assignments first-run) [a b] (:ingestion first-run)
         input basis-a))
    (is (not (words/valid-word-accounting-basis?
              document plan (:assignments first-run) [a b] (:ingestion first-run)
              input (assoc-in basis-a [:basis-evidence :accounting-request/root]
                              (root 999)))))
    (is (= {:accounting/appeal-bond-posted 17N
            :accounting/appeal-bond-returned -4N
            :accounting/withdrawal 8N
            :accounting/custodian-held 3N
            :accounting/incentive-accrued 2N}
           (accounting/serial-values request-a)))
    (is (accounting/valid-partition-plan? request-a partition))
    (is (= (comparison/partitioned-values request-a partition)
           (comparison/partitioned-values request-a coarse-partition)
           (accounting/serial-values request-a)))
    (is (= :equal (:comparison/status comparison)))
    (is (comparison/valid-comparison? request-a partition request-b partition
                                      comparison))
    (is (every? zero? (vals (:comparison/right-minus-left comparison))))))

(deftest missing-duplicate-and-substituted-word-evidence-fail
  (let [document (words/build-document source-text)
        plan (words/build-plan document)
        a (runner 1 true)
        {:keys [assignments ingestion]} (execute-all document plan [a])
        one (first (vals ingestion))
        unit-root (:research-word-work-unit/root one)
        altered (assoc one :word/text "999")]
    (is (= ingestion (words/ingest-observation ingestion one)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (words/ingest-observation ingestion altered)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (words/reconcile-words document plan assignments [a]
                                        (dissoc ingestion unit-root))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (words/reconcile-words document plan assignments [a]
                                        (assoc ingestion unit-root altered))))
    (let [other (second (vals ingestion))
          substituted (assoc one :word/index (:word/index other)
                             :word/start (:word/start other)
                             :word/end (:word/end other)
                             :word/text (:word/text other))
          substituted (assoc substituted :research-word-observation/root
                             ((deref #'resolver-sim.research.word-accounting/observation-root)
                              substituted))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (words/reconcile-words document plan assignments [a]
                                          (assoc ingestion unit-root substituted)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (words/reconcile-words document plan (conj assignments (first assignments))
                                        [a] ingestion)))
    (is (nil? (words/steal-next document plan (runner 3 false) [])))))

(deftest declared-row-substitution-changes-accounting-identity
  (let [a (words/build-document source-text)
        b (words/build-document
           (str/replace source-text "posted 17" "posted 18"))
        a-plan (words/build-plan a)
        b-plan (words/build-plan b)
        participant (runner 1 true)
        a-run (execute-all a a-plan [participant])
        b-run (execute-all b b-plan [participant])
        inputs {:accounting-domain :accounting-domain/declared-research-fixture
                :cutpoint-root (root 20)
                :accounting-definition-root (root 21)
                :accounting-policy-root (root 22)
                :reduction-contract (accounting/build-reduction-contract)}
        a-request (words/declared-accounting-request
                   a a-plan (:assignments a-run) [participant] (:ingestion a-run) inputs)
        b-request (words/declared-accounting-request
                   b b-plan (:assignments b-run) [participant] (:ingestion b-run) inputs)
        requirements {:dependency-set-root (root 23)
                      :execution-environment-root (root 24)
                      :allowed-implementation-roots [(root 25)]
                      :required-capabilities [:capability/accounting]
                      :privacy-requirement :public}
        a-plan-accounting (accounting/build-partition-plan
                           a-request [(mapv :source/root (:accounting/sources a-request))]
                           requirements)
        b-plan-accounting (accounting/build-partition-plan
                           b-request [(mapv :source/root (:accounting/sources b-request))]
                           requirements)
        incompatible-request
        (accounting/build-request
         {:accounting-domain :accounting-domain/different
          :cutpoint-root (root 20) :accounting-definition-root (root 21)
          :accounting-policy-root (root 22)
          :reduction-contract (accounting/build-reduction-contract)
          :sources (:accounting/sources b-request)})
        incompatible-plan (accounting/build-partition-plan
                           incompatible-request
                           [(mapv :source/root (:accounting/sources b-request))]
                           requirements)
        comparison (comparison/compare-bases a-request a-plan-accounting
                                             b-request b-plan-accounting)]
    (is (not= (:accounting-request/root a-request)
              (:accounting-request/root b-request)))
    (is (not= (accounting/serial-values a-request)
              (accounting/serial-values b-request)))
    (is (= :different (:comparison/status comparison)))
    (is (= 1 (get-in comparison [:comparison/right-minus-left
                                 :accounting/appeal-bond-posted])))
    (is (= 0 (get-in comparison [:comparison/right-minus-left
                                 :accounting/withdrawal])))
    (is (not (comparison/valid-comparison?
              a-request a-plan-accounting b-request b-plan-accounting
              (assoc comparison :comparison/status :equal))))
    (is (= :not-comparable
           (:comparison/status
            (comparison/compare-bases
             a-request a-plan-accounting
             incompatible-request incompatible-plan))))))
