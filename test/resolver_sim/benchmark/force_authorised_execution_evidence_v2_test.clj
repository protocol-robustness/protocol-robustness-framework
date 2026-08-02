(ns resolver-sim.benchmark.force-authorised-execution-evidence-v2-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.force-authorised-execution-evidence :as evidence]
            [resolver-sim.assurance.authorised-effect-correlation :as correlation]))

(defn- hash-ref [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(defn- corr [suffix]
  (correlation/build-correlation
   {:protocol/id :sew :research-assignment/hash (hash-ref [:assignment suffix])
    :researcher-force-authorisation/hash (hash-ref [:auth suffix])
    :reservation/hash (hash-ref [:reservation suffix])
    :reservation/execution-attempt-id :attempt/v2
    :public-authorisation/id "fa-0" :public-authorisation/scope-hash (hash-ref [:scope suffix])
    :effect/type :held-adjustment :effect/id "held-0" :effect/artifact-hash (hash-ref [:artifact suffix])}))

(defn- receipt [status outcome correlation]
  (rfa/build-consumption-receipt-v2
   (cond-> {:consumption/reservation-hash (hash-ref :reservation)
            :consumption/authorisation-hash (hash-ref :auth)
            :consumption/consumption-key (hash-ref :key)
            :consumption/resulting-outcome-hash (hash-ref :outcome)
            :consumption/terminal-evidence-hash (hash-ref :terminal)
            :consumption/status status :consumption/effect-outcome outcome}
     correlation (assoc :correlation correlation))))

(defn- v1-profile []
  {:schema-version "force-authorised-execution-evidence.v1"
   :evidence-profile/id :evidence-profile/force-authorised-execution
   :evidence-profile/policy-hash (hash-ref :policy)
   :evidence-profile/review-round-hash (hash-ref :round)
   :evidence-profile/authorisation-hash (hash-ref :auth)
   :evidence-profile/reservation-hash (hash-ref :reservation)
   :evidence-profile/outcome-manifest-hash (hash-ref :manifest)
   :evidence-profile/consumption-receipt-hash (hash-ref :receipt)
   :evidence-profile/executed-content-root (hash-ref :content)
   :evidence-profile/execution-result {:terminal-status :consumed}
   :evidence-profile/verification {}})

(defn- build-v2 [receipt correlation]
  (with-redefs [evidence/build-force-authorised-execution-evidence (fn [_] (v1-profile))]
    (evidence/build-force-authorised-execution-evidence-v2
     {:consumption-receipt receipt :correlation correlation
      :authorisation {} :policy {} :review-round {} :reservation {}
      :outcome-manifest {} :public-key-resolver identity})))

(deftest execution-evidence-v2-is-status-aware
  (let [a (corr :a)
        produced (build-v2 (receipt :consumed :produced a) a)
        reversed (build-v2 (receipt :rolled-back-after-consumption :reversed a) a)
        no-effect (build-v2 (receipt :failed-after-consumption :not-produced nil) nil)]
    (is (= (:correlation/hash a) (:execution/effect-correlation-hash produced)))
    (is (= (:correlation/hash a) (:execution/effect-correlation-hash reversed)))
    (is (not (contains? no-effect :execution/effect-correlation-hash)))
    (is (:valid? (evidence/validate-force-authorised-execution-evidence-v2 produced)))))

(deftest execution-evidence-v2-rejects-invalid-correlation-combinations
  (let [a (corr :a) b (corr :b)
        produced (receipt :consumed :produced a)
        no-effect (receipt :failed-after-consumption :not-produced nil)
        v1 (rfa/build-consumption-receipt {:consumption/reservation-hash (hash-ref :reservation)
                                           :consumption/authorisation-hash (hash-ref :auth)
                                           :consumption/consumption-key (hash-ref :key)
                                           :consumption/resulting-outcome-hash (hash-ref :outcome)
                                           :consumption/status :consumed})]
    (is (thrown? clojure.lang.ExceptionInfo (build-v2 produced b)))
    (is (thrown? clojure.lang.ExceptionInfo (build-v2 no-effect a)))
    (is (thrown? clojure.lang.ExceptionInfo (build-v2 v1 nil)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (build-v2 (dissoc produced :consumption/effect-correlation-hash) a)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (build-v2 (assoc produced :consumption/hash "sha256:tampered") a)))))

(deftest execution-evidence-v2-hash-and-version-boundaries
  (let [a (corr :a)
        v2 (build-v2 (receipt :consumed :produced a) a)
        v1-base (v1-profile)
        v1 (assoc v1-base :evidence-profile/hash
                  (str "sha256:" (hc/domain-hash :force-authorised-execution-evidence v1-base)))
        appended (assoc v1 :execution/effect-correlation-hash (:correlation/hash a))]
    (is (not= (:evidence-profile/hash v1) (:evidence-profile/hash v2)))
    (is (false? (:valid? (evidence/validate-force-authorised-execution-evidence-any appended))))
    (is (contains? (set (:errors (evidence/validate-force-authorised-execution-evidence-any appended)))
                   :v2-field-on-v1))
    (is (false? (:valid? (evidence/validate-force-authorised-execution-evidence-v2
                          (dissoc v2 :execution/effect-correlation-hash)))))
    (is (false? (:valid? (evidence/validate-force-authorised-execution-evidence-v2
                          (assoc v2 :execution/effect-correlation-hash (hash-ref :other))))))))
