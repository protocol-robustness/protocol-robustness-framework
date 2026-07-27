(ns resolver-sim.benchmark.research-conclusion-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-conclusion :as rc]))

(def ^:const minimal-conclusion
  {:conclusion/id :conclusion/quota-bounded-write-back
   :conclusion/premise
   {:x "Every participant allocation satisfies its effective quota,
        the deferred residual is conserved, and the successor position
        writes that residual as :position/current-amount."}
   :conclusion/result
   {:y "The quota-bounded partial-fill transition preserves the participant's
        remaining entitlement in authoritative deferred-position state."}
   :conclusion/status :established
   :conclusion/scope {:cases 128
                      :parameter-domain-root "sha256:domain"
                      :model-root "sha256:model"}
   :conclusion/qualifications
   ["No conclusion is made about coalition incentive compatibility."]
   :conclusion/supporting-theorem-hashes
   ["sha256:th1" "sha256:th2"]})

(deftest build-minimal-conclusion
  (let [c (rc/build-conclusion minimal-conclusion)]
    (is (rc/conclusion-valid? c))
    (is (some? (:conclusion/hash c)))
    (is (= :conclusion/quota-bounded-write-back (:conclusion/id c)))
    (is (= :therefore (:conclusion/inference c)))
    (is (= :established (:conclusion/status c)))))

(deftest build-conclusion-defaults-status
  (let [c (rc/build-conclusion
           (dissoc minimal-conclusion :conclusion/status))]
    (is (= :established (:conclusion/status c)))))

(deftest build-conclusion-requires-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/id"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/id)))))

(deftest build-conclusion-requires-premise
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/premise"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/premise)))))

(deftest build-conclusion-requires-result
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/result"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/result)))))

(deftest build-conclusion-rejects-invalid-status
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid :conclusion/status"
                        (rc/build-conclusion (assoc minimal-conclusion :conclusion/status :bogus)))))

(deftest build-conclusion-rejects-hash-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Declared conclusion/hash"
                        (rc/build-conclusion (assoc minimal-conclusion :conclusion/hash "sha256:wrong")))))

(deftest validate-conclusion-valid
  (let [c (rc/build-conclusion minimal-conclusion)
        result (rc/validate-conclusion c)]
    (is (:valid? result))))

(deftest validate-conclusion-rejects-tampered-hash
  (let [c (rc/build-conclusion minimal-conclusion)
        bad (assoc c :conclusion/hash "sha256:fake")
        result (rc/validate-conclusion bad)]
    (is (not (:valid? result)))))

(deftest conclusion-overreaches-without-qualifications
  (let [c (rc/build-conclusion
           (-> minimal-conclusion
               (assoc :conclusion/qualifications [])
               (assoc :conclusion/scope {})))]
    (is (rc/conclusion-overreaches? c))))

(deftest conclusion-does-not-overreach-with-qualifications
  (let [c (rc/build-conclusion minimal-conclusion)]
    (is (not (rc/conclusion-overreaches? c)))))

(deftest conclusion-does-not-overreach-with-scope
  (let [c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/qualifications []
                  :conclusion/scope {:cases 128}))]
    (is (not (rc/conclusion-overreaches? c)))))

(deftest collective-hash-deterministic
  (let [c1 (rc/build-conclusion minimal-conclusion)
        c2 (rc/build-conclusion
            (assoc minimal-conclusion
                   :conclusion/id :conclusion/incentive-compatibility
                   :conclusion/premise {:x "Different premise"}
                   :conclusion/result {:y "Different result"}))
        h1 (rc/conclusion-collective-hash [c1 c2])
        h2 (rc/conclusion-collective-hash [c2 c1])]
    (is (= h1 h2) "collective hash must be order-independent")))

(deftest valid-conclusion-statuses-catalog
  (is (rc/valid-conclusion-status? :established))
  (is (rc/valid-conclusion-status? :qualified))
  (is (rc/valid-conclusion-status? :tentative))
  (is (rc/valid-conclusion-status? :contested))
  (is (rc/valid-conclusion-status? :withdrawn))
  (is (not (rc/valid-conclusion-status? :bogus))))
