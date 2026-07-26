(ns resolver-sim.benchmark.research-theorem-outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]))

(def ^:const minimal-theorem
  {:theorem/id :theorem/quota-bounded
   :theorem/type :boundedness
   :theorem/statement
   {:if [:and {:claim :partial-fill-calculated} {:claim :precondition-valid}]
    :then {:claim :allocation-quota-bounded}}
   :theorem/scope {:benchmark/content-root "sha256:content"
                   :model/root "sha256:model"
                   :parameter-domain-root "sha256:domain"
                   :generated-case-set-root "sha256:cases"}
   :theorem/premises [{:premise/id :allocation-valid
                        :status :established
                        :evidence-hash "sha256:ev1"}
                       {:premise/id :state-write-back-valid
                        :status :established
                        :evidence-hash "sha256:ev2"}]
   :theorem/inference {:rule :conjunctive-entailment
                       :policy-root "sha256:policy"}
   :theorem/conclusion {:status :established
                        :claim-id :claim/quota-bounded}
   :theorem/falsifiers [{:falsifier/id :allocation-over-quota
                          :status :not-observed}]})

(deftest build-minimal-theorem
  (let [th (rto/build-theorem-outcome minimal-theorem)]
    (is (rto/theorem-valid? th))
    (is (some? (:theorem/hash th)))
    (is (= :theorem/quota-bounded (:theorem/id th)))
    (is (= :boundedness (:theorem/type th)))
    (is (= :established (get-in th [:theorem/conclusion :status])))))

(deftest build-theorem-with-rationale
  (let [th (rto/build-theorem-outcome
            (assoc minimal-theorem :theorem/rationale "Free-form explanatory prose."))]
    (is (rto/theorem-valid? th))
    (is (= "Free-form explanatory prose." (:theorem/rationale th)))
    (let [without-rationale (dissoc th :theorem/hash :theorem/rationale)]
      (is (not (contains? without-rationale :theorem/rationale))))))

(deftest build-theorem-requires-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :theorem/id"
        (rto/build-theorem-outcome (dissoc minimal-theorem :theorem/id)))))

(deftest build-theorem-requires-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or invalid :theorem/type"
        (rto/build-theorem-outcome (dissoc minimal-theorem :theorem/type)))))

(deftest build-theorem-rejects-invalid-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or invalid :theorem/type"
        (rto/build-theorem-outcome (assoc minimal-theorem :theorem/type :bogus)))))

(deftest build-theorem-requires-statement
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :theorem/statement"
        (rto/build-theorem-outcome (dissoc minimal-theorem :theorem/statement)))))

(deftest build-theorem-rejects-hash-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Declared theorem/hash"
        (rto/build-theorem-outcome (assoc minimal-theorem :theorem/hash "sha256:wrong")))))

(deftest validate-theorem-outcome-valid
  (let [th (rto/build-theorem-outcome minimal-theorem)
        result (rto/validate-theorem-outcome th)]
    (is (:valid? result))))

(deftest validate-theorem-outcome-rejects-tampered-hash
  (let [th (rto/build-theorem-outcome minimal-theorem)
        bad (assoc th :theorem/hash "sha256:fake")
        result (rto/validate-theorem-outcome bad)]
    (is (not (:valid? result)))))

(deftest validate-theorem-outcome-rejects-wrong-schema
  (let [th (rto/build-theorem-outcome minimal-theorem)
        bad (assoc th :schema-version "wrong.v1")
        result (rto/validate-theorem-outcome bad)]
    (is (not (:valid? result)))))

(deftest theorem-references-extracts-evidence-hashes
  (let [th (rto/build-theorem-outcome minimal-theorem)
        refs (rto/theorem-references th)]
    (is (= #{"sha256:ev1" "sha256:ev2"} refs))))

(deftest collective-hash-deterministic
  (let [t1 (rto/build-theorem-outcome minimal-theorem)
        t2 (rto/build-theorem-outcome
            (assoc minimal-theorem
                   :theorem/id :theorem/incentive-compatibility
                   :theorem/type :incentive-compatibility
                   :theorem/statement
                   {:if {:claim :valid-strategy-profile}
                    :then {:claim :no-unilateral-profitable-deviation}}))
        h1 (rto/theorem-outcome-collective-hash [t1 t2])
        h2 (rto/theorem-outcome-collective-hash [t2 t1])]
    (is (= h1 h2) "collective hash must be order-independent")))

(deftest theorem-outcome-with-limitations
  (let [th (rto/build-theorem-outcome
            (assoc minimal-theorem
                   :theorem/limitations [:finite-generated-case-set
                                          :coalitions-not-evaluated]))]
    (is (rto/theorem-valid? th))
    (is (= 2 (count (:theorem/limitations th))))))

(deftest valid-theorem-types-catalog
  (is (rto/valid-theorem-type? :state-transition))
  (is (rto/valid-theorem-type? :incentive-compatibility))
  (is (not (rto/valid-theorem-type? :bogus))))

(deftest valid-theorem-statuses-catalog
  (is (rto/valid-theorem-status? :established))
  (is (rto/valid-theorem-status? :supported-within-domain))
  (is (not (rto/valid-theorem-status? :bogus))))

(deftest build-theorem-supports-all-types
  (doseq [t rto/valid-theorem-types]
    (let [th (rto/build-theorem-outcome
              (assoc minimal-theorem :theorem/type t))]
      (is (rto/theorem-valid? th) (str "type " t " should be valid")))))
