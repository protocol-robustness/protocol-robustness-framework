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

(deftest renderer-projects-supported-repair-context-and-omits-unknown-fields
  (let [option {:id "repair-2"
                :snapshot-root "snapshot-root-2"
                :source-revision "rev-a"
                :target-revision "rev-b"
                :proposed-operation :restore
                :preconditions [:all-signatures-verified]
                :recoverable-state {:state/root "state-1"}
                :missing-evidence [:independent-review]
                :history-impact :append-only
                :reversibility :compensating-action
                :limitations ["simulation only"]
                :evidence-integrity :verified
                :repair-feasibility :feasible
                :authorization :approved
                :execution :not-executed
                :independent-review :pending
                :publication :not-authorized
                :unrecognized-private-field "must not escape"}
        section (report/build-section {:snapshot-root "snapshot-root-2"
                                       :options [option]})
        rendered (first (:options section))]
    (is (= {:id "repair-2"
            :snapshot-root "snapshot-root-2"
            :source-revision "rev-a"
            :target-revision "rev-b"
            :proposed-operation :restore
            :preconditions [:all-signatures-verified]
            :recoverable-state {:state/root "state-1"}
            :missing-evidence [:independent-review]
            :history-impact :append-only
            :reversibility :compensating-action
            :limitations ["simulation only"]}
           (:details rendered)))
    (is (= #{:evidence-integrity :repair-feasibility :authorization
             :execution :independent-review :publication}
           (set (keys (:claimed-dimensions rendered)))))
    (is (every? #(= :unverified (:status %))
                (vals (:claimed-dimensions rendered))))
    (is (= :unverified (:assertion/status rendered)))
    (is (not (some #(= "must not escape" %) (tree-seq coll? seq section))))))

(deftest invalid-inputs-are-rejected
  (is (thrown? clojure.lang.ExceptionInfo (report/build-section nil)))
  (is (thrown? clojure.lang.ExceptionInfo (report/build-section {:options [42]}))))
