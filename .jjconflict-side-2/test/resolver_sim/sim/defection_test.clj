(ns resolver-sim.sim.defection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.defection :as d]
            [resolver-sim.stochastic.rng :as rng]))

(deftest apply-strategy-defection-disabled-when-rate-zero
  (testing ":defection-rate 0 leaves histories unchanged"
    (let [rng (rng/make-rng 1)
          h {"r1" {:strategy :lazy :epoch-history {:epoch-1 {:trials 1 :profit 1.0}}}}
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection rng h 1 {:defection-rate 0})]
      (is (= h updated-histories))
      (is (empty? defection-events))
      (is (empty? diagnostics)))))

(deftest repeated-grim-trigger-first-epoch-cooperates
  (testing "grim-trigger stays honest in first epoch"
    (let [rng (rng/make-rng 1)
          resolver {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 1.0}}}
          cfg {:allowed-targets #{:honest :malicious}
               :strategy-space #{:honest :malicious}}
          result (d/select-next-strategy :repeated/grim-trigger
                                         {:cfg cfg :epoch 1 :previous-epoch-strategies []}
                                         resolver)]
      (is (:skip? result))
      (is (= :honest (:to result))))))

(deftest repeated-grim-trigger-defects-on-defection
  (testing "grim-trigger switches to malicious after detecting defection"
    (let [rng (rng/make-rng 1)
          resolver {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 100.0}}}
          cfg {:allowed-targets #{:honest :malicious :lazy}
               :strategy-space #{:honest :malicious :lazy}
               :slash-risk-inhibition 0.0}
          result (d/select-next-strategy :repeated/grim-trigger
                                         {:cfg cfg :epoch 2
                                          :previous-epoch-strategies [:malicious :malicious :honest]}
                                         resolver)]
      (is (= :malicious (:to result)))
      (is (= :grim-trigger-activated (:reason result))))))

(deftest repeated-tit-for-tat-cooperates-first-epoch
  (testing "tit-for-tat stays honest in first epoch"
    (let [rng (rng/make-rng 1)
          resolver {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 1.0}}}
          cfg {:allowed-targets #{:honest :malicious}}
          result (d/select-next-strategy :repeated/tit-for-tat
                                         {:cfg cfg :epoch 1 :previous-epoch-strategies []}
                                         resolver)]
      (is (:skip? result)))))

(deftest repeated-tit-for-tat-retaliates
  (testing "tit-for-tat switches to malicious when opponents defected"
    (let [rng (rng/make-rng 1)
          resolver {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 100.0}}}
          cfg {:allowed-targets #{:honest :malicious :lazy}
               :strategy-space #{:honest :malicious :lazy}}
          result (d/select-next-strategy :repeated/tit-for-tat
                                         {:cfg cfg :epoch 2
                                          :previous-epoch-strategies [:malicious :malicious :lazy]}
                                         resolver)]
      (is (= :malicious (:to result)))
      (is (= :tit-for-tat-retaliation (:reason result))))))

(deftest repeated-tit-for-tat-returns-to-cooperation
  (testing "tit-for-tat returns to honest when opponents cooperate"
    (let [rng (rng/make-rng 1)
          resolver {:strategy :malicious :epoch-history {:epoch-1 {:trials 1 :profit 50.0}}}
          cfg {:allowed-targets #{:honest :malicious :lazy}
               :strategy-space #{:honest :malicious :lazy}}
          result (d/select-next-strategy :repeated/tit-for-tat
                                         {:cfg cfg :epoch 2
                                          :previous-epoch-strategies [:honest :honest :honest]}
                                         resolver)]
      (is (= :honest (:to result)))
      (is (= :tit-for-tat-return (:reason result))))))

(deftest legacy-binary-mode-keeps-compatibility
  (testing "legacy mode keeps binary honest/malicious behavior"
    (let [rng (rng/make-rng 42)
          histories {"lazy1" {:strategy :lazy
                              :epoch-history {:epoch-1 {:trials 10 :profit 1.0}}}
                     "honest1" {:strategy :honest
                                :epoch-history {:epoch-1 {:trials 10 :profit 100.0}}}}
          {:keys [updated-histories defection-events]}
          (d/apply-strategy-defection rng histories 1 {:defection-rate 1.0})
          lazy-event (first (filter #(= "lazy1" (:id %)) defection-events))]
      (when lazy-event
        (is (= :honest (:to lazy-event)))
        (is (= :lazy (:from lazy-event))))
      (is (= :honest (get-in updated-histories ["lazy1" :strategy]))))))
