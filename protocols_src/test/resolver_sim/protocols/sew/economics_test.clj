(ns resolver-sim.protocols.sew.economics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
                        [resolver-sim.protocols.sew.economics :as sew-econ]
                        [resolver-sim.pro-rata.allocation :as pro-rata]
                        [resolver-sim.pro-rata.evidence :as mechanism-evidence]))

(deftest sew-economic-policy-helpers
  (testing "SEW-specific fees, bonds, slashes, and escrow caps live in the Sew adapter"
    (is (= 15 (sew-econ/calculate-escrow-fee 1000 150)))
    (is (= {:fee 10 :net 990}
           (sew-econ/calculate-appeal-bond-fee 1000 100)))
    (is (= 25
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 250 :appeal-bond-amount 50})))
    (is (= 50
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 0 :appeal-bond-amount 50})))
    (is (= 30
           (sew-econ/calculate-appeal-bond-amount
            1000 {:appeal-bond-bps 300})))
    (is (= 20 (sew-econ/calculate-bounty 1000 200)))
    (is (= 25 (sew-econ/calculate-slash-amount-from-basis 1000 250)))
    (is (= 25 (sew-econ/calculate-reversal-slash 1000 250)))
    (is (= 1500.0 (sew-econ/calculate-escrow-cap 1000 1.5)))))

(deftest sew-slashing-distribution
  (testing "SEW slash distribution keeps the historical default split"
    (is (= {:insurance 500 :protocol 300 :retained 200}
           (sew-econ/calculate-slashing-distribution 1000 0)))
    (is (= 990
           (let [{:keys [insurance protocol retained]}
                 (sew-econ/calculate-slashing-distribution 1000 10)]
             (+ insurance protocol retained))))))

(deftest sew-slash-allocation-uses-slashable-stake-as-default-weight
  (testing "SEW adapter maps slashable stake into generic allocation weight"
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
      (is (empty? (mechanism-evidence/evidence-violations
                   (:mechanism/evidence result))))
      (is (= (:evidence/hash (:mechanism/evidence result))
             (get-in result [:mechanism/evidence-reference :evidence/hash]))))))

(deftest sew-slash-allocation-preserves-caps-and-legacy-shape
  (testing "SEW adapter applies available-slashable caps and returns historical keys"
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
      (is (= [70 100] (mapv :cap (:allocations result)))))))

(deftest sew-slash-projection-artifact-uses-current-allocation-input
  (testing "SEW sidecar projection artifact comes from the same parties, basis, caps, and amount"
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
                                  (dissoc artifact :projection-hash)))))))

(deftest sew-slash-allocation-from-projection-shadows-current-path
  (testing "projection-based SEW allocation matches the current SEW allocation on the same fixtures"
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
                    :liable-parties [{:id :resolver-a
                                      :custom-weight 1
                                      :custom-cap 10}
                                     {:id :resolver-b
                                      :custom-weight 1
                                      :custom-cap 1}
                                     {:id :resolver-c
                                      :custom-weight 1
                                      :custom-cap 10}]}]]
      (let [current (sew-econ/calculate-sew-slash-allocation input)
            artifact (sew-econ/build-sew-slash-projection-artifact input)
            from-projection (sew-econ/calculate-sew-slash-allocation-from-projection artifact)]
        ;; Projection remains a legacy presentation artifact and intentionally
        ;; does not reconstruct the complete mechanism evidence envelope.
        (is (= (dissoc current :mechanism/evidence :mechanism/evidence-reference)
               from-projection))))))

(deftest sew-adapter-preserves-presentation-while-binding-canonical-mechanism-rows
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
    ;; Mechanism order is canonical, while the public SEW presentation retains
    ;; the supplied liable-party order.
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

(deftest sew-resolution-call-site-uses-sew-economics-adapter
  (testing "resolution query path does not call the deprecated payoffs slash wrapper directly"
    (let [source (slurp "protocols_src/resolver_sim/protocols/sew/resolution.clj")]
      (is (str/includes? source "sew-econ/calculate-sew-slash-allocation"))
      (is (not (str/includes? source "payoffs/calculate-prorata-slash-allocation"))))))
