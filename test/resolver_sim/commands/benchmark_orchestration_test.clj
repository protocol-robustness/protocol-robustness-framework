(ns resolver-sim.commands.benchmark-orchestration-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.benchmark-orchestration :as orchestration]))

(defn- successful-phases [calls]
  (into {}
        (map (fn [phase]
               [phase (fn [& _] (swap! calls conj phase) {:exit-code 0 :evidence {}})])
             orchestration/phases)))

(deftest phase-failure-stops-benchmark-finalization
  (doseq [failed-phase orchestration/phases]
    (let [calls (atom [])
          phases (assoc (successful-phases calls)
                        failed-phase
                        (fn [& _]
                          (swap! calls conj failed-phase)
                          (throw (ex-info "injected benchmark phase failure" {:phase failed-phase}))))]
      (testing (name failed-phase)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"injected benchmark phase failure"
                              (orchestration/run! {:run/id "test"} phases)))
        (let [expected (vec (take-while #(not= % failed-phase) orchestration/phases))]
          (is (= (conj expected failed-phase) @calls)))))))

(deftest successful-benchmark-orchestration-calls-completion-last
  (let [calls (atom [])
        result (orchestration/run! {:run/id "test"} (successful-phases calls))]
    (is (= orchestration/phases @calls))
    (is (= :complete (:phase (last (:phases result)))))
    (is (every? #(= :completed (:status %)) (:phases result)))))
