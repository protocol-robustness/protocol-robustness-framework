(ns resolver-sim.protocols.sew.transition-assurance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.frames :as frames]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.scenarios :as scenarios]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.transition-assurance :as assurance]
            [resolver-sim.scenario.normalize :as normalize]))

(deftest transition-assurance-commits-ratios-exactly
  (let [before {:balance 1/3}
        after {:balance 2/3}
        basis (assurance/build before after {:type :ratio-transition})
        expected-after-root
        (ref/sha256-ref
         (canonical/domain-hash
          :sew-dispute-transition-state-v1
          {:balance {:canonical/type "ratio"
                     :canonical/numerator 2
                     :canonical/denominator 3}}))
        changed-basis (assurance/build before {:balance 3/4}
                                       {:type :ratio-transition})]
    (is (= expected-after-root (:state-after/root basis)))
    (is (= basis (assurance/build before after {:type :ratio-transition})))
    (is (not= (:state-after/root basis) (:state-after/root changed-basis)))))

(deftest sew-appeal-replay-produces-anchored-assured-frames
  (let [scenario (-> "scenarios/edn/S-DR-030-biased-resolver-appealed.edn"
                     scenarios/load-scenario-file
                     normalize/normalize-scenario
                     (assoc :replay/requirements [:transition-assurance/v1]))
        result (replay/replay-events sew/protocol scenario
                                     {:flags {:evidence-mode :none
                                              :temporal-enabled? false}})
        stream (:accepted-frames result)
        first-frame (first (:frames stream))
        last-frame (last (:frames stream))
        anchors {:expected-state-before-root (:state-before/root first-frame)
                 :expected-head-frame-root (:frame/root last-frame)
                 :expected-final-state-root (:state-after/root last-frame)}]
    (is (= :pass (:outcome result)))
    (is (= :ok (:frame/status stream)))
    (is (seq (:frames stream)))
    (is (:valid? (frames/validate-frame-lineage stream anchors))
        (pr-str (frames/validate-frame-lineage stream anchors)))
    (is (not (:valid? (frames/validate-frame-lineage
                       (assoc-in stream [:frames 0 :frame/root] "tampered") anchors))))
    (is (every? #(= :passed (get-in % [:transition-assurance :status]))
                (filter frames/frame-eligible? (:trace result))))))

(deftest rejected-appeal-attempts-remain-diagnostics-not-frames
  (let [scenario (-> "scenarios/edn/S74_appeal-deadline-boundary.edn"
                     scenarios/load-scenario-file
                     normalize/normalize-scenario
                     (assoc :replay/requirements [:transition-assurance/v1]))
        result (replay/replay-events sew/protocol scenario
                                     {:flags {:evidence-mode :none
                                              :temporal-enabled? false}})
        rejected (filter #(not= :ok (:result %)) (:trace result))
        accepted (filter frames/frame-eligible? (:trace result))
        stream (:accepted-frames result)]
    (is (= :pass (:outcome result)))
    (is (seq rejected))
    (is (= (count accepted) (count (:frames stream))))
    (is (every? #(not (frames/frame-eligible? %)) rejected))
    (is (:valid? (frames/validate-frame-lineage stream)))))
