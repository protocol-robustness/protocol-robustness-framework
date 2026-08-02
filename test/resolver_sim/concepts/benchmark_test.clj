(ns resolver-sim.concepts.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.concepts.registry :as concepts-registry]
            [resolver-sim.concepts.reporting :as reporting]))

(deftest resolve-benchmark-concepts-prefers-local-shadow
  (testing "benchmark-local concepts override global concepts with the same id"
    (let [local-concepts [{:concept/id :allocation/shortfall
                           :concept/title "Local shortfall"
                           :concept/summary "Benchmark-local summary"
                           :concept/stakeholder-language "Benchmark-local language"
                           :concept/why-it-matters "Benchmark-local rationale"
                           :concept/maps-to {:scenarios ["S103_negative-yield-shortfall-cascade"]}}]
          resolved (benchmark-concepts/resolve-benchmark-concepts
                    [:allocation/shortfall :consensus/finality :concept/missing]
                    {:local-concepts local-concepts})]
      (is (= [:concept/missing] (:unknown-concept-ids resolved)))
      (is (= :allocation/shortfall
             (get-in resolved [:local-concepts 0 :concept/id])))
      (is (= "Local shortfall"
             (get-in resolved [:report-concepts 0 :concept/title])))
      (is (= "Consensus finality"
             (get-in resolved [:report-concepts 1 :concept/title]))))))

(deftest missing-related-concepts-detects-unresolved-links
  (testing "related concept validation reports unresolved ids"
    (is (= [{:from :concept/a :to :concept/missing}]
           (concepts-registry/missing-related-concepts
            [{:concept/id :concept/a
              :concept/related [:concept/b :concept/missing]}
             {:concept/id :concept/b}])))))

(deftest normalize-concept-adds-mapping-statuses
  (let [concept {:concept/roles {:native {:maps-to [:protocol.actor/sender]}
                                 :approximate {:maps-to [:protocol.actor/resolver]
                                               :mapping/confidence :approximate}}
                 :concept/entities {:unsupported {:maps-to []}}
                 :concept/actions {}
                 :concept/outcomes {}}
        normalized (concepts-registry/normalize-concept concept)]
    (is (= :native (get-in normalized [:concept/roles :native :mapping/status])))
    (is (= :approximate (get-in normalized [:concept/roles :approximate :mapping/status])))
    (is (= :not-modelled (get-in normalized [:concept/entities :unsupported :mapping/status])))
    (is (= [{:concept/id nil
             :mapping/category :concept/roles
             :mapping/id :native
             :mapping/label :protocol.actor/sender
             :error :unsupported-capability-label}]
           (concepts-registry/capability-validation-errors
            concept
            #{:protocol.actor/resolver})))))

(deftest held-custody-add-held-is-abstract-classification
  (testing "add-held is an abstract held-classification increase, not a concrete ledger mutation"
    (let [{:keys [concepts]} (concepts-registry/load-registry)
          held (first (filter #(= (:concept/id %) :framework/held-custody) concepts))]
      (is (some? held))
      (testing "add-held action exists and maps to the abstract record-held-adjustment action"
        (let [add-held (get-in held [:concept/actions :add-held])]
          (is (some? add-held))
          (is (some #(= % :protocol.action/record-held-adjustment) (:maps-to add-held)))))
      (testing "out-of-scope assigns concrete ledger mutation and economic meaning to other layers"
        (let [oos (:concept/out-of-scope held)]
          (is (some #(re-find #"(?i)protocol accounting|protocol-ledger mutation" %) oos))
          (is (some #(re-find #"(?i)protocol policy|economic event" %) oos)))))))

(deftest yield-aggregate-shortfall-cap-and-held-adjacency
  (testing "yield concept covers aggregate shortfall cap and held-custody adjacency"
    (let [{:keys [concepts]} (concepts-registry/load-registry)
          yield-c (first (filter #(= (:concept/id %) :yield/yield-bearing) concepts))]
      (is (some? yield-c))
      (testing "aggregate shortfall checks exist as actions"
        (let [actions (:concept/actions yield-c)]
          (is (some? (:check-aggregate-shortfall-cap actions)))
          (is (some? (:check-aggregate-shortfall actions)))
          (is (some? (:check-aggregate actions)))))
      (testing "held-custody is a related concept (add-held adjacency)"
        (is (some #(= % :framework/held-custody) (:concept/related yield-c))))
      (testing "out-of-scope assigns concrete ledger mutation to protocol accounting"
        (is (some #(re-find #"(?i)protocol accounting|protocol-extension-boundary" %) (:concept/out-of-scope yield-c)))))))

(deftest semantic-commitments-generic-categories
  (testing "semantic-commitments concept defines generic categories, not research-command-specific"
    (let [{:keys [concepts]} (concepts-registry/load-registry)
          sc (first (filter #(= (:concept/id %) :framework/semantic-commitments) concepts))
          rc (first (filter #(= (:concept/id %) :framework/research-command) concepts))]
      (is (some? sc))
      (testing "allowed categories are declared"
        (let [note (:concept/note sc)]
          (is (some #(re-find #":semantic/scope" %) [note]))
          (is (some #(re-find #":semantic/authorisation" %) [note]))
          (is (some #(re-find #":semantic/economic-application" %) [note]))))
      (testing "research-command references semantic-commitments generically"
        (is (some #(= % :framework/semantic-commitments) (:concept/related rc)))
        (let [note (:concept/note rc)]
          (is (not (re-find #"partial-fill decisions, propagation refs" note))))))))

(deftest enrich-report-surfaces-limitations-and-mapping-statuses
  (let [report (reporting/enrich-report
                {}
                [{:concept/id :example/use-case
                  :concept/name "Example"
                  :concept/summary "Example summary"
                  :concept/stakeholder-question "Example question"
                  :concept/maturity :illustrative
                  :concept/support-status :not-asserted
                  :concept/assumptions []
                  :concept/out-of-scope []
                  :concept/known-gaps ["No runnable scenario"]
                  :concept/evidence {:scenarios #{} :benchmarks #{} :claims #{}}
                  :concept/roles {}
                  :concept/entities {}
                  :concept/actions {:create {:maps-to [:protocol.action/create-escrow]}}
                  :concept/outcomes {}}])
        summary (get-in report [:concept/section :concept/summaries 0])]
    (is (= :not-asserted (:concept/support-status summary)))
    (is (= ["No runnable scenario"] (:concept/known-gaps summary)))
    (is (some #{"This mapping is illustrative; it is not a production-support claim."}
              (:concept/not-claimed summary)))
    (is (some #{"No executable scenario, benchmark, or claim evidence is linked to this concept."}
              (:concept/not-claimed summary)))
    (is (= :native (get-in summary [:concept/mappings :concept/actions :create :mapping/status])))))
