(ns resolver-sim.benchmark.three-member-authority-report-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.three-member-authority-report :as sut]))

(defn- root [ch] (str "sha256:" (apply str (repeat 64 ch))))
(def request-root (root "a"))
(def round-root (root "b"))
(def outcome-root (root "c"))
(def signature "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- decision [member outcome]
  (with-redefs [signing/sign-hash (fn [_ _ _] signature)]
    (rfa/build-signed-decision-v2 member :authority/test request-root round-root
                                  outcome :approve "/dev/null")))

(defn- evaluator-report [positions]
  (authority/evaluate-three-member-authority
   :authorisation {:authorisation/id :authority/test
                   :authorisation/request-root request-root
                   :authorisation/review-round {:review-round/id round-root
                                                :review-round/hash round-root}
                   :authorisation/target {:target/kind :benchmark-branch
                                          :target/baseline-content-root (root "d")
                                          :target/branch-descriptor-hash (root "e")
                                          :target/proposed-content-root outcome-root}
                   :authorisation/decision-references positions}
   :review-round {:review-round/members [{:researcher/id "a"}
                                         {:researcher/id "b"}
                                         {:researcher/id "c"}]}
   :signature-valid? (constantly true)))

(defn- closed-report []
  (sut/build-report (evaluator-report [(decision "a" outcome-root)
                                       (decision "b" outcome-root)
                                       (decision "c" outcome-root)])))

(deftest evaluator-projection-is-closed-and-stable
  (let [report (closed-report)]
    (is (sut/verify-report report))
    (is (= :authorised (:authority-status report)))
    (is (= (:three-member-authority-report/root report)
           (:three-member-authority-report/root (sut/build-report report))))
    (is (= "three-member-authority-report-position.v2"
           (get-in report [:valid-supporting-positions 0 :position/schema])))))

(deftest closed-schema-and-root-sensitivity
  (let [report (closed-report)
        reseal #(assoc % :three-member-authority-report/root (sut/report-root %))]
    (testing "unknown fields cannot enter either layer"
      (is (false? (sut/verify-report (assoc report :unknown true))))
      (is (false? (sut/verify-report
                   (reseal (assoc-in report [:valid-supporting-positions 0 :unknown] true))))))
    (testing "declared semantic fields bind the root"
      (let [changed (reseal (assoc report :required-threshold 3))]
        (is (sut/verify-report changed))
        (is (not= (:three-member-authority-report/root report)
                  (:three-member-authority-report/root changed)))))
    (testing "wrong nested variant and malformed reason are rejected"
      (is (false? (sut/verify-report
                   (reseal (assoc-in report [:valid-supporting-positions 0 :position/schema]
                                     "three-member-authority-report-position.v1")))))
      (let [invalid (sut/build-report
                     (evaluator-report [(decision "a" outcome-root)
                                        (decision "b" outcome-root)
                                        (assoc (decision "c" outcome-root) :decision/hash (root "f"))]))]
        (is (seq (:invalid-position-reasons invalid)))
        (is (false? (sut/verify-report
                     (reseal (assoc-in invalid [:invalid-position-reasons 0 :reason] :other)))))))))

(deftest equivocation-and-duplicate-projections-preserve-evaluator-order
  (let [a (decision "a" outcome-root)
        duplicate (assoc-in a [:signature :signed-at] "9999-01-01T00:00:00Z")
        dissent (with-redefs [signing/sign-hash (fn [_ _ _] signature)]
                  (rfa/build-signed-decision-v2 "b" :authority/test request-root round-root
                                                outcome-root :dissent "/dev/null"
                                                :dissent-reason "no"))
        report (sut/build-report (evaluator-report [a duplicate (decision "b" outcome-root)
                                                    dissent (decision "c" outcome-root)]))]
    (is (sut/verify-report report))
    (is (= 1 (count (:duplicate-seat-positions report))))
    (is (= 1 (count (:equivocating-members report))))
    (is (= ["b"] (mapv :member/id (:equivocating-members report))))))
