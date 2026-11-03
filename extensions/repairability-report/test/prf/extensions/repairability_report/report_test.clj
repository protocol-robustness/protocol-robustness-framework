(ns prf.extensions.repairability-report.report-test
  (:require [clojure.test :refer [deftest is testing]]
            [prf.extensions.repairability-report.report :as report]))

(def input
  {:snapshot-root "snapshot-root-1"
   :options [{:id :repair/one
              :snapshot-root "snapshot-root-1"
              :proposed-operation :restore
              :authorization :authorized
              :repair-feasibility :supported
              :private-producer-field "must not be projected"}]
   :verification {:schema :repair-verification.v1
                  :status :verified
                  :signature "untrusted producer field"}})

(deftest section-is-deterministic-and-keeps-claims-unverified
  (let [section (report/build-section input)]
    (is (= section (report/build-section input)))
    (is (= :prf/repairability-report-section.v1 (:schema section)))
    (is (= :unverified-inputs (:qualification section)))
    (is (= :unverified (get-in section [:options 0 :assertion/status])))
    (is (= :authorized
           (get-in section [:options 0 :claimed-dimensions :authorization :claimed])))
    (is (= :unverified
           (get-in section [:options 0 :claimed-dimensions :authorization :status])))
    (is (not (contains? (get-in section [:options 0 :details]) :private-producer-field)))
    (is (= :verified (get-in section [:verification-assertion :claimed-status])))
    (is (= :unverified (:status (:verification-assertion section))))
    (is (some #(.contains ^String % "repair-authorization-not-granted-by-report")
              (:limitations section)))))

(deftest verifier-assertion-cannot-upgrade-report-qualification
  (testing "claimed pass and fail statuses have the same non-authoritative qualification"
    (let [claimed-pass (report/build-section (assoc input :verification {:status :passed}))
          claimed-fail (report/build-section (assoc input :verification {:status :failed}))]
      (is (= :unverified-inputs (:qualification claimed-pass)))
      (is (= :unverified-inputs (:qualification claimed-fail)))
      (is (not= (get-in claimed-pass [:verification-assertion :claimed-status])
                (get-in claimed-fail [:verification-assertion :claimed-status]))))))

(deftest invalid-inputs-are-rejected
  (is (thrown? clojure.lang.ExceptionInfo (report/build-section nil)))
  (is (thrown? clojure.lang.ExceptionInfo (report/build-section {:options [42]}))))
