(ns resolver-sim.claims.engine-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.claims.engine :as engine]
            [resolver-sim.hash.canonical :as hc]))

(defn- with-claim-hash
  [entry]
  (assoc entry :canonical-hash (hc/hash-with-intent {:hash/intent :claim-definition} entry)))

(defn- claim-registry
  [entries]
  {:registry-version 1
   :claim-definitions (mapv with-claim-hash entries)})

(defn- evidence-node
  [node-hash status]
  {:node-hash node-hash
   :result {:status status}})

(def base-registry
  (claim-registry
   [{:id :claim/base-pass
     :version 1
     :category :audit
     :description "Passes when all referenced evidence nodes passed."
     :inputs [:evidence-node]
     :evaluation {:type :code-reference
                  :entry 'resolver-sim.claims.engine/evaluate-pass-status-claim}
     :outputs [:holds? :observed-statuses]}
    {:id :claim/depends-on-base
     :version 1
     :category :audit
     :description "Requires evidence presence and the base claim to hold."
     :inputs [:evidence-node]
     :depends-on [:claim/base-pass]
     :evaluation {:type :code-reference
                  :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
     :outputs [:holds? :evidence-count]}
    {:id :claim/independent-presence
     :version 1
     :category :audit
     :description "Requires at least one evidence node."
     :inputs [:evidence-node]
     :evaluation {:type :code-reference
                  :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
     :outputs [:holds? :evidence-count]}]))

(deftest evaluate-claims-is-reproducible-from-same-inputs
  (let [nodes [(evidence-node "node-a" :pass)]
        requests [{:claim-id :claim/base-pass
                   :evidence-references ["node-a"]}
                  {:claim-id :claim/depends-on-base
                   :evidence-references ["node-a"]}]
        first-run (engine/evaluate-claims requests nodes {:registry base-registry})
        second-run (engine/evaluate-claims requests nodes {:registry base-registry})]
    (is (= first-run second-run))
    (is (:valid? (:validation first-run)))
    (is (= #{:claim/base-pass :claim/depends-on-base}
           (set (map :claim-id (:claim-results first-run)))))))

(deftest evaluate-claims-changes-when-evidence-nodes-change
  (let [requests [{:claim-id :claim/base-pass
                   :evidence-references ["node-a"]}]
        passing (engine/evaluate-claims requests
                                        [(evidence-node "node-a" :pass)]
                                        {:registry base-registry})
        failing (engine/evaluate-claims requests
                                        [(evidence-node "node-a" :fail)]
                                        {:registry base-registry})]
    (is (not= passing failing))
    (is (= :pass (-> passing :claim-results first :status)))
    (is (= :fail (-> failing :claim-results first :status)))))

(deftest validate-claim-results-detects-definition-hash-mismatch
  (let [definition (engine/claim-definition :claim/base-pass base-registry)
        result [{:claim-id :claim/base-pass
                 :claim-definition-hash "wrong-hash"
                 :evidence-references ["node-a"]
                 :depends-on []
                 :holds? true
                 :status :pass}]
        validation (engine/validate-claim-results result
                                                  [(evidence-node "node-a" :pass)]
                                                  {:registry base-registry})]
    (is (false? (:valid? validation)))
    (is (some #(= :claim/definition-hash-mismatch (:error %)) (:errors validation)))
    (is (= (:canonical-hash definition)
           (:expected (first (filter #(= :claim/definition-hash-mismatch (:error %))
                                     (:errors validation))))))))

(deftest validate-claim-results-detects-missing-and-orphaned-evidence
  (let [missing-evidence [{:claim-id :claim/base-pass
                           :claim-definition-hash (:canonical-hash (engine/claim-definition :claim/base-pass base-registry))
                           :evidence-references ["node-missing"]
                           :depends-on []
                           :holds? false
                           :status :fail}]
        orphaned [{:claim-id :claim/independent-presence
                   :claim-definition-hash (:canonical-hash (engine/claim-definition :claim/independent-presence base-registry))
                   :evidence-references []
                   :depends-on []
                   :holds? false
                   :status :fail}]
        missing-validation (engine/validate-claim-results missing-evidence [] {:registry base-registry})
        orphan-validation (engine/validate-claim-results orphaned [] {:registry base-registry})]
    (is (some #(= :claim/missing-evidence (:error %)) (:errors missing-validation)))
    (is (some #(= :claim/orphan (:error %)) (:errors orphan-validation)))))

(deftest validate-claim-results-detects-circular-dependencies
  (let [cyclic-registry (claim-registry
                         [{:id :claim/a
                           :version 1
                           :category :audit
                           :description "A"
                           :inputs [:evidence-node]
                           :depends-on [:claim/b]
                           :evaluation {:type :code-reference
                                        :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
                           :outputs [:holds?]}
                          {:id :claim/b
                           :version 1
                           :category :audit
                           :description "B"
                           :inputs [:evidence-node]
                           :depends-on [:claim/a]
                           :evaluation {:type :code-reference
                                        :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
                           :outputs [:holds?]}])
        node (evidence-node "node-a" :pass)
        results [{:claim-id :claim/a
                  :claim-definition-hash (:canonical-hash (engine/claim-definition :claim/a cyclic-registry))
                  :evidence-references ["node-a"]
                  :depends-on [:claim/b]
                  :holds? true
                  :status :pass}
                 {:claim-id :claim/b
                  :claim-definition-hash (:canonical-hash (engine/claim-definition :claim/b cyclic-registry))
                  :evidence-references ["node-a"]
                  :depends-on [:claim/a]
                  :holds? true
                  :status :pass}]
        validation (engine/validate-claim-results results [node] {:registry cyclic-registry})]
    (is (false? (:valid? validation)))
    (is (some #(= :claim/dependency-cycle (:error %)) (:errors validation)))))
