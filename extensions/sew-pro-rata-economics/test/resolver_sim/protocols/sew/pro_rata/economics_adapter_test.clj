(ns resolver-sim.protocols.sew.pro-rata.economics-adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.extensions.api.pro-rata.v1 :as pro-rata-api]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as mechanism-evidence]
            [resolver-sim.protocols.sew.pro-rata.economics :as sew-econ]))

(deftest slash-allocation-uses-slashable-stake-as-default-weight
  (testing "Sew adapter maps slashable stake into generic allocation weight"
    (let [result (sew-econ/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :resolver-a
                                     :slashable-stake 300
                                     :available-slashable 300}
                                    {:id :resolver-b
                                     :slashable-stake 100
                                     :available-slashable 100}]})]
      (is (= 400 (:total-basis result)))
      (is (= [75 25] (mapv :paid (:allocations result))))
      (is (= [300 100] (mapv :basis-amount (:allocations result))))
      (is (= [3/4 1/4] (mapv :share (:allocations result))))
      (is (= [300 100] (mapv :cap (:allocations result))))
      (is (true? (pro-rata-api/evidence-valid?
                  (:mechanism/evidence result))))
      (is (= (:evidence/hash (:mechanism/evidence result))
             (get-in result [:mechanism/evidence-reference :evidence/hash]))))))

(deftest slash-allocation-preserves-caps-and-historical-result-shape
  (let [result (sew-econ/calculate-sew-slash-allocation
                {:slash-obligation 100
                 :liable-parties [{:id :resolver-a
                                   :slashable-stake 300
                                   :available-slashable 70}
                                  {:id :resolver-b
                                   :slashable-stake 100
                                   :available-slashable 100}]})]
    (is (= :slashable-stake (:basis result)))
    (is (= :available-slashable (:cap-field result)))
    (is (= 95 (:recovered-total result)))
    (is (= 5 (:unmet-total result)))
    (is (= [70 25] (mapv :paid (:allocations result))))
    (is (= [5 0] (mapv :unmet (:allocations result))))
    (is (= [70 100] (mapv :cap (:allocations result))))))

(deftest slash-projection-artifact-uses-current-allocation-input
  (let [input {:slash-obligation 100
               :liable-parties [{:id :resolver-a
                                 :slashable-stake 300
                                 :available-slashable 70}
                                {:id :resolver-b
                                 :slashable-stake 100
                                 :available-slashable 100}]
               :source {:world-hash "sha256:sew-world"}}
        allocation (sew-econ/calculate-sew-slash-allocation input)
        artifact (sew-econ/build-sew-slash-projection-artifact input)]
    (is (= 95 (:recovered-total allocation)))
    (is (= 100N (get-in artifact [:projection :total-obligation])))
    (is (= {:resolver-a 300N :resolver-b 100N}
           (get-in artifact [:projection :weights])))
    (is (= {:resolver-a 70N :resolver-b 100N}
           (get-in artifact [:projection :caps])))
    (is (= :slashable-stake (get-in artifact [:source :basis])))
    (is (= :available-slashable (get-in artifact [:source :cap-field])))
    (is (= "sha256:sew-world" (get-in artifact [:source :world-hash])))
    (is (nil? (hc/validate-canonical-value! artifact)))
    (is (= (:projection-hash artifact)
           (hc/hash-with-intent {:hash/intent :projection-artifact}
                                (dissoc artifact :projection-hash))))))

(deftest projection-shadow-matches-current-allocation
  (doseq [input [{:slash-obligation 100
                  :slash-policy {:policy/id :test-policy}
                  :liable-parties [{:id :resolver-a
                                    :slashable-stake 300
                                    :available-slashable 300}
                                   {:id :resolver-b
                                    :slashable-stake 100
                                    :available-slashable 100}]}
                 {:slash-obligation 100
                  :liable-parties [{:id :resolver-a
                                    :slashable-stake 300
                                    :available-slashable 70}
                                   {:id :resolver-b
                                    :slashable-stake 100
                                    :available-slashable 100}]}
                 {:slash-obligation 100
                  :liable-parties [{:id :resolver-a
                                    :slashable-stake 0
                                    :available-slashable 70}
                                   {:id :resolver-b
                                    :slashable-stake 0
                                    :available-slashable 100}]}
                 {:slash-obligation 7
                  :basis :custom-weight
                  :cap-field :custom-cap
                  :liable-parties [{:id :resolver-a :custom-weight 1 :custom-cap 10}
                                   {:id :resolver-b :custom-weight 1 :custom-cap 1}
                                   {:id :resolver-c :custom-weight 1 :custom-cap 10}]}]]
    (let [current (sew-econ/calculate-sew-slash-allocation input)
          artifact (sew-econ/build-sew-slash-projection-artifact input)
          shadow (sew-econ/calculate-sew-slash-allocation-from-projection artifact)]
      (testing (pr-str input)
        (is (= (dissoc current :mechanism/evidence :mechanism/evidence-reference)
               shadow))))))

(deftest adapter-preserves-presentation-order-and-binds-canonical-rows
  (let [parties [{:id :resolver-c :slashable-stake 1 :available-slashable 10}
                 {:id :resolver-a :slashable-stake 1 :available-slashable 10}
                 {:id :resolver-b :slashable-stake 1 :available-slashable 10}]
        mechanism (pro-rata/allocate
                   {:allocation/id :sew-conformance
                    :available 4
                    :rows (mapv (fn [party]
                                  {:row/id [:sew-slash-row (:id party)]
                                   :obligation/id (:id party)
                                   :requested 4
                                   :weight (:slashable-stake party)
                                   :cap (:available-slashable party)})
                                parties)})
        presentation (sew-econ/calculate-sew-slash-allocation
                      {:slash-obligation 4 :liable-parties parties})
        by-mechanism (into {} (map (fn [row]
                                     [(second (:row/id row)) (:allocated row)])
                                   (:rows mechanism)))
        by-presentation (into {} (map (juxt :id :paid) (:allocations presentation)))]
    (is (= [[:sew-slash-row :resolver-a]
            [:sew-slash-row :resolver-b]
            [:sew-slash-row :resolver-c]]
           (mapv :row/id (:rows mechanism))))
    (is (= [:resolver-c :resolver-a :resolver-b]
           (mapv :id (:allocations presentation))))
    (is (= by-mechanism by-presentation))
    (is (= (:allocated-total mechanism) (:recovered-total presentation)))
    (is (= (reduce + 0 (map :unmet (:rows mechanism)))
           (:unmet-total presentation)))
    (is (= (get-in presentation [:mechanism/evidence :mechanism/result :allocation/hash])
           (get-in presentation [:mechanism/evidence-reference :allocation/hash])))))
