(ns resolver-sim.benchmark.force-authorisation-consumption-v2-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.assurance.authorised-effect-correlation :as correlation]))

(defn- hash-ref [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(defn- effect-correlation []
  (correlation/build-correlation
   {:protocol/id :sew
    :research-assignment/hash (hash-ref :assignment)
    :researcher-force-authorisation/hash (hash-ref :authorisation)
    :reservation/hash (hash-ref :reservation)
    :reservation/execution-attempt-id :attempt/v2
    :public-authorisation/id "fa-0"
    :public-authorisation/scope-hash (hash-ref :scope)
    :effect/type :held-adjustment :effect/id "held-0"
    :effect/artifact-hash (hash-ref :artifact)}))

(defn- receipt-fields [status]
  {:consumption/reservation-hash (hash-ref :reservation)
   :consumption/authorisation-hash (hash-ref :authorisation)
   :consumption/consumption-key (hash-ref :key)
   :consumption/resulting-outcome-hash (hash-ref :outcome)
   :consumption/terminal-evidence-hash (hash-ref :terminal)
   :consumption/status status})

(deftest v2-receipt-requires-status-appropriate-correlation
  (let [corr (effect-correlation)
        consumed (rfa/build-consumption-receipt-v2
                  (assoc (receipt-fields :consumed) :correlation corr
                         :consumption/effect-outcome :produced))
        reversed (rfa/build-consumption-receipt-v2
                  (assoc (receipt-fields :rolled-back-after-consumption) :correlation corr
                         :consumption/effect-outcome :reversed))
        no-effect (rfa/build-consumption-receipt-v2
                   (assoc (receipt-fields :failed-after-consumption)
                          :consumption/effect-outcome :not-produced))]
    (is (:valid? (rfa/validate-consumption-receipt consumed)))
    (is (:valid? (rfa/validate-consumption-receipt reversed)))
    (is (:valid? (rfa/validate-consumption-receipt no-effect)))
    (is (= (:correlation/hash corr) (:consumption/effect-correlation-hash consumed)))
    (is (nil? (:consumption/effect-correlation-hash no-effect)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rfa/build-consumption-receipt-v2
                  (assoc (receipt-fields :consumed) :consumption/effect-outcome :produced))))))

(deftest receipt-v2-enforces-the-complete-status-effect-truth-table
  (let [corr (effect-correlation)
        build (fn [status outcome correlation]
                (rfa/build-consumption-receipt-v2
                 (cond-> (assoc (receipt-fields status) :consumption/effect-outcome outcome)
                   correlation (assoc :correlation correlation))))]
    (is (:valid? (rfa/validate-consumption-receipt
                  (build :failed-after-consumption :produced corr))))
    (let [no-effect (build :failed-after-consumption :not-produced nil)]
      (is (:valid? (rfa/validate-consumption-receipt no-effect)))
      (is (not (contains? no-effect :consumption/effect-correlation-hash))))
    (doseq [[status outcome] [[:consumed :not-produced]
                              [:consumed :reversed]
                              [:failed-after-consumption :reversed]
                              [:rolled-back-after-consumption :produced]
                              [:rolled-back-after-consumption :not-produced]
                              [:unknown :produced]]]
      (is (thrown? clojure.lang.ExceptionInfo (build status outcome corr))
          (str "rejects " status "/" outcome)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rfa/build-consumption-receipt-v2
                  (dissoc (assoc (receipt-fields :failed-after-consumption)
                                 :correlation corr :consumption/effect-outcome :produced)
                          :consumption/terminal-evidence-hash))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rfa/build-consumption-receipt-v2
                  (dissoc (assoc (receipt-fields :failed-after-consumption)
                                 :consumption/effect-outcome :not-produced)
                          :consumption/terminal-evidence-hash))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rfa/build-consumption-receipt-v2
                  (dissoc (assoc (receipt-fields :rolled-back-after-consumption)
                                 :correlation corr :consumption/effect-outcome :reversed)
                          :consumption/terminal-evidence-hash))))))

(deftest v1-and-v2-receipts-have-separated-boundaries
  (let [v1 (rfa/build-consumption-receipt (receipt-fields :consumed))
        v2 (rfa/build-consumption-receipt-v2
            (assoc (receipt-fields :consumed) :correlation (effect-correlation)
                   :consumption/effect-outcome :produced))]
    (is (not= (:consumption/hash v1) (:consumption/hash v2)))
    (is (false? (:valid? (rfa/validate-consumption-receipt
                          (assoc v1 :consumption/effect-outcome :produced)))))
    (is (false? (:valid? (rfa/validate-consumption-receipt
                          (assoc v1 :consumption/effect-correlation-hash (hash-ref :correlation))))))
    (is (false? (:valid? (rfa/validate-consumption-receipt
                          (assoc v1 :consumption/status :rolled-back-after-consumption)))))))
