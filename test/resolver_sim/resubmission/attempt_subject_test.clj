(ns resolver-sim.resubmission.attempt-subject-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.resubmission.attempt-subject :as subject]
            [resolver-sim.resubmission.issuance :as issuance]
            [resolver-sim.resubmission.receipt :as receipt]))

(def e "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def b "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def t {:target/type :prf/resubmission :target/id "destination-1"})

(deftest subject-is-closed-rooted-and-sensitive
  (let [s (subject/build e b t)]
    (is (subject/valid? s))
    (is (not (subject/valid? (assoc s :reservation/id "r"))))
    (is (not= (:attempt/subject-root s)
              (:attempt/subject-root (subject/build e
                                                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                                                    t))))
    (is (not= (:attempt/subject-root s)
              (:attempt/subject-root (subject/build e b
                                                    (assoc t :target/id "destination-2")))))))

(deftest receipt-subject-binding-is-explicit
  (let [s (subject/build e b t)
        receipt {:attempt-receipt/attempt-subject-root (:attempt/subject-root s)}]
    (is (= (:attempt/subject-root s)
           (:attempt-receipt/attempt-subject-root
            (issuance/bind-attempt-subject {} s))))
    (is (issuance/receipt-binds-attempt-subject? receipt s))
    (is (receipt/receipt-binds-attempt-subject? receipt s))
    (is (not (receipt/receipt-binds-attempt-subject?
              (assoc receipt :attempt-receipt/attempt-subject-root "sha256:bad") s)))))
