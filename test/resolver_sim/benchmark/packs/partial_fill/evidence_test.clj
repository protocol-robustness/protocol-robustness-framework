(ns resolver-sim.benchmark.packs.partial-fill.evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.evidence :as pfev]
            [resolver-sim.hash.canonical :as hc]))

(def sample-final-world
  {:yield/withdrawn {:USDC {"alice" 15}}
   :yield/positions {"alice"
                     {:principal 100
                      :deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}
                      :cumulative-fulfilled 15}}})

(def final-world-pos-hash
  (str "sha256:" (hc/domain-hash :state-projection
                                 (get-in sample-final-world [:yield/positions "alice"]))))

(def sample-application
  {:propagation-id "prop-1"
   :participants
   [{:participant-id "alice"
     :position-before {:deferred-position
                       {:position/id "alice/deferred/1"
                        :position/current-amount 20
                        :position/status :active}}
     :position-before-hash "sha256:before"
     :position-after {:deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}}
     :position-after-hash final-world-pos-hash
     :withdrawn {:token :USDC :before 10 :delta 5 :after 15}
     :obligation {:before 20 :fulfilled 5 :deferred 15 :after 15}
     :cumulative-fulfilled {:before 10 :delta 5 :after 15}}]})

(deftest derive-state-write-back-verified
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)]
    (is (seq wb))
    (let [alice (first wb)]
      (is (= "alice" (:participant/id alice)))
      (is (true? (get-in alice [:withdrawn :verified?])))
      (is (true? (get-in alice [:position :verified?])))
      (is (true? (get-in alice [:deferred-position :verified?]))))))

(deftest derive-state-write-back-values
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)
        alice (first wb)]
    (is (= 10 (get-in alice [:withdrawn :before])))
    (is (= 5 (get-in alice [:withdrawn :delta])))
    (is (= 15 (get-in alice [:withdrawn :after])))
    (is (= 15 (get-in alice [:withdrawn :final-world-value])))
    (is (= 15 (get-in alice [:deferred-position :successor-current-amount])))
    (is (= 15 (get-in alice [:deferred-position :final-world-current-amount])))))

(deftest derive-state-write-back-mismatch-detected
  (let [bad-world (assoc-in sample-final-world
                            [:yield/withdrawn :USDC "alice"] 99)
        wb (pfev/derive-state-write-back sample-application bad-world)
        alice (first wb)]
    (is (false? (get-in alice [:withdrawn :verified?])))))

(deftest derive-state-write-back-nil-for-no-participants
  (let [app {:participants []}
        wb (pfev/derive-state-write-back app {})]
    (is (nil? wb))))

(deftest collect-application-refs
  (let [world {:yield/applied-pro-rata-propagations
               {"prop-1" {:propagation-id "prop-1"
                          :application/hash "sha256:app-hash"
                          :calculation-id "calc-1"
                          :outcome-hash "sha256:outcome"
                          :application-order {:step 1 :event-id 0}}}}]
    (is (= 1 (count (pfev/collect-application-refs world))))
    (is (= "prop-1" (:propagation/id (first (pfev/collect-application-refs world)))))))

(deftest semantic-commitments-empty
  (is (nil? (pfev/semantic-commitments {}))))

(deftest semantic-commitments-with-decisions
  (let [world {:yield/partial-fill-decisions
               {"d1" {:decision/id "d1" :decision/hash "sha256:d1"}}}]
    (let [commitments (pfev/semantic-commitments world)]
      (is (some? commitments))
      (is (map? (:semantic/economic-application commitments)))
      (is (some? (get-in commitments [:semantic/economic-application
                                      :partial-fill-decisions-root]))))))

(deftest application-evidence-ladder-basic
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)]
    (is (= 1 (count ladder)))
    (let [entry (first ladder)]
      (is (= "prop-1" (:propagation/id entry)))
      (is (= 6 (count (:levels entry)))))))

(deftest application-ladder-next-precondition-not-observed
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        continuity (nth (:levels entry) 4)]
    (is (= :continuity-consumed (:level continuity)))
    (is (= "not-observed" (:status continuity)))))

(deftest application-evidence-ladder-levels-verified
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        levels (:levels entry)]
    (is (= "verified" (:status (nth levels 0))))
    (is (= "verified" (:status (nth levels 1))))
    (is (= "verified" (:status (nth levels 2))))
    (is (= "not-observed" (:status (nth levels 3))))
    (is (= "not-observed" (:status (nth levels 4))))
    (is (= "not-observed" (:status (nth levels 5))))))
